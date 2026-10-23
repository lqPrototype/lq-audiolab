package net.siteed.audiostudio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioProcessingPlanTest {
    @Test
    fun `hybrid mode only falls back for effects the system did not enable`() {
        val plan = AudioProcessingPlan.create(
            config = AndroidAudioProcessingConfig(),
            systemStatus = SystemAudioProcessingStatus(
                aec = true,
                noiseSuppression = false,
                automaticGainControl = true,
            ),
        )

        assertFalse(plan.software.echoCancellation)
        assertTrue(plan.software.noiseSuppression)
        assertFalse(plan.software.automaticGainControl)
    }

    @Test
    fun `software mode never requests duplicate platform effects`() {
        val plan = AudioProcessingPlan.create(
            config = AndroidAudioProcessingConfig(mode = AndroidAudioProcessingMode.SOFTWARE),
            systemStatus = SystemAudioProcessingStatus(),
        )

        assertFalse(plan.system.anyEnabled)
        assertTrue(plan.software.echoCancellation)
        assertTrue(plan.software.noiseSuppression)
        assertTrue(plan.software.automaticGainControl)
    }
}
