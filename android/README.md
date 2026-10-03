# Media Downloader (Android)

Kotlin / Jetpack Compose port of the Media Downloader Toolkit's YouTube and Other Sites tabs.

## What's in this zip
Everything written for the app, laid out at its real project path:

- `app/src/main/java/com/nil/mediadownloader/` — all 9 Kotlin source files
- `app/src/main/AndroidManifest.xml` — permissions + DownloadService
- `app/build.gradle.kts` — ABI filters, legacy JNI packaging, dependencies
- `gradle/libs.versions.toml.additions` — the version-catalog lines to merge into your own libs.versions.toml

Not included (Android Studio generates these, and yours already work): the Gradle
wrapper, root build files, `res/` (icons, themes, strings, backup rules), and the
rest of `libs.versions.toml`.

## Starting from a fresh project
1. New Project → Empty Activity, package `com.nil.mediadownloader`, minimum SDK API 30.
2. Copy this zip's `app/` folder over the new project's `app/` folder.
3. Merge `gradle/libs.versions.toml.additions` into `gradle/libs.versions.toml`.
4. Sync Gradle, then Build → Rebuild Project.

## Notes
- Test on a real phone or an API 34 (4 KB page) emulator. The bundled FFmpeg
  can't load on 16 KB page-size images (the newer API 36/37 emulators).
- Keep your signing keystore (.jks) and its passwords safe — updates must be
  signed with the same key.
