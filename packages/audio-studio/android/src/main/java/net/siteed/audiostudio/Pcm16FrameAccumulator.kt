package net.siteed.audiostudio

import java.io.ByteArrayOutputStream

/** Converts arbitrary PCM16 read sizes into exact fixed-size frames without reordering data. */
internal class Pcm16FrameAccumulator(private val frameBytes: Int) {
    init {
        require(frameBytes > 0 && frameBytes % 2 == 0) { "PCM16 frame size must be positive and even" }
    }

    private var pending = ByteArray(0)

    @Synchronized
    fun consume(
        data: ByteArray,
        length: Int,
        transform: (ByteArray) -> ByteArray,
    ): ByteArray {
        require(length in 0..data.size) { "Invalid PCM byte count" }
        if (length == 0) return ByteArray(0)

        val combined = ByteArray(pending.size + length)
        pending.copyInto(combined)
        data.copyInto(combined, destinationOffset = pending.size, endIndex = length)

        val completeBytes = combined.size - (combined.size % frameBytes)
        if (completeBytes == 0) {
            pending = combined
            return ByteArray(0)
        }

        val output = ByteArrayOutputStream(completeBytes)
        var offset = 0
        while (offset < completeBytes) {
            val frame = combined.copyOfRange(offset, offset + frameBytes)
            val transformed = transform(frame)
            require(transformed.size == frameBytes) { "PCM frame transform changed frame size" }
            output.write(transformed)
            offset += frameBytes
        }
        pending = combined.copyOfRange(completeBytes, combined.size)
        return output.toByteArray()
    }

    @Synchronized
    fun drain(): ByteArray = pending.also { pending = ByteArray(0) }
}
