import Foundation

/// Plan I6 Task 9: `-resetData <token>` (debug builds only) starts the app from clean data, so a UI test that failed
/// half way can't leave highlights, books, packs, songs or a saved player behind for the next. It clears the library and
/// downloads databases and the rest of Application Support/granger, Documents (Packs, Books, Music, `.granger/media`),
/// Caches/granger, the temporary folder, the app's defaults (the player's saved state and the picked folders' bookmarks
/// with them) and the app group's defaults and folder (Local only's `network.localOnly`, the widget's snapshot).
///
/// Kept: Wikipedia online's index set and the last good catalog (`indexes`, `catalog`), which are downloads, not the
/// reader's state; fetching 19 MB again for every online test would only slow the suite.
///
/// The token stands for one test: the UI tests pass the same one on every launch of a test, so a relaunch inside a test
/// (a saved place, a killed download) finds what the first launch left, and the next test's new token clears again.
/// Launch arguments live in their own domain, so the ones a test passes still apply after the clear.
enum DebugReset {
    /// Runs before anything reads the defaults or opens a database (the app's first stored property).
    static func runIfAsked() -> Bool {
        #if DEBUG
        guard let token = UserDefaults.standard.string(forKey: "resetData"), !token.isEmpty else { return false }
        let fm = FileManager.default
        let library = fm.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        let mark = library.appendingPathComponent("debug-reset-token")
        if (try? String(contentsOf: mark, encoding: .utf8)) == token { return false }

        let support = fm.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0].appendingPathComponent("granger", isDirectory: true)
        empty(support, keeping: ["indexes", "catalog"])
        empty(fm.urls(for: .documentDirectory, in: .userDomainMask)[0])
        empty(fm.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("granger", isDirectory: true))
        empty(fm.temporaryDirectory)
        if let id = Bundle.main.bundleIdentifier { UserDefaults.standard.removePersistentDomain(forName: id) }
        AppGroup.defaults.removePersistentDomain(forName: AppGroup.id)
        if let group = AppGroup.folder { empty(group, keeping: ["Library"]) }
        try? token.write(to: mark, atomically: true, encoding: .utf8)
        NSLog("Granger: -resetData cleared the app's data")
        return true
        #else
        return false
        #endif
    }

    /// Removes everything in [folder] but the names in [keeping]; the folder itself stays.
    private static func empty(_ folder: URL, keeping: Set<String> = []) {
        let fm = FileManager.default
        guard let names = try? fm.contentsOfDirectory(atPath: folder.path) else { return }
        for name in names where !keeping.contains(name) {
            try? fm.removeItem(at: folder.appendingPathComponent(name))
        }
    }
}
