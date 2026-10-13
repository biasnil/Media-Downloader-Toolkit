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

/// One YouTube video waiting to download.
struct YouTubeJob: Sendable {
    let videoID: String
    let title: String   // empty when not known yet (single links)
    var link: String { "https://www.youtube.com/watch?v=\(videoID)" }
}

/// Runs downloads and holds the progress, status and log the screen shows.
@MainActor
@Observable
final class DownloadManager {
    var urlText = ""
    var status = AppText.Common.idle
    var progress: Double = 0
    var playlistPrompt: PlaylistPrompt?
    private(set) var log: [String] = []
    private(set) var isRunning = false
    /// Links shared from other apps while a download was running. They start right after.
    private(set) var queued: [String] = []

    private var job: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var notifyEnabled = false
    private var tracker = BatchTracker(total: 0)

    /// Files → On My iPhone → Media Downloader
    static var saveFolder: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    /// The Download button: everything in the link box.
    func start(_ options: DownloadOptions) {
        let links = urlText.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !links.isEmpty else { status = AppText.Status.pasteYouTubeLink; return }
        start(links: links, options)
    }

    /// A link shared from another app (or "Download again" in History).
    func enqueue(_ link: String) {
        if isRunning {
            queued.append(link)
            add(AppText.Status.queued(link))
        } else {
            add(AppText.Status.linkReceived)
            start(links: [link], DownloadOptions.saved(formatKey: "format", defaultFormat: .mp3))
        }
    }

    private func start(links: [String], _ options: DownloadOptions) {
        guard !isRunning else { return }
        isRunning = true
        progress = 0
        notifyEnabled = options.notify
        UIApplication.shared.isIdleTimerDisabled = true // keep the screen on while downloading
        BackgroundKeeper.shared.begin()                 // and keep going when the phone locks
        beginBackgroundTime()

        job = Task {
            await run(links, options)
            finishJob()
        }
    }

    func cancel() {
        job?.cancel()
        queued.removeAll()
        answerPlaylist(nil)
        status = AppText.Common.cancelling
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

        // 1. Work out every video first (playlists ask which videos you want)
        var jobs: [YouTubeJob] = []
        var failedLinks = 0
        for (i, link) in links.enumerated() {
            if Task.isCancelled { break }
            let tag = links.count > 1 ? AppText.Status.linkTag(i + 1, of: links.count) : AppText.Status.linkTagSingle
            add("\(tag) \(link)")
            guard let url = URL(string: link) else {
                add("\(tag) \(AppText.Status.notValidLink)")
                HistoryStore.shared.add(source: "YouTube", title: link, link: link, status: .failed, detail: AppText.History.notValidLink)
                failedLinks += 1
                continue
            }
            status = "\(tag) \(AppText.Status.readingLink)"

            let single = YouTubeService.videoID(in: url)
            do {
                let playlist = try await YouTubeService.playlist(from: url)
                if let playlist, playlist.items.count > 1 {
                    guard let picked = await askPlaylist(playlist, url: url) else {
                        if Task.isCancelled { break }
                        add("\(tag) \(AppText.Status.playlistSkipped)")
                        continue
                    }
                    add("\(tag) \(AppText.Status.playlistPicked(picked.count, of: playlist.items.count, playlist.title))")
                    jobs += picked.map { YouTubeJob(videoID: $0.id, title: $0.title) }
                } else if let single {
                    jobs.append(YouTubeJob(videoID: single, title: ""))
                } else if let only = playlist?.items.first {
                    jobs.append(YouTubeJob(videoID: only.id, title: only.title)) // a playlist with one video
                } else {
                    add("\(tag) \(AppText.Status.noYouTubeVideo)")
                    HistoryStore.shared.add(source: "YouTube", title: link, link: link, status: .failed,
                                            detail: AppText.History.noVideoInLink)
                    failedLinks += 1
                }
            } catch {
                if Task.isCancelled { break }
                guard let single else {
                    add("\(tag) \(AppText.Status.playlistFailed(error.localizedDescription))")
                    HistoryStore.shared.add(source: "YouTube", title: link, link: link, status: .failed,
                                            detail: error.localizedDescription)
                    failedLinks += 1
                    continue
                }
                add("\(tag) \(AppText.Status.playlistFailedSingle)")
                jobs.append(YouTubeJob(videoID: single, title: ""))
            }
        }

        // 2. Download them, several at once if set in Settings
        tracker = BatchTracker(total: jobs.count)
        tracker.failed = failedLinks
        if !jobs.isEmpty && !Task.isCancelled {
            if o.atOnce > 1 && jobs.count > 1 { add(AppText.Status.atOnce(min(o.atOnce, jobs.count))) }
            let total = jobs.count, batch = jobs
            refreshProgress(total: total)
            await runLimited(batch, limit: o.atOnce) { index, job in
                await self.download(job, number: index, of: total, o)
            }
        }

        progress = 0
        status = Task.isCancelled ? AppText.Status.cancelledCount(tracker.done) : AppText.Status.finished(tracker.summary)
        LiveActivityController.shared.finish(source: "YouTube", summary: status,
                                             finished: tracker.finished, total: tracker.total)
        if o.notify && UIApplication.shared.applicationState != .active {
            notify(AppText.Status.downloadsFinished, status)
        }
    }

    /// One video: skip check, download, save, history.
    private func download(_ job: YouTubeJob, number index: Int, of total: Int, _ o: DownloadOptions) async {
        let label = total > 1 ? "[\(index + 1)/\(total)]" : "[1/1]"
        defer {
            tracker.active[index] = nil
            tracker.finished += 1
            refreshProgress(total: total)
        }
        if Task.isCancelled {
            HistoryStore.shared.add(source: "YouTube", title: job.title, link: job.link, status: .cancelled)
            return
        }

        // Skip before downloading anything when we already know the title
        if o.skipExisting, !job.title.isEmpty {
            let name = YouTubeService.fileName(job.title, ext: o.format.fileExtension)
            if FileManager.default.fileExists(atPath: Self.saveFolder.appendingPathComponent(name).path) {
                add("\(label) \(AppText.Status.skippedExists(name))")
                tracker.skipped += 1
                HistoryStore.shared.add(source: "YouTube", title: job.title, link: job.link, status: .skipped,
                                        detail: AppText.History.alreadyInFolder, fileName: name)
                return
            }
        }

        tracker.active[index] = (0, AppText.Status.starting)
        refreshProgress(total: total)
        do {
            let result = try await YouTubeService.download(
                videoID: job.videoID, format: o.format, maxHeight: o.maxHeight, mp3Bitrate: o.mp3Bitrate,
                skipExisting: o.skipExisting, embedArtwork: o.embedArtwork,
                into: Self.saveFolder
            ) { p, text in
                self.tracker.active[index] = (p, text)
                self.refreshProgress(total: total)
            }
            switch result {
            case .alreadyExists(let name):
                add("\(label) \(AppText.Status.skippedExists(name))")
                tracker.skipped += 1
                HistoryStore.shared.add(source: "YouTube", title: (name as NSString).deletingPathExtension,
                                        link: job.link, status: .skipped, detail: AppText.History.alreadyInFolder, fileName: name)
            case .saved(let file):
                add("\(label) \(AppText.Status.done(file.lastPathComponent))")
                tracker.done += 1
                HistoryStore.shared.add(source: "YouTube", title: file.deletingPathExtension().lastPathComponent,
                                        link: job.link, status: .done, fileName: file.lastPathComponent)
                if o.format == .video && o.saveVideosToPhotos {
                    do {
                        try await YouTubeService.saveToPhotos(file)
                        add("\(label) \(AppText.Status.addedToPhotos)")
                    } catch {
                        add("\(label) \(AppText.Status.photosFailed(error.localizedDescription))")
                    }
                }
            }
        } catch {
            if Task.isCancelled {
                add("\(label) \(AppText.Status.cancelled)")
                HistoryStore.shared.add(source: "YouTube", title: job.title, link: job.link, status: .cancelled)
            } else {
                add("\(label) \(AppText.Status.failed(error.localizedDescription))")
                tracker.failed += 1
                HistoryStore.shared.add(source: "YouTube", title: job.title, link: job.link, status: .failed,
                                        detail: error.localizedDescription)
            }
        }
    }

    private func refreshProgress(total: Int) {
        progress = tracker.overall
        status = tracker.status { "[\($0 + 1)/\(total)]" }
        LiveActivityController.shared.update(source: "YouTube", status: status, progress: progress,
                                             finished: tracker.finished, total: total)
    }

    private func askPlaylist(_ playlist: PlaylistInfo, url: URL) async -> [PlaylistItem]? {
        status = AppText.Status.waitingForPlaylist
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
        if isRunning && notifyEnabled && !BackgroundKeeper.shared.isKeepingAlive {
            notify(AppText.Status.downloadPaused, AppText.Status.openToContinue)
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
        BackgroundKeeper.shared.end()

        // Links shared while this batch ran
        if !queued.isEmpty {
            let next = queued
            queued.removeAll()
            start(links: next, DownloadOptions.saved(formatKey: "format", defaultFormat: .mp3))
        }
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
