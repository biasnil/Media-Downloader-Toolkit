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

    @State private var report: ProblemReport?

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
        .reportProblem($report)
        .background(Color.appBackground)
    }

    private var urlSection: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                SectionLabel(AppText.OtherSites.linksLabel)
                Spacer()
                Menu {
                    Section(AppText.OtherSites.supportedSites) {
                        ForEach(Sites.supportedList, id: \.self) { Text($0) }
                    }
                } label: {
                    Label(AppText.OtherSites.sites, systemImage: "chevron.down.circle")
                        .font(.footnote.bold())
                }
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
                    Text(AppText.OtherSites.placeholder)
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
            HStack(alignment: .firstTextBaseline) {
                Text(AppText.Common.savesTo)
                    .font(.footnote)
                    .foregroundStyle(Color.muted)
                InfoButton(AppText.OtherSites.loginInfo)
                    .font(.footnote)
            }
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
                switch format {
                case .video:
                    Picker(AppText.Common.quality, selection: $videoQuality) {
                        ForEach(videoQualities, id: \.height) { Text($0.label).tag($0.height) }
                    }
                    .pickerStyle(.menu)
                    .disabled(busy)
                case .mp3:
                    Picker(AppText.Common.quality, selection: $mp3Bitrate) {
                        ForEach(mp3Bitrates, id: \.self) { Text(AppText.Common.kbps($0)).tag($0) }
                    }
                    .pickerStyle(.menu)
                    .disabled(busy)
                case .audio:
                    Text(AppText.OtherSites.originalAAC).foregroundStyle(Color.muted)
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
                    embedArtwork: false,
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
                SecondaryButton(AppText.Common.openFolder) {
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
}
