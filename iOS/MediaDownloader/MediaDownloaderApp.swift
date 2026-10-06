import SwiftUI

@main
struct MediaDownloaderApp: App {
    @AppStorage("theme") private var theme = "system"

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(theme == "dark" ? .dark : theme == "light" ? .light : nil)
                .tint(.ytRed)
        }
    }
}
