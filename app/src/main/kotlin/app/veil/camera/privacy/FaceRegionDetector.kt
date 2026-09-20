package app.veil.camera.privacy

import android.graphics.Bitmap
import android.graphics.Rect
import android.media.Image
import app.veil.privacy.FaceGeometry
import app.veil.privacy.FaceRegion
import app.veil.privacy.FaceRegions
import app.veil.privacy.PointF2
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceContour
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Locates faces with on-device ML Kit and converts each detection into the
 * smallest ellipse that safely covers the face. ML Kit only answers "where is
 * a face"; no identity, embedding or tracking information is ever derived.
 *
 * A captured photograph is scanned in three stages:
 *  1. the whole frame, downscaled - ML Kit recalls more faces at ~1600px than
 *     at 12MP and is an order of magnitude faster there;
 *  2. overlapping tiles of the full resolution frame, which is what makes
 *     small, distant faces detectable at all;
 *  3. contour detection on each surviving face crop, giving a tight rotated
 *     outline. Contours cannot be requested for the whole frame: in that mode
 *     ML Kit returns only the most prominent face.
 */
class FaceRegionDetector private constructor(
    private val detector: FaceDetector,
    private val contourDetector: FaceDetector?,
) : AutoCloseable {

    companion object {
        private const val DETECTION_MAX_DIMENSION = 1600
        private const val CONTOUR_MAX_DIMENSION = 512
        private const val CONTOUR_CROP_MARGIN = 0.45f
        private const val TILE_OVERLAP = 0.2f

        /** High quality detection used for the captured photograph. */
        fun forCapture(): FaceRegionDetector = FaceRegionDetector(
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.03f)
                    .build(),
            ),
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setMinFaceSize(0.25f)
                    .build(),
            ),
        )

        /** Cheap detection used for the live preview overlay. */
        fun forPreview(): FaceRegionDetector = FaceRegionDetector(
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.08f)
                    .build(),
            ),
            contourDetector = null,
        )

        fun toRegion(face: Face, contour: List<PointF2> = emptyList()): FaceRegion {
            val box = face.boundingBox
            return FaceGeometry.bestRegion(
                left = box.left.toFloat(),
                top = box.top.toFloat(),
                right = box.right.toFloat(),
                bottom = box.bottom.toFloat(),
                rotationDegrees = -face.headEulerAngleZ,
                contour = contour,
            )
        }
    }

    suspend fun detect(bitmap: Bitmap): List<FaceRegion> {
        val found = mutableListOf<FaceRegion>()
        for (window in windows(bitmap.width, bitmap.height)) {
            found += scan(bitmap, window)
        }
        return FaceRegions.deduplicate(found).map { region -> refine(bitmap, region) }
    }

    suspend fun detect(image: Image, rotationDegrees: Int): List<FaceRegion> =
        process(detector, InputImage.fromMediaImage(image, rotationDegrees)).map { toRegion(it) }

    /** Whole frame first, then overlapping quadrants for small faces. */
    private fun windows(width: Int, height: Int): List<Rect> {
        val whole = Rect(0, 0, width, height)
        if (max(width, height) <= DETECTION_MAX_DIMENSION) return listOf(whole)
        val tileW = (width * (0.5f + TILE_OVERLAP / 2f)).roundToInt()
        val tileH = (height * (0.5f + TILE_OVERLAP / 2f)).roundToInt()
        return listOf(
            whole,
            Rect(0, 0, tileW, tileH),
            Rect(width - tileW, 0, width, tileH),
            Rect(0, height - tileH, tileW, height),
            Rect(width - tileW, height - tileH, width, height),
        )
    }

    private suspend fun scan(source: Bitmap, window: Rect): List<FaceRegion> {
        val crop = cropped(source, window) ?: return emptyList()
        val scale = downscale(crop.width, crop.height, DETECTION_MAX_DIMENSION)
        val input = resized(crop, scale)
        val faces = try {
            process(detector, InputImage.fromBitmap(input, 0))
        } finally {
            if (input !== crop) input.recycle()
            if (crop !== source) crop.recycle()
        }
        return faces.map { face ->
            toRegion(face).scaledBy(1f / scale).translatedBy(window.left.toFloat(), window.top.toFloat())
        }
    }

    /**
     * Re-runs detection with contours on a crop around [region] so the ellipse
     * follows the real outline instead of the bounding box.
     */
    private suspend fun refine(source: Bitmap, region: FaceRegion): FaceRegion {
        val contourDetector = contourDetector ?: return region
        val bounds = region.bounds(pad = CONTOUR_CROP_MARGIN * max(region.radiusX, region.radiusY))
        val window = Rect(
            bounds.left.coerceIn(0, source.width - 1),
            bounds.top.coerceIn(0, source.height - 1),
            bounds.right.coerceIn(1, source.width),
            bounds.bottom.coerceIn(1, source.height),
        )
        if (window.width() < 24 || window.height() < 24) return region
        val crop = cropped(source, window) ?: return region
        val scale = downscale(crop.width, crop.height, CONTOUR_MAX_DIMENSION)
        val input = resized(crop, scale)

        val face = try {
            process(contourDetector, InputImage.fromBitmap(input, 0)).firstOrNull()
        } catch (t: Throwable) {
            null
        } finally {
            if (input !== crop) input.recycle()
            if (crop !== source) crop.recycle()
        }
        val points = face?.getContour(FaceContour.FACE)?.points.orEmpty().map {
            PointF2(it.x / scale + window.left, it.y / scale + window.top)
        }
        if (points.size < 5) return region
        val fitted = FaceGeometry.fromContour(points, face?.headEulerAngleZ?.let { -it } ?: 0f)
            ?: return region
        // Never let a partial contour shrink the protected area.
        return fitted.copy(
            radiusX = max(fitted.radiusX, region.radiusX * 0.8f),
            radiusY = max(fitted.radiusY, region.radiusY * 0.8f),
        )
    }

    private fun cropped(source: Bitmap, window: Rect): Bitmap? {
        if (window.left == 0 && window.top == 0 &&
            window.width() == source.width && window.height() == source.height
        ) {
            return source
        }
        return runCatching {
            Bitmap.createBitmap(source, window.left, window.top, window.width(), window.height())
        }.getOrNull()
    }

    private fun resized(bitmap: Bitmap, scale: Float): Bitmap =
        if (scale >= 1f) {
            bitmap
        } else {
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true,
            )
        }

    private fun downscale(width: Int, height: Int, limit: Int): Float {
        val longest = max(width, height)
        return if (longest <= limit) 1f else limit.toFloat() / longest
    }

    private suspend fun process(detector: FaceDetector, input: InputImage): List<Face> =
        suspendCancellableCoroutine { cont ->
            detector.process(input)
                .addOnSuccessListener { faces -> cont.resume(faces) }
                .addOnFailureListener { error -> cont.resumeWithException(error) }
        }

    override fun close() {
        detector.close()
        contourDetector?.close()
    }
}

private fun FaceRegion.scaledBy(factor: Float): FaceRegion =
    if (factor == 1f) {
        this
    } else {
        copy(
            centerX = centerX * factor,
            centerY = centerY * factor,
            radiusX = radiusX * factor,
            radiusY = radiusY * factor,
        )
    }

private fun FaceRegion.translatedBy(dx: Float, dy: Float): FaceRegion =
    if (dx == 0f && dy == 0f) this else copy(centerX = centerX + dx, centerY = centerY + dy)
