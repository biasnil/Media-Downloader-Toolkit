# Media Downloader (Android)

Kotlin / Jetpack Compose version of the Media Downloader Toolkit for Android 11+ (API 30). Downloads run on the
bundled yt-dlp, Python and FFmpeg from [youtubedl-android](https://github.com/JunkFood02/youtubedl-android).

## Features

- **YouTube:** MP3 / M4A / WAV / MP4 with a quality picker, playlists with a tick-the-videos screen, skip files that already exist
- **Other Sites:** anything yt-dlp supports (Instagram, X, Reddit, TikTok, Facebook...), with a grid picker for posts holding several items. Links with no video offer their pictures instead (Instagram, Facebook and other pages, public posts only)
- **Spotify:** each song is matched to its closest YouTube upload (Spotify audio is copy-protected). Playlist and album links list their songs; Exportify CSV/ZIP import for private or long playlists. Files are tagged with Spotify's title and artist
- **Utility:** Normalizer, Converter, Cover (add / remove / save), Tag editor, Trim & fade, Speed & pitch (slowed + reverb, nightcore), Effects (bass boost, volume, remove silence)
- Several downloads at once, a speed limit, background downloads with a progress notification
- **Share sheet:** share a link to Media Downloader to start it. **Clipboard:** copied YouTube/Spotify links are offered when the app opens
- History with retry, open, share and delete
- English, French, Simplified Chinese and Malay (Settings → Language)
- Update check: Settings → About → Version (only when tapped)
- Terms & Conditions, Privacy, Acknowledgements and Report a Problem (email or GitHub)

## Build

1. Open this `android` folder in Android Studio.
2. Let Gradle sync, then **Build → Build APK(s)**.
3. Test on a real phone or an API 34 (4 KB page size) emulator. The bundled FFmpeg can't load on 16 KB page-size
   images (the newer API 36/37 emulators).

## Releasing an update

1. In `app/build.gradle.kts`, raise `versionName` (e.g. `1.0.1`) **and** `versionCode` (2, 3, ...). Android won't
   install an update with the same `versionCode`.
2. Build the release APK, signed with the **same keystore** as before. Keep the `.jks` and its passwords safe:
   without them, phones can't update the app.
3. Create a GitHub release tagged **`Androidv1.0.1`** and attach the APK.

The app's update check only looks at tags starting with `Androidv`, so the Windows releases (`1.1.0` etc.) are ignored.

## Translations

Text lives in `app/src/main/res/values*/strings.xml`: `values` (English), `values-fr`, `values-b+zh+Hans` and
`values-ms`. A string missing from a language falls back to English.
