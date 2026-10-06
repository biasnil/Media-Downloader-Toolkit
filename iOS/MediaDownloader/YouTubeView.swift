import SwiftUI
import UIKit

let mp3Bitrates = [320, 256, 192, 128]

let videoQualities: [(label: String, height: Int)] = [
    ("Best available", 0), ("1080p", 1080), ("720p", 720), ("480p", 480), ("360p", 360),
]

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
        .background(Color.appBackground)
    }

    // MARK: Sections

    private var urlSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                SectionLabel("YouTube URL(s), one per line")
                Spacer()
                Button("Paste") {
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
                    Text("https://youtu.be/...")
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
            Text("Saves to Files → On My iPhone → Media Downloader")
                .font(.footnote)
                .foregroundStyle(Color.muted)

            HStack {
                Text("Format:")
                Picker("Format", selection: $format) {
                    ForEach(MediaFormat.allCases) { Text($0.label).tag($0) }
                }
                .pickerStyle(.menu)
                .disabled(busy)
            }
            HStack {
                Text("Quality:")
                if format == .video {
                    Picker("Quality", selection: $videoQuality) {
                        ForEach(videoQualities, id: \.height) { Text($0.label).tag($0.height) }
                    }
                    .pickerStyle(.menu)
                    .disabled(busy)
                } else if format == .mp3 {
                    Picker("Quality", selection: $mp3Bitrate) {
                        ForEach(mp3Bitrates, id: \.self) { Text("\($0) kbps").tag($0) }
                    }
                    .pickerStyle(.menu)
                    .disabled(busy)
                } else {
                    Text("Best available (AAC)")
                        .foregroundStyle(Color.muted)
                }
            }
            Toggle("Skip files that already exist", isOn: $skipExisting)
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
                Text(busy ? "Downloading..." : "Download")
                    .font(.headline)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 12)
                    .foregroundStyle(.white)
                    .background(Color.ytRed.opacity(busy ? 0.5 : 1), in: RoundedRectangle(cornerRadius: 8))
            }
            .disabled(busy)

            HStack(spacing: 8) {
                SecondaryButton("Cancel", enabled: busy) { manager.cancel() }
                SecondaryButton("Open Folder") { openFolder() }
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
                SectionLabel("Log")
                Spacer()
                Button("Clear") { manager.clearLog() }
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
