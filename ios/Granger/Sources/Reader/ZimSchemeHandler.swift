import Foundation
import WebKit

/// Where a page's files come from: the core's online Wikipedia (and later the packs), behind a protocol so tests can
/// stand in for it.
protocol PageSource: AnyObject {
    /// [path]'s bytes and MIME type in [pack], or nil when there's no such entry (or it can't be had now). Blocking.
    func item(pack: String, path: String) -> (data: Data, mimeType: String)?
}

/// Granger's `granger-zim:` scheme (Plan I2 builder's call 6): what Android's ZimWebViewClient does with intercepted
/// requests. A page, its pictures (`_img/…`) and its stylesheet come from the archive; Granger's own files from
/// `_granger/`; everything else is refused, so nothing in a page reaches the network by itself.
final class ZimSchemeHandler: NSObject, WKURLSchemeHandler {
    static let scheme = "granger-zim"
    static let base = "granger-zim://zim/"

    private let source: PageSource
    private let appFiles: [String: (Data, String)]
    private var stopped = Set<ObjectIdentifier>()
    private let lock = NSLock()

    init(source: PageSource, appFiles: [String: (Data, String)]) {
        self.source = source
        self.appFiles = appFiles
    }

    func webView(_ webView: WKWebView, start task: WKURLSchemeTask) {
        let id = ObjectIdentifier(task)
        guard let url = task.request.url, let parts = Self.parse(url.absoluteString) else {
            return respond(task, id: id, status: 404, data: Data(), mime: "text/plain")
        }
        if parts.pack == Self.appPack {
            guard let (data, mime) = appFiles[parts.path] else { return respond(task, id: id, status: 404, data: Data(), mime: "text/plain") }
            return respond(task, id: id, status: 200, data: data, mime: mime)
        }
        DispatchQueue.global(qos: .userInitiated).async { [source] in
            if let item = source.item(pack: parts.pack, path: parts.path) {
                self.respond(task, id: id, status: 200, data: item.data, mime: item.mimeType)
            } else {
                self.respond(task, id: id, status: 404, data: Data(), mime: "text/plain")
            }
        }
    }

    func webView(_ webView: WKWebView, stop task: WKURLSchemeTask) {
        lock.lock(); stopped.insert(ObjectIdentifier(task)); lock.unlock()
    }

    /// Answers [task] unless the web view has stopped it (a fast swipe-back); answering a stopped task crashes WebKit.
    private func respond(_ task: WKURLSchemeTask, id: ObjectIdentifier, status: Int, data: Data, mime: String) {
        let send = {
            self.lock.lock()
            let gone = self.stopped.remove(id) != nil
            self.lock.unlock()
            if gone { return }
            let (type, charset) = Self.split(mime)
            var headers = ["Content-Type": charset.map { "\(type); charset=\($0)" } ?? type, "Content-Length": "\(data.count)"]
            headers["Cache-Control"] = "no-store"
            let response = HTTPURLResponse(url: task.request.url!, statusCode: status, httpVersion: "HTTP/1.1", headerFields: headers)!
            task.didReceive(response)
            task.didReceive(data)
            task.didFinish()
        }
        if Thread.isMainThread { send() } else { DispatchQueue.main.async(execute: send) }
    }

    static let appPack = "_granger"

    /// A reader URL's pack and path; nil for anything else.
    static func parse(_ url: String) -> (pack: String, path: String, fragment: String?)? {
        guard let p = GrangerCoreBridge.parse(url) else { return nil }
        return p
    }

    /// "text/html; charset=utf-8" → ("text/html", "utf-8").
    static func split(_ mime: String) -> (String, String?) {
        let parts = mime.split(separator: ";", maxSplits: 1).map { $0.trimmingCharacters(in: .whitespaces) }
        let charset = parts.count > 1 ? parts[1].replacingOccurrences(of: "charset=", with: "") : (mime.hasPrefix("text/") ? "utf-8" : nil)
        return (parts.first ?? mime, charset)
    }
}
