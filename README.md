# audiolab

## Fork information

This repository is a fork of the upstream [`deeeed/audiolab`](https://github.com/deeeed/audiolab) project.

- Upstream repository: [https://github.com/deeeed/audiolab](https://github.com/deeeed/audiolab)
- This fork: [https://github.com/lqPrototype/lq-audiolab](https://github.com/lqPrototype/lq-audiolab)
- The fork-specific Android audio changes documented below are maintained primarily in the `lq-audio-studio` package. The original project attribution, documentation, and license remain intact.

## Fork-specific changes

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
