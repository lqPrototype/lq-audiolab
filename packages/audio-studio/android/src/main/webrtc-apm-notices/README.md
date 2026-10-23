# webrtc-apm-deps-build

Independent, manual GitHub Actions builds for WebRTC Audio Processing 1.3 and
the Aozora APM native interface. This repository does not contain the consuming
application, downloaded toolchains, source caches, or prebuilt binaries.

## Outputs

| Target | Artifact content | Baseline |
| --- | --- | --- |
| Android | `app-android/libs/<abi>/libaozora_apm.so` and C header | API 28, NDK r27c; ARM64 default |
| iOS | `app-ios/Frameworks/AozoraApm.xcframework` | iPhoneOS ARM64, iOS 14.0; no simulator |
| HarmonyOS | `app-harmony/libs/arm64-v8a/libaozora_apm.so`, required C++ runtime, N-API types and C header | ARM64 Linux-host Native SDK |

Android optionally builds the existing ARMv7 and x86 ABIs. No AAR, HAR or APK is
produced. Each ZIP includes license notices, `manifest.json`, per-file hashes,
architecture/dependency checks, and a separate archive SHA-256 file.

The common processing profile is mono signed PCM16, 10 ms frames, at 8/16/32/48
kHz. AEC3 is enabled; NS and AGC2 are optional. Render reference, capture
processing, stream delay and voice-detection state are exposed. The library
does not capture or play audio by itself.

## Manual GitHub builds

1. Open Actions, then **Build WebRTC APM native libraries**.
2. Choose **Run workflow** on the latest `main`.
3. Select `all` (default), `android`, `ios`, or `harmony`.
4. Check any combination of `android_arm64` (ARM64), `android_armv7` (ARMv7),
   and `android_x86` (x86). Only ARM64 is checked by default. Select at least
   one ABI when building Android; the checkboxes are ignored for iOS and Harmony.
   Choosing platform `all` builds all three platforms, not all Android ABIs.
5. Download the platform artifact from that run. Retention is seven days.

Platform jobs are independent. One failure does not cancel the others. A
separate Linux host smoke job exercises the shared C API on every manual run;
that is not a phone-device test. Native builds are **manual-only**. Push/PR only
runs Python syntax, configuration, archive-safety and workflow-routing checks.
Start a new run after fixes; re-running an old record uses its original commit.

### Harmony SDK configuration

Configure **this repository's** Settings / Secrets and variables / Actions:

- Secret `HARMONY_SDK_URL`: authorized HTTPS download URL for the Linux x64
  `commandline-tools-linux-x64-26.0.0.851.zip` bundle. A signed URL may expire.
- Variable `HARMONY_SDK_SHA256`:
  `ab604bd92721d5cbcafd154e6461d46b9f1b105e7b89eab04a9d046681198082`

Secrets in another repository are not automatically inherited. Set the URL
again here; do not post it in an issue or commit its query parameters. The script
verifies the archive and only extracts the Native SDK subtree. A `.har` package
or a Windows/macOS host SDK is not a Linux C/C++ toolchain. A local Linux build
may instead set `OHOS_SDK_NATIVE_DIR` to an already installed Native SDK.

If a run reports `Missing HARMONY_SDK_URL`, open
[this repository's Actions secrets](https://github.com/restia-aozora/webrtc-apm-deps-build/settings/secrets/actions),
choose **New repository secret**, enter `HARMONY_SDK_URL` as the name, and
paste a current, authorized HTTPS SDK download URL as the value without quotes.
The checksum belongs in the separate `HARMONY_SDK_SHA256` variable; a valid
checksum does not replace the download URL. This error is a configuration
preflight failure, not a native compiler failure. Start a new workflow run on
the updated `main` after configuring the secret to use the current inputs.

## Integration

### Android

Keep the existing Java/Kotlin class `com.aozora.audio.AozoraWebRtcApm` and its six
native methods: `nativeCreate`, `nativeProcessReverse`, `nativeProcessCapture`,
`nativeSetStreamDelay`, `nativeGetVoiceDetected`, and `nativeRelease`. Load
`aozora_apm` and install the `.so` for each supported ABI in the plugin's libs
directory. Preserve these class and method names in R8/ProGuard rules. Do not
rename JNI methods or install a library built for a different ABI.

### iOS

Add and embed/sign `AozoraApm.xcframework` with your application, then import
`AozoraApm` from Swift. The build only ad-hoc signs the library; it does not sign
your application or provide an Apple distribution identity.

```swift
import AozoraApm
let processor = aozora_apm_create(16000, 1, 1, 0)
// Feed 160 Int16 samples per render/capture call at 16 kHz.
aozora_apm_destroy(processor)
```

### HarmonyOS

Copy `app-harmony/libs/arm64-v8a` into the native libs location of your consuming
module, including any packaged `libc++_shared.so`. Add the bundled
`types/libaozora_apm` local type package as the module dependency named
`libaozora_apm.so`, then import it from ArkTS. The package's `index.d.ts` describes
`create`, `processReverse`, `processCapture`, `setStreamDelay`,
`getVoiceDetected` and `release`. Audio buffers are `Int16Array`, not raw pointer
numbers. This repository provides the native interface and types, not a full
UTS recorder implementation or microphone permissions configuration.

### Frame and lifetime rules

Feed the actual playback-reference PCM to the reverse-stream API and microphone
PCM to the capture API in matching 10 ms frames. Supply measured playback-path
delay before capture processing. Processing is in-place. A return of zero or
the WebRTC bad-stream-parameter warning indicates an accepted frame; other
negative results indicate errors. Voice state is -1 (unknown), 0 or 1.

Do not destroy a C/JNI handle concurrently with processing, and never reuse a
destroyed pointer/long handle. Release N-API handles explicitly when finished;
their finalizer also handles garbage collection. The wrapper serializes
processing on one instance but does not manage application audio threads.

## Local builds

Use Python 3.12+, Git, and the platform compiler. The script clones a fresh
pinned upstream commit and never edits an existing source checkout.

```bash
python -m pip install -r requirements-build.txt
python -m unittest discover -s tests -p 'test_*.py' -v
python scripts/build.py android --android-abis arm64-v8a --jobs 3
python scripts/build.py android --android-abis arm64-v8a armeabi-v7a --jobs 3
python scripts/build.py ios --jobs 3
python scripts/build.py harmony --jobs 3
python scripts/build.py host --jobs 3
```

Android requires `ANDROID_NDK_HOME` pointing to NDK `27.2.12479018`. Android
cross-compilation also supports Windows. iOS requires macOS/Xcode. Harmony and
the host smoke test require Linux. `--source-repository /path/to/local/clone`
can use a local clone without modifying it; the exact pinned commit remains
mandatory. Work files remain in ignored `.work/` for diagnosis; artifacts go to
ignored `dist/`. Do not upload work directories or SDK archives.

## Provenance and validation limits

The source commit and dependency hashes match the reference local build.
The known `absl_crc` dependency fix is applied with an exact-match, fail-closed
patch. The wrapper compiles inside upstream Meson using the same architecture
and platform definitions; static dependencies, including private architecture
libraries, are resolved by Meson's dependency graph rather than a guessed list.

Native build scripts check exports, architecture, dynamic dependencies and
Android 16 KB ELF load alignment. iOS also checks a Swift module import. Those
checks do **not** establish HBuilderX/UTS integration, 16 KB APK zip alignment,
device loading, acoustic echo-cancellation quality, or upload quota compliance.
`manifest.json` explicitly reports device runtime and UTS validation as
`NOT RUN`. Complete native CI success is known only after an actual platform
run succeeds; this repository's existence is not evidence of that success.

Keep `licenses/` with redistributed artifacts. See `THIRD_PARTY_NOTICES.md`.
