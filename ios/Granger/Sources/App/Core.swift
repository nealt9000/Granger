import Foundation
import GrangerCoreKit

/// The shared Kotlin core, as the app uses it: one Wikimedia client and one online Wikipedia per language. Every call
/// into it blocks, so screens call it through [background].
final class Core {
    let client: WikimediaClient
    private var archives: [String: OnlineWikipediaArchive] = [:]
    private let lock = NSLock()

    init(version: String) {
        client = GrangerCoreEntry.shared.wikimedia(version: version)
    }

    /// Wikipedia in [lang], read online.
    func wikipedia(_ lang: String) -> OnlineWikipediaArchive {
        lock.lock(); defer { lock.unlock() }
        if let a = archives[lang] { return a }
        let a = GrangerCoreEntry.shared.onlineWikipedia(lang: lang, client: client)
        archives[lang] = a
        return a
    }

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
