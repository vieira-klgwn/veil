package app.veil.camera.privacy

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes protected photographs to the shared gallery. Location metadata is
 * never written: the app only ever stores the anonymized image.
 */
object PhotoStore {

    private const val ALBUM = "Veil"
    private const val JPEG_QUALITY = 96

    suspend fun saveToGallery(context: Context, bitmap: Bitmap): Uri = withContext(Dispatchers.IO) {
        val name = "VEIL_${timestamp()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.WIDTH, bitmap.width)
            put(MediaStore.Images.Media.HEIGHT, bitmap.height)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$ALBUM")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("gallery rejected the new photo")
            try {
                resolver.openOutputStream(uri)?.use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                        error("photo could not be encoded")
                    }
                } ?: error("gallery returned no output stream")
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), ALBUM)
            if (!dir.exists() && !dir.mkdirs()) error("cannot create album folder")
            val file = File(dir, name)
            FileOutputStream(file).use { out ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    error("photo could not be encoded")
                }
            }
            values.put(MediaStore.Images.Media.DATA, file.absolutePath)
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: Uri.fromFile(file)
        }
    }

    /** Copies the protected image into the app cache so it can be shared. */
    suspend fun shareIntent(context: Context, bitmap: Bitmap): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "veil_${timestamp()}.jpg")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out) }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** Moves a finished protected recording into Movies/Veil. */
    suspend fun saveVideoToGallery(context: Context, source: File): Uri = withContext(Dispatchers.IO) {
        val name = "VEIL_${timestamp()}.mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, VIDEO_MIME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/$ALBUM")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                ?: error("gallery rejected the new video")
            try {
                resolver.openOutputStream(uri)?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: error("gallery returned no output stream")
            } catch (t: Throwable) {
                resolver.delete(uri, null, null)
                throw t
            }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            source.delete()
            uri
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), ALBUM)
            if (!dir.exists() && !dir.mkdirs()) error("cannot create album folder")
            val file = File(dir, name)
            source.inputStream().use { input -> FileOutputStream(file).use { input.copyTo(it) } }
            source.delete()
            values.put(MediaStore.Video.Media.DATA, file.absolutePath)
            resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: Uri.fromFile(file)
        }
    }

    /** Shares a saved recording straight from its gallery entry. */
    fun shareVideoIntent(uri: Uri): Intent = Intent(Intent.ACTION_SEND).apply {
        type = VIDEO_MIME
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private const val VIDEO_MIME = "video/mp4"

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
}
