# IPTV

Free online TV channels for Android TV / Google TV. Kotlin, AndroidX Media3
(ExoPlayer) playback, no ads, no account needed.

## Features

- 109 built-in channel entries (Pakistani drama, entertainment, news, sports,
  cricket) in `app/src/main/assets/verified-channels.m3u`
- Media3 ExoPlayer playback: adaptive HLS/DASH, quality selection, playback
  speed, system volume, seek bar on movies
- Channel logos (`tvg-logo`) with a lightweight async loader and cache
- Favorites, recent channels, and resume-last-channel on launch
- M3U import (HTTPS URL or local file), multiple saved playlists with
  auto-refresh, Xtream Codes login (live streams)
- Search, category filters, channel-number entry from the remote, channel
  up/down in fullscreen or while the player has focus
- Sleep timer, buffering indicator, automatic retry with backoff
- YouTube/Dailymotion links play through an embedded WebView fallback

Movies and series use a third-party MovieBox resolver. For series, select a season and episode before choosing a quality. A title being listed does not guarantee that a playable link is available in your region. If an episode fails to load, select it again to retry.

Only use content and playlists you are authorized to access. Contact the relevant provider about availability and permissions. HTTP panels transmit login details without transport encryption. Prefer HTTPS when available. Saved playlist credentials are protected with the device keystore. Clearing app data removes the saved login.

The header shows IPTV upload and download combined. Its daily total uses Pakistan time and waits for an online date check at startup and at a day boundary.

## Build

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and Android SDK platform 35. CI builds the debug APK on every
push (`.github/workflows/android.yml`) and uploads it as the `IPTV-debug`
artifact; on `main` it also builds the R8 release APK (`IPTV.apk`, signed with
the committed debug keystore `app/debug.keystore`; the key password and alias
come from the `RELEASE_KEYSTORE_PASSWORD` / `RELEASE_KEY_ALIAS` /
`RELEASE_KEY_PASSWORD` secrets) and publishes that as the
GitHub release. The in-app updater looks for the `IPTV.apk` asset.

`build-apk.sh` is a thin wrapper around `./gradlew assembleDebug`, kept so the
old build command still works.

## Notes

- Media3 is pinned to 1.9.2: it is the newest stable that still builds against
  `compileSdk` 35 (1.11.x requires `compileSdk` 36).
- HLS and DASH need their own Media3 modules (`media3-exoplayer-hls`,
  `media3-exoplayer-dash`): the core `media3-exoplayer` artifact does not
  include them, and a `.m3u8` URL crashes the app instantly without the HLS
  module.
- Picture-in-picture is intentionally not included: on TV, PiP needs Android 14
  or higher, and Google's TV app quality guidelines do not allow media playback
  in PiP mode.
- Xtream credentials are stored in the app-private playlist metadata file, in
  the clear, like the stream URLs themselves.
- Signing: every build — the debug APK and the R8 release APK alike — uses
  the fixed debug key in `app/debug.keystore` (PKCS12, alias `androiddebugkey`,
  password `android`), wired in `app/build.gradle.kts`, so updates install
  cleanly over older builds. Reusing the debug key for the release build is a
  deliberate choice (a separate release key would need an uninstall first);
  never replace the key without expecting an uninstall: Android refuses to
  update an app whose signature changed.
