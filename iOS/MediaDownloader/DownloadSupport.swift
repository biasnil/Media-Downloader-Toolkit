import Foundation

/// Settings captured when Download is pressed, so changing them mid-download can't mix things up.
struct DownloadOptions {
    var format: MediaFormat
    var maxHeight: Int?
    var mp3Bitrate: Int
    var skipExisting: Bool
    var embedArtwork: Bool
    var saveVideosToPhotos: Bool
    var notify: Bool
    /// How many files download at the same time (Settings → Downloads → At once).
    var atOnce: Int = DownloadOptions.savedAtOnce

    static var savedAtOnce: Int {
        min(5, max(1, UserDefaults.standard.object(forKey: "maxConcurrent") as? Int ?? 1))
    }

    /// The options the tab's screen currently shows. Used for links shared from other apps.
    /// [formatKey] is "format" for the YouTube tab, "otherFormat" for Other Sites.
    static func saved(formatKey: String, defaultFormat: MediaFormat) -> DownloadOptions {
        let d = UserDefaults.standard
        func bool(_ key: String, _ fallback: Bool) -> Bool { d.object(forKey: key) as? Bool ?? fallback }
        func int(_ key: String, _ fallback: Int) -> Int { d.object(forKey: key) as? Int ?? fallback }
        let quality = int("videoQuality", 0)
        return DownloadOptions(
            format: MediaFormat(rawValue: d.string(forKey: formatKey) ?? "") ?? defaultFormat,
            maxHeight: quality == 0 ? nil : quality,
            mp3Bitrate: int("mp3Bitrate", 320),
            skipExisting: bool("skipExisting", true),
            embedArtwork: bool("embedArtwork", true),
            saveVideosToPhotos: bool("saveVideosToPhotos", false),
            notify: bool("notify", true)
        )
    }
}

/// Runs [work] for every job, at most [limit] at a time. Stops starting new ones when cancelled.
@MainActor
func runLimited<Job: Sendable>(_ jobs: [Job], limit: Int, _ work: @escaping @MainActor (Int, Job) async -> Void) async {
    await withTaskGroup(of: Void.self) { group in
        var next = 0
        while next < min(max(1, limit), jobs.count) {
            let index = next, job = jobs[index]
            group.addTask { await work(index, job) }
            next += 1
        }
        while await group.next() != nil {
            guard next < jobs.count, !Task.isCancelled else { continue }
            let index = next, job = jobs[index]
            group.addTask { await work(index, job) }
            next += 1
        }
    }
}

/// Combines the progress of several files downloading at once into one bar and one status line.
struct BatchTracker {
    let total: Int
    var finished = 0
    var done = 0, skipped = 0, failed = 0
    /// Files in progress: job number → (fraction, what it's doing)
    var active: [Int: (fraction: Double, text: String)] = [:]

    var overall: Double {
        guard total > 0 else { return 0 }
        return (Double(finished) + active.values.reduce(0) { $0 + $1.fraction }) / Double(total)
    }

    func status(label: (Int) -> String) -> String {
        if active.count == 1, let only = active.first {
            return "\(label(only.key)) \(only.value.text)"
        }
        if active.count > 1 {
            return AppText.Status.atOnceProgress(active.count, finished, total)
        }
        return AppText.Status.finishedCount(finished, total)
    }

    var summary: String {
        AppText.Status.summary(done: done, skipped: skipped, failed: failed)
    }
}

/// Keeps total download speed under Settings → Downloads → Speed limit, across all files at once.
actor SpeedLimiter {
    static let shared = SpeedLimiter()
    private var nextFree = ContinuousClock.now

    /// 0 = unlimited.
    nonisolated static var bytesPerSecond: Int {
        (UserDefaults.standard.object(forKey: "speedLimitKB") as? Int ?? 0) * 1024
    }

    /// Smaller pieces when limited, so the speed stays smooth instead of bursting.
    nonisolated static var chunkSize: Int {
        let limit = bytesPerSecond
        return limit == 0 ? 10 * 1024 * 1024 : max(64 * 1024, limit / 2)
    }

    /// Call after receiving [bytes]; waits as long as needed to stay under the limit.
    func consume(_ bytes: Int) async {
        let limit = Self.bytesPerSecond
        let now = ContinuousClock.now
        guard limit > 0 else { nextFree = now; return }
        if nextFree < now { nextFree = now }
        nextFree = nextFree + .seconds(Double(bytes) / Double(limit))
        let wait = nextFree - now
        if wait > .zero { try? await Task.sleep(for: wait) }
    }
}
