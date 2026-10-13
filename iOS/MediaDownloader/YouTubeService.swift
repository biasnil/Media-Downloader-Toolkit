import AVFoundation
import Foundation
import Photos
import UIKit
import SwiftLAME
import YouTubeKit

/// One video in a playlist (or a single link).
struct PlaylistItem: Identifiable, Hashable, Sendable {
    let id: String      // YouTube video ID
    let title: String
}

struct PlaylistInfo: Sendable {
    let title: String
    let items: [PlaylistItem]
}

enum MediaFormat: String, CaseIterable, Identifiable {
    case mp3, audio, video   // "audio" = m4a (kept as the saved-setting name from before)
    var id: String { rawValue }
    var label: String {
        switch self {
        case .mp3: return AppText.Formats.mp3
        case .audio: return AppText.Formats.m4a
        case .video: return AppText.Formats.mp4
        }
    }
    var fileExtension: String {
        switch self {
        case .mp3: return "mp3"
        case .audio: return "m4a"
        case .video: return "mp4"
        }
    }
}

enum DownloadResult {
    case saved(URL)
    case alreadyExists(String)
}

enum DownloadError: LocalizedError {
    case http(Int)
    case noStream(String)
    case export(String)
    case photosDenied

    var errorDescription: String? {
        switch self {
        case .http(let code): return AppText.Errors.http(code)
        case .noStream(let what): return what
        case .export(let why): return AppText.Errors.saveFailed(why)
        case .photosDenied: return AppText.Errors.photosDenied
        }
    }

    /// YouTube's 403/429s are often temporary — fresh stream links usually fix them.
    var isRetryable: Bool {
        if case .http(let code) = self { return code == 403 || code == 429 || code >= 500 }
        return false
    }
}

/// Everything that talks to YouTube or touches media files.
@MainActor
enum YouTubeService {
    private static let desktopUA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    // MARK: - Links

    /// Video ID from watch?v=, youtu.be/, /shorts/, /live/ or /embed/ links.
    static func videoID(in url: URL) -> String? {
        let query = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems
        if let v = query?.first(where: { $0.name == "v" })?.value, !v.isEmpty { return v }
        let parts = url.pathComponents.filter { $0 != "/" }
        if url.host?.lowercased().contains("youtu.be") == true { return parts.first }
        if let i = parts.firstIndex(where: { ["shorts", "live", "embed"].contains($0) }), i + 1 < parts.count {
            return parts[i + 1]
        }
        return nil
    }

    static func playlistID(in url: URL) -> String? {
        URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?
            .first(where: { $0.name == "list" })?.value
    }

    // MARK: - Playlists
    // YouTubeKit only handles single videos. Playlists are read the way yt-dlp does it:
    // first YouTube's own internal API (the "browse"/"next" calls its website makes),
    // then the web page as a backup.

    /// Returns nil when the link has no playlist. Reads up to ~5,000 videos.
    static func playlist(from url: URL) async throws -> PlaylistInfo? {
        guard let list = playlistID(in: url) else { return nil }
        let video = videoID(in: url)

        // Mixes (RD...) only exist next to a video; normal playlists have their own page
        let isMix = list.hasPrefix("RD")
        var attempts: [() async throws -> PlaylistInfo] = []
        if isMix, let video {
            attempts.append { try await apiMix(list: list, video: video) }
        } else {
            attempts.append { try await apiPlaylist(list: list) }
        }
        attempts.append { try await webPage(list: list, video: isMix ? video : nil) }

        var lastError: Error?
        for attempt in attempts {
            do {
                let info = try await attempt()
                if !info.items.isEmpty { return info }
            } catch {
                lastError = lastError ?? error // keep the first reason, it is usually the clearest
            }
        }
        if let error = lastError as? DownloadError { throw error }
        throw DownloadError.noStream(AppText.Errors.playlistUnreadable)
    }

    // MARK: InnerTube (YouTube's internal API)

    private static let apiBase = "https://www.youtube.com/youtubei/v1/"

    private static func api(_ endpoint: String, _ body: [String: Any]) async throws -> Any {
        var payload = body
        payload["context"] = ["client": ["clientName": "WEB", "clientVersion": "2.20250925.01.00",
                                         "hl": "en", "gl": "US"]]
        var request = URLRequest(url: URL(string: apiBase + endpoint + "?prettyPrint=false")!)
        request.httpMethod = "POST"
        request.httpBody = try JSONSerialization.data(withJSONObject: payload)
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue(desktopUA, forHTTPHeaderField: "User-Agent")
        request.setValue("https://www.youtube.com", forHTTPHeaderField: "Origin")
        request.setValue("1", forHTTPHeaderField: "X-YouTube-Client-Name")
        request.setValue("2.20250925.01.00", forHTTPHeaderField: "X-YouTube-Client-Version")
        let (data, response) = try await URLSession.shared.data(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            throw DownloadError.http(http.statusCode)
        }
        return try JSONSerialization.jsonObject(with: data)
    }

    /// A normal playlist: first page, then "load more" pages until the end.
    private static func apiPlaylist(list: String) async throws -> PlaylistInfo {
        var collected = Collected()
        var response = try await api("browse", ["browseId": "VL" + list])
        try checkAlerts(response)
        collected.read(response)

        var pages = 1
        while let token = collected.nextToken, pages < 50 {
            collected.nextToken = nil
            try Task.checkCancellation()
            response = try await api("browse", ["continuation": token])
            collected.read(response)
            pages += 1
        }
        return collected.result
    }

    /// A Mix (RD...): the list YouTube shows next to the video.
    private static func apiMix(list: String, video: String) async throws -> PlaylistInfo {
        var collected = Collected()
        collected.read(try await api("next", ["videoId": video, "playlistId": list]))
        return collected.result
    }

    /// YouTube says why a playlist can't be shown (private, deleted...) in an "alert".
    private static func checkAlerts(_ response: Any) throws {
        guard let alerts = (response as? [String: Any])?["alerts"] as? [[String: Any]] else { return }
        for alert in alerts {
            let box = (alert["alertRenderer"] ?? alert["alertWithButtonRenderer"]) as? [String: Any]
            if (box?["type"] as? String) == "ERROR", let message = text(box?["text"]) {
                throw DownloadError.noStream("YouTube: \(message)")
            }
        }
    }

    // MARK: Web page backup

    private static func webPage(list: String, video: String?) async throws -> PlaylistInfo {
        var page = URLComponents(string: "https://www.youtube.com/playlist")!
        page.queryItems = [URLQueryItem(name: "list", value: list)]
        if let video {
            page = URLComponents(string: "https://www.youtube.com/watch")!
            page.queryItems = [URLQueryItem(name: "v", value: video), URLQueryItem(name: "list", value: list)]
        }
        var request = URLRequest(url: page.url!)
        request.setValue(desktopUA, forHTTPHeaderField: "User-Agent")
        request.setValue("en-US,en;q=0.9", forHTTPHeaderField: "Accept-Language")
        request.setValue("SOCS=CAI; CONSENT=YES+1", forHTTPHeaderField: "Cookie") // skip the cookie-consent page
        let (data, _) = try await URLSession.shared.data(for: request)

        guard let json = initialData(in: String(decoding: data, as: UTF8.self)),
              let root = try? JSONSerialization.jsonObject(with: Data(json.utf8)) else {
            throw DownloadError.noStream(AppText.Errors.playlistPageUnreadable)
        }
        try checkAlerts(root)
        var collected = Collected()
        collected.read(root)
        return collected.result
    }

    // MARK: Reading the data

    /// Gathers videos, the title and the "load more" token from YouTube's JSON.
    private struct Collected {
        var items: [PlaylistItem] = []
        var lockupItems: [PlaylistItem] = []   // YouTube's newer layout, used only if the classic one is missing
        var seen = Set<String>()
        var title: String?
        var nextToken: String?

        var result: PlaylistInfo {
            PlaylistInfo(title: title ?? "Playlist", items: items.isEmpty ? lockupItems : items)
        }

        mutating func read(_ root: Any) {
            YouTubeService.walk(root) { key, dict in
                switch key {
                case "playlistVideoRenderer", "playlistPanelVideoRenderer":
                    if let id = dict["videoId"] as? String, seen.insert(id).inserted {
                        items.append(PlaylistItem(id: id, title: YouTubeService.text(dict["title"]) ?? id))
                    }
                case "lockupViewModel":
                    if (dict["contentType"] as? String) == "LOCKUP_CONTENT_TYPE_VIDEO",
                       let id = dict["contentId"] as? String, !seen.contains(id) {
                        let name = (((dict["metadata"] as? [String: Any])?["lockupMetadataViewModel"] as? [String: Any])?["title"]
                            as? [String: Any])?["content"] as? String
                        lockupItems.append(PlaylistItem(id: id, title: name ?? id))
                    }
                case "continuationItemRenderer":
                    if let token = ((dict["continuationEndpoint"] as? [String: Any])?["continuationCommand"]
                        as? [String: Any])?["token"] as? String {
                        nextToken = token
                    }
                case "playlistMetadataRenderer", "playlist", "playlistHeaderRenderer":
                    if title == nil {
                        title = (dict["title"] as? String) ?? YouTubeService.text(dict["title"])
                    }
                case "pageHeaderRenderer":
                    if title == nil, let t = dict["pageTitle"] as? String { title = t }
                default:
                    break
                }
            }
        }
    }

    /// Cuts the `ytInitialData = {...}` JSON out of the page by matching braces.
    private static func initialData(in html: String) -> String? {
        let markers = ["var ytInitialData = ", "window[\"ytInitialData\"] = ", "ytInitialData = "]
        guard let marker = markers.lazy.compactMap({ html.range(of: $0) }).first,
              let brace = html[marker.upperBound...].firstIndex(of: "{") else { return nil }

        let bytes = Array(html.utf8)
        let start = html.utf8.distance(from: html.utf8.startIndex, to: brace)
        var depth = 0
        var inString = false
        var escaped = false
        for j in start..<bytes.count {
            let b = bytes[j]
            if inString {
                if escaped { escaped = false }
                else if b == 0x5C { escaped = true }       // backslash
                else if b == 0x22 { inString = false }     // quote
            } else if b == 0x22 {
                inString = true
            } else if b == 0x7B {                          // {
                depth += 1
            } else if b == 0x7D {                          // }
                depth -= 1
                if depth == 0 { return String(decoding: bytes[start...j], as: UTF8.self) }
            }
        }
        return nil
    }

    nonisolated private static func walk(_ node: Any, _ visit: (String, [String: Any]) -> Void) {
        if let dict = node as? [String: Any] {
            for (key, value) in dict {
                if let child = value as? [String: Any] { visit(key, child) }
                walk(value, visit)
            }
        } else if let array = node as? [Any] {
            for value in array { walk(value, visit) }
        }
    }

    nonisolated private static func text(_ node: Any?) -> String? {
        guard let dict = node as? [String: Any] else { return nil }
        if let simple = dict["simpleText"] as? String { return simple }
        if let runs = dict["runs"] as? [[String: Any]] {
            let joined = runs.compactMap { $0["text"] as? String }.joined()
            return joined.isEmpty ? nil : joined
        }
        return nil
    }

    // MARK: - Downloading

    /// Downloads one video as m4a or mp4 into [folder], retrying temporary YouTube errors.
    /// [maxHeight] caps video resolution (nil = best).
    static func download(
        videoID: String,
        format: MediaFormat,
        maxHeight: Int?,
        mp3Bitrate: Int,
        skipExisting: Bool,
        embedArtwork: Bool,
        into folder: URL,
        update: (Double, String) -> Void
    ) async throws -> DownloadResult {
        var attempt = 1
        while true {
            do {
                return try await downloadOnce(videoID: videoID, format: format, maxHeight: maxHeight,
                                              mp3Bitrate: mp3Bitrate,
                                              skipExisting: skipExisting, embedArtwork: embedArtwork,
                                              into: folder, update: update)
            } catch let error as DownloadError where error.isRetryable && attempt < 3 {
                attempt += 1
                update(0, AppText.Status.retrying(attempt))
                try await Task.sleep(for: .seconds(3 * attempt))
            }
        }
    }

    private static func downloadOnce(
        videoID: String,
        format: MediaFormat,
        maxHeight: Int?,
        mp3Bitrate: Int,
        skipExisting: Bool,
        embedArtwork: Bool,
        into folder: URL,
        update: (Double, String) -> Void
    ) async throws -> DownloadResult {
        update(0, AppText.Status.findingStreams)
        let (streams, metadata) = try await fetchInfo(videoID: videoID)
        let title = metadata?.title.isEmpty == false ? metadata!.title : videoID
        let name = fileName(title, ext: format.fileExtension)
        let destination = folder.appendingPathComponent(name)
        if skipExisting && FileManager.default.fileExists(atPath: destination.path) {
            return .alreadyExists(name)
        }

        let work = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }

        // YouTube's best AAC audio. (Its opus audio is higher quality but can't be saved without FFmpeg.)
        guard let audio = streams.filterAudioOnly().filter({ $0.fileExtension == .m4a }).highestAudioBitrateStream() else {
            throw DownloadError.noStream(AppText.Errors.noM4A)
        }
        let artwork = embedArtwork ? await artworkData(videoID: videoID, square: format != .video) : nil
        let audioFile = work.appendingPathComponent("audio.m4a")
        let output = work.appendingPathComponent("output.\(format.fileExtension)")

        if format == .audio {
            try await fetch(audio.url, to: audioFile) { update($0, "\(Int($0 * 100))%") }
            update(1, AppText.Status.saving)
            try await exportAudio(audioFile, to: output, title: title, artwork: artwork)
        } else if format == .mp3 {
            try await fetch(audio.url, to: audioFile) { update($0 * 0.9, "\(Int($0 * 100))%") }
            update(0.9, AppText.Status.convertingMP3)
            // YouTube's audio comes in a streaming-style container; rewrap it as a plain
            // .m4a first so iOS's audio decoder can read it for the MP3 encoder
            let plain = work.appendingPathComponent("plain.m4a")
            try await exportAudio(audioFile, to: plain, title: title, artwork: nil)
            try await encodeMP3(plain, to: output, bitrate: mp3Bitrate, title: title, artwork: artwork)
        } else {
            guard let video = pickVideo(from: streams, maxHeight: maxHeight) else {
                throw DownloadError.noStream(AppText.Errors.noMP4)
            }
            let resolution = video.videoResolution.map { "\($0)p" }
            let videoFile = work.appendingPathComponent("video.mp4")
            try await fetch(video.url, to: videoFile) { p in
                let percent = Int(p * 100)
                update(p * 0.85, resolution.map { "\($0) \(percent)%" } ?? AppText.Status.videoPercent(percent))
            }
            try await fetch(audio.url, to: audioFile) { update(0.85 + $0 * 0.15, AppText.Status.audioPercent(Int($0 * 100))) }
            update(1, AppText.Status.merging)
            try await mux(video: videoFile, audio: audioFile, to: output, title: title, artwork: artwork)
        }

        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: output, to: destination)
        return .saved(destination)
    }

    /// Streams + title in one place, so YouTubeKit's object never crosses threads.
    nonisolated private static func fetchInfo(videoID: String) async throws -> ([YouTubeKit.Stream], YouTubeMetadata?) {
        // Local extraction first; if YouTube changed something, fall back to YouTubeKit's server
        let youtube = YouTube(videoID: videoID, methods: [.local, .remote])
        let streams = try await youtube.streams
        let metadata = try? await youtube.metadata
        return (streams, metadata)
    }

    /// Highest resolution up to [maxHeight], preferring H.264 (plays and shares everywhere).
    private static func pickVideo(from streams: [YouTubeKit.Stream], maxHeight: Int?) -> YouTubeKit.Stream? {
        let playable = streams.filterVideoOnly().filter { $0.fileExtension == .mp4 && $0.isNativelyPlayable }
        let limit = maxHeight ?? Int.max
        let fits = playable.filter { ($0.videoResolution ?? 0) <= limit }
        let pool = fits.isEmpty ? [playable.min { ($0.videoResolution ?? 0) < ($1.videoResolution ?? 0) }].compactMap { $0 } : fits
        return pool.max { a, b in
            let ra = a.videoResolution ?? 0, rb = b.videoResolution ?? 0
            if ra != rb { return ra < rb }
            return codecRank(a) < codecRank(b)
        }
    }

    private static func codecRank(_ stream: YouTubeKit.Stream) -> Int {
        if case .avc1? = stream.videoCodec { return 2 }
        return 1
    }

    /// Downloads in 10 MB pieces. YouTube throttles one big request, but serves ranges at full speed.
    /// With a speed limit set, uses smaller pieces and waits between them.
    static func fetch(_ url: URL, to destination: URL, progress: (Double) -> Void) async throws {
        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let handle = try FileHandle(forWritingTo: destination)
        defer { try? handle.close() }

        let chunk = SpeedLimiter.chunkSize
        var offset = 0
        var total: Int?
        while total == nil || offset < total! {
            try Task.checkCancellation()
            var request = URLRequest(url: url)
            request.setValue("bytes=\(offset)-\(offset + chunk - 1)", forHTTPHeaderField: "Range")
            let (data, response) = try await dataWithRetry(request)
            guard let http = response as? HTTPURLResponse else { throw DownloadError.http(-1) }
            guard (200...299).contains(http.statusCode) else { throw DownloadError.http(http.statusCode) }

            try handle.write(contentsOf: data)
            offset += data.count
            await SpeedLimiter.shared.consume(data.count)
            if http.statusCode == 200 {
                total = offset // server ignored the range and sent the whole file
            } else if total == nil,
                      let range = http.value(forHTTPHeaderField: "Content-Range"),
                      let size = range.split(separator: "/").last.flatMap({ Int($0) }) {
                total = size
            }
            if let total { progress(Double(offset) / Double(max(total, 1))) }
            if data.count < chunk { break }
        }
    }

    /// Retries network drops — e.g. when iOS paused the app and you came back.
    private static func dataWithRetry(_ request: URLRequest) async throws -> (Data, URLResponse) {
        var attempt = 1
        while true {
            do {
                return try await URLSession.shared.data(for: request)
            } catch let error as URLError where error.code != .cancelled && attempt < 4 {
                attempt += 1
                try await Task.sleep(for: .seconds(2 * attempt))
            }
        }
    }

    // MARK: - Saving files (AVFoundation — no FFmpeg needed)

    /// Rewraps the downloaded audio as a normal .m4a with title + cover art. No re-encoding.
    nonisolated static func exportAudio(_ file: URL, to output: URL, title: String, artwork: Data?) async throws {
        try await export(AVURLAsset(url: file), to: output, as: .m4a, title: title, artwork: artwork)
    }

    /// Combines the separate video and audio streams into one .mp4. No re-encoding.
    nonisolated static func mux(video: URL, audio: URL, to output: URL, title: String, artwork: Data?) async throws {
        let videoAsset = AVURLAsset(url: video)
        let audioAsset = AVURLAsset(url: audio)
        guard let videoTrack = try await videoAsset.loadTracks(withMediaType: .video).first,
              let audioTrack = try await audioAsset.loadTracks(withMediaType: .audio).first else {
            throw DownloadError.export("missing video or audio track")
        }
        let duration = try await videoAsset.load(.duration)
        let audioDuration = try await audioAsset.load(.duration)
        let transform = try await videoTrack.load(.preferredTransform)

        let composition = AVMutableComposition()
        guard let v = composition.addMutableTrack(withMediaType: .video, preferredTrackID: kCMPersistentTrackID_Invalid),
              let a = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw DownloadError.export("couldn't create tracks")
        }
        try v.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: videoTrack, at: .zero)
        try a.insertTimeRange(CMTimeRange(start: .zero, duration: CMTimeMinimum(duration, audioDuration)), of: audioTrack, at: .zero)
        v.preferredTransform = transform

        try await export(composition, to: output, as: .mp4, title: title, artwork: artwork)
    }

    /// Copies just the audio track out of a video file into an .m4a. No re-encoding.
    nonisolated static func extractAudio(from video: URL, to output: URL, title: String, artwork: Data?) async throws {
        let asset = AVURLAsset(url: video)
        guard let track = try await asset.loadTracks(withMediaType: .audio).first else {
            throw DownloadError.noStream(AppText.Errors.noSound)
        }
        let duration = try await asset.load(.duration)
        let composition = AVMutableComposition()
        guard let a = composition.addMutableTrack(withMediaType: .audio, preferredTrackID: kCMPersistentTrackID_Invalid) else {
            throw DownloadError.export("couldn't create audio track")
        }
        try a.insertTimeRange(CMTimeRange(start: .zero, duration: duration), of: track, at: .zero)
        try await export(composition, to: output, as: .m4a, title: title, artwork: artwork)
    }

    nonisolated static func export(_ asset: AVAsset, to output: URL, as type: AVFileType, title: String, artwork: Data?) async throws {
        guard let session = AVAssetExportSession(asset: asset, presetName: AVAssetExportPresetPassthrough) else {
            throw DownloadError.export("export not supported")
        }
        let titleItem = AVMutableMetadataItem()
        titleItem.identifier = .iTunesMetadataSongName
        titleItem.value = title as NSString
        titleItem.extendedLanguageTag = "und"
        var items: [AVMetadataItem] = [titleItem]
        if let artwork {
            let art = AVMutableMetadataItem()
            art.identifier = .iTunesMetadataCoverArt
            art.value = artwork as NSData
            art.dataType = kCMMetadataBaseDataType_JPEG as String
            items.append(art)
        }
        session.metadata = items
        session.shouldOptimizeForNetworkUse = true
        try await session.export(to: output, as: type)
    }

    /// Decodes the audio and encodes it as MP3 with LAME (the same encoder FFmpeg uses on desktop).
    /// Title and cover art go in an ID3 tag at the front, like any normal MP3.
    nonisolated static func encodeMP3(_ source: URL, to output: URL, bitrate: Int, title: String, artwork: Data?) async throws {
        try? FileManager.default.removeItem(at: output)
        try id3Tag(title: title, artwork: artwork).write(to: output)
        // The encoder appends its MP3 audio after the tag we just wrote
        let encoder = try SwiftLameEncoder(
            sourceUrl: source,
            configuration: LameConfiguration(bitrateMode: .constant(Int32(bitrate)), quality: .nearBest),
            destinationUrl: output
        )
        try await encoder.encode(priority: .userInitiated)
    }

    /// A minimal ID3v2.3 tag: title (TIT2) and optional cover art (APIC).
    nonisolated private static func id3Tag(title: String, artwork: Data?) -> Data {
        func bigEndian(_ value: Int) -> [UInt8] {
            [UInt8((value >> 24) & 0xFF), UInt8((value >> 16) & 0xFF), UInt8((value >> 8) & 0xFF), UInt8(value & 0xFF)]
        }
        var frames = Data()
        func addFrame(_ id: String, _ body: Data) {
            frames.append(contentsOf: Array(id.utf8))
            frames.append(contentsOf: bigEndian(body.count))
            frames.append(contentsOf: [0, 0]) // frame flags
            frames.append(body)
        }

        // Title as UTF-16 with byte-order mark, so Chinese/Korean titles show correctly
        var titleBody = Data([1, 0xFF, 0xFE])
        for unit in title.utf16 {
            titleBody.append(UInt8(unit & 0xFF))
            titleBody.append(UInt8(unit >> 8))
        }
        addFrame("TIT2", titleBody)

        if let artwork {
            var picture = Data([0])                      // text encoding: Latin-1
            picture.append(contentsOf: Array("image/jpeg".utf8))
            picture.append(0)
            picture.append(3)                            // picture type: front cover
            picture.append(0)                            // empty description
            picture.append(artwork)
            addFrame("APIC", picture)
        }

        var tag = Data(Array("ID3".utf8))
        tag.append(contentsOf: [3, 0, 0])                // version 2.3, no flags
        let size = frames.count                          // "syncsafe" size: 7 bits per byte
        tag.append(contentsOf: [UInt8((size >> 21) & 0x7F), UInt8((size >> 14) & 0x7F),
                                UInt8((size >> 7) & 0x7F), UInt8(size & 0x7F)])
        tag.append(frames)
        return tag
    }

    /// The video's thumbnail as JPEG. Cropped square for audio so it looks right as album art.
    private static func artworkData(videoID: String, square: Bool) async -> Data? {
        for size in ["maxresdefault", "hqdefault"] {
            guard let url = URL(string: "https://i.ytimg.com/vi/\(videoID)/\(size).jpg"),
                  let result = try? await URLSession.shared.data(from: url),
                  (result.1 as? HTTPURLResponse)?.statusCode == 200,
                  var image = UIImage(data: result.0) else { continue }
            if square, let cg = image.cgImage {
                let side = min(cg.width, cg.height)
                let rect = CGRect(x: (cg.width - side) / 2, y: (cg.height - side) / 2, width: side, height: side)
                if let cropped = cg.cropping(to: rect) { image = UIImage(cgImage: cropped) }
            }
            return image.jpegData(compressionQuality: 0.9)
        }
        return nil
    }

    nonisolated static func saveToPhotos(_ file: URL, isImage: Bool = false) async throws {
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw DownloadError.photosDenied }
        try await PHPhotoLibrary.shared().performChanges {
            if isImage {
                _ = PHAssetCreationRequest.creationRequestForAssetFromImage(atFileURL: file)
            } else {
                _ = PHAssetCreationRequest.creationRequestForAssetFromVideo(atFileURL: file)
            }
        }
    }

    /// Safe file name: removes characters iOS/Windows don't allow.
    static func fileName(_ title: String, ext: String) -> String {
        let bad = CharacterSet(charactersIn: "/\\:*?\"<>|").union(.newlines).union(.controlCharacters)
        var clean = title.components(separatedBy: bad).joined(separator: "_").trimmingCharacters(in: .whitespaces)
        if clean.isEmpty { clean = "video" }
        return String(clean.prefix(150)) + "." + ext
    }
}
