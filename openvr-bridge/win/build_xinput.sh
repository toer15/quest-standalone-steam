#!/usr/bin/env bash
# Builds the controller bridge: XInput DLLs (64-bit for system32, 32-bit for syswow64) with mingw-w64.
# Output: $1/xinput64.dll and $1/xinput32.dll (default: openvr-bridge/build)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
out="${1:-$root/build}"
mkdir -p "$out"
for arch in x86_64:64 i686:32; do
  cc="${arch%%:*}-w64-mingw32"
  dll="$out/xinput${arch##*:}.dll"
  "$cc-g++" -std=c++17 -O2 -shared -static -static-libgcc -static-libstdc++ -Wall \
    -I"$root/include" "$here/xinput.cpp" "$here/xinput.def" -o "$dll" -Wl,--enable-stdcall-fixup -Wl,--kill-at
  "$cc-strip" "$dll"
  echo "built $dll ($(stat -c %s "$dll") bytes)"
done
