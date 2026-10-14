#include "bridge.h"

#include <deque>
#include <mutex>

using namespace vr;

namespace qss {

static std::mutex g_eventLock;
static std::deque<VREvent_t> g_events;

void PushEvent(EVREventType type, TrackedDeviceIndex_t device, uint32_t button) {
    VREvent_t e;
    memset(&e, 0, sizeof e);
    e.eventType = type;
    e.trackedDeviceIndex = device;
    e.data.controller.button = button;
    std::lock_guard<std::mutex> l(g_eventLock);
    if (g_events.size() < 256) g_events.push_back(e);
}

static bool PopEvent(VREvent_t* out, uint32_t size) {
    std::lock_guard<std::mutex> l(g_eventLock);
    if (g_events.empty()) return false;
    VREvent_t e = g_events.front();
    g_events.pop_front();
    memcpy(out, &e, size < sizeof e ? size : sizeof e);
    return true;
}

// Legacy (pre-SteamVR Input) mapping of Quest Touch controls, matching what SteamVR reports for Oculus Touch.
void InputLegacyButtons(int hand, uint64_t* pressed, uint64_t* touched, VRControllerAxis_t axes[5]) {
    const QssController& c = g_pose.hand[hand];
    uint64_t p = 0, t = 0;
    auto bit = [](EVRButtonId b) { return ButtonMaskFromId(b); };
    if (c.buttons & QSS_BTN_A) p |= bit(k_EButton_A);
    if (c.buttons & QSS_BTN_B) p |= bit(k_EButton_ApplicationMenu);
    if (c.buttons & QSS_BTN_MENU) p |= bit(k_EButton_ApplicationMenu);
    if (c.buttons & QSS_BTN_STICK) p |= bit(k_EButton_SteamVR_Touchpad);
    if (c.trigger > 0.75f) p |= bit(k_EButton_SteamVR_Trigger);
    if (c.grip > 0.75f) p |= bit(k_EButton_Grip);
    if (c.buttons & QSS_TOUCH_A) t |= bit(k_EButton_A);
    if (c.buttons & QSS_TOUCH_B) t |= bit(k_EButton_ApplicationMenu);
    if (c.buttons & QSS_TOUCH_STICK) t |= bit(k_EButton_SteamVR_Touchpad);
    if ((c.buttons & QSS_TOUCH_TRIGGER) || c.trigger > 0.05f) t |= bit(k_EButton_SteamVR_Trigger);
    if (c.grip > 0.05f) t |= bit(k_EButton_Grip);
    *pressed = p;
    *touched = t | p;
    memset(axes, 0, sizeof(VRControllerAxis_t) * 5);
    axes[0].x = c.stick[0]; axes[0].y = c.stick[1];
    axes[1].x = c.trigger;
    axes[2].x = c.grip;
}

void UpdateLegacyButtonEvents() {
    static uint64_t prev[2] = {0, 0};
    for (int h = 0; h < 2; ++h) {
        uint64_t p, t;
        VRControllerAxis_t ax[5];
        InputLegacyButtons(h, &p, &t, ax);
        uint64_t changed = p ^ prev[h];
        for (uint32_t b = 0; b < 64; ++b) {
            if (!(changed & (1ull << b))) continue;
            PushEvent((p & (1ull << b)) ? VREvent_ButtonPress : VREvent_ButtonUnpress, HandToDevice(h), b);
        }
        prev[h] = p;
    }
}

class CSystem : public IVRSystem {
public:
    void GetRecommendedRenderTargetSize(uint32_t* w, uint32_t* h) override {
        *w = g_shm.s && g_shm.s->rec_width ? g_shm.s->rec_width : 1440;
        *h = g_shm.s && g_shm.s->rec_height ? g_shm.s->rec_height : 1584;
    }

    HmdMatrix44_t GetProjectionMatrix(EVREye eye, float zNear, float zFar) override {
        float l, r, t, b;
        GetProjectionRaw(eye, &l, &r, &t, &b);
        float idx = 1.0f / (r - l), idy = 1.0f / (b - t), idz = 1.0f / (zFar - zNear);
        float sx = r + l, sy = b + t;
        HmdMatrix44_t m{};
        m.m[0][0] = 2 * idx; m.m[0][2] = sx * idx;
        m.m[1][1] = 2 * idy; m.m[1][2] = sy * idy;
        m.m[2][2] = -zFar * idz; m.m[2][3] = -zFar * zNear * idz;
        m.m[3][2] = -1.0f;
        return m;
    }

    void GetProjectionRaw(EVREye eye, float* l, float* r, float* t, float* b) override {
        RefreshPose();
        QssFov f = g_pose.fov[eye == Eye_Left ? 0 : 1];
        if (f.left == 0 && f.right == 0) f = {-0.90f, 0.75f, 0.85f, -0.90f};  // ~Quest 3 until the first pose arrives
        // OpenVR's "top" is the negative-up tangent.
        *l = tanf(f.left);
        *r = tanf(f.right);
        *t = -tanf(f.up);
        *b = -tanf(f.down);
    }

    bool ComputeDistortion(EVREye, float u, float v, DistortionCoordinates_t* d) override {
        d->rfRed[0] = d->rfGreen[0] = d->rfBlue[0] = u;
        d->rfRed[1] = d->rfGreen[1] = d->rfBlue[1] = v;
        return true;
    }

    HmdMatrix34_t GetEyeToHeadTransform(EVREye eye) override {
        RefreshPose();
        int e = eye == Eye_Left ? 0 : 1;
        QssPose rel;
        if (g_pose.frame_id) rel = RelativePose(g_pose.head, g_pose.eye[e]);
        else rel = QssPose{{e == 0 ? -0.032f : 0.032f, 0, 0}, {0, 0, 0, 1}};
        return PoseToMatrix(rel);
    }

    bool GetTimeSinceLastVsync(float* secs, uint64_t* frame) override {
        float hz = g_pose.refresh_hz > 0 ? g_pose.refresh_hz : 72.0f;
        LARGE_INTEGER c, f;
        QueryPerformanceCounter(&c); QueryPerformanceFrequency(&f);
        double t = (double)c.QuadPart / f.QuadPart;
        if (secs) *secs = (float)fmod(t, 1.0 / hz);
        if (frame) *frame = g_pose.frame_id;
        return true;
    }

    int32_t GetD3D9AdapterIndex() override { return 0; }
    void GetDXGIOutputInfo(int32_t* idx) override { if (idx) *idx = 0; }
    void GetOutputDevice(uint64_t* dev, ETextureType, VkInstance_T*) override { if (dev) *dev = 0; }
    bool IsDisplayOnDesktop() override { return false; }
    bool SetDisplayVisibility(bool) override { return false; }

    void GetDeviceToAbsoluteTrackingPose(ETrackingUniverseOrigin origin, float, TrackedDevicePose_t* poses, uint32_t count) override {
        RefreshPose();
        for (uint32_t i = 0; i < count; ++i) FillDevicePose(&poses[i], i, g_pose, origin);
    }

    HmdMatrix34_t GetSeatedZeroPoseToStandingAbsoluteTrackingPose() override {
        QssPose p{{g_seatedOrigin[0], g_seatedOrigin[1], g_seatedOrigin[2]}, {0, 0, 0, 1}};
        return PoseToMatrix(p);
    }
    HmdMatrix34_t GetRawZeroPoseToStandingAbsoluteTrackingPose() override { return PoseToMatrix(QssPose{{0, 0, 0}, {0, 0, 0, 1}}); }

    uint32_t GetSortedTrackedDeviceIndicesOfClass(ETrackedDeviceClass cls, TrackedDeviceIndex_t* out, uint32_t n, TrackedDeviceIndex_t) override {
        TrackedDeviceIndex_t list[2];
        uint32_t c = 0;
        if (cls == TrackedDeviceClass_HMD) list[c++] = kHmd;
        if (cls == TrackedDeviceClass_Controller) { list[c++] = kLeft; list[c++] = kRight; }
        for (uint32_t i = 0; i < c && i < n; ++i) out[i] = list[i];
        return c;
    }

    EDeviceActivityLevel GetTrackedDeviceActivityLevel(TrackedDeviceIndex_t) override { return k_EDeviceActivityLevel_UserInteraction; }

    void ApplyTransform(TrackedDevicePose_t* out, const TrackedDevicePose_t* in, const HmdMatrix34_t* t) override {
        *out = *in;
        const HmdMatrix34_t& a = in->mDeviceToAbsoluteTracking;
        for (int r = 0; r < 3; ++r)
            for (int c = 0; c < 4; ++c)
                out->mDeviceToAbsoluteTracking.m[r][c] = a.m[r][0] * t->m[0][c] + a.m[r][1] * t->m[1][c] + a.m[r][2] * t->m[2][c] + (c == 3 ? a.m[r][3] : 0);
    }

    TrackedDeviceIndex_t GetTrackedDeviceIndexForControllerRole(ETrackedControllerRole role) override {
        return role == TrackedControllerRole_LeftHand ? kLeft : role == TrackedControllerRole_RightHand ? kRight : k_unTrackedDeviceIndexInvalid;
    }
    ETrackedControllerRole GetControllerRoleForTrackedDeviceIndex(TrackedDeviceIndex_t i) override {
        return i == kLeft ? TrackedControllerRole_LeftHand : i == kRight ? TrackedControllerRole_RightHand : TrackedControllerRole_Invalid;
    }
    ETrackedDeviceClass GetTrackedDeviceClass(TrackedDeviceIndex_t i) override {
        return i == kHmd ? TrackedDeviceClass_HMD : (i == kLeft || i == kRight) ? TrackedDeviceClass_Controller : TrackedDeviceClass_Invalid;
    }
    bool IsTrackedDeviceConnected(TrackedDeviceIndex_t i) override { return i < kDeviceCount; }

    template <typename T>
    static T Fail(ETrackedPropertyError* e, ETrackedPropertyError code, T v) { if (e) *e = code; return v; }
    static void Ok(ETrackedPropertyError* e) { if (e) *e = TrackedProp_Success; }

    bool GetBoolTrackedDeviceProperty(TrackedDeviceIndex_t i, ETrackedDeviceProperty p, ETrackedPropertyError* e) override {
        if (i >= kDeviceCount) return Fail(e, TrackedProp_InvalidDevice, false);
        switch (p) {
            case Prop_DeviceProvidesBatteryStatus_Bool: case Prop_WillDriftInYaw_Bool: case Prop_DeviceIsCharging_Bool:
            case Prop_ContainsProximitySensor_Bool: case Prop_DeviceIsWireless_Bool:
                Ok(e); return p == Prop_DeviceIsWireless_Bool;
            default: return Fail(e, TrackedProp_UnknownProperty, false);
        }
    }

    float GetFloatTrackedDeviceProperty(TrackedDeviceIndex_t i, ETrackedDeviceProperty p, ETrackedPropertyError* e) override {
        if (i >= kDeviceCount) return Fail(e, TrackedProp_InvalidDevice, 0.0f);
        switch (p) {
            case Prop_DisplayFrequency_Float: Ok(e); return g_pose.refresh_hz > 0 ? g_pose.refresh_hz : 72.0f;
            case Prop_UserIpdMeters_Float: Ok(e); return g_pose.ipd > 0 ? g_pose.ipd : 0.064f;
            case Prop_SecondsFromVsyncToPhotons_Float: Ok(e); return 0.011f;
            case Prop_UserHeadToEyeDepthMeters_Float: Ok(e); return 0.0f;
            case Prop_DeviceBatteryPercentage_Float: Ok(e); return 1.0f;
            default: return Fail(e, TrackedProp_UnknownProperty, 0.0f);
        }
    }

    int32_t GetInt32TrackedDeviceProperty(TrackedDeviceIndex_t i, ETrackedDeviceProperty p, ETrackedPropertyError* e) override {
        if (i >= kDeviceCount) return Fail(e, TrackedProp_InvalidDevice, 0);
        switch (p) {
            case Prop_DeviceClass_Int32: Ok(e); return GetTrackedDeviceClass(i);
            case Prop_ControllerRoleHint_Int32: Ok(e); return GetControllerRoleForTrackedDeviceIndex(i);
            case Prop_Axis0Type_Int32: Ok(e); return k_eControllerAxis_Joystick;
            case Prop_Axis1Type_Int32: case Prop_Axis2Type_Int32: Ok(e); return k_eControllerAxis_Trigger;
            case Prop_Axis3Type_Int32: case Prop_Axis4Type_Int32: Ok(e); return k_eControllerAxis_None;
            default: return Fail(e, TrackedProp_UnknownProperty, 0);
        }
    }

    uint64_t GetUint64TrackedDeviceProperty(TrackedDeviceIndex_t i, ETrackedDeviceProperty p, ETrackedPropertyError* e) override {
        if (i >= kDeviceCount) return Fail(e, TrackedProp_InvalidDevice, (uint64_t)0);
        if (p == Prop_SupportedButtons_Uint64) {
            Ok(e);
            return ButtonMaskFromId(k_EButton_A) | ButtonMaskFromId(k_EButton_ApplicationMenu) | ButtonMaskFromId(k_EButton_Grip) |
                   ButtonMaskFromId(k_EButton_SteamVR_Touchpad) | ButtonMaskFromId(k_EButton_SteamVR_Trigger) | ButtonMaskFromId(k_EButton_System);
        }
        if (p == Prop_HardwareRevision_Uint64 || p == Prop_FirmwareVersion_Uint64) { Ok(e); return 1; }
        return Fail(e, TrackedProp_UnknownProperty, (uint64_t)0);
    }

    HmdMatrix34_t GetMatrix34TrackedDeviceProperty(TrackedDeviceIndex_t, ETrackedDeviceProperty, ETrackedPropertyError* e) override {
        return Fail(e, TrackedProp_UnknownProperty, PoseToMatrix(QssPose{{0, 0, 0}, {0, 0, 0, 1}}));
    }

    uint32_t GetArrayTrackedDeviceProperty(TrackedDeviceIndex_t, ETrackedDeviceProperty, PropertyTypeTag_t, void*, uint32_t, ETrackedPropertyError* e) override {
        return Fail(e, TrackedProp_UnknownProperty, 0u);
    }

    uint32_t GetStringTrackedDeviceProperty(TrackedDeviceIndex_t i, ETrackedDeviceProperty p, char* out, uint32_t size, ETrackedPropertyError* e) override {
        if (i >= kDeviceCount) return Fail(e, TrackedProp_InvalidDevice, 0u);
        bool left = i == kLeft, hmd = i == kHmd;
        const char* v = nullptr;
        switch (p) {
            case Prop_TrackingSystemName_String: v = "oculus"; break;
            case Prop_ManufacturerName_String: v = "Oculus"; break;
            case Prop_ModelNumber_String: v = hmd ? "Oculus Quest3" : left ? "Oculus Quest3 (Left Controller)" : "Oculus Quest3 (Right Controller)"; break;
            case Prop_SerialNumber_String: v = hmd ? "QSS-HMD" : left ? "QSS-LEFT" : "QSS-RIGHT"; break;
            case Prop_RenderModelName_String: v = hmd ? "generic_hmd" : left ? "oculus_quest2_controller_left" : "oculus_quest2_controller_right"; break;
            case Prop_ControllerType_String: v = hmd ? "oculus" : "oculus_touch"; break;
            case Prop_InputProfilePath_String: v = hmd ? "{oculus}/input/rift_profile.json" : "{oculus}/input/touch_profile.json"; break;
            case Prop_TrackingFirmwareVersion_String: case Prop_HardwareRevision_String: v = "1"; break;
            case Prop_RegisteredDeviceType_String: v = hmd ? "oculus/QSS-HMD" : left ? "oculus/QSS-LEFT" : "oculus/QSS-RIGHT"; break;
            default: break;
        }
        if (!v) return Fail(e, TrackedProp_UnknownProperty, 0u);
        uint32_t need = CopyString(v, out, size);
        if (size < need) return Fail(e, TrackedProp_BufferTooSmall, need);
        Ok(e);
        return need;
    }

    const char* GetPropErrorNameFromEnum(ETrackedPropertyError) override { return "TrackedProp_Error"; }

    bool PollNextEvent(VREvent_t* ev, uint32_t size) override { return ev && PopEvent(ev, size); }

    bool PollNextEventWithPose(ETrackingUniverseOrigin origin, VREvent_t* ev, uint32_t size, TrackedDevicePose_t* pose) override {
        if (!ev || !PopEvent(ev, size)) return false;
        if (pose) FillDevicePose(pose, ev->trackedDeviceIndex, g_pose, origin);
        return true;
    }

    const char* GetEventTypeNameFromEnum(EVREventType) override { return "VREvent"; }

    HiddenAreaMesh_t GetHiddenAreaMesh(EVREye, EHiddenAreaMeshType) override { return HiddenAreaMesh_t{nullptr, 0}; }

    bool GetControllerState(TrackedDeviceIndex_t i, VRControllerState_t* st, uint32_t size) override {
        int h = DeviceToHand(i);
        if (h < 0 || !st) return false;
        RefreshPose();
        VRControllerState_t s{};
        s.unPacketNum = (uint32_t)g_pose.frame_id;
        InputLegacyButtons(h, &s.ulButtonPressed, &s.ulButtonTouched, s.rAxis);
        memcpy(st, &s, size < sizeof s ? size : sizeof s);
        return true;
    }

    bool GetControllerStateWithPose(ETrackingUniverseOrigin origin, TrackedDeviceIndex_t i, VRControllerState_t* st, uint32_t size, TrackedDevicePose_t* pose) override {
        if (!GetControllerState(i, st, size)) return false;
        if (pose) FillDevicePose(pose, i, g_pose, origin);
        return true;
    }

    void TriggerHapticPulse(TrackedDeviceIndex_t i, uint32_t, unsigned short micros) override {
        int h = DeviceToHand(i);
        if (h < 0 || !g_shm.s) return;
        g_shm.s->haptic_amplitude[h] = 1.0f;
        g_shm.s->haptic_seconds[h] = micros / 1e6f < 0.005f ? 0.005f : micros / 1e6f;
        MemoryBarrier();
        g_shm.s->haptic_seq[h]++;
    }

    const char* GetButtonIdNameFromEnum(EVRButtonId) override { return "k_EButton"; }
    const char* GetControllerAxisTypeNameFromEnum(EVRControllerAxisType) override { return "k_eControllerAxis"; }
    bool IsInputAvailable() override { return true; }
    bool IsSteamVRDrawingControllers() override { return false; }
    bool ShouldApplicationPause() override { return false; }
    bool ShouldApplicationReduceRenderingWork() override { return false; }
    EVRFirmwareError PerformFirmwareUpdate(TrackedDeviceIndex_t) override { return VRFirmwareError_None; }
    void AcknowledgeQuit_Exiting() override {}
    uint32_t GetAppContainerFilePaths(char* buf, uint32_t size) override { return CopyString("", buf, size); }
    const char* GetRuntimeVersion() override { return "2.5.1"; }
};

class CChaperone : public IVRChaperone {
public:
    ChaperoneCalibrationState GetCalibrationState() override { return ChaperoneCalibrationState_OK; }
    bool GetPlayAreaSize(float* x, float* z) override { if (x) *x = 2.0f; if (z) *z = 2.0f; return true; }
    bool GetPlayAreaRect(HmdQuad_t* r) override {
        if (!r) return false;
        const float c[4][2] = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int i = 0; i < 4; ++i) r->vCorners[i] = HmdVector3_t{{c[i][0], 0, c[i][1]}};
        return true;
    }
    void ReloadInfo() override {}
    void SetSceneColor(HmdColor_t) override {}
    void GetBoundsColor(HmdColor_t* out, int n, float, HmdColor_t* cam) override {
        for (int i = 0; out && i < n; ++i) out[i] = HmdColor_t{0, 0, 0, 0};
        if (cam) *cam = HmdColor_t{0, 0, 0, 0};
    }
    bool AreBoundsVisible() override { return false; }
    void ForceBoundsVisible(bool) override {}
    void ResetZeroPose(ETrackingUniverseOrigin) override {
        RefreshPose();
        g_seatedOrigin[0] = g_pose.head.pos[0];
        g_seatedOrigin[1] = g_pose.head.pos[1];
        g_seatedOrigin[2] = g_pose.head.pos[2];
    }
};

IVRSystem* System() { static CSystem s; return &s; }
IVRChaperone* Chaperone() { static CChaperone c; return &c; }

}  // namespace qss
