import Foundation

// MARK: - Extractor (yt-dlp's "logged in" route)

/// Asks Instagram's own web API for the post, using the saved login.
/// The reply already contains direct .mp4 links with sound in them.
enum InstagramExtractor: SiteExtractor {
    static let name = "Instagram"
    private static let webAppID = "936619743392459" // Instagram website's app ID (same as yt-dlp)

    static func canHandle(_ url: URL) -> Bool {
        Sites.host(url).hasSuffix("instagram.com") || Sites.host(url) == "instagr.am"
    }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        guard let cookie = await SiteLogin.cookieHeader(for: .instagram) else {
            throw DownloadError.noStream("Log in to Instagram first: Settings (gear) → Accounts → Instagram")
        }

        var postURL = url
        if shortcode(in: postURL) == nil {
            postURL = try await resolve(url, cookie: cookie) // share links redirect to the post
        }
        guard let code = shortcode(in: postURL), let mediaID = mediaID(from: code) else {
            throw DownloadError.noStream("That's not a link to an Instagram post or Reel (stories aren't supported)")
        }

        var request = URLRequest(url: URL(string: "https://www.instagram.com/api/v1/media/\(mediaID)/info/")!)
        request.setValue(cookie, forHTTPHeaderField: "Cookie")
        request.setValue(SiteLogin.mobileUA, forHTTPHeaderField: "User-Agent")
        request.setValue(webAppID, forHTTPHeaderField: "X-IG-App-ID")
        request.setValue("0", forHTTPHeaderField: "X-IG-WWW-Claim")
        request.setValue("https://www.instagram.com", forHTTPHeaderField: "Origin")
        request.setValue(postURL.absoluteString, forHTTPHeaderField: "Referer")
        request.setValue("*/*", forHTTPHeaderField: "Accept")

        let (data, response) = try await URLSession.shared.data(for: request)
        let http = response as? HTTPURLResponse
        if http?.url?.path.hasPrefix("/accounts/login") == true || http?.statusCode == 401 {
            throw DownloadError.noStream("Instagram logged you out. Log in again: Settings → Accounts")
        }
        let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
        if let message = json?["message"] as? String, http?.statusCode != 200 {
            if message.contains("login_required") {
                throw DownloadError.noStream("Instagram logged you out. Log in again: Settings → Accounts")
            }
            throw DownloadError.noStream("Instagram: \(message)")
        }
        if let code = http?.statusCode, !(200...299).contains(code) {
            throw DownloadError.http(code)
        }
        guard let item = (json?["items"] as? [[String: Any]])?.first else {
            throw DownloadError.noStream("Instagram didn't return this post (private account you don't follow?)")
        }

        let user = ((item["user"] as? [String: Any])?["username"] as? String) ?? "instagram"
        let caption = ((item["caption"] as? [String: Any])?["text"] as? String) ?? ""
        let title = makeTitle(user: user, caption: caption, code: code)

        // A carousel holds several items; a single post is just itself
        let parts = (item["carousel_media"] as? [[String: Any]]) ?? [item]
        let videos = parts.compactMap { part -> (URL, Bool)? in
            guard let pick = bestVideo(part["video_versions"] as? [[String: Any]] ?? [], maxHeight: maxHeight) else { return nil }
            return (pick, (part["has_audio"] as? Bool) == false)
        }
        guard !videos.isEmpty else {
            throw DownloadError.noStream("This post only has photos (only videos are downloaded)")
        }
        return videos.enumerated().map { index, video in
            SiteMedia(title: videos.count > 1 ? "\(title) (\(index + 1))" : title,
                      videoURL: video.0, audioURL: nil, silent: video.1)
        }
    }

    /// /p/CODE, /reel/CODE, /reels/CODE, /tv/CODE — also with a username in front.
    private static func shortcode(in url: URL) -> String? {
        let parts = url.pathComponents.filter { $0 != "/" }
        guard let i = parts.firstIndex(where: { ["p", "reel", "reels", "tv"].contains($0) }),
              i + 1 < parts.count else { return nil }
        return parts[i + 1]
    }

    /// The shortcode is the post's number written in Instagram's base-64 alphabet.
    private static func mediaID(from shortcode: String) -> UInt64? {
        let alphabet = Array("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        let code = shortcode.count > 28 ? String(shortcode.dropLast(28)) : shortcode // private-post links are longer
        var value: UInt64 = 0
        for ch in code {
            guard let digit = alphabet.firstIndex(of: ch) else { return nil }
            let (shifted, overflow1) = value.multipliedReportingOverflow(by: 64)
            let (added, overflow2) = shifted.addingReportingOverflow(UInt64(digit))
            if overflow1 || overflow2 { return nil }
            value = added
        }
        return value
    }

    private static func resolve(_ url: URL, cookie: String) async throws -> URL {
        var request = URLRequest(url: url)
        request.setValue(cookie, forHTTPHeaderField: "Cookie")
        request.setValue(SiteLogin.mobileUA, forHTTPHeaderField: "User-Agent")
        let (_, response) = try await URLSession.shared.data(for: request)
        return response.url ?? url
    }

    /// Highest quality that fits the limit; if none fit, the smallest.
    private static func bestVideo(_ versions: [[String: Any]], maxHeight: Int?) -> URL? {
        let options: [(URL, Int)] = versions.compactMap { v in
            guard let u = (v["url"] as? String).flatMap(URL.init(string:)) else { return nil }
            let w = (v["width"] as? Int) ?? 0, h = (v["height"] as? Int) ?? 0
            return (u, min(w, h) > 0 ? min(w, h) : h)
        }
        let limit = maxHeight ?? Int.max
        let fitting = options.filter { $0.1 <= limit }
        let best = fitting.isEmpty ? options.min(by: { $0.1 < $1.1 }) : fitting.max(by: { $0.1 < $1.1 })
        return best?.0
    }

    private static func makeTitle(user: String, caption: String, code: String) -> String {
        let words = caption.components(separatedBy: .whitespacesAndNewlines).filter { !$0.isEmpty }.joined(separator: " ")
        return words.isEmpty ? "@\(user) - \(code)" : "@\(user) - \(words.prefix(60))"
    }
}
