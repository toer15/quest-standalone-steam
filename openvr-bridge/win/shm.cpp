#include "bridge.h"

#include <cstdarg>
#include <cstdio>

namespace qss {

Shm g_shm;
QssPoseState g_pose{};
QssPoseState g_renderPose{};
vr::ETrackingUniverseOrigin g_origin = vr::TrackingUniverseStanding;
float g_seatedOrigin[3] = {0, 0, 0};

void Log(const char* fmt, ...) {
    char buf[1024];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    // stderr reaches the in-app log (Box64/Wine stdout+stderr are streamed there).
    fprintf(stderr, "[qss-vr] %s\n", buf);
    fflush(stderr);
    OutputDebugStringA(buf);
}

typedef char* (CDECL* WineGetDosFileName)(const char*);

static HANDLE OpenUnixPath(const char* unixPath) {
    // Wine converts a unix path to a DOS path via kernel32!wine_get_dos_file_name.
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    auto toDos = k32 ? (WineGetDosFileName)GetProcAddress(k32, "wine_get_dos_file_name") : nullptr;
    if (toDos) {
        if (char* dos = toDos(unixPath)) {
            HANDLE h = CreateFileA(dos, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                                   OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
            HeapFree(GetProcessHeap(), 0, dos);
            if (h != INVALID_HANDLE_VALUE) return h;
        }
    }
    // Fallback: Wine maps Z: to the unix root by default.
    std::string z = std::string("Z:") + unixPath;
    for (auto& c : z) if (c == '/') c = '\\';
    return CreateFileA(z.c_str(), GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr,
                       OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, nullptr);
}

bool Shm::Open() {
    if (s) return true;
    char path[1024];
    DWORD n = GetEnvironmentVariableA("QSS_VR_SHM", path, sizeof path);
    if (n == 0 || n >= sizeof path) { Log("QSS_VR_SHM not set: start the game from the Quest app"); return false; }
    HANDLE f = OpenUnixPath(path);
    if (f == INVALID_HANDLE_VALUE) { Log("can't open %s (error %lu)", path, GetLastError()); return false; }
    HANDLE m = CreateFileMappingA(f, nullptr, PAGE_READWRITE, 0, QSS_VR_SHM_SIZE, nullptr);
    if (!m) { Log("CreateFileMapping failed (%lu)", GetLastError()); CloseHandle(f); return false; }
    void* p = MapViewOfFile(m, FILE_MAP_ALL_ACCESS, 0, 0, QSS_VR_SHM_SIZE);
    if (!p) { Log("MapViewOfFile failed (%lu)", GetLastError()); CloseHandle(m); CloseHandle(f); return false; }
    auto* shm = (QssVrShm*)p;
    if (shm->magic != QSS_VR_MAGIC || shm->version != QSS_VR_VERSION) {
        Log("shared memory not initialised by the Quest app (magic %08x version %u)", shm->magic, shm->version);
        UnmapViewOfFile(p); CloseHandle(m); CloseHandle(f);
        return false;
    }
    s = shm;
    Log("connected to Quest VR bridge: %s, recommended %ux%u per eye", path, shm->rec_width, shm->rec_height);
    return true;
}

bool Shm::ReadPose(QssPoseState* out) {
    if (!s || s->pose_seq == 0) return false;
    for (int tries = 0; tries < 1000; ++tries) {
        uint32_t a = s->pose_seqlock;
        if (a & 1) { YieldProcessor(); continue; }
        MemoryBarrier();
        memcpy(out, (const void*)&s->pose, sizeof *out);
        MemoryBarrier();
        if (s->pose_seqlock == a) return true;
    }
    return false;
}

bool Shm::WaitNewPose(uint64_t* lastSeq, QssPoseState* out, DWORD timeoutMs) {
    if (!s) return false;
    DWORD start = GetTickCount();
    while (s->pose_seq == *lastSeq) {
        if (GetTickCount() - start > timeoutMs) return ReadPose(out);
        if (s->pose_seq == *lastSeq) Sleep(1);
    }
    *lastSeq = s->pose_seq;
    return ReadPose(out);
}

void RefreshPose() {
    QssPoseState st;
    if (g_shm.ReadPose(&st)) g_pose = st;
}

// ---------------------------------------------------------------- math

vr::HmdMatrix34_t PoseToMatrix(const QssPose& p) {
    float x = p.rot[0], y = p.rot[1], z = p.rot[2], w = p.rot[3];
    vr::HmdMatrix34_t m;
    m.m[0][0] = 1 - 2 * (y * y + z * z); m.m[0][1] = 2 * (x * y - z * w);     m.m[0][2] = 2 * (x * z + y * w);     m.m[0][3] = p.pos[0];
    m.m[1][0] = 2 * (x * y + z * w);     m.m[1][1] = 1 - 2 * (x * x + z * z); m.m[1][2] = 2 * (y * z - x * w);     m.m[1][3] = p.pos[1];
    m.m[2][0] = 2 * (x * z - y * w);     m.m[2][1] = 2 * (y * z + x * w);     m.m[2][2] = 1 - 2 * (x * x + y * y); m.m[2][3] = p.pos[2];
    return m;
}

static void QuatMul(const float a[4], const float b[4], float out[4]) {
    float r[4] = {
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2],
    };
    memcpy(out, r, sizeof r);
}

static void QuatRotate(const float q[4], const float v[3], float out[3]) {
    float qv[4] = {v[0], v[1], v[2], 0}, conj[4] = {-q[0], -q[1], -q[2], q[3]}, t[4], r[4];
    QuatMul(q, qv, t);
    QuatMul(t, conj, r);
    out[0] = r[0]; out[1] = r[1]; out[2] = r[2];
}

QssPose RelativePose(const QssPose& parent, const QssPose& child) {
    QssPose r;
    float inv[4] = {-parent.rot[0], -parent.rot[1], -parent.rot[2], parent.rot[3]};
    float d[3] = {child.pos[0] - parent.pos[0], child.pos[1] - parent.pos[1], child.pos[2] - parent.pos[2]};
    QuatRotate(inv, d, r.pos);
    QuatMul(inv, child.rot, r.rot);
    return r;
}

void ToUniverse(QssPose& p, vr::ETrackingUniverseOrigin origin) {
    if (origin == vr::TrackingUniverseSeated) {
        for (int i = 0; i < 3; ++i) p.pos[i] -= g_seatedOrigin[i];
    }
}

vr::TrackedDeviceIndex_t HandToDevice(int hand) { return hand == 0 ? kLeft : kRight; }
int DeviceToHand(vr::TrackedDeviceIndex_t idx) { return idx == kLeft ? 0 : idx == kRight ? 1 : -1; }

void FillDevicePose(vr::TrackedDevicePose_t* out, vr::TrackedDeviceIndex_t idx, const QssPoseState& st,
                    vr::ETrackingUniverseOrigin origin) {
    memset(out, 0, sizeof *out);
    bool connected = g_shm.s && st.frame_id > 0;
    QssPose p{};
    bool valid = false;
    const float* lin = nullptr;
    const float* ang = nullptr;
    if (idx == kHmd) { p = st.head; valid = connected; }
    else if (int h = DeviceToHand(idx); h >= 0) {
        p = st.hand[h].grip_pose;
        valid = connected && st.hand[h].valid;
        lin = st.hand[h].lin_vel;
        ang = st.hand[h].ang_vel;
    } else return;
    if (p.rot[0] == 0 && p.rot[1] == 0 && p.rot[2] == 0 && p.rot[3] == 0) p.rot[3] = 1;
    ToUniverse(p, origin);
    out->mDeviceToAbsoluteTracking = PoseToMatrix(p);
    if (lin) for (int i = 0; i < 3; ++i) { out->vVelocity.v[i] = lin[i]; out->vAngularVelocity.v[i] = ang[i]; }
    out->eTrackingResult = valid ? vr::TrackingResult_Running_OK : vr::TrackingResult_Running_OutOfRange;
    out->bPoseIsValid = valid;
    out->bDeviceIsConnected = connected;
}

uint32_t CopyString(const char* src, char* dst, uint32_t size) {
    uint32_t need = (uint32_t)strlen(src) + 1;
    if (dst && size > 0) {
        uint32_t n = need < size ? need : size;
        memcpy(dst, src, n - 1);
        dst[n - 1] = 0;
    }
    return need;
}

}  // namespace qss
