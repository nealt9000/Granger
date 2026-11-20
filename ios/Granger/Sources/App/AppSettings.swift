import Foundation
import Observation

/// What Settings holds, kept in UserDefaults (Android keeps these in its prefs and DataStore).
@Observable
final class AppSettings {
    private let defaults: UserDefaults

    var appearance: Appearance { didSet { defaults.set(appearance.rawValue, forKey: Keys.appearance) } }
    /// The Wikipedia read online: "en" or "es" (Android's knowledge language for online sources).
    var wikiLanguage: String { didSet { defaults.set(wikiLanguage, forKey: Keys.wikiLanguage) } }
    /// The reader's text size, as a share of normal (Android's Aa: 80–200 %).
    var textScale: Double { didSet { defaults.set(textScale, forKey: Keys.textScale) } }
    /// Local only (Plan O3): nothing goes out while it's on.
    var localOnly: Bool { didSet { defaults.set(localOnly, forKey: Keys.localOnly) } }
    /// The privacy line has been seen (Plan O5): until then nothing is asked of Wikimedia.
    var privacySeen: Bool { didSet { defaults.set(privacySeen, forKey: Keys.privacySeen) } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        appearance = Appearance(rawValue: defaults.string(forKey: Keys.appearance) ?? "") ?? .system
        wikiLanguage = defaults.string(forKey: Keys.wikiLanguage) ?? AppSettings.phoneWikiLanguage()
        textScale = defaults.object(forKey: Keys.textScale) as? Double ?? 1.0
        localOnly = defaults.bool(forKey: Keys.localOnly)
        privacySeen = defaults.bool(forKey: Keys.privacySeen)
    }

    /// Whether a request may go out now.
    var canFetch: Bool { privacySeen && !localOnly }

    /// Spanish when the phone lists Spanish before English, else English (Android: a second language is offered only
    /// when the phone lists one).
    static func phoneWikiLanguage() -> String {
        for id in Locale.preferredLanguages {
            let code = Locale(identifier: id).language.languageCode?.identifier ?? ""
            if code == "es" { return "es" }
            if code == "en" { return "en" }
        }
        return "en"
    }

    private enum Keys {
        static let appearance = "appearance"
        static let wikiLanguage = "wikiLanguage"
        static let textScale = "textScale"
        static let localOnly = "localOnly"
        static let privacySeen = "privacySeen"
    }
}
