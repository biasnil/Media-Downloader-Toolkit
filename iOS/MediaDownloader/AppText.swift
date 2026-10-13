import Foundation

// Every sentence the app shows, in one place (English).
// Translations (French, Chinese, Malay) live in Localizable.xcstrings: open it in Xcode to edit them.
// Changing an English sentence here makes it a new entry there, so translate it again.

nonisolated enum AppText {

    // MARK: - Shared

    nonisolated enum Common {
        static var download: String { String(localized: "Download") }
        static var downloading: String { String(localized: "Downloading...") }
        static var cancel: String { String(localized: "Cancel") }
        static var done: String { String(localized: "Done") }
        static var paste: String { String(localized: "Paste") }
        static var clear: String { String(localized: "Clear") }
        static var log: String { String(localized: "Log") }
        static var openFolder: String { String(localized: "Open Folder") }
        static var skip: String { String(localized: "Skip") }
        static var selectAll: String { String(localized: "Select all") }
        static var selectNone: String { String(localized: "Select none") }
        static var idle: String { String(localized: "Idle.") }
        static var cancelling: String { String(localized: "Cancelling...") }
        static var format: String { String(localized: "Format") }
        static var formatLabel: String { String(localized: "Format:") }
        static var quality: String { String(localized: "Quality") }
        static var qualityLabel: String { String(localized: "Quality:") }
        static var bestAvailable: String { String(localized: "Best available") }
        static var skipExisting: String { String(localized: "Skip files that already exist") }
        static var savesTo: String { String(localized: "Saves to Files → On My iPhone → Media Downloader") }
        static func downloadCount(_ n: Int) -> String { String(localized: "Download (\(n))") }
        static func kbps(_ n: Int) -> String { String(localized: "\(n) kbps") }
    }

    nonisolated enum Formats {
        static var mp3: String { String(localized: "Audio (mp3)") }
        static var m4a: String { String(localized: "Audio (m4a)") }
        static var mp4: String { String(localized: "Video (mp4)") }
    }

    // MARK: - Main screen

    nonisolated enum Main {
        static var otherSitesTab: String { String(localized: "Other Sites") }
        static var dark: String { String(localized: "Dark") }
        static var darkMode: String { String(localized: "Dark mode") }
        static var clipboardQuestion: String { String(localized: "Download this YouTube link?") }
        static var dontAskToday: String { String(localized: "Don't Ask Again Today") }
        static var notNow: String { String(localized: "Not Now") }
    }

    nonisolated enum YouTube {
        static var linksLabel: String { String(localized: "YouTube URL(s), one per line") }
        static var bestAAC: String { String(localized: "Best available (AAC)") }
    }

    nonisolated enum OtherSites {
        static var linksLabel: String { String(localized: "Link(s), one per line") }
        static var sites: String { String(localized: "Sites") }
        static var supportedSites: String { String(localized: "Supported sites") }
        static var placeholder: String { String(localized: "Instagram, Facebook, Reddit, X, Streamable,\nImgur, archive.org, direct .mp4 links...") }
        static var originalAAC: String { String(localized: "Original (AAC)") }
        static var loginInfo: String { String(localized: "Sites marked (login) need a one-time login in Settings → Accounts.") }
        static var supportedList: [String] {
            [
                String(localized: "Instagram: videos & photos (login)"),
                String(localized: "Facebook: videos & photos (login)"),
                String(localized: "Reddit: videos & photos (login optional)"),
                "X / Twitter",
                "Streamable",
                "Imgur",
                "Internet Archive",
                String(localized: "Direct .mp4 / .mov / .mp3 / .m4a links"),
                String(localized: "Tumblr & other pages with a video tag"),
            ]
        }
    }

    // MARK: - Pickers

    nonisolated enum Playlist {
        static var toNumber: String { String(localized: "to #") }
        static var apply: String { String(localized: "Apply") }
        static func count(_ n: Int) -> String { String(localized: "\(n) videos. Tick the ones you want.") }
    }

    nonisolated enum Picker {
        static var photosOnly: String { String(localized: "Photos only") }
        static var videosOnly: String { String(localized: "Videos only") }
        static func count(photos: Int, videos: Int) -> String {
            String(localized: "\(photos) photos, \(videos) videos. Tap to tick.")
        }
    }

    // MARK: - Settings

    nonisolated enum Settings {
        static var title: String { String(localized: "Settings") }
        static var thumbnail: String { String(localized: "Thumbnail") }
        static var embedThumbnail: String { String(localized: "Embed thumbnail as cover art") }
        static var photos: String { String(localized: "Photos") }
        static var alsoSaveToPhotos: String { String(localized: "Also save to Photos") }
        static var clipboard: String { String(localized: "Clipboard") }
        static var detectLinks: String { String(localized: "Detect copied YouTube links") }
        static var downloads: String { String(localized: "Downloads") }
        static var atOnce: String { String(localized: "At once") }
        static var speedLimit: String { String(localized: "Speed limit") }
        static var unlimited: String { String(localized: "Unlimited") }
        static var progress: String { String(localized: "Progress & notifications") }
        static var keepAlive: String { String(localized: "Keep downloading when locked") }
        static var liveActivity: String { String(localized: "Live Activity") }
        static var notifications: String { String(localized: "Notifications") }
        static var language: String { String(localized: "Language") }
        static var followPhone: String { String(localized: "Follow phone") }
        static var restartToApply: String { String(localized: "Close and reopen the app to switch language.") }
        static var theme: String { String(localized: "Theme") }
        static var light: String { String(localized: "Light") }
        static var dark: String { String(localized: "Dark") }
        static var notAvailable: String { String(localized: "Not available on iPhone") }
    }

    /// The texts behind the ⓘ buttons.
    nonisolated enum Info {
        static var photos: String {
            String(localized: "Videos and pictures always go to Files → On My iPhone → Media Downloader. Turn this on to also add them to the Photos app.")
        }
        static var clipboard: String {
            String(localized: "Copy a YouTube link (Share → Copy link) and open the app: it asks if you want to download it.\n\nTo stop iPhone asking \"Allow Paste\": Settings → Apps → Media Downloader → Paste from Other Apps → Allow.")
        }
        static var downloads: String {
            String(localized: "At once: how many files download at the same time. 2–3 is faster; more can get you blocked by YouTube for a while.\n\nSpeed limit: the total for all downloads, handy on mobile data.")
        }
        static var progress: String {
            String(localized: "Keep downloading when locked: plays silent audio during downloads so iPhone doesn't pause the app. It mixes with your music and stops when downloads finish.\n\nLive Activity: progress on the Lock Screen and in the Dynamic Island.")
        }
        static var accounts: String {
            String(localized: "You log in on the site's own page, so your password goes to the site, not this app. Logins are only used for the links you paste.")
        }
        static var notAvailable: String {
            String(localized: "Subtitles, TikTok and sites not in the Other Sites list need yt-dlp, which iPhone apps aren't allowed to run.")
        }
    }

    nonisolated enum Accounts {
        static var title: String { String(localized: "Accounts") }
        static var site: String { String(localized: "Site") }
        static var loggedIn: String { String(localized: "Logged in") }
        static var notLoggedIn: String { String(localized: "Not logged in") }
        static var logIn: String { String(localized: "Log in") }
        static var logOut: String { String(localized: "Log out") }
        static func logInTo(_ site: String) -> String { String(localized: "Log in to \(site)") }
        static var instagramNote: String { String(localized: "Needed for all Instagram downloads.") }
        static var facebookNote: String { String(localized: "Needed for most Facebook posts (public ones may work without it).") }
        static var redditNote: String { String(localized: "Optional. Helps when Reddit blocks downloads, and needed for 18+ posts.") }
    }

    // MARK: - History

    nonisolated enum History {
        static var title: String { String(localized: "History") }
        static var show: String { String(localized: "Show") }
        static var all: String { String(localized: "All") }
        static var downloaded: String { String(localized: "Downloaded") }
        static var failed: String { String(localized: "Failed") }
        static var skipped: String { String(localized: "Skipped") }
        static var cancelled: String { String(localized: "Cancelled") }
        static var nothingHere: String { String(localized: "Nothing here") }
        static var emptyNote: String { String(localized: "Downloads you make will be listed here.") }
        static var remove: String { String(localized: "Remove") }
        static var deleteFile: String { String(localized: "Delete file") }
        static var deleteFiles: String { String(localized: "Delete Files") }
        static var retry: String { String(localized: "Retry") }
        static var downloadAgain: String { String(localized: "Download again") }
        static var copyLink: String { String(localized: "Copy link") }
        static var shareFile: String { String(localized: "Share file") }
        static var removeFromHistory: String { String(localized: "Remove from history") }
        static var areYouSure: String { String(localized: "Are you sure?") }
        static var clearAllHistory: String { String(localized: "Clear all history") }
        static func deleteCount(_ n: Int) -> String { String(localized: "Delete \(n) file(s)") }
        static func removeCount(_ n: Int) -> String { String(localized: "Remove (\(n))") }
        static var deleteAllDownloaded: String { String(localized: "Delete all downloaded files") }
        static var deleteAllDownloadedMenu: String { String(localized: "Delete all downloaded files...") }
        static var clearAllNote: String { String(localized: "Only the history list is cleared. Your downloaded files stay in the folder.") }
        static var deleteSelectedNote: String { String(localized: "The selected files are removed from the Media Downloader folder.") }
        static var deleteAllNote: String { String(localized: "Every file listed as downloaded is removed from the Media Downloader folder.") }
        static var saved: String { String(localized: "Saved") }
        static var fileGone: String { String(localized: "File no longer in folder") }
        static var alreadyExisted: String { String(localized: "Already existed") }
        static var select: String { String(localized: "Select...") }
        static var selectAll: String { String(localized: "Select All") }
        static var clearFromHistory: String { String(localized: "Clear from history") }
        static var clearDownloaded: String { String(localized: "Clear downloaded") }
        static var clearFailed: String { String(localized: "Clear failed") }
        static var clearSkipped: String { String(localized: "Clear skipped") }
        static var clearAll: String { String(localized: "Clear all") }
        static var files: String { String(localized: "Files") }
        // Reasons saved with an entry
        static var siteNotSupported: String { String(localized: "Site not supported") }
        static var alreadyInFolder: String { String(localized: "Already in folder") }
        static var notValidLink: String { String(localized: "Not a valid link") }
        static var noVideoInLink: String { String(localized: "No YouTube video in this link") }
    }

    // MARK: - Progress, log and notifications

    nonisolated enum Status {
        static func linkTag(_ i: Int, of n: Int) -> String { String(localized: "[link \(i)/\(n)]") }
        static var linkTagSingle: String { String(localized: "[link]") }
        static var pasteYouTubeLink: String { String(localized: "Paste at least one YouTube link.") }
        static var pasteLink: String { String(localized: "Paste at least one link.") }
        static func queued(_ link: String) -> String { String(localized: "Queued (starts after the current downloads): \(link)") }
        static var linkReceived: String { String(localized: "Link received from another app") }
        static var notValidLink: String { String(localized: "Not a valid link.") }
        static var readingLink: String { String(localized: "Reading link...") }
        static var playlistSkipped: String { String(localized: "Playlist skipped.") }
        static func playlistPicked(_ picked: Int, of total: Int, _ title: String) -> String {
            String(localized: "\(picked) of \(total) videos selected from \"\(title)\"")
        }
        static var noYouTubeVideo: String { String(localized: "Couldn't find a YouTube video in this link.") }
        static func playlistFailed(_ why: String) -> String { String(localized: "Couldn't read the playlist: \(why)") }
        static var playlistFailedSingle: String { String(localized: "Couldn't read the playlist, downloading just this video.") }
        static var waitingForPlaylist: String { String(localized: "Waiting for playlist selection...") }
        static func notSupported(_ list: String) -> String { String(localized: "Not supported yet. Works with: \(list)") }
        static func readingPost(_ site: String) -> String { String(localized: "Reading \(site) post...") }
        static func filesInPost(_ n: Int) -> String { String(localized: "\(n) files in this post") }
        static var waitingForPick: String { String(localized: "Waiting for you to pick...") }
        static var postSkipped: String { String(localized: "Post skipped.") }
        static func picked(_ n: Int, of total: Int) -> String { String(localized: "\(n) of \(total) picked") }
        static func atOnce(_ n: Int) -> String { String(localized: "Downloading \(n) at once") }
        static func atOnceProgress(_ active: Int, _ finished: Int, _ total: Int) -> String {
            String(localized: "Downloading \(active) at once · \(finished)/\(total) finished")
        }
        static func finishedCount(_ finished: Int, _ total: Int) -> String { String(localized: "\(finished)/\(total) finished") }
        static func summary(done: Int, skipped: Int, failed: Int) -> String {
            String(localized: "\(done) downloaded, \(skipped) skipped, \(failed) failed")
        }
        static func cancelledCount(_ n: Int) -> String { String(localized: "Cancelled. \(n) downloaded.") }
        static func finished(_ summary: String) -> String { String(localized: "Finished: \(summary).") }
        static func skippedExists(_ name: String) -> String { String(localized: "Skipped (already exists): \(name)") }
        static var starting: String { String(localized: "Starting...") }
        static func done(_ name: String) -> String { String(localized: "Done: \(name)") }
        static var addedToPhotos: String { String(localized: "Added to Photos") }
        static func photosFailed(_ why: String) -> String { String(localized: "Couldn't add to Photos: \(why)") }
        static var cancelled: String { String(localized: "Cancelled") }
        static func failed(_ why: String) -> String { String(localized: "Failed: \(why)") }
        static func retrying(_ attempt: Int) -> String { String(localized: "Temporary error, retrying (\(attempt)/3)...") }
        static var findingStreams: String { String(localized: "Finding streams...") }
        static var saving: String { String(localized: "Saving...") }
        static var convertingMP3: String { String(localized: "Converting to MP3...") }
        static var merging: String { String(localized: "Merging video and audio...") }
        static func videoPercent(_ p: Int) -> String { String(localized: "Video \(p)%") }
        static func audioPercent(_ p: Int) -> String { String(localized: "Audio \(p)%") }
        static func photoPercent(_ p: Int) -> String { String(localized: "Photo \(p)%") }
        static var downloadsFinished: String { String(localized: "Downloads finished") }
        static var downloadPaused: String { String(localized: "Download paused") }
        static var openToContinue: String { String(localized: "Open Media Downloader to continue downloading.") }
    }

    // MARK: - Errors

    nonisolated enum Errors {
        static func http(_ code: Int) -> String { String(localized: "Server returned HTTP \(code)") }
        static func saveFailed(_ why: String) -> String { String(localized: "Couldn't save the file: \(why)") }
        static var photosDenied: String { String(localized: "No permission to add to Photos") }
        static var gifNoSound: String { String(localized: "This one has no sound (it's a GIF), so there's no audio to save") }
        static var noSound: String { String(localized: "This video has no sound") }
        // YouTube
        static var playlistUnreadable: String { String(localized: "Couldn't read this playlist. It may be private, or the link is incomplete.") }
        static var playlistPageUnreadable: String { String(localized: "Couldn't read the playlist page") }
        static var noM4A: String { String(localized: "No M4A audio stream available for this video") }
        static var noMP4: String { String(localized: "No iPhone-playable MP4 video stream available") }
        // Instagram
        static var instagramLogin: String { String(localized: "Log in to Instagram first: Settings (gear) → Accounts → Instagram") }
        static var instagramBadLink: String { String(localized: "That's not a link to an Instagram post or Reel (stories aren't supported)") }
        static var instagramLoggedOut: String { String(localized: "Instagram logged you out. Log in again: Settings → Accounts") }
        static var instagramNoPost: String { String(localized: "Instagram didn't return this post (private account you don't follow?)") }
        static var instagramEmpty: String { String(localized: "Nothing to download in this post") }
        // Facebook
        static var facebookLogin: String { String(localized: "Facebook wants you logged in for this post: Settings (gear) → Accounts → Facebook") }
        static var facebookLoggedOut: String { String(localized: "Facebook logged you out. Log in again: Settings → Accounts → Facebook") }
        static var facebookNothingPublic: String { String(localized: "No public video or photo found. If it's not public, log in: Settings → Accounts → Facebook") }
        static var facebookNothing: String { String(localized: "Couldn't find a video or photo on this Facebook page") }
        // Reddit
        static var redditBadLink: String { String(localized: "That's not a link to a Reddit post") }
        static func redditBlocked(_ code: Int) -> String {
            String(localized: "Reddit blocked the request (HTTP \(code)). Logging in to Reddit in Settings → Accounts usually fixes this.")
        }
        static var redditUnreadable: String { String(localized: "Couldn't read the Reddit post") }
        static var redditNoMedia: String { String(localized: "This post has no Reddit-hosted video or pictures (YouTube and other links aren't supported here)") }
        // X
        static var xBadLink: String { String(localized: "That's not a link to an X post") }
        static var xNoVideo: String { String(localized: "This post has no video (or it's private / age-restricted)") }
        // Other sites
        static var streamableBadLink: String { String(localized: "That's not a Streamable video link") }
        static var streamableUnreadable: String { String(localized: "Couldn't read the Streamable video") }
        static var streamableProcessing: String { String(localized: "This Streamable video is still processing or unavailable") }
        static var streamableNoMP4: String { String(localized: "No MP4 file for this Streamable video") }
        static var imgurBadLink: String { String(localized: "That's not an Imgur link") }
        static var archiveBadLink: String { String(localized: "That's not an Internet Archive item link") }
        static var archiveUnreadable: String { String(localized: "Couldn't read this Internet Archive item") }
        static var archiveNoMedia: String { String(localized: "This item has no MP4 video or MP3 audio") }
        static var pageNoVideo: String { String(localized: "No downloadable video found on this page (this site isn't supported)") }
    }

    // MARK: - Terms, privacy, about, reports

    nonisolated enum Terms {
        static var title: String { String(localized: "Terms & Conditions") }
        static var intro: String { String(localized: "Please read and agree before using Media Downloader. You'll be asked again every 10 days.") }
        static var agree: String { String(localized: "I Agree") }
        static func agreedOn(_ date: String, next: String) -> String {
            String(localized: "You agreed on \(date). You'll be asked again on \(next).")
        }
        /// (heading, text) pairs, in order.
        static var sections: [(String, String)] {
            [
                (String(localized: "Personal use only"),
                 String(localized: "Only download content you own, have permission to save, or are allowed to keep where you live (for example, to watch offline yourself). Don't share, upload or sell what you download.")),
                (String(localized: "Follow each site's rules"),
                 String(localized: "YouTube, Instagram, Facebook, Reddit, X and other sites have their own terms, and downloading can go against them. You are responsible for how you use this app.")),
                (String(localized: "Not affiliated"),
                 String(localized: "Media Downloader is an independent personal project. It isn't made, endorsed or supported by YouTube, Google, Meta, Reddit, X or any other site.")),
                (String(localized: "Everything stays on your iPhone"),
                 String(localized: "The app has no servers, accounts, analytics, tracking or ads. Your downloads, history and settings never leave this iPhone.")),
                (String(localized: "Logins"),
                 String(localized: "You log in on the site's own page. The app never sees or saves your password. Only the site's login session is kept, privately on this iPhone, until you log out or delete the app.")),
                (String(localized: "No warranty"),
                 String(localized: "The app is provided as is. Sites change often, so downloads can stop working at any time. The developer isn't responsible for any loss or damage from using it.")),
            ]
        }
    }

    nonisolated enum Privacy {
        static var title: String { String(localized: "Privacy") }
        static var summary: String { String(localized: "Media Downloader has no servers, no accounts, no analytics, no tracking and no ads. Nothing about you is collected.") }
        static var storedTitle: String { String(localized: "Stored only on this iPhone") }
        static var stored: [String] {
            [
                String(localized: "Downloaded files (Files → On My iPhone → Media Downloader)"),
                String(localized: "Download history"),
                String(localized: "Settings"),
                String(localized: "Site logins: the login session only, never your password"),
            ]
        }
        static var onlineTitle: String { String(localized: "What goes online") }
        static var online: String { String(localized: "Only the requests needed to download from the links you paste, the same as opening them in a browser. A report is sent only if you choose to, and you see everything in it first.") }
        static var deleteTitle: String { String(localized: "Removing your data") }
        static var delete: String { String(localized: "Log out in Settings → Accounts to remove a login. Clear History removes the list. Deleting the app removes everything it stored.") }
        static func loginNotice(_ site: String) -> String {
            String(localized: "Your password isn't saved by this app: you're typing it into \(site)'s own page. Only the login session is kept, privately on this iPhone, until you log out in Settings → Accounts.")
        }
    }

    nonisolated enum Report {
        static var title: String { String(localized: "Report a Problem") }
        static var short: String { String(localized: "Report") }
        static var byEmail: String { String(localized: "Send Email") }
        static var onGitHub: String { String(localized: "Open an Issue on GitHub") }
        static var message: String { String(localized: "You'll see the report before it's sent. It includes the app version, iOS version and iPhone model, plus the error if there is one.") }
        static var prompt: String { String(localized: "Describe what happened:") }
        static var noMail: String { String(localized: "No email app is set up. Use GitHub instead, or email workacckwanrong@gmail.com.") }
    }

    nonisolated enum About {
        static var title: String { String(localized: "About") }
        static var version: String { String(localized: "Version") }
        static var sourceCode: String { String(localized: "Source Code on GitHub") }
        static var acknowledgements: String { String(localized: "Acknowledgements") }
        static var thanks: String { String(localized: "Media Downloader is built with these open-source projects. Thank you!") }
        static var youTubeKit: String { String(localized: "Finds YouTube video and audio streams.") }
        static var swiftLAME: String { String(localized: "Turns audio into MP3, using the LAME encoder.") }
    }
}
