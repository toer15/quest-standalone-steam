// SteamVR Input (IVRInput): reads the game's action manifest + its default binding for
// Oculus Touch (or Index/Vive as a fallback) and evaluates actions from Quest Touch state.
#include "bridge.h"
#include "json.h"

#include <algorithm>
#include <fstream>
#include <sstream>
#include <vector>

using namespace vr;

namespace qss {

namespace {

enum class Src { None, Trigger, Grip, Stick, A, B, Menu, PoseGrip, PoseAim, Haptic };
enum class Comp { Click, Touch, Value, Position, North, South, East, West, Center };

struct Binding {
    int hand = 0;  // 0 left, 1 right
    Src src = Src::None;
    Comp comp = Comp::Click;
    bool toggle = false;
};

struct Action {
    std::string name;  // lower case
    std::string type;  // boolean, vector1, vector2, vector3, pose, skeleton, vibration
    std::vector<Binding> bindings;
    bool fallback = false;
    // evaluated state
    bool state = false, prevState = false, changed = false;
    float x = 0, y = 0, px = 0, py = 0;
    int activeHand = -1;
    bool toggled[2] = {false, false};
    bool lastRaw[2] = {false, false};
};

std::vector<std::string> g_sets;
std::vector<Action> g_actions;
bool g_manifestLoaded = false;
double g_updateTime = 0;

std::string Lower(std::string s) {
    for (auto& c : s) c = (char)tolower((unsigned char)c);
    return s;
}

bool ReadFile(const std::string& path, std::string& out) {
    std::ifstream f(path, std::ios::binary);
    if (!f) return false;
    std::stringstream ss;
    ss << f.rdbuf();
    out = ss.str();
    return true;
}

std::string DirOf(const std::string& p) {
    size_t i = p.find_last_of("/\\");
    return i == std::string::npos ? std::string() : p.substr(0, i + 1);
}

Action* FindAction(const std::string& name) {
    std::string n = Lower(name);
    for (auto& a : g_actions) if (a.name == n) return &a;
    return nullptr;
}

Action& GetOrAddAction(const std::string& name, const std::string& type) {
    if (Action* a = FindAction(name)) return *a;
    Action a;
    a.name = Lower(name);
    a.type = Lower(type);
    g_actions.push_back(a);
    return g_actions.back();
}

/** "/user/hand/left/input/trigger" -> hand + source. Handles Touch, Index and Vive names. */
bool ParsePath(const std::string& path, int* hand, Src* src, std::string* kind) {
    std::string p = Lower(path);
    if (p.rfind("/user/hand/left/", 0) == 0) *hand = 0;
    else if (p.rfind("/user/hand/right/", 0) == 0) *hand = 1;
    else return false;
    size_t slash = p.find('/', 16);
    std::string rest = p.substr(p.find('/', 11) + 1);  // "input/trigger"
    rest = rest.substr(rest.find('/') + 1);
    std::string group = p.substr(p.find('/', 11) + 1);
    group = group.substr(0, group.find('/'));
    (void)slash;
    if (kind) *kind = group;
    if (group == "pose") {
        *src = (rest == "tip" || rest == "aim") ? Src::PoseAim : Src::PoseGrip;
        return true;
    }
    if (group == "output") { *src = Src::Haptic; return true; }
    if (rest == "trigger") *src = Src::Trigger;
    else if (rest == "grip") *src = Src::Grip;
    else if (rest == "joystick" || rest == "thumbstick" || rest == "trackpad") *src = Src::Stick;
    else if (rest == "a" || rest == "x") *src = Src::A;
    else if (rest == "b" || rest == "y") *src = Src::B;
    else if (rest == "application_menu" || rest == "system" || rest == "menu") *src = Src::Menu;
    else return false;
    return true;
}

bool CompFromInput(const std::string& in, Comp* c) {
    std::string i = Lower(in);
    if (i == "click" || i == "grab") *c = Comp::Click;
    else if (i == "touch") *c = Comp::Touch;
    else if (i == "pull" || i == "value" || i == "force") *c = Comp::Value;
    else if (i == "position") *c = Comp::Position;
    else if (i == "north") *c = Comp::North;
    else if (i == "south") *c = Comp::South;
    else if (i == "east") *c = Comp::East;
    else if (i == "west") *c = Comp::West;
    else if (i == "center") *c = Comp::Center;
    else return false;
    return true;
}

int LoadBindingFile(const std::string& path) {
    std::string text;
    if (!ReadFile(path, text)) { Log("binding file not found: %s", path.c_str()); return 0; }
    bool ok;
    Json root = Json::Parse(text, &ok);
    if (!ok) { Log("binding file is not valid JSON: %s", path.c_str()); return 0; }
    int count = 0;
    const Json& sets = root["bindings"];
    for (auto& setKv : sets.o) {
        const Json& set = setKv.second;
        for (auto& src : set["sources"].a) {
            int hand; Src s; std::string kind;
            if (!ParsePath(src["path"].str(), &hand, &s, &kind)) continue;
            std::string mode = Lower(src["mode"].str());
            for (auto& in : src["inputs"].o) {
                Comp c;
                if (!CompFromInput(in.first, &c)) continue;
                std::string out = in.second["output"].str();
                if (out.empty()) continue;
                Action* a = FindAction(out);
                if (!a) continue;
                Binding b;
                b.hand = hand; b.src = s; b.comp = c; b.toggle = mode == "toggle_button";
                if (mode == "dpad" && c == Comp::Click) b.comp = Comp::Center;
                a->bindings.push_back(b);
                ++count;
            }
        }
        for (auto& pose : set["poses"].a) {
            int hand; Src s;
            if (!ParsePath(pose["path"].str(), &hand, &s, nullptr)) continue;
            if (Action* a = FindAction(pose["output"].str())) { a->bindings.push_back(Binding{hand, s, Comp::Value, false}); ++count; }
        }
        for (auto& hap : set["haptics"].a) {
            int hand; Src s;
            if (!ParsePath(hap["path"].str(), &hand, &s, nullptr)) continue;
            if (Action* a = FindAction(hap["output"].str())) { a->bindings.push_back(Binding{hand, Src::Haptic, Comp::Value, false}); ++count; }
        }
    }
    return count;
}

/** Guess bindings from action names when the game ships no usable default binding. */
void ApplyFallbacks() {
    for (auto& a : g_actions) {
        if (!a.bindings.empty()) continue;
        a.fallback = true;
        const std::string& n = a.name;
        auto has = [&](const char* k) { return n.find(k) != std::string::npos; };
        int hands[2] = {0, 1};
        int handCount = 2;
        if (has("left")) { hands[0] = 0; handCount = 1; }
        else if (has("right")) { hands[0] = 1; handCount = 1; }
        for (int hi = 0; hi < handCount; ++hi) {
            int h = hands[hi];
            if (a.type == "pose") a.bindings.push_back({h, has("tip") || has("aim") || has("pointer") ? Src::PoseAim : Src::PoseGrip, Comp::Value});
            else if (a.type == "vibration") a.bindings.push_back({h, Src::Haptic, Comp::Value});
            else if (a.type == "vector2") a.bindings.push_back({h, Src::Stick, Comp::Position});
            else if (a.type == "vector1") a.bindings.push_back({h, has("grip") || has("squeeze") || has("grab") ? Src::Grip : Src::Trigger, Comp::Value});
            else if (a.type == "boolean") {
                if (has("grab") || has("grip")) a.bindings.push_back({h, Src::Grip, Comp::Click});
                else if (has("menu") || has("pause")) a.bindings.push_back({0, Src::Menu, Comp::Click});
                else if (has("jump") || has("primary")) a.bindings.push_back({h, Src::A, Comp::Click});
                else if (has("secondary")) a.bindings.push_back({h, Src::B, Comp::Click});
                else if (has("teleport")) a.bindings.push_back({h, Src::Stick, Comp::North});
                else a.bindings.push_back({h, Src::Trigger, Comp::Click});
            }
        }
    }
}

bool Digital(const QssController& c, const Binding& b) {
    float sx = c.stick[0], sy = c.stick[1];
    switch (b.src) {
        case Src::Trigger:
            return b.comp == Comp::Touch ? ((c.buttons & QSS_TOUCH_TRIGGER) || c.trigger > 0.05f) : c.trigger > 0.75f;
        case Src::Grip:
            return b.comp == Comp::Touch ? c.grip > 0.05f : c.grip > 0.7f;
        case Src::A: return b.comp == Comp::Touch ? (c.buttons & (QSS_TOUCH_A | QSS_BTN_A)) != 0 : (c.buttons & QSS_BTN_A) != 0;
        case Src::B: return b.comp == Comp::Touch ? (c.buttons & (QSS_TOUCH_B | QSS_BTN_B)) != 0 : (c.buttons & QSS_BTN_B) != 0;
        case Src::Menu: return (c.buttons & QSS_BTN_MENU) != 0;
        case Src::Stick:
            switch (b.comp) {
                case Comp::Touch: return (c.buttons & QSS_TOUCH_STICK) != 0;
                case Comp::North: return sy > 0.5f && fabsf(sy) >= fabsf(sx);
                case Comp::South: return sy < -0.5f && fabsf(sy) >= fabsf(sx);
                case Comp::East: return sx > 0.5f && fabsf(sx) > fabsf(sy);
                case Comp::West: return sx < -0.5f && fabsf(sx) > fabsf(sy);
                case Comp::Center: return (c.buttons & QSS_BTN_STICK) != 0 && fabsf(sx) < 0.5f && fabsf(sy) < 0.5f;
                default: return (c.buttons & QSS_BTN_STICK) != 0;
            }
        default: return false;
    }
}

float Analog(const QssController& c, const Binding& b) {
    if (b.comp != Comp::Value && b.comp != Comp::Position) return Digital(c, b) ? 1.0f : 0.0f;
    switch (b.src) {
        case Src::Trigger: return c.trigger;
        case Src::Grip: return c.grip;
        case Src::Stick: return c.stick[0];
        default: return Digital(c, b) ? 1.0f : 0.0f;
    }
}

int HandFromRestrict(VRInputValueHandle_t h) { return h == 1 ? 0 : h == 2 ? 1 : -1; }

void Evaluate() {
    RefreshPose();
    for (auto& a : g_actions) {
        a.prevState = a.state;
        a.px = a.x; a.py = a.y;
        bool st = false;
        float x = 0, y = 0;
        int active = -1;
        for (auto& b : a.bindings) {
            const QssController& c = g_pose.hand[b.hand];
            if (a.type == "boolean") {
                bool raw = Digital(c, b);
                if (b.toggle) {
                    if (raw && !a.lastRaw[b.hand]) a.toggled[b.hand] = !a.toggled[b.hand];
                    a.lastRaw[b.hand] = raw;
                    raw = a.toggled[b.hand];
                }
                if (raw) { st = true; active = b.hand; }
            } else if (a.type == "vector1") {
                float v = Analog(c, b);
                if (v > x) { x = v; active = b.hand; }
            } else if (a.type == "vector2" || a.type == "vector3") {
                if (b.src == Src::Stick && (fabsf(c.stick[0]) > fabsf(x) || fabsf(c.stick[1]) > fabsf(y))) {
                    x = c.stick[0]; y = c.stick[1]; active = b.hand;
                }
            } else if (a.type == "pose") {
                if (active < 0 && c.valid) active = b.hand;
            }
        }
        a.state = st;
        a.changed = a.state != a.prevState;
        a.x = x; a.y = y;
        a.activeHand = active;
    }
}

}  // namespace

bool InputUsesActions() { return g_manifestLoaded; }

class CInput : public IVRInput {
public:
    EVRInputError SetActionManifestPath(const char* path) override {
        std::string text;
        if (!path || !ReadFile(path, text)) { Log("action manifest not found: %s", path ? path : "(null)"); return VRInputError_InvalidParam; }
        bool ok;
        Json root = Json::Parse(text, &ok);
        if (!ok) { Log("action manifest is not valid JSON"); return VRInputError_InvalidParam; }
        for (auto& s : root["action_sets"].a) {
            std::string n = Lower(s["name"].str());
            if (std::find(g_sets.begin(), g_sets.end(), n) == g_sets.end()) g_sets.push_back(n);
        }
        for (auto& a : root["actions"].a) GetOrAddAction(a["name"].str(), a["type"].str());

        // Pick a default binding: Touch first, then the controllers whose layout is closest.
        const char* preference[] = {"oculus_touch", "knuckles", "vive_controller", "holographic_controller", "generic"};
        std::string chosen, chosenType;
        for (const char* want : preference) {
            for (auto& db : root["default_bindings"].a) {
                if (Lower(db["controller_type"].str()) == want) { chosen = db["binding_url"].str(); chosenType = want; break; }
            }
            if (!chosen.empty()) break;
        }
        int n = 0;
        if (!chosen.empty()) {
            std::string full = (chosen.find(':') != std::string::npos || chosen[0] == '/' || chosen[0] == '\\') ? chosen : DirOf(path) + chosen;
            n = LoadBindingFile(full);
        }
        ApplyFallbacks();
        g_manifestLoaded = true;
        Log("action manifest: %zu actions, %d bindings from %s", g_actions.size(), n, chosenType.empty() ? "(none, guessing by name)" : chosenType.c_str());
        return VRInputError_None;
    }

    EVRInputError GetActionSetHandle(const char* name, VRActionSetHandle_t* h) override {
        if (!name || !h) return VRInputError_InvalidParam;
        std::string n = Lower(name);
        auto it = std::find(g_sets.begin(), g_sets.end(), n);
        if (it == g_sets.end()) { g_sets.push_back(n); it = g_sets.end() - 1; }
        *h = 0x1000 + (it - g_sets.begin());
        return VRInputError_None;
    }

    EVRInputError GetActionHandle(const char* name, VRActionHandle_t* h) override {
        if (!name || !h) return VRInputError_InvalidParam;
        Action* a = FindAction(name);
        if (!a) {
            // Action not in the manifest (or no manifest): guess its type from the path.
            std::string n = Lower(name);
            std::string type = n.find("/out/") != std::string::npos ? "vibration" : n.find("pose") != std::string::npos ? "pose" : "boolean";
            a = &GetOrAddAction(name, type);
            ApplyFallbacks();
        }
        *h = (VRActionHandle_t)(a - g_actions.data()) + 1;
        return VRInputError_None;
    }

    EVRInputError GetInputSourceHandle(const char* path, VRInputValueHandle_t* h) override {
        if (!path || !h) return VRInputError_InvalidParam;
        std::string p = Lower(path);
        if (p == "/user/hand/left") *h = 1;
        else if (p == "/user/hand/right") *h = 2;
        else if (p == "/user/head") *h = 3;
        else *h = 100 + std::hash<std::string>()(p) % 100000;
        return VRInputError_None;
    }

    EVRInputError UpdateActionState(VRActiveActionSet_t*, uint32_t, uint32_t) override {
        LARGE_INTEGER c, f;
        QueryPerformanceCounter(&c); QueryPerformanceFrequency(&f);
        g_updateTime = (double)c.QuadPart / f.QuadPart;
        Evaluate();
        return VRInputError_None;
    }

    static Action* Get(VRActionHandle_t h) {
        if (h == 0 || h > g_actions.size()) return nullptr;
        return &g_actions[h - 1];
    }

    EVRInputError GetDigitalActionData(VRActionHandle_t h, InputDigitalActionData_t* d, uint32_t size, VRInputValueHandle_t restrict) override {
        Action* a = Get(h);
        if (!a || !d) return VRInputError_InvalidHandle;
        InputDigitalActionData_t r{};
        int hand = HandFromRestrict(restrict);
        bool st = a->state;
        if (hand >= 0) {
            st = false;
            for (auto& b : a->bindings) if (b.hand == hand && Digital(g_pose.hand[hand], b)) st = true;
        }
        r.bActive = true;
        r.activeOrigin = a->activeHand >= 0 ? (VRInputValueHandle_t)(a->activeHand + 1) : 0;
        r.bState = st;
        r.bChanged = hand >= 0 ? false : a->changed;
        r.fUpdateTime = 0;
        memcpy(d, &r, size < sizeof r ? size : sizeof r);
        return VRInputError_None;
    }

    EVRInputError GetAnalogActionData(VRActionHandle_t h, InputAnalogActionData_t* d, uint32_t size, VRInputValueHandle_t restrict) override {
        Action* a = Get(h);
        if (!a || !d) return VRInputError_InvalidHandle;
        InputAnalogActionData_t r{};
        float x = a->x, y = a->y;
        int hand = HandFromRestrict(restrict);
        if (hand >= 0) {
            x = y = 0;
            for (auto& b : a->bindings) {
                if (b.hand != hand) continue;
                const QssController& c = g_pose.hand[hand];
                if (b.src == Src::Stick && b.comp == Comp::Position) { x = c.stick[0]; y = c.stick[1]; }
                else x = (std::max)(x, Analog(c, b));
            }
        }
        r.bActive = true;
        r.activeOrigin = a->activeHand >= 0 ? (VRInputValueHandle_t)(a->activeHand + 1) : 0;
        r.x = x; r.y = y; r.z = 0;
        r.deltaX = x - a->px; r.deltaY = y - a->py;
        memcpy(d, &r, size < sizeof r ? size : sizeof r);
        return VRInputError_None;
    }

    EVRInputError GetPose(VRActionHandle_t h, ETrackingUniverseOrigin origin, InputPoseActionData_t* d, uint32_t size, VRInputValueHandle_t restrict) {
        Action* a = Get(h);
        if (!a || !d) return VRInputError_InvalidHandle;
        RefreshPose();
        InputPoseActionData_t r{};
        int want = HandFromRestrict(restrict);
        for (auto& b : a->bindings) {
            if (want >= 0 && b.hand != want) continue;
            const QssController& c = g_pose.hand[b.hand];
            QssPose p = b.src == Src::PoseAim ? c.aim_pose : c.grip_pose;
            if (p.rot[3] == 0 && p.rot[0] == 0 && p.rot[1] == 0 && p.rot[2] == 0) p.rot[3] = 1;
            ToUniverse(p, origin);
            r.bActive = true;
            r.activeOrigin = (VRInputValueHandle_t)(b.hand + 1);
            r.pose.mDeviceToAbsoluteTracking = PoseToMatrix(p);
            for (int i = 0; i < 3; ++i) { r.pose.vVelocity.v[i] = c.lin_vel[i]; r.pose.vAngularVelocity.v[i] = c.ang_vel[i]; }
            r.pose.bPoseIsValid = c.valid != 0;
            r.pose.bDeviceIsConnected = true;
            r.pose.eTrackingResult = c.valid ? TrackingResult_Running_OK : TrackingResult_Running_OutOfRange;
            break;
        }
        memcpy(d, &r, size < sizeof r ? size : sizeof r);
        return VRInputError_None;
    }

    EVRInputError GetPoseActionDataRelativeToNow(VRActionHandle_t h, ETrackingUniverseOrigin o, float, InputPoseActionData_t* d, uint32_t s, VRInputValueHandle_t r) override { return GetPose(h, o, d, s, r); }
    EVRInputError GetPoseActionDataForNextFrame(VRActionHandle_t h, ETrackingUniverseOrigin o, InputPoseActionData_t* d, uint32_t s, VRInputValueHandle_t r) override { return GetPose(h, o, d, s, r); }

    EVRInputError GetSkeletalActionData(VRActionHandle_t, InputSkeletalActionData_t* d, uint32_t size) override {
        if (d) { InputSkeletalActionData_t r{}; memcpy(d, &r, size < sizeof r ? size : sizeof r); }
        return VRInputError_None;
    }
    EVRInputError GetDominantHand(ETrackedControllerRole* r) override { if (r) *r = TrackedControllerRole_RightHand; return VRInputError_None; }
    EVRInputError SetDominantHand(ETrackedControllerRole) override { return VRInputError_None; }
    EVRInputError GetBoneCount(VRActionHandle_t, uint32_t* n) override { if (n) *n = 0; return VRInputError_NoData; }
    EVRInputError GetBoneHierarchy(VRActionHandle_t, BoneIndex_t*, uint32_t) override { return VRInputError_NoData; }
    EVRInputError GetBoneName(VRActionHandle_t, BoneIndex_t, char*, uint32_t) override { return VRInputError_NoData; }
    EVRInputError GetSkeletalReferenceTransforms(VRActionHandle_t, EVRSkeletalTransformSpace, EVRSkeletalReferencePose, VRBoneTransform_t*, uint32_t) override { return VRInputError_NoData; }
    EVRInputError GetSkeletalTrackingLevel(VRActionHandle_t, EVRSkeletalTrackingLevel* l) override { if (l) *l = VRSkeletalTracking_Estimated; return VRInputError_None; }
    EVRInputError GetSkeletalBoneData(VRActionHandle_t, EVRSkeletalTransformSpace, EVRSkeletalMotionRange, VRBoneTransform_t*, uint32_t) override { return VRInputError_NoData; }
    EVRInputError GetSkeletalSummaryData(VRActionHandle_t, EVRSummaryType, VRSkeletalSummaryData_t* d) override { if (d) memset(d, 0, sizeof *d); return VRInputError_None; }
    EVRInputError GetSkeletalBoneDataCompressed(VRActionHandle_t, EVRSkeletalMotionRange, void*, uint32_t, uint32_t* req) override { if (req) *req = 0; return VRInputError_NoData; }
    EVRInputError DecompressSkeletalBoneData(const void*, uint32_t, EVRSkeletalTransformSpace, VRBoneTransform_t*, uint32_t) override { return VRInputError_NoData; }

    EVRInputError TriggerHapticVibrationAction(VRActionHandle_t h, float, float seconds, float, float amplitude, VRInputValueHandle_t restrict) override {
        Action* a = Get(h);
        if (!a || !g_shm.s) return VRInputError_None;
        int want = HandFromRestrict(restrict);
        for (auto& b : a->bindings) {
            if (want >= 0 && b.hand != want) continue;
            g_shm.s->haptic_amplitude[b.hand] = amplitude;
            g_shm.s->haptic_seconds[b.hand] = seconds > 0 ? seconds : 0.01f;
            MemoryBarrier();
            g_shm.s->haptic_seq[b.hand]++;
        }
        return VRInputError_None;
    }

    EVRInputError GetActionOrigins(VRActionSetHandle_t, VRActionHandle_t h, VRInputValueHandle_t* out, uint32_t n) override {
        Action* a = Get(h);
        for (uint32_t i = 0; out && i < n; ++i) out[i] = 0;
        if (!a || !out) return VRInputError_None;
        uint32_t k = 0;
        bool seen[2] = {false, false};
        for (auto& b : a->bindings) if (!seen[b.hand] && k < n) { seen[b.hand] = true; out[k++] = (VRInputValueHandle_t)(b.hand + 1); }
        return VRInputError_None;
    }
    EVRInputError GetOriginLocalizedName(VRInputValueHandle_t o, char* buf, uint32_t size, int32_t) override {
        CopyString(o == 1 ? "Left Hand" : o == 2 ? "Right Hand" : "Headset", buf, size);
        return VRInputError_None;
    }
    EVRInputError GetOriginTrackedDeviceInfo(VRInputValueHandle_t o, InputOriginInfo_t* info, uint32_t size) override {
        if (!info) return VRInputError_InvalidParam;
        InputOriginInfo_t r{};
        r.devicePath = o;
        r.trackedDeviceIndex = o == 1 ? kLeft : o == 2 ? kRight : kHmd;
        CopyString(o == 1 ? "/user/hand/left" : o == 2 ? "/user/hand/right" : "/user/head", r.rchRenderModelComponentName, sizeof r.rchRenderModelComponentName);
        memcpy(info, &r, size < sizeof r ? size : sizeof r);
        return VRInputError_None;
    }
    EVRInputError GetActionBindingInfo(VRActionHandle_t, InputBindingInfo_t*, uint32_t, uint32_t, uint32_t* returned) override { if (returned) *returned = 0; return VRInputError_None; }
    EVRInputError ShowActionOrigins(VRActionSetHandle_t, VRActionHandle_t) override { return VRInputError_None; }
    EVRInputError ShowBindingsForActionSet(VRActiveActionSet_t*, uint32_t, uint32_t, VRInputValueHandle_t) override { return VRInputError_None; }
    EVRInputError GetComponentStateForBinding(const char*, const char*, const InputBindingInfo_t*, uint32_t, uint32_t, RenderModel_ComponentState_t*) override { return VRInputError_NoData; }
    bool IsUsingLegacyInput() override { return !g_manifestLoaded; }
    EVRInputError OpenBindingUI(const char*, VRActionSetHandle_t, VRInputValueHandle_t, bool) override { return VRInputError_None; }
    EVRInputError GetBindingVariant(VRInputValueHandle_t, char* buf, uint32_t size) override { CopyString("", buf, size); return VRInputError_None; }
};

IVRInput* Input() { static CInput i; return &i; }

}  // namespace qss
