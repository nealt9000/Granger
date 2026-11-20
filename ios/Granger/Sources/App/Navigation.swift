import Foundation

/// A place on a tab's navigation stack.
enum Route: Hashable {
    /// An article's topic page (in front of the article, as on Android since LL2).
    case topic(lang: String, path: String)
    /// The full article in the reader.
    case reader(lang: String, path: String, fragment: String?)
}

/// What the user has read lately (Android's Continue / history): the last 50 articles, newest first.
@Observable
final class RecentStore {
    struct Entry: Codable, Hashable, Identifiable {
        let lang: String
        let path: String
        let title: String
        let at: Date
        var id: String { "\(lang):\(path)" }
    }

    private(set) var entries: [Entry]
    private let defaults: UserDefaults
    private static let key = "recentArticles"
    static let limit = 50

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        entries = (defaults.data(forKey: Self.key)).flatMap { try? JSONDecoder().decode([Entry].self, from: $0) } ?? []
    }

    func add(lang: String, path: String, title: String, at: Date = Date()) {
        entries.removeAll { $0.lang == lang && $0.path == path }
        entries.insert(Entry(lang: lang, path: path, title: title, at: at), at: 0)
        if entries.count > Self.limit { entries.removeLast(entries.count - Self.limit) }
        save()
    }

    func clear() {
        entries = []
        save()
    }

    private func save() {
        defaults.set(try? JSONEncoder().encode(entries), forKey: Self.key)
    }
}
