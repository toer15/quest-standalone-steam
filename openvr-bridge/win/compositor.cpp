#include "bridge.h"

#include <d3d11.h>

using namespace vr;

namespace qss {

namespace {

uint64_t g_lastPoseSeq = 0;
uint64_t g_framesSubmitted = 0;
bool g_eyeDone[2] = {false, false};
uint32_t g_writeBuffer = 0;
QssFrameInfo g_pending{};

// ---- half float -> sRGB8, for RGBA16F swapchains (linear HDR) ----
float HalfToFloat(uint16_t h) {
    uint32_t sign = (h >> 15) & 1, exp = (h >> 10) & 0x1f, man = h & 0x3ff;
    float v;
    if (exp == 0) v = ldexpf((float)man, -24);
    else if (exp == 31) v = man ? 0.0f : 65504.0f;
    else v = ldexpf((float)(man | 0x400), (int)exp - 25);
    return sign ? -v : v;
}
uint8_t LinearToSrgb8(float x) {
    if (!(x > 0)) return 0;
    if (x >= 1) return 255;
    float s = x <= 0.0031308f ? x * 12.92f : 1.055f * powf(x, 1.0f / 2.4f) - 0.055f;
    return (uint8_t)(s * 255.0f + 0.5f);
}

enum class PixKind { Rgba8, Bgra8, Rgba16f, Rgb10a2, Unsupported };

PixKind KindOf(DXGI_FORMAT f) {
    switch (f) {
        case DXGI_FORMAT_R8G8B8A8_TYPELESS: case DXGI_FORMAT_R8G8B8A8_UNORM: case DXGI_FORMAT_R8G8B8A8_UNORM_SRGB:
            return PixKind::Rgba8;
        case DXGI_FORMAT_B8G8R8A8_TYPELESS: case DXGI_FORMAT_B8G8R8A8_UNORM: case DXGI_FORMAT_B8G8R8A8_UNORM_SRGB:
        case DXGI_FORMAT_B8G8R8X8_TYPELESS: case DXGI_FORMAT_B8G8R8X8_UNORM: case DXGI_FORMAT_B8G8R8X8_UNORM_SRGB:
            return PixKind::Bgra8;
        case DXGI_FORMAT_R16G16B16A16_TYPELESS: case DXGI_FORMAT_R16G16B16A16_FLOAT:
            return PixKind::Rgba16f;
        case DXGI_FORMAT_R10G10B10A2_TYPELESS: case DXGI_FORMAT_R10G10B10A2_UNORM:
            return PixKind::Rgb10a2;
        default:
            return PixKind::Unsupported;
    }
}

DXGI_FORMAT ResolveFormat(DXGI_FORMAT f) {
    switch (f) {
        case DXGI_FORMAT_R8G8B8A8_TYPELESS: return DXGI_FORMAT_R8G8B8A8_UNORM;
        case DXGI_FORMAT_B8G8R8A8_TYPELESS: return DXGI_FORMAT_B8G8R8A8_UNORM;
        case DXGI_FORMAT_B8G8R8X8_TYPELESS: return DXGI_FORMAT_B8G8R8X8_UNORM;
        case DXGI_FORMAT_R16G16B16A16_TYPELESS: return DXGI_FORMAT_R16G16B16A16_FLOAT;
        case DXGI_FORMAT_R10G10B10A2_TYPELESS: return DXGI_FORMAT_R10G10B10A2_UNORM;
        default: return f;
    }
}

/** Per-eye staging textures (CPU readable), recreated when size/format changes. */
struct Staging {
    ID3D11Texture2D* tex = nullptr;
    ID3D11Texture2D* resolve = nullptr;  // for MSAA sources
    UINT w = 0, h = 0;
    DXGI_FORMAT fmt = DXGI_FORMAT_UNKNOWN;
    ID3D11Device* dev = nullptr;
    void Release() {
        if (tex) tex->Release();
        if (resolve) resolve->Release();
        tex = resolve = nullptr;
    }
};
Staging g_staging[2];

bool CopyD3D11(int eye, ID3D11Texture2D* src, UINT arrayIndex, const VRTextureBounds_t* bounds, uint8_t* dst, QssFrameInfo* info) {
    ID3D11Device* dev = nullptr;
    src->GetDevice(&dev);
    if (!dev) return false;
    ID3D11DeviceContext* ctx = nullptr;
    dev->GetImmediateContext(&ctx);
    D3D11_TEXTURE2D_DESC d;
    src->GetDesc(&d);

    PixKind kind = KindOf(d.Format);
    if (kind == PixKind::Unsupported) {
        static bool warned = false;
        if (!warned) { Log("Submit: texture format %d not supported yet", (int)d.Format); warned = true; }
        ctx->Release(); dev->Release();
        return false;
    }

    float u0 = 0, u1 = 1, v0 = 0, v1 = 1;
    if (bounds) { u0 = bounds->uMin; u1 = bounds->uMax; v0 = bounds->vMin; v1 = bounds->vMax; }
    bool flipY = v0 > v1;
    if (flipY) { float t = v0; v0 = v1; v1 = t; }
    UINT x0 = (UINT)(u0 * d.Width + 0.5f), x1 = (UINT)(u1 * d.Width + 0.5f);
    UINT y0 = (UINT)(v0 * d.Height + 0.5f), y1 = (UINT)(v1 * d.Height + 0.5f);
    UINT w = x1 > x0 ? x1 - x0 : 1, h = y1 > y0 ? y1 - y0 : 1;
    if (w > QSS_VR_MAX_EYE_W) w = QSS_VR_MAX_EYE_W;
    if (h > QSS_VR_MAX_EYE_H) h = QSS_VR_MAX_EYE_H;

    DXGI_FORMAT fmt = ResolveFormat(d.Format);
    Staging& st = g_staging[eye];
    if (st.dev != dev || st.w != w || st.h != h || st.fmt != fmt) {
        st.Release();
        st.dev = dev; st.w = w; st.h = h; st.fmt = fmt;
        D3D11_TEXTURE2D_DESC sd{};
        sd.Width = w; sd.Height = h; sd.MipLevels = 1; sd.ArraySize = 1; sd.Format = fmt;
        sd.SampleDesc.Count = 1; sd.Usage = D3D11_USAGE_STAGING; sd.CPUAccessFlags = D3D11_CPU_ACCESS_READ;
        if (FAILED(dev->CreateTexture2D(&sd, nullptr, &st.tex))) { Log("CreateTexture2D(staging) failed"); st.dev = nullptr; ctx->Release(); dev->Release(); return false; }
        Log("eye %d: %ux%u format %d%s", eye, w, h, (int)d.Format, d.SampleDesc.Count > 1 ? " (MSAA, resolving)" : "");
    }

    ID3D11Resource* copyFrom = src;
    UINT sub = D3D11CalcSubresource(0, arrayIndex, d.MipLevels);
    if (d.SampleDesc.Count > 1) {
        if (!st.resolve) {
            D3D11_TEXTURE2D_DESC rd = d;
            rd.Format = fmt; rd.SampleDesc.Count = 1; rd.SampleDesc.Quality = 0; rd.ArraySize = 1; rd.MipLevels = 1;
            rd.Usage = D3D11_USAGE_DEFAULT; rd.BindFlags = 0; rd.CPUAccessFlags = 0; rd.MiscFlags = 0;
            dev->CreateTexture2D(&rd, nullptr, &st.resolve);
        }
        if (st.resolve) {
            ctx->ResolveSubresource(st.resolve, 0, src, sub, fmt);
            copyFrom = st.resolve;
            sub = 0;
        }
    }
    D3D11_BOX box{x0, y0, 0, x0 + w, y0 + h, 1};
    ctx->CopySubresourceRegion(st.tex, 0, 0, 0, 0, copyFrom, sub, &box);

    D3D11_MAPPED_SUBRESOURCE m;
    bool ok = SUCCEEDED(ctx->Map(st.tex, 0, D3D11_MAP_READ, 0, &m));
    if (ok) {
        const uint8_t* row = (const uint8_t*)m.pData;
        for (UINT y = 0; y < h; ++y, row += m.RowPitch) {
            uint8_t* o = dst + (size_t)y * w * 4;
            if (kind == PixKind::Rgba8 || kind == PixKind::Bgra8) {
                memcpy(o, row, (size_t)w * 4);
            } else if (kind == PixKind::Rgba16f) {
                const uint16_t* in = (const uint16_t*)row;
                for (UINT x = 0; x < w; ++x) {
                    o[x * 4 + 0] = LinearToSrgb8(HalfToFloat(in[x * 4 + 0]));
                    o[x * 4 + 1] = LinearToSrgb8(HalfToFloat(in[x * 4 + 1]));
                    o[x * 4 + 2] = LinearToSrgb8(HalfToFloat(in[x * 4 + 2]));
                    o[x * 4 + 3] = 255;
                }
            } else {  // R10G10B10A2
                const uint32_t* in = (const uint32_t*)row;
                for (UINT x = 0; x < w; ++x) {
                    uint32_t p = in[x];
                    o[x * 4 + 0] = (uint8_t)((p & 0x3ff) >> 2);
                    o[x * 4 + 1] = (uint8_t)(((p >> 10) & 0x3ff) >> 2);
                    o[x * 4 + 2] = (uint8_t)(((p >> 20) & 0x3ff) >> 2);
                    o[x * 4 + 3] = 255;
                }
            }
        }
        ctx->Unmap(st.tex, 0);
    }
    info->width = w;
    info->height = h;
    info->format = kind == PixKind::Bgra8 ? QSS_FMT_BGRA8 : QSS_FMT_RGBA8;
    info->flip_y = flipY ? 1 : 0;
    ctx->Release();
    dev->Release();
    return ok;
}

}  // namespace

class CCompositor : public IVRCompositor {
public:
    void SetTrackingSpace(ETrackingUniverseOrigin o) override { g_origin = o; }
    ETrackingUniverseOrigin GetTrackingSpace() override { return g_origin; }

    EVRCompositorError WaitGetPoses(TrackedDevicePose_t* render, uint32_t nRender, TrackedDevicePose_t* game, uint32_t nGame) override {
        if (g_shm.s) {
            QssPoseState st;
            // The Quest side publishes one pose per display frame; waiting on it paces the game to the headset.
            if (g_shm.WaitNewPose(&g_lastPoseSeq, &st, 100)) g_pose = st;
            if (g_seatedOrigin[0] == 0 && g_seatedOrigin[1] == 0 && g_seatedOrigin[2] == 0 && g_pose.frame_id) {
                memcpy(g_seatedOrigin, g_pose.head.pos, sizeof g_seatedOrigin);
            }
        } else {
            Sleep(11);
        }
        g_renderPose = g_pose;
        UpdateLegacyButtonEvents();
        return GetLastPoses(render, nRender, game, nGame);
    }

    EVRCompositorError GetLastPoses(TrackedDevicePose_t* render, uint32_t nRender, TrackedDevicePose_t* game, uint32_t nGame) override {
        for (uint32_t i = 0; render && i < nRender; ++i) FillDevicePose(&render[i], i, g_renderPose, g_origin);
        for (uint32_t i = 0; game && i < nGame; ++i) FillDevicePose(&game[i], i, g_renderPose, g_origin);
        return VRCompositorError_None;
    }

    EVRCompositorError GetLastPoseForTrackedDeviceIndex(TrackedDeviceIndex_t i, TrackedDevicePose_t* out, TrackedDevicePose_t* outGame) override {
        if (i >= kDeviceCount) return VRCompositorError_IndexOutOfRange;
        if (out) FillDevicePose(out, i, g_renderPose, g_origin);
        if (outGame) FillDevicePose(outGame, i, g_renderPose, g_origin);
        return VRCompositorError_None;
    }

    EVRCompositorError Submit(EVREye eye, const Texture_t* tex, const VRTextureBounds_t* bounds, EVRSubmitFlags flags) override {
        return SubmitWithArrayIndex(eye, tex, 0, bounds, flags);
    }

    EVRCompositorError SubmitWithArrayIndex(EVREye eye, const Texture_t* tex, uint32_t arrayIndex, const VRTextureBounds_t* bounds, EVRSubmitFlags) override {
        if (!tex || !tex->handle) return VRCompositorError_InvalidTexture;
        if (!g_shm.s) return VRCompositorError_None;
        int e = eye == Eye_Left ? 0 : 1;
        if (!g_eyeDone[0] && !g_eyeDone[1]) {
            // Start of a frame: pick the buffer the headset isn't reading.
            g_writeBuffer = (uint32_t)(g_shm.s->frame_seq % 2);
            for (int i = 0; i < 40 && g_shm.s->quest_reading == g_writeBuffer + 1; ++i) Sleep(0);
            g_pending.pose = g_renderPose;
        }
        uint8_t* dst = qss_vr_pixels(g_shm.s, g_writeBuffer, (uint32_t)e);
        bool ok = false;
        if (tex->eType == TextureType_DirectX) {
            ok = CopyD3D11(e, (ID3D11Texture2D*)tex->handle, arrayIndex, bounds, dst, &g_pending);
        } else {
            static bool warned[8] = {};
            int t = (int)tex->eType & 7;
            if (!warned[t]) { Log("Submit: texture type %d not supported yet (D3D11 only)", (int)tex->eType); warned[t] = true; }
            return VRCompositorError_None;
        }
        if (!ok) return VRCompositorError_None;
        g_eyeDone[e] = true;
        if (g_eyeDone[0] && g_eyeDone[1]) {
            g_shm.s->frame_info[g_writeBuffer] = g_pending;
            MemoryBarrier();
            g_shm.s->frame_seq = g_shm.s->frame_seq + 1;
            g_eyeDone[0] = g_eyeDone[1] = false;
            if (++g_framesSubmitted == 1) Log("first frame sent to the headset (%ux%u per eye)", g_pending.width, g_pending.height);
        }
        return VRCompositorError_None;
    }

    void ClearLastSubmittedFrame() override {}
    void PostPresentHandoff() override {}

    bool GetFrameTiming(Compositor_FrameTiming* t, uint32_t) override {
        if (!t) return false;
        uint32_t size = t->m_nSize;
        memset(t, 0, size && size < sizeof *t ? size : sizeof *t);
        t->m_nSize = size;
        t->m_nFrameIndex = (uint32_t)g_renderPose.frame_id;
        t->m_nNumFramePresents = 1;
        return true;
    }
    uint32_t GetFrameTimings(Compositor_FrameTiming* t, uint32_t n) override { return n && GetFrameTiming(t, 0) ? 1 : 0; }
    float GetFrameTimeRemaining() override { return 0.005f; }
    void GetCumulativeStats(Compositor_CumulativeStats* s, uint32_t size) override { if (s) memset(s, 0, size); }
    void FadeToColor(float, float, float, float, float, bool) override {}
    HmdColor_t GetCurrentFadeColor(bool) override { return HmdColor_t{0, 0, 0, 0}; }
    void FadeGrid(float, bool) override {}
    float GetCurrentGridAlpha() override { return 0; }
    EVRCompositorError SetSkyboxOverride(const Texture_t*, uint32_t) override { return VRCompositorError_None; }
    void ClearSkyboxOverride() override {}
    void CompositorBringToFront() override {}
    void CompositorGoToBack() override {}
    void CompositorQuit() override {}
    bool IsFullscreen() override { return true; }
    uint32_t GetCurrentSceneFocusProcess() override { return GetCurrentProcessId(); }
    uint32_t GetLastFrameRenderer() override { return GetCurrentProcessId(); }
    bool CanRenderScene() override { return true; }
    void ShowMirrorWindow() override {}
    void HideMirrorWindow() override {}
    bool IsMirrorWindowVisible() override { return false; }
    void CompositorDumpImages() override {}
    bool ShouldAppRenderWithLowResources() override { return true; }
    void ForceInterleavedReprojectionOn(bool) override {}
    void ForceReconnectProcess() override {}
    void SuspendRendering(bool) override {}
    EVRCompositorError GetMirrorTextureD3D11(EVREye, void*, void**) override { return VRCompositorError_RequestFailed; }
    void ReleaseMirrorTextureD3D11(void*) override {}
    EVRCompositorError GetMirrorTextureGL(EVREye, glUInt_t*, glSharedTextureHandle_t*) override { return VRCompositorError_RequestFailed; }
    bool ReleaseSharedGLTexture(glUInt_t, glSharedTextureHandle_t) override { return false; }
    void LockGLSharedTextureForAccess(glSharedTextureHandle_t) override {}
    void UnlockGLSharedTextureForAccess(glSharedTextureHandle_t) override {}
    uint32_t GetVulkanInstanceExtensionsRequired(char* buf, uint32_t size) override { return CopyString("", buf, size); }
    uint32_t GetVulkanDeviceExtensionsRequired(VkPhysicalDevice_T*, char* buf, uint32_t size) override { return CopyString("", buf, size); }
    void SetExplicitTimingMode(EVRCompositorTimingMode) override {}
    EVRCompositorError SubmitExplicitTimingData() override { return VRCompositorError_None; }
    bool IsMotionSmoothingEnabled() override { return false; }
    bool IsMotionSmoothingSupported() override { return false; }
    bool IsCurrentSceneFocusAppLoading() override { return false; }
    EVRCompositorError SetStageOverride_Async(const char*, const HmdMatrix34_t*, const Compositor_StageRenderSettings*, uint32_t) override { return VRCompositorError_None; }
    void ClearStageOverride() override {}
    bool GetCompositorBenchmarkResults(Compositor_BenchmarkResults*, uint32_t) override { return false; }
    EVRCompositorError GetLastPosePredictionIDs(uint32_t* r, uint32_t* g) override {
        if (r) *r = (uint32_t)g_renderPose.frame_id;
        if (g) *g = (uint32_t)g_renderPose.frame_id;
        return VRCompositorError_None;
    }
    EVRCompositorError GetPosesForFrame(uint32_t, TrackedDevicePose_t* poses, uint32_t n) override {
        for (uint32_t i = 0; poses && i < n; ++i) FillDevicePose(&poses[i], i, g_renderPose, g_origin);
        return VRCompositorError_None;
    }
};

IVRCompositor* Compositor() { static CCompositor c; return &c; }

}  // namespace qss
