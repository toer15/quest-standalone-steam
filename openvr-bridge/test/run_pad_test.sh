#!/usr/bin/env bash
# End-to-end test of the controller bridge under Wine: a fake Quest app publishes a controller,
# a tiny XInput "game" (pad_test.exe) reads it through our xinput DLL and rumbles it.
# Usage: run_pad_test.sh path/to/xinput64.dll
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
dll="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
work="$(mktemp -d)"
gcc -O2 -I"$root/include" "$here/fake_pad.c" -o "$work/fake_pad"
x86_64-w64-mingw32-g++ -std=c++17 -O1 "$here/pad_test.cpp" -o "$work/pad_test.exe" -static
for n in xinput1_4 xinput1_3 xinput9_1_0; do cp "$dll" "$work/$n.dll"; done
wine=$(command -v wine64 || echo /usr/lib/wine/wine64)
export WINEPREFIX="$work/prefix" WINEDEBUG=-all QSS_PAD_SHM="$work/gamepad.shm"
export WINEDLLOVERRIDES="xinput1_4,xinput1_3,xinput9_1_0=n,b"
trap 'wineserver -k 2>/dev/null || true' EXIT
timeout 180 "$wine" wineboot -i >/dev/null 2>&1 || true
"$work/fake_pad" "$QSS_PAD_SHM" > "$work/fake_pad.log" 2>&1 &
fp=$!
sleep 0.5
cd "$work"
set +e
timeout 120 "$wine" pad_test.exe
game=$?
wait $fp
pad=$?
set -e
cat "$work/fake_pad.log"
if [ $game -ne 0 ] || [ $pad -ne 0 ]; then echo "controller bridge test FAILED (game=$game pad=$pad)"; exit 1; fi
echo "controller bridge test passed"
