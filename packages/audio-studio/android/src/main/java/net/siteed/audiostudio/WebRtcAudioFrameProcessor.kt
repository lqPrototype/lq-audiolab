package net.siteed.audiostudio

import com.aozora.audio.AozoraWebRtcApm

/** Owns one native WebRTC APM instance and its 10 ms capture/render framing. */
internal class WebRtcAudioFrameProcessor private constructor(
    private var handle: Long,
    val request: AudioProcessingRequest,
    sampleRate: Int,
) {
    private val frameBytes = sampleRate / 100 * 2
    private val captureFrames = Pcm16FrameAccumulator(frameBytes)
    private val reverseFrames = Pcm16FrameAccumulator(frameBytes)

    @Volatile
    var reverseStreamConnected: Boolean = false
        private set

    @Volatile
    var operational: Boolean = true
        private set

    @Synchronized
    fun processCapture(data: ByteArray, length: Int): ByteArray {
        if (handle == 0L || !operational) return data.copyOf(length)
        return captureFrames.consume(data, length) { processFrame(it, reverse = false) }
    }

    @Synchronized
    fun processReverse(data: ByteArray, length: Int, delayMs: Int): Boolean {
        if (handle == 0L || !operational || !request.echoCancellation) return false
        AozoraWebRtcApm.nativeSetStreamDelay(handle, delayMs.coerceIn(0, 500))
        reverseFrames.consume(data, length) { processFrame(it, reverse = true) }
        return reverseStreamConnected
    }

    @Synchronized
    fun flushCapture(): ByteArray = captureFrames.drain()

    @Synchronized
    fun release() {
        val current = handle
        handle = 0L
        operational = false
        reverseStreamConnected = false
        if (current != 0L) AozoraWebRtcApm.nativeRelease(current)
    }

    private fun processFrame(frame: ByteArray, reverse: Boolean): ByteArray {
        val samples = ShortArray(frame.size / 2)
        for (index in samples.indices) {
            val offset = index * 2
            samples[index] = (((frame[offset + 1].toInt() and 0xff) shl 8) or
                (frame[offset].toInt() and 0xff)).toShort()
        }
        val status = if (reverse) {
            AozoraWebRtcApm.nativeProcessReverse(handle, samples)
        } else {
            AozoraWebRtcApm.nativeProcessCapture(handle, samples)
        }
        if (status != 0 && status != WEBRTC_BAD_STREAM_PARAMETER_WARNING) {
            operational = false
            LogUtils.e(CLASS_NAME, "WebRTC APM ${if (reverse) "reverse" else "capture"} failed: $status")
            return frame
        }
        if (reverse) reverseStreamConnected = true

        val output = ByteArray(frame.size)
        for (index in samples.indices) {
            val value = samples[index].toInt()
            output[index * 2] = (value and 0xff).toByte()
            output[index * 2 + 1] = ((value ushr 8) and 0xff).toByte()
        }
        return output
    }

    companion object {
        private const val CLASS_NAME = "WebRtcAudioFrameProcessor"
        private const val WEBRTC_BAD_STREAM_PARAMETER_WARNING = -13
        private val SUPPORTED_SAMPLE_RATES = setOf(8000, 16000, 32000, 48000)

        fun create(
            sampleRate: Int,
            channels: Int,
            encoding: String,
            request: AudioProcessingRequest,
        ): WebRtcAudioFrameProcessor? {
            if (!request.anyEnabled) return null
            if (sampleRate !in SUPPORTED_SAMPLE_RATES || channels != 1 || encoding != "pcm_16bit") {
                LogUtils.w(
                    CLASS_NAME,
                    "WebRTC APM requires mono PCM16 at 8/16/32/48 kHz; got $sampleRate Hz, $channels channel(s), $encoding"
                )
                return null
            }
            if (!AozoraWebRtcApm.isAvailable()) {
                LogUtils.w(CLASS_NAME, "WebRTC APM native library is unavailable for this ABI")
                return null
            }
            val handle = AozoraWebRtcApm.nativeCreate(
                sampleRate,
                channels,
                request.echoCancellation,
                request.noiseSuppression,
                request.automaticGainControl,
            )
            if (handle == 0L) {
                LogUtils.w(CLASS_NAME, "WebRTC APM native processor creation failed")
                return null
            }
            LogUtils.i(
                CLASS_NAME,
                "WebRTC APM enabled (AEC=${request.echoCancellation}, NS=${request.noiseSuppression}, AGC=${request.automaticGainControl})"
            )
            return WebRtcAudioFrameProcessor(handle, request, sampleRate)
        }
    }
}
