# Quest Standalone Steam (experiment)

A fun experiment: can a Meta Quest 3 run your Steam (Windows) games **standalone**, no PC?

1. Log in with Steam (OpenID in a WebView) and list your library, VR titles highlighted.
2. Get the games' Windows depots (SteamCMD / DepotDownloader with *your* credentials — see [docs/DEPOTS.md](docs/DEPOTS.md)).
3. Try to run them on the headset with **Box64 + Wine + DXVK** (like [Winlator](https://github.com/brunodev85/winlator)), with an **OpenVR → OpenXR bridge** (idea from OpenComposite) so VR games could reach the Quest's runtime.

## Download: two apps
| Device | Download |
|---|---|
| **Meta Quest 3** (also 3S / 2 / Pro) | [quest-steam-quest3.apk](https://github.com/toer15/quest-standalone-steam/releases/download/v0.5.1/quest-steam-quest3.apk) |
| **Android phone** (Android 10+) | [quest-steam-phone.apk](https://github.com/toer15/quest-standalone-steam/releases/download/v0.5.1/quest-steam-phone.apk) |

Both are built by GitHub Actions from the same code ([Releases](https://github.com/toer15/quest-standalone-steam/releases)). They share the app id `com.qssteam`, which the Wine runtime needs, so install one per device.
- **Quest 3 version**: a large landscape 2D panel (1400×860 dp). Cards, text, tabs and buttons are bigger so controllers and hand pointers hit them easily. Declares head tracking and hand tracking (optional) and the Quest device list. The QR sign-in opens first, because you can scan it with your phone while wearing the headset.
- **Phone version**: a compact touch layout with smaller cards (2+ per row), tighter spacing and the password sign-in first. It has no VR manifest entries.

On Quest: sideload with SideQuest or `adb install`, then open it from Library → Unknown Sources.

## v0.5.0: SteamVR games in the headset (Quest 3 app)
On a Quest, **Play in VR** on a downloaded SteamVR game:
1. Replaces the game's `openvr_api.dll` with the bridge (originals are kept as `*.qss-orig`).
2. Starts the game with Box64 + Wine, using **Turnip** (Mesa Vulkan for the Quest 3's Adreno 740) and **DXVK** for Direct3D 11.
3. Switches the headset to the immersive view (OpenXR, Meta's runtime). Your head and Touch controllers drive the game. The game's frames show in the headset, and the runtime's timewarp corrects for latency.

Dark blue in the headset means the VR view is waiting for the game. Teal means the game connected but hasn't sent a frame yet. **Runtime & settings → Enter VR view** reopens it.
Details and limits: [openvr-bridge/README.md](openvr-bridge/README.md). First-version limits:
- 64-bit D3D11 games only.
- Frames are copied through the CPU, so expect low frame rates.
- Games still need Termux:X11 for their window.
- Games that need the Steam client (Steamworks DRM) won't start.

**Tested**: the Windows half under Wine in CI. A test D3D11 VR game gets poses and controller input, and its frames arrive in shared memory pixel-perfect. **Untested on hardware**: the headset half (OpenXR activity), Turnip + DXVK on Quest, and real games.

## v0.4.0: Steam sign-in, auto-sync, real downloads
1. **Sign in to Steam in the app** (Steam client protocol via [JavaSteam](https://github.com/Longi94/JavaSteam)):
   - **Account & password**, with Steam Guard: approve in the Steam phone app, or type the code (authenticator or email).
   - **QR code**: in the Steam phone app, open Steam Guard → scan the QR code. No password is typed on the device.
   - The password goes RSA-encrypted straight to Steam and is never stored. The app keeps only Steam's refresh token in private storage, like the Steam client's "Remember me". Sign out deletes it.
2. **Your games sync automatically** right after sign-in, from Steam's servers (`Player.GetOwnedGames`, with a licenses + PICS fallback). That includes private profiles. The list is saved, so it shows instantly on the next launch and then refreshes.
3. **Download** on a flipped card fetches the game's Windows depots (64-bit, English) from Steam's CDN with JavaSteam's DepotDownloader. The card shows the phase, `done / total` and time left. When it finishes the card shows **Play Game**, and the front shows **Installed**. If a download fails or stalls (3 minutes with no data), tap **Retry download**: files already on disk are checked and reused.
4. **Play Game** starts the game's largest `.exe` with Box64 + Wine. On the first Play the runtime is installed automatically (~157 MB).
5. **Uninstall** (under Play Game) deletes the game's files.

The old website sign-in (OpenID) is still there: "Library only? Sign in on Steam's website". It syncs the library but can't download. The profile menu then offers **Sign in to Steam (sync + downloads)**.

**Untested on hardware**: every Steam network call above. This environment can't reach Steam's servers. The pure-JVM parts (`steamclient/`) compile against JavaSteam 1.8.0 and have unit tests. The Android builds compile in CI.

## Steam library without a built-in key (v0.3.2)
The public APK ships with **no** Steam Web API key. Each player signs in with their own Steam account. A private profile still loads for its owner.
- After OpenID, the login WebView opens `https://steamcommunity.com/my/games/?tab=all` so the `steamcommunity.com` session stays in the app.
- **Profile name + avatar** come from `steamcommunity.com/profiles/<id>/?xml=1`, sent with that session.
- **Owned games** come from your own games page with those cookies. The page parser is unit-tested. The signed-in request is **untested on hardware**.
- If the session cookies are missing or Steam rejects them, the app says **Sign out and sign in again**.
- **Fallback**: in the profile menu, choose *Steam API key (optional)…* and paste your own key from https://steamcommunity.com/dev/apikey. It's stored only on your device.
- Builders can still put `STEAM_API_KEY` in `local.properties` for personal builds. Never commit it.

## Look (v0.3.2)
Dark gradient backdrop, glassy top bar with a sliding tab indicator, a Home hero of your most-played game, larger flip cards with press and hover scale, shimmer while art loads, pill category chips, and a progress ring on downloads. The same layout fits a phone and a Quest 2D panel.

## Launcher UI (v0.3.0)
Jetpack Compose with a dark, Steam-style theme. It works on phones and as a Quest 2D panel.
- **Top bar**: HOME and GAMES tabs, Sync and Settings buttons, and your Steam avatar from `GetPlayerSummaries`. Tap the avatar for profile, sync, settings or sign out.
- **Cards**: art comes from Steam's CDN (`header.jpg`). Tap a card to flip it in 3D.
  - Back of the card: **Download** (shows progress + time remaining), then **Play Game** + **Uninstall**. The front shows **Installed**.
  - **Import from folder** copies a game folder you picked, with real progress and ETA.
- **Games tab**: category slider with All / VR / Installed plus Steam store genres. **☰ Browse** (or a long-press on a chip) opens an animated popup that grows into a larger panel, with **Search games**, **All games** and an A–Z jump list (A–F, G–L, M–R, S–Z, #).
- **Downloads** use pluggable `DownloadSource`s (`app/.../download/Downloads.kt`):
  - `FolderImportSource` works.
  - `SteamDepotSource` downloads from Steam (v0.4.0, see above).
- Play opens the runtime screen and launches the game's largest `.exe` via Box64 + Wine, with logs. The runtime installer and smoke test live under Settings.

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

### v0.2.1 install fix
v0.2.0's installer could corrupt the downloaded Winlator APK: tapping Install twice ran two downloads into the same file, and resumed downloads weren't verified. Opening the result failed with `ZipException: invalid LOC header`. Now:
- The download goes to `.part` and is resumed only on HTTP 206 with a matching `Content-Range`. A 200 restarts from zero, and an HTML/text response is rejected.
- The finished file is checked against size `157549075` and SHA-256 `ea5c81bf0e5b90a6b9ccb8007121ab6738c8646a5a60b341ce0ac2b33f1d100a`. On a mismatch it's deleted and downloaded again (up to 3 tries).
- Only one install can run at a time, and the log shows HTTP status, bytes and hash.
- ELF binaries and scripts get the exec bit, because Winlator ships `box64` as 0644.
- `launcher/src/test/.../RuntimeCoreTest.kt` runs the real install logic on a desktop JVM:
  `./gradlew :launcher:testDebugUnitTest -Dwinlator.apk=/path/Winlator_11.2.apk -Dwinlator.download=1`

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
app/            Kotlin/Compose UI. Flavors: quest (Quest 3 panel) and phone. LoginActivity (Steam client + OpenID fallback)
steamclient/    Pure-JVM Steam client: SteamSession (QR/password + Steam Guard, owned games), SteamDownloader (DepotDownloader)
launcher/       GameLauncher: builds the box64 -> wine64 -> game.exe process (TODOs for runtime/prefix/X server/VR)
openvr-bridge/  SteamVR bridge: Windows openvr_api.dll (win/), shared-memory contract (include/), Wine test (test/)
app/src/quest/cpp/  Quest immersive OpenXR view (NativeActivity, libqssxr.so)
docs/           Depot download + copy instructions
```

## Setup
1. Install Android Studio (JDK 17, Android SDK 34, NDK for the bridge later).
2. `cp local.properties.example local.properties` and, if you want one, set `STEAM_API_KEY` (from https://steamcommunity.com/dev/apikey). It's git-ignored — never commit it. The library loads from your Steam sign-in session; the key is only an optional fallback.
3. Open in Android Studio, or run `gradle wrapper --gradle-version 8.7` once (the binary wrapper jar isn't committed) then `./gradlew :app:assembleQuestDebug` (Quest 3) or `./gradlew :app:assemblePhoneDebug` (phone). Unit tests: `./gradlew :steamclient:test`.

## Sideload to Quest 3
- Enable Developer Mode (Meta Horizon phone app → device → Developer mode).
- USB to PC, accept the prompt in headset.
- `adb install -r quest-steam-quest3.apk` (or `app/build/outputs/apk/quest/debug/app-quest-debug.apk`), or drag the APK into **SideQuest**.
- Find it under *Library → Unknown Sources*.

## Roadmap
- [x] Steam login + library with VR highlighting
- [x] Runtime installer (Winlator v11.2.0 assets), game import, run .exe with in-app logs (untested on device)
- [ ] Embedded X server (port Winlator's Java XServer) instead of Termux:X11
- [ ] sysvshm/ALSA helpers, Turnip Vulkan driver + DXVK install into prefix
- [ ] Run a simple 2D Windows game end-to-end (milestone 1!)
- [x] In-app Steam sign-in (QR / password + Steam Guard) and auto library sync (v0.4.0, untested on hardware)
- [x] In-app depot download via JavaSteam (v0.4.0, untested on hardware)
- [x] Separate Quest 3 and phone apps
- [ ] Foreground service so downloads continue with the headset asleep
- [x] openvr-bridge: mingw openvr_api.dll + shared memory + OpenXR session (v0.5.0)
- [x] Head pose → IVRSystem, Touch controllers → IVRInput (v0.5.0)
- [x] D3D11 frames → XrSwapchain (CPU copy, v0.5.0)
- [x] Turnip + DXVK in the runtime (v0.5.0)
- [ ] Zero-copy frames (AHardwareBuffer/dma-buf) for speed
- [ ] Older OpenVR interface versions, OpenGL/Vulkan submissions
- [ ] Embedded X server so Termux:X11 isn't needed
- [ ] Try a tiny VR title on hardware

## Disclaimer
Personal experiment. Only run games you own. Not affiliated with Valve or Meta.
