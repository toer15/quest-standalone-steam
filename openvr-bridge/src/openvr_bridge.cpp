// OpenVR -> OpenXR bridge (STUB). Idea borrowed from OpenComposite:
// https://gitlab.com/znixian/OpenOVR
//
// SteamVR games call openvr_api.dll exports (VR_InitInternal2, VR_GetGenericInterface, ...)
// and then use C++ interfaces like IVRSystem / IVRCompositor. We implement those
// on top of OpenXR so the Quest's native runtime does the rendering.
//
// Big problem: the game is an x86_64 *Windows* process inside Wine+Box64, while OpenXR on
// Quest is an arm64 *Android* library. So this needs two halves:
//   (a) a Windows-side openvr_api.dll (built with mingw) that marshals calls, and
//   (b) this arm64 side that owns the XrSession, connected via a Wine unix-lib thunk
//       or shared memory. Textures need D3D11 -> Vulkan (DXVK) -> XrSwapchain sharing.

#include <cstdint>
#include <cstdio>

namespace bridge {

enum EVRInitError : int32_t { VRInitError_None = 0, VRInitError_Init_HmdNotFound = 108 };

struct Pose { float m[3][4]; bool valid; };

// TODO: xrCreateInstance / xrGetSystem / xrCreateSession with XR_KHR_vulkan_enable2
static bool g_sessionReady = false;

bool InitOpenXR() {
    std::fprintf(stderr, "[openvr-bridge] InitOpenXR: not implemented\n");
    return false;
}

// IVRSystem::GetRecommendedRenderTargetSize -> xrEnumerateViewConfigurationViews
void GetRecommendedRenderTargetSize(uint32_t* w, uint32_t* h) { *w = 2064; *h = 2208; /* Quest 3 per-eye */ }

// IVRCompositor::WaitGetPoses -> xrWaitFrame + xrBeginFrame + xrLocateViews/xrLocateSpace
int32_t WaitGetPoses(Pose* poses, uint32_t count) {
    for (uint32_t i = 0; i < count; ++i) poses[i].valid = false; // TODO
    return 0;
}

// IVRCompositor::Submit -> copy into XrSwapchain image, xrEndFrame after both eyes
int32_t Submit(int eye, void* texture) { (void)eye; (void)texture; return 0; }

} // namespace bridge

extern "C" {
// Real openvr_api exports we must eventually provide:
int32_t VR_InitInternal2(bridge::EVRInitError* err, int32_t /*appType*/, const char* /*startupInfo*/) {
    bridge::g_sessionReady = bridge::InitOpenXR();
    *err = bridge::g_sessionReady ? bridge::VRInitError_None : bridge::VRInitError_Init_HmdNotFound;
    return bridge::g_sessionReady ? 1 : 0;
}
void VR_ShutdownInternal() { /* TODO xrDestroySession / xrDestroyInstance */ }
bool VR_IsHmdPresent() { return true; }
bool VR_IsRuntimeInstalled() { return true; }
void* VR_GetGenericInterface(const char* version, bridge::EVRInitError* err) {
    // TODO: return vtable objects for "IVRSystem_0xx", "IVRCompositor_0xx", "IVRInput_0xx"...
    std::fprintf(stderr, "[openvr-bridge] requested %s\n", version);
    *err = bridge::VRInitError_Init_HmdNotFound;
    return nullptr;
}
}
