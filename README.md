# Quest Standalone Steam (experiment)

A fun experiment: can a Meta Quest 3 run your Steam (Windows) games **standalone**, no PC?

1. Log in with Steam (OpenID in a WebView) and list your library, VR titles highlighted.
2. Get the games' Windows depots (SteamCMD / DepotDownloader with *your* credentials — see [docs/DEPOTS.md](docs/DEPOTS.md)).
3. Try to run them on the headset with **Box64 + Wine + DXVK** (like [Winlator](https://github.com/brunodev85/winlator)), with an **OpenVR → OpenXR bridge** (idea from OpenComposite) so VR games could reach the Quest's runtime.

## Download
Grab `quest-standalone-steam.apk` from [Releases](https://github.com/toer15/quest-standalone-steam/releases) (built by GitHub Actions). One universal arm64 APK installs on **Android phones (Android 10+)** and **Meta Quest** (appears in Library → Unknown Sources). CI builds have no Steam API key unless you add a repo secret `STEAM_API_KEY` (Settings → Secrets → Actions); otherwise build locally with your key.

| | Phone | Quest 3 |
|---|---|---|
| Steam login + library | yes | yes (2D panel) |
| Runtime installer, import game, run .exe | yes (flat, via Termux:X11) | yes (flat 2D panel via Termux:X11); VR bridge TODO |

## Runtime (v0.2.0): Box64 + Wine on the device
Open **Library → Runtime & launcher**:
1. **Install runtime**: downloads the official [Winlator v11.2.0 APK](https://github.com/brunodev85/winlator/releases/tag/v11.2.0) (~157 MB, resumable) and extracts from its assets: `rootfs.tzst` (arm64 glibc userland + x86_64 Wine in `/opt/wine`), `box64/box64-0.4.4.tzst`, `container_pattern.tzst` (ready Wine prefix) and `dxwrapper/dxvk-2.4.1.tzst`, into app-private storage, with a progress bar.
2. **Smoke test**: `box64 wine cmd /c ver`, no display needed.
3. **Import game folder / .exe** with the Android file picker. Files are copied into `C:\games\` in the prefix.
4. **Run**: pick an .exe; stdout and stderr from Box64/Wine stream into the in-app log.

**How it works**: Winlator's binaries hardcode `/data/data/com.winlator/files/rootfs`. This app's id is `com.qssteam`, so `/data/data/com.qssteam//` is exactly the same length, and the installer byte-patches every file and symlink as it extracts. That keeps ELF interpreter and rpath strings valid. `targetSdk` is 28 because newer targets block `exec()` of files in app data (Winlator and Termux do the same).
**The app id changed from v0.1.0** (`com.toer15.queststeam` → `com.qssteam`), so uninstall the old one first.

### Display setup (Termux:X11)
There's no embedded X server yet. Use [Termux](https://github.com/termux/termux-app/releases) + [Termux:X11](https://github.com/termux/termux-x11/releases/tag/nightly) (`termux-x11-universal-debug.apk`):
```
pkg install x11-repo && pkg install termux-x11-nightly
termux-x11 :0 -listen tcp -ac &
```
Keep the launcher's display field at `127.0.0.1:0`. When you run a game the app brings Termux:X11 to the front. On Quest, both apps show as 2D panels.

### Verified vs untested
- **Verified**: the APK compiles (CI + local) and installs as one arm64 package. The Winlator asset names and URLs exist in v11.2.0. Every hardcoded path in those assets is `/data/data/com.winlator/files/rootfs/...`, so the same-length patch covers them all.
- **Untested on hardware**: the download/extract flow, Box64 execution under targetSdk 28 on a phone and on Quest (Horizon OS), the Wine prefix booting, the Termux:X11 TCP display, and every game. Winlator itself starts Wine via extra pieces we don't have yet (its own X server, sysvshm server, ALSA/pulse bridges, a Vulkan driver like Turnip), so expect failures. The log view is there to debug them.
- Graphics: no GPU driver is wired up yet, so only simple GDI/2D programs have a chance. DXVK is extracted but not installed into the prefix.

### Sources / licensing
- Winlator by brunodev85, LGPL-2.1: https://github.com/brunodev85/winlator. Its assets are downloaded at runtime from the official release, not redistributed here.
- Box64 (MIT): https://github.com/ptitSeb/box64 · Wine (LGPL-2.1): https://www.winehq.org · DXVK (zlib): https://github.com/doitsujin/dxvk
- Termux:X11 (GPL-3.0): https://github.com/termux/termux-x11 · zstd-jni (BSD), Apache Commons Compress (Apache-2.0)

## Honest limitations
- SteamVR games are x86_64 Windows binaries built for desktop CPUs/GPUs. The Quest 3 is an ARM Android device (Snapdragon XR2 Gen 2). Every instruction is translated (Box64) and every Windows/D3D call is translated (Wine/DXVK). **VR games are very unlikely to run well, and most won't run at all.**
- No SteamVR on Android, so the bridge must reimplement OpenVR — a large job.
- DRM / Steam client dependency, anti-cheat, and memory (8 GB shared) are further blockers.
- Small, old, flat 2D games have the best chance. Speed is not a goal here.

## Architecture
```
app/            Kotlin UI: LoginActivity (Steam OpenID), LibraryActivity (GetOwnedGames + VR tagging)
launcher/       GameLauncher: builds the box64 -> wine64 -> game.exe process (TODOs for runtime/prefix/X server/VR)
openvr-bridge/  C++ stub of openvr_api exports to be backed by OpenXR
docs/           Depot download + copy instructions
```

## Setup
1. Install Android Studio (JDK 17, Android SDK 34, NDK for the bridge later).
2. `cp local.properties.example local.properties` and set `STEAM_API_KEY` (from https://steamcommunity.com/dev/apikey). It's git-ignored — never commit it. Your Steam profile's game details must be public for GetOwnedGames.
3. Open in Android Studio, or run `gradle wrapper --gradle-version 8.7` once (the binary wrapper jar isn't committed) then `./gradlew :app:assembleDebug`.

## Sideload to Quest 3
- Enable Developer Mode (Meta Horizon phone app → device → Developer mode).
- USB to PC, accept the prompt in headset.
- `adb install -r app/build/outputs/apk/debug/app-debug.apk` — or drag the APK into **SideQuest**.
- Find it under *Library → Unknown Sources*.

## Roadmap
- [x] Steam login + library with VR highlighting
- [x] Runtime installer (Winlator v11.2.0 assets), game import, run .exe with in-app logs (untested on device)
- [ ] Embedded X server (port Winlator's Java XServer) instead of Termux:X11
- [ ] sysvshm/ALSA helpers, Turnip Vulkan driver + DXVK install into prefix
- [ ] Run a simple 2D Windows game end-to-end (milestone 1!)
- [ ] In-app depot download via JavaSteam
- [ ] openvr-bridge: mingw DLL + Wine unixlib thunk + OpenXR session
- [ ] Head pose → IVRSystem, Touch controllers → IVRInput
- [ ] DXVK VkImage → XrSwapchain submission
- [ ] Try a tiny VR title

## Disclaimer
Personal experiment. Only run games you own. Not affiliated with Valve or Meta.
