import SwiftUI

struct ContentView: View {
    @State private var manager = DownloadManager()
    @State private var otherSites = OtherSitesManager()
    @State private var tab = 0
    @State private var showSettings = false
    @AppStorage("theme") private var theme = "system"
    @Environment(\.colorScheme) private var colorScheme

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
        .sheet(item: $manager.playlistPrompt) { prompt in
            PlaylistSheet(prompt: prompt) { manager.answerPlaylist($0) }
                .interactiveDismissDisabled() // use Skip / Download, so the download loop always gets an answer
        }
    }

    /// Pill-style tab switcher, like the one under the desktop header.
    private var tabBar: some View {
        HStack(spacing: 0) {
            ForEach(Array(["YouTube", "Other Sites"].enumerated()), id: \.offset) { i, name in
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
            Text("▶  Media Downloader")
                .font(.headline.bold())
                .foregroundStyle(.white)
            Spacer()
            Button { showSettings = true } label: {
                Image(systemName: "gearshape.fill")
                    .font(.title3)
                    .foregroundStyle(.white)
            }
            Text("Dark")
                .font(.footnote)
                .foregroundStyle(.white)
            Toggle("Dark mode", isOn: Binding(get: { isDark }, set: { theme = $0 ? "dark" : "light" }))
                .labelsHidden()
                .tint(Color(white: 0.15))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .background(Color.ytRed.ignoresSafeArea(edges: .top))
    }
}
