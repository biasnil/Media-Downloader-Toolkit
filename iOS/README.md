# Media Downloader (iOS)

SwiftUI version of the Media Downloader Toolkit for iPhone (iOS 18+).
- YouTube tab: YouTubeKit finds the streams; AVFoundation merges/saves them; SwiftLAME makes MP3s.
- Other Sites tab: Instagram & Facebook (one-time login), Reddit (login optional), X, Streamable,
  Imgur, Internet Archive, direct media links, and pages with a video tag (Tumblr etc.).
No yt-dlp or FFmpeg — iPhone apps can't run them.

## Files (all go in the app target, next to Assets.xcassets)
- MediaDownloaderApp.swift  — app entry + theme
- ContentView.swift         — red header, gear, dark switch, YouTube / Other Sites tabs
- YouTubeView.swift         — YouTube tab screen
- DownloadManager.swift     — YouTube download loop, log, playlist prompt, notifications
- YouTubeService.swift      — YouTube streams/playlists, chunked download, merge, MP3 encoding
- PlaylistSheet.swift       — tick-which-videos window
- OtherSitesView.swift      — Other Sites tab screen
- OtherSitesManager.swift   — Other Sites download loop
- SiteExtractors.swift      — site list, shared page helpers, Reddit and X extractors
- InstagramSupport.swift    — Instagram extractor
- MoreSites.swift           — Facebook, Streamable, Imgur, Internet Archive, direct links, any-page fallback
- SiteLogins.swift          — login sites list, login screen, saved sessions, Settings → Accounts
- SettingsView.swift        — settings (thumbnail, Photos, notifications, Instagram login, theme)
- Theme.swift               — colors (same as desktop/Android)

## Xcode setup (fresh project)
1. New Project → iOS → App. Product Name: MediaDownloader, Organization Identifier: com.nil,
   Interface: SwiftUI, Language: Swift, Storage: None.
2. Target → General → Minimum Deployments: iOS 18.0.
3. Target → Signing & Capabilities → Team: your Apple ID; Bundle Identifier: com.nil.mediadownloader
   (add a suffix if Xcode says it's taken).
4. Packages (File → Add Package Dependencies…), both added to the MediaDownloader target:
   - YouTubeKit: https://github.com/alexeichhorn/YouTubeKit (Up to Next Major Version)
   - SwiftLAME: download https://github.com/hidden-spectrum/SwiftLAME as a ZIP, keep it in a
     permanent folder (e.g. Documents/MediaDownloader/Packages) and use "Add Local…".
     (Remote SwiftLAME fails with an "unsafe build flags" error.)
5. Delete the generated ContentView.swift and MediaDownloaderApp.swift, then drag in all
   14 .swift files (tick "Copy items if needed").
6. Target → Info tab → add:
   - Application supports iTunes file sharing (Boolean) = YES
   - Supports opening documents in place (Boolean) = YES
   - Privacy - Photo Library Additions Usage Description (String) = "Saves downloaded videos to Photos."
7. Assets → AppIcon must be an "iOS App Icon" set (not an Image Set); drop AppIcon-1024.png in.
   AccentColor: set to #FF0000.
8. iPhone: Settings → Privacy & Security → Developer Mode → On. Plug in, pick the iPhone, Run.
   First time: Settings → General → VPN & Device Management → trust your Apple ID.

With a free Apple ID the app stops opening after 7 days — press Run in Xcode again.

## Notes
- Files appear in Files → On My iPhone → Media Downloader.
- Logins: Settings → Accounts → pick the site → Log in (once per site).
- Instagram: videos only; stories not supported.
- Reddit/X: public posts only.
- YouTube playlists: first ~100 videos.
- If YouTube downloads start failing: File → Packages → Update to Latest Package Versions.
