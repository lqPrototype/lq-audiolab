package net.siteed.audiostudio

internal enum class AudioProcessingBackend(val wireValue: String) {
    DISABLED("disabled"),
    SYSTEM("system"),
    WEBRTC("webrtc"),
    UNAVAILABLE("unavailable"),
}

internal data class AudioProcessingRequest(
    val echoCancellation: Boolean,
    val noiseSuppression: Boolean,
    val automaticGainControl: Boolean,
) {
    val anyEnabled: Boolean
        get() = echoCancellation || noiseSuppression || automaticGainControl

    companion object {
        fun from(config: AndroidAudioProcessingConfig) = AudioProcessingRequest(
            echoCancellation = config.echoCancellation,
            noiseSuppression = config.noiseSuppression,
            automaticGainControl = config.automaticGainControl,
        )
    }
}

internal data class SystemAudioProcessingStatus(
    val aec: Boolean = false,
    val noiseSuppression: Boolean = false,
    val automaticGainControl: Boolean = false,
)

internal data class AudioProcessingPlan(
    val system: AudioProcessingRequest,
    val software: AudioProcessingRequest,
) {
    companion object {
        fun create(
            config: AndroidAudioProcessingConfig,
            systemStatus: SystemAudioProcessingStatus,
        ): AudioProcessingPlan {
            val requested = AudioProcessingRequest.from(config)
            val system = when (config.mode) {
                AndroidAudioProcessingMode.SOFTWARE -> AudioProcessingRequest(false, false, false)
                else -> requested
            }
            val software = when (config.mode) {
                AndroidAudioProcessingMode.SYSTEM -> AudioProcessingRequest(false, false, false)
                AndroidAudioProcessingMode.SOFTWARE -> requested
                AndroidAudioProcessingMode.SYSTEM_WITH_SOFTWARE_FALLBACK -> AudioProcessingRequest(
                    echoCancellation = requested.echoCancellation && !systemStatus.aec,
                    noiseSuppression = requested.noiseSuppression && !systemStatus.noiseSuppression,
                    automaticGainControl = requested.automaticGainControl && !systemStatus.automaticGainControl,
                )
            }
            return AudioProcessingPlan(system = system, software = software)
        }
    }
}

internal data class AudioProcessingStatus(
    val mode: String,
    val aec: AudioProcessingBackend,
    val noiseSuppression: AudioProcessingBackend,
    val automaticGainControl: AudioProcessingBackend,
    val reverseStreamConnected: Boolean,
    val sampleRate: Int,
    val channels: Int,
    val frameDurationMs: Int = 10,
    val compressedOutputProcessed: Boolean = false,
) {
    fun toMap(): Map<String, Any> = mapOf(
        "mode" to mode,
        "aec" to aec.wireValue,
        "noiseSuppression" to noiseSuppression.wireValue,
        "automaticGainControl" to automaticGainControl.wireValue,
        "reverseStreamConnected" to reverseStreamConnected,
        "sampleRate" to sampleRate,
        "channels" to channels,
        "frameDurationMs" to frameDurationMs,
        "compressedOutputProcessed" to compressedOutputProcessed,
    )

    companion object {
        fun disabled(sampleRate: Int = 0, channels: Int = 0) = AudioProcessingStatus(
            mode = "disabled",
            aec = AudioProcessingBackend.DISABLED,
            noiseSuppression = AudioProcessingBackend.DISABLED,
            automaticGainControl = AudioProcessingBackend.DISABLED,
            reverseStreamConnected = false,
            sampleRate = sampleRate,
            channels = channels,
        )
    }
}
