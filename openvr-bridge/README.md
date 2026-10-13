# openvr-bridge

Stub that would let SteamVR (OpenVR) games talk to the Quest 3's own OpenXR runtime instead of SteamVR — the same idea as [OpenComposite](https://gitlab.com/znixian/OpenOVR), but crossing an extra boundary: Windows x86_64 (in Wine/Box64) → Android arm64.

## Pieces needed
1. **Windows side `openvr_api.dll`** (mingw-w64 build) placed next to the game exe / overridden via `WINEDLLOVERRIDES=openvr_api=n`. Implements the exported C functions + interface vtables (IVRSystem, IVRCompositor, IVRInput, IVRRenderModels...).
2. **Thunk** from that DLL to native: a Wine *unixlib* (`__wine_unix_call`) so calls leave the emulated process and reach native arm64 code.
3. **Native side** (this `src/`): owns the OpenXR instance/session (Meta OpenXR loader), maps poses, input (Quest Touch → OpenVR actions), and swapchains.
4. **Textures**: game renders D3D11 → DXVK → Vulkan. Need to share that VkImage with the XrSwapchain (same VkDevice, via XR_KHR_vulkan_enable2).

Status: compiles to a shared lib with placeholder exports; nothing works yet.
