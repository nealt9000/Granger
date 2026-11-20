import Foundation
import GrangerCoreKit
import WebKit

/// The scripts every reader page gets: a shim giving the shared tap/link script the `GrangerBridge` object Android's
/// WebView provides (posting to `webkit.messageHandlers.granger`), then the script itself (from the core).
enum PageBridge {
    static let handlerName = "granger"

    static var shim: String {
        """
        window.GrangerBridge = {
          noteLink: function (href, text) { window.webkit.messageHandlers.\(handlerName).postMessage({ op: 'noteLink', href: href, text: text }); },
          toggleChrome: function () { window.webkit.messageHandlers.\(handlerName).postMessage({ op: 'toggleChrome' }); },
          onUserScroll: function (dy, byHand) {},
          onBoxScroll: function (fraction) {}
        };
        """
    }

    static func userScripts() -> [WKUserScript] {
        [
            WKUserScript(source: shim, injectionTime: .atDocumentStart, forMainFrameOnly: true),
            WKUserScript(source: GrangerCoreEntry.shared.tapScript, injectionTime: .atDocumentEnd, forMainFrameOnly: true),
        ]
    }

    /// Text size as Android's Aa sets it: the page's text scaled, its layout kept.
    static func textScaleScript(_ scale: Double) -> String {
        "document.documentElement.style.webkitTextSizeAdjust = '\(Int((scale * 100).rounded()))%';"
    }

    /// Scrolls to a section's anchor.
    static func scrollScript(anchor: String) -> String {
        let escaped = anchor.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "'", with: "\\'")
        return "var e = document.getElementById('\(escaped)'); if (e) { e.scrollIntoView({ block: 'start' }); }"
    }
}

/// What a tapped link in a reader page should do (Android's `linkAction`, same outcomes).
enum LinkAction: Equatable {
    /// Let the web view follow it (a fragment in the same page).
    case load
    /// Another article in the same source: open its topic page.
    case article(pack: String, path: String, fragment: String?)
    /// Outside Granger: the in-app browser.
    case external(URL)
    /// mail, phone: the system.
    case system(URL)
    case ignore

    static func of(_ url: URL, currentPack: String, currentPath: String) -> LinkAction {
        switch url.scheme?.lowercased() {
        case ZimSchemeHandler.scheme:
            guard let p = GrangerCoreBridge.parse(url.absoluteString) else { return .ignore }
            if p.pack == ZimSchemeHandler.appPack { return .ignore }
            if p.pack == currentPack && p.path == currentPath { return .load }
            if p.pack != currentPack { return .ignore }
            if p.path.hasPrefix("_img/") || p.path.hasPrefix("_assets_/") { return .ignore }
            return .article(pack: p.pack, path: p.path, fragment: p.fragment)
        case "http", "https":
            return .external(url)
        case "mailto", "tel", "sms":
            return .system(url)
        default:
            return .ignore
        }
    }
}
