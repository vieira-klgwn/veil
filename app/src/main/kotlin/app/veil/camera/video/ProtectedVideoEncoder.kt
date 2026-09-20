package app.veil.camera.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import app.veil.privacy.YuvConverter
import java.io.File
import java.nio.ByteBuffer

/**
 * Encodes already anonymized frames into an MP4. Frames are pushed in buffer
 * mode rather than through a camera surface on purpose: a surface recording
 * would capture the raw camera stream, so the unprotected faces would end up
 * in the file. Nothing reaches the muxer that has not been through the
 * privacy pipeline first.
 */
class ProtectedVideoEncoder(
    val file: File,
    val width: Int,
    val height: Int,
    private val frameRate: Int = 24,
) : AutoCloseable {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val bufferInfo = MediaCodec.BufferInfo()
    private var trackIndex = -1
    private var muxing = false
    private var frames = 0
    private var closed = false
    private var startNanos = -1L
    private var lastPtsUs = 0L

    private val luma = ByteArray(width * height)
    private val chromaU = ByteArray(((width + 1) / 2) * ((height + 1) / 2))
    private val chromaV = ByteArray(chromaU.size)
    private val pixels = IntArray(width * height)
    private val frameSizeBytes = luma.size + chromaU.size + chromaV.size

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitRateFor(width, height))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(MIME)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    /** Frame count accepted so far; the recording duration derives from it. */
    val frameCount: Int get() = frames

    /**
     * Queues one protected frame. Returns false when the encoder has no free
     * input buffer, so the caller can drop the frame instead of stalling the
     * camera.
     */
    fun encode(bitmap: Bitmap, timestampNanos: Long): Boolean {
        check(!closed) { "encoder already finished" }
        var index = codec.dequeueInputBuffer(0)
        if (index < 0) {
            // Freeing output buffers usually releases an input buffer straight
            // away; only give up when the encoder really is behind.
            drain(endOfStream = false)
            index = codec.dequeueInputBuffer(INPUT_RETRY_US)
        }
        if (index < 0) return false
        val image = codec.getInputImage(index)
        if (image == null) {
            codec.queueInputBuffer(index, 0, 0, lastPtsUs, 0)
            return false
        }
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        YuvConverter.toI420(pixels, width, height, luma, chromaU, chromaV)
        writePlane(image.planes[0].buffer, image.planes[0].rowStride, image.planes[0].pixelStride, luma, width, height)
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        writePlane(image.planes[1].buffer, image.planes[1].rowStride, image.planes[1].pixelStride, chromaU, chromaWidth, chromaHeight)
        writePlane(image.planes[2].buffer, image.planes[2].rowStride, image.planes[2].pixelStride, chromaV, chromaWidth, chromaHeight)
        lastPtsUs = presentationTimeUs(timestampNanos)
        codec.queueInputBuffer(index, 0, frameSizeBytes, lastPtsUs, 0)
        frames++
        drain(endOfStream = false)
        return true
    }

    /** Flushes the encoder and finishes the MP4. */
    fun finish(): File {
        check(!closed) { "encoder already finished" }
        var index = -1
        var attempts = 0
        while (index < 0 && attempts < EOS_ATTEMPTS) {
            index = codec.dequeueInputBuffer(FLUSH_TIMEOUT_US)
            if (index < 0) drain(endOfStream = false)
            attempts++
        }
        if (index >= 0) {
            codec.queueInputBuffer(
                index,
                0,
                0,
                lastPtsUs + 1,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
        }
        drain(endOfStream = true)
        close()
        return file
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxing) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drain(endOfStream: Boolean) {
        var idleRounds = 0
        while (true) {
            val status = codec.dequeueOutputBuffer(bufferInfo, if (endOfStream) FLUSH_TIMEOUT_US else 0)
            when {
                status == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream || ++idleRounds > EOS_ATTEMPTS) return
                }
                status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                status >= 0 -> {
                    val encoded: ByteBuffer? = codec.getOutputBuffer(status)
                    val isConfig = bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encoded != null && muxing && !isConfig && bufferInfo.size > 0) {
                        encoded.position(bufferInfo.offset)
                        encoded.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(trackIndex, encoded, bufferInfo)
                    }
                    codec.releaseOutputBuffer(status, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Wall clock timestamps keep playback speed honest when frames drop. */
    private fun presentationTimeUs(timestampNanos: Long): Long {
        if (startNanos < 0) startNanos = timestampNanos
        return ((timestampNanos - startNanos) / 1_000L).coerceAtLeast(lastPtsUs + 1)
    }

    private fun writePlane(
        destination: ByteBuffer,
        rowStride: Int,
        pixelStride: Int,
        source: ByteArray,
        planeWidth: Int,
        planeHeight: Int,
    ) {
        destination.clear()
        if (pixelStride == 1 && rowStride == planeWidth) {
            destination.put(source, 0, planeWidth * planeHeight)
            return
        }
        for (row in 0 until planeHeight) {
            var offset = row * rowStride
            val sourceRow = row * planeWidth
            for (col in 0 until planeWidth) {
                destination.put(offset, source[sourceRow + col])
                offset += pixelStride
            }
        }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val FLUSH_TIMEOUT_US = 20_000L
        const val INPUT_RETRY_US = 8_000L
        const val EOS_ATTEMPTS = 100

        fun bitRateFor(width: Int, height: Int): Int = (width * height * 5).coerceAtLeast(2_000_000)
    }
}
