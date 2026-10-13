import Foundation
import Observation
import UIKit
import UserNotifications

/// One file from a post, waiting to download.
struct SiteJob: Sendable {
    let item: SiteMedia
    let link: String
    let site: String
}

/// A post with several photos/videos, waiting for the user to tick which ones they want.
@MainActor
final class MediaPrompt: Identifiable {
    let id = UUID()
    let title: String
    let items: [SiteMedia]
    private var continuation: CheckedContinuation<[SiteMedia]?, Never>?

    init(title: String, items: [SiteMedia], continuation: CheckedContinuation<[SiteMedia]?, Never>) {
        self.title = title
        self.items = items
        self.continuation = continuation
    }

    /// Answers exactly once, even if called again.
    func finish(_ picked: [SiteMedia]?) {
        continuation?.resume(returning: picked)
        continuation = nil
    }
}

/// Runs Other Sites downloads and holds what that tab shows.
@MainActor
@Observable
final class OtherSitesManager {
    var urlText = ""
    var status = AppText.Common.idle
    var progress: Double = 0
    var mediaPrompt: MediaPrompt?
    private(set) var log: [String] = []
    private(set) var isRunning = false
    /// Links shared from other apps while a download was running. They start right after.
    private(set) var queued: [String] = []

    private var job: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid
    private var tracker = BatchTracker(total: 0)

    /// The Download button: everything in the link box.
    func start(_ o: DownloadOptions) {
        let links = urlText.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !links.isEmpty else { status = AppText.Status.pasteLink; return }
        start(links: links, o)
    }

    /// A link shared from another app (or "Download again" in History).
    func enqueue(_ link: String) {
        if isRunning {
            queued.append(link)
            add(AppText.Status.queued(link))
        } else {
            add(AppText.Status.linkReceived)
            start(links: [link], DownloadOptions.saved(formatKey: "otherFormat", defaultFormat: .video))
        }
    }

    private func start(links: [String], _ o: DownloadOptions) {
        guard !isRunning else { return }
        isRunning = true
        progress = 0
        UIApplication.shared.isIdleTimerDisabled = true
        BackgroundKeeper.shared.begin()
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "other-sites") { [weak self] in
            MainActor.assumeIsolated { self?.endBackgroundTime() }
        }
        job = Task {
            await run(links, o)
            isRunning = false
            job = nil
            UIApplication.shared.isIdleTimerDisabled = false
            endBackgroundTime()
            BackgroundKeeper.shared.end()
            if !queued.isEmpty {
                let next = queued
                queued.removeAll()
                start(links: next, DownloadOptions.saved(formatKey: "otherFormat", defaultFormat: .video))
            }
        }
    }

    func cancel() {
        job?.cancel()
        queued.removeAll()
        answerMedia(nil)
        status = AppText.Common.cancelling
    }

    func answerMedia(_ picked: [SiteMedia]?) {
        mediaPrompt?.finish(picked)
        mediaPrompt = nil
    }

    func clearLog() { log.removeAll() }

    private func run(_ links: [String], _ o: DownloadOptions) async {
        // 1. Read every post first
        var jobs: [SiteJob] = []
        var failedLinks = 0
        for (i, link) in links.enumerated() {
            if Task.isCancelled { break }
            let tag = links.count > 1 ? AppText.Status.linkTag(i + 1, of: links.count) : AppText.Status.linkTagSingle
            add("\(tag) \(link)")
            guard let url = URL(string: link), let site = Sites.extractor(for: url) else {
                add("\(tag) \(AppText.Status.notSupported(Sites.supportedList.joined(separator: ", ")))")
                HistoryStore.shared.add(source: AppText.Main.otherSitesTab, title: link, link: link, status: .failed,
                                        detail: AppText.History.siteNotSupported)
                failedLinks += 1
                continue
            }

            status = "\(tag) \(AppText.Status.readingPost(site.name))"
            do {
                var items = try await site.extract(url, maxHeight: o.maxHeight)
                if items.count > 1 {
                    add("\(tag) \(AppText.Status.filesInPost(items.count))")
                    // Several photos/videos: let the user tick which ones
                    guard let picked = await askMedia(items, title: site.name) else {
                        if Task.isCancelled { break }
                        add("\(tag) \(AppText.Status.postSkipped)")
                        continue
                    }
                    add("\(tag) \(AppText.Status.picked(picked.count, of: items.count))")
                    items = picked
                }
                jobs += items.map { SiteJob(item: $0, link: link, site: site.name) }
            } catch {
                if Task.isCancelled { break }
                add("\(tag) \(AppText.Status.failed(error.localizedDescription))")
                HistoryStore.shared.add(source: site.name, title: link, link: link, status: .failed,
                                        detail: error.localizedDescription)
                failedLinks += 1
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
        LiveActivityController.shared.finish(source: AppText.Main.otherSitesTab, summary: status,
                                             finished: tracker.finished, total: tracker.total)
        if o.notify && UIApplication.shared.applicationState != .active {
            let content = UNMutableNotificationContent()
            content.title = AppText.Status.downloadsFinished
            content.body = status
            try? await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            )
        }
    }

    /// One file: skip check, download, save, history.
    private func download(_ job: SiteJob, number index: Int, of total: Int, _ o: DownloadOptions) async {
        let label = "[\(index + 1)/\(total)]"
        let item = job.item
        defer {
            tracker.active[index] = nil
            tracker.finished += 1
            refreshProgress(total: total)
        }
        if Task.isCancelled {
            HistoryStore.shared.add(source: job.site, title: item.title, link: job.link, status: .cancelled)
            return
        }

        // Pictures and audio files (e.g. a direct .mp3 link) keep their own format
        let ext: String
        if item.isImage {
            let own = item.videoURL.pathExtension.lowercased()
            ext = ["jpg", "jpeg", "png", "heic", "webp", "gif"].contains(own) ? own : "jpg"
        } else {
            ext = item.isAudioOnly ? item.videoURL.pathExtension.lowercased() : o.format.fileExtension
        }
        let name = YouTubeService.fileName(item.title, ext: ext)
        let destination = DownloadManager.saveFolder.appendingPathComponent(name)
        if o.skipExisting && FileManager.default.fileExists(atPath: destination.path) {
            add("\(label) \(AppText.Status.skippedExists(name))")
            tracker.skipped += 1
            HistoryStore.shared.add(source: job.site, title: item.title, link: job.link, status: .skipped,
                                    detail: AppText.History.alreadyInFolder, fileName: name)
            return
        }

        tracker.active[index] = (0, AppText.Status.starting)
        refreshProgress(total: total)
        do {
            try await save(item, as: o, to: destination) { p, text in
                self.tracker.active[index] = (p, text)
                self.refreshProgress(total: total)
            }
            add("\(label) \(AppText.Status.done(name))")
            tracker.done += 1
            HistoryStore.shared.add(source: job.site, title: item.title, link: job.link, status: .done, fileName: name)
            if o.saveVideosToPhotos && (item.isImage || (o.format == .video && !item.isAudioOnly)) {
                do {
                    try await YouTubeService.saveToPhotos(destination, isImage: item.isImage)
                    add("\(label) \(AppText.Status.addedToPhotos)")
                } catch {
                    add("\(label) \(AppText.Status.photosFailed(error.localizedDescription))")
                }
            }
        } catch {
            if Task.isCancelled {
                add("\(label) \(AppText.Status.cancelled)")
                HistoryStore.shared.add(source: job.site, title: item.title, link: job.link, status: .cancelled)
            } else {
                add("\(label) \(AppText.Status.failed(error.localizedDescription))")
                tracker.failed += 1
                HistoryStore.shared.add(source: job.site, title: item.title, link: job.link, status: .failed,
                                        detail: error.localizedDescription)
            }
        }
    }

    private func refreshProgress(total: Int) {
        progress = tracker.overall
        status = tracker.status { "[\($0 + 1)/\(total)]" }
        LiveActivityController.shared.update(source: AppText.Main.otherSitesTab, status: status, progress: progress,
                                             finished: tracker.finished, total: total)
    }

    /// Downloads the file(s) for one post and turns them into the chosen format.
    private func save(_ item: SiteMedia, as o: DownloadOptions, to destination: URL,
                      update: (Double, String) -> Void) async throws {
        if item.isAudioOnly || item.isImage {
            try await YouTubeService.fetch(item.videoURL, to: destination.appendingPathExtension("part")) {
                update($0, item.isImage ? AppText.Status.photoPercent(Int($0 * 100)) : "\(Int($0 * 100))%")
            }
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: destination.appendingPathExtension("part"), to: destination)
            return
        }
        if o.format != .video && item.silent {
            throw DownloadError.noStream(AppText.Errors.gifNoSound)
        }
        let work = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: work) }

        let video = work.appendingPathComponent("video.mp4")
        let audio = work.appendingPathComponent("audio.m4a")
        let output = work.appendingPathComponent("output.\(o.format.fileExtension)")
        let separateAudio = item.audioURL

        if o.format == .video {
            let share = separateAudio == nil ? 1.0 : 0.85
            try await YouTubeService.fetch(item.videoURL, to: video) { update($0 * share, AppText.Status.videoPercent(Int($0 * 100))) }
            if let separateAudio {
                try await YouTubeService.fetch(separateAudio, to: audio) { update(share + $0 * (1 - share), AppText.Status.audioPercent(Int($0 * 100))) }
                update(1, AppText.Status.merging)
                try await YouTubeService.mux(video: video, audio: audio, to: output, title: item.title, artwork: nil)
            } else {
                update(1, AppText.Status.saving)
                try FileManager.default.moveItem(at: video, to: output)
            }
        } else {
            // Get a plain .m4a of the sound first (Reddit: its audio file; X: pulled out of the video)
            let plain = work.appendingPathComponent("plain.m4a")
            if let separateAudio {
                try await YouTubeService.fetch(separateAudio, to: audio) { update($0 * 0.9, "\(Int($0 * 100))%") }
                try await YouTubeService.exportAudio(audio, to: plain, title: item.title, artwork: nil)
            } else {
                try await YouTubeService.fetch(item.videoURL, to: video) { update($0 * 0.9, "\(Int($0 * 100))%") }
                try await YouTubeService.extractAudio(from: video, to: plain, title: item.title, artwork: nil)
            }
            if o.format == .mp3 {
                update(0.9, AppText.Status.convertingMP3)
                try await YouTubeService.encodeMP3(plain, to: output, bitrate: o.mp3Bitrate, title: item.title, artwork: nil)
            } else {
                try FileManager.default.moveItem(at: plain, to: output)
            }
        }

        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: output, to: destination)
    }

    private func askMedia(_ items: [SiteMedia], title: String) async -> [SiteMedia]? {
        status = AppText.Status.waitingForPick
        return await withCheckedContinuation { continuation in
            mediaPrompt = MediaPrompt(title: title, items: items, continuation: continuation)
        }
    }

    private func add(_ line: String) {
        log.append(line)
        if log.count > 500 { log.removeFirst(log.count - 500) }
    }

    private func endBackgroundTime() {
        if backgroundTask != .invalid {
            UIApplication.shared.endBackgroundTask(backgroundTask)
            backgroundTask = .invalid
        }
    }
}
