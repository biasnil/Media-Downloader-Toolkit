import ActivityKit
import Foundation

/// Starts, updates and ends the download Live Activity.
/// If both tabs download at once, the Live Activity follows whichever updated last.
@MainActor
final class LiveActivityController {
    static let shared = LiveActivityController()

    private var activity: Activity<DownloadActivityAttributes>?
    private var states: [String: DownloadActivityAttributes.ContentState] = [:]
    private var lastSent: DownloadActivityAttributes.ContentState?
    private var lastSentAt = Date.distantPast

    private var enabled: Bool {
        (UserDefaults.standard.object(forKey: "liveActivity") as? Bool ?? true)
            && ActivityAuthorizationInfo().areActivitiesEnabled
    }

    func update(source: String, status: String, progress: Double, finished: Int, total: Int) {
        guard enabled else { return }
        let state = DownloadActivityAttributes.ContentState(
            source: source, status: status, progress: min(1, max(0, progress)),
            finished: finished, total: total, isComplete: false)
        states[source] = state

        // iOS limits how often a Live Activity can change, so send at most about once a second
        // unless something important changed (a file finished).
        let important = lastSent?.finished != finished || lastSent?.total != total
        guard important || Date().timeIntervalSince(lastSentAt) >= 1 else { return }
        send(state)
    }

    /// Call when a tab's batch is over. Ends the activity when no tab is downloading.
    func finish(source: String, summary: String, finished: Int, total: Int) {
        states[source] = nil
        if let other = states.values.first {
            send(other)
            return
        }
        guard let activity else { return }
        let final = DownloadActivityAttributes.ContentState(
            source: source, status: summary, progress: 1, finished: finished, total: total, isComplete: true)
        self.activity = nil
        lastSent = nil
        Task {
            // Leaves the summary on the Lock Screen for a few minutes, then it disappears
            await activity.end(ActivityContent(state: final, staleDate: nil),
                               dismissalPolicy: .after(Date().addingTimeInterval(5 * 60)))
        }
    }

    private func send(_ state: DownloadActivityAttributes.ContentState) {
        lastSent = state
        lastSentAt = Date()
        let content = ActivityContent(state: state, staleDate: nil)
        if let activity {
            Task { await activity.update(content) }
        } else {
            // Close any leftover one (e.g. the app was closed mid-download last time)
            for old in Activity<DownloadActivityAttributes>.activities {
                Task { await old.end(nil, dismissalPolicy: .immediate) }
            }
            activity = try? Activity.request(attributes: DownloadActivityAttributes(), content: content, pushType: nil)
        }
    }
}
