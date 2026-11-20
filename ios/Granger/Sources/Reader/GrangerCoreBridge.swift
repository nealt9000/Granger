import Foundation
import GrangerCoreKit

/// The few core calls the web host needs, kept in one place so the host's tests needn't load the Kotlin core.
enum GrangerCoreBridge {
    /// Overridden by tests; the real one asks the core's ZimUrls.
    nonisolated(unsafe) static var parser: (String) -> (pack: String, path: String, fragment: String?)? = { url in
        guard let z = GrangerCoreEntry.shared.parseZimUrl(url: url) else { return nil }
        return (z.packId, z.path, z.fragment)
    }

    static func parse(_ url: String) -> (pack: String, path: String, fragment: String?)? { parser(url) }

    static func url(pack: String, path: String, fragment: String? = nil) -> String {
        GrangerCoreEntry.shared.zimUrl(packId: pack, path: path, fragment: fragment)
    }
}

/// Static page pieces from the core.
enum GrangerCoreEntryAccess {
    static var onlineCss: String { GrangerCoreEntry.shared.onlineCss }
}
