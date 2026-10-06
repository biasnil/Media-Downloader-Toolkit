import Foundation
import SwiftUI
import WebKit

/// Sites you can log in to. Adding one = one new case here.
enum LoginSite: String, CaseIterable, Identifiable {
    case instagram, facebook, reddit

    var id: String { rawValue }

    var name: String {
        switch self {
        case .instagram: return "Instagram"
        case .facebook: return "Facebook"
        case .reddit: return "Reddit"
        }
    }

    /// Cookies for this site end with this domain.
    var domain: String {
        switch self {
        case .instagram: return "instagram.com"
        case .facebook: return "facebook.com"
        case .reddit: return "reddit.com"
        }
    }

    var loginURL: URL {
        switch self {
        case .instagram: return URL(string: "https://www.instagram.com/accounts/login/")!
        case .facebook: return URL(string: "https://m.facebook.com/login/")!
        case .reddit: return URL(string: "https://www.reddit.com/login/")!
        }
    }

    /// The cookie that only exists while you're logged in.
    var sessionCookie: String {
        switch self {
        case .instagram: return "sessionid"
        case .facebook: return "c_user"
        case .reddit: return "reddit_session"
        }
    }

    /// Shown under the dropdown in Settings.
    var note: String {
        switch self {
        case .instagram: return "Needed for all Instagram downloads."
        case .facebook: return "Needed for most Facebook videos (public ones may work without it)."
        case .reddit: return "Optional. Helps when Reddit blocks downloads, and needed for 18+ posts."
        }
    }
}

/// Logins live in this app's own web storage (iOS doesn't let apps share logins with
/// Safari or other apps). They're kept between launches, so you log in once per site.
@MainActor
enum SiteLogin {
    /// Safari on iPhone, for login pages and API requests.
    nonisolated static let mobileUA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1"
    /// Safari on Mac, for sites that only put video data in their desktop pages.
    nonisolated static let desktopUA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15"

    private static var store: WKHTTPCookieStore { WKWebsiteDataStore.default().httpCookieStore }

    static func cookies(for site: LoginSite) async -> [HTTPCookie] {
        await store.allCookies().filter { $0.domain.hasSuffix(site.domain) }
    }

    static func isLoggedIn(_ site: LoginSite) async -> Bool {
        await cookies(for: site).contains { $0.name == site.sessionCookie && !$0.value.isEmpty }
    }

    /// "Cookie:" header value, or nil when not logged in.
    static func cookieHeader(for site: LoginSite) async -> String? {
        let all = await cookies(for: site)
        guard all.contains(where: { $0.name == site.sessionCookie && !$0.value.isEmpty }) else { return nil }
        return all.map { "\($0.name)=\($0.value)" }.joined(separator: "; ")
    }

    static func logOut(_ site: LoginSite) async {
        for cookie in await cookies(for: site) {
            await store.deleteCookie(cookie)
        }
    }
}

/// The site's real login page. Closes itself once the site hands back a session.
struct SiteLoginView: View {
    let site: LoginSite
    var onDone: (Bool) -> Void

    var body: some View {
        NavigationStack {
            LoginWebView(url: site.loginURL)
                .ignoresSafeArea(edges: .bottom)
                .navigationTitle("Log in to \(site.name)")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { onDone(false) }
                    }
                }
        }
        .task {
            // Check once a second until the login cookie shows up
            while !Task.isCancelled {
                if await SiteLogin.isLoggedIn(site) {
                    try? await Task.sleep(for: .seconds(1.5)) // let the site finish setting its other cookies
                    onDone(true)
                    return
                }
                try? await Task.sleep(for: .seconds(1))
            }
        }
    }
}

private struct LoginWebView: UIViewRepresentable {
    let url: URL

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.websiteDataStore = .default() // persistent, so the login survives restarts
        let view = WKWebView(frame: .zero, configuration: config)
        view.customUserAgent = SiteLogin.mobileUA
        view.load(URLRequest(url: url))
        return view
    }

    func updateUIView(_ view: WKWebView, context: Context) {}
}

/// Settings section: pick a site from the dropdown, then log in or out.
struct AccountsSection: View {
    @State private var site: LoginSite = .instagram
    @State private var loggedIn: Set<LoginSite> = []
    @State private var loginSheet: LoginSite?

    var body: some View {
        Section {
            Picker("Site", selection: $site) {
                ForEach(LoginSite.allCases) { s in
                    Text(loggedIn.contains(s) ? "\(s.name) ✓" : s.name).tag(s)
                }
            }
            .pickerStyle(.menu)

            HStack {
                Text(loggedIn.contains(site) ? "Logged in" : "Not logged in")
                    .foregroundStyle(loggedIn.contains(site) ? Color.primary : Color.muted)
                Spacer()
                if loggedIn.contains(site) {
                    Button("Log out", role: .destructive) {
                        Task {
                            await SiteLogin.logOut(site)
                            await refresh()
                        }
                    }
                } else {
                    Button("Log in") { loginSheet = site }
                }
            }
        } header: {
            Text("Accounts")
        } footer: {
            Text("\(site.note) You log in on the site's own page, so your password goes to the site, not this app. Logins are only used for the links you paste.")
        }
        .task { await refresh() }
        .sheet(item: $loginSheet) { s in
            SiteLoginView(site: s) { _ in
                loginSheet = nil
                Task { await refresh() }
            }
        }
    }

    private func refresh() async {
        var result: Set<LoginSite> = []
        for s in LoginSite.allCases {
            if await SiteLogin.isLoggedIn(s) { result.insert(s) }
        }
        loggedIn = result
    }
}
