package net.siteed.audiostudio

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * Owns Android's platform voice-processing effects for one AudioRecord session.
 *
 * VOICE_COMMUNICATION asks the audio HAL for its communication capture path, but it does
 * not prove that AEC, NS, or AGC was attached. Creating each available AudioEffect against
 * the AudioRecord session makes that request explicit and gives logcat verifiable evidence
 * of the implementation and enabled state. OEMs still decide which effects exist and may
 * reject an enable request, so every effect is independent and best-effort.
 */
internal class VoiceProcessingEffects private constructor(
    private val effects: List<AudioEffect>,
) {
    fun release() {
        effects.asReversed().forEach { effect ->
            try {
                effect.release()
            } catch (e: Exception) {
                LogUtils.w(CLASS_NAME, "Failed to release ${effectName(effect)}: ${e.message}")
            }
        }
    }

    companion object {
        private const val CLASS_NAME = "VoiceProcessingEffects"

        fun attach(audioSessionId: Int): VoiceProcessingEffects {
            val attached = mutableListOf<AudioEffect>()

            attachEffect(
                label = "AEC",
                available = AcousticEchoCanceler.isAvailable(),
                create = { AcousticEchoCanceler.create(audioSessionId) },
            )?.let(attached::add)
            attachEffect(
                label = "ANS",
                available = NoiseSuppressor.isAvailable(),
                create = { NoiseSuppressor.create(audioSessionId) },
            )?.let(attached::add)
            attachEffect(
                label = "AGC",
                available = AutomaticGainControl.isAvailable(),
                create = { AutomaticGainControl.create(audioSessionId) },
            )?.let(attached::add)

            LogUtils.i(
                CLASS_NAME,
                "3A session=$audioSessionId attached=${attached.size}/3 " +
                    "(AEC=${hasType(attached, AcousticEchoCanceler::class.java)}, " +
                    "ANS=${hasType(attached, NoiseSuppressor::class.java)}, " +
                    "AGC=${hasType(attached, AutomaticGainControl::class.java)})"
            )
            return VoiceProcessingEffects(attached)
        }

        private fun attachEffect(
            label: String,
            available: Boolean,
            create: () -> AudioEffect?,
        ): AudioEffect? {
            if (!available) {
                LogUtils.i(CLASS_NAME, "$label unavailable on this device")
                return null
            }

            val effect = try {
                create()
            } catch (e: Exception) {
                LogUtils.w(CLASS_NAME, "$label creation failed: ${e.message}")
                null
            }
            if (effect == null) {
                LogUtils.w(CLASS_NAME, "$label is reported available but could not be created")
                return null
            }

            return try {
                val enableStatus = effect.setEnabled(true)
                val enabled = effect.enabled
                val descriptor = effect.descriptor
                val implementation = listOf(descriptor.implementor, descriptor.name)
                    .filter { it.isNotBlank() }
                    .joinToString(" / ")
                    .ifBlank { "unknown" }

                if (enableStatus == AudioEffect.SUCCESS && enabled) {
                    LogUtils.i(
                        CLASS_NAME,
                        "$label enabled=true status=$enableStatus implementation=$implementation"
                    )
                    effect
                } else {
                    LogUtils.w(
                        CLASS_NAME,
                        "$label enable rejected: enabled=$enabled status=$enableStatus " +
                            "implementation=$implementation"
                    )
                    effect.release()
                    null
                }
            } catch (e: Exception) {
                LogUtils.w(CLASS_NAME, "$label enable failed: ${e.message}")
                try {
                    effect.release()
                } catch (_: Exception) {
                    // The original enable failure is the useful diagnostic.
                }
                null
            }
        }

        private fun hasType(effects: List<AudioEffect>, type: Class<out AudioEffect>): Boolean =
            effects.any(type::isInstance)

        private fun effectName(effect: AudioEffect): String = when (effect) {
            is AcousticEchoCanceler -> "AEC"
            is NoiseSuppressor -> "ANS"
            is AutomaticGainControl -> "AGC"
            else -> effect.javaClass.simpleName
        }
    }
}
