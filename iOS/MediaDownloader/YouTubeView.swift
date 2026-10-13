import SwiftUI
import UIKit

let mp3Bitrates = [320, 256, 192, 128]

var videoQualities: [(label: String, height: Int)] {
    [(AppText.Common.bestAvailable, 0), ("1080p", 1080), ("720p", 720), ("480p", 480), ("360p", 360)]
}

struct YouTubeView: View {
    @Bindable var manager: DownloadManager

    // Remembered between launches, like config.json on desktop
    @AppStorage("format") private var format: MediaFormat = .mp3
    @AppStorage("mp3Bitrate") private var mp3Bitrate = 320
    @AppStorage("videoQuality") private var videoQuality = 0
    @AppStorage("skipExisting") private var skipExisting = true
    @AppStorage("embedArtwork") private var embedArtwork = true
    @AppStorage("saveVideosToPhotos") private var saveVideosToPhotos = false
    @AppStorage("notify") private var notify = true

    @State private var report: ProblemReport?

    private var busy: Bool { manager.isRunning }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                urlSection
                optionsSection
                buttons
                progressSection
                logSection
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .reportProblem($report)
        .background(Color.appBackground)
    }

    // MARK: Sections

    private var urlSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                SectionLabel(AppText.YouTube.linksLabel)
                Spacer()
                Button(AppText.Common.paste) {
                    if let text = UIPasteboard.general.string {
                        manager.urlText += (manager.urlText.isEmpty || manager.urlText.hasSuffix("\n") ? "" : "\n") + text
                    }
                }
                .font(.footnote.bold())
                .disabled(busy)
            }
            ZStack(alignment: .topLeading) {
                TextEditor(text: $manager.urlText)
                    .font(.callout)
                    .scrollContentBackground(.hidden)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .disabled(busy)
                if manager.urlText.isEmpty {
                    Text(verbatim: "https://youtu.be/...")
                        .font(.callout)
                        .foregroundStyle(Color.muted)
                        .padding(.top, 8)
                        .padding(.leading, 5)
                        .allowsHitTesting(false)
                }
            }
            .padding(6)
            .frame(height: 120)
            .background(Color.surface, in: RoundedRectangle(cornerRadius: 8))
            .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.outline))
        }
    }

    private var optionsSection: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(AppText.Common.savesTo)
                .font(.footnote)
                .foregroundStyle(Color.muted)

            HStack {
                Text(AppText.Common.formatLabel)
                Picker(AppText.Common.format, selection: $format) {
                    ForEach(MediaFormat.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.menu)
                .disabled(busy)
            }
            HStack {
                Text(AppText.Common.qualityLabel)
                if format == .video {
                    Picker(AppText.Common.quality, selection: $videoQuality) {
                        ForEach(videoQualities, id: \.height) { Text($0.label).tag($0.height) }
                    }
                    .pickerStyle(.menu)
                    .disabled(busy)
                } else if format == .mp3 {
                    Picker(AppText.Common.quality, selection: $mp3Bitrate) {
                        ForEach(mp3Bitrates, id: \.self) { Text(AppText.Common.kbps($0)).tag($0) }
                    }
                    .pickerStyle(.menu)
                    .disabled(busy)
                } else {
                    Text(AppText.YouTube.bestAAC)
                        .foregroundStyle(Color.muted)
                }
            }
            Toggle(AppText.Common.skipExisting, isOn: $skipExisting)
                .disabled(busy)
        }
    }

    private var buttons: some View {
        VStack(spacing: 8) {
            Button {
                manager.start(DownloadOptions(
                    format: format,
                    maxHeight: videoQuality == 0 ? nil : videoQuality,
                    mp3Bitrate: mp3Bitrate,
                    skipExisting: skipExisting,
                    embedArtwork: embedArtwork,
                    saveVideosToPhotos: saveVideosToPhotos,
                    notify: notify
                ))
            } label: {
                Text(busy ? AppText.Common.downloading : AppText.Common.download)
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .foregroundStyle(.white)
                    .background(Color.ytRed.opacity(busy ? 0.5 : 1), in: RoundedRectangle(cornerRadius: 8))
            }
            .disabled(busy)

            HStack(spacing: 8) {
                SecondaryButton(AppText.Common.cancel, enabled: busy) { manager.cancel() }
                SecondaryButton(AppText.Common.openFolder) { openFolder() }
            }
        }
    }

    private var progressSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            ProgressView(value: manager.progress)
                .tint(.ytRed)
            Text(manager.status)
                .font(.footnote)
                .foregroundStyle(Color.muted)
        }
    }

    private var logSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                SectionLabel(AppText.Common.log)
                Spacer()
                Button(AppText.Report.short) {
                    // The last lines of the log show what went wrong
                    report = ProblemReport(context: manager.log.suffix(30).joined(separator: "\n"))
                }
                .font(.footnote.bold())
                .padding(.trailing, 8)
                Button(AppText.Common.clear) { manager.clearLog() }
                    .font(.footnote.bold())
                    .disabled(busy || manager.log.isEmpty)
            }
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(manager.log.enumerated()), id: \.offset) { index, line in
                            Text(line)
                                .font(.system(size: 12, design: .monospaced))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .id(index)
                        }
                    }
                    .padding(10)
                }
                .frame(height: 220)
                .background(Color.surface, in: RoundedRectangle(cornerRadius: 8))
                .onChange(of: manager.log.count) { _, count in
                    withAnimation { proxy.scrollTo(count - 1, anchor: .bottom) }
                }
            }
        }
    }

    /// Opens this app's folder in the Files app.
    private func openFolder() {
        if let url = URL(string: "shareddocuments://" + DownloadManager.saveFolder.path) {
            UIApplication.shared.open(url)
        }
    }
}

// MARK: - Small shared pieces

struct SectionLabel: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.subheadline.bold())
    }
}

/// ⓘ button: tap to read a longer explanation in a small bubble.
struct InfoButton: View {
    let text: String
    @State private var shown = false
    init(_ text: String) { self.text = text }

    var body: some View {
        Button { shown = true } label: {
            Image(systemName: "info.circle")
        }
        .buttonStyle(.borderless)
        .popover(isPresented: $shown) {
            Text(text)
                .font(.footnote)
                .fixedSize(horizontal: false, vertical: true)
                .frame(width: 280, alignment: .leading)
                .padding()
                .presentationCompactAdaptation(.popover)
        }
    }
}

/// Settings section title with an ⓘ at the end.
struct InfoHeader: View {
    let title: String
    let info: String
    init(_ title: String, info: String) {
        self.title = title
        self.info = info
    }

    var body: some View {
        HStack {
            Text(title)
            Spacer()
            InfoButton(info)
                .textCase(nil)
                .font(.body)
        }
    }
}

struct SecondaryButton: View {
    let title: String
    var enabled = true
    let action: () -> Void

    init(_ title: String, enabled: Bool = true, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .frame(maxWidth: .infinity)
                .padding(.vertical, 10)
                .foregroundStyle(enabled ? Color.primary : Color.muted)
                .overlay(RoundedRectangle(cornerRadius: 8).stroke(Color.outline))
        }
        .disabled(!enabled)
    }
}
