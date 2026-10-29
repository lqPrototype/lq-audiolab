package net.siteed.audiostudio

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.AudioDeviceInfo
import android.media.AudioDeviceCallback
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.annotation.RequiresApi
import expo.modules.kotlin.Promise
import net.siteed.audiostudio.LogUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Constants not available in all Android versions
 */
private const val ACTION_CONNECTION_STATE_CHANGED = "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED"

/**
 * Interface for handling audio device disconnection events
 */
interface AudioDeviceManagerDelegate {
    fun onDeviceDisconnected(deviceId: String)
}

/**
 * Manages audio device detection, selection and capabilities for Android
 */
class AudioDeviceManager(
    private val context: Context,
    private val enableDeviceDetection: Boolean = true
) {
    
    companion object {
        private const val TAG = "AudioDeviceManager"
        private const val CLASS_NAME = "AudioDeviceManager" // Add class name constant for logging
        
        // Device type constants - standardized across platforms
        const val DEVICE_TYPE_BUILTIN_MIC = "builtin_mic"
        const val DEVICE_TYPE_BLUETOOTH = "bluetooth"
        const val DEVICE_TYPE_USB = "usb"
        const val DEVICE_TYPE_WIRED_HEADSET = "wired_headset"
        const val DEVICE_TYPE_WIRED_HEADPHONES = "wired_headphones"
        const val DEVICE_TYPE_SPEAKER = "speaker"
        const val DEVICE_TYPE_UNKNOWN = "unknown"
        
        // Common sample rates most devices support
        private val COMMON_SAMPLE_RATES = listOf(8000, 11025, 16000, 22050, 32000, 44100, 48000)
        
        // Common channel configurations most devices support
        private val COMMON_CHANNEL_COUNTS = listOf(1, 2)

        // USB broadcasts and AudioDeviceCallback may describe one route change.
        // Coalesce them briefly while using AudioDeviceCallback as the source of truth.
        private const val USB_DEVICE_CHANGE_DEBOUNCE_MS = 120L
        // Lenovo briefly removes and re-adds the same wired route with a new
        // AudioDeviceInfo id when Bluetooth A2DP/HFP changes state. Do not
        // treat that re-enumeration as a physical unplug while the replacement
        // endpoint is still being published by AudioPolicy.
        private const val EXTERNAL_DEVICE_REPLACEMENT_GRACE_MS = 1_600L
    }
    
    // Delegate for handling device disconnection
    var delegate: AudioDeviceManagerDelegate? = null
    
    // Simple callback for device connections
    var onDeviceConnected: ((String) -> Unit)? = null
    
    // Simple callback for device disconnections
    var onDeviceDisconnected: ((String) -> Unit)? = null
    
    // Audio manager for accessing device information
    private val audioManager: AudioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    // OEM AudioDeviceInfo.productName may be the tablet model for SCO inputs.
    // Connection broadcasts and the headset profile provide the real label.
    private val bluetoothNamesByAddress = mutableMapOf<String, String>()
    private var lastBluetoothDeviceName: String? = null
    private var bluetoothHeadsetProfile: BluetoothProfile? = null
    private val connectedHeadsets = java.util.concurrent.ConcurrentHashMap<String, BluetoothDevice>()
    private val disconnectedHeadsetAddresses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    
    // Last selected device ID for tracking changes
    private var lastSelectedDeviceId: String? = null

    // Preserve USB labels after the AudioDeviceInfo is removed on detach.
    private val usbNamesByKey = mutableMapOf<String, String>()
    private val usbAudioDeviceNamesById = mutableMapOf<String, String>()
    private val usbAudioDeviceKeysById = mutableMapOf<String, String>()
    private val usbAudioDeviceIds = mutableSetOf<String>()
    private val pendingAttachedUsbDeviceKeys = mutableSetOf<String>()
    private val pendingExternalRemovals = mutableMapOf<String, Pair<String, Runnable>>()
    private val deviceCapabilitiesCache = mutableMapOf<String, Map<String, Any>>()
    
    // BroadcastReceiver for device connection/disconnection
    private var deviceReceiver: BroadcastReceiver? = null
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var pendingUsbDeviceScan: Runnable? = null
    
    // Coroutine scope for async operations
    private val coroutineScope = CoroutineScope(Dispatchers.Main)
    
    init {
        preloadConnectedBluetoothHeadsetNames()
        // Start monitoring device changes only if BT_CONNECT permission is available (#294)
        if (enableDeviceDetection) {
            startMonitoringDeviceChanges()
        }
    }
    
    /**
     * Gets all available audio input devices
     */
    fun getAvailableInputDevices(promise: Promise) {
        LogUtils.d(TAG, "Getting available input devices")
        
        val devices = mutableListOf<Map<String, Any>>()
        val currentInput = getCurrentInputDeviceInternal()
        
        // Device map for smart deduplication
        // We won't deduplicate devices with different capabilities
        // This ensures we preserve devices that represent different recording profiles
        val deviceMap = mutableMapOf<String, MutableList<Map<String, Any>>>()
        
        // Get all audio devices
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val headsets = connectedBluetoothInputs()
            
            for (device in audioDevices) {
                if (device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO && headsets.isNotEmpty()) continue
                if (device.type == AudioDeviceInfo.TYPE_UNKNOWN) {
                    continue
                }
                
                val deviceId = device.id.toString()
                val isDefault = currentInput?.get("id") == deviceId
                val deviceType = mapDeviceType(device)
                val deviceName = getDeviceName(device)
                
                val capabilities = getDeviceCapabilities(device)
                
                LogUtils.d(TAG, "Raw device found: ${deviceName} (ID: ${deviceId}, type: ${deviceType})")
                
                val deviceInfo = mapOf(
                    "id" to deviceId,
                    "name" to deviceName, 
                    "type" to deviceType,
                    "isDefault" to isDefault,
                    "capabilities" to capabilities,
                    "isAvailable" to true
                )
                
                // Group devices by name for potential deduplication
                val key = deviceName
                if (!deviceMap.containsKey(key)) {
                    deviceMap[key] = mutableListOf()
                }
                deviceMap[key]?.add(deviceInfo)
            }
            headsets.forEach { (address, headset) ->
                val id = AudioInputRoutes.BLUETOOTH_PREFIX + address
                devices.add(mapOf(
                    "id" to id,
                    "name" to (headset.name?.takeIf { it.isNotBlank() } ?: "Bluetooth Headset"),
                    "type" to DEVICE_TYPE_BLUETOOTH,
                    "isDefault" to (lastSelectedDeviceId == id),
                    "capabilities" to getDefaultCapabilities(),
                    "isAvailable" to true
                ))
            }
            
            // Deduplicate while preserving devices with different capabilities
            deviceMap.forEach { (name, deviceList) ->
                if (deviceList.size > 1) {
                    LogUtils.d(TAG, "Found ${deviceList.size} devices with name: $name - checking for duplicates")
                    
                    // First check if we have a default device in this group - it gets priority
                    val defaultDevice = deviceList.find { it["isDefault"] == true }
                    if (defaultDevice != null) {
                        // Always keep the default device
                        LogUtils.d(TAG, "Keeping default device with ID: ${defaultDevice["id"]}")
                        devices.add(defaultDevice)
                        
                        // Now process the others
                        val remainingDevices = deviceList.filter { it["id"] != defaultDevice["id"] }
                        
                        // Create groups based on unique capabilities
                        val capabilityGroups = mutableMapOf<String, MutableList<Map<String, Any>>>()
                        
                        for (device in remainingDevices) {
                            val capabilities = device["capabilities"] as Map<String, Any>
                            val capabilityHash = generateCapabilityHash(capabilities)
                            
                            if (!capabilityGroups.containsKey(capabilityHash)) {
                                capabilityGroups[capabilityHash] = mutableListOf()
                            }
                            capabilityGroups[capabilityHash]?.add(device)
                        }
                        
                        // Now keep one device from each capability group
                        capabilityGroups.forEach { (capHash, devicesWithSameCapabilities) ->
                            val selectedDevice = devicesWithSameCapabilities.first()
                            LogUtils.d(TAG, "Adding device ${selectedDevice["id"]} with unique capabilities: $capHash")
                            devices.add(selectedDevice)
                            
                            // Log if we're dropping duplicate devices
                            if (devicesWithSameCapabilities.size > 1) {
                                val droppedIds = devicesWithSameCapabilities.drop(1).map { it["id"] }
                                LogUtils.d(TAG, "Dropping ${devicesWithSameCapabilities.size - 1} duplicate devices with IDs: $droppedIds")
                            }
                        }
                    } else {
                        // No default device, so just deduplicate by capabilities
                        val capabilityGroups = mutableMapOf<String, MutableList<Map<String, Any>>>()
                        
                        for (device in deviceList) {
                            val capabilities = device["capabilities"] as Map<String, Any>
                            val capabilityHash = generateCapabilityHash(capabilities)
                            
                            if (!capabilityGroups.containsKey(capabilityHash)) {
                                capabilityGroups[capabilityHash] = mutableListOf()
                            }
                            capabilityGroups[capabilityHash]?.add(device)
                        }
                        
                        // Keep one device from each capability group
                        capabilityGroups.forEach { (capHash, devicesWithSameCapabilities) ->
                            val selectedDevice = devicesWithSameCapabilities.first()
                            LogUtils.d(TAG, "Adding device ${selectedDevice["id"]} with unique capabilities: $capHash")
                            devices.add(selectedDevice)
                            
                            // Log if we're dropping duplicate devices
                            if (devicesWithSameCapabilities.size > 1) {
                                val droppedIds = devicesWithSameCapabilities.drop(1).map { it["id"] }
                                LogUtils.d(TAG, "Dropping ${devicesWithSameCapabilities.size - 1} duplicate devices with IDs: $droppedIds")
                            }
                        }
                    }
                } else {
                    // Only one device with this name, just add it
                    devices.add(deviceList.first())
                }
            }
            
        } else {
            // Fallback for older Android versions - add at least the default device
            val isHeadsetConnected = audioManager.isWiredHeadsetOn || isBluetoothHeadsetConnected()
            
            // Add default device (built-in mic)
            devices.add(mapOf(
                "id" to "0", // Default device ID
                "name" to "Built-in Microphone",
                "type" to DEVICE_TYPE_BUILTIN_MIC,
                "isDefault" to !isHeadsetConnected,
                "capabilities" to getDefaultCapabilities(),
                "isAvailable" to true
            ))
            
            // Add headset device if connected
            if (audioManager.isWiredHeadsetOn) {
                devices.add(mapOf(
                    "id" to "1",
                    "name" to "Wired Headset",
                    "type" to DEVICE_TYPE_WIRED_HEADSET,
                    "isDefault" to true,
                    "capabilities" to getDefaultCapabilities(),
                    "isAvailable" to true
                ))
            }
            
            // Add Bluetooth device if connected
            if (isBluetoothHeadsetConnected()) {
                devices.add(mapOf(
                    "id" to "2",
                    "name" to "Bluetooth Headset",
                    "type" to DEVICE_TYPE_BLUETOOTH,
                    "isDefault" to true,
                    "capabilities" to getDefaultCapabilities(),
                    "isAvailable" to true
                ))
            }
        }
        
        LogUtils.d(TAG, "Found ${devices.size} input devices after deduplication")
        promise.resolve(devices)
    }
    
    /**
     * Generate a string hash representing device capabilities for comparison
     */
    private fun generateCapabilityHash(capabilities: Map<String, Any>): String {
        val sampleRates = capabilities["sampleRates"]?.toString() ?: ""
        val channelCounts = capabilities["channelCounts"]?.toString() ?: ""
        val bitDepths = capabilities["bitDepths"]?.toString() ?: ""
        val hasEchoCancellation = capabilities["hasEchoCancellation"]?.toString() ?: "false"
        val hasNoiseSuppression = capabilities["hasNoiseSuppression"]?.toString() ?: "false"
        val hasAutomaticGainControl = capabilities["hasAutomaticGainControl"]?.toString() ?: "false"
        
        return "$sampleRates|$channelCounts|$bitDepths|$hasEchoCancellation|$hasNoiseSuppression|$hasAutomaticGainControl"
    }
    
    /**
     * Gets the currently selected input device
     */
    fun getCurrentInputDevice(promise: Promise) {
        val device = getCurrentInputDeviceInternal()
        promise.resolve(device)
    }
    
    /**
     * Gets the default input device (built-in mic usually)
     */
    suspend fun getDefaultInputDevice(): Map<String, Any>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            // Find built-in microphone which is the typical default
            val defaultDevice = audioDevices.firstOrNull { 
                it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC 
            }
            
            if (defaultDevice != null) {
                val deviceType = mapDeviceType(defaultDevice)
                val deviceName = getDeviceName(defaultDevice)
                
                LogUtils.d(TAG, "Found default device: $deviceName (ID: ${defaultDevice.id}, Type: $deviceType)")
                
                return mapOf(
                    "id" to defaultDevice.id.toString(),
                    "name" to deviceName,
                    "type" to deviceType,
                    "isDefault" to true,
                    "capabilities" to getDeviceCapabilities(defaultDevice),
                    "isAvailable" to true
                )
            }
        }
        
        // Fallback for older Android or if no built-in mic found
        return mapOf(
            "id" to "0",
            "name" to "Built-in Microphone",
            "type" to DEVICE_TYPE_BUILTIN_MIC,
            "isDefault" to true,
            "capabilities" to getDefaultCapabilities(),
            "isAvailable" to true
        )
    }
    
    /**
     * Gets the currently active input device (internal implementation)
     */
    private fun getCurrentInputDeviceInternal(): Map<String, Any>? {
        val selectedId = lastSelectedDeviceId
        if (selectedId?.startsWith(AudioInputRoutes.BLUETOOTH_PREFIX) == true) {
            val headset = connectedBluetoothInputs()[selectedId.removePrefix(AudioInputRoutes.BLUETOOTH_PREFIX)]
            if (headset != null) return mapOf(
                "id" to selectedId, "name" to (headset.name ?: "Bluetooth Headset"),
                "type" to DEVICE_TYPE_BLUETOOTH, "isDefault" to false,
                "capabilities" to getDefaultCapabilities(), "isAvailable" to true
            )
        }
        // On Android, we need to check the current routing
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            
            // Determine current input device based on the communication device
            // or audio routing
            // For API level 31+, we can use getCommunicationDevice() directly
            var currentDevice: AudioDeviceInfo? = audioDevices.firstOrNull {
                it.id.toString() == lastSelectedDeviceId
            }
            
            if (currentDevice == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                currentDevice = audioManager.communicationDevice?.takeIf { 
                    it.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER && 
                    it.isSource 
                }
            }
            
            // If no communication device found, check other indicators
            if (currentDevice == null) {
                // Check if we have a Bluetooth SCO device active
                if (audioManager.isBluetoothScoOn) {
                    currentDevice = audioDevices.firstOrNull { 
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                }
                
                // Check if wired headset is connected
                if (currentDevice == null && audioManager.isWiredHeadsetOn) {
                    currentDevice = audioDevices.firstOrNull { 
                        it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    }
                }
                
                // Default to built-in mic if nothing else is found
                if (currentDevice == null) {
                    currentDevice = audioDevices.firstOrNull { 
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                    }
                }
            }
            
            if (currentDevice != null) {
                val deviceId = currentDevice.id.toString()
                val deviceType = mapDeviceType(currentDevice)
                val deviceName = getDeviceName(currentDevice)
                
                LogUtils.d(TAG, "Current input device: $deviceName (ID: $deviceId, type: $deviceType)")
                
                return mapOf(
                    "id" to deviceId,
                    "name" to deviceName,
                    "type" to deviceType,
                    "isDefault" to (deviceType == DEVICE_TYPE_BUILTIN_MIC),
                    "capabilities" to getDeviceCapabilities(currentDevice),
                    "isAvailable" to true
                )
            }
        } else {
            // For older Android versions, determine based on flags
            if (isBluetoothHeadsetConnected()) {
                return mapOf(
                    "id" to "2",
                    "name" to "Bluetooth Headset",
                    "type" to DEVICE_TYPE_BLUETOOTH,
                    "isDefault" to true,
                    "capabilities" to getDefaultCapabilities(),
                    "isAvailable" to true
                )
            } else if (audioManager.isWiredHeadsetOn) {
                return mapOf(
                    "id" to "1",
                    "name" to "Wired Headset",
                    "type" to DEVICE_TYPE_WIRED_HEADSET,
                    "isDefault" to true,
                    "capabilities" to getDefaultCapabilities(),
                    "isAvailable" to true
                )
            } else {
                // Default to built-in mic
                return mapOf(
                    "id" to "0",
                    "name" to "Built-in Microphone",
                    "type" to DEVICE_TYPE_BUILTIN_MIC,
                    "isDefault" to true,
                    "capabilities" to getDefaultCapabilities(),
                    "isAvailable" to true
                )
            }
        }
        
        return null
    }
    
    /**
     * Selects a specific audio input device for recording
     */
    fun selectInputDevice(deviceId: String, promise: Promise) {
        LogUtils.d(TAG, "Selecting input device with ID: $deviceId")
        if (selectDeviceNow(deviceId)) {
            promise.resolve(true)
        } else {
            promise.reject("DEVICE_NOT_AVAILABLE", "The selected audio device could not be routed", null)
        }
    }
    
    /**
     * Selects a specific audio input device asynchronously (for internal use)
     */
    suspend fun selectDevice(deviceId: String): Boolean {
        if (!selectDeviceNow(deviceId)) return false
        if (!deviceId.startsWith(AudioInputRoutes.BLUETOOTH_PREFIX)) return true
        // SCO activation is asynchronous. Never start AudioRecord on the tablet while
        // the selected headset's communication channel is still being established.
        // Lenovo Android 11 commonly needs 4-7 seconds when A2DP is active and
        // has to hand the headset over to HFP/SCO. The previous 3 second window
        // timed out, reset the route, and made the UI fall back to the tablet mic.
        repeat(120) {
            if (!isDeviceAvailable(deviceId)) {
                LogUtils.w(TAG, "Bluetooth input disappeared while SCO was connecting: $deviceId")
                return false
            }
            val input = AudioInputRoutes.resolve(audioManager, deviceId)
            val routed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.communicationDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } else {
                val stickyState = context.registerReceiver(
                    null,
                    IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED),
                )?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                audioManager.isBluetoothScoOn ||
                    stickyState == AudioManager.SCO_AUDIO_STATE_CONNECTED
            }
            if (input != null && routed) {
                LogUtils.d(TAG, "Bluetooth input ready after SCO connection: $deviceId")
                return true
            }
            delay(50)
        }
        LogUtils.w(
            TAG,
            "Bluetooth SCO timed out without a usable input endpoint: $deviceId " +
                "scoOn=${audioManager.isBluetoothScoOn}",
        )
        return false
    }

    internal fun selectDeviceNow(deviceId: String): Boolean {
        LogUtils.d(TAG, "Asynchronously selecting device with ID: $deviceId")
        fun reportSelection(success: Boolean = true): Boolean {
            if (success) lastSelectedDeviceId = deviceId
            return success
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (deviceId.startsWith(AudioInputRoutes.BLUETOOTH_PREFIX)) {
                if (!isDeviceAvailable(deviceId)) return false
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val address = deviceId.removePrefix(AudioInputRoutes.BLUETOOTH_PREFIX)
                    val sinks = audioManager.availableCommunicationDevices.filter {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                    val sink = sinks.firstOrNull { it.address.equals(address, ignoreCase = true) }
                        ?: sinks.singleOrNull() ?: return false
                    return reportSelection(audioManager.setCommunicationDevice(sink))
                }
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
                return reportSelection()
            }
            val selectedDevice = AudioInputRoutes.resolve(audioManager, deviceId)
            
            if (selectedDevice == null) {
                LogUtils.e(TAG, "Device not found with ID $deviceId for async selection")
                return false
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android accepts only sink devices from this list. Keep playback on the
                // paired route when it is already available, but do not make capture
                // selection depend on that output endpoint. Right after a wired/USB input
                // is reported, availableCommunicationDevices may still omit its sink or
                // setCommunicationDevice() may transiently reject it. The new AudioRecord
                // validates the requested source with setPreferredDevice().
                val communicationDevices = audioManager.availableCommunicationDevices
                val sinkId = selectCommunicationSink(
                    requested = selectedDevice.toCommunicationDeviceDescriptor(),
                    availableSinks = communicationDevices.map {
                        it.toCommunicationDeviceDescriptor()
                    }
                )
                val sink = sinkId?.let { id ->
                    communicationDevices.firstOrNull { it.id == id }
                }
                val previousSelectedDeviceId = lastSelectedDeviceId
                val outputRouted = sink?.let {
                    attemptCommunicationDeviceSelection(
                        selection = { audioManager.setCommunicationDevice(it) }
                    )
                } ?: false
                if (!outputRouted && previousSelectedDeviceId?.startsWith(AudioInputRoutes.BLUETOOTH_PREFIX) == true) {
                    // Do not leave a previous Bluetooth SCO route active while the
                    // newly inserted wired/USB input is being selected. Capture can
                    // still use the preferred input below, and playback can retry
                    // its paired output once Android publishes the endpoint.
                    try {
                        audioManager.clearCommunicationDevice()
                    } catch (_: Exception) {
                        // Some OEMs reject clearing during a route transition; the
                        // AudioRecord preferred-device check remains authoritative.
                    }
                }
                lastSelectedDeviceId = deviceId
                if (sink == null) {
                    LogUtils.w(
                        TAG,
                        "Communication sink is not ready for input $deviceId; " +
                            "capture will validate the preferred input"
                    )
                } else {
                    LogUtils.d(
                        TAG,
                        "Setting communication sink ${sink.id} for input $deviceId: $outputRouted"
                    )
                }
                return true
            }
            
            // Handle device selection based on type
            when (selectedDevice.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> {
                    // For Bluetooth SCO devices, start SCO connection
                    if (!audioManager.isBluetoothScoOn) {
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                    }
                    
                    return reportSelection()
                }
                
                else -> {
                    // For other device types
                    if (audioManager.isBluetoothScoOn) {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }

                    // Android 10 has no setCommunicationDevice(). Explicitly
                    // clear the speakerphone force-use when an external wired
                    // input is selected; otherwise Lenovo keeps communication
                    // audio on the tablet speaker even though AudioRecord's
                    // preferred input is the headset.
                    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                    audioManager.isSpeakerphoneOn =
                        selectedDevice.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
                    
                    return reportSelection()
                }
            }
        } else {
            // For older Android versions
            when (deviceId) {
                "0" -> { // Built-in mic
                    if (audioManager.isBluetoothScoOn) {
                        audioManager.stopBluetoothSco()
                        audioManager.isBluetoothScoOn = false
                    }
                    return reportSelection()
                }
                "1" -> { // Wired headset
                    if (audioManager.isWiredHeadsetOn) {
                        if (audioManager.isBluetoothScoOn) {
                            audioManager.stopBluetoothSco()
                            audioManager.isBluetoothScoOn = false
                        }
                        return reportSelection()
                    }
                    return false
                }
                "2" -> { // Bluetooth headset
                    if (isBluetoothHeadsetConnected()) {
                        audioManager.startBluetoothSco()
                        audioManager.isBluetoothScoOn = true
                        return reportSelection()
                    }
                    return false
                }
                else -> return false
            }
        }
    }
    
    /**
     * Resets to the default audio input device (usually built-in mic)
     */
    fun resetToDefaultDevice(callback: (Boolean, Exception?) -> Unit) {
        val success = resetToDefaultDeviceNow()
        callback(success, if (success) null else IllegalStateException("Default input route was rejected"))
    }

    internal fun resetToDefaultDeviceNow(): Boolean {
        LogUtils.d(TAG, "Resetting to default input device")
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // API 31+ owns SCO and source pairing through the communication-device API.
                // Clearing the request restores the platform default without mutating a
                // legacy SCO flag first.
                audioManager.clearCommunicationDevice()
            } else if (audioManager.isBluetoothScoOn) {
                audioManager.stopBluetoothSco()
                audioManager.isBluetoothScoOn = false
            }

            // Clear last selected device
            lastSelectedDeviceId = null
            
            // Get the device after reset
            val currentDevice = getCurrentInputDeviceInternal()
            if (currentDevice != null) {
                LogUtils.d(TAG, "Reset to default device: ${currentDevice["name"]}")
            } else {
                LogUtils.d(TAG, "No device detected after reset")
            }
            
            return true
        } catch (e: Exception) {
            LogUtils.e(TAG, "Failed to reset to default device: ${e.message}")
            return false
        }
    }
    
    /**
     * Force refreshes the audio session to update device list
     */
    fun forceRefreshAudioDevices(): Boolean {
        // Enumeration must never change the selected route or open an SCO channel.
        return true
    }
    
    /**
     * Maps Android's AudioDeviceInfo type to our standardized device type
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun mapDeviceType(device: AudioDeviceInfo): String {
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> DEVICE_TYPE_BUILTIN_MIC
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> DEVICE_TYPE_BLUETOOTH
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> DEVICE_TYPE_WIRED_HEADSET
            AudioDeviceInfo.TYPE_USB_DEVICE, 
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_USB_HEADSET -> DEVICE_TYPE_USB
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> DEVICE_TYPE_WIRED_HEADPHONES
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> DEVICE_TYPE_SPEAKER
            else -> DEVICE_TYPE_UNKNOWN
        }
    }
    
    /**
     * Gets a human-readable name for the device with detailed capability information
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun getDeviceName(device: AudioDeviceInfo): String {
        val usbName = getUsbDeviceName(device)
        val bluetoothName = getBluetoothDeviceName(device)
        // Get product name if available
        val productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.productName?.toString()
        } else null
        
        // Get base name
        val baseName = if (!usbName.isNullOrBlank()) {
            usbName
        } else if (!bluetoothName.isNullOrBlank()) {
            bluetoothName
        } else if (device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET &&
            (productName.isNullOrBlank() || productName.equals("h2w", ignoreCase = true))) {
            "Wired Headset"
        } else if (productName.isNullOrBlank()) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
                else -> "Audio Device"
            }
        } else {
            productName
        }

        if (isUsbAudioDevice(device)) return baseName
        
        // Get capability details for naming
        val maxSampleRate = device.sampleRates?.maxOrNull() ?: 0
        val channelCount = device.channelCounts?.maxOrNull() ?: 1
        
        // Create a descriptive suffix based on detailed capabilities
        val typeDescription = if (device.type == AudioDeviceInfo.TYPE_UNKNOWN) {
            when {
                maxSampleRate >= 44100 && channelCount > 1 -> "External"
                maxSampleRate >= 44100 -> "Line-in"
                else -> "Unknown Type"
            }
        } else {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Internal"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired"
                AudioDeviceInfo.TYPE_USB_DEVICE, 
                AudioDeviceInfo.TYPE_USB_ACCESSORY,
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
                else -> ""
            }
        }
        
        // Create full capability description
        val capabilityDesc = when {
            // Stereo high sample rate
            maxSampleRate >= 48000 && channelCount >= 2 -> {
                if (device.type == AudioDeviceInfo.TYPE_UNKNOWN) {
                    "HD Audio $typeDescription #${device.id}"
                } else {
                    "HD Audio $typeDescription"
                }
            }
            
            // Stereo
            channelCount > 1 -> {
                if (maxSampleRate >= 44100) {
                    "Stereo $typeDescription ${maxSampleRate/1000}kHz"
                } else {
                    "Stereo $typeDescription"
                }
            }
            
            // High sample rate mono
            maxSampleRate >= 44100 -> "High Quality $typeDescription"
            
            // Basic
            else -> "$typeDescription #${device.id}"
        }
        
        return "$baseName ($capabilityDesc)".trim()
    }
    
    /**
     * Gets just the base device name without capability information
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun getBaseDeviceName(device: AudioDeviceInfo): String {
        val usbName = getUsbDeviceName(device)
        val bluetoothName = getBluetoothDeviceName(device)
        val productName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            device.productName?.toString()
        } else null
        
        return if (!usbName.isNullOrBlank()) {
            usbName
        } else if (!bluetoothName.isNullOrBlank()) {
            bluetoothName
        } else if (productName.isNullOrBlank()) {
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in Microphone"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio Device"
                AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB Audio Accessory"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in Speaker"
                else -> "Audio Device"
            }
        } else {
            productName
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun getUsbDeviceName(device: AudioDeviceInfo): String? {
        if (!isUsbAudioDevice(device)) return null
        usbAudioDeviceNamesById[device.id.toString()]?.let { return it }
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return null
        return try {
            val candidates = usbManager.deviceList.values.filter { usbDevice ->
                usbDevice.deviceClass == android.hardware.usb.UsbConstants.USB_CLASS_AUDIO ||
                    (0 until usbDevice.interfaceCount).any { index ->
                        usbDevice.getInterface(index).interfaceClass ==
                            android.hardware.usb.UsbConstants.USB_CLASS_AUDIO
                    }
            }
            val reportedName = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                device.productName?.toString()?.normalizeDeviceName()
            } else null
            val matchingCandidates = if (reportedName.isNullOrBlank()) {
                emptyList()
            } else {
                candidates.filter { usbDevice ->
                    val productName = usbDevice.productName?.normalizeDeviceName()
                    val manufacturerName = usbDevice.manufacturerName?.normalizeDeviceName()
                    reportedName == productName || reportedName == manufacturerName
                }
            }
            val usbDevice = matchingCandidates.singleOrNull()
                ?: candidates.singleOrNull()
                ?: return null
            val name = usbDeviceDisplayName(usbDevice)
            usbNamesByKey[usbDeviceKey(usbDevice)] = name
            name
        } catch (_: SecurityException) {
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun usbDeviceKey(device: UsbDevice): String =
        "${device.vendorId}:${device.productId}:${device.deviceName}"

    private fun usbDeviceDisplayName(device: UsbDevice): String {
        val product = device.productName?.trim().orEmpty()
        val manufacturer = device.manufacturerName?.trim().orEmpty()
        return product.takeIf { it.isNotBlank() }
            ?: manufacturer.takeIf { it.isNotBlank() }
            ?: usbNamesByKey[usbDeviceKey(device)]
            ?: "USB Audio Device (${device.vendorId.toString(16).padStart(4, '0')}:${device.productId.toString(16).padStart(4, '0')})"
    }

    private fun String.normalizeDeviceName(): String =
        trim().lowercase().replace(Regex("\\s+"), " ")

    private fun getUsbDeviceFromIntent(intent: Intent): UsbDevice? {
        if (!intent.hasExtra(UsbManager.EXTRA_DEVICE)) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
    }

    private fun isUsbAudioDevice(device: AudioDeviceInfo): Boolean =
        device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
            device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
            device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY

    /**
     * Some OEMs expose an anonymous SCO endpoint whose productName is the
     * tablet model. Resolve the connected headset profile as well, including
     * headsets that were connected before the app started.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun getBluetoothDeviceName(device: AudioDeviceInfo): String? {
        if (device.type != AudioDeviceInfo.TYPE_BLUETOOTH_SCO) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val address = device.address.orEmpty()
        bluetoothNamesByAddress[address]?.let { return it }
        return try {
            val connectedHeadsets = bluetoothHeadsetProfile?.connectedDevices.orEmpty()
            connectedHeadsets.firstOrNull {
                address.isNotBlank() &&
                    it.address.equals(address, ignoreCase = true)
            }?.let { headset ->
                rememberBluetoothDevice(headset)
                return headset.name?.takeIf { it.isNotBlank() }
            }
            if (connectedHeadsets.size == 1) {
                rememberBluetoothDevice(connectedHeadsets.first())
                connectedHeadsets.first().name?.takeIf { it.isNotBlank() }?.let {
                    return it
                }
            }
            val adapter = BluetoothAdapter.getDefaultAdapter()
            val bondedDevices = adapter?.bondedDevices.orEmpty()
            val bondedName = bondedDevices
                .firstOrNull { it.address.equals(address, ignoreCase = true) }
                ?.name
                ?.takeIf { it.isNotBlank() }
            if (bondedName != null) return bondedName

            // Lenovo Android 11 exposes SCO with a zero address and an empty
            // HEADSET profile list. When exactly one bonded headset is active,
            // its name is the only unambiguous user-visible label available.
            if (address == "00:00:00:00:00:00" &&
                adapter?.getProfileConnectionState(BluetoothProfile.HEADSET) ==
                    BluetoothProfile.STATE_CONNECTED &&
                bondedDevices.size == 1) {
                bondedDevices.first().name?.takeIf { it.isNotBlank() }?.let {
                    return it
                }
            }
            lastBluetoothDeviceName
        } catch (_: SecurityException) {
            lastBluetoothDeviceName
        } catch (_: IllegalArgumentException) {
            lastBluetoothDeviceName
        }
    }

    private fun rememberBluetoothDevice(device: BluetoothDevice) {
        try {
            val name = device.name?.takeIf { it.isNotBlank() } ?: return
            lastBluetoothDeviceName = name
            val address = device.address.orEmpty()
            if (address.isNotBlank()) bluetoothNamesByAddress[address] = name
        } catch (_: SecurityException) {
            // BLUETOOTH_CONNECT can be revoked while a broadcast is in flight.
        }
    }

    private fun connectedBluetoothInputs(): Map<String, BluetoothDevice> {
        return try {
            val adapter = BluetoothAdapter.getDefaultAdapter() ?: return emptyMap()
            if (adapter.getProfileConnectionState(BluetoothProfile.HEADSET) != BluetoothProfile.STATE_CONNECTED) {
                connectedHeadsets.clear()
                return emptyMap()
            }
            bluetoothHeadsetProfile?.connectedDevices.orEmpty().forEach { device ->
                if (device.address !in disconnectedHeadsetAddresses) {
                    rememberBluetoothDevice(device)
                    connectedHeadsets[device.address] = device
                }
            }
            // Some Lenovo firmware returns an empty profile list even though HFP
            // is connected. Only infer identity when exactly one bonded device exists.
            if (connectedHeadsets.isEmpty()) {
                adapter.bondedDevices.singleOrNull()?.let { device ->
                    if (device.address !in disconnectedHeadsetAddresses) {
                        connectedHeadsets[device.address] = device
                    }
                }
            }
            connectedHeadsets.toMap()
        } catch (_: SecurityException) {
            emptyMap()
        }
    }

    /**
     * AudioDeviceInfo can expose an anonymous SCO endpoint (00:00:00:00:00:00)
     * whose productName is the tablet model. Query the connected HEADSET
     * profile so its real user-visible name is available on initial refresh.
     */
    private fun preloadConnectedBluetoothHeadsetNames() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED) {
            return
        }
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: return
        try {
            adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    if (profile != BluetoothProfile.HEADSET) return
                    bluetoothHeadsetProfile = proxy
                    try {
                        proxy.connectedDevices.forEach { device ->
                            rememberBluetoothDevice(device)
                            disconnectedHeadsetAddresses.remove(device.address)
                            connectedHeadsets[device.address] = device
                        }
                        scheduleUsbDeviceScan()
                    } catch (_: SecurityException) {
                        // Permission can be revoked while the profile binds.
                    }
                }

                override fun onServiceDisconnected(profile: Int) {
                    if (profile == BluetoothProfile.HEADSET) {
                        bluetoothHeadsetProfile = null
                    }
                }
            }, BluetoothProfile.HEADSET)
        } catch (_: SecurityException) {
            // Bluetooth names are optional; keep the platform fallback label.
        } catch (_: IllegalArgumentException) {
            // The OEM Bluetooth service may not expose the headset profile.
        }
    }
    
    /**
     * Gets capabilities for an audio input device
     * Enhanced to provide comprehensive capabilities even if not reported by the API
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun getDeviceCapabilities(device: AudioDeviceInfo): Map<String, Any> {
        // Get reported sample rates or use common ones if not available
        val reportedSampleRates = device.sampleRates?.toList()
        
        // Use reported sample rates but ensure common ones are included as most devices
        // actually support these even if not explicitly reported
        val sampleRates = if (reportedSampleRates.isNullOrEmpty()) {
            COMMON_SAMPLE_RATES
        } else {
            // Create a set of all sample rates - both reported and common ones
            val combinedRates = reportedSampleRates.toMutableSet()
            
            // For built-in and common devices, add standard rates that are typically supported
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || 
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                combinedRates.addAll(COMMON_SAMPLE_RATES)
            }
            
            // Convert back to list and sort
            combinedRates.toList().sorted()
        }
        
        // Get reported channel counts or use common ones
        val reportedChannelCounts = device.channelCounts?.toList()
        
        // Ensure mono and stereo are included as they're widely supported
        val channelCounts = if (reportedChannelCounts.isNullOrEmpty()) {
            COMMON_CHANNEL_COUNTS
        } else {
            val combinedCounts = reportedChannelCounts.toMutableSet()
            
            // Most devices support at least mono recording
            if (device.type == AudioDeviceInfo.TYPE_BUILTIN_MIC || 
                device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                combinedCounts.addAll(COMMON_CHANNEL_COUNTS)
            }
            
            combinedCounts.toList().sorted()
        }

        val cacheKey = "${device.id}:${sampleRates.joinToString(",")}:${channelCounts.joinToString(",")}"
        deviceCapabilitiesCache[cacheKey]?.let { return it }
        
        // Test if common configurations are actually supported
        // Probing with temporary AudioRecords can interrupt the live recorder.
        // Enumerate metadata only; the actual recorder validates its configuration.
        val verifiedSampleRates = sampleRates
        
        // Android doesn't provide bit depth info, so we use common values
        val bitDepths = listOf(16, 24)
        
        val capabilities = mapOf(
            "sampleRates" to verifiedSampleRates,
            "channelCounts" to channelCounts,
            "bitDepths" to bitDepths,
            "hasEchoCancellation" to true, // Android generally has AEC
            "hasNoiseSuppression" to true, // Android generally has noise suppression
            "hasAutomaticGainControl" to true // Android generally has AGC
        )
        if (deviceCapabilitiesCache.size >= 64) deviceCapabilitiesCache.clear()
        deviceCapabilitiesCache[cacheKey] = capabilities
        return capabilities
    }
    
    /**
     * Verify which sample rates are actually supported by attempting to create an AudioRecord
     * This helps catch cases where the API reports capabilities incorrectly
     */
    private fun verifyAudioConfigurations(deviceId: Int, channels: Int, sampleRates: List<Int>): List<Int> {
        if (!permissionGranted()) {
            return sampleRates // Can't verify without permission, return as-is
        }
        
        val supportedSampleRates = mutableListOf<Int>()
        val channelConfig = if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        
        // Always include these standard rates that are almost universally supported
        val standardRates = listOf(16000, 44100, 48000)
        
        LogUtils.d(TAG, "Verifying audio configurations for device ${deviceId} with ${sampleRates.size} sample rates")
        
        for (sampleRate in sampleRates.distinct()) {
            try {
                val minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate, 
                    channelConfig,
                    AudioFormat.ENCODING_PCM_16BIT
                )
                
                // Skip if invalid buffer size
                if (minBufferSize <= 0) {
                    // But keep standard rates anyway as they usually work
                    if (sampleRate in standardRates) {
                        supportedSampleRates.add(sampleRate)
                        LogUtils.d(TAG, "⚠️ Adding standard rate ${sampleRate}Hz despite test failure")
                    }
                    continue
                }
                
                // Try to create an AudioRecord with this configuration
                var audioRecord: AudioRecord? = null
                try {
                    audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        // Use the specific device if on newer Android
                        AudioRecord.Builder()
                            .setAudioSource(MediaRecorder.AudioSource.MIC)
                            .setAudioFormat(
                                AudioFormat.Builder()
                                    .setSampleRate(sampleRate)
                                    .setChannelMask(channelConfig)
                                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                                    .build()
                            )
                            .setBufferSizeInBytes(minBufferSize)
                            .build()
                    } else {
                        AudioRecord(
                            MediaRecorder.AudioSource.MIC,
                            sampleRate,
                            channelConfig,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBufferSize
                        )
                    }
                    
                    if (audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
                        supportedSampleRates.add(sampleRate)
                        LogUtils.d(TAG, "✅ Sample rate ${sampleRate}Hz verified as supported")
                    } else if (sampleRate in standardRates) {
                        // Include standard rates even if initialization failed
                        // as they typically work during actual recording
                        supportedSampleRates.add(sampleRate)
                        LogUtils.d(TAG, "⚠️ Adding standard rate ${sampleRate}Hz despite test failure")
                    }
                } finally {
                    audioRecord?.release()
                }
            } catch (e: Exception) {
                LogUtils.d(TAG, "Sample rate $sampleRate not supported: ${e.message}")
                
                // Include standard rates even if test failed
                if (sampleRate in standardRates) {
                    supportedSampleRates.add(sampleRate)
                    LogUtils.d(TAG, "⚠️ Adding standard rate ${sampleRate}Hz despite test failure")
                }
            }
        }
        
        // Ensure we have at least the standard rates
        if (supportedSampleRates.isEmpty()) {
            supportedSampleRates.addAll(standardRates)
        }
        
        return supportedSampleRates.sorted()
    }
    
    /**
     * Check if recording permission is granted
     */
    private fun permissionGranted(): Boolean {
        return context.checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) == 
               android.content.pm.PackageManager.PERMISSION_GRANTED
    }
    
    /**
     * Default capabilities for older Android versions
     */
    private fun getDefaultCapabilities(): Map<String, Any> {
        return mapOf(
            "sampleRates" to COMMON_SAMPLE_RATES,
            "channelCounts" to COMMON_CHANNEL_COUNTS,
            "bitDepths" to listOf(16, 24),
            "hasEchoCancellation" to true,
            "hasNoiseSuppression" to true,
            "hasAutomaticGainControl" to true
        )
    }
    
    /**
     * Checks if a Bluetooth headset is connected
     */
    private fun isBluetoothHeadsetConnected(): Boolean {
        try {
            // BLUETOOTH_CONNECT permission is required on API 31+ for Bluetooth API access.
            // Without it, BluetoothAdapter calls throw SecurityException.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
                    return false
                }
            }

            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter() ?: return false
            if (!bluetoothAdapter.isEnabled) {
                return false
            }
            
            // For newer Android versions, check communication device directly
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commsDevice = audioManager.communicationDevice
                if (commsDevice != null && commsDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                    return true
                }
            }
            
            // Check if Bluetooth SCO is enabled (active call)
            if (audioManager.isBluetoothScoOn) {
                return true
            }
            
            // Check legacy API
            val bluetoothProfile = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            } else {
                @Suppress("DEPRECATION")
                bluetoothAdapter.getProfileConnectionState(BluetoothProfile.HEADSET)
            }
            
            return bluetoothProfile == BluetoothProfile.STATE_CONNECTED
        } catch (e: Exception) {
            LogUtils.e(CLASS_NAME, "Error checking Bluetooth headset connection: ${e.message}", e)
            return false
        }
    }
    
    /**
     * Checks if a device is still available
     */
    fun isDeviceAvailable(deviceId: String): Boolean {
        if (deviceId.startsWith(AudioInputRoutes.BLUETOOTH_PREFIX)) {
            return connectedBluetoothInputs().containsKey(deviceId.removePrefix(AudioInputRoutes.BLUETOOTH_PREFIX))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            return audioDevices.any { it.id.toString() == deviceId }
        } else {
            // For older Android versions, check based on device ID
            return when (deviceId) {
                "0" -> true // Built-in mic is always available
                "1" -> audioManager.isWiredHeadsetOn // Wired headset
                "2" -> isBluetoothHeadsetConnected() // Bluetooth headset
                else -> false
            }
        }
    }
    
    /**
     * Starts monitoring device connection/disconnection events
     */
    private fun startMonitoringDeviceChanges() {
        if (deviceReceiver != null) {
            return // Already monitoring
        }
        
        try {
            val filter = IntentFilter().apply {
                // Wired headset events
                addAction(AudioManager.ACTION_HEADSET_PLUG)
                
                // Bluetooth device events
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                
                // Audio routing change events - critical for detecting device disconnection during recording
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                
                // Bluetooth connection state changes
                addAction(ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                
                // USB device events - to detect USB audio devices
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                
                // For Android 8+ we need to look for USB device permission events
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED)
                    addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED)
                }
                
                // Bluetooth SCO state changes - to detect when microphone becomes available
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            }
            
            deviceReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    LogUtils.d(CLASS_NAME, "Device connectivity changed: $action")

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
                        (action == BluetoothDevice.ACTION_ACL_CONNECTED ||
                        action == BluetoothDevice.ACTION_ACL_DISCONNECTED ||
                        action == ACTION_CONNECTION_STATE_CHANGED ||
                        action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED ||
                        action == AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED ||
                        action == AudioManager.ACTION_AUDIO_BECOMING_NOISY)) {
                        if (action == BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED) {
                            @Suppress("DEPRECATION")
                            val headset = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                            if (headset != null) {
                                val id = AudioInputRoutes.BLUETOOTH_PREFIX + headset.address
                                when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                                    BluetoothProfile.STATE_CONNECTED -> {
                                        rememberBluetoothDevice(headset)
                                        disconnectedHeadsetAddresses.remove(headset.address)
                                        connectedHeadsets[headset.address] = headset
                                        handleDeviceConnection(id)
                                    }
                                    BluetoothProfile.STATE_DISCONNECTED -> {
                                        disconnectedHeadsetAddresses.add(headset.address)
                                        connectedHeadsets.remove(headset.address)
                                        handleDeviceDisconnectionEvent(id)
                                        if (id == lastSelectedDeviceId) coroutineScope.launch {
                                            handleDeviceDisconnection(id)
                                        }
                                    }
                                }
                            }
                        }
                        // SCO/noisy events describe routes, not physical detachments.
                        // An ACL connection may be A2DP-only; HEADSET determines inputs.
                        scheduleUsbDeviceScan()
                        return
                    }

                    if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                        action == UsbManager.ACTION_USB_DEVICE_DETACHED ||
                        action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED ||
                        action == UsbManager.ACTION_USB_ACCESSORY_DETACHED) {
                        getUsbDeviceFromIntent(intent)?.let { usbDevice ->
                            val attached = action == UsbManager.ACTION_USB_DEVICE_ATTACHED ||
                                action == UsbManager.ACTION_USB_ACCESSORY_ATTACHED
                            val key = usbDeviceKey(usbDevice)
                            usbNamesByKey[key] = usbDeviceDisplayName(usbDevice)
                            if (attached) {
                                pendingAttachedUsbDeviceKeys.add(key)
                            } else {
                                pendingAttachedUsbDeviceKeys.remove(key)
                            }
                        }
                        scheduleUsbDeviceScan()
                        return
                    }
                    
                    // Log current audio state for debugging
                    logAudioState()
                    
                    // Determine which device was affected
                    var deviceId: String? = null
                    var deviceName: String? = null
                    var deviceType: String? = null
                    
                    when (action) {
                        AudioManager.ACTION_HEADSET_PLUG -> {
                            // Broadcast names and AudioDeviceInfo labels differ on OEMs
                            // (e.g. "Wired Headset" vs "h2w"). Diff real input IDs instead.
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                scheduleUsbDeviceScan()
                                return
                            }
                            val state = intent.getIntExtra("state", 0)
                            val microphone = intent.getIntExtra("microphone", 0)
                            val name = intent.getStringExtra("name") ?: "Wired Headset"
                            
                            if (state == 0) { // Unplugged
                                // Legacy device ID for pre-M Android
                                deviceId = "1" 
                                deviceName = name
                                deviceType = DEVICE_TYPE_WIRED_HEADSET
                                
                                LogUtils.d(CLASS_NAME, "Wired headset unplugged: $name")
                                
                                // For M+ we can get the actual device ID
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    // Look up the actual device ID we were using
                                    if (lastSelectedDeviceId != null) {
                                        val lastDeviceInfo = findDeviceById(lastSelectedDeviceId!!)
                                        if (lastDeviceInfo?.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || 
                                            lastDeviceInfo?.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) {
                                            deviceId = lastSelectedDeviceId
                                        }
                                    }
                                }
                            } else if (state == 1) { // Plugged in
                                LogUtils.d(CLASS_NAME, "Wired headset connected: $name")
                                
                                // For M+ find the actual new device
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    // Find the newly connected wired device
                                    val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                    val wiredDevice = audioDevices.firstOrNull { 
                                        (it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || 
                                         it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES) &&
                                        getDeviceName(it).contains(name, ignoreCase = true)
                                    }
                                    
                                    if (wiredDevice != null) {
                                        val connectedDeviceId = wiredDevice.id.toString()
                                        LogUtils.d(CLASS_NAME, "Found connected wired device: $name (ID: $connectedDeviceId)")
                                        handleDeviceConnection(connectedDeviceId)
                                    }
                                } else {
                                    // Legacy handling for older Android
                                    handleDeviceConnection("1")
                                }
                            }
                        }
                        
                        AudioManager.ACTION_AUDIO_BECOMING_NOISY -> {
                            LogUtils.d(CLASS_NAME, "Audio becoming noisy - potential device disconnect")

                            // USB has a dedicated route callback that preserves
                            // the removed AudioDeviceInfo and its cached label.
                            // Avoid emitting a second disconnect from this
                            // generic broadcast before that callback is handled.
                            if (lastSelectedDeviceId != null &&
                                lastSelectedDeviceId in usbAudioDeviceIds) {
                                LogUtils.d(
                                    CLASS_NAME,
                                    "Selected USB input disconnect will be handled by AudioDeviceCallback: $lastSelectedDeviceId"
                                )
                                forceRefreshAudioDevices()
                                return
                            }
                            
                            // This could be any type of device disconnect, so we need to check
                            // what device was actually removed by comparing current devices with our last device
                            if (lastSelectedDeviceId != null) {
                                // First check if the device is simply unavailable
                                if (!isDeviceAvailable(lastSelectedDeviceId!!)) {
                                    deviceId = lastSelectedDeviceId
                                    LogUtils.d(CLASS_NAME, "Detected device disconnection via AUDIO_BECOMING_NOISY: $deviceId")
                                } 
                                // If device seems available but routing changed, also consider it disconnected
                                else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val lastDeviceInfo = findDeviceById(lastSelectedDeviceId!!)
                                    
                                    // Get current routing
                                    val currentDevice = getCurrentInputDeviceInternal()
                                    
                                    // If current input device is different from the last selected, consider it disconnected
                                    if (lastDeviceInfo != null && currentDevice != null && 
                                        currentDevice["id"] != lastSelectedDeviceId) {
                                        deviceId = lastSelectedDeviceId
                                        LogUtils.d(CLASS_NAME, "Routing changed from ${lastDeviceInfo.id} to ${currentDevice["id"]}")
                                    }
                                }
                            }
                        }
                        
                        BluetoothDevice.ACTION_ACL_CONNECTED -> {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            
                            if (device != null) {
                                rememberBluetoothDevice(device)
                                LogUtils.d(CLASS_NAME, "Bluetooth device connected: ${device.name}")
                                
                                // For M+ find the actual new device
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val actualDevice = findBluetoothDevice(device)
                                    if (actualDevice != null) {
                                        val connectedDeviceId = actualDevice.id.toString()
                                        LogUtils.d(CLASS_NAME, "Found connected Bluetooth device: ${device.name} (ID: $connectedDeviceId)")
                                        handleDeviceConnection(connectedDeviceId)
                                    } else {
                                        LogUtils.d(CLASS_NAME, "Bluetooth device ${device.name} connected but not found in audio device list - attempting to activate SCO")
                                        
                                        // Try to activate Bluetooth SCO to make microphone available
                                        if (!audioManager.isBluetoothScoOn) {
                                            LogUtils.d(CLASS_NAME, "Starting Bluetooth SCO to activate microphone for ${device.name}")
                                            audioManager.startBluetoothSco()
                                            
                                            // Give SCO time to activate, then check again
                                            coroutineScope.launch {
                                                delay(2000) // Wait 2 seconds
                                                
                                                val scoDevice = findBluetoothDevice(device)
                                                if (scoDevice != null) {
                                                    val activatedDeviceId = scoDevice.id.toString()
                                                    LogUtils.d(CLASS_NAME, "Bluetooth SCO activated for device: ${device.name} (ID: $activatedDeviceId)")
                                                    handleDeviceConnection(activatedDeviceId)
                                                } else {
                                                    LogUtils.d(CLASS_NAME, "Bluetooth SCO didn't activate microphone for ${device.name}")
                                                    // Send generic connection event anyway
                                                    handleDeviceConnection("bluetooth:${device.address}")
                                                }
                                            }
                                        } else {
                                            // SCO already on, send generic event
                                            handleDeviceConnection("bluetooth:${device.address}")
                                        }
                                    }
                                } else {
                                    // Legacy handling for older Android
                                    handleDeviceConnection("2")
                                }
                            }
                        }
                        
                        BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            
                            if (device != null) {
                                rememberBluetoothDevice(device)
                                deviceId = "2" // Legacy ID for bluetooth headset
                                deviceName = device.name
                                deviceType = DEVICE_TYPE_BLUETOOTH
                                
                                // For M+ get the actual device ID
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val actualDevice = findBluetoothDevice(device)
                                    if (actualDevice != null) {
                                        deviceId = actualDevice.id.toString()
                                    }
                                }
                                
                                LogUtils.d(CLASS_NAME, "Bluetooth device disconnected: ${device.name}, using ID: $deviceId")
                            }
                        }
                        
                        ACTION_CONNECTION_STATE_CHANGED -> {
                            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE, -1)
                            logAudioState()
                            
                            when (state) {
                                BluetoothAdapter.STATE_CONNECTED -> {
                                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                    }
                                    
                                    if (device != null) {
                                        rememberBluetoothDevice(device)
                                        LogUtils.d(CLASS_NAME, "Bluetooth profile connected: ${device.name}")
                                        
                                        // For M+ find the actual new device
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            val actualDevice = findBluetoothDevice(device)
                                            if (actualDevice != null) {
                                                val connectedDeviceId = actualDevice.id.toString()
                                                LogUtils.d(CLASS_NAME, "Found connected Bluetooth profile device: ${device.name} (ID: $connectedDeviceId)")
                                                handleDeviceConnection(connectedDeviceId)
                                            } else {
                                                LogUtils.d(CLASS_NAME, "Bluetooth profile ${device.name} connected but not found in audio device list - attempting to activate SCO")
                                                
                                                // Try to activate Bluetooth SCO to make microphone available
                                                if (!audioManager.isBluetoothScoOn) {
                                                    LogUtils.d(CLASS_NAME, "Starting Bluetooth SCO to activate microphone for ${device.name}")
                                                    audioManager.startBluetoothSco()
                                                    
                                                    // Give SCO time to activate, then check again
                                                    coroutineScope.launch {
                                                        delay(2000) // Wait 2 seconds
                                                        
                                                        val scoDevice = findBluetoothDevice(device)
                                                        if (scoDevice != null) {
                                                            val activatedDeviceId = scoDevice.id.toString()
                                                            LogUtils.d(CLASS_NAME, "Bluetooth SCO activated for device: ${device.name} (ID: $activatedDeviceId)")
                                                            handleDeviceConnection(activatedDeviceId)
                                                        } else {
                                                            LogUtils.d(CLASS_NAME, "Bluetooth SCO didn't activate microphone for ${device.name}")
                                                            // Send generic connection event anyway
                                                            handleDeviceConnection("bluetooth:${device.address}")
                                                        }
                                                    }
                                                } else {
                                                    // SCO already on, send generic event
                                                    handleDeviceConnection("bluetooth:${device.address}")
                                                }
                                            }
                                        } else {
                                            // Legacy handling for older Android
                                            handleDeviceConnection("2")
                                        }
                                    }
                                }
                                
                                BluetoothAdapter.STATE_DISCONNECTED -> {
                                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                                    }
                                    
                                    if (device != null) {
                                        rememberBluetoothDevice(device)
                                        deviceId = "2" // Legacy ID for bluetooth
                                        deviceName = device.name
                                        deviceType = DEVICE_TYPE_BLUETOOTH
                                        
                                        // For M+ get the actual ID
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                            val actualDevice = findBluetoothDevice(device)
                                            if (actualDevice != null) {
                                                deviceId = actualDevice.id.toString()
                                            }
                                        }
                                        
                                        LogUtils.d(CLASS_NAME, "Bluetooth profile disconnected: ${device.name}, using ID: $deviceId")
                                    } 
                                    // No device info, check if our last device was bluetooth
                                    else if (lastSelectedDeviceId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val lastDeviceInfo = findDeviceById(lastSelectedDeviceId!!)
                                        
                                        if (lastDeviceInfo?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO) {
                                            deviceId = lastSelectedDeviceId
                                            LogUtils.d(CLASS_NAME, "Bluetooth profile disconnected, using last selected device ID: $deviceId")
                                        }
                                    }
                                }
                            }
                        }
                        
                        AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                            val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                            LogUtils.d(CLASS_NAME, "Bluetooth SCO state changed: $scoState")
                            
                            when (scoState) {
                                AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                                    LogUtils.d(CLASS_NAME, "Bluetooth SCO connected - microphone available")
                                    
                                    // Check if any new Bluetooth SCO devices appeared
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                                        val bluetoothScoDevices = audioDevices.filter { 
                                            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO 
                                        }
                                        
                                        if (bluetoothScoDevices.isNotEmpty()) {
                                            // Found Bluetooth SCO device(s), notify about the first one
                                            val scoDevice = bluetoothScoDevices.first()
                                            val scoDeviceId = scoDevice.id.toString()
                                            val scoDeviceName = getDeviceName(scoDevice)
                                            LogUtils.d(CLASS_NAME, "Found Bluetooth SCO device: $scoDeviceName (ID: $scoDeviceId)")
                                            handleDeviceConnection(scoDeviceId)
                                        }
                                    }
                                }
                                
                                AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                                    LogUtils.d(CLASS_NAME, "Bluetooth SCO disconnected - microphone no longer available")
                                    // Note: Device disconnection will be handled by other broadcasts
                                }
                                
                                AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                                    LogUtils.d(CLASS_NAME, "Bluetooth SCO connecting...")
                                }
                                
                                else -> {
                                    LogUtils.d(CLASS_NAME, "Bluetooth SCO state: $scoState")
                                }
                            }
                        }
                    }
                    
                    // Handle any device disconnection - send events to React Native for device list updates
                    if (deviceId != null) {
                        LogUtils.d(CLASS_NAME, "Device disconnected: $deviceId (selected: ${deviceId == lastSelectedDeviceId})")
                        if (deviceName != null) {
                            LogUtils.d(CLASS_NAME, "Device name: $deviceName, type: $deviceType")
                        }
                        
                        // Log the disconnection for debugging
                        logDeviceDisconnection(deviceId, action ?: "unknown")
                        
                        // Send device disconnection event to React Native (for UI updates)
                        handleDeviceDisconnectionEvent(deviceId)
                        
                        // If this was the currently selected device, also notify delegate for recording interruption
                        if (deviceId == lastSelectedDeviceId) {
                            LogUtils.d(CLASS_NAME, "Currently selected device disconnected - notifying delegate: $deviceId")
                            // Launch a coroutine to call the suspend function
                            coroutineScope.launch {
                                try {
                                    handleDeviceDisconnection(deviceId)
                                } catch (e: Exception) {
                                    LogUtils.e(CLASS_NAME, "Error handling device disconnection: ${e.message}", e)
                                }
                            }
                        }
                    }
                    
                    // Force refresh the device list
                    forceRefreshAudioDevices()
                }
            }
            
            context.registerReceiver(deviceReceiver, filter)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                registerUsbAudioDeviceCallback()
            }
            LogUtils.d(CLASS_NAME, "Started monitoring device changes")
        } catch (e: Exception) {
            LogUtils.e(CLASS_NAME, "Error starting device monitoring: ${e.message}", e)
        }
    }

    /**
     * Use AudioManager's route callback as the source of truth. USB broadcasts
     * can precede audio-policy registration and are only used to cache metadata.
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun isMonitoredExternalInput(device: AudioDeviceInfo): Boolean =
        device.isSource && (isUsbAudioDevice(device) ||
            device.type == AudioDeviceInfo.TYPE_WIRED_HEADSET)

    @RequiresApi(Build.VERSION_CODES.M)
    private fun monitoredExternalDeviceKey(device: AudioDeviceInfo): String =
        "${mapDeviceType(device)}:${getDeviceName(device).trim().lowercase()}"

    @RequiresApi(Build.VERSION_CODES.M)
    private fun registerUsbAudioDeviceCallback() {
        val existingDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter(::isMonitoredExternalInput)
        usbAudioDeviceIds.clear()
        existingDevices.forEach { device ->
            val id = device.id.toString()
            usbAudioDeviceIds.add(id)
            usbAudioDeviceNamesById[id] = getDeviceName(device)
            usbAudioDeviceKeysById[id] = monitoredExternalDeviceKey(device)
        }

        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                scheduleUsbDeviceScan()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                scheduleUsbDeviceScan()
            }
        }
        audioDeviceCallback = callback
        audioManager.registerAudioDeviceCallback(callback, mainHandler)
    }

    private fun scheduleUsbDeviceScan() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        pendingUsbDeviceScan?.let(mainHandler::removeCallbacks)
        val scan = Runnable {
            val currentDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .filter(::isMonitoredExternalInput)
            val currentById = currentDevices.associateBy { it.id.toString() }
            val currentIds = currentById.keys
            val addedIds = currentIds - usbAudioDeviceIds
            val removedIds = usbAudioDeviceIds - currentIds
            val sameScanReplacementKeys = removedIds.mapNotNull { removedId ->
                usbAudioDeviceKeysById[removedId]
            }.toSet()
            val broadcastName = if (addedIds.size == 1) {
                pendingAttachedUsbDeviceKeys.singleOrNull()?.let(usbNamesByKey::get)
            } else null

            addedIds.forEach { id ->
                val device = currentById[id] ?: return@forEach
                val deviceKey = monitoredExternalDeviceKey(device)
                val replacement = pendingExternalRemovals.entries.firstOrNull { it.value.first == deviceKey }
                if (replacement != null) {
                    mainHandler.removeCallbacks(replacement.value.second)
                    pendingExternalRemovals.remove(replacement.key)
                    usbAudioDeviceNamesById.remove(replacement.key)
                    usbAudioDeviceKeysById.remove(replacement.key)
                    deviceCapabilitiesCache.keys.removeAll { it.startsWith("${replacement.key}:") }
                    LogUtils.d(
                        CLASS_NAME,
                        "External input re-enumerated without disconnect: " +
                            "${replacement.key} -> $id (${getDeviceName(device)})"
                    )
                }
                // UsbManager knows the consumer-facing product name before the
                // audio-policy route appears. It is safe to associate it only
                // when both sides report exactly one new device.
                val name = broadcastName?.takeIf { isUsbAudioDevice(device) } ?: getDeviceName(device)
                usbAudioDeviceNamesById[id] = name
                usbAudioDeviceKeysById[id] = deviceKey
                LogUtils.d(CLASS_NAME, "External input available: $name (ID: $id, type: ${device.type})")
                if (replacement == null && deviceKey !in sameScanReplacementKeys) {
                    handleDeviceConnection(id)
                }
            }
            removedIds.forEach { id ->
                val name = usbAudioDeviceNamesById[id] ?: "unknown"
                val deviceKey = usbAudioDeviceKeysById[id]
                if (deviceKey != null && deviceKey in sameScanReplacementKeys) {
                    usbAudioDeviceNamesById.remove(id)
                    usbAudioDeviceKeysById.remove(id)
                    deviceCapabilitiesCache.keys.removeAll { it.startsWith("$id:") }
                    LogUtils.d(CLASS_NAME, "External input re-enumerated in same scan: $id ($name)")
                    return@forEach
                }
                val removal = Runnable {
                    pendingExternalRemovals.remove(id)
                    usbAudioDeviceNamesById.remove(id)
                    usbAudioDeviceKeysById.remove(id)
                    deviceCapabilitiesCache.keys.removeAll { it.startsWith("$id:") }
                    // A replacement with the same logical device key may have
                    // appeared after this scan but before the grace timer.
                    if (deviceKey != null && currentMonitoredExternalDeviceKey(deviceKey)) {
                        LogUtils.d(CLASS_NAME, "External input removal canceled after delayed replacement: $id")
                        return@Runnable
                    }
                    LogUtils.d(CLASS_NAME, "External input removed: $name (ID: $id)")
                    handleDeviceDisconnectionEvent(id)
                    if (id == lastSelectedDeviceId) {
                        coroutineScope.launch {
                            try {
                                handleDeviceDisconnection(id)
                            } catch (e: Exception) {
                                LogUtils.e(CLASS_NAME, "Error handling USB disconnection: ${e.message}", e)
                            }
                        }
                    }
                    if (pendingExternalRemovals.isEmpty()) {
                        onDeviceConnected?.invoke("inventory")
                    }
                }
                if (deviceKey != null) {
                    pendingExternalRemovals[id]?.second?.let(mainHandler::removeCallbacks)
                    pendingExternalRemovals[id] = deviceKey to removal
                    LogUtils.d(
                        CLASS_NAME,
                        "Delaying external input removal for $EXTERNAL_DEVICE_REPLACEMENT_GRACE_MS ms: " +
                            "$name (ID: $id, key: $deviceKey)"
                    )
                    mainHandler.postDelayed(removal, EXTERNAL_DEVICE_REPLACEMENT_GRACE_MS)
                } else {
                    removal.run()
                }
            }
            usbAudioDeviceIds.clear()
            usbAudioDeviceIds.addAll(currentIds)
            if (addedIds.isNotEmpty()) pendingAttachedUsbDeviceKeys.clear()
            forceRefreshAudioDevices()
            // Also refresh when only the Bluetooth endpoint changed. Its physical
            // HEADSET entry remains in the inventory while SCO is inactive.
            // A removal is held during the replacement grace period. Avoid
            // publishing an intermediate inventory without the selected input;
            // that would make JS clear the user's route before the replacement
            // AudioDeviceInfo id arrives.
            if (pendingExternalRemovals.isEmpty()) {
                onDeviceConnected?.invoke("inventory")
            }
            pendingUsbDeviceScan = null
        }
        pendingUsbDeviceScan = scan
        mainHandler.postDelayed(scan, USB_DEVICE_CHANGE_DEBOUNCE_MS)
    }

    /**
     * Helper method to find a device by ID
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun findDeviceById(deviceId: String): AudioDeviceInfo? {
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.id.toString() == deviceId }
    }
    
    /**
     * Finds a BluetoothDevice in the list of audio devices
     */
    @RequiresApi(Build.VERSION_CODES.M)
    private fun findBluetoothDevice(device: BluetoothDevice): AudioDeviceInfo? {
        try {
            val audioDevices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            val bluetoothDevices = audioDevices.filter { 
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
            }
            
            // First try to match by address
            val foundByAddress = bluetoothDevices.firstOrNull { 
                it.address == device.address 
            }
            
            if (foundByAddress != null) {
                return foundByAddress
            }
            
            // If no match by address, try by name
            val deviceName = device.name
            if (deviceName != null) {
                return bluetoothDevices.firstOrNull { 
                    getDeviceName(it).contains(deviceName, ignoreCase = true)
                }
            }
            
            return null
        } catch (e: Exception) {
            LogUtils.e(CLASS_NAME, "Error finding Bluetooth device: ${e.message}", e)
            return null
        }
    }
    
    /**
     * Cleanup resources
     */
    fun cleanup() {
        // Stop monitoring device changes
        stopMonitoringDeviceChanges()
        pendingUsbDeviceScan?.let(mainHandler::removeCallbacks)
        pendingUsbDeviceScan = null
        pendingExternalRemovals.values.forEach { mainHandler.removeCallbacks(it.second) }
        pendingExternalRemovals.clear()
        audioDeviceCallback?.let { callback ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                audioManager.unregisterAudioDeviceCallback(callback)
            }
        }
        audioDeviceCallback = null

        bluetoothHeadsetProfile?.let { profile ->
            try {
                BluetoothAdapter.getDefaultAdapter()?.closeProfileProxy(
                    BluetoothProfile.HEADSET,
                    profile
                )
            } catch (_: SecurityException) {
                // Permission can be revoked before teardown.
            }
        }
        bluetoothHeadsetProfile = null
        
        // Stop Bluetooth SCO if active
        if (audioManager.isBluetoothScoOn) {
            audioManager.stopBluetoothSco()
            audioManager.isBluetoothScoOn = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun currentMonitoredExternalDeviceKey(key: String): Boolean =
        audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter(::isMonitoredExternalInput)
            .any { monitoredExternalDeviceKey(it) == key }
    
    /**
     * Stops monitoring device connection/disconnection events
     */
    private fun stopMonitoringDeviceChanges() {
        if (deviceReceiver != null) {
            try {
                context.unregisterReceiver(deviceReceiver)
                deviceReceiver = null
                LogUtils.d(CLASS_NAME, "Stopped monitoring device changes")
            } catch (e: Exception) {
                LogUtils.e(CLASS_NAME, "Error stopping device monitoring: ${e.message}", e)
            }
        }
    }
    
    /**
     * Log current audio state for debugging
     */
    fun logAudioState() {
        try {
            LogUtils.d(CLASS_NAME, "--- Current Audio State ---")
            LogUtils.d(CLASS_NAME, "BluetoothScoOn: ${audioManager.isBluetoothScoOn}")
            LogUtils.d(CLASS_NAME, "WiredHeadsetOn: ${audioManager.isWiredHeadsetOn}")
            LogUtils.d(CLASS_NAME, "BluetoothHeadsetConnected: ${isBluetoothHeadsetConnected()}")
            LogUtils.d(CLASS_NAME, "LastSelectedDeviceId: $lastSelectedDeviceId")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                LogUtils.d(CLASS_NAME, "Available input devices: ${devices.size}")
                
                var usbDevicesCount = 0
                var bluetoothDevicesCount = 0
                var wiredDevicesCount = 0
                
                devices.forEachIndexed { index, device ->
                    val deviceName = getDeviceName(device)
                    val deviceType = mapDeviceType(device)
                    val isSource = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) device.isSource else true
                    
                    when (device.type) {
                        AudioDeviceInfo.TYPE_USB_DEVICE, 
                        AudioDeviceInfo.TYPE_USB_ACCESSORY,
                        AudioDeviceInfo.TYPE_USB_HEADSET -> usbDevicesCount++
                        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> bluetoothDevicesCount++
                        AudioDeviceInfo.TYPE_WIRED_HEADSET,
                        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> wiredDevicesCount++
                    }
                    
                    LogUtils.d(CLASS_NAME, "Device $index: $deviceName (ID: ${device.id}, Type: $deviceType, IsSource: $isSource)")
                    
                    // Log address if available (helps track bluetooth devices)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val address = device.address
                        if (address != null) {
                            LogUtils.d(CLASS_NAME, "  Address: ${address}")
                        }
                    }
                    
                    // For M+, log detailed capabilities
                    try {
                        val sampleRates = device.sampleRates?.joinToString(", ") ?: "Unknown"
                        val channelCounts = device.channelCounts?.joinToString(", ") ?: "Unknown"
                        LogUtils.d(CLASS_NAME, "  Capabilities: SampleRates=[$sampleRates], Channels=[$channelCounts]")
                    } catch (e: Exception) {
                        LogUtils.d(CLASS_NAME, "  Error getting capabilities: ${e.message}")
                    }
                }
                
                LogUtils.d(CLASS_NAME, "Device Counts: USB=$usbDevicesCount, Bluetooth=$bluetoothDevicesCount, Wired=$wiredDevicesCount")
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val commsDevice = audioManager.communicationDevice
                    if (commsDevice != null) {
                        LogUtils.d(CLASS_NAME, "Communication device: ${getDeviceName(commsDevice)} (ID: ${commsDevice.id})")
                    } else {
                        LogUtils.d(CLASS_NAME, "No communication device set")
                    }
                }
            }
            
            // Log audio system properties
            val mode = when (audioManager.mode) {
                AudioManager.MODE_NORMAL -> "NORMAL"
                AudioManager.MODE_RINGTONE -> "RINGTONE"
                AudioManager.MODE_IN_CALL -> "IN_CALL"
                AudioManager.MODE_IN_COMMUNICATION -> "IN_COMMUNICATION"
                else -> "UNKNOWN(${audioManager.mode})"
            }
            
            LogUtils.d(CLASS_NAME, "AudioManager Mode: $mode")
            LogUtils.d(CLASS_NAME, "-------------------------")
        } catch (e: Exception) {
            LogUtils.e(CLASS_NAME, "Error logging audio state: ${e.message}", e)
        }
    }
    
    /**
     * Log a device disconnection event for better debugging
     */
    private fun logDeviceDisconnection(deviceId: String, reason: String) {
        LogUtils.d(CLASS_NAME, "=== DEVICE DISCONNECTION ===")
        LogUtils.d(CLASS_NAME, "Device ID: $deviceId")
        LogUtils.d(CLASS_NAME, "Reason: $reason")
        LogUtils.d(CLASS_NAME, "Last Selected Device ID: $lastSelectedDeviceId")
        
        // Get device info if possible
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val device = findDeviceById(deviceId)
            if (device != null) {
                LogUtils.d(CLASS_NAME, "Disconnected device: ${getDeviceName(device)}")
                LogUtils.d(CLASS_NAME, "Device type: ${mapDeviceType(device)} (raw type: ${device.type})")
            } else {
                LogUtils.d(CLASS_NAME, "Device ID $deviceId no longer found in device list")
            }
        }
        
        // Check current device after disconnection
        val currentDevice = getCurrentInputDeviceInternal()
        if (currentDevice != null) {
            LogUtils.d(CLASS_NAME, "Current device after disconnection: ${currentDevice["name"]} (ID: ${currentDevice["id"]})")
        } else {
            LogUtils.d(CLASS_NAME, "No current device found after disconnection")
        }
        
        logAudioState()
        LogUtils.d(CLASS_NAME, "==========================")
    }
    
    /**
     * Handles audio device disconnection based on the recording configuration
     */
    private suspend fun handleDeviceDisconnection(deviceId: String) {
        // Always pause on device disconnection - simpler approach
        LogUtils.d(CLASS_NAME, "Device disconnected: $deviceId. Pausing recording.")
        delegate?.onDeviceDisconnected(deviceId)
    }
    
    /**
     * Handles audio device connection
     */
    private fun handleDeviceConnection(deviceId: String) {
        LogUtils.d(CLASS_NAME, "Device connected: $deviceId")
        onDeviceConnected?.invoke(deviceId)
    }
    
    /**
     * Handles audio device disconnection (for React Native events)
     */
    private fun handleDeviceDisconnectionEvent(deviceId: String) {
        LogUtils.d(CLASS_NAME, "Device disconnected: $deviceId")
        onDeviceDisconnected?.invoke(deviceId)
    }
    

}

internal data class CommunicationDeviceDescriptor(
    val id: Int,
    val type: Int,
    val address: String,
    val name: String
)

private fun AudioDeviceInfo.toCommunicationDeviceDescriptor() =
    CommunicationDeviceDescriptor(
        id = id,
        type = type,
        address = address.orEmpty(),
        name = productName?.toString().orEmpty()
    )

internal fun preferredCommunicationSinkTypes(inputType: Int): List<Int> = when (inputType) {
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> listOf(
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    )
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> listOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
    AudioDeviceInfo.TYPE_WIRED_HEADSET -> listOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        // Lenovo exposes the wired playback endpoint as AUDIO_DEVICE_OUT_LINE.
        AudioDeviceInfo.TYPE_LINE_ANALOG
    )
    AudioDeviceInfo.TYPE_USB_DEVICE -> listOf(
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_LINE_ANALOG
    )
    AudioDeviceInfo.TYPE_USB_HEADSET -> listOf(
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_LINE_ANALOG
    )
    AudioDeviceInfo.TYPE_BLE_HEADSET -> listOf(AudioDeviceInfo.TYPE_BLE_HEADSET)
    else -> listOf(inputType)
}

internal fun selectCommunicationSink(
    requested: CommunicationDeviceDescriptor,
    availableSinks: List<CommunicationDeviceDescriptor>
): Int? {
    val preferredTypes = preferredCommunicationSinkTypes(requested.type)
    val candidates = availableSinks
        .filter { it.type in preferredTypes }
        .sortedBy { preferredTypes.indexOf(it.type) }
    if (candidates.isEmpty()) return null

    if (requested.address.isNotBlank()) {
        candidates.firstOrNull { it.address == requested.address }?.let { return it.id }
    }
    val isBuiltIn = requested.type == AudioDeviceInfo.TYPE_BUILTIN_MIC
    if (!isBuiltIn && requested.name.isNotBlank()) {
        candidates.filter { it.name == requested.name }.singleOrNull()?.let { return it.id }
    }

    val wiredOrUsb = requested.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
        requested.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
        requested.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        requested.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        requested.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
    return if (isBuiltIn || wiredOrUsb || candidates.size == 1) candidates.first().id else null
}

internal fun attemptCommunicationDeviceSelection(
    selection: () -> Boolean,
    onAccepted: () -> Unit = {}
): Boolean {
    val accepted = try {
        selection()
    } catch (_: Exception) {
        false
    }
    if (accepted) onAccepted()
    return accepted
}
