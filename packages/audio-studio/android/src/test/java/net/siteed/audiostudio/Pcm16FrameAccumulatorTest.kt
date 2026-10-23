package net.siteed.audiostudio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16FrameAccumulatorTest {
    @Test
    fun `split reads become exact frames and preserve the remainder`() {
        val accumulator = Pcm16FrameAccumulator(frameBytes = 8)
        val input = ByteArray(13) { it.toByte() }

        assertEquals(0, accumulator.consume(input, 3) { it }.size)
        assertArrayEquals(
            input.copyOfRange(0, 8),
            accumulator.consume(input.copyOfRange(3, 11), 8) { it },
        )
        assertEquals(0, accumulator.consume(input.copyOfRange(11, 13), 2) { it }.size)
        assertArrayEquals(input.copyOfRange(8, 13), accumulator.drain())
    }

    @Test
    fun `transform is applied once per complete frame`() {
        val accumulator = Pcm16FrameAccumulator(frameBytes = 4)
        var calls = 0
        val output = accumulator.consume(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), 8) { frame ->
            calls++
            frame.reversedArray()
        }

        assertEquals(2, calls)
        assertArrayEquals(byteArrayOf(4, 3, 2, 1, 8, 7, 6, 5), output)
    }
}
