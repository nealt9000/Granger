import SafariServices
import SwiftUI
import WebKit

/// Talks to the page from outside SwiftUI: scroll to a section, apply a text size.
@MainActor
final class PageController {
    fileprivate weak var webView: WKWebView?

    func scroll(to anchor: String) {
        webView?.evaluateJavaScript(PageBridge.scrollScript(anchor: anchor))
    }

    func setTextScale(_ scale: Double) {
        webView?.evaluateJavaScript(PageBridge.textScaleScript(scale))
    }

    /// Find in page (Android's reader search): the system's find bar over the page.
    func find() {
        webView?.findInteraction?.presentFindNavigator(showingReplace: false)
    }

    /// Runs a read-aloud page call and hands back its JSON answer (Plan I5 Task 8).
    func evaluate(_ script: String, _ done: @escaping (String?) -> Void = { _ in }) {
        guard let webView else { return done(nil) }
        webView.evaluateJavaScript(script) { result, _ in done(result as? String) }
    }

    /// Whether a page is there to talk to.
    var ready: Bool { webView != nil }
}

/// The reader's web view, with Read from here in its selection menu (Android's READ_ALOUD selection action).
final class ReaderWebView: WKWebView {
    var onReadFromHere: (() -> Void)?

    override func buildMenu(with builder: any UIMenuBuilder) {
        super.buildMenu(with: builder)
        guard let onReadFromHere else { return }
        let read = UIAction(title: "Read from here", image: UIImage(systemName: "headphones")) { _ in onReadFromHere() }
        builder.insertChild(UIMenu(options: .displayInline, children: [read]), atStartOfMenu: .standardEdit)
    }
}

/// One reader page in a `WKWebView`, served by the `granger-zim:` scheme with the shared CSS and scripts (Plan I2
/// Task 3). Taps on plain content toggle the bars; links follow Android's rules.
struct ArticleWebView: UIViewRepresentable {
    let pack: String
    let path: String
    let fragment: String?
    let source: PageSource
    let textScale: Double
    let dark: Bool
    let controller: PageController
    var onToggleChrome: () -> Void = {}
    var onOpenArticle: (_ pack: String, _ path: String, _ fragment: String?) -> Void = { _, _, _ in }
    var onLoaded: () -> Void = {}
    /// The page was scrolled by the reader, not by the voice (the read-aloud script's report): the page stops following.
    var onUserScroll: () -> Void = {}
    /// Read from here was picked on a selection; nil leaves it out of the menu.
    var onReadFromHere: (() -> Void)? = nil

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        let appFiles = ["online.css": (Data(GrangerCoreEntryAccess.onlineCss.utf8), "text/css")]
        config.setURLSchemeHandler(ZimSchemeHandler(source: source, appFiles: appFiles), forURLScheme: ZimSchemeHandler.scheme)
        PageBridge.userScripts().forEach(config.userContentController.addUserScript)
        config.userContentController.add(WeakMessageHandler(context.coordinator), name: PageBridge.handlerName)
        config.dataDetectorTypes = []
        let web = ReaderWebView(frame: .zero, configuration: config)
        web.onReadFromHere = onReadFromHere
        web.isFindInteractionEnabled = true
        web.navigationDelegate = context.coordinator
        web.allowsBackForwardNavigationGestures = false   // Back is the navigation stack's swipe
        web.isOpaque = false
        web.backgroundColor = .clear
        web.overrideUserInterfaceStyle = dark ? .dark : .light
        web.accessibilityIdentifier = "article-web"
        controller.webView = web
        let url = URL(string: GrangerCoreBridge.url(pack: pack, path: path, fragment: fragment))!
        web.load(URLRequest(url: url))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        context.coordinator.parent = self
        (web as? ReaderWebView)?.onReadFromHere = onReadFromHere
        web.overrideUserInterfaceStyle = dark ? .dark : .light
        if context.coordinator.appliedScale != textScale, context.coordinator.loaded {
            context.coordinator.appliedScale = textScale
            controller.setTextScale(textScale)
        }
    }

    static func dismantleUIView(_ web: WKWebView, coordinator: Coordinator) {
        web.configuration.userContentController.removeScriptMessageHandler(forName: PageBridge.handlerName)
    }

    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        var parent: ArticleWebView
        var appliedScale: Double = 1
        var loaded = false

        init(_ parent: ArticleWebView) { self.parent = parent }

        func webView(_ webView: WKWebView, decidePolicyFor action: WKNavigationAction, decisionHandler: @escaping @MainActor (WKNavigationActionPolicy) -> Void) {
            guard let url = action.request.url else { return decisionHandler(.cancel) }
            // The first load, and anything not a tap, goes through.
            guard action.navigationType == .linkActivated else { return decisionHandler(.allow) }
            switch LinkAction.of(url, currentPack: parent.pack, currentPath: parent.path) {
            case .load:
                decisionHandler(.allow)
            case let .article(pack, path, fragment):
                decisionHandler(.cancel)
                parent.onOpenArticle(pack, path, fragment)
            case let .external(url):
                decisionHandler(.cancel)
                Coordinator.present(SFSafariViewController(url: url))
            case let .system(url):
                decisionHandler(.cancel)
                UIApplication.shared.open(url)
            case .ignore:
                decisionHandler(.cancel)
            }
        }

        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            loaded = true
            appliedScale = parent.textScale
            if parent.textScale != 1 { webView.evaluateJavaScript(PageBridge.textScaleScript(parent.textScale)) }
            parent.onLoaded()
        }

        func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
            guard let body = message.body as? [String: Any], let op = body["op"] as? String else { return }
            if op == "toggleChrome" { parent.onToggleChrome() }
            if op == "userScroll" { parent.onUserScroll() }
        }

        @MainActor static func present(_ vc: UIViewController) {
            let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
            var top = scene?.keyWindow?.rootViewController
            while let next = top?.presentedViewController { top = next }
            top?.present(vc, animated: true)
        }
    }
}

/// WKUserContentController keeps its handlers strongly; this breaks the cycle with the coordinator.
private final class WeakMessageHandler: NSObject, WKScriptMessageHandler {
    weak var target: WKScriptMessageHandler?
    init(_ target: WKScriptMessageHandler) { self.target = target }
    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        target?.userContentController(controller, didReceive: message)
    }
}
