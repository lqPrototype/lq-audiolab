# Third-party notices

The repository contains build scripts and an APM wrapper, not a vendored WebRTC
tree or redistributable SDK. Native builds retrieve the pinned dependency source
in `dependencies.json` and must ship its original notices with their artifacts.

- WebRTC Audio Processing: the helloooideeeeea/webrtc-audio-processing fork,
  commit `f8efa8486ac0dd463fb84e4e99be2e072c28adb8`, derived from the freedesktop
  AudioProcessing distribution and the WebRTC project. Preserve its COPYING,
  LICENSE, AUTHORS, PATENTS and included third-party notices.
- Abseil C++ 20230125.1 and Meson wrapdb port: Apache-2.0 source dependency;
  preserve its LICENSE and any notices from the pinned source/port.
- The upstream tree includes PFFFT, RNNoise and other components. The build
  collects their license/notice files with source-relative paths rather than
  assuming all embedded code has the top-level license.
- Android NDK C++ runtime is linked into the Android wrapper. Its upstream
  license notices must accompany redistribution; the build collects the NDK
  runtime/toolchain notice files separately.
- Harmony SDK and Apple SDK are external toolchains, not published in this
  repository. Only bundle the required target runtime when permitted, preserve
  its notices, and comply with the toolchain provider's distribution terms.

`LICENSE` in this repository covers the new wrapper/build code, not third-party
source, toolchains, or linked dependencies. Do not remove `licenses/` from a
published native artifact.
