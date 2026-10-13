# Getting your games' Windows files

The app does **not** handle your Steam password. Download depots on a PC (or in Termux) with your own account, then copy them to the headset.

## Option A: SteamCMD
```
steamcmd +@sSteamCmdForcePlatformType windows +login YOUR_USER +force_install_dir ./game_APPID +app_update APPID validate +quit
```

## Option B: DepotDownloader
```
DepotDownloader -app APPID -os windows -osarch 64 -username YOUR_USER -remember-password -dir ./game_APPID
```

## Copy to the Quest
```
adb push ./game_APPID /sdcard/Download/game_APPID
adb shell run-as com.toer15.queststeam mkdir -p files/runtime/games/APPID
adb shell run-as com.toer15.queststeam cp -r /sdcard/Download/game_APPID/. files/runtime/games/APPID/
```
(`run-as` works on debug builds.)

Notes: many games need the Steam client running (Steamworks DRM / `steam_api64.dll`). That's another TODO: Goldberg-style emulation is *not* included; using the real Steam client inside Wine is the honest route, and is even slower.

Roadmap idea: integrate [JavaSteam](https://github.com/Longi94/JavaSteam) to download depots in-app with the user logging in directly to Steam's CM servers (credentials never stored by us).
