package app.veil.camera.privacy

import android.graphics.Bitmap
import android.media.Image
import app.veil.privacy.FaceGeometry
import app.veil.privacy.FaceRegion
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

/**
 * Locates faces with on-device ML Kit and converts each detection into the
 * smallest ellipse that safely covers the face. ML Kit only answers "where is
 * a face"; no identity, embedding or tracking information is ever derived.
 */
class FaceRegionDetector private constructor(private val detector: FaceDetector) : AutoCloseable {

    companion object {
        /** High quality detection used for the captured photograph. */
        fun forCapture(): FaceRegionDetector = FaceRegionDetector(
            FaceDetection.getClient(
                FaceDetectorOptions.Builder()
                    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                    .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                    .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                    .setMinFaceSize(0.035f)
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
        )

        fun toRegion(face: Face): FaceRegion {
            val box = face.boundingBox
            val contour = face.getContour(FaceContour.FACE)?.points.orEmpty()
                .map { PointF2(it.x, it.y) }
            return FaceGeometry.bestRegion(
                left = box.left.toFloat(),
                top = box.top.toFloat(),
                right = box.right.toFloat(),
                bottom = box.bottom.toFloat(),
                rotationDegrees = face.headEulerAngleZ.let { -it },
                contour = contour,
            )
        }
    }

    suspend fun detect(bitmap: Bitmap): List<FaceRegion> =
        process(InputImage.fromBitmap(bitmap, 0))

    suspend fun detect(image: Image, rotationDegrees: Int): List<FaceRegion> =
        process(InputImage.fromMediaImage(image, rotationDegrees))

    private suspend fun process(input: InputImage): List<FaceRegion> =
        suspendCancellableCoroutine { cont ->
            detector.process(input)
                .addOnSuccessListener { faces -> cont.resume(faces.map { toRegion(it) }) }
                .addOnFailureListener { error -> cont.resumeWithException(error) }
        }

    override fun close() = detector.close()
}
