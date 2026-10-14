// Quest side of the SteamVR bridge: an immersive OpenXR activity (Meta's runtime) that
//  - publishes head, eye and Touch controller tracking to the game through shared memory, and
//  - shows the frames the game submits (openvr_api.dll -> shared memory) in the headset,
//    using the pose each frame was rendered with so the runtime's timewarp corrects latency.
#include <android/log.h>
#include <android_native_app_glue.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <fcntl.h>
#include <jni.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <ctime>
#include <string>
#include <vector>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include "qss_vr_shm.h"

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "QssXr", __VA_ARGS__)
#define CHECK_XR(call)                                                       \
    do {                                                                     \
        XrResult r_ = (call);                                                \
        if (XR_FAILED(r_)) { LOG("%s failed: %d", #call, (int)r_); return false; } \
    } while (0)

namespace {

struct Hand {
    XrPath path = XR_NULL_PATH;
    XrSpace grip = XR_NULL_HANDLE, aim = XR_NULL_HANDLE;
    uint32_t lastHaptic = 0;
};

struct Eye {
    XrSwapchain swapchain = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    int32_t width = 0, height = 0;
    GLuint fbo = 0;
};

struct App {
    android_app* android = nullptr;
    bool resumed = false;

    EGLDisplay display = EGL_NO_DISPLAY;
    EGLConfig config = nullptr;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface surface = EGL_NO_SURFACE;

    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool running = false;
    XrSpace stage = XR_NULL_HANDLE, view = XR_NULL_HANDLE;
    Eye eyes[2];
    float refreshHz = 72.0f;

    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction gripPose = XR_NULL_HANDLE, aimPose = XR_NULL_HANDLE, trigger = XR_NULL_HANDLE, triggerTouch = XR_NULL_HANDLE,
             squeeze = XR_NULL_HANDLE, stick = XR_NULL_HANDLE, stickClick = XR_NULL_HANDLE, stickTouch = XR_NULL_HANDLE,
             primary = XR_NULL_HANDLE, secondary = XR_NULL_HANDLE, primaryTouch = XR_NULL_HANDLE,
             secondaryTouch = XR_NULL_HANDLE, menu = XR_NULL_HANDLE, haptic = XR_NULL_HANDLE;
    Hand hands[2];

    // The app itself as a panel in VR (XR_KHR_android_surface_swapchain): Java draws the library
    // into an Android Surface, shown as a quad in front of the player; Touch controllers point at it.
    JNIEnv* env = nullptr;
    bool surfaceExt = false;
    XrSwapchain panelSwapchain = XR_NULL_HANDLE;
    jmethodID onPanelPointer = nullptr;
    XrPosef panelPose{{0, 0, 0, 1}, {0, 0, 0}};
    bool panelPlaced = false;
    bool pointerDown = false, pointerWasHit = false, recenterHeld = false;
    int activeHand = 1;
    double lastGameFrame = -100.0;
    QssPoseState lastPose{};

    QssVrShm* shm = nullptr;
    uint64_t frameId = 0;
    uint64_t shownFrameSeq = 0;
    bool haveFrame = false;
    QssFrameInfo shown{};
    std::vector<uint8_t> staging;
};

App g;

constexpr int kPanelPxW = 1920, kPanelPxH = 1200;   // 1600 x 1000 dp at 192 dpi
constexpr float kPanelW = 1.6f, kPanelH = 1.0f;      // metres
constexpr float kPanelDistance = 1.5f;

double NowSeconds() {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return ts.tv_sec + ts.tv_nsec * 1e-9;
}

void Rotate(const XrQuaternionf& q, const float v[3], float out[3]) {
    float tx = 2 * (q.y * v[2] - q.z * v[1]), ty = 2 * (q.z * v[0] - q.x * v[2]), tz = 2 * (q.x * v[1] - q.y * v[0]);
    out[0] = v[0] + q.w * tx + (q.y * tz - q.z * ty);
    out[1] = v[1] + q.w * ty + (q.z * tx - q.x * tz);
    out[2] = v[2] + q.w * tz + (q.x * ty - q.y * tx);
}

float Dot(const float a[3], const float b[3]) { return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]; }

// ------------------------------------------------------------------ shared memory

bool OpenShm(const char* dir) {
    // Same file the app creates (VrBridge.shmFile): inside the runtime's /tmp so Wine can reach it.
    std::string tmp = std::string(dir) + "/rootfs/tmp";
    mkdir((std::string(dir) + "/rootfs").c_str(), 0700);
    mkdir(tmp.c_str(), 0700);
    std::string path = tmp + "/vrbridge.shm";
    int fd = open(path.c_str(), O_RDWR | O_CREAT, 0600);
    if (fd < 0) { LOG("open %s failed", path.c_str()); return false; }
    if (ftruncate(fd, QSS_VR_SHM_SIZE) != 0) { LOG("ftruncate failed"); close(fd); return false; }
    void* p = mmap(nullptr, QSS_VR_SHM_SIZE, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) { LOG("mmap failed"); return false; }
    g.shm = (QssVrShm*)p;
    if (g.shm->magic != QSS_VR_MAGIC || g.shm->version != QSS_VR_VERSION) {
        // Normally the app writes the header before starting the game; set it up if it didn't.
        memset(g.shm, 0, sizeof(QssVrShm));
        g.shm->version = QSS_VR_VERSION;
        g.shm->total_size = QSS_VR_SHM_SIZE;
        g.shm->rec_width = 1216;
        g.shm->rec_height = 1280;
        __sync_synchronize();
        g.shm->magic = QSS_VR_MAGIC;
    }
    g.shownFrameSeq = g.shm->frame_seq;
    LOG("shared memory %s, game sees %ux%u per eye", path.c_str(), g.shm->rec_width, g.shm->rec_height);
    return true;
}

void ToQss(const XrPosef& p, QssPose& out) {
    out.pos[0] = p.position.x; out.pos[1] = p.position.y; out.pos[2] = p.position.z;
    out.rot[0] = p.orientation.x; out.rot[1] = p.orientation.y; out.rot[2] = p.orientation.z; out.rot[3] = p.orientation.w;
}

XrPosef FromQss(const QssPose& p) {
    XrPosef r;
    r.position = {p.pos[0], p.pos[1], p.pos[2]};
    r.orientation = {p.rot[0], p.rot[1], p.rot[2], p.rot[3]};
    if (r.orientation.x == 0 && r.orientation.y == 0 && r.orientation.z == 0 && r.orientation.w == 0) r.orientation.w = 1;
    return r;
}

// ------------------------------------------------------------------ EGL

bool InitEgl() {
    g.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(g.display, nullptr, nullptr);
    const EGLint attribs[] = {EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
                              EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(g.display, attribs, &g.config, 1, &n) || n == 0) { LOG("eglChooseConfig failed"); return false; }
    const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    g.context = eglCreateContext(g.display, g.config, EGL_NO_CONTEXT, ctxAttribs);
    const EGLint pb[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    g.surface = eglCreatePbufferSurface(g.display, g.config, pb);
    if (g.context == EGL_NO_CONTEXT || !eglMakeCurrent(g.display, g.surface, g.surface, g.context)) { LOG("EGL context failed"); return false; }
    return true;
}

// ------------------------------------------------------------------ OpenXR setup

XrPath Path(const char* s) {
    XrPath p = XR_NULL_PATH;
    xrStringToPath(g.instance, s, &p);
    return p;
}

bool CreateAction(XrAction* out, const char* name, XrActionType type) {
    XrActionCreateInfo ci{XR_TYPE_ACTION_CREATE_INFO};
    ci.actionType = type;
    strncpy(ci.actionName, name, sizeof ci.actionName - 1);
    strncpy(ci.localizedActionName, name, sizeof ci.localizedActionName - 1);
    XrPath sub[2] = {g.hands[0].path, g.hands[1].path};
    ci.countSubactionPaths = 2;
    ci.subactionPaths = sub;
    CHECK_XR(xrCreateAction(g.actionSet, &ci, out));
    return true;
}

bool InitActions() {
    XrActionSetCreateInfo si{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(si.actionSetName, "steamvr_bridge");
    strcpy(si.localizedActionSetName, "SteamVR bridge");
    CHECK_XR(xrCreateActionSet(g.instance, &si, &g.actionSet));
    g.hands[0].path = Path("/user/hand/left");
    g.hands[1].path = Path("/user/hand/right");

    if (!CreateAction(&g.gripPose, "grip_pose", XR_ACTION_TYPE_POSE_INPUT)) return false;
    if (!CreateAction(&g.aimPose, "aim_pose", XR_ACTION_TYPE_POSE_INPUT)) return false;
    if (!CreateAction(&g.trigger, "trigger", XR_ACTION_TYPE_FLOAT_INPUT)) return false;
    if (!CreateAction(&g.triggerTouch, "trigger_touch", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!CreateAction(&g.squeeze, "squeeze", XR_ACTION_TYPE_FLOAT_INPUT)) return false;
    if (!CreateAction(&g.stick, "thumbstick", XR_ACTION_TYPE_VECTOR2F_INPUT)) return false;
    if (!CreateAction(&g.stickClick, "thumbstick_click", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!CreateAction(&g.stickTouch, "thumbstick_touch", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!CreateAction(&g.primary, "primary", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!CreateAction(&g.secondary, "secondary", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!CreateAction(&g.primaryTouch, "primary_touch", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!CreateAction(&g.secondaryTouch, "secondary_touch", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!CreateAction(&g.menu, "menu", XR_ACTION_TYPE_BOOLEAN_INPUT)) return false;
    if (!CreateAction(&g.haptic, "haptic", XR_ACTION_TYPE_VIBRATION_OUTPUT)) return false;

    std::vector<XrActionSuggestedBinding> b;
    auto add = [&](XrAction a, const char* p) { b.push_back({a, Path(p)}); };
    for (const char* h : {"/user/hand/left", "/user/hand/right"}) {
        std::string s(h);
        add(g.gripPose, (s + "/input/grip/pose").c_str());
        add(g.aimPose, (s + "/input/aim/pose").c_str());
        add(g.trigger, (s + "/input/trigger/value").c_str());
        add(g.triggerTouch, (s + "/input/trigger/touch").c_str());
        add(g.squeeze, (s + "/input/squeeze/value").c_str());
        add(g.stick, (s + "/input/thumbstick").c_str());
        add(g.stickClick, (s + "/input/thumbstick/click").c_str());
        add(g.stickTouch, (s + "/input/thumbstick/touch").c_str());
        add(g.haptic, (s + "/output/haptic").c_str());
    }
    add(g.primary, "/user/hand/left/input/x/click");
    add(g.secondary, "/user/hand/left/input/y/click");
    add(g.primaryTouch, "/user/hand/left/input/x/touch");
    add(g.secondaryTouch, "/user/hand/left/input/y/touch");
    add(g.menu, "/user/hand/left/input/menu/click");
    add(g.primary, "/user/hand/right/input/a/click");
    add(g.secondary, "/user/hand/right/input/b/click");
    add(g.primaryTouch, "/user/hand/right/input/a/touch");
    add(g.secondaryTouch, "/user/hand/right/input/b/touch");
    XrInteractionProfileSuggestedBinding sb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    sb.interactionProfile = Path("/interaction_profiles/oculus/touch_controller");
    sb.countSuggestedBindings = (uint32_t)b.size();
    sb.suggestedBindings = b.data();
    CHECK_XR(xrSuggestInteractionProfileBindings(g.instance, &sb));

    for (int i = 0; i < 2; ++i) {
        XrActionSpaceCreateInfo ci{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        ci.subactionPath = g.hands[i].path;
        ci.poseInActionSpace.orientation.w = 1;
        ci.action = g.gripPose;
        CHECK_XR(xrCreateActionSpace(g.session, &ci, &g.hands[i].grip));
        ci.action = g.aimPose;
        CHECK_XR(xrCreateActionSpace(g.session, &ci, &g.hands[i].aim));
    }
    XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    ai.countActionSets = 1;
    ai.actionSets = &g.actionSet;
    CHECK_XR(xrAttachSessionActionSets(g.session, &ai));
    return true;
}

bool InitXr() {
    PFN_xrInitializeLoaderKHR initLoader = nullptr;
    if (XR_SUCCEEDED(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", (PFN_xrVoidFunction*)&initLoader)) && initLoader) {
        XrLoaderInitInfoAndroidKHR li{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
        li.applicationVM = g.android->activity->vm;
        li.applicationContext = g.android->activity->clazz;
        initLoader((const XrLoaderInitInfoBaseHeaderKHR*)&li);
    }

    std::vector<const char*> exts = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
    uint32_t extCount = 0;
    xrEnumerateInstanceExtensionProperties(nullptr, 0, &extCount, nullptr);
    std::vector<XrExtensionProperties> props(extCount, {XR_TYPE_EXTENSION_PROPERTIES});
    xrEnumerateInstanceExtensionProperties(nullptr, extCount, &extCount, props.data());
    for (auto& p : props) if (strcmp(p.extensionName, XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME) == 0) g.surfaceExt = true;
    if (g.surfaceExt) exts.push_back(XR_KHR_ANDROID_SURFACE_SWAPCHAIN_EXTENSION_NAME);
    LOG("app panel in VR: %s", g.surfaceExt ? "yes" : "no (XR_KHR_android_surface_swapchain missing)");
    XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    android.applicationVM = g.android->activity->vm;
    android.applicationActivity = g.android->activity->clazz;
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &android;
    strcpy(ci.applicationInfo.applicationName, "Quest Steam VR bridge");
    ci.applicationInfo.applicationVersion = 1;
    strcpy(ci.applicationInfo.engineName, "QSS");
    ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 34);
    ci.enabledExtensionCount = (uint32_t)exts.size();
    ci.enabledExtensionNames = exts.data();
    CHECK_XR(xrCreateInstance(&ci, &g.instance));

    XrSystemGetInfo sg{XR_TYPE_SYSTEM_GET_INFO};
    sg.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    CHECK_XR(xrGetSystem(g.instance, &sg, &g.system));

    PFN_xrGetOpenGLESGraphicsRequirementsKHR getReqs = nullptr;
    CHECK_XR(xrGetInstanceProcAddr(g.instance, "xrGetOpenGLESGraphicsRequirementsKHR", (PFN_xrVoidFunction*)&getReqs));
    XrGraphicsRequirementsOpenGLESKHR reqs{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    CHECK_XR(getReqs(g.instance, g.system, &reqs));

    if (!InitEgl()) return false;
    XrGraphicsBindingOpenGLESAndroidKHR gb{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    gb.display = g.display;
    gb.config = g.config;
    gb.context = g.context;
    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
    sci.next = &gb;
    sci.systemId = g.system;
    CHECK_XR(xrCreateSession(g.instance, &sci, &g.session));

    XrReferenceSpaceCreateInfo rs{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rs.poseInReferenceSpace.orientation.w = 1;
    rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
    if (XR_FAILED(xrCreateReferenceSpace(g.session, &rs, &g.stage))) {
        // No guardian floor set up: local space is the closest standing equivalent.
        rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        CHECK_XR(xrCreateReferenceSpace(g.session, &rs, &g.stage));
        LOG("STAGE space unavailable, using LOCAL");
    }
    rs.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
    CHECK_XR(xrCreateReferenceSpace(g.session, &rs, &g.view));

    // Swapchains sized to what the game renders (recommended size we report to it).
    uint32_t fmtCount = 0;
    xrEnumerateSwapchainFormats(g.session, 0, &fmtCount, nullptr);
    std::vector<int64_t> fmts(fmtCount);
    xrEnumerateSwapchainFormats(g.session, fmtCount, &fmtCount, fmts.data());
    int64_t format = fmts.empty() ? GL_SRGB8_ALPHA8 : fmts[0];
    for (int64_t f : fmts) if (f == GL_SRGB8_ALPHA8) format = f;

    for (int e = 0; e < 2; ++e) {
        Eye& eye = g.eyes[e];
        eye.width = (int32_t)(g.shm->rec_width ? g.shm->rec_width : 1216);
        eye.height = (int32_t)(g.shm->rec_height ? g.shm->rec_height : 1280);
        XrSwapchainCreateInfo sc{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        sc.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_TRANSFER_DST_BIT;
        sc.format = format;
        sc.sampleCount = 1;
        sc.width = (uint32_t)eye.width;
        sc.height = (uint32_t)eye.height;
        sc.faceCount = 1;
        sc.arraySize = 1;
        sc.mipCount = 1;
        CHECK_XR(xrCreateSwapchain(g.session, &sc, &eye.swapchain));
        uint32_t n = 0;
        xrEnumerateSwapchainImages(eye.swapchain, 0, &n, nullptr);
        eye.images.assign(n, XrSwapchainImageOpenGLESKHR{XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        CHECK_XR(xrEnumerateSwapchainImages(eye.swapchain, n, &n, (XrSwapchainImageBaseHeader*)eye.images.data()));
        glGenFramebuffers(1, &eye.fbo);
    }
    LOG("OpenXR ready: swapchains %dx%d, format 0x%llx", g.eyes[0].width, g.eyes[0].height, (unsigned long long)format);
    return InitActions();
}

/** Creates the app panel's Android Surface swapchain and hands the Surface to QssXrActivity. */
void InitPanel() {
    if (!g.surfaceExt || !g.env) return;
    PFN_xrCreateSwapchainAndroidSurfaceKHR create = nullptr;
    if (XR_FAILED(xrGetInstanceProcAddr(g.instance, "xrCreateSwapchainAndroidSurfaceKHR", (PFN_xrVoidFunction*)&create)) || !create) return;
    XrSwapchainCreateInfo sc{XR_TYPE_SWAPCHAIN_CREATE_INFO};
    sc.usageFlags = XR_SWAPCHAIN_USAGE_SAMPLED_BIT | XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
    sc.sampleCount = 1;
    sc.width = kPanelPxW;
    sc.height = kPanelPxH;
    sc.faceCount = 1;
    sc.arraySize = 1;
    sc.mipCount = 1;
    jobject surface = nullptr;
    XrResult r = create(g.session, &sc, &g.panelSwapchain, &surface);
    if (XR_FAILED(r) || !surface) { LOG("panel surface swapchain failed: %d", (int)r); g.panelSwapchain = XR_NULL_HANDLE; return; }
    JNIEnv* env = g.env;
    jobject activity = g.android->activity->clazz;
    jclass cls = env->GetObjectClass(activity);
    jmethodID onSurface = env->GetMethodID(cls, "onPanelSurface", "(Landroid/view/Surface;II)V");
    g.onPanelPointer = env->GetMethodID(cls, "onPanelPointer", "(FFZZF)V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); LOG("QssXrActivity has no panel callbacks"); return; }
    env->CallVoidMethod(activity, onSurface, surface, (jint)kPanelPxW, (jint)kPanelPxH);
    if (env->ExceptionCheck()) { env->ExceptionDescribe(); env->ExceptionClear(); }
    LOG("app panel ready (%dx%d)", kPanelPxW, kPanelPxH);
}

/** Puts the panel 1.5 m in front of the player, at eye height, facing them. */
void PlacePanel(const QssPose& head) {
    float fwd0[3] = {0, 0, -1}, f[3];
    XrQuaternionf hq{head.rot[0], head.rot[1], head.rot[2], head.rot[3]};
    if (hq.x == 0 && hq.y == 0 && hq.z == 0 && hq.w == 0) return;
    Rotate(hq, fwd0, f);
    float len = sqrtf(f[0] * f[0] + f[2] * f[2]);
    if (len < 1e-3f) return;
    f[0] /= len; f[2] /= len;
    float yaw = atan2f(-f[0], -f[2]);
    g.panelPose.orientation = {0, sinf(yaw / 2), 0, cosf(yaw / 2)};
    g.panelPose.position = {head.pos[0] + f[0] * kPanelDistance, head.pos[1] - 0.1f, head.pos[2] + f[2] * kPanelDistance};
    g.panelPlaced = true;
}

/** Controller ray -> panel (u, v); trigger = touch. Sent to Java every frame while it points at the panel. */
void UpdatePanelPointer() {
    if (!g.onPanelPointer || !g.env || !g.panelPlaced) return;
    const XrQuaternionf& pq = g.panelPose.orientation;
    float p0[3] = {g.panelPose.position.x, g.panelPose.position.y, g.panelPose.position.z};
    float z[3] = {0, 0, 1}, x[3] = {1, 0, 0}, y[3] = {0, 1, 0}, n[3], right[3], up[3];
    Rotate(pq, z, n); Rotate(pq, x, right); Rotate(pq, y, up);
    // The hand pressing the trigger takes over the pointer.
    for (int h = 0; h < 2; ++h) if (g.lastPose.hand[h].trigger > 0.6f && h != g.activeHand && !g.pointerDown) g.activeHand = h;
    float u = 0, v = 0;
    bool hit = false;
    const QssController& c = g.lastPose.hand[g.activeHand];
    if (c.valid) {
        XrQuaternionf aq{c.aim_pose.rot[0], c.aim_pose.rot[1], c.aim_pose.rot[2], c.aim_pose.rot[3]};
        float o[3] = {c.aim_pose.pos[0], c.aim_pose.pos[1], c.aim_pose.pos[2]}, fwd[3] = {0, 0, -1}, d[3];
        Rotate(aq, fwd, d);
        float denom = Dot(d, n);
        if (fabsf(denom) > 1e-4f) {
            float diff[3] = {p0[0] - o[0], p0[1] - o[1], p0[2] - o[2]};
            float t = Dot(diff, n) / denom;
            if (t > 0) {
                float rel[3] = {o[0] + d[0] * t - p0[0], o[1] + d[1] * t - p0[1], o[2] + d[2] * t - p0[2]};
                u = Dot(rel, right) / kPanelW + 0.5f;
                v = 0.5f - Dot(rel, up) / kPanelH;
                hit = u >= 0 && u <= 1 && v >= 0 && v <= 1;
            }
        }
    }
    bool down = g.pointerDown ? c.trigger > 0.35f : c.trigger > 0.6f;
    if (!hit && !g.pointerDown) down = false;
    float scroll = fabsf(c.stick[1]) > 0.25f ? c.stick[1] : 0.0f;
    if (hit || g.pointerWasHit || down != g.pointerDown) {
        g.env->CallVoidMethod(g.android->activity->clazz, g.onPanelPointer, u, v, (jboolean)hit, (jboolean)down, scroll);
        if (g.env->ExceptionCheck()) g.env->ExceptionClear();
    }
    g.pointerDown = down;
    g.pointerWasHit = hit;
}

// ------------------------------------------------------------------ per frame

bool ActionBool(XrAction a, int hand) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a;
    gi.subactionPath = g.hands[hand].path;
    XrActionStateBoolean s{XR_TYPE_ACTION_STATE_BOOLEAN};
    return XR_SUCCEEDED(xrGetActionStateBoolean(g.session, &gi, &s)) && s.isActive && s.currentState;
}

float ActionFloat(XrAction a, int hand) {
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = a;
    gi.subactionPath = g.hands[hand].path;
    XrActionStateFloat s{XR_TYPE_ACTION_STATE_FLOAT};
    return XR_SUCCEEDED(xrGetActionStateFloat(g.session, &gi, &s)) && s.isActive ? s.currentState : 0.0f;
}

void WritePose(XrTime time) {
    QssPoseState st{};
    st.frame_id = ++g.frameId;
    st.display_time_ns = time;
    st.refresh_hz = g.refreshHz;

    XrViewLocateInfo vi{XR_TYPE_VIEW_LOCATE_INFO};
    vi.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    vi.displayTime = time;
    vi.space = g.stage;
    XrViewState vs{XR_TYPE_VIEW_STATE};
    XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    uint32_t n = 0;
    xrLocateViews(g.session, &vi, &vs, 2, &n, views);
    for (int e = 0; e < 2; ++e) {
        ToQss(views[e].pose, st.eye[e]);
        st.fov[e] = {views[e].fov.angleLeft, views[e].fov.angleRight, views[e].fov.angleUp, views[e].fov.angleDown};
    }
    XrSpaceLocation head{XR_TYPE_SPACE_LOCATION};
    if (XR_SUCCEEDED(xrLocateSpace(g.view, g.stage, time, &head))) ToQss(head.pose, st.head);
    float dx = views[1].pose.position.x - views[0].pose.position.x, dy = views[1].pose.position.y - views[0].pose.position.y,
          dz = views[1].pose.position.z - views[0].pose.position.z;
    st.ipd = sqrtf(dx * dx + dy * dy + dz * dz);

    XrActiveActionSet active{g.actionSet, XR_NULL_PATH};
    XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
    sync.countActiveActionSets = 1;
    sync.activeActionSets = &active;
    xrSyncActions(g.session, &sync);

    for (int h = 0; h < 2; ++h) {
        QssController& c = st.hand[h];
        XrSpaceVelocity vel{XR_TYPE_SPACE_VELOCITY};
        XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
        loc.next = &vel;
        if (XR_SUCCEEDED(xrLocateSpace(g.hands[h].grip, g.stage, time, &loc)) &&
            (loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT) && (loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT)) {
            c.valid = 1;
            ToQss(loc.pose, c.grip_pose);
            if (vel.velocityFlags & XR_SPACE_VELOCITY_LINEAR_VALID_BIT) { c.lin_vel[0] = vel.linearVelocity.x; c.lin_vel[1] = vel.linearVelocity.y; c.lin_vel[2] = vel.linearVelocity.z; }
            if (vel.velocityFlags & XR_SPACE_VELOCITY_ANGULAR_VALID_BIT) { c.ang_vel[0] = vel.angularVelocity.x; c.ang_vel[1] = vel.angularVelocity.y; c.ang_vel[2] = vel.angularVelocity.z; }
        }
        XrSpaceLocation aim{XR_TYPE_SPACE_LOCATION};
        if (XR_SUCCEEDED(xrLocateSpace(g.hands[h].aim, g.stage, time, &aim))) ToQss(aim.pose, c.aim_pose);
        c.trigger = ActionFloat(g.trigger, h);
        c.grip = ActionFloat(g.squeeze, h);
        XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
        gi.action = g.stick;
        gi.subactionPath = g.hands[h].path;
        XrActionStateVector2f v{XR_TYPE_ACTION_STATE_VECTOR2F};
        if (XR_SUCCEEDED(xrGetActionStateVector2f(g.session, &gi, &v)) && v.isActive) { c.stick[0] = v.currentState.x; c.stick[1] = v.currentState.y; }
        uint32_t btn = 0;
        if (ActionBool(g.primary, h)) btn |= QSS_BTN_A;
        if (ActionBool(g.secondary, h)) btn |= QSS_BTN_B;
        if (ActionBool(g.menu, h)) btn |= QSS_BTN_MENU;
        if (ActionBool(g.stickClick, h)) btn |= QSS_BTN_STICK;
        if (c.trigger > 0.75f) btn |= QSS_BTN_TRIGGER;
        if (c.grip > 0.7f) btn |= QSS_BTN_GRIP;
        if (ActionBool(g.primaryTouch, h)) btn |= QSS_TOUCH_A;
        if (ActionBool(g.secondaryTouch, h)) btn |= QSS_TOUCH_B;
        if (ActionBool(g.stickTouch, h)) btn |= QSS_TOUCH_STICK;
        if (ActionBool(g.triggerTouch, h)) btn |= QSS_TOUCH_TRIGGER;
        c.buttons = btn;
    }

    g.lastPose = st;
    // Seqlock publish: odd while writing.
    QssVrShm* s = g.shm;
    __atomic_add_fetch(&s->pose_seqlock, 1, __ATOMIC_ACQ_REL);
    memcpy((void*)&s->pose, &st, sizeof st);
    __atomic_add_fetch(&s->pose_seqlock, 1, __ATOMIC_RELEASE);
    __atomic_add_fetch(&s->pose_seq, 1, __ATOMIC_RELEASE);

    // Haptics the game asked for.
    for (int h = 0; h < 2; ++h) {
        uint32_t seq = __atomic_load_n(&s->haptic_seq[h], __ATOMIC_ACQUIRE);
        if (seq == g.hands[h].lastHaptic) continue;
        g.hands[h].lastHaptic = seq;
        XrHapticVibration hv{XR_TYPE_HAPTIC_VIBRATION};
        hv.amplitude = s->haptic_amplitude[h];
        hv.duration = (XrDuration)(s->haptic_seconds[h] * 1e9f);
        hv.frequency = XR_FREQUENCY_UNSPECIFIED;
        XrHapticActionInfo hi{XR_TYPE_HAPTIC_ACTION_INFO};
        hi.action = g.haptic;
        hi.subactionPath = g.hands[h].path;
        xrApplyHapticFeedback(g.session, &hi, (XrHapticBaseHeader*)&hv);
    }
}

/** Converts one eye from the shared memory to bottom-up RGBA and uploads it into the swapchain. */
void UploadEye(int e, const uint8_t* src, const QssFrameInfo& info) {
    Eye& eye = g.eyes[e];
    uint32_t idx = 0;
    XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
    if (XR_FAILED(xrAcquireSwapchainImage(eye.swapchain, &ai, &idx))) return;
    XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wi.timeout = XR_INFINITE_DURATION;
    xrWaitSwapchainImage(eye.swapchain, &wi);

    int w = (int)std::min<uint32_t>(info.width, (uint32_t)eye.width);
    int h = (int)std::min<uint32_t>(info.height, (uint32_t)eye.height);
    size_t row = (size_t)info.width * 4;
    g.staging.resize((size_t)w * h * 4);
    // D3D rows are top-down, GL textures bottom-up; swap rows (and BGRA -> RGBA) while copying.
    for (int y = 0; y < h; ++y) {
        const uint8_t* in = src + (size_t)(info.flip_y ? y : (info.height - 1 - y)) * row;
        uint8_t* out = g.staging.data() + (size_t)y * w * 4;
        if (info.format == QSS_FMT_BGRA8) {
            for (int x = 0; x < w; ++x) {
                out[x * 4 + 0] = in[x * 4 + 2];
                out[x * 4 + 1] = in[x * 4 + 1];
                out[x * 4 + 2] = in[x * 4 + 0];
                out[x * 4 + 3] = 255;
            }
        } else {
            memcpy(out, in, (size_t)w * 4);
        }
    }
    glBindTexture(GL_TEXTURE_2D, eye.images[idx].image);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, g.staging.data());
    glBindTexture(GL_TEXTURE_2D, 0);
    XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
    xrReleaseSwapchainImage(eye.swapchain, &ri);
}

/** Fills both swapchains with a colour while waiting for the game's first frame. */
void ClearEyes(float r, float gr, float b) {
    for (Eye& eye : g.eyes) {
        uint32_t idx = 0;
        XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        if (XR_FAILED(xrAcquireSwapchainImage(eye.swapchain, &ai, &idx))) continue;
        XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wi.timeout = XR_INFINITE_DURATION;
        xrWaitSwapchainImage(eye.swapchain, &wi);
        glBindFramebuffer(GL_FRAMEBUFFER, eye.fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, eye.images[idx].image, 0);
        glViewport(0, 0, eye.width, eye.height);
        glClearColor(r, gr, b, 1);
        glClear(GL_COLOR_BUFFER_BIT);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
        xrReleaseSwapchainImage(eye.swapchain, &ri);
    }
}

void RenderFrame() {
    XrFrameWaitInfo fwi{XR_TYPE_FRAME_WAIT_INFO};
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    if (XR_FAILED(xrWaitFrame(g.session, &fwi, &fs))) return;
    XrFrameBeginInfo fbi{XR_TYPE_FRAME_BEGIN_INFO};
    xrBeginFrame(g.session, &fbi);

    QssVrShm* s = g.shm;
    s->xr_running = g.state == XR_SESSION_STATE_FOCUSED ? 1 : 0;
    WritePose(fs.predictedDisplayTime);

    uint64_t seq = __atomic_load_n(&s->frame_seq, __ATOMIC_ACQUIRE);
    if (seq != g.shownFrameSeq && seq > 0) {
        uint32_t b = (uint32_t)((seq - 1) % 2);
        s->quest_reading = b + 1;
        __sync_synchronize();
        QssFrameInfo info = s->frame_info[b];
        if (info.width > 0 && info.height > 0 && info.width <= QSS_VR_MAX_EYE_W && info.height <= QSS_VR_MAX_EYE_H) {
            UploadEye(0, qss_vr_pixels(s, b, 0), info);
            UploadEye(1, qss_vr_pixels(s, b, 1), info);
            g.shown = info;
            g.haveFrame = true;
            g.lastGameFrame = NowSeconds();
        }
        s->quest_reading = 0;
        g.shownFrameSeq = seq;
    } else if (!g.haveFrame && (g.frameId % 36) == 1) {
        // Dark backdrop behind the app panel (slightly teal once a game has connected).
        if (s->game_connected) ClearEyes(0.02f, 0.07f, 0.08f);
        else ClearEyes(0.03f, 0.04f, 0.06f);
    }

    // The app panel shows whenever no game frames are coming in (library, downloads, loading screen).
    bool gameLive = g.haveFrame && NowSeconds() - g.lastGameFrame < 1.5;
    bool showPanel = g.panelSwapchain != XR_NULL_HANDLE && !gameLive;
    if (showPanel) {
        // Thumbstick click puts the panel back in front of you.
        bool recenter = (g.lastPose.hand[0].buttons | g.lastPose.hand[1].buttons) & QSS_BTN_STICK;
        if (!g.panelPlaced || (recenter && !g.recenterHeld)) PlacePanel(g.lastPose.head);
        g.recenterHeld = recenter;
        UpdatePanelPointer();
    }

    XrCompositionLayerProjectionView pv[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    for (int e = 0; e < 2; ++e) {
        const QssPoseState& p = g.haveFrame ? g.shown.pose : s->pose;
        pv[e].pose = FromQss(p.eye[e]);
        pv[e].fov = {p.fov[e].left, p.fov[e].right, p.fov[e].up, p.fov[e].down};
        pv[e].subImage.swapchain = g.eyes[e].swapchain;
        pv[e].subImage.imageRect.offset = {0, 0};
        int32_t w = g.haveFrame ? (int32_t)std::min<uint32_t>(g.shown.width, (uint32_t)g.eyes[e].width) : g.eyes[e].width;
        int32_t h = g.haveFrame ? (int32_t)std::min<uint32_t>(g.shown.height, (uint32_t)g.eyes[e].height) : g.eyes[e].height;
        pv[e].subImage.imageRect.extent = {w, h};
    }
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    layer.space = g.stage;
    layer.viewCount = 2;
    layer.views = pv;
    XrCompositionLayerQuad quad{XR_TYPE_COMPOSITION_LAYER_QUAD};
    quad.layerFlags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
    quad.space = g.stage;
    quad.eyeVisibility = XR_EYE_VISIBILITY_BOTH;
    quad.subImage.swapchain = g.panelSwapchain;
    quad.subImage.imageRect = {{0, 0}, {kPanelPxW, kPanelPxH}};
    quad.pose = g.panelPose;
    quad.size = {kPanelW, kPanelH};
    const XrCompositionLayerBaseHeader* layers[] = {(XrCompositionLayerBaseHeader*)&layer, (XrCompositionLayerBaseHeader*)&quad};
    bool submit = fs.shouldRender && g.frameId > 1;
    XrFrameEndInfo fei{XR_TYPE_FRAME_END_INFO};
    fei.displayTime = fs.predictedDisplayTime;
    fei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    fei.layerCount = submit ? ((showPanel && g.panelPlaced) ? 2 : 1) : 0;
    fei.layers = layers;
    xrEndFrame(g.session, &fei);
}

void HandleXrEvents() {
    XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(g.instance, &ev) == XR_SUCCESS) {
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto* sc = (XrEventDataSessionStateChanged*)&ev;
            g.state = sc->state;
            LOG("session state %d", (int)g.state);
            if (g.state == XR_SESSION_STATE_READY) {
                XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                if (XR_SUCCEEDED(xrBeginSession(g.session, &bi))) g.running = true;
            } else if (g.state == XR_SESSION_STATE_STOPPING) {
                xrEndSession(g.session);
                g.running = false;
            } else if (g.state == XR_SESSION_STATE_EXITING || g.state == XR_SESSION_STATE_LOSS_PENDING) {
                g.running = false;
                ANativeActivity_finish(g.android->activity);
            }
        }
        ev = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}

void OnAppCmd(android_app* app, int32_t cmd) {
    if (cmd == APP_CMD_RESUME) g.resumed = true;
    if (cmd == APP_CMD_PAUSE) g.resumed = false;
    (void)app;
}

}  // namespace

void android_main(android_app* app) {
    g.android = app;
    app->onAppCmd = OnAppCmd;
    JNIEnv* env = nullptr;
    app->activity->vm->AttachCurrentThread(&env, nullptr);
    g.env = env;

    bool ok = OpenShm(app->activity->internalDataPath) && InitXr();
    if (ok) InitPanel();
    if (!ok) LOG("VR bridge failed to start; see messages above");

    while (!app->destroyRequested) {
        int events;
        android_poll_source* source;
        int timeout = (ok && g.running) ? 0 : 50;
        while (ALooper_pollOnce(timeout, nullptr, &events, (void**)&source) >= 0) {
            if (source) source->process(app, source);
            if (app->destroyRequested) break;
            timeout = 0;
        }
        if (!ok) continue;
        HandleXrEvents();
        if (g.running) RenderFrame();
    }

    if (g.shm) g.shm->xr_running = 0;
    if (g.session) xrDestroySession(g.session);
    if (g.instance) xrDestroyInstance(g.instance);
    app->activity->vm->DetachCurrentThread();
}
