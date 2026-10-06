import Foundation

// MARK: - Facebook (login)

/// Facebook puts direct .mp4 links (with sound) in the page's own data,
/// under keys like "browser_native_hd_url" — the same ones yt-dlp reads.
enum FacebookExtractor: SiteExtractor {
    static let name = "Facebook"

    static func canHandle(_ url: URL) -> Bool {
        let h = Sites.host(url)
        return h.hasSuffix("facebook.com") || h == "fb.watch" || h == "fb.com"
    }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        let cookie = await SiteLogin.cookieHeader(for: .facebook)

        // Mobile links show a different page; the desktop one has the video data
        var pageURL = url
        if var c = URLComponents(url: url, resolvingAgainstBaseURL: false),
           let host = c.host, host.hasPrefix("m.") || host.hasPrefix("mbasic.") || host.hasPrefix("web.") {
            c.host = "www.facebook.com"
            pageURL = c.url ?? url
        }
        let page = try await Sites.getPage(pageURL, userAgent: SiteLogin.desktopUA, cookie: cookie)
        if page.finalURL.path.contains("/login") || page.finalURL.path.contains("/checkpoint") {
            throw DownloadError.noStream(cookie == nil
                ? "Facebook wants you logged in for this video: Settings (gear) → Accounts → Facebook"
                : "Facebook logged you out. Log in again: Settings → Accounts → Facebook")
        }
        let html = page.html

        // Newer pages list "progressive_url" entries labelled HD / SD
        var progressiveHD: String?, progressiveSD: String?
        var cursor = html.startIndex
        while let found = Sites.jsonString(after: "progressive_url", in: html, from: cursor) {
            let window = html[found.end..<(html.index(found.end, offsetBy: 300, limitedBy: html.endIndex) ?? html.endIndex)]
            if window.contains("\"quality\":\"HD\"") { progressiveHD = progressiveHD ?? found.value }
            else { progressiveSD = progressiveSD ?? found.value }
            cursor = found.end
        }

        let hd = [Sites.jsonString(after: "browser_native_hd_url", in: html)?.value,
                  Sites.jsonString(after: "playable_url_quality_hd", in: html)?.value,
                  progressiveHD].compactMap { $0 }.first { !$0.contains(".mpd") }
        let sd = [Sites.jsonString(after: "browser_native_sd_url", in: html)?.value,
                  Sites.jsonString(after: "playable_url", in: html)?.value,
                  progressiveSD].compactMap { $0 }.first { !$0.contains(".mpd") }

        // HD is usually 720p+, SD around 360p
        let wantSD = (maxHeight ?? Int.max) < 720
        guard let link = (wantSD ? (sd ?? hd) : (hd ?? sd)), let videoURL = URL(string: link) else {
            throw DownloadError.noStream(cookie == nil
                ? "No public video found. If it's not public, log in: Settings → Accounts → Facebook"
                : "Couldn't find a video on this Facebook page")
        }

        let meta = Sites.metaTags(in: html)
        var title = meta["og:title"] ?? "Facebook video"
        if title.count > 80 { title = String(title.prefix(80)) }
        return [SiteMedia(title: title, videoURL: videoURL, audioURL: nil, silent: false)]
    }
}

// MARK: - Streamable

/// Streamable's own website API returns the mp4 files directly.
enum StreamableExtractor: SiteExtractor {
    static let name = "Streamable"

    static func canHandle(_ url: URL) -> Bool { Sites.host(url) == "streamable.com" }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        // streamable.com/abc, /e/abc, /s/abc/...
        var parts = url.pathComponents.filter { $0 != "/" }
        if let first = parts.first, first == "e" || first == "s" { parts.removeFirst() }
        guard let id = parts.first else { throw DownloadError.noStream("That's not a Streamable video link") }

        guard let video = try await Sites.getJSON(URLRequest(url: URL(string: "https://ajax.streamable.com/videos/\(id)")!)) as? [String: Any] else {
            throw DownloadError.noStream("Couldn't read the Streamable video")
        }
        guard (video["status"] as? Int) == 2 else {
            throw DownloadError.noStream("This Streamable video is still processing or unavailable")
        }
        let title = (video["reddit_title"] as? String) ?? (video["title"] as? String) ?? "Streamable \(id)"

        let files = (video["files"] as? [String: Any] ?? [:]).values.compactMap { value -> (URL, Int)? in
            guard let f = value as? [String: Any], var link = f["url"] as? String else { return nil }
            if link.hasPrefix("//") { link = "https:" + link }
            guard let u = URL(string: link), u.path.hasSuffix(".mp4") else { return nil }
            return (u, (f["height"] as? Int) ?? 0)
        }
        let limit = maxHeight ?? Int.max
        let fitting = files.filter { $0.1 <= limit }
        let best = fitting.isEmpty ? files.min(by: { $0.1 < $1.1 }) : fitting.max(by: { $0.1 < $1.1 })
        guard let pick = best?.0 else { throw DownloadError.noStream("No MP4 file for this Streamable video") }
        return [SiteMedia(title: title, videoURL: pick, audioURL: nil, silent: false)]
    }
}

// MARK: - Imgur

/// imgur.com/abc123 and i.imgur.com/abc123.gifv are just i.imgur.com/abc123.mp4.
/// Albums go through the page reader instead.
enum ImgurExtractor: SiteExtractor {
    static let name = "Imgur"

    static func canHandle(_ url: URL) -> Bool { Sites.host(url).hasSuffix("imgur.com") }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        let parts = url.pathComponents.filter { $0 != "/" }
        if let first = parts.first, ["a", "gallery", "t", "r"].contains(first) {
            return try await GenericPageExtractor.extract(url, maxHeight: maxHeight)
        }
        guard let last = parts.last else { throw DownloadError.noStream("That's not an Imgur link") }
        let id = (last as NSString).deletingPathExtension
        let video = URL(string: "https://i.imgur.com/\(id).mp4")!
        return [SiteMedia(title: "Imgur \(id)", videoURL: video, audioURL: nil, silent: false)]
    }
}

// MARK: - Internet Archive

/// archive.org has an official, stable API listing every file in an item.
enum ArchiveOrgExtractor: SiteExtractor {
    static let name = "Internet Archive"

    static func canHandle(_ url: URL) -> Bool { Sites.host(url) == "archive.org" }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        // archive.org/details/ITEM  or  archive.org/details/ITEM/file.mp4  (also /download/)
        let parts = url.pathComponents.filter { $0 != "/" }
        guard parts.count >= 2, ["details", "download", "embed"].contains(parts[0]) else {
            throw DownloadError.noStream("That's not an Internet Archive item link")
        }
        let item = parts[1]
        let wantedFile = parts.count > 2 ? parts[2...].joined(separator: "/").removingPercentEncoding : nil

        guard let json = try await Sites.getJSON(URLRequest(url: URL(string: "https://archive.org/metadata/\(item)")!)) as? [String: Any],
              let files = json["files"] as? [[String: Any]] else {
            throw DownloadError.noStream("Couldn't read this Internet Archive item")
        }
        let itemTitle = ((json["metadata"] as? [String: Any])?["title"] as? String) ?? item
        let names = files.compactMap { $0["name"] as? String }

        func media(_ name: String, audio: Bool, multiple: Bool) -> SiteMedia? {
            guard let encoded = name.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed),
                  let link = URL(string: "https://archive.org/download/\(item)/\(encoded)") else { return nil }
            let fileTitle = ((name as NSString).lastPathComponent as NSString).deletingPathExtension
            return SiteMedia(title: multiple ? "\(itemTitle) - \(fileTitle)" : itemTitle,
                             videoURL: link, audioURL: nil, silent: false, isAudioOnly: audio)
        }

        if let wantedFile, names.contains(wantedFile) {
            let ext = (wantedFile as NSString).pathExtension.lowercased()
            if let m = media(wantedFile, audio: ["mp3", "m4a"].contains(ext), multiple: false) { return [m] }
        }

        // Videos: prefer full-quality MP4s over the small "_512kb" copies
        var videos = names.filter { $0.lowercased().hasSuffix(".mp4") }
        let full = videos.filter { !$0.lowercased().contains("_512kb") }
        if !full.isEmpty { videos = full }
        if !videos.isEmpty {
            return videos.prefix(100).compactMap { media($0, audio: false, multiple: videos.count > 1) }
        }

        let audios = names.filter { $0.lowercased().hasSuffix(".mp3") || $0.lowercased().hasSuffix(".m4a") }
        if !audios.isEmpty {
            return audios.prefix(100).compactMap { media($0, audio: true, multiple: audios.count > 1) }
        }
        throw DownloadError.noStream("This item has no MP4 video or MP3 audio")
    }
}

// MARK: - Direct file links

/// Any link that points straight at a media file.
enum DirectFileExtractor: SiteExtractor {
    static let name = "Direct link"
    static let videoTypes = ["mp4", "m4v", "mov"]
    static let audioTypes = ["mp3", "m4a", "aac", "wav"]

    static func canHandle(_ url: URL) -> Bool {
        let ext = url.pathExtension.lowercased()
        return ["http", "https"].contains(url.scheme?.lowercased() ?? "") && (videoTypes + audioTypes).contains(ext)
    }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        let name = (url.lastPathComponent.removingPercentEncoding ?? url.lastPathComponent) as NSString
        let audio = audioTypes.contains(url.pathExtension.lowercased())
        return [SiteMedia(title: name.deletingPathExtension, videoURL: url, audioURL: nil,
                          silent: false, isAudioOnly: audio)]
    }
}

// MARK: - Any page with a video tag (catch-all)

/// Many sites (Tumblr, news sites, blogs) name their video file in the tags used for link
/// previews (og:video), or in a plain <video> tag. Sites that stream video in pieces won't work.
enum GenericPageExtractor: SiteExtractor {
    static let name = "Web page"

    static func canHandle(_ url: URL) -> Bool {
        ["http", "https"].contains(url.scheme?.lowercased() ?? "")
    }

    static func extract(_ url: URL, maxHeight: Int?) async throws -> [SiteMedia] {
        let page = try await Sites.getPage(url, userAgent: SiteLogin.desktopUA)
        let html = page.html
        let meta = Sites.metaTags(in: html)
        let title = meta["og:title"] ?? meta["twitter:title"] ?? pageTitle(html) ?? (url.host ?? "video")

        func resolve(_ s: String?) -> URL? {
            guard let s, !s.isEmpty else { return nil }
            return URL(string: Sites.decodeEntities(s), relativeTo: page.finalURL)?.absoluteURL
        }
        func isFile(_ u: URL, types: [String]) -> Bool { types.contains(u.pathExtension.lowercased()) }

        // 1. Link-preview tags
        let declaredMP4 = (meta["og:video:type"] ?? meta["twitter:player:stream:content_type"] ?? "").contains("mp4")
        for key in ["og:video:secure_url", "og:video:url", "og:video", "twitter:player:stream"] {
            if let u = resolve(meta[key]), isFile(u, types: DirectFileExtractor.videoTypes) || declaredMP4 {
                return [SiteMedia(title: title, videoURL: u, audioURL: nil, silent: false)]
            }
        }

        // 2. <video src="..."> or <source src="...">
        for tagName in ["<video", "<source"] {
            for chunk in html.components(separatedBy: tagName).dropFirst() {
                let tag = String(chunk.prefix { $0 != ">" })
                if let u = resolve(Sites.htmlAttribute("src", in: tag)),
                   isFile(u, types: DirectFileExtractor.videoTypes) || (Sites.htmlAttribute("type", in: tag)?.contains("mp4") ?? false) {
                    return [SiteMedia(title: title, videoURL: u, audioURL: nil, silent: false)]
                }
            }
        }

        // 3. Audio pages (podcasts etc.)
        for key in ["og:audio:secure_url", "og:audio:url", "og:audio"] {
            if let u = resolve(meta[key]), isFile(u, types: DirectFileExtractor.audioTypes) {
                return [SiteMedia(title: title, videoURL: u, audioURL: nil, silent: false, isAudioOnly: true)]
            }
        }

        throw DownloadError.noStream("No downloadable video found on this page (this site isn't supported)")
    }

    private static func pageTitle(_ html: String) -> String? {
        guard let open = html.range(of: "<title", options: .caseInsensitive),
              let start = html[open.upperBound...].firstIndex(of: ">"),
              let close = html.range(of: "</title>", options: .caseInsensitive, range: start..<html.endIndex) else { return nil }
        let text = Sites.decodeEntities(String(html[html.index(after: start)..<close.lowerBound]))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return text.isEmpty ? nil : text
    }
}
