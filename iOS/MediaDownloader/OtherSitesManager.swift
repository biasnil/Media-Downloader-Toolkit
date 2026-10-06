import Foundation
import Observation
import UIKit
import UserNotifications

/// Runs Other Sites downloads and holds what that tab shows.
@MainActor
@Observable
final class OtherSitesManager {
    var urlText = ""
    var status = "Idle."
    var progress: Double = 0
    private(set) var log: [String] = []
    private(set) var isRunning = false

    private var job: Task<Void, Never>?
    private var backgroundTask: UIBackgroundTaskIdentifier = .invalid

    func start(_ o: DownloadOptions) {
        let links = urlText.split(whereSeparator: \.isNewline)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
        guard !links.isEmpty else { status = "Paste at least one link."; return }
        guard !isRunning else { return }

        isRunning = true
        progress = 0
        UIApplication.shared.isIdleTimerDisabled = true
        backgroundTask = UIApplication.shared.beginBackgroundTask(withName: "other-sites") { [weak self] in
            MainActor.assumeIsolated { self?.endBackgroundTime() }
        }
        job = Task {
            await run(links, o)
            isRunning = false
            job = nil
            UIApplication.shared.isIdleTimerDisabled = false
            endBackgroundTime()
        }
    }

    func cancel() {
        job?.cancel()
        status = "Cancelling..."
    }

    func clearLog() { log.removeAll() }

    private func run(_ links: [String], _ o: DownloadOptions) async {
        var done = 0, skipped = 0, failed = 0
        for (i, link) in links.enumerated() {
            if Task.isCancelled { break }
            let tag = "[\(i + 1)/\(links.count)]"
            add("\(tag) \(link)")
            guard let url = URL(string: link), let site = Sites.extractor(for: url) else {
                add("\(tag) Not supported yet. Works with: \(Sites.all.map { $0.name }.joined(separator: ", "))")
                failed += 1
                continue
            }

            status = "\(tag) Reading \(site.name) post..."
            let items: [SiteMedia]
            do {
                items = try await site.extract(url, maxHeight: o.maxHeight)
            } catch {
                if Task.isCancelled { break }
                add("\(tag) Failed: \(error.localizedDescription)")
                failed += 1
                continue
            }

            for (j, item) in items.enumerated() {
                if Task.isCancelled { break }
                let label = items.count > 1 ? "\(tag) \(j + 1)/\(items.count)" : tag
                // Audio files (e.g. a direct .mp3 link) keep their own format
                let ext = item.isAudioOnly ? item.videoURL.pathExtension.lowercased() : o.format.fileExtension
                let name = YouTubeService.fileName(item.title, ext: ext)
                let destination = DownloadManager.saveFolder.appendingPathComponent(name)
                if o.skipExisting && FileManager.default.fileExists(atPath: destination.path) {
                    add("\(label) Skipped (already exists): \(name)")
                    skipped += 1
                    continue
                }
                progress = 0
                do {
                    try await save(item, as: o, to: destination) { p, text in
                        self.progress = p
                        self.status = "\(label) \(text)"
                    }
                    add("\(label) Done: \(name)")
                    done += 1
                    if o.format == .video && !item.isAudioOnly && o.saveVideosToPhotos {
                        do {
                            try await YouTubeService.saveToPhotos(destination)
                            add("\(label) Added to Photos")
                        } catch {
                            add("\(label) Couldn't add to Photos: \(error.localizedDescription)")
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
            let content = UNMutableNotificationContent()
            content.title = "Downloads finished"
            content.body = status
            try? await UNUserNotificationCenter.current().add(
                UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
            )
        }
    }

    /// Downloads the file(s) for one post and turns them into the chosen format.
    private func save(_ item: SiteMedia, as o: DownloadOptions, to destination: URL,
                      update: (Double, String) -> Void) async throws {
        if item.isAudioOnly {
            try await YouTubeService.fetch(item.videoURL, to: destination.appendingPathExtension("part")) {
                update($0, "\(Int($0 * 100))%")
            }
            try? FileManager.default.removeItem(at: destination)
            try FileManager.default.moveItem(at: destination.appendingPathExtension("part"), to: destination)
            return
        }
        if o.format != .video && item.silent {
            throw DownloadError.noStream("This one has no sound (it's a GIF), so there's no audio to save")
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
            try await YouTubeService.fetch(item.videoURL, to: video) { update($0 * share, "Video \(Int($0 * 100))%") }
            if let separateAudio {
                try await YouTubeService.fetch(separateAudio, to: audio) { update(share + $0 * (1 - share), "Audio \(Int($0 * 100))%") }
                update(1, "Merging video and audio...")
                try await YouTubeService.mux(video: video, audio: audio, to: output, title: item.title, artwork: nil)
            } else {
                update(1, "Saving...")
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
                update(0.9, "Converting to MP3...")
                try await YouTubeService.encodeMP3(plain, to: output, bitrate: o.mp3Bitrate, title: item.title, artwork: nil)
            } else {
                try FileManager.default.moveItem(at: plain, to: output)
            }
        }

        try? FileManager.default.removeItem(at: destination)
        try FileManager.default.moveItem(at: output, to: destination)
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
