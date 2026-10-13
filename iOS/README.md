# Media Downloader (iOS)

SwiftUI version of the Media Downloader Toolkit for iPhone (iOS 18+). Personal use only: it isn't on the App Store.

iPhone apps can't run yt-dlp or FFmpeg, so instead:
- **YouTube:** YouTubeKit finds the streams, the app downloads them in chunks, AVFoundation merges them (no re-encoding), and SwiftLAME makes MP3s.
- **Other Sites:** small built-in readers for Instagram and Facebook (videos and photos, one-time login), Reddit (videos, photos and galleries, login optional), X, Streamable, Imgur, Internet Archive, direct media links, and pages with a video tag.

Files are saved to **Files → On My iPhone → Media Downloader**.

## Features

- YouTube MP3 / M4A / MP4 with a quality picker, plus playlists with a tick-the-videos screen
- Several downloads at once, a speed limit, and skipping files that already exist
- Picker for posts with several photos or videos (Instagram carousels, Facebook albums, Reddit galleries, X)
- **Share sheet:** share a link from any app to Media Downloader to start the download
- **Clipboard:** copy a YouTube link, open the app, and it asks whether to download it
- Live Activity with progress on the Lock Screen and in the Dynamic Island, plus a notification when downloads finish
- History with retry, delete file and share
- Option to save videos and photos to the Photos app too
- English, French, Simplified Chinese and Malay
- Terms & Conditions (asked again every 10 days), a Privacy page and Report a Problem (email or GitHub)
- Privacy: no servers, no accounts, no analytics. Logins stay on the iPhone and the app never sees your password.

## Project layout

```
iOS/
├── MediaDownloader.xcodeproj
├── MediaDownloader/         App: screens, download logic, site readers
│   ├── AppText.swift        Every sentence the app shows (English)
│   ├── Localizable.xcstrings  Translations (fr, zh-Hans, ms), edit in Xcode
│   └── PrivacyInfo.xcprivacy  Apple privacy manifest
├── ShareExtension/          "Media Downloader" in the Share sheet
├── DownloadWidgets/         Live Activity (Lock Screen + Dynamic Island)
└── AppIcon-1024.png
```

Xcode uses synchronized folders, so any file dropped into one of these folders is part of that target.

## Build

1. Put the two Swift packages in a folder named `Package` **next to** the `iOS` folder:
   ```
   Package/SwiftLAME-0.1.0     https://github.com/hidden-spectrum/SwiftLAME  (download as ZIP)
   Package/YouTubeKit-0.4.9    https://github.com/alexeichhorn/YouTubeKit   (download as ZIP)
   iOS/MediaDownloader.xcodeproj
   ```
   SwiftLAME has to be a local package, because the remote one fails with an "unsafe build flags" error.
2. Open `iOS/MediaDownloader.xcodeproj` in Xcode 26 or later.
3. For each of the three targets (MediaDownloader, ShareExtension, DownloadWidgetsExtension), go to **Signing & Capabilities**, set **Team** to your Apple ID, and change the bundle ID if Xcode says it's taken.
4. Pick your iPhone and run the **MediaDownloader** scheme. The DownloadWidgetsExtension scheme gives a harmless SpringBoard error.
5. The first time, turn on **Settings → Privacy & Security → Developer Mode** on the iPhone, then trust your Apple ID under **Settings → General → VPN & Device Management**.

With a free Apple ID the app stops opening after 7 days. Run it from Xcode again to renew.

## Notes

- All three targets must have the same Minimum Deployments (iOS 18.0). An extension set to a newer iOS version than the phone silently doesn't install.
- Info.plist files must not be target members ("Multiple commands produce …/Info.plist").
- To get Lock Screen progress, turn on Live Activities under Settings → Media Downloader on the iPhone.
- If YouTube downloads start failing, update YouTubeKit to the latest version.
- Not possible on iPhone: subtitles, TikTok, and any site that would need yt-dlp.
