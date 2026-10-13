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
| Launch | flat mode, runtime TODO | flat panel; VR bridge TODO |

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
- [ ] Runtime installer: download rootfs + Box64 + Wine + DXVK + Turnip (reuse Winlator's imagefs)
- [ ] Embedded X server view so Wine windows render in a Quest panel
- [ ] Run a simple 2D Windows game end-to-end (milestone 1!)
- [ ] In-app depot download via JavaSteam
- [ ] openvr-bridge: mingw DLL + Wine unixlib thunk + OpenXR session
- [ ] Head pose → IVRSystem, Touch controllers → IVRInput
- [ ] DXVK VkImage → XrSwapchain submission
- [ ] Try a tiny VR title

## Disclaimer
Personal experiment. Only run games you own. Not affiliated with Valve or Meta.
