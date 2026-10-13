import SwiftUI

/// Terms & Conditions must be agreed to again every 10 days.
enum Terms {
    static let validDays = 10

    static var agreedAt: Date? {
        let time = UserDefaults.standard.double(forKey: "termsAgreedAt")
        return time > 0 ? Date(timeIntervalSince1970: time) : nil
    }

    static var expiresAt: Date? {
        agreedAt.map { $0.addingTimeInterval(Double(validDays) * 24 * 60 * 60) }
    }

    static var needsAgreement: Bool {
        guard let expiresAt else { return true }
        return Date() >= expiresAt
    }

    static func agree() {
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: "termsAgreedAt")
    }
}

/// Shown full screen when agreement is needed, or read from Settings → About.
struct TermsView: View {
    /// Set when the user must agree before continuing; nil = just reading.
    var onAgree: (() -> Void)?

    var body: some View {
        if let onAgree {
            NavigationStack {
                content
                    .safeAreaInset(edge: .bottom) {
                        Button(action: onAgree) {
                            Text(AppText.Terms.agree)
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                                .padding(.vertical, 12)
                                .foregroundStyle(.white)
                                .background(Color.ytRed, in: RoundedRectangle(cornerRadius: 8))
                        }
                        .padding(16)
                        .background(.bar)
                    }
            }
            .interactiveDismissDisabled()
        } else {
            content
        }
    }

    private var content: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text(AppText.Terms.intro)
                    .foregroundStyle(Color.muted)
                ForEach(Array(AppText.Terms.sections.enumerated()), id: \.offset) { index, section in
                    VStack(alignment: .leading, spacing: 6) {
                        Text(verbatim: "\(index + 1). \(section.0)").font(.headline)
                        Text(section.1)
                    }
                }
                if onAgree == nil, let agreed = Terms.agreedAt, let next = Terms.expiresAt {
                    Text(AppText.Terms.agreedOn(agreed.formatted(date: .long, time: .omitted),
                                                next: next.formatted(date: .long, time: .omitted)))
                        .font(.footnote)
                        .foregroundStyle(Color.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
        }
        .background(Color.appBackground)
        .navigationTitle(AppText.Terms.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct PrivacyView: View {
    var body: some View {
        List {
            Section {
                Label(AppText.Privacy.summary, systemImage: "lock.shield")
            }
            Section(AppText.Privacy.storedTitle) {
                ForEach(AppText.Privacy.stored, id: \.self) { Text($0) }
            }
            Section(AppText.Privacy.onlineTitle) {
                Text(AppText.Privacy.online)
            }
            Section(AppText.Privacy.deleteTitle) {
                Text(AppText.Privacy.delete)
            }
        }
        .navigationTitle(AppText.Privacy.title)
        .navigationBarTitleDisplayMode(.inline)
    }
}

struct AcknowledgementsView: View {
    private let projects: [(name: String, about: String, license: String, url: String)] = [
        ("YouTubeKit", AppText.About.youTubeKit, "MIT License", "https://github.com/alexeichhorn/YouTubeKit"),
        ("SwiftLAME / LAME", AppText.About.swiftLAME, "GNU LGPL 2.1", "https://lame.sourceforge.io"),
    ]

    var body: some View {
        List {
            Section {
                Text(AppText.About.thanks)
            }
            ForEach(projects, id: \.name) { p in
                Section {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: p.name).font(.headline)
                        Text(p.about)
                        Text(verbatim: p.license).font(.footnote).foregroundStyle(Color.muted)
                    }
                    if let url = URL(string: p.url) {
                        Link(p.url, destination: url).font(.footnote)
                    }
                }
            }
        }
        .navigationTitle(AppText.About.acknowledgements)
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Banner at the top of the login page.
struct LoginNotice: View {
    let site: String

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lock.shield.fill")
                .foregroundStyle(.green)
                .font(.title3)
            Text(AppText.Privacy.loginNotice(site))
                .font(.footnote)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(12)
        .background(.bar)
    }
}
