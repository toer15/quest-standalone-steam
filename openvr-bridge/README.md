# openvr-bridge: SteamVR games → Quest 3 headset

Lets a SteamVR (OpenVR) game running in Wine + Box64 on the Quest render into the headset through Meta's own OpenXR runtime. Same idea as [OpenComposite](https://gitlab.com/znixian/OpenOVR), but across two processes: the Windows x86_64 game and the Android arm64 app.

```
 Windows game (Wine/Box64, x86_64)                      Quest app (Android, arm64)
 ┌──────────────────────────────┐   vrbridge.shm   ┌────────────────────────────────┐
 │ openvr_api.dll  (win/)       │◀── poses ───────│ NativeActivity libqssxr.so     │
 │  IVRSystem / IVRCompositor   │                  │  OpenXR session (Meta runtime) │
 │  IVRInput / IVRChaperone     │─── frames ─────▶│  head/eye/Touch tracking       │
 │  D3D11 → staging → memcpy    │   (2 buffers)    │  swapchain upload + timewarp   │
 └──────────────────────────────┘                  └────────────────────────────────┘
```

- `include/qss_vr_shm.h`: the shared-memory layout. Both sides map `<app files>/vrbridge.shm`. The app passes its path to the game in `QSS_VR_SHM`.
- `win/`: the replacement `openvr_api.dll` (mingw-w64, `win/build.sh`). The app copies it over each 64-bit `openvr_api.dll` in the game folder and keeps the original as `*.qss-orig`.
  - `WaitGetPoses` waits for the next headset pose, so the game runs at the headset's pace.
  - `Submit` copies each eye's D3D11 texture (any bounds, MSAA resolved; RGBA8, BGRA8, RGBA16F and RGB10A2) into shared memory, tagged with the pose it was rendered with.
  - SteamVR Input: reads the game's action manifest and its `oculus_touch` default binding (falls back to Index or Vive bindings, then to guessing by action name). Legacy `GetControllerState` and button events work too.
  - Unity's `FnTable:` interfaces are generated from `openvr.h` (`win/gen_fntables.py`).
  - Interfaces not implemented yet (overlays, render models, screenshots…) get a stub where every call returns 0.
- `../app/src/quest/cpp/qss_xr.cpp`: the headset side. OpenXR with OpenGL ES. It publishes poses every display frame, uploads new game frames into the swapchains, and submits a projection layer using the frame's render pose, so the runtime reprojects. While it waits for the game it shows dark blue, then teal once the game has connected.
- `test/`: `run_wine_test.sh` runs a fake Quest (`fake_quest.c`) and a small D3D11 VR game (`vr_test.cpp`) under Wine. It checks init, poses, controller input, haptics plumbing and that both eyes' pixels arrive. CI runs it on every build.

## Limits (first version)
- 64-bit D3D11 games only. OpenGL, Vulkan and D3D12 submissions are logged and skipped.
- Frames are copied through the CPU (GPU → staging → shared memory → GPU), so it's slow. The default render size is 1216×1280 per eye to keep it manageable. Zero-copy sharing (AHardwareBuffer / dma-buf between DXVK's Vulkan and the XR swapchain) is the next step for speed.
- Only exact current interface versions (IVRSystem_022, IVRCompositor_028, IVRInput_010, IVRChaperone_004) are fully correct. Older versions get the current one with a warning in the log.
- No skeletal hand data, overlays or dashboard.
- Games that need the Steam client running (Steamworks DRM) won't start.

Third-party: `third_party/openvr/` is Valve's OpenVR SDK headers v2.5.1 (BSD-3-Clause, see LICENSE there).
