package app.veil.camera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.veil.camera.privacy.FaceRegionDetector
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DetectorDebugTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun logDetections(): Unit = runBlocking {
        val dir = context.getExternalFilesDir(null)
        val files = dir?.listFiles()?.filter { it.name.endsWith(".jpg") }.orEmpty()
        Log.i("VeilDebug", "files=${files.map { it.name }}")
        val variants = listOf(0.035f, 0.08f, 0.15f)
        val modes = listOf(
            com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_FAST to "fast",
            com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE to "accurate",
        )
        for ((mode, modeName) in modes) {
            for (minSize in variants) {
                val det = com.google.mlkit.vision.face.FaceDetection.getClient(
                    com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                        .setPerformanceMode(mode)
                        .setMinFaceSize(minSize)
                        .build(),
                )
                files.forEach { f: File ->
                    val bmp: Bitmap = BitmapFactory.decodeFile(f.absolutePath)
                    val n = kotlinx.coroutines.suspendCancellableCoroutine<Int> { c ->
                        det.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))
                            .addOnSuccessListener { c.resumeWith(Result.success(it.size)) }
                            .addOnFailureListener { c.resumeWith(Result.success(-1)) }
                    }
                    Log.i("VeilDebug", "$modeName min=$minSize ${f.name} ${bmp.width}x${bmp.height} faces=$n")
                }
                det.close()
            }
        }
    }
}
