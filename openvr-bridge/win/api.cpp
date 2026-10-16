// openvr_api.dll exports. Games load this instead of Valve's openvr_api.dll.
#include "bridge.h"

#include <cstdlib>

namespace qss {
namespace {

// Interfaces we don't implement get an object whose every virtual method returns 0
// (false / "no error" / null). On x64 the caller cleans the stack, so one stub fits all.
uint64_t StubMethod() { return 0; }

struct StubObject {
    void** vtbl;
};

void** StubTable() {
    static void* table[256];
    static bool init = false;
    if (!init) {
        for (auto& p : table) p = (void*)&StubMethod;
        init = true;
    }
    return table;
}

StubObject* Stub() {
    static StubObject obj{StubTable()};
    return &obj;
}

bool g_inited = false;
uint32_t g_initToken = 1;

}  // namespace
}  // namespace qss

#include "fntables.inc"

using namespace vr;

namespace qss {
namespace {

bool HasPrefix(const char* s, const char* p) { return strncmp(s, p, strlen(p)) == 0; }

void* Lookup(const char* version, EVRInitError* err) {
    bool fn = HasPrefix(version, "FnTable:");
    const char* v = fn ? version + 8 : version;
    struct Entry { const char* prefix; const char* current; void* cpp; void* table; };
    const Entry entries[] = {
        {"IVRSystem_", IVRSystem_Version, System(), g_fntable_IVRSystem},
        {"IVRCompositor_", IVRCompositor_Version, Compositor(), g_fntable_IVRCompositor},
        {"IVRInput_", IVRInput_Version, Input(), g_fntable_IVRInput},
        {"IVRChaperone_", IVRChaperone_Version, Chaperone(), g_fntable_IVRChaperone},
    };
    for (auto& e : entries) {
        if (!HasPrefix(v, e.prefix)) continue;
        if (strcmp(v, e.current) != 0) {
            // Older interface versions mostly share a prefix of the current vtable; try it and say so.
            Log("WARNING: game asked for %s, bridge implements %s; trying it", v, e.current);
        }
        if (err) *err = VRInitError_None;
        return fn ? e.table : e.cpp;
    }
    if (err) *err = VRInitError_None;
    Log("stubbed interface %s (all calls return 0)", version);
    return fn ? (void*)StubTable() : (void*)Stub();
}

}  // namespace
}  // namespace qss

extern "C" {

__declspec(dllexport) uint32_t VR_InitInternal2(EVRInitError* err, EVRApplicationType type, const char*) {
    qss::Log("VR_Init (app type %d)", (int)type);
    if (!qss::g_shm.Open()) {
        if (err) *err = VRInitError_Init_HmdNotFound;
        return 0;
    }
    if (!qss::g_inited) {
        qss::g_inited = true;
        qss::g_shm.s->game_connected = 1;
        char exe[MAX_PATH] = {};
        GetModuleFileNameA(nullptr, exe, MAX_PATH);
        const char* base = strrchr(exe, '\\');
        strncpy(qss::g_shm.s->app_name, base ? base + 1 : exe, sizeof qss::g_shm.s->app_name - 1);
        for (TrackedDeviceIndex_t i = 0; i < qss::kDeviceCount; ++i) qss::PushEvent(VREvent_TrackedDeviceActivated, i);
    }
    if (err) *err = VRInitError_None;
    return ++qss::g_initToken;
}

__declspec(dllexport) uint32_t VR_InitInternal(EVRInitError* err, EVRApplicationType type) {
    return VR_InitInternal2(err, type, nullptr);
}

__declspec(dllexport) void VR_ShutdownInternal() {
    qss::Log("VR_Shutdown");
    if (qss::g_shm.s) qss::g_shm.s->game_connected = 0;
    qss::g_inited = false;
}

__declspec(dllexport) bool VR_IsHmdPresent() { return true; }
__declspec(dllexport) bool VR_IsRuntimeInstalled() { return true; }

__declspec(dllexport) void* VR_GetGenericInterface(const char* version, EVRInitError* err) {
    if (!version) { if (err) *err = VRInitError_Init_InvalidInterface; return nullptr; }
    return qss::Lookup(version, err);
}

__declspec(dllexport) bool VR_IsInterfaceVersionValid(const char*) { return true; }
__declspec(dllexport) uint32_t VR_GetInitToken() { return qss::g_initToken; }

__declspec(dllexport) const char* VR_GetVRInitErrorAsSymbol(EVRInitError e) {
    return e == VRInitError_None ? "VRInitError_None" : "VRInitError_Init_HmdNotFound";
}
__declspec(dllexport) const char* VR_GetVRInitErrorAsEnglishDescription(EVRInitError e) {
    return e == VRInitError_None ? "No error"
                                 : "Quest bridge not connected. Start the game from the Quest Steam app.";
}
__declspec(dllexport) const char* VR_GetStringForHmdError(EVRInitError e) { return VR_GetVRInitErrorAsEnglishDescription(e); }

__declspec(dllexport) bool VR_GetRuntimePath(char* buf, uint32_t size, uint32_t* required) {
    uint32_t n = qss::CopyString("C:\\QuestSteamVR", buf, size);
    if (required) *required = n;
    return size >= n;
}
__declspec(dllexport) const char* VR_RuntimePath() { return "C:\\QuestSteamVR"; }

}  // extern "C"
