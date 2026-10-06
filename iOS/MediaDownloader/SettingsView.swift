import SwiftUI

/// iPhone version of desktop's gear-icon settings window. Changes save instantly.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("embedArtwork") private var embedArtwork = true
    @AppStorage("saveVideosToPhotos") private var saveVideosToPhotos = false
    @AppStorage("notify") private var notify = true
    @AppStorage("theme") private var theme = "system"

    var body: some View {
        NavigationStack {
            Form {
                Section("Thumbnail") {
                    Toggle("Embed thumbnail as cover art", isOn: $embedArtwork)
                }

                Section {
                    Toggle("Also save videos to Photos", isOn: $saveVideosToPhotos)
                } header: {
                    Text("Videos")
                } footer: {
                    Text("Videos are always saved in Files → On My iPhone → Media Downloader as well.")
                }

                Section {
                    Toggle("Notifications", isOn: $notify)
                } header: {
                    Text("Notifications")
                } footer: {
                    Text("iPhone pauses apps about 30 seconds after you leave them. You'll get a notification when a download is paused this way, and when downloads finish. The screen stays on while downloading, and a paused download continues when you come back.")
                }

                AccountsSection()

                Section("Theme") {
                    Picker("Theme", selection: $theme) {
                        Text("Follow phone").tag("system")
                        Text("Light").tag("light")
                        Text("Dark").tag("dark")
                    }
                    .pickerStyle(.segmented)
                }

                Section("Not available on iPhone") {
                    Text("Subtitles, TikTok and sites not in the Other Sites list need yt-dlp, which iPhone apps aren't allowed to run.")
                        .font(.footnote)
                        .foregroundStyle(Color.muted)
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
