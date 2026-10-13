import ActivityKit
import SwiftUI
import WidgetKit

private let ytRed = Color(red: 1, green: 0, blue: 0)

/// Lock Screen banner + Dynamic Island for downloads.
struct DownloadLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: DownloadActivityAttributes.self) { context in
            LockScreenView(state: context.state)
                .activityBackgroundTint(Color.black.opacity(0.85))
                .activitySystemActionForegroundColor(.white)
        } dynamicIsland: { context in
            let s = context.state
            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    Label(s.source, systemImage: icon(s))
                        .font(.caption.bold())
                        .foregroundStyle(ytRed)
                }
                DynamicIslandExpandedRegion(.trailing) {
                    Text("\(s.finished)/\(s.total)")
                        .font(.caption.monospacedDigit())
                        .foregroundStyle(.secondary)
                }
                DynamicIslandExpandedRegion(.bottom) {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(s.status)
                            .font(.caption)
                            .lineLimit(1)
                        Bar(progress: s.progress)
                    }
                }
            } compactLeading: {
                Image(systemName: icon(s))
                    .foregroundStyle(ytRed)
            } compactTrailing: {
                Ring(progress: s.isComplete ? 1 : s.progress)
            } minimal: {
                Ring(progress: s.isComplete ? 1 : s.progress)
            }
            .keylineTint(ytRed)
        }
    }
}

private func icon(_ s: DownloadActivityAttributes.ContentState) -> String {
    s.isComplete ? "checkmark.circle.fill" : "arrow.down.circle.fill"
}

private struct LockScreenView: View {
    let state: DownloadActivityAttributes.ContentState

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Image(systemName: icon(state))
                    .foregroundStyle(ytRed)
                (state.isComplete ? Text("Downloads finished") : Text("Downloading · \(state.source)"))
                    .font(.subheadline.bold())
                    .foregroundStyle(.white)
                Spacer()
                Text("\(state.finished)/\(state.total)")
                    .font(.subheadline.monospacedDigit())
                    .foregroundStyle(.white.opacity(0.7))
            }
            Bar(progress: state.progress)
            Text(state.status)
                .font(.caption)
                .foregroundStyle(.white.opacity(0.8))
                .lineLimit(1)
        }
        .padding(16)
    }
}

/// A plain red progress bar (drawn by hand so it always shows on the Lock Screen).
private struct Bar: View {
    let progress: Double

    var body: some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(Color.white.opacity(0.2))
                Capsule().fill(ytRed)
                    .frame(width: max(6, geo.size.width * min(1, max(0, progress))))
            }
        }
        .frame(height: 6)
    }
}

/// Small progress ring for the Dynamic Island.
private struct Ring: View {
    let progress: Double

    var body: some View {
        ZStack {
            Circle().stroke(Color.white.opacity(0.25), lineWidth: 3)
            Circle()
                .trim(from: 0, to: min(1, max(0.02, progress)))
                .stroke(ytRed, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                .rotationEffect(.degrees(-90))
        }
        .frame(width: 16, height: 16)
    }
}
