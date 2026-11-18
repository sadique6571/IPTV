# IPTV

Free online TV channels for Android TV / Google TV. Kotlin, AndroidX Media3
(ExoPlayer) playback, no ads, no account needed.

## Features

- 200+ checked built-in channels (Pakistani drama, entertainment, news, sports,
  cricket) in `app/src/main/assets/verified-channels.m3u`
- Media3 ExoPlayer playback: adaptive HLS/DASH, quality selection, playback
  speed, volume, seek bar on seekable streams
- Channel logos (`tvg-logo`) with a lightweight async loader and cache
- Favorites, recent channels, and resume-last-channel on launch
- M3U import (HTTPS URL or local file), multiple saved playlists with
  auto-refresh, Xtream Codes login (live streams)
- Search, category filters, channel-number entry from the remote, channel
  up/down in fullscreen or while the player has focus
- Sleep timer, buffering indicator, automatic retry with backoff
- YouTube/Dailymotion links play through an embedded WebView fallback

No EPG, by design.

## Build

```sh
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and Android SDK platform 35. CI builds the debug APK on every
push to `main` (`.github/workflows/android.yml`) and uploads it as the
`IPTV-debug` artifact.

`build-apk.sh` is a thin wrapper around `./gradlew assembleDebug`, kept so the
old build command still works.

## Notes

- Media3 is pinned to 1.9.2: it is the newest stable that still builds against
  `compileSdk` 35 (1.11.x requires `compileSdk` 36).
- Picture-in-picture is intentionally not included: on TV, PiP needs Android 14
  or higher, and Google's TV app quality guidelines do not allow media playback
  in PiP mode.
- Xtream credentials are stored in the app-private playlist metadata file, in
  the clear, like the stream URLs themselves.
