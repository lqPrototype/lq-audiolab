package net.siteed.audiostudio

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the source and lifecycle wiring for explicit Android 3A processing. */
class VoiceProcessingWiringTest {

    private fun sourceFile(name: String): String {
        val candidates = listOf(
            "src/main/java/net/siteed/audiostudio/$name",
            "android/src/main/java/net/siteed/audiostudio/$name",
            "packages/audio-studio/android/src/main/java/net/siteed/audiostudio/$name",
        )
        return candidates.map(::File).firstOrNull { it.exists() }?.readText()
            ?: error("$name not found relative to ${File(".").absolutePath}")
    }

    @Test
    fun `voice communication resolves to the Android communication source`() {
        val resolver = sourceFile("AudioSourceResolver.kt")

        assertTrue(resolver.contains("\"voiceCommunication\" -> Resolution("))
        assertTrue(resolver.contains("MediaRecorder.AudioSource.VOICE_COMMUNICATION"))
    }

    @Test
    fun `voice communication explicitly requests every platform 3A effect`() {
        val effects = sourceFile("VoiceProcessingEffects.kt")

        assertTrue(effects.contains("AcousticEchoCanceler.isAvailable()"))
        assertTrue(effects.contains("AcousticEchoCanceler.create(audioSessionId)"))
        assertTrue(effects.contains("NoiseSuppressor.isAvailable()"))
        assertTrue(effects.contains("NoiseSuppressor.create(audioSessionId)"))
        assertTrue(effects.contains("AutomaticGainControl.isAvailable()"))
        assertTrue(effects.contains("AutomaticGainControl.create(audioSessionId)"))
        assertTrue(effects.contains("effect.setEnabled(true)"))
    }

    @Test
    fun `effects follow every rebuilt AudioRecord session`() {
        val manager = sourceFile("AudioRecorderManager.kt")

        assertTrue(manager.contains("currentSource.name == \"voiceCommunication\""))
        assertTrue(manager.contains("VoiceProcessingEffects.attach("))
        assertTrue(manager.contains("checkNotNull(audioRecord).audioSessionId"))
        assertTrue(manager.contains("releaseVoiceProcessingEffects()"))
    }
}
