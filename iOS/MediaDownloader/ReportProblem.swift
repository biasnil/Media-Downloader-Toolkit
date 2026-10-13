import MessageUI
import SwiftUI
import UIKit

/// A problem report. Nothing is sent by the app itself: it opens Mail or GitHub with the
/// text filled in, and the user sees all of it before tapping Send / Submit.
struct ProblemReport: Identifiable {
    static let email = "workacckwanrong@gmail.com"
    static let repository = URL(string: "https://github.com/biasnil/Media-Downloader-Toolkit")!

    let id = UUID()
    /// What went wrong, e.g. the link and its error. Empty for a general report.
    var context = ""

    var subject: String { "Media Downloader (iPhone): problem report" }

    var body: String {
        var lines = [AppText.Report.prompt, "", "", "---"]
        if !context.isEmpty { lines += [context, ""] }
        lines += [
            "App: \(Self.appVersion)",
            "iOS: \(UIDevice.current.systemVersion)",
            "Device: \(Self.deviceModel)",
            "Language: \(Bundle.main.preferredLocalizations.first ?? "?")",
        ]
        return lines.joined(separator: "\n")
    }

    var mailtoURL: URL? {
        var c = URLComponents()
        c.scheme = "mailto"
        c.path = Self.email
        c.queryItems = [URLQueryItem(name: "subject", value: subject), URLQueryItem(name: "body", value: body)]
        return c.url
    }

    var gitHubURL: URL {
        var c = URLComponents(url: Self.repository.appendingPathComponent("issues/new"), resolvingAgainstBaseURL: false)!
        c.queryItems = [URLQueryItem(name: "title", value: subject), URLQueryItem(name: "body", value: body)]
        return c.url ?? Self.repository
    }

    /// "1.0 (1)"
    static var appVersion: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        return "\(version) (\(build))"
    }

    /// e.g. "iPhone16,2" (iPhone 15 Pro Max)
    static var deviceModel: String {
        var info = utsname()
        uname(&info)
        return withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }
}

extension View {
    /// Shows "Send Email / Open an Issue on GitHub" when [report] is set.
    func reportProblem(_ report: Binding<ProblemReport?>) -> some View {
        modifier(ReportProblemModifier(report: report))
    }
}

private struct ReportProblemModifier: ViewModifier {
    @Binding var report: ProblemReport?
    @State private var mail: ProblemReport?
    @State private var noMail = false
    @Environment(\.openURL) private var openURL

    func body(content: Content) -> some View {
        content
            .confirmationDialog(AppText.Report.title,
                                isPresented: Binding(get: { report != nil }, set: { if !$0 { report = nil } }),
                                titleVisibility: .visible, presenting: report) { r in
                Button(AppText.Report.byEmail) {
                    if MFMailComposeViewController.canSendMail() {
                        mail = r
                    } else if let url = r.mailtoURL {
                        // Another mail app set as default (e.g. Gmail) handles mailto: links
                        openURL(url) { opened in if !opened { noMail = true } }
                    }
                }
                Button(AppText.Report.onGitHub) { openURL(r.gitHubURL) }
                Button(AppText.Common.cancel, role: .cancel) {}
            } message: { _ in
                Text(AppText.Report.message)
            }
            .sheet(item: $mail) { r in
                MailComposer(report: r) { mail = nil }
                    .ignoresSafeArea()
            }
            .alert(AppText.Report.title, isPresented: $noMail) {
                Button(AppText.Common.done, role: .cancel) {}
            } message: {
                Text(AppText.Report.noMail)
            }
    }
}

/// Apple's Mail compose screen, filled in.
private struct MailComposer: UIViewControllerRepresentable {
    let report: ProblemReport
    let onFinish: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onFinish: onFinish) }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let composer = MFMailComposeViewController()
        composer.mailComposeDelegate = context.coordinator
        composer.setToRecipients([ProblemReport.email])
        composer.setSubject(report.subject)
        composer.setMessageBody(report.body, isHTML: false)
        return composer
    }

    func updateUIViewController(_ controller: MFMailComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let onFinish: () -> Void
        init(onFinish: @escaping () -> Void) { self.onFinish = onFinish }

        func mailComposeController(_ controller: MFMailComposeViewController,
                                   didFinishWith result: MFMailComposeResult, error: Error?) {
            onFinish()
        }
    }
}
