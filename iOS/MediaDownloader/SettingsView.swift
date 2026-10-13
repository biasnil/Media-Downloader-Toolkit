import SwiftUI

/// iPhone version of desktop's gear-icon settings window. Changes save instantly.
struct SettingsView: View {
    @Environment(\.dismiss) private var dismiss
    @AppStorage("embedArtwork") private var embedArtwork = true
    @AppStorage("saveVideosToPhotos") private var saveVideosToPhotos = false
    @AppStorage("notify") private var notify = true
    @AppStorage("theme") private var theme = "system"
    @AppStorage("maxConcurrent") private var maxConcurrent = 1
    @AppStorage("speedLimitKB") private var speedLimitKB = 0
    @AppStorage("liveActivity") private var liveActivity = true
    @AppStorage("keepAlive") private var keepAlive = true
    @AppStorage("clipboardDetect") private var clipboardDetect = true
    @AppStorage("appLanguage") private var appLanguage = ""
    @State private var languageChanged = false
    @State private var report: ProblemReport?

    /// KB/s; 0 = unlimited
    private var speedOptions: [(label: String, kb: Int)] {
        [(AppText.Settings.unlimited, 0), ("250 KB/s", 250), ("500 KB/s", 500),
         ("1 MB/s", 1024), ("2 MB/s", 2048), ("5 MB/s", 5120)]
    }

    /// Language codes the app is translated into, each written in its own language.
    private let languages: [(code: String, name: String)] = [
        ("en", "English"), ("fr", "Français"), ("zh-Hans", "简体中文"), ("ms", "Bahasa Melayu"),
    ]

    var body: some View {
        NavigationStack {
            Form {
                Section(AppText.Settings.thumbnail) {
                    Toggle(AppText.Settings.embedThumbnail, isOn: $embedArtwork)
                }

                Section {
                    Toggle(AppText.Settings.alsoSaveToPhotos, isOn: $saveVideosToPhotos)
                } header: {
                    InfoHeader(AppText.Settings.photos, info: AppText.Info.photos)
                }

                Section {
                    Toggle(AppText.Settings.detectLinks, isOn: $clipboardDetect)
                } header: {
                    InfoHeader(AppText.Settings.clipboard, info: AppText.Info.clipboard)
                }

                Section {
                    Picker(AppText.Settings.atOnce, selection: $maxConcurrent) {
                        ForEach(1...5, id: \.self) { Text(verbatim: "\($0)").tag($0) }
                    }
                    Picker(AppText.Settings.speedLimit, selection: $speedLimitKB) {
                        ForEach(speedOptions, id: \.kb) { Text($0.label).tag($0.kb) }
                    }
                } header: {
                    InfoHeader(AppText.Settings.downloads, info: AppText.Info.downloads)
                }

                Section {
                    Toggle(AppText.Settings.keepAlive, isOn: $keepAlive)
                    Toggle(AppText.Settings.liveActivity, isOn: $liveActivity)
                    Toggle(AppText.Settings.notifications, isOn: $notify)
                } header: {
                    InfoHeader(AppText.Settings.progress, info: AppText.Info.progress)
                }

                AccountsSection()

                Section {
                    Picker(AppText.Settings.language, selection: $appLanguage) {
                        Text(AppText.Settings.followPhone).tag("")
                        ForEach(languages, id: \.code) { Text(verbatim: $0.name).tag($0.code) }
                    }
                    .onChange(of: appLanguage) { _, code in
                        // iOS reads this when the app starts
                        if code.isEmpty {
                            UserDefaults.standard.removeObject(forKey: "AppleLanguages")
                        } else {
                            UserDefaults.standard.set([code], forKey: "AppleLanguages")
                        }
                        languageChanged = true
                    }
                } footer: {
                    if languageChanged { Text(AppText.Settings.restartToApply) }
                }

                Section(AppText.Settings.theme) {
                    Picker(AppText.Settings.theme, selection: $theme) {
                        Text(AppText.Settings.followPhone).tag("system")
                        Text(AppText.Settings.light).tag("light")
                        Text(AppText.Settings.dark).tag("dark")
                    }
                    .pickerStyle(.segmented)
                }

                Section(AppText.About.title) {
                    Button { report = ProblemReport() } label: {
                        Label(AppText.Report.title, systemImage: "exclamationmark.bubble")
                    }
                    Link(destination: ProblemReport.repository) {
                        Label(AppText.About.sourceCode, systemImage: "chevron.left.forwardslash.chevron.right")
                    }
                    NavigationLink { TermsView() } label: {
                        Label(AppText.Terms.title, systemImage: "doc.text")
                    }
                    NavigationLink { PrivacyView() } label: {
                        Label(AppText.Privacy.title, systemImage: "hand.raised")
                    }
                    NavigationLink { AcknowledgementsView() } label: {
                        Label(AppText.About.acknowledgements, systemImage: "heart")
                    }
                    LabeledContent(AppText.About.version, value: ProblemReport.appVersion)
                }

                Section {
                    HStack {
                        Text(AppText.Settings.notAvailable)
                            .foregroundStyle(Color.muted)
                        Spacer()
                        InfoButton(AppText.Info.notAvailable)
                    }
                }
            }
            .reportProblem($report)
            .navigationTitle(AppText.Settings.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(AppText.Common.done) { dismiss() }
                }
            }
        }
    }
}
