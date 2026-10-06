import Foundation
import Observation
import UIKit
import UserNotifications

/// A playlist waiting for the user to tick which videos they want.
@MainActor
final class PlaylistPrompt: Identifiable {
    let id = UUID()
    let title: String
    let items: [PlaylistItem]
    let preselected: Set<String>
    private var continuation: CheckedContinuation<[PlaylistItem]?, Never>?

    init(info: PlaylistInfo, preselected: Set<String>, continuation: CheckedContinuation<[PlaylistItem]?, Never>) {
        self.title = info.title
        self.items = info.items
        self.preselected = preselected
        self.continuation = continuation
    }

    /// Answers exactly once, even if called again.
    func finish(_ picked: [PlaylistItem]?) {
        continuation?.resume(returning: picked)
        continuation = nil
    }
}

/// Settings captured when Download is pressed, so changing them mid-download can't mix things up.
struct DownloadOptions {
    var format: MediaFormat
    var maxHeight: Int?
    var mp3Bitrate: Int
    var skipExisting: Bool
    var embedArtwork: Bool
    var saveVideosToPhotos: Bool
    var notify: Bool
}

/// Runs downloads and holds the progress, status and log the screen shows.
@MainActor
@Observable
final class DownloadManager {
    var urlText = ""
    var status = "Idle."
    var progress: Double = 0
    var playlistPrompt: PlaylistPrompt?
    private(set) var log: [String] = []
    private(set) var isRunning = false

    private var job: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var notifyEnabled = false

    /// Files → On My iPhone → Media Downloader
    static var saveFolder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    func start(_ options: DownloadOptions) {
        let links = urlText.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !links.isEmpty else { status = "Paste at least one YouTube link."; return }
        guard !isRunning else { return }

        isRunning = true
        progress = 0
        notifyEnabled = options.notify
        UIApplication.shared.isIdleTimerDisabled = true // keep the screen on while downloading
        beginBackgroundTime()

        job = Task {
            await run(links, options)
            finishJob()
        }
    }

    func cancel() {
        job?.cancel()
        answerPlaylist(nil)
        status = "Cancelling..."
    }

    func answerPlaylist(_ picked: [PlaylistItem]?) {
        playlistPrompt?.finish(picked)
        playlistPrompt = nil
    }

    func clearLog() { log.removeAll() }

    // MARK: - The download loop

    private func run(_ links: [String], _ o: DownloadOptions) async {
        if o.notify {
            _ = try? await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound])
        }

        var done = 0, skipped = 0, failed = 0
        for (i, link) in links.enumerated() {
            if Task.isCancelled { break }
            let tag = "[\(i + 1)/\(links.count)]"
            add("\(tag) \(link)")
            guard let url = URL(string: link) else { add("\(tag) Not a valid link."); failed += 1; continue }
            status = "\(tag) Reading link..."

            // Work out which videos this link means
            var videos: [PlaylistItem]
            do {
                if let playlist = try await YouTubeService.playlist(from: url), playlist.items.count > 1 {
                    guard let picked = await askPlaylist(playlist, url: url) else {
                        if Task.isCancelled { break }
                        add("\(tag) Playlist skipped.")
                        continue
                    }
                    add("\(tag) \(picked.count) of \(playlist.items.count) videos selected from \"\(playlist.title)\"")
                    videos = picked
                } else if let id = YouTubeService.videoID(in: url) {
                    videos = [PlaylistItem(id: id, title: "")]
                } else {
                    add("\(tag) Couldn't find a YouTube video in this link.")
                    failed += 1
                    continue
                }
            } catch {
                if Task.isCancelled { break }
                guard let id = YouTubeService.videoID(in: url) else {
                    add("\(tag) Couldn't read link: \(error.localizedDescription)")
                    failed += 1
                    continue
                }
                add("\(tag) Couldn't read the playlist, downloading just this video.")
                videos = [PlaylistItem(id: id, title: "")]
            }

            for (j, video) in videos.enumerated() {
                if Task.isCancelled { break }
                let label = videos.count > 1 ? "\(tag) \(j + 1)/\(videos.count)" : tag

                // Skip before downloading anything when we already know the title
                if o.skipExisting, !video.title.isEmpty {
                    let name = YouTubeService.fileName(video.title, ext: o.format.fileExtension)
                    if FileManager.default.fileExists(atPath: Self.saveFolder.appendingPathComponent(name).path) {
                        add("\(label) Skipped (already exists): \(name)")
                        skipped += 1
                        continue
                    }
                }

                progress = 0
                do {
                    let result = try await YouTubeService.download(
                        videoID: video.id, format: o.format, maxHeight: o.maxHeight, mp3Bitrate: o.mp3Bitrate,
                        skipExisting: o.skipExisting, embedArtwork: o.embedArtwork,
                        into: Self.saveFolder
                    ) { p, text in
                        self.progress = p
                        self.status = "\(label) \(text)"
                    }
                    switch result {
                    case .alreadyExists(let name):
                        add("\(label) Skipped (already exists): \(name)")
                        skipped += 1
                    case .saved(let file):
                        add("\(label) Done: \(file.lastPathComponent)")
                        done += 1
                        if o.format == .video && o.saveVideosToPhotos {
                            do {
                                try await YouTubeService.saveToPhotos(file)
                                add("\(label) Added to Photos")
                            } catch {
                                add("\(label) Couldn't add to Photos: \(error.localizedDescription)")
                            }
                        }
                    }
                } catch {
                    if Task.isCancelled { add("\(label) Cancelled"); break }
                    add("\(label) Failed: \(error.localizedDescription)")
                    failed += 1
                }
            }
        }

        progress = 0
        status = Task.isCancelled
            ? "Cancelled. \(done) downloaded."
            : "Finished: \(done) downloaded, \(skipped) skipped, \(failed) failed."
        if o.notify && UIApplication.shared.applicationState != .active {
            notify("Downloads finished", status)
        }
    }

    private func askPlaylist(_ playlist: PlaylistInfo, url: URL) async -> [PlaylistItem]? {
        status = "Waiting for playlist selection..."
        // watch?v=X&list=... usually means "this one song", so pre-tick just it
        let preselected: Set<String>
        if let id = YouTubeService.videoID(in: url), playlist.items.contains(where: { $0.id == id }) {
            preselected = [id]
        } else {
            preselected = Set(playlist.items.map(\.id))
        }

        return await withCheckedContinuation { continuation in
            playlistPrompt = PlaylistPrompt(info: playlist, preselected: preselected, continuation: continuation)
        }
    }

    private func add(_ line: String) {
        log.append(line)
        if log.count > 500 { log.removeFirst(log.count - 500) }
    }

    // MARK: - Leaving the app
    // iOS gives apps about 30 seconds after you switch away, then pauses them.
    // A download in progress picks back up (with retries) when you return.

    private func beginBackgroundTime() {
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "download") { [weak self] in
            MainActor.assumeIsolated { self?.backgroundTimeExpired() }
        }
    }

    private func backgroundTimeExpired() {
        if isRunning && notifyEnabled {
            notify("Download paused", "Open Media Downloader to continue downloading.")
        }
        endBackgroundTime()
    }

    private func endBackgroundTime() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }

    private func finishJob() {
        isRunning = false
        job = nil
        UIApplication.shared.isIdleTimerDisabled = false
        endBackgroundTime()
    }

    private func notify(_ title: String, _ body: String) {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        UNUserNotificationCenter.current().add(
            UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
        )
    }
}
