import Foundation
import GrangerCoreKit

/// A place on a tab's navigation stack.
enum Route: Hashable {
    /// An article's topic page (in front of the article, as on Android since LL2).
    case topic(lang: String, path: String)
    /// A pack article's topic page (Plan I4 Task 5); Wikipedia online's go by language, `topic`.
    case packTopic(pack: String, path: String)
    /// The full article in the reader.
    case reader(lang: String, path: String, fragment: String?)
    /// A pack article in the reader.
    case packReader(pack: String, path: String, fragment: String?)
    /// The full timeline, opened at an article when given (Show in time, Plan H5b).
    case timeline(packId: String?, path: String?)
    /// Explore (Plan I3 Task 9): a subject's page, a room (facet nil: the whole subject's listing).
    case subject(id: String)
    case room(subject: String, facet: String?)
    /// Wander from an article, or anywhere with nil; inside a subject or room.
    case wander(packId: String?, path: String?)
    case wanderIn(subject: String, facet: String?)
    /// The Games screen, and a subject's quiz.
    case games
    /// The download queue (Plan I4 Task 6).
    case downloads
    /// Get more's hub, and one source's screen (CatalogScope by name) narrowed to a domain, family or book set.
    case getMore
    /// Outfitting: Ready offline, kept articles, the knowledge domains, storage (Plan I4 Task 8).
    case outfitting
    /// Explore's Your sources, and one pack's PDFs and EPUBs (Plan I4 Task 7).
    case yourSources
    case packFiles(pack: String)
    case source(scope: String, domain: String?, family: String?, bookSet: String?)
    case quiz(subject: String)
    /// The connections quilt round a topic.
    case quilt(lang: String, path: String)
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

import SwiftUI

/// Recently open (Plan I3 builder's call 8; Android's open items on the rail): the last four articles, from each
/// tab's toolbar.
struct RecentlyOpenMenu: View {
    @Environment(RecentStore.self) private var recent
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        if !recent.entries.isEmpty {
            Menu {
                Section("Recently open") {
                    ForEach(recent.entries.prefix(4)) { e in
                        Button(e.title) { openRoute(.reader(lang: e.lang, path: e.path, fragment: nil)) }
                    }
                }
            } label: {
                Image(systemName: "clock.arrow.circlepath")
            }
            .accessibilityLabel("Recently open")
            .accessibilityIdentifier("recently-open")
        }
    }
}

extension Route {
    /// An article's topic page wherever it lives: Wikipedia online by its language, a pack by its id.
    static func article(packId: String, path: String) -> Route {
        if let lang = OnlineWikipediaArchive.companion.languageOf(packId: packId) { return .topic(lang: lang, path: path) }
        return .packTopic(pack: packId, path: path)
    }

    /// An article in the reader wherever it lives.
    static func read(packId: String, path: String, fragment: String? = nil) -> Route {
        if let lang = OnlineWikipediaArchive.companion.languageOf(packId: packId) { return .reader(lang: lang, path: path, fragment: fragment) }
        return .packReader(pack: packId, path: path, fragment: fragment)
    }
}
