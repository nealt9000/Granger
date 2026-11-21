import Foundation
import GrangerCoreKit

/// A Kiwix pack read with CoreKiwix (libkiwix 14.2.1, Plan I4 Task 5), as the shared Kotlin `ZimArchive`: the shared
/// library, search and readers open a pack on the iPhone exactly as java-libkiwix's KiwixZimArchive does on Android.
/// Safe from any thread (the bridge locks); answers nil or empty instead of throwing, also after `close()`.
final class KiwixArchive: NSObject, ZimArchive {
    private let zim: GZimArchive
    let uuid: String
    let metadata: ZimMetadata
    let hasFulltextIndex: Bool
    let mainPath: String?
    let remote = false

    /// Opens the pack at `path`; the reason when it can't.
    static func open(_ path: String) -> Result<KiwixArchive, OpenFailure> {
        var reason: NSString?
        guard let zim = GZimArchive(path: path, error: &reason) else { return .failure(OpenFailure(reason: (reason as String?) ?? "Can't open \(path)")) }
        return .success(KiwixArchive(zim, fileName: ((path as NSString).lastPathComponent as NSString).deletingPathExtension))
    }

    struct OpenFailure: Error { let reason: String }

    private init(_ zim: GZimArchive, fileName: String) {
        self.zim = zim
        uuid = zim.uuid
        // As Android's KiwixZimArchive: a ZIM without a Title is titled by its file name.
        let title = zim.metadata("Title").trimmingCharacters(in: .whitespaces)
        metadata = ZimMetadata(
            title: title.isEmpty ? fileName : zim.metadata("Title"), description: zim.metadata("Description"), language: zim.metadata("Language"),
            date: zim.metadata("Date"), name: zim.metadata("Name"), scraper: zim.metadata("Scraper"), counter: zim.metadata("Counter")
        )
        hasFulltextIndex = zim.hasFulltextIndex
        mainPath = zim.mainPath
    }

    func resolvePath(path: String) -> String? { zim.resolvePath(path) }

    func read(path: String) -> ZimItem? {
        guard let e = zim.entry(path, read: true), let data = e.data else { return nil }
        return GrangerCoreEntry.shared.zimItem(path: e.path, mimeType: e.mimeType, data: data)
    }

    func entry(path: String) -> ZimEntry? {
        zim.entry(path, read: false).map { ZimEntry(path: $0.path, title: $0.title, mimeType: $0.mimeType, sizeBytes: $0.size) }
    }

    func list(mimes: Set<String>, each: @escaping (ZimEntry) -> KotlinBoolean) -> Bool {
        zim.list(mimes) { e in each(ZimEntry(path: e.path, title: e.title, mimeType: e.mimeType, sizeBytes: e.size)).boolValue }
    }

    func randomPath() -> String? { zim.randomPath() }

    func suggest(query: String, limit: Int32) -> [ZimHit] {
        zim.suggest(query, limit: limit).map { ZimHit(path: $0.path, title: $0.title, snippet: nil, score: 0) }
    }

    func search(query: String, limit: Int32) -> [ZimHit] { hits(zim.search(query, limit: limit, snippets: true)) }

    func searchWithoutSnippets(query: String, limit: Int32) -> [ZimHit] { hits(zim.search(query, limit: limit, snippets: false)) }

    private func hits(_ found: [GZimHit]) -> [ZimHit] {
        found.map { ZimHit(path: $0.path, title: $0.title, snippet: $0.snippet, score: $0.score) }
    }

    func close() { zim.close() }
}
