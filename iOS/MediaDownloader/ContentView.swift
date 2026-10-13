import SwiftUI

struct ContentView: View {
    @State private var manager = DownloadManager()
    @State private var otherSites = OtherSitesManager()
    @State private var tab = 0
    @State private var showSettings = false
    @State private var showHistory = false
    @State private var clipboard = ClipboardWatcher()
    @State private var showTerms = Terms.needsAgreement
    /// A shared link that arrived before the Terms were agreed to.
    @State private var pendingLink: String?
    @AppStorage("theme") private var theme = "system"
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase

    private var isDark: Bool { theme == "dark" || (theme == "system" && colorScheme == .dark) }

    var body: some View {
        VStack(spacing: 0) {
            header
            tabBar
            // Both stay alive, so a download keeps going when you switch tabs
            ZStack {
                YouTubeView(manager: manager).opacity(tab == 0 ? 1 : 0).allowsHitTesting(tab == 0)
                OtherSitesView(manager: otherSites).opacity(tab == 1 ? 1 : 0).allowsHitTesting(tab == 1)
            }
        }
        .background(Color.appBackground)
        .sheet(isPresented: $showSettings) { SettingsView() }
        .sheet(isPresented: $showHistory) {
            HistoryView { link in startDownload(of: link) }
        }
        // Links from the Share button: mediadownloader://download?url=...
        .onOpenURL { url in
            guard url.scheme == "mediadownloader",
                  let link = URLComponents(url: url, resolvingAgainstBaseURL: false)?
                      .queryItems?.first(where: { $0.name == "url" })?.value,
                  !link.isEmpty else { return }
            showSettings = false
            showHistory = false
            clipboard.foundLink = nil
            if Terms.needsAgreement {
                pendingLink = link
                showTerms = true
            } else {
                startDownload(of: link)
            }
        }
        // Copied YouTube link: ask when the app comes to the front
        .onChange(of: scenePhase, initial: true) { _, phase in
            guard phase == .active else { return }
            if Terms.needsAgreement {
                showTerms = true // every 10 days
                return
            }
            guard !showTerms, !showSettings, !showHistory,
                  manager.playlistPrompt == nil, otherSites.mediaPrompt == nil else { return }
            Task { await clipboard.check() }
        }
        .alert(AppText.Main.clipboardQuestion,
               isPresented: Binding(get: { clipboard.foundLink != nil },
                                    set: { if !$0 { clipboard.foundLink = nil } }),
               presenting: clipboard.foundLink) { link in
            Button(AppText.Common.download) {
                clipboard.foundLink = nil
                startDownload(of: link)
            }
            Button(AppText.Main.dontAskToday) { clipboard.snoozeForToday() }
            Button(AppText.Main.notNow, role: .cancel) {}
        } message: { link in
            Text(link)
        }
        .sheet(item: $manager.playlistPrompt) { prompt in
            PlaylistSheet(prompt: prompt) { manager.answerPlaylist($0) }
                .interactiveDismissDisabled() // use Skip / Download, so the download loop always gets an answer
        }
        .fullScreenCover(isPresented: $showTerms) {
            TermsView {
                Terms.agree()
                showTerms = false
                if let link = pendingLink {
                    pendingLink = nil
                    startDownload(of: link)
                } else {
                    Task { await clipboard.check() }
                }
            }
        }
        .sheet(item: $otherSites.mediaPrompt) { prompt in
            MediaPickerSheet(prompt: prompt) { otherSites.answerMedia($0) }
                .interactiveDismissDisabled()
        }
    }

    /// Sends a link to the right tab and starts it (or queues it if that tab is busy).
    private func startDownload(of link: String) {
        let host = (URL(string: link)?.host ?? "").lowercased()
        let isYouTube = host.hasSuffix("youtube.com") || host == "youtu.be" || host.hasSuffix("youtube-nocookie.com")
        if isYouTube {
            tab = 0
            manager.enqueue(link)
        } else {
            tab = 1
            otherSites.enqueue(link)
        }
    }

    /// Pill-style tab switcher, like the one under the desktop header.
    private var tabBar: some View {
        HStack(spacing: 0) {
            ForEach(Array(["YouTube", AppText.Main.otherSitesTab].enumerated()), id: \.offset) { i, name in
                Button { tab = i } label: {
                    Text(name)
                        .font(.subheadline.weight(tab == i ? .bold : .regular))
                        .foregroundStyle(tab == i ? Color.white : Color.primary)
                        .padding(.horizontal, 16)
                        .padding(.vertical, 6)
                        .background(tab == i ? Color.ytRed : Color.clear, in: RoundedRectangle(cornerRadius: 6))
                }
            }
        }
        .padding(3)
        .background(Color.surface, in: RoundedRectangle(cornerRadius: 8))
        .padding(.top, 10)
    }

    /// Red header like desktop: title, gear for settings, and the dark mode switch.
    private var header: some View {
        HStack(spacing: 10) {
            Text(verbatim: "▶  Media Downloader")
                .font(.headline.bold())
                .foregroundStyle(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Spacer()
            Button { showHistory = true } label: {
                Image(systemName: "clock.arrow.circlepath")
                    .font(.title3)
                    .foregroundStyle(.white)
            }
            Button { showSettings = true } label: {
                Image(systemName: "gearshape.fill")
                    .font(.title3)
                    .foregroundStyle(.white)
            }
            Text(AppText.Main.dark)
                .font(.footnote)
                .foregroundStyle(.white)
            Toggle(AppText.Main.darkMode, isOn: Binding(get: { isDark }, set: { theme = $0 ? "dark" : "light" }))
                .labelsHidden()
                .tint(Color(white: 0.15))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.ytRed.ignoresSafeArea(edges: .top))
    }
}
