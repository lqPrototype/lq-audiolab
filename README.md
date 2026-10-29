# audiolab

## Fork information

This repository is a fork of the upstream [`deeeed/audiolab`](https://github.com/deeeed/audiolab) project.

- Upstream repository: [https://github.com/deeeed/audiolab](https://github.com/deeeed/audiolab)
- This fork: [https://github.com/lqPrototype/lq-audiolab](https://github.com/lqPrototype/lq-audiolab)
- The fork-specific Android audio changes documented below are maintained primarily in the `lq-audio-studio` package. The original project attribution, documentation, and license remain intact.

## Fork-specific changes

### [4.0.3] - 2026-10-29

#### Added

- Added Android `AudioDeviceCallback` monitoring for Bluetooth, wired-headset, and USB input routes. Route callbacks are treated as the source of truth, while USB broadcasts preserve the physical device name and metadata until Android publishes the corresponding audio endpoint.
- Added stable Bluetooth input identifiers based on the physical headset address (`bluetooth:<address>`). The selected headset now survives SCO endpoint removal and recreation instead of depending on a transient `AudioDeviceInfo.id`.
- Added Bluetooth HEADSET-profile discovery and cached USB descriptors so device lists report the real headset or USB product name when an OEM exposes the tablet model, `h2w`, or another generic audio label.
- Added routing coverage for wired inputs whose paired playback endpoint is reported as `TYPE_LINE_ANALOG` on Lenovo devices.

#### Changed

- Android input selection now waits for the Bluetooth HFP/SCO route and input endpoint to become ready before resolving the selection request. Android 12 and newer use `setCommunicationDevice`; earlier versions use Bluetooth SCO routing.
- Every new `AudioRecord` session explicitly resolves the latest endpoint and calls `setPreferredDevice`. Compressed `MediaRecorder` capture uses the same selected input and rejects startup when Android refuses that route.
- Device inventory refreshes are serialized in JavaScript. Native events that arrive during an active refresh schedule one final refresh instead of starting overlapping enumerations or dropping the latest device state.
- Device enumeration no longer opens SCO or changes the selected route as a side effect.

#### Fixed

- Fixed external inputs appearing late or not appearing while recording by refreshing the inventory directly from Android route-change callbacks.
- Fixed transient wired/USB removal notifications and selection fallback when an OEM re-enumerates the same physical device with a new endpoint ID. Equivalent replacement endpoints are reconciled during a short grace period.
- Fixed Bluetooth microphones being displayed as the tablet model and stale input IDs causing capture to fall back to the built-in microphone after a route change.
- Fixed switching away from Bluetooth leaving the previous SCO route active when Android had not yet published the new wired/USB playback endpoint.

### [4.0.2] - 2026-10-23

#### Changed

- Republished the Android 3A and WebRTC APM integration as version `4.0.2`; runtime behavior remains unchanged from `4.0.1`.
- Documented the upstream fork origin and the maintained repository URL.

### [4.0.1] - 2026-10-23

#### Added

- Added configurable Android 3A processing through `android.audioProcessing`. Callers can select `system`, `systemWithSoftwareFallback`, or `software` mode and independently enable echo cancellation, noise suppression, and automatic gain control.
- Bundled an ARM64 WebRTC Audio Processing Module based on WebRTC Audio Processing 1.3. It provides AEC3, noise suppression, and AGC2 for mono PCM16 capture in 10 ms frames when the corresponding Android platform effect is unavailable or software processing is explicitly requested.
- Added `audioProcessing` diagnostics to Android recording start results and status payloads. Each effect reports `disabled`, `system`, `webrtc`, or `unavailable`, together with the active sample rate, channel count, frame duration, and software-AEC reverse-stream state.
- Added a native playback-reference entry point for software AEC. Software AEC is reported as active only after real speaker PCM reaches the WebRTC reverse stream; creating an AEC3 processor alone is not treated as successful echo cancellation.

#### Changed

- Android `voiceCommunication` capture now defaults to `systemWithSoftwareFallback`: verified platform AEC/NS/AGC effects remain preferred, while only effects that failed to attach are delegated to WebRTC APM.
- WebRTC software processing is applied before primary WAV writes, JS PCM streaming, and audio analysis so all three consumers receive the same processed PCM. Android `MediaRecorder` compressed output remains on its separate capture path and is explicitly reported as unprocessed.
- Platform effects and WebRTC processors are rebuilt for each new `AudioRecord` session, including USB route changes and interruption recovery, and are released together during recorder teardown.

### [4.0.0] - 2026-10-21

#### Added

- Android `audioSource: 'voiceCommunication'` now opens `VOICE_COMMUNICATION` capture and explicitly attaches every available platform AEC, noise-suppression, and automatic-gain-control effect to the `AudioRecord` session. Each effect is checked and enabled independently, logged with its OEM implementation, released before the recorder, and safely skipped when unsupported or rejected by the device. Effects are reattached when Android rebuilds capture after a route change or interruption.
