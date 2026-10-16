// Minimal "SteamVR game" for testing the bridge under Wine: loads openvr_api.dll, inits,
// reads poses, renders red (left) / blue (right) with D3D11, submits, and checks input.
#include <windows.h>
#include <d3d11.h>
#include <cmath>
#include <cstdio>
#include <string>

#include "openvr.h"

using namespace vr;

typedef uint32_t(__cdecl* InitFn)(EVRInitError*, EVRApplicationType, const char*);
typedef void*(__cdecl* GetIfaceFn)(const char*, EVRInitError*);

static int g_fail = 0;
#define CHECK(c, ...) do { if (!(c)) { printf("FAIL: " __VA_ARGS__); printf("\n"); g_fail++; } else { printf("ok: " __VA_ARGS__); printf("\n"); } } while (0)

static void WriteFile(const std::string& p, const char* text) {
    FILE* f = fopen(p.c_str(), "wb");
    fputs(text, f);
    fclose(f);
}

int main() {
    HMODULE dll = LoadLibraryA("openvr_api.dll");
    CHECK(dll != nullptr, "load openvr_api.dll");
    if (!dll) return 1;
    auto init = (InitFn)GetProcAddress(dll, "VR_InitInternal2");
    auto get = (GetIfaceFn)GetProcAddress(dll, "VR_GetGenericInterface");
    EVRInitError err = VRInitError_Unknown;
    init(&err, VRApplication_Scene, nullptr);
    CHECK(err == VRInitError_None, "VR_Init (err %d)", (int)err);
    if (err != VRInitError_None) return 1;

    auto sys = (IVRSystem*)get(IVRSystem_Version, &err);
    auto comp = (IVRCompositor*)get(IVRCompositor_Version, &err);
    auto input = (IVRInput*)get(IVRInput_Version, &err);
    auto overlay = get(IVROverlay_Version, &err);
    CHECK(sys && comp && input && overlay, "interfaces");

    uint32_t w = 0, h = 0;
    sys->GetRecommendedRenderTargetSize(&w, &h);
    CHECK(w == 640 && h == 704, "recommended size %ux%u", w, h);

    // Unity-style C function table.
    auto ft = (void**)get("FnTable:IVRSystem_022", &err);
    uint32_t fw = 0, fh = 0;
    ((void(__stdcall*)(uint32_t*, uint32_t*))ft[0])(&fw, &fh);
    CHECK(fw == w && fh == h, "FnTable GetRecommendedRenderTargetSize %ux%u", fw, fh);

    char model[128];
    sys->GetStringTrackedDeviceProperty(k_unTrackedDeviceIndex_Hmd, Prop_ModelNumber_String, model, sizeof model);
    CHECK(strstr(model, "Quest") != nullptr, "HMD model '%s'", model);
    CHECK(sys->GetTrackedDeviceIndexForControllerRole(TrackedControllerRole_RightHand) == 2, "right hand index");

    // SteamVR Input with an action manifest and an Oculus Touch binding.
    char tmp[MAX_PATH];
    GetTempPathA(MAX_PATH, tmp);
    std::string dir = std::string(tmp);
    WriteFile(dir + "actions.json", R"({"actions":[{"name":"/actions/main/in/Fire","type":"boolean"},{"name":"/actions/main/in/Jump","type":"boolean"},
      {"name":"/actions/main/in/Squeeze","type":"vector1"},{"name":"/actions/main/in/HandRight","type":"pose"},{"name":"/actions/main/out/Buzz","type":"vibration"}],
      "action_sets":[{"name":"/actions/main","usage":"leftright"}],
      "default_bindings":[{"controller_type":"knuckles","binding_url":"nope.json"},{"controller_type":"oculus_touch","binding_url":"touch.json"}]})");
    WriteFile(dir + "touch.json", R"({"bindings":{"/actions/main":{"sources":[
      {"path":"/user/hand/right/input/trigger","mode":"trigger","inputs":{"click":{"output":"/actions/main/in/fire"},"pull":{"output":"/actions/main/in/squeeze"}}},
      {"path":"/user/hand/right/input/a","mode":"button","inputs":{"click":{"output":"/actions/main/in/jump"}}}],
      "poses":[{"output":"/actions/main/in/handright","path":"/user/hand/right/pose/raw"}],
      "haptics":[{"output":"/actions/main/out/buzz","path":"/user/hand/right/output/haptic"}]}}})");
    CHECK(input->SetActionManifestPath((dir + "actions.json").c_str()) == VRInputError_None, "SetActionManifestPath");
    VRActionSetHandle_t set; VRActionHandle_t fire, jump, squeeze, hand, buzz;
    input->GetActionSetHandle("/actions/main", &set);
    input->GetActionHandle("/actions/main/in/Fire", &fire);
    input->GetActionHandle("/actions/main/in/Jump", &jump);
    input->GetActionHandle("/actions/main/in/Squeeze", &squeeze);
    input->GetActionHandle("/actions/main/in/HandRight", &hand);
    input->GetActionHandle("/actions/main/out/Buzz", &buzz);

    // D3D11 device + one render target per eye.
    ID3D11Device* dev = nullptr; ID3D11DeviceContext* ctx = nullptr;
    HRESULT hr = D3D11CreateDevice(nullptr, D3D_DRIVER_TYPE_HARDWARE, nullptr, 0, nullptr, 0, D3D11_SDK_VERSION, &dev, nullptr, &ctx);
    CHECK(SUCCEEDED(hr), "D3D11CreateDevice (hr %08lx)", (unsigned long)hr);
    if (FAILED(hr)) return 1;
    ID3D11Texture2D* tex[2]; ID3D11RenderTargetView* rtv[2];
    for (int e = 0; e < 2; ++e) {
        D3D11_TEXTURE2D_DESC d{}; d.Width = w; d.Height = h; d.MipLevels = 1; d.ArraySize = 1;
        d.Format = DXGI_FORMAT_R8G8B8A8_UNORM_SRGB; d.SampleDesc.Count = 1; d.BindFlags = D3D11_BIND_RENDER_TARGET | D3D11_BIND_SHADER_RESOURCE;
        dev->CreateTexture2D(&d, nullptr, &tex[e]);
        dev->CreateRenderTargetView(tex[e], nullptr, &rtv[e]);
    }

    TrackedDevicePose_t poses[k_unMaxTrackedDeviceCount];
    float firstZ = 0, lastZ = 0;
    for (int frame = 0; frame < 70; ++frame) {
        comp->WaitGetPoses(poses, k_unMaxTrackedDeviceCount, nullptr, 0);
        if (frame == 0) firstZ = poses[0].mDeviceToAbsoluteTracking.m[2][3];
        lastZ = poses[0].mDeviceToAbsoluteTracking.m[2][3];
        const float red[4] = {1, 0, 0, 1}, blue[4] = {0, 0, 1, 1};
        ctx->ClearRenderTargetView(rtv[0], red);
        ctx->ClearRenderTargetView(rtv[1], blue);
        Texture_t l{tex[0], TextureType_DirectX, ColorSpace_Gamma}, r{tex[1], TextureType_DirectX, ColorSpace_Gamma};
        comp->Submit(Eye_Left, &l);
        comp->Submit(Eye_Right, &r);
    }
    CHECK(poses[0].bPoseIsValid, "HMD pose valid");
    CHECK(fabsf(poses[0].mDeviceToAbsoluteTracking.m[1][3] - 1.6f) < 0.01f, "HMD height %.2f", poses[0].mDeviceToAbsoluteTracking.m[1][3]);
    CHECK(lastZ < firstZ, "head moves between frames (z %.3f -> %.3f)", firstZ, lastZ);
    CHECK(poses[2].bPoseIsValid && fabsf(poses[2].mDeviceToAbsoluteTracking.m[0][3] - 0.2f) < 0.01f, "right controller pose");

    VRActiveActionSet_t active{}; active.ulActionSet = set;
    input->UpdateActionState(&active, sizeof active, 1);
    InputDigitalActionData_t dd{}; InputAnalogActionData_t ad{}; InputPoseActionData_t pd{};
    input->GetDigitalActionData(fire, &dd, sizeof dd, k_ulInvalidInputValueHandle);
    CHECK(dd.bState, "trigger click -> Fire");
    input->GetDigitalActionData(jump, &dd, sizeof dd, k_ulInvalidInputValueHandle);
    CHECK(dd.bState, "A button -> Jump");
    input->GetAnalogActionData(squeeze, &ad, sizeof ad, k_ulInvalidInputValueHandle);
    CHECK(ad.x > 0.99f, "trigger pull -> Squeeze %.2f", ad.x);
    input->GetPoseActionDataForNextFrame(hand, TrackingUniverseStanding, &pd, sizeof pd, k_ulInvalidInputValueHandle);
    CHECK(pd.bActive && pd.pose.bPoseIsValid, "pose action");
    input->TriggerHapticVibrationAction(buzz, 0, 0.1f, 160, 0.5f, k_ulInvalidInputValueHandle);

    VRControllerState_t cs{};
    sys->GetControllerState(2, &cs, sizeof cs);
    CHECK((cs.ulButtonPressed & ButtonMaskFromId(k_EButton_SteamVR_Trigger)) && cs.rAxis[1].x > 0.99f, "legacy controller state");

    Sleep(200);
    printf("%s (%d failures)\n", g_fail ? "TEST FAILED" : "TEST PASSED", g_fail);
    return g_fail ? 1 : 0;
}
