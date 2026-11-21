import CryptoKit
import Foundation
import GrangerCoreKit
import Observation

/// The library as screens see it (Plan I3 Task 6): a count that moves on every Keep, Remove and page saved (so marks
/// are worked out again), and the online index set: Top's QID index and time index for the Wikipedia language,
/// fetched at first run by a plain download, checked against the signed catalog (builder's call 5), then opened.
@Observable
final class LibraryModel {
    enum IndexState: Equatable {
        /// Not asked for yet, or the gate is shut and nothing's on the phone.
        case none
        /// On its way.
        case coming
        /// On the phone and open.
        case ready
        /// Couldn't be had (no catalog, a bad file); tried again at the next start or language change.
        case failed
    }

    /// Moves with the library's own count.
    private(set) var version = 0
    private(set) var index: IndexState = .none
    /// The verified catalog, once fetched this run.
    private(set) var catalog: Catalog?

    private var watching = false

    /// Starts watching the library's count. Once per run.
    @MainActor
    func watch(_ core: Core) {
        guard !watching else { return }
        watching = true
        catalogCache = core.folder.appendingPathComponent("catalog", isDirectory: true)
        let flow = core.library.version
        Task { @MainActor in
            for await v in flow { self.version = v.intValue }
        }
    }

    /// Opens the index set on the phone for [lang] and, when the gate is open, fetches what's missing or out of date.
    @MainActor
    func prepareIndexes(core: Core, lang: String, allowed: Bool) async {
        let folder = core.folder.appendingPathComponent("indexes", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        var manifest = IndexManifest.load(folder)
        let source = GrangerCoreEntry.shared.onlinePackId(lang: lang)
        let opened = await open(core: core, folder: folder, files: manifest.files.filter { $0.source == source })
        if opened { index = .ready }
        // Offline, the last good catalog still names the packs' areas and Get more's lists (Android's cache).
        if !allowed, let cached = await loadCatalog() { core.library.catalog = cached }
        guard allowed else { if !opened { index = .none }; return }
        guard let catalog = await loadCatalog() else { if !opened { index = .failed }; return }
        // Topic records (Plan O6) are named by the catalog.
        core.library.catalog = catalog
        let wanted = core.library.indexFiles(catalog: catalog, lang: lang)
        let missing = wanted.filter { w in !manifest.files.contains { $0.source == w.source && $0.sha256 == w.sha256 && $0.kind == w.kind.name } }
        guard !missing.isEmpty else { return }
        if !opened { index = .coming }
        for file in missing {
            guard let path = await Self.download(file, into: folder) else { index = opened ? .ready : .failed; return }
            manifest.files.removeAll { $0.source == file.source && $0.kind == file.kind.name }
            manifest.files.append(.init(kind: file.kind.name, source: file.source, sha256: file.sha256, name: path.lastPathComponent))
        }
        manifest.save(folder)
        manifest.removeUnlisted(folder)
        index = await open(core: core, folder: folder, files: manifest.files.filter { $0.source == source }) ? .ready : .failed
    }

    private func open(core: Core, folder: URL, files: [IndexManifest.File]) async -> Bool {
        let toOpen = files.compactMap { f -> OpenIndex? in
            let url = folder.appendingPathComponent(f.name)
            guard FileManager.default.fileExists(atPath: url.path) else { return nil }
            return OpenIndex(kind: f.kind == IndexKind.qid.name ? .qid : .time, source: f.source, path: url.path)
        }
        guard !toOpen.isEmpty else { return false }
        let library = core.library
        return await background { library.openIndexes(files: toOpen) }
    }

    /// The catalog from main (Android's CATALOG_URL), verified against the app's public key; nil when it doesn't verify.
    @MainActor
    /// The next loadCatalog fetches afresh (Get more's refresh).
    func forgetCatalog() { catalog = nil }

    /// Where the last good catalog and its signature are kept (Android's catalog cache), set by [watch].
    private var catalogCache: URL?

    /// The verified catalog: fetched when the gate is open (and kept, with its signature, for next time), else the last
    /// good one from the cache, verified again; nil when there's neither.
    func loadCatalog() async -> Catalog? {
        if let catalog { return catalog }
        guard let key = Data(base64Encoded: Self.publicKey) else { return nil }
        if NetworkGate.shared.open,
           let json = try? await URLSession.shared.data(from: Self.catalogURL).0,
           let sig = try? await URLSession.shared.data(from: Self.catalogURL.appendingPathExtension("sig")).0,
           let parsed = await background({ GrangerCoreEntry.shared.parseCatalog(json: json, signature: sig, publicKey: key) }) {
            if let dir = catalogCache {
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                try? json.write(to: dir.appendingPathComponent("catalog.json"), options: .atomic)
                try? sig.write(to: dir.appendingPathComponent("catalog.json.sig"), options: .atomic)
            }
            catalog = parsed
            return parsed
        }
        catalog = await cachedCatalog(key: key)
        return catalog
    }

    private func cachedCatalog(key: Data) async -> Catalog? {
        guard let dir = catalogCache,
              let json = try? Data(contentsOf: dir.appendingPathComponent("catalog.json")),
              let sig = try? Data(contentsOf: dir.appendingPathComponent("catalog.json.sig")) else { return nil }
        return await background { GrangerCoreEntry.shared.parseCatalog(json: json, signature: sig, publicKey: key) }
    }

    /// Downloads [file] into [folder] under a name that carries its hash, checking the sha256 before it's moved in.
    private static func download(_ file: IndexFile, into folder: URL) async -> URL? {
        guard let url = URL(string: file.url), let (tmp, response) = try? await URLSession.shared.download(from: url),
              (response as? HTTPURLResponse)?.statusCode == 200 else { return nil }
        defer { try? FileManager.default.removeItem(at: tmp) }
        guard let sum = sha256(of: tmp), sum == file.sha256.lowercased() else { return nil }
        let to = folder.appendingPathComponent("\(file.source)_\(file.sha256.prefix(12)).\(file.kind == .qid ? "gqid" : "gtime")")
        try? FileManager.default.removeItem(at: to)
        do { try FileManager.default.moveItem(at: tmp, to: to) } catch { return nil }
        return to
    }

    /// The file's SHA-256 in hex, read in 1 MB pieces.
    static func sha256(of url: URL) -> String? {
        guard let handle = try? FileHandle(forReadingFrom: url) else { return nil }
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try? handle.read(upToCount: 1 << 20), !chunk.isEmpty { hasher.update(data: chunk) }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    static let catalogURL = URL(string: "https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json")!
    /// The catalog's Ed25519 public key (catalog/public-key.txt), as Android's CATALOG_PUBLIC_KEY.
    static let publicKey = "VbbUcWkin89B18cPJaJYlg8Duv+eXtUBRzr4v18DyZs="
}

/// Which index files are on the phone, as a small JSON beside them.
struct IndexManifest: Codable {
    struct File: Codable, Equatable {
        let kind: String
        let source: String
        let sha256: String
        let name: String
    }

    var files: [File] = []

    static func load(_ folder: URL) -> IndexManifest {
        (try? Data(contentsOf: folder.appendingPathComponent("indexes.json"))).flatMap { try? JSONDecoder().decode(IndexManifest.self, from: $0) } ?? IndexManifest()
    }

    func save(_ folder: URL) {
        try? JSONEncoder().encode(self).write(to: folder.appendingPathComponent("indexes.json"), options: .atomic)
    }

    /// Takes away index files no longer listed (an older snapshot replaced by a newer).
    func removeUnlisted(_ folder: URL) {
        let keep = Set(files.map(\.name) + ["indexes.json"])
        for name in (try? FileManager.default.contentsOfDirectory(atPath: folder.path)) ?? [] where !keep.contains(name) {
            try? FileManager.default.removeItem(at: folder.appendingPathComponent(name))
        }
    }
}
