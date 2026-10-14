// A tiny "game" that uses XInput like real games do (import by name and ordinal 100) and checks
// it sees the fake controller, then rumbles it.
#include <windows.h>
#include <xinput.h>
#include <cstdio>

typedef DWORD(WINAPI* GetStateFn)(DWORD, XINPUT_STATE*);
typedef DWORD(WINAPI* SetStateFn)(DWORD, XINPUT_VIBRATION*);
typedef DWORD(WINAPI* CapsFn)(DWORD, DWORD, XINPUT_CAPABILITIES*);

int main() {
    int fails = 0;
    const char* names[] = {"xinput1_4.dll", "xinput1_3.dll", "xinput9_1_0.dll"};
    for (const char* name : names) {
        HMODULE m = LoadLibraryA(name);
        if (!m) { printf("FAIL load %s (%lu)\n", name, GetLastError()); fails++; continue; }
        auto get = (GetStateFn)GetProcAddress(m, "XInputGetState");
        auto getEx = (GetStateFn)GetProcAddress(m, (LPCSTR)100);
        auto set = (SetStateFn)GetProcAddress(m, "XInputSetState");
        auto caps = (CapsFn)GetProcAddress(m, "XInputGetCapabilities");
        if (!get || !getEx || !set || !caps) { printf("FAIL exports in %s\n", name); fails++; continue; }
        XINPUT_STATE st{};
        DWORD r = ERROR_DEVICE_NOT_CONNECTED;
        for (int i = 0; i < 100 && r != ERROR_SUCCESS; i++) { r = get(0, &st); if (r) Sleep(100); }
        if (r != ERROR_SUCCESS) { printf("FAIL %s: pad 0 not connected (%lu)\n", name, r); fails++; continue; }
        const auto& g = st.Gamepad;
        bool ok = g.wButtons == (XINPUT_GAMEPAD_A | XINPUT_GAMEPAD_DPAD_UP) && g.bRightTrigger == 200 &&
                  g.sThumbLX == 12345 && g.sThumbLY == -32768 && g.sThumbRX == 32767 && st.dwPacketNumber == 1;
        printf("%s %s: buttons %04x RT %u LX %d LY %d RX %d\n", ok ? "ok  " : "FAIL", name, g.wButtons, g.bRightTrigger,
               g.sThumbLX, g.sThumbLY, g.sThumbRX);
        if (!ok) fails++;
        if (get(1, &st) != ERROR_DEVICE_NOT_CONNECTED) { printf("FAIL pad 1 should be disconnected\n"); fails++; }
        if (getEx(0, &st) != ERROR_SUCCESS) { printf("FAIL ordinal 100\n"); fails++; }
        XINPUT_CAPABILITIES c{};
        if (caps(0, XINPUT_FLAG_GAMEPAD, &c) != ERROR_SUCCESS || c.Type != XINPUT_DEVTYPE_GAMEPAD) { printf("FAIL caps\n"); fails++; }
        XINPUT_VIBRATION v{0x8000, 0x4000};
        if (set(0, &v) != ERROR_SUCCESS) { printf("FAIL rumble\n"); fails++; }
    }
    printf(fails ? "pad test FAILED (%d)\n" : "pad test: all good\n", fails);
    return fails ? 1 : 0;
}
