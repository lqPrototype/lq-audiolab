package net.siteed.audiostudio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Physical Bluetooth identity survives SCO endpoint removal/recreation. */
internal object AudioInputRoutes {
    const val BLUETOOTH_PREFIX = "bluetooth:"

    fun resolve(manager: AudioManager, id: String?): AudioDeviceInfo? {
        val inputs = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (id?.startsWith(BLUETOOTH_PREFIX) == true) {
            val address = id.removePrefix(BLUETOOTH_PREFIX)
            val candidates = inputs.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            return candidates.firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?: candidates.singleOrNull()
        }
        return inputs.firstOrNull { it.id.toString() == id }
    }
}
