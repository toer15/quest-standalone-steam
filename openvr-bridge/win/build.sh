#!/usr/bin/env bash
# Builds the Windows-side bridge: openvr_api.dll (x86_64) with mingw-w64.
#   apt install mingw-w64   (Ubuntu)
# Output: $1 (default: openvr-bridge/build/openvr_api.dll)
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(cd "$here/.." && pwd)"
out="${1:-$root/build/openvr_api.dll}"
mkdir -p "$(dirname "$out")" "$root/build/gen"
python3 "$here/gen_fntables.py" "$root/third_party/openvr/openvr.h" "$root/build/gen/fntables.inc"
x86_64-w64-mingw32-g++ -std=c++17 -O2 -shared -static -static-libgcc -static-libstdc++ \
  -Wall -Wno-unused-function -Wno-cast-function-type \
  -I"$root/include" -I"$root/third_party/openvr" -I"$root/build/gen" \
  "$here/api.cpp" "$here/shm.cpp" "$here/system.cpp" "$here/compositor.cpp" "$here/input.cpp" \
  -o "$out" -ld3d11 -Wl,--kill-at
x86_64-w64-mingw32-strip "$out"
echo "built $out ($(stat -c %s "$out") bytes)"
