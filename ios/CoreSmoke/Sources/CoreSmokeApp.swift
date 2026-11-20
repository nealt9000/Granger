import GrangerCoreKit
import SwiftUI

/// Plan I1 Task 8: proves the shared core links and works on iOS. Opens "Moon" from Wikipedia through the core and
/// checks the bundled catalog's signature with CryptoKit.
@main
struct CoreSmokeApp: App {
    init() { GrangerCoreKit.start() }

    var body: some Scene {
        WindowGroup { SmokeView() }
    }
}

struct SmokeView: View {
    @State private var lines: [String] = ["Working…"]

    var body: some View {
        NavigationStack {
            List(lines, id: \.self) { Text($0).font(.body) }
                .navigationTitle("GrangerCore")
                .accessibilityIdentifier("smoke-list")
        }
        .task { lines = await Task.detached { Smoke.run() }.value }
    }
}

enum Smoke {
    static func run() -> [String] {
        var out: [String] = []
        let entry = GrangerCoreEntry.shared
        let client = entry.wikimedia(version: "0.1-coresmoke")
        if let s = client.summary(lang: "en", title: "Moon") {
            out.append("SUMMARY: \(s.title) — \(s.extract.prefix(80))…")
        } else {
            out.append("SUMMARY: none")
        }
        let wiki = entry.onlineWikipedia(lang: "en", client: client)
        if let item = wiki.read(path: "Moon") {
            let html = String(decoding: entry.bytes(of: item.bytes), as: UTF8.self)
            let blocks = ArticleText.shared.blocks(html: html)
            let first = blocks.first { $0.tag == "p" }?.text ?? "(no paragraph)"
            out.append("ARTICLE: \(item.path), \(html.count) chars, \(blocks.count) blocks")
            out.append("FIRST: \(first.prefix(100))…")
        } else {
            out.append("ARTICLE: none")
        }
        out.append(catalogLine())
        return out
    }

    static func catalogLine() -> String {
        guard let json = Bundle.main.url(forResource: "catalog", withExtension: "json").flatMap({ try? Data(contentsOf: $0) }),
              let sig = Bundle.main.url(forResource: "catalog.json", withExtension: "sig").flatMap({ try? Data(contentsOf: $0) }),
              let keyText = Bundle.main.url(forResource: "public-key", withExtension: "txt").flatMap({ try? String(contentsOf: $0, encoding: .utf8) }),
              let key = Data(base64Encoded: keyText.trimmingCharacters(in: .whitespacesAndNewlines))
        else { return "CATALOG: files missing" }
        guard let catalog = GrangerCoreEntry.shared.parseCatalog(json: json, signature: sig, publicKey: key) else {
            return "CATALOG: signature or parse FAILED"
        }
        return "CATALOG: verified, \(catalog.packs.count) packs"
    }
}
