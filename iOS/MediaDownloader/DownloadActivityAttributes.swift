import ActivityKit
import Foundation

/// What the Lock Screen / Dynamic Island shows while downloading.
/// This file belongs to BOTH targets: the app and the DownloadWidgets extension.
/// nonisolated: ActivityKit uses this off the main thread.
nonisolated struct DownloadActivityAttributes: ActivityAttributes {
    nonisolated struct ContentState: Codable, Hashable {
        /// "YouTube" or "Other Sites"
        var source: String
        /// e.g. "[3/12] 45%" or "Downloading 3 at once · 5/12 finished"
        var status: String
        /// 0...1 for the whole batch
        var progress: Double
        var finished: Int
        var total: Int
        /// True once everything is done (the activity stays briefly with the summary).
        var isComplete: Bool
    }
}
