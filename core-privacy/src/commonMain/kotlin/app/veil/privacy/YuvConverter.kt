package app.veil.privacy

/**
 * Converts protected ARGB frames to planar I420, the input format video
 * encoders accept. Kept in the pure Kotlin core so the colour maths can be
 * unit tested without a device.
 */
object YuvConverter {

    /**
     * Writes BT.601 limited range luma into [y] and chroma into [u] / [v],
     * each chroma plane holding one sample per 2x2 block of pixels.
     */
    fun toI420(
        pixels: IntArray,
        width: Int,
        height: Int,
        y: ByteArray,
        u: ByteArray,
        v: ByteArray,
    ) {
        require(width > 0 && height > 0) { "empty frame" }
        require(pixels.size >= width * height) { "pixel buffer too small" }
        val chromaWidth = (width + 1) / 2
        var yIndex = 0
        for (row in 0 until height) {
            val rowStart = row * width
            for (col in 0 until width) {
                val argb = pixels[rowStart + col]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                y[yIndex++] = clamp((66 * r + 129 * g + 25 * b + 128 shr 8) + 16)
                if (row % 2 == 0 && col % 2 == 0) {
                    val chromaIndex = (row / 2) * chromaWidth + (col / 2)
                    u[chromaIndex] = clamp((-38 * r - 74 * g + 112 * b + 128 shr 8) + 128)
                    v[chromaIndex] = clamp((112 * r - 94 * g - 18 * b + 128 shr 8) + 128)
                }
            }
        }
    }

    private fun clamp(value: Int): Byte = when {
        value < 0 -> 0
        value > 255 -> 255.toByte()
        else -> value.toByte()
    }
}
