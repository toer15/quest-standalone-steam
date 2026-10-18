# QSS VR lab: our own local Windows-to-Quest test path

This is original experimental code added alongside the existing v0.19.3 game and VR paths. It is
not based on WinlatorXR's XrAPI, and it does not call GameHub, Steam Link or another
Android app. It continues to use the app's existing Box64/Wine installation for
Windows execution. The OpenXR loader is Khronos's standard loader; the headset's
OpenXR runtime is supplied by Meta.

**It is not an implementation of OpenVR or the Windows OpenXR API. No existing
SteamVR title is claimed compatible. We make no claim of being first.**

## What is implemented

- Native Android OpenXR instance, session state transitions, predicted stereo
  views, LOCAL reference space, Touch aim poses, triggers and B-to-exit action.
- An EGL/GLES compositor with separate eye swapchains and stereo projection
  layers. Eye sizes/FOV come from the runtime rather than hardcoded Quest pixels.
- A versioned little-endian transport for poses, FOV, controller state and two
  RGBA eye images. Private per-session files with atomic replacement connect the
  Windows process to the Android process. Nothing is sent over a network.
- A small ray-traced target test. Green targets move after a correctly aimed
  trigger press; controller locations and aim markers are visible. It can run as
  a native diagnostic or as a separate Windows x86_64 console program under Wine.
- Tracking-sequence matching: late guest images use their original source poses
  for OpenXR reprojection. Unmatched or older-than-200-ms images are not submitted.
- A shared session lease prevents the existing 2D display and VR test from using
  the shared Wine prefix concurrently. Game files are not patched.
- In-app launch controls, exit handling, private guest logs and a readable report
  distinguishing native test frames from actual guest frames received.

The stereo test uses **192 × 192 pixels per eye**, CPU ray tracing, synchronous
file I/O and texture uploads. This is a diagnostic transport, with substantial
latency/copy overhead. It is not a design for production game performance.

## Build

Use Linux, JDK 17, Gradle 8.7, Android SDK 34, NDK 24.0.8215888, CMake 3.22.1,
`g++`, Python 3 and `g++-mingw-w64-x86-64`. Dependencies resolve from the project's
existing Google/Maven repositories. OpenXR loader is pinned to `1.1.49`, the
coordinate used by the [official Khronos Android tutorial][tutorial]. The build
extracts that pinned AAR into its generated build directory and uses the AAR
supplied CMake package directly. This avoids AGP 8.5 rejecting its header-only
Prefab module. The same AAR supplies the manifest and packaged JNI loader.

```sh
bash tools/build-xr-lab.sh
gradle :launcher:testDebugUnitTest :app:testQuestDebugUnitTest :steamclient:test :app:assembleQuestRelease
```

The script first compiles and tests the portable core and Linux guest, then
cross-compiles `qss-vr-guest.exe` into the app's assets. It does not download or
bundle any commercial games. Use `--host-only` if the Windows compiler is absent;
that option does not generate a Windows executable. A Gradle build without the
asset can still offer the native diagnostic, but disables the guest test button.

The build workflow also runs the portable core, guest and startup checks, then
builds the signed Quest release APK. The lab uses an exclusive runtime lease:
close games and Steam before entering it. Normal Wine starts and automatic Steam
warm-up are blocked while the lab owns the prefix. The existing SteamVR bridge
is retained separately; this test path does not expand its game compatibility.

## Quest acceptance test

1. Build the APK, enable developer mode and sideload this same app. Use the same
   signing key to update an existing install; preserve data before any uninstall.
2. Open Settings → Runtime & launcher → **VR lab: native tracking + stereo test**.
   Verify true stereo, correct head movement, controller positions and aim
   markers. Aim at a green target and press a trigger. Press B to leave.
3. Open **View VR lab report**. Native mode should report native frames with zero
   guest frames. That is expected and is not evidence of Windows execution.
4. Install the existing runtime and run its console smoke test. With the Windows
   asset included, select **VR lab: local Windows stereo test**. It copies only
   our test executable into `C:\qss-lab`, starts it through the app's Wine and
   Box64, and passes the private directory through the standard Z: mapping.
5. Verify the same target game, then leave and inspect the report. Guest frame
   count and last guest sequence must advance. Read `guest.log` for actual Wine
   output. No rendering is claimed if only a process started or no image arrived.
6. Remove/reseat headset, pause/resume, exit while launching, test missing runtime,
   and relaunch. Confirm the Windows process stops and the 2D game path still works.

If OpenXR initialization fails, the native report records the failing call and
numeric result. If Windows execution fails, the guest error/log are appended to
the report. The test needs **no PC rendering during play**; building and initial
sideloading require development tools on another machine. The runtime installer
also needs internet and its upstream downloaded assets.

## Verified here and remaining gaps

See the root `BUILD-STATUS.md` for the exact checks actually executed for this
checkpoint. Passing the Linux tests verifies serialization, input/scene logic
and the file bridge; it does not establish Android ABI/linking, OpenXR support,
Wine execution, controller latency, sustained FPS or headset thermal behavior.

Existing games still require an actual OpenVR/Windows-OpenXR implementation,
correct exported interface versions/action bindings, and a graphics transfer path
from their real D3D/Vulkan textures into this compositor. GPU resource sharing,
audio, Steam client-dependent services and compatibility testing remain open.
The old `openvr-bridge` is explicitly still a stub and now fails honestly instead
of reporting a headset or successful texture submission when nothing exists.

Potential next implementation: use the validated tracking/frame boundary as a
reference to integrate a correctly licensed existing OpenVR implementation, then
replace CPU image copies with synchronized graphics resource transfer. This
requires engineering and Quest tests; adding DLL names alone cannot supply it.

## Sources and licensing

Original QSS lab code is provided under the MIT license in `xr-lab/LICENSE`.
The rest of the project retains its existing component licenses. The included
Windows test executable was cross-compiled with LLVM-MinGW 20261006 (UCRT,
x86_64, static runtime). Bundled runtime notices are in
`app/src/main/assets/third-party/guest-runtime/`. It has not been executed on Quest.

- [Official OpenXR Android tutorial][tutorial] and [sample CMake][cmake].
- [OpenXR loader and headers][sdk] (Apache-2.0/MIT upstream licenses).
- Existing Wine/Box64 and embedded Winlator display provenance remains in the
  root README and `embedded-display/UPSTREAM.md`. They are not original QSS work.

[tutorial]: https://github.com/KhronosGroup/OpenXR-Tutorials/blob/main/Chapter2/app/build.gradle
[cmake]: https://github.com/KhronosGroup/OpenXR-SDK-Source/blob/release-1.1.36/src/tests/hello_xr/CMakeLists.txt
[sdk]: https://github.com/KhronosGroup/OpenXR-SDK
