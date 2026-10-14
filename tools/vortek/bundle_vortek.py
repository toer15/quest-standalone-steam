#!/usr/bin/env python3
"""Bundles Vortek (Winlator 11.2, LGPL-2.1) into the app.

Vortek sends the game's Vulkan calls (guest ICD libvulkan_vortek.so) to the headset's own Vulkan
driver through libvortekrenderer.so in the app. libvortekrenderer.so needs Winlator 11's
libwinlator.so for a few C helpers (ring buffers, arrays, ashmem). The app already has its own
libwinlator.so (Winlator 10's X server), so the 11.2 copy is renamed libwlvortek.so (same length)
and its JNI names are renamed Java_com_winlator -> Java_qss_winlator, so ART can't bind the app's
Java classes to it.

Also copies DXVK 1.10.3 and 2.4.1 (the app installs the one the headset's Vulkan can run).

usage: bundle_vortek.py Winlator_11.2.apk <jniLibs/arm64-v8a dir> <assets dir>
"""
import os
import sys
import zipfile

apk, libdir, assetdir = sys.argv[1:4]
os.makedirs(libdir, exist_ok=True)
os.makedirs(assetdir, exist_ok=True)


def replace(data, old, new, expect):
    assert len(old) == len(new)
    n = data.count(old)
    if n != expect:
        sys.exit(f"expected {expect} x {old!r}, found {n}")
    return data.replace(old, new)


# Adrenotools' hook libraries, loaded by the renderer from the app's native library folder.
HOOKS = ("libhook_impl.so", "libmain_hook.so", "libfile_redirect_hook.so", "libgsl_alloc_hook.so")

with zipfile.ZipFile(apk) as z:
    renderer = z.read("lib/arm64-v8a/libvortekrenderer.so")
    helpers = z.read("lib/arm64-v8a/libwinlator.so")
    hooks = {h: z.read(f"lib/arm64-v8a/{h}") for h in HOOKS}
    driver = z.read("assets/graphics_driver/vortek-2.1.tzst")
    # DXVK 2.x needs Vulkan 1.3; the app picks 1.10.3 when the headset's driver is older.
    dxvk = {v: z.read(f"assets/dxwrapper/dxvk-{v}.tzst") for v in ("1.10.3", "2.4.1")}

renderer = replace(renderer, b"libwinlator.so\0", b"libwlvortek.so\0", 1)
helpers = replace(helpers, b"libwinlator.so\0", b"libwlvortek.so\0", 1)
helpers = replace(helpers, b"Java_com_winlator", b"Java_qss_winlator", helpers.count(b"Java_com_winlator"))
assert b"Java_com_winlator" not in helpers
# Their cache files (/data/data/com.winlator/cache/...) go to this app's cache folder (same length).
renderer = replace(renderer, b"/data/data/com.winlator/", b"/data/data/com.qssteam//", 3)
helpers = replace(helpers, b"/data/data/com.winlator/", b"/data/data/com.qssteam//", 1)

for name, data in [("libvortekrenderer.so", renderer), ("libwlvortek.so", helpers), *hooks.items()]:
    with open(os.path.join(libdir, name), "wb") as f:
        f.write(data)
with open(os.path.join(assetdir, "vortek-2.1.tzst"), "wb") as f:
    f.write(driver)
for v, data in dxvk.items():
    with open(os.path.join(assetdir, f"dxvk-{v}.tzst"), "wb") as f:
        f.write(data)
print(f"vortek: renderer {len(renderer)} B, helpers {len(helpers)} B, guest driver {len(driver)} B")
