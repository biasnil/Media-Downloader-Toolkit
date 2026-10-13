import ObjectiveC
import UIKit
import UniformTypeIdentifiers

/// "Media Downloader" in the Share sheet. Finds the link in what was shared,
/// then opens the app with it — the app starts the download (share sheets can't run long tasks).
final class ShareViewController: UIViewController {
    private let label = UILabel()

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = UIColor(red: 1, green: 0, blue: 0, alpha: 1)
        label.text = String(localized: "Opening Media Downloader...")
        label.textColor = .white
        label.font = .preferredFont(forTextStyle: .headline)
        label.numberOfLines = 0
        label.textAlignment = .center
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.centerXAnchor.constraint(equalTo: view.centerXAnchor),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor),
            label.leadingAnchor.constraint(greaterThanOrEqualTo: view.leadingAnchor, constant: 24),
        ])
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        Task { await handleShare() }
    }

    private func handleShare() async {
        guard let link = await findLink() else {
            show(String(localized: "No link found in what you shared."), closeAfter: 2)
            return
        }

        var app = URLComponents()
        app.scheme = "mediadownloader"
        app.host = "download"
        app.queryItems = [URLQueryItem(name: "url", value: link.absoluteString)]

        if let appURL = app.url, openContainingApp(appURL) {
            close()
        } else {
            // Backup: the link is on the clipboard, ready for the app's Paste button
            UIPasteboard.general.string = link.absoluteString
            show(String(localized: "Couldn't open the app automatically.\nThe link is copied — open Media Downloader and tap Paste."), closeAfter: 3)
        }
    }

    /// The shared web link, or the first link inside shared text (YouTube shares text sometimes).
    private func findLink() async -> URL? {
        let providers = (extensionContext?.inputItems as? [NSExtensionItem] ?? [])
            .flatMap { $0.attachments ?? [] }

        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.url.identifier) {
            if let item = try? await provider.loadItem(forTypeIdentifier: UTType.url.identifier),
               let url = item as? URL, url.scheme?.hasPrefix("http") == true {
                return url
            }
        }
        for provider in providers where provider.hasItemConformingToTypeIdentifier(UTType.plainText.identifier) {
            if let item = try? await provider.loadItem(forTypeIdentifier: UTType.plainText.identifier),
               let text = item as? String, let url = firstLink(in: text) {
                return url
            }
        }
        return nil
    }

    private func firstLink(in text: String) -> URL? {
        let detector = try? NSDataDetector(types: NSTextCheckingResult.CheckingType.link.rawValue)
        let range = NSRange(text.startIndex..., in: text)
        return detector?.matches(in: text, range: range)
            .compactMap(\.url)
            .first { $0.scheme?.hasPrefix("http") == true }
    }

    /// Share extensions can't open apps directly; this finds the app hosting the share sheet and
    /// asks it to open our link. Called through the Objective-C runtime because the normal
    /// open(_:options:) is marked "not for extensions" and wouldn't compile here.
    private func openContainingApp(_ url: URL) -> Bool {
        var responder: UIResponder? = self
        while let current = responder {
            if let application = current as? UIApplication {
                let selector = NSSelectorFromString("openURL:options:completionHandler:")
                guard let method = class_getInstanceMethod(UIApplication.self, selector) else { return false }
                typealias OpenURL = @convention(c) (
                    AnyObject, Selector, NSURL, NSDictionary, (@convention(block) (Bool) -> Void)?
                ) -> Void
                let open = unsafeBitCast(method_getImplementation(method), to: OpenURL.self)
                open(application, selector, url as NSURL, NSDictionary(), nil)
                return true
            }
            responder = current.next
        }
        return false
    }

    private func show(_ text: String, closeAfter seconds: Double) {
        label.text = text
        Task {
            try? await Task.sleep(for: .seconds(seconds))
            close()
        }
    }

    private func close() {
        extensionContext?.completeRequest(returningItems: nil)
    }
}
