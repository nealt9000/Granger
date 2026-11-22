import Foundation
import GrangerCoreKit
import MediaPlayer
import SwiftUI

/// Music on the iPhone (Plan I6 Task 6, call 2) for SwiftUI: the core's `IosMusic` (the shared scan over three sources
/// and the music models), the picked folders' security-scoped bookmarks, imports into Documents/Music, Music library
/// access, and Rescan now's state, as Android's MediaSettings keeps it.
@Observable @MainActor
final class MusicCenter {
    let music: IosMusic
    /// Documents/Music, which the Files app shows as On My iPhone › Granger › Music.
    let folder: URL
    private(set) var folders: [Folder] = []
    private(set) var scan: Rescan = .idle
    private(set) var libraryStatus: MPMediaLibraryAuthorizationStatus = MPMediaLibrary.authorizationStatus()
    /// Music library songs left out at the last scan: protected or only in the cloud.
    private(set) var leftOut = 0
    private var tasks: [Task<Void, Never>] = []

    /// A picked folder: its bookmark, its name in Files, and whether it holds audiobooks.
    struct Folder: Codable, Identifiable, Hashable {
        let id: String
        let name: String
        let bookmark: Data
        let audiobooks: Bool
    }

    /// Rescan now's line, as Android's RescanState.
    enum Rescan: Equatable { case idle, running, done(songs: Int, audiobooks: Int), failed }

    private static let foldersKey = "media.folders"
    /// Folders whose security-scoped access is held while the app runs (the scan reads them in place).
    private var opened: [String: URL] = [:]

    init(library: IosLibrary, voice: IosVoice) {
        let fm = FileManager.default
        folder = fm.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("Music", isDirectory: true)
        try? fm.createDirectory(at: folder, withIntermediateDirectories: true)
        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("granger", isDirectory: true)
        music = IosMusic(library: library, voice: voice, natives: MusicNativesImpl(), musicDir: folder.path,
                         coversDir: support.appendingPathComponent("media-covers").path, guardDir: support.appendingPathComponent("media-scan").path)
        folders = (UserDefaults.standard.data(forKey: Self.foldersKey)).flatMap { try? JSONDecoder().decode([Folder].self, from: $0) } ?? []
        openFolders()
        let m = music
        tasks.append(Task { @MainActor [weak self] in for await n in m.leftOut { self?.leftOut = Int(n.int32Value) } })
        NotificationCenter.default.addObserver(forName: .MPMediaLibraryDidChange, object: nil, queue: .main) { _ in m.scanSoon() }
        if libraryStatus == .authorized { MPMediaLibrary.default().beginGeneratingLibraryChangeNotifications() }
        #if DEBUG
        debugHooks()
        #endif
        music.scanSoon()
    }

    // MARK: Picked folders

    /// Resolves every bookmark, holds its access open, and hands the folders to the scan.
    private func openFolders() {
        var shown: [PickedFolder] = []
        for f in folders {
            if opened[f.id] == nil {
                var stale = false
                guard let url = try? URL(resolvingBookmarkData: f.bookmark, options: [], relativeTo: nil, bookmarkDataIsStale: &stale) else { continue }
                _ = url.startAccessingSecurityScopedResource()
                opened[f.id] = url
                if stale, let fresh = try? url.bookmarkData() { replace(f, bookmark: fresh) }
            }
            if let url = opened[f.id] { shown.append(PickedFolder(path: url.path, name: f.name, audiobooks: f.audiobooks)) }
        }
        #if DEBUG
        if let debugFolder { shown.append(debugFolder) }
        #endif
        music.setFolders(list: shown)
    }

    private func replace(_ f: Folder, bookmark: Data) {
        guard let i = folders.firstIndex(of: f) else { return }
        folders[i] = Folder(id: f.id, name: f.name, bookmark: bookmark, audiobooks: f.audiobooks)
        save()
    }

    private func save() { UserDefaults.standard.set(try? JSONEncoder().encode(folders), forKey: Self.foldersKey) }

    /// A folder from Files (Android's Add a folder): its bookmark kept, its access held, then a scan that reads it.
    func addFolder(_ url: URL, audiobooks: Bool) {
        let scoped = url.startAccessingSecurityScopedResource()
        guard let bookmark = try? url.bookmarkData() else {
            if scoped { url.stopAccessingSecurityScopedResource() }
            scan = .failed
            return
        }
        let f = Folder(id: UUID().uuidString, name: url.lastPathComponent, bookmark: bookmark, audiobooks: audiobooks)
        folders.append(f)
        opened[f.id] = url
        save()
        openFolders()
        rescan()
    }

    /// Off the list, its access given back, and a scan (its songs go missing, their places and playlists kept).
    func removeFolder(_ f: Folder) {
        folders.removeAll { $0.id == f.id }
        opened.removeValue(forKey: f.id)?.stopAccessingSecurityScopedResource()
        save()
        openFolders()
        rescan()
    }

    // MARK: Imports

    /// Copies files picked in Files into Documents/Music (a name taken by another file gets a number), then scans.
    /// How many came in.
    @discardableResult
    func importFiles(_ urls: [URL]) async -> Int {
        let target = folder
        let count = await background {
            urls.reduce(0) { n, url in
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                return n + (Self.copy(url, into: target) ? 1 : 0)
            }
        }
        await runRescan()
        return count
    }

    nonisolated private static func copy(_ url: URL, into dir: URL) -> Bool {
        let fm = FileManager.default
        let base = url.deletingPathExtension().lastPathComponent
        let ext = url.pathExtension
        var dest = dir.appendingPathComponent(url.lastPathComponent)
        var n = 2
        while fm.fileExists(atPath: dest.path) {
            // The same file picked again: already here.
            let a = (try? fm.attributesOfItem(atPath: dest.path)[.size] as? NSNumber)?.int64Value
            let b = (try? fm.attributesOfItem(atPath: url.path)[.size] as? NSNumber)?.int64Value
            if a != nil, a == b { return false }
            dest = dir.appendingPathComponent(ext.isEmpty ? "\(base) \(n)" : "\(base) \(n).\(ext)")
            n += 1
        }
        return (try? fm.copyItem(at: url, to: dest)) != nil
    }

    // MARK: The Music library

    /// Asks for Music library access (Android's Allow access), then scans with the answer.
    func requestLibraryAccess() {
        MPMediaLibrary.requestAuthorization { status in
            Task { @MainActor [weak self] in
                guard let self else { return }
                self.libraryStatus = status
                if status == .authorized { MPMediaLibrary.default().beginGeneratingLibraryChangeNotifications() }
                self.rescan()
            }
        }
    }

    /// The app came back: access may have changed in Settings, and files may have been dropped into Documents/Music.
    func foregrounded() {
        let status = MPMediaLibrary.authorizationStatus()
        if status != libraryStatus { libraryStatus = status }
        music.scanSoon()
    }

    // MARK: Rescan now

    func rescan() { Task { await runRescan() } }

    func runRescan() async {
        scan = .running
        do {
            let counts = try await music.scan()
            scan = .done(songs: Int(counts.songs), audiobooks: Int(counts.audiobooks))
        } catch {
            NSLog("Granger: rescan failed")
            scan = .failed
        }
    }

    #if DEBUG
    /// UI tests (the Files picker can't be driven): `-debugImportMusic "<file on the Mac>|<file>"` imports those files as a
    /// pick would; `-debugMusicFolder <folder on the Mac>` reads that folder as a picked one for this launch only (the
    /// Simulator reads the Mac's disk, so it needs no bookmark, and a run leaves no folder behind for the next).
    private var debugFolder: PickedFolder?

    private func debugHooks() {
        let d = UserDefaults.standard
        if let path = d.string(forKey: "debugMusicFolder"), FileManager.default.fileExists(atPath: path) {
            debugFolder = PickedFolder(path: path, name: (path as NSString).lastPathComponent, audiobooks: false)
            openFolders()
        }
        if let raw = d.string(forKey: "debugImportMusic"), !raw.isEmpty {
            let urls = raw.split(separator: "|").map { URL(fileURLWithPath: String($0)) }.filter { FileManager.default.fileExists(atPath: $0.path) }
            Task { let n = await importFiles(urls); NSLog("debugImportMusic: %d", n) }
        }
    }
    #endif
}
