import Foundation
import GrangerCoreKit

/// The shared Kotlin core, as the app uses it: one Wikimedia client, and the library (Plan I3 Task 6) that reads
/// Wikipedia online through Saved for now and the kept copies. Every call into it blocks, so screens call it through
/// [background].
final class Core: PageSource {
    /// The one Core, for the app delegate (the background download session's events arrive there).
    nonisolated(unsafe) static weak var shared: Core?
    let client: WikimediaClient
    let library: IosLibrary
    /// Where the library keeps its files: Application Support/granger (Android's files folder).
    let folder: URL
    /// Where packs live: Documents/Packs, which the Files app shows as On My iPhone › Granger › Packs (Plan I4
    /// builder's call 2). Left out of iCloud backup: packs are big and can be fetched again.
    let packsFolder: URL
    /// Where books live: Documents/Books, beside Packs in the Files app (Plan I5 Task 5): imports, and the PDFs and EPUBs
    /// copied out of packs (`packs/`, `gutenberg/`, `nautilus/`, `kiwix/`; those copies are left out of iCloud backup).
    let booksFolder: URL

    init(version: String) {
        GrangerCoreEntry.shared.setZimBase(base: ZimSchemeHandler.base)
        // The gate (Plan O5/O3), which AppSettings keeps current; the core asks it from any thread.
        client = GrangerCoreEntry.shared.wikimedia(version: version) { KotlinBoolean(value: NetworkGate.shared.open) }
        folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("granger", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        packsFolder = Core.makePacksFolder()
        booksFolder = Core.makeBooksFolder()
        // PDFKit for the shared book rules (import, covers, outlines).
        PdfKitEngine.install()
        // libarchive and UIGraphicsPDFRenderer for comics (CBZ, CBR) imported as books (Plan I5 Task 6).
        ComicNatives.install()
        #if DEBUG
        // UI tests and checks: `-debugPack <path on the Mac>` copies that pack in (the Simulator reads the Mac's disk).
        if let from = UserDefaults.standard.string(forKey: "debugPack"), FileManager.default.fileExists(atPath: from) {
            let to = packsFolder.appendingPathComponent((from as NSString).lastPathComponent)
            if !FileManager.default.fileExists(atPath: to.path) { try? FileManager.default.copyItem(atPath: from, toPath: to.path) }
        }
        #endif
        library = IosLibrary(
            dir: folder.path, client: client,
            languages: { LibraryGate.shared.languages },
            gateOpen: { KotlinBoolean(value: NetworkGate.shared.open) },
            savePictures: { KotlinBoolean(value: LibraryGate.shared.savePictures) },
            savedCapMb: { KotlinLong(value: Int64(LibraryGate.shared.savedCapMb)) },
            keepAdultOff: { KotlinBoolean(value: LibraryGate.shared.keepAdultOff) },
            cacheDir: FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("granger").path,
            appVersion: version,
            opener: { path in
                switch KiwixArchive.open(path) {
                case let .success(archive): ZimOpenResultOpened(archive: archive)
                case let .failure(why): ZimOpenResultFailed(reason: why.reason)
                }
            },
            packsDir: packsFolder.path,
            // Downloads go when the gate is open (Local only stops them, as Android's NetworkGate does).
            unmetered: { KotlinBoolean(value: NetworkPath.shared.unmetered) },
            connected: { KotlinBoolean(value: NetworkPath.shared.connected && NetworkGate.shared.open) },
            wifiOnly: { KotlinBoolean(value: LibraryGate.shared.wifiOnly) },
            booksDir: booksFolder.path,
            pdfCovers: PdfKitCovers()
        )
        Core.shared = self
        NetworkPath.shared.changed = { [weak self] in self?.library.networkChanged() }
        library.startDownloads()
        library.startBooks()
    }

    private static func makePacksFolder() -> URL {
        var url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Packs", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try? url.setResourceValues(values)
        return url
    }

    private static func makeBooksFolder() -> URL {
        let url = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Books", isDirectory: true)
        try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        // Copies out of packs can be made again from the pack; imports are the reader's own and are backed up.
        for name in ["packs", "gutenberg", "nautilus", "kiwix"] {
            var sub = url.appendingPathComponent(name, isDirectory: true)
            try? FileManager.default.createDirectory(at: sub, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            try? sub.setResourceValues(values)
        }
        return url
    }

    /// The online source's pack id for [lang] (`wikipedia_en_online`).
    func pack(for lang: String) -> String { GrangerCoreEntry.shared.onlinePackId(lang: lang) }

    /// A page or picture for the reader: Wikipedia online's by language, a pack's from its archive (Plan I4 Task 5).
    func item(pack: String, path: String) -> (data: Data, mimeType: String)? {
        let source: ZimArchive? = GrangerCoreEntry.shared.languageOfPack(packId: pack).map { wikipedia($0) } ?? library.archive(packId: pack)
        guard let item = source?.read(path: path) else { return nil }
        return (GrangerCoreEntry.shared.bytes(of: item.bytes), item.mimeType)
    }

    /// Wikipedia in [lang], read online through Saved for now and the kept copies.
    func wikipedia(_ lang: String) -> OnlineWikipediaArchive { library.wikipedia(lang: lang) }

    /// The app's version and build, for the User-Agent and About.
    static var version: String {
        let info = Bundle.main.infoDictionary
        return "\(info?["CFBundleShortVersionString"] as? String ?? "0")-ios.\(info?["CFBundleVersion"] as? String ?? "0")"
    }
}

/// Runs a blocking core call off the main thread.
func background<T>(_ work: @escaping () -> T) async -> T {
    await Task.detached(priority: .userInitiated) { work() }.value
}
