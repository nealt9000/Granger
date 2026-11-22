import Foundation
import Observation

/// What Settings holds, kept in UserDefaults (Android keeps these in its prefs and DataStore).
@Observable
final class AppSettings {
    private let defaults: UserDefaults

    var appearance: Appearance { didSet { defaults.set(appearance.rawValue, forKey: Keys.appearance) } }
    /// The Wikipedia read online: "en" or "es" (Android's knowledge language for online sources).
    var wikiLanguage: String { didSet { defaults.set(wikiLanguage, forKey: Keys.wikiLanguage); LibraryGate.shared.languages = [wikiLanguage] } }
    /// Offline pictures (Plan O2 Task 2): whether what you keep, and what's saved for now, carries its pictures.
    var savePictures: Bool { didSet { defaults.set(savePictures, forKey: Keys.savePictures); LibraryGate.shared.savePictures = savePictures } }
    /// Saved for now's cap in MB (Android's 100 / 300 / 1000, 300 by default).
    var savedCapMb: Int { didSet { defaults.set(savedCapMb, forKey: Keys.savedCapMb); LibraryGate.shared.savedCapMb = savedCapMb } }
    static let capChoices = [100, 300, 1000]
    /// Keep adult topics out of suggestions (Plan AF; on by default): Explore, Home, Timeline and games leave them out.
    var keepAdultOff: Bool { didSet { defaults.set(keepAdultOff, forKey: Keys.keepAdultOff); LibraryGate.shared.keepAdultOff = keepAdultOff } }
    /// The reader's text size, as a share of normal (Android's Aa: 80–200 %).
    var textScale: Double { didSet { defaults.set(textScale, forKey: Keys.textScale) } }
    /// Local only (Plan O3): nothing goes out while it's on. Kept in the app group's defaults since Plan I6 Task 8, so
    /// Control Center's control can flip it; the app follows a flip made there ([followLocalOnly]).
    var localOnly: Bool {
        didSet {
            guard localOnly != oldValue else { return }
            if SharedLocalOnly.value != localOnly { SharedLocalOnly.set(localOnly) }
            NetworkGate.shared.open = canFetch
            AppSettings.localOnlyChanged?(localOnly)
        }
    }
    /// Told when Local only changes, from here or the control (the widget snapshot and Control Center redraw).
    nonisolated(unsafe) static var localOnlyChanged: ((Bool) -> Void)?
    /// Big downloads on Wi-Fi only (Android's Downloads setting, on by default): a waiting pack never uses mobile data.
    var wifiOnly: Bool { didSet { defaults.set(wifiOnly, forKey: Keys.wifiOnly); LibraryGate.shared.wifiOnly = wifiOnly } }
    /// The privacy line has been seen (Plan O5): until then nothing is asked of Wikimedia.
    var privacySeen: Bool { didSet { defaults.set(privacySeen, forKey: Keys.privacySeen); NetworkGate.shared.open = canFetch } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        appearance = Appearance(rawValue: defaults.string(forKey: Keys.appearance) ?? "") ?? .system
        wikiLanguage = defaults.string(forKey: Keys.wikiLanguage) ?? AppSettings.phoneWikiLanguage()
        textScale = defaults.object(forKey: Keys.textScale) as? Double ?? 1.0
        localOnly = AppSettings.sharedLocalOnly(defaults)
        privacySeen = defaults.bool(forKey: Keys.privacySeen)
        savePictures = defaults.object(forKey: Keys.savePictures) as? Bool ?? true
        savedCapMb = defaults.object(forKey: Keys.savedCapMb) as? Int ?? 300
        keepAdultOff = defaults.object(forKey: Keys.keepAdultOff) as? Bool ?? true
        wifiOnly = defaults.object(forKey: Keys.wifiOnly) as? Bool ?? true
        NetworkGate.shared.open = canFetch
        LibraryGate.shared.wifiOnly = wifiOnly
        LibraryGate.shared.languages = [wikiLanguage]
        LibraryGate.shared.savePictures = savePictures
        LibraryGate.shared.savedCapMb = savedCapMb
        LibraryGate.shared.keepAdultOff = keepAdultOff
    }

    /// Local only from the group's defaults. A `-localOnly` launch argument (the UI tests) wins and is copied there; a
    /// phone that had it before Plan I6 Task 8 keeps it (moved from the app's own defaults).
    private static func sharedLocalOnly(_ defaults: UserDefaults) -> Bool {
        if let pinned = UserDefaults.standard.volatileDomain(forName: UserDefaults.argumentDomain)[Keys.localOnly] {
            let on: Bool
            if let b = pinned as? Bool { on = b } else { on = ["yes", "true", "1"].contains("\(pinned)".lowercased()) }
            AppGroup.defaults.set(on, forKey: SharedLocalOnly.key)
            return on
        }
        if AppGroup.defaults.object(forKey: SharedLocalOnly.key) == nil {
            AppGroup.defaults.set(defaults.bool(forKey: Keys.localOnly), forKey: SharedLocalOnly.key)
        }
        return SharedLocalOnly.value
    }

    /// Follows Local only flipped outside the app (Control Center's control): its Darwin notification, and coming back
    /// to the foreground in case one was missed.
    func followLocalOnly() {
        AppSettings.follower = { [weak self] in
            DispatchQueue.main.async { self?.refreshLocalOnly() }
        }
        CFNotificationCenterAddObserver(CFNotificationCenterGetDarwinNotifyCenter(), nil, { _, _, _, _, _ in AppSettings.follower?() },
                                        SharedLocalOnly.changed as CFString, nil, .deliverImmediately)
    }

    func refreshLocalOnly() {
        let shared = SharedLocalOnly.value
        if shared != localOnly { localOnly = shared }
    }

    nonisolated(unsafe) private static var follower: (() -> Void)?

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
        static let savePictures = "savePictures"
        static let savedCapMb = "savedCapMb"
        static let keepAdultOff = "keepAdultOff"
        static let wifiOnly = "wifiOnly"
    }
}

/// The one gate every request passes (Android's NetworkGate, Plan O3): open only after the privacy line and with
/// Local only off. AppSettings keeps it current; the core reads it from any thread.
final class NetworkGate: @unchecked Sendable {
    static let shared = NetworkGate()
    private let lock = NSLock()
    private var _open = false

    var open: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _open }
        set { lock.lock(); _open = newValue; lock.unlock() }
    }
}

/// The settings the library reads from any thread (the knowledge languages, Offline pictures, Saved for now's cap),
/// kept current by AppSettings like [NetworkGate].
final class LibraryGate: @unchecked Sendable {
    static let shared = LibraryGate()
    private let lock = NSLock()
    private var _languages = ["en"]
    private var _savePictures = true
    private var _savedCapMb = 300
    private var _keepAdultOff = true
    private var _wifiOnly = true

    var languages: [String] {
        get { lock.lock(); defer { lock.unlock() }; return _languages }
        set { lock.lock(); _languages = newValue; lock.unlock() }
    }
    var savePictures: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _savePictures }
        set { lock.lock(); _savePictures = newValue; lock.unlock() }
    }
    var savedCapMb: Int {
        get { lock.lock(); defer { lock.unlock() }; return _savedCapMb }
        set { lock.lock(); _savedCapMb = newValue; lock.unlock() }
    }
    var keepAdultOff: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _keepAdultOff }
        set { lock.lock(); _keepAdultOff = newValue; lock.unlock() }
    }
    var wifiOnly: Bool {
        get { lock.lock(); defer { lock.unlock() }; return _wifiOnly }
        set { lock.lock(); _wifiOnly = newValue; lock.unlock() }
    }
}
