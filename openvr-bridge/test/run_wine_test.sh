#!/usr/bin/env bash
# End-to-end test of the Windows bridge under Wine: a fake Quest (Linux) publishes poses and
# checks frames; a tiny D3D11 "SteamVR game" (vr_test.exe) loads the bridge DLL and plays 70 frames.
# Usage: run_wine_test.sh path/to/openvr_api.dll
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
dll="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
work="$(mktemp -d)"
gcc -O2 -I"$root/include" "$here/fake_quest.c" -o "$work/fake_quest"
x86_64-w64-mingw32-g++ -std=c++17 -O1 -I"$root/include" -I"$root/third_party/openvr" "$here/vr_test.cpp" -o "$work/vr_test.exe" -ld3d11 -static
cp "$dll" "$work/openvr_api.dll"
wine=$(command -v wine64 || echo /usr/lib/wine/wine64)
export WINEPREFIX="$work/prefix" WINEDEBUG=-all DISPLAY=:97 QSS_VR_SHM="$work/vrbridge.shm"
Xvfb :97 -screen 0 1024x768x24 >/dev/null 2>&1 &
xvfb=$!
trap 'kill $xvfb 2>/dev/null || true; wineserver -k 2>/dev/null || true' EXIT
sleep 1
timeout 180 "$wine" wineboot -i >/dev/null 2>&1 || true
"$work/fake_quest" "$QSS_VR_SHM" 30 > "$work/fake_quest.log" 2>&1 &
fq=$!
sleep 0.5
cd "$work"
set +e
timeout 180 "$wine" vr_test.exe
game=$?
wait $fq
quest=$?
set -e
cat "$work/fake_quest.log"
if [ $game -ne 0 ] || [ $quest -ne 0 ]; then echo "bridge test FAILED (game=$game quest=$quest)"; exit 1; fi
echo "bridge test passed"
