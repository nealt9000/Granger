import AppIntents
import Foundation

// Plan I6 Task 8 (call 7): what the app and its widget extension share. This folder is compiled into both targets;
// the extension never loads the Kotlin core (memory and size), so everything here is plain Swift over the app group.

/// The app group both targets belong to (`group.<bundle prefix>`, so `group.app.granger` by default), from Info.plist.
enum AppGroup {
    static let id: String = Bundle.main.object(forInfoDictionaryKey: "GrangerAppGroup") as? String ?? "group.app.granger"

    /// The group's defaults; the process's own when the group isn't there (a build without the entitlement).
    static let defaults: UserDefaults = UserDefaults(suiteName: id) ?? .standard

    /// The group's shared folder: the widget's snapshot and its cover.
    static var folder: URL? { FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: id) }
}

/// Local only in the group's defaults (call 7), so Control Center's control can flip it from the extension. The key isn't
/// the app's old `localOnly`: a launch argument of that name (the UI tests pin it) would shadow the group's value in the
/// app, and then a flip from the control could never be seen. A flip posts a Darwin notification the app listens for.
enum SharedLocalOnly {
    static let key = "network.localOnly"
    static let changed = "app.granger.localOnly.changed"

    static var value: Bool { AppGroup.defaults.bool(forKey: key) }

    static func set(_ on: Bool) {
        AppGroup.defaults.set(on, forKey: key)
        CFNotificationCenterPostNotification(CFNotificationCenterGetDarwinNotifyCenter(), CFNotificationName(changed as CFString), nil, nil, true)
    }
}

/// The Local only control's kind, for reloading it.
enum LocalOnlyControlKind {
    static let kind = "app.granger.localOnly"
}

/// What the player widget shows, written by the app when playback changes and read by the widget (Android's
/// WidgetModelStore). Text is already in words; the cover is a small copy in the group folder.
struct WidgetSnapshot: Codable, Equatable {
    var title: String
    var subtitle: String
    var playing: Bool
    /// "next" (music), "back" (a book, the voice) or "none" (nothing loaded).
    var secondary: String
    var progress: Double?
    /// Nothing ever played: "Nothing playing" / "Open Granger".
    var empty: Bool
    var hasCover: Bool

    static let nothing = WidgetSnapshot(title: "", subtitle: "", playing: false, secondary: "none", progress: nil, empty: true, hasCover: false)

    static var fileURL: URL? { AppGroup.folder?.appendingPathComponent("widget.json") }
    static var coverURL: URL? { AppGroup.folder?.appendingPathComponent("widget-cover.jpg") }

    static func load() -> WidgetSnapshot {
        guard let url = fileURL, let data = try? Data(contentsOf: url), let s = try? JSONDecoder().decode(WidgetSnapshot.self, from: data) else { return .nothing }
        return s
    }

    func save() {
        guard let url = WidgetSnapshot.fileURL, let data = try? JSONEncoder().encode(self) else { return }
        try? data.write(to: url, options: .atomic)
    }

    /// The widget's kind, for reloading its timelines.
    static let kind = "GrangerPlayer"
}

/// The app's side of the player intents: set by the app at launch, nil in the extension. The intents below are
/// `AudioPlaybackIntent`s, which the system runs in the app's process, so the hub is there to act on.
enum PlayerIntentHandlers {
    @MainActor static var playPause: (() -> Void)?
    @MainActor static var secondary: (() -> Void)?
}

/// The player widget's play/pause (Android's PlayPauseAction): the loaded player, else the saved one resumes.
struct PlayPauseIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Play or pause"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await MainActor.run { PlayerIntentHandlers.playPause?() }
        return .result()
    }
}

/// The player widget's second button (Android's SecondaryAction): the next song, or back the skip interval in a book.
struct SecondaryIntent: AudioPlaybackIntent {
    static let title: LocalizedStringResource = "Next or back"
    static let isDiscoverable = false

    func perform() async throws -> some IntentResult {
        await MainActor.run { PlayerIntentHandlers.secondary?() }
        return .result()
    }
}

/// Control Center's Local only control (Android's Quick Settings tile): sets Local only in the group's defaults, and the
/// app follows through the Darwin notification.
struct SetLocalOnlyIntent: SetValueIntent {
    static let title: LocalizedStringResource = "Local only"
    static let isDiscoverable = false

    @Parameter(title: "Local only")
    var value: Bool

    init() {}
    init(value: Bool) { self.value = value }

    func perform() async throws -> some IntentResult {
        SharedLocalOnly.set(value)
        return .result()
    }
}
