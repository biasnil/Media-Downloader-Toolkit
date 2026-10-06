import SwiftUI
import UIKit

/// Other Sites tab: Instagram, Facebook, Reddit, X and more. Same layout as the YouTube tab.
struct OtherSitesView: View {
    @Bindable var manager: OtherSitesManager

    // Its own format choice, so it doesn't change the YouTube tab's
    @AppStorage("otherFormat") private var format: MediaFormat = .video
    @AppStorage("mp3Bitrate") private var mp3Bitrate = 320
    @AppStorage("videoQuality") private var videoQuality = 0
    @AppStorage("skipExisting") private var skipExisting = true
    @AppStorage("saveVideosToPhotos") private var saveVideosToPhotos = false
    @AppStorage("notify") private var notify = true

    private var busy: Bool { manager.isRunning }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                urlSection
                optionsSection
                buttons
                VStack(alignment: .leading, spacing: 6) {
                    ProgressView(value: manager.progress).tint(.ytRed)
                    Text(manager.status)
                        .font(.footnote)
                        .foregroundStyle(Color.muted)
                }
                logSection
            }
            .padding(16)
        }
        .scrollDismissesKeyboard(.interactively)
        .background(Color.appBackground)
    }

    private var urlSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                SectionLabel("Link(s), one per line")
                Spacer()
                Menu {
                    Section("Supported sites") {
                        ForEach(Sites.supportedList, id: \.self) { Text($0) }
                    }
                } label: {
                    Label("Sites", systemImage: "chevron.down.circle")
                        .font(.footnote.bold())
                }
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
                    Text("Instagram, Facebook, Reddit, X, Streamable,\nImgur, archive.org, direct .mp4 links...")
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
            Text("Sites marked (login) need a one-time login in Settings → Accounts. Saves to Files → On My iPhone → Media Downloader")
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
                switch format {
                case .video:
                    Picker("Quality", selection: $videoQuality) {
                        ForEach(videoQualities, id: \.height) { Text($0.label).tag($0.height) }
                    }
                    .pickerStyle(.menu)
                    .disabled(busy)
                case .mp3:
                    Picker("Quality", selection: $mp3Bitrate) {
                        ForEach(mp3Bitrates, id: \.self) { Text("\($0) kbps").tag($0) }
                    }
                    .pickerStyle(.menu)
                    .disabled(busy)
                case .audio:
                    Text("Original (AAC)").foregroundStyle(Color.muted)
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
                    embedArtwork: false,
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
                SecondaryButton("Open Folder") {
                    if let url = URL(string: "shareddocuments://" + DownloadManager.saveFolder.path) {
                        UIApplication.shared.open(url)
                    }
                }
            }
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
}
