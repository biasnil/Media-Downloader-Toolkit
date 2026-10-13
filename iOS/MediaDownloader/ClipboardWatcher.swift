import SwiftUI
import UIKit

/// Spots a YouTube link on the clipboard when the app opens (e.g. after Share → Copy link)
/// and offers to download it. Setting: `clipboardDetect`.
@MainActor @Observable
final class ClipboardWatcher {
    /// Set when a new YouTube link is found; the alert in ContentView shows while this is non-nil.
    var foundLink: String?

    private let defaults = UserDefaults.standard

    /// Looks at the clipboard once per copy, so the same link is never offered twice.
    func check() async {
        guard defaults.object(forKey: "clipboardDetect") as? Bool ?? true, !snoozedToday else { return }
        let board = UIPasteboard.general
        guard board.changeCount != defaults.integer(forKey: "clipboardLastChange") else { return }
        defaults.set(board.changeCount, forKey: "clipboardLastChange")

        // Checking for a link doesn't trigger iOS's "Allow Paste" prompt; reading it does,
        // so only read when there's probably a web link there.
        guard let found = try? await board.detectedPatterns(for: [\.probableWebURL]),
              found.contains(\.probableWebURL) else { return }
        let text = board.url?.absoluteString ?? board.string ?? ""
        foundLink = Self.youTubeLink(in: text)
    }

    /// "Don't ask again today"
    func snoozeForToday() {
        defaults.set(Date().timeIntervalSince1970, forKey: "clipboardSnoozed")
        foundLink = nil
    }

    private var snoozedToday: Bool {
        let time = defaults.double(forKey: "clipboardSnoozed")
        return time > 0 && Calendar.current.isDateInToday(Date(timeIntervalSince1970: time))
    }

    /// First YouTube video or playlist link in the text.
    private static func youTubeLink(in text: String) -> String? {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(text.startIndex..., in: text)
        for match in detector?.matches(in: text, range: range) ?? [] {
            guard let url = match.url else { continue }
            let host = (url.host ?? "").lowercased()
            guard host.hasSuffix("youtube.com") || host == "youtu.be" || host.hasSuffix("youtube-nocookie.com") else { continue }
            if YouTubeService.videoID(in: url) != nil || YouTubeService.playlistID(in: url) != nil {
                return url.absoluteString
            }
        }
        return nil
    }
}
