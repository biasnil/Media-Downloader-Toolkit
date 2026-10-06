import Foundation

/// What an extractor found behind a link: where the video (and maybe separate audio) lives.
struct SiteMedia: Sendable {
    let title: String
    let videoURL: URL
    /// Reddit keeps sound in a separate file; X puts it inside the video (nil here).
    let audioURL: URL?
    /// True when the video file itself has no sound (GIF-style posts).
    let silent: Bool
    /// The link is an audio file (e.g. a direct .mp3) — saved as-is.
    var isAudioOnly: Bool = false
}

/// One site = one extractor, so if a site changes, only its file needs fixing.
protocol SiteExtractor {
    static var name: String { get }
    static func canHandle(_ url: URL) -> Bool
    /// Every video in the post (an X post can hold up to 4).
    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia]
}

enum Sites {
    /// Checked in order; the generic page reader is last, as a catch-all.
    static let all: [any SiteExtractor.Type] = [
        InstagramExtractor.self, FacebookExtractor.self, RedditExtractor.self, XExtractor.self,
        StreamableExtractor.self, ImgurExtractor.self, ArchiveOrgExtractor.self,
        DirectFileExtractor.self, GenericPageExtractor.self,
    ]

    /// What the "Supported sites" dropdown shows.
    static let supportedList: [String] = [
        "Instagram (login)",
        "Facebook (login)",
        "Reddit (login optional)",
        "X / Twitter",
        "Streamable",
        "Imgur",
        "Internet Archive",
        "Direct .mp4 / .mov / .mp3 / .m4a links",
        "Tumblr & other pages with a video tag",
    ]

    static func extractor(for url: URL) -> (any SiteExtractor.Type)? {
        all.first { $0.canHandle(url) }
    }

    static func host(_ url: URL) -> String {
        (url.host ?? "").lowercased().replacingOccurrences(of: "www.", with: "")
    }

    /// Downloads a web page as text.
    static func getPage(_ url: URL, userAgent: String, cookie: String? = nil) async throws -> (html: String, finalURL: URL) {
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        request.setValue("text/html,application/xhtml+xml,*/*", forHTTPHeaderField: "Accept")
        if let cookie { request.setValue(cookie, forHTTPHeaderField: "Cookie") }
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw DownloadError.http(http.statusCode)
        }
        return (String(decoding: data, as: UTF8.self), response.url ?? url)
    }

    /// Every <meta> tag's property/name → content, e.g. "og:video" → "https://...".
    static func metaTags(in html: String) -> [String: String] {
        var result: [String: String] = [:]
        for chunk in html.components(separatedBy: "<meta").dropFirst() {
            let tag = String(chunk.prefix { $0 != ">" })
            guard let key = htmlAttribute("property", in: tag) ?? htmlAttribute("name", in: tag),
                  let content = htmlAttribute("content", in: tag), result[key.lowercased()] == nil else { continue }
            result[key.lowercased()] = decodeEntities(content)
        }
        return result
    }

    /// Value of name="..." or name='...' inside an HTML tag.
    static func htmlAttribute(_ name: String, in tag: String) -> String? {
        for quote in ["\"", "'"] {
            if let start = tag.range(of: "\(name)=\(quote)", options: .caseInsensitive) {
                let before = start.lowerBound == tag.startIndex ? " " : tag[tag.index(before: start.lowerBound)]
                guard before == " " || before == "\n" || before == "\t" else { continue }
                let rest = tag[start.upperBound...]
                if let end = rest.firstIndex(of: Character(quote)) { return String(rest[..<end]) }
            }
        }
        return nil
    }

    static func decodeEntities(_ s: String) -> String {
        s.replacingOccurrences(of: "&amp;", with: "&")
            .replacingOccurrences(of: "&quot;", with: "\"")
            .replacingOccurrences(of: "&#039;", with: "'")
            .replacingOccurrences(of: "&#39;", with: "'")
            .replacingOccurrences(of: "&lt;", with: "<")
            .replacingOccurrences(of: "&gt;", with: ">")
    }

    /// Finds "key":"value" in a page's embedded JSON and returns the decoded value.
    static func jsonString(after key: String, in text: String, from start: String.Index? = nil) -> (value: String, end: String.Index)? {
        var searchFrom = start ?? text.startIndex
        while let found = text.range(of: "\"\(key)\":\"", range: searchFrom..<text.endIndex) {
            var i = found.upperBound
            var escaped = false
            while i < text.endIndex {
                let c = text[i]
                if escaped { escaped = false }
                else if c == "\\" { escaped = true }
                else if c == "\"" { break }
                i = text.index(after: i)
            }
            guard i < text.endIndex else { return nil }
            let raw = "\"" + String(text[found.upperBound..<i]) + "\""
            if let data = raw.data(using: .utf8),
               let value = try? JSONSerialization.jsonObject(with: data, options: .fragmentsAllowed) as? String,
               !value.isEmpty {
                return (value, i)
            }
            searchFrom = i
        }
        return nil
    }

    static func getJSON(_ request: URLRequest) async throws -> Any {
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw DownloadError.http(http.statusCode)
        }
        return try JSONSerialization.jsonObject(with: data)
    }
}

// MARK: - Reddit

/// Reddit gives any post as JSON if you add ".json" to its link — no login needed.
/// Videos live on v.redd.it as separate video and audio files (DASH), which we merge.
enum RedditExtractor: SiteExtractor {
    static let name = "Reddit"
    private static let userAgent = "ios:com.nil.mediadownloader:1.0 (personal media downloader)"

    static func canHandle(_ url: URL) -> Bool {
        let h = Sites.host(url)
        return h.hasSuffix("reddit.com") || h == "redd.it" || h == "v.redd.it"
    }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        let postURL = try await resolve(url)
        var path = postURL.path
        while path.hasSuffix("/") { path.removeLast() }
        guard path.contains("/comments/") else {
            throw DownloadError.noStream("That's not a link to a Reddit post")
        }

        var request = URLRequest(url: URL(string: "https://www.reddit.com\(path).json?raw_json=1")!)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        if let cookie = await SiteLogin.cookieHeader(for: .reddit) {
            request.setValue(cookie, forHTTPHeaderField: "Cookie") // optional login: 18+ posts, fewer blocks
        }
        let json: Any
        do {
            json = try await Sites.getJSON(request)
        } catch DownloadError.http(let code) where code == 403 || code == 429 {
            throw DownloadError.noStream("Reddit blocked the request (HTTP \(code)). Logging in to Reddit in Settings → Accounts usually fixes this.")
        }

        guard let listing = (json as? [Any])?.first as? [String: Any],
              let post = ((listing["data"] as? [String: Any])?["children"] as? [[String: Any]])?
                .first?["data"] as? [String: Any] else {
            throw DownloadError.noStream("Couldn't read the Reddit post")
        }
        let title = (post["title"] as? String) ?? "Reddit video"

        guard let video = redditVideo(in: post),
              let fallback = (video["fallback_url"] as? String).flatMap(URL.init(string:)) else {
            throw DownloadError.noStream("This post has no Reddit-hosted video (images and YouTube links aren't supported here)")
        }
        let isGIF = (video["is_gif"] as? Bool) ?? false
        let hasAudio = !isGIF && ((video["has_audio"] as? Bool) ?? true)

        // The DASH list names every quality and the audio file. If it can't be read, fall back.
        var videoURL = fallback
        var audioURL: URL?
        if let dash = (video["dash_url"] as? String).flatMap(URL.init(string:)),
           let tracks = try? await dashTracks(dash) {
            let limit = maxHeight ?? Int.max
            let fitting = tracks.videos.filter { $0.height <= limit }
            let best = fitting.isEmpty
                ? tracks.videos.min(by: { $0.height < $1.height })
                : fitting.max(by: { $0.height < $1.height })
            if let best {
                videoURL = best.url
            }
            audioURL = tracks.audios.max(by: { $0.bandwidth < $1.bandwidth })?.url
        }
        if hasAudio && audioURL == nil {
            audioURL = await guessAudio(near: fallback)
        }
        return [SiteMedia(title: title, videoURL: videoURL, audioURL: hasAudio ? audioURL : nil, silent: !hasAudio)]
    }

    /// Share links (/s/xxxx), redd.it/xxxx and v.redd.it/xxxx redirect to the real post.
    private static func resolve(_ url: URL) async throws -> URL {
        if url.path.contains("/comments/") { return url }
        var request = URLRequest(url: url)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (_, response) = try await URLSession.shared.data(for: request)
        return response.url ?? url
    }

    /// The video can be on the post itself, on a crosspost's original, or a GIF preview.
    private static func redditVideo(in post: [String: Any]) -> [String: Any]? {
        func fromMedia(_ p: [String: Any]) -> [String: Any]? {
            ((p["secure_media"] as? [String: Any])?["reddit_video"] as? [String: Any])
                ?? ((p["media"] as? [String: Any])?["reddit_video"] as? [String: Any])
        }
        if let v = fromMedia(post) { return v }
        if let original = (post["crosspost_parent_list"] as? [[String: Any]])?.first, let v = fromMedia(original) { return v }
        return (post["preview"] as? [String: Any])?["reddit_video_preview"] as? [String: Any]
    }

    struct Track { let url: URL; let height: Int; let bandwidth: Int }

    /// Reads the DASH .mpd file: every video quality, plus the audio file.
    private static func dashTracks(_ mpd: URL) async throws -> (videos: [Track], audios: [Track]) {
        var request = URLRequest(url: mpd)
        request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
        let (data, _) = try await URLSession.shared.data(for: request)
        let xml = String(decoding: data, as: UTF8.self)
        let base = mpd.deletingLastPathComponent()

        var videos: [Track] = [], audios: [Track] = []
        for set in xml.components(separatedBy: "<AdaptationSet").dropFirst() {
            let setHeader = String(set.prefix { $0 != ">" })
            let setIsAudio = attribute("contentType", in: setHeader) == "audio"
                || (attribute("mimeType", in: setHeader)?.hasPrefix("audio") ?? false)
            for rep in set.components(separatedBy: "<Representation").dropFirst() {
                let header = String(rep.prefix { $0 != ">" })
                guard let open = rep.range(of: "<BaseURL>"),
                      let close = rep.range(of: "</BaseURL>", range: open.upperBound..<rep.endIndex) else { continue }
                let file = rep[open.upperBound..<close.lowerBound].trimmingCharacters(in: .whitespacesAndNewlines)
                guard let url = URL(string: file, relativeTo: base)?.absoluteURL else { continue }
                let track = Track(url: url,
                                  height: Int(attribute("height", in: header) ?? "") ?? 0,
                                  bandwidth: Int(attribute("bandwidth", in: header) ?? "") ?? 0)
                let isAudio = setIsAudio || (attribute("mimeType", in: header)?.hasPrefix("audio") ?? false)
                if isAudio { audios.append(track) } else { videos.append(track) }
            }
        }
        guard !videos.isEmpty else { throw DownloadError.noStream("Unreadable DASH list") }
        return (videos, audios)
    }

    /// Value of name="..." inside an XML tag.
    private static func attribute(_ name: String, in tag: String) -> String? {
        guard let start = tag.range(of: " \(name)=\"") else { return nil }
        let rest = tag[start.upperBound...]
        guard let end = rest.firstIndex(of: "\"") else { return nil }
        return String(rest[..<end])
    }

    /// Older posts without a readable DASH list: try Reddit's usual audio file names.
    private static func guessAudio(near video: URL) async -> URL? {
        let folder = video.deletingLastPathComponent()
        for name in ["DASH_AUDIO_128.mp4", "DASH_AUDIO_64.mp4", "DASH_audio.mp4", "audio"] {
            var request = URLRequest(url: folder.appendingPathComponent(name))
            request.httpMethod = "HEAD"
            request.setValue(userAgent, forHTTPHeaderField: "User-Agent")
            if let (_, response) = try? await URLSession.shared.data(for: request),
               (response as? HTTPURLResponse)?.statusCode == 200 {
                return request.url
            }
        }
        return nil
    }
}

// MARK: - X / Twitter

/// Uses the endpoint X's own "embedded post" widget reads — public posts only, no login.
/// If X returns nothing (it sometimes does), falls back to the public FxTwitter API.
enum XExtractor: SiteExtractor {
    static let name = "X"

    static func canHandle(_ url: URL) -> Bool {
        let h = Sites.host(url)
        return ["x.com", "twitter.com", "mobile.twitter.com", "mobile.x.com",
                "fxtwitter.com", "vxtwitter.com", "fixupx.com"].contains(h)
    }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        let parts = url.pathComponents
        guard let i = parts.firstIndex(of: "status"), i + 1 < parts.count,
              let id = Int64(parts[i + 1].prefix { $0.isNumber }) else {
            throw DownloadError.noStream("That's not a link to an X post")
        }
        let limit = maxHeight ?? Int.max

        if let found = try? await fromSyndication(id: id, limit: limit), !found.isEmpty {
            return found
        }
        let found = try await fromFxTwitter(id: id, limit: limit)
        if found.isEmpty {
            throw DownloadError.noStream("This post has no video (or it's private / age-restricted)")
        }
        return found
    }

    private static func fromSyndication(id: Int64, limit: Int) async throws -> [SiteMedia] {
        var c = URLComponents(string: "https://cdn.syndication.twimg.com/tweet-result")!
        c.queryItems = [
            URLQueryItem(name: "id", value: String(id)),
            URLQueryItem(name: "lang", value: "en"),
            URLQueryItem(name: "token", value: token(for: id)),
        ]
        guard let tweet = try await Sites.getJSON(URLRequest(url: c.url!)) as? [String: Any] else { return [] }

        let user = ((tweet["user"] as? [String: Any])?["screen_name"] as? String) ?? "x"
        let title = makeTitle(user: user, text: tweet["text"] as? String)

        // The post's own media, or the quoted post's if it only quotes a video
        var details = tweet["mediaDetails"] as? [[String: Any]] ?? []
        if details.isEmpty, let quoted = tweet["quoted_tweet"] as? [String: Any] {
            details = quoted["mediaDetails"] as? [[String: Any]] ?? []
        }

        let videos = details.filter { ["video", "animated_gif"].contains($0["type"] as? String) }
        return videos.enumerated().compactMap { index, media in
            let isGIF = (media["type"] as? String) == "animated_gif"
            let variants = ((media["video_info"] as? [String: Any])?["variants"] as? [[String: Any]] ?? [])
                .filter { ($0["content_type"] as? String) == "video/mp4" }
                .compactMap { v -> (URL, Int)? in
                    guard let u = (v["url"] as? String).flatMap(URL.init(string:)) else { return nil }
                    return (u, heightFromPath(u) ?? ((v["bitrate"] as? Int) ?? 0) / 10_000)
                }
            guard let pick = choose(variants, limit: limit) else { return nil }
            let name = videos.count > 1 ? "\(title) (\(index + 1))" : title
            return SiteMedia(title: name, videoURL: pick, audioURL: nil, silent: isGIF)
        }
    }

    private static func fromFxTwitter(id: Int64, limit: Int) async throws -> [SiteMedia] {
        let json = try await Sites.getJSON(URLRequest(url: URL(string: "https://api.fxtwitter.com/status/\(id)")!))
        guard let tweet = (json as? [String: Any])?["tweet"] as? [String: Any] else { return [] }
        let user = ((tweet["author"] as? [String: Any])?["screen_name"] as? String) ?? "x"
        let title = makeTitle(user: user, text: tweet["text"] as? String)

        var media = tweet["media"] as? [String: Any]
        if (media?["videos"] as? [Any])?.isEmpty ?? true {
            media = (tweet["quote"] as? [String: Any])?["media"] as? [String: Any]
        }
        let videos = media?["videos"] as? [[String: Any]] ?? []
        return videos.enumerated().compactMap { index, v in
            var options: [(URL, Int)] = []
            for f in v["formats"] as? [[String: Any]] ?? [] where (f["container"] as? String) == "mp4" {
                if let u = (f["url"] as? String).flatMap(URL.init(string:)) {
                    options.append((u, heightFromPath(u) ?? 0))
                }
            }
            if let u = (v["url"] as? String).flatMap(URL.init(string:)) {
                options.append((u, (v["height"] as? Int) ?? heightFromPath(u) ?? 0))
            }
            guard let pick = choose(options, limit: limit) else { return nil }
            let name = videos.count > 1 ? "\(title) (\(index + 1))" : title
            return SiteMedia(title: name, videoURL: pick, audioURL: nil, silent: (v["type"] as? String) == "gif")
        }
    }

    /// Highest quality that fits the limit; if none fit, the smallest.
    private static func choose(_ options: [(URL, Int)], limit: Int) -> URL? {
        let fitting = options.filter { $0.1 <= limit }
        let best = fitting.isEmpty ? options.min(by: { $0.1 < $1.1 }) : fitting.max(by: { $0.1 < $1.1 })
        return best?.0
    }

    /// X video links contain the size, e.g. /vid/avc1/1280x720/abc.mp4 → 720.
    private static func heightFromPath(_ url: URL) -> Int? {
        for part in url.pathComponents {
            let wh = part.split(separator: "x")
            if wh.count == 2, let w = Int(wh[0]), let h = Int(wh[1]) { return min(w, h) }
        }
        return nil
    }

    private static func makeTitle(user: String, text: String?) -> String {
        let words = (text ?? "")
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty && !$0.hasPrefix("https://t.co") }
            .joined(separator: " ")
        return words.isEmpty ? "@\(user)" : "@\(user) - \(words.prefix(60))"
    }

    /// Same token X's embed widget sends: (id / 1e15 * π) in base 36, without zeros or the dot.
    private static func token(for id: Int64) -> String {
        let value = Double(id) / 1e15 * Double.pi
        let digits = Array("0123456789abcdefghijklmnopqrstuvwxyz")
        var whole = Int64(value)
        var fraction = value - Double(whole)
        var integerPart = ""
        repeat {
            integerPart = String(digits[Int(whole % 36)]) + integerPart
            whole /= 36
        } while whole > 0
        var fractionPart = ""
        for _ in 0..<8 where fraction > 0 {
            fraction *= 36
            let d = Int(fraction)
            fractionPart.append(digits[d])
            fraction -= Double(d)
        }
        return (integerPart + fractionPart).replacingOccurrences(of: "0", with: "")
    }
}
