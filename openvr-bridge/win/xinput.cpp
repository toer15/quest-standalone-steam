// XInput for games running in Wine on the Quest: reads the Bluetooth controller (Xbox, DualSense,
// DualShock, Switch Pro, 8BitDo...) that the Quest app writes into <rootfs>/tmp/gamepad.shm, and
// passes rumble back. Built as one DLL and shipped under every XInput name (1_1 .. 1_4, 9_1_0),
// 64-bit for system32 and 32-bit for syswow64. Exports and ordinals: xinput.def.
#include <windows.h>
#include <xinput.h>

#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>

#include "qss_pad_shm.h"

namespace {

QssPadShm* g_shm = nullptr;
bool g_tried = false;
bool g_enabled = true;
DWORD g_lastTry = 0;

void Log(const char* fmt, ...) {
    char buf[512];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof buf, fmt, ap);
    va_end(ap);
    fprintf(stderr, "[qss-pad] %s\n", buf);
    fflush(stderr);
}

typedef WCHAR*(CDECL* WineGetDosFileName)(const char*);

HANDLE OpenRw(const WCHAR* w, const char* a) {
    return w ? CreateFileW(w, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL, nullptr)
             : CreateFileA(a, GENERIC_READ | GENERIC_WRITE, FILE_SHARE_READ | FILE_SHARE_WRITE, nullptr, OPEN_EXISTING,
                           FILE_ATTRIBUTE_NORMAL, nullptr);
}

HANDLE OpenUnixPath(const char* unixPath) {
    HMODULE k32 = GetModuleHandleA("kernel32.dll");
    auto toDos = k32 ? (WineGetDosFileName)GetProcAddress(k32, "wine_get_dos_file_name") : nullptr;
    if (toDos) {
        if (WCHAR* dos = toDos(unixPath)) {
            HANDLE h = OpenRw(dos, nullptr);
            HeapFree(GetProcessHeap(), 0, dos);
            if (h != INVALID_HANDLE_VALUE) return h;
        }
    }
    std::string unix = std::string("\\\\?\\unix") + unixPath;
    for (auto& c : unix) if (c == '/') c = '\\';
    HANDLE h = OpenRw(nullptr, unix.c_str());
    if (h != INVALID_HANDLE_VALUE) return h;
    // Winlator's Z: is its rootfs; plain Wine's Z: is "/".
    const char* tmp = strstr(unixPath, "/rootfs/");
    for (std::string z : {std::string("Z:") + (tmp ? tmp + 7 : unixPath), std::string("Z:") + unixPath}) {
        for (auto& c : z) if (c == '/') c = '\\';
        h = OpenRw(nullptr, z.c_str());
        if (h != INVALID_HANDLE_VALUE) return h;
    }
    return INVALID_HANDLE_VALUE;
}

// Maps the shared memory once; retried at most every 2 s so a game started before the app wrote
// the file still finds the controller later.
QssPadShm* Shm() {
    if (g_shm) return g_shm;
    DWORD now = GetTickCount();
    if (g_tried && now - g_lastTry < 2000) return nullptr;
    bool first = !g_tried;
    g_tried = true;
    g_lastTry = now;
    char path[1024];
    DWORD n = GetEnvironmentVariableA("QSS_PAD_SHM", path, sizeof path);
    if (n == 0 || n >= sizeof path) { if (first) Log("QSS_PAD_SHM not set: start the game from the Quest app"); return nullptr; }
    HANDLE f = OpenUnixPath(path);
    if (f == INVALID_HANDLE_VALUE) { if (first) Log("can't open %s", path); return nullptr; }
    HANDLE m = CreateFileMappingA(f, nullptr, PAGE_READWRITE, 0, QSS_PAD_SHM_SIZE, nullptr);
    void* p = m ? MapViewOfFile(m, FILE_MAP_ALL_ACCESS, 0, 0, QSS_PAD_SHM_SIZE) : nullptr;
    if (!p) { if (first) Log("can't map %s (%lu)", path, GetLastError()); if (m) CloseHandle(m); CloseHandle(f); return nullptr; }
    auto* s = (QssPadShm*)p;
    if (s->magic != QSS_PAD_MAGIC || s->version != QSS_PAD_VERSION) {
        if (first) Log("gamepad memory not initialised by the Quest app");
        UnmapViewOfFile(p); CloseHandle(m); CloseHandle(f);
        return nullptr;
    }
    g_shm = s;
    Log("connected to Quest controllers (%s)", path);
    return g_shm;
}

const volatile QssPad* Pad(DWORD i) {
    if (i >= QSS_PAD_MAX) return nullptr;
    QssPadShm* s = Shm();
    if (!s || !s->pads[i].connected) return nullptr;
    return &s->pads[i];
}

DWORD Fill(DWORD i, XINPUT_STATE* st, bool guide) {
    if (!st) return ERROR_BAD_ARGUMENTS;
    const volatile QssPad* p = Pad(i);
    if (!p) return ERROR_DEVICE_NOT_CONNECTED;
    st->dwPacketNumber = p->packet;
    WORD b = p->buttons;
    st->Gamepad.wButtons = guide ? b : (WORD)(b & ~0x0400);
    // While XInputEnable(FALSE) (game lost focus), report neutral input.
    if (!g_enabled) { memset(&st->Gamepad, 0, sizeof st->Gamepad); return ERROR_SUCCESS; }
    st->Gamepad.bLeftTrigger = p->left_trigger;
    st->Gamepad.bRightTrigger = p->right_trigger;
    st->Gamepad.sThumbLX = p->lx;
    st->Gamepad.sThumbLY = p->ly;
    st->Gamepad.sThumbRX = p->rx;
    st->Gamepad.sThumbRY = p->ry;
    return ERROR_SUCCESS;
}

}  // namespace

extern "C" {

DWORD WINAPI QssXInputGetState(DWORD i, XINPUT_STATE* st) { return Fill(i, st, false); }
DWORD WINAPI QssXInputGetStateEx(DWORD i, XINPUT_STATE* st) { return Fill(i, st, true); }

DWORD WINAPI QssXInputSetState(DWORD i, XINPUT_VIBRATION* v) {
    if (!v) return ERROR_BAD_ARGUMENTS;
    const volatile QssPad* cp = Pad(i);
    if (!cp) return ERROR_DEVICE_NOT_CONNECTED;
    auto* p = const_cast<volatile QssPad*>(cp);
    p->rumble_low = g_enabled ? v->wLeftMotorSpeed : 0;
    p->rumble_high = g_enabled ? v->wRightMotorSpeed : 0;
    p->rumble_seq = p->rumble_seq + 1;
    return ERROR_SUCCESS;
}

DWORD WINAPI QssXInputGetCapabilities(DWORD i, DWORD flags, XINPUT_CAPABILITIES* c) {
    if (!c || (flags && flags != XINPUT_FLAG_GAMEPAD)) return ERROR_BAD_ARGUMENTS;
    if (!Pad(i)) return ERROR_DEVICE_NOT_CONNECTED;
    memset(c, 0, sizeof *c);
    c->Type = XINPUT_DEVTYPE_GAMEPAD;
    c->SubType = XINPUT_DEVSUBTYPE_GAMEPAD;
    c->Flags = XINPUT_CAPS_WIRELESS;
    c->Gamepad.wButtons = 0xF3FF;
    c->Gamepad.bLeftTrigger = c->Gamepad.bRightTrigger = 0xFF;
    c->Gamepad.sThumbLX = c->Gamepad.sThumbLY = c->Gamepad.sThumbRX = c->Gamepad.sThumbRY = (SHORT)0xFFC0;
    c->Vibration.wLeftMotorSpeed = c->Vibration.wRightMotorSpeed = 0xFFFF;
    return ERROR_SUCCESS;
}

void WINAPI QssXInputEnable(BOOL enable) {
    g_enabled = enable != FALSE;
    if (!g_enabled && g_shm)
        for (auto& p : g_shm->pads) { p.rumble_low = p.rumble_high = 0; p.rumble_seq++; }
}

DWORD WINAPI QssXInputGetBatteryInformation(DWORD i, BYTE, XINPUT_BATTERY_INFORMATION* b) {
    if (!b) return ERROR_BAD_ARGUMENTS;
    if (!Pad(i)) return ERROR_DEVICE_NOT_CONNECTED;
    b->BatteryType = BATTERY_TYPE_NIMH;
    b->BatteryLevel = BATTERY_LEVEL_FULL;
    return ERROR_SUCCESS;
}

DWORD WINAPI QssXInputGetKeystroke(DWORD i, DWORD, PXINPUT_KEYSTROKE k) {
    if (!k) return ERROR_BAD_ARGUMENTS;
    if (i != XUSER_INDEX_ANY && !Pad(i)) return ERROR_DEVICE_NOT_CONNECTED;
    return ERROR_EMPTY;
}

DWORD WINAPI QssXInputGetDSoundAudioDeviceGuids(DWORD i, GUID* render, GUID* capture) {
    if (!Pad(i)) return ERROR_DEVICE_NOT_CONNECTED;
    if (render) *render = GUID{};
    if (capture) *capture = GUID{};
    return ERROR_SUCCESS;
}

DWORD WINAPI QssXInputGetAudioDeviceIds(DWORD i, LPWSTR render, UINT* renderLen, LPWSTR capture, UINT* captureLen) {
    if (!Pad(i)) return ERROR_DEVICE_NOT_CONNECTED;
    if (render && renderLen && *renderLen) render[0] = 0;
    if (renderLen) *renderLen = 0;
    if (capture && captureLen && *captureLen) capture[0] = 0;
    if (captureLen) *captureLen = 0;
    return ERROR_SUCCESS;
}

DWORD WINAPI QssXInputWaitForGuideButton(DWORD, DWORD, void*) { return ERROR_SUCCESS; }
DWORD WINAPI QssXInputCancelGuideButtonWait(DWORD) { return ERROR_SUCCESS; }
DWORD WINAPI QssXInputPowerOffController(DWORD) { return ERROR_SUCCESS; }

BOOL WINAPI DllMain(HINSTANCE inst, DWORD reason, LPVOID) {
    if (reason == DLL_PROCESS_ATTACH) DisableThreadLibraryCalls(inst);
    return TRUE;
}

}  // extern "C"
