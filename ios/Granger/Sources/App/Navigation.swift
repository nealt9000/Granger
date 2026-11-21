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
    /// A book pack as a collection of its books.
    case bookPack(pack: String)
    case source(scope: String, domain: String?, family: String?, bookSet: String?)
    case quiz(subject: String)
    /// The connections quilt round a topic.
    case quilt(lang: String, path: String)
    /// One of the Library's collections (Plan I5 Task 6), by the shelves' id (`c:<default key>` for an app-made one).
    case collection(id: String)
    /// A book on the shelf in the e-book or PDF reader, at its saved place (Plan I5 Task 5).
    case book(id: String)
    /// Search with words already typed (the e-book reader's selection menu, as Android's SearchRoute(text)).
    case search(query: String)
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

    /// A book open lately (Plan I5 Task 6: the rail's open books), with where the reader is in it.
    struct BookEntry: Codable, Hashable, Identifiable {
        let id: String
        let title: String
        /// "Chapter 3 · 42%", from the saved place; blank until the book has one.
        var place: String
        var at: Date
    }

    private(set) var entries: [Entry]
    /// Books, newest first; kept apart from [entries], which Home's Continue and Ready offline read as articles.
    private(set) var books: [BookEntry]
    private let defaults: UserDefaults
    private static let key = "recentArticles"
    private static let booksKey = "recentBooks"
    static let limit = 50

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        entries = (defaults.data(forKey: Self.key)).flatMap { try? JSONDecoder().decode([Entry].self, from: $0) } ?? []
        books = (defaults.data(forKey: Self.booksKey)).flatMap { try? JSONDecoder().decode([BookEntry].self, from: $0) } ?? []
    }

    func addBook(id: String, title: String, at: Date = Date()) {
        let place = books.first { $0.id == id }?.place ?? ""
        books.removeAll { $0.id == id }
        books.insert(BookEntry(id: id, title: title, place: place, at: at), at: 0)
        if books.count > Self.limit { books.removeLast(books.count - Self.limit) }
        defaults.set(try? JSONEncoder().encode(books), forKey: Self.booksKey)
    }

    /// The reading place of a book open lately, as the reader saves it.
    func setPlace(bookId: String, place: String) {
        guard let i = books.firstIndex(where: { $0.id == bookId }), books[i].place != place else { return }
        books[i].place = place
        defaults.set(try? JSONEncoder().encode(books), forKey: Self.booksKey)
    }

    /// One item open lately: an article or a book.
    enum Item: Identifiable {
        case article(Entry)
        case book(BookEntry)
        var id: String { switch self { case let .article(e): "a:\(e.id)"; case let .book(b): "b:\(b.id)" } }
        var at: Date { switch self { case let .article(e): e.at; case let .book(b): b.at } }
    }

    /// Articles and books open lately, newest first: the rail's open items on the iPhone.
    func recentlyOpen(_ count: Int) -> [Item] {
        (entries.prefix(count).map(Item.article) + books.prefix(count).map(Item.book)).sorted { $0.at > $1.at }.prefix(count).map { $0 }
    }

    func add(lang: String, path: String, title: String, at: Date = Date()) {
        entries.removeAll { $0.lang == lang && $0.path == path }
        entries.insert(Entry(lang: lang, path: path, title: title, at: at), at: 0)
        if entries.count > Self.limit { entries.removeLast(entries.count - Self.limit) }
        save()
    }

    func clear() {
        entries = []
        books = []
        save()
        defaults.removeObject(forKey: Self.booksKey)
    }

    private func save() {
        defaults.set(try? JSONEncoder().encode(entries), forKey: Self.key)
    }
}

import SwiftUI

/// Recently open (Plan I3 builder's call 8; Android's open items on the rail): the last four articles and books, from
/// each tab's toolbar; a book reopens at its place, which its row says (Plan I5 Task 6).
struct RecentlyOpenMenu: View {
    @Environment(RecentStore.self) private var recent
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        if !recent.entries.isEmpty || !recent.books.isEmpty {
            Menu {
                Section("Recently open") {
                    ForEach(recent.recentlyOpen(4)) { item in
                        switch item {
                        case let .article(e):
                            Button(e.title) { openRoute(.reader(lang: e.lang, path: e.path, fragment: nil)) }
                        case let .book(b):
                            Button { openRoute(.book(id: b.id)) } label: {
                                Text(b.title)
                                if !b.place.isEmpty { Text(b.place) }
                                Image(systemName: "book.closed")
                            }
                            .accessibilityIdentifier("recent-book-\(b.id)")
                        }
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
        // A book on the shelf (Search's Books and titles): its reader.
        if packId.hasPrefix("book:") { return .book(id: packId) }
        if let lang = OnlineWikipediaArchive.companion.languageOf(packId: packId) { return .topic(lang: lang, path: path) }
        return .packTopic(pack: packId, path: path)
    }

    /// An article in the reader wherever it lives.
    static func read(packId: String, path: String, fragment: String? = nil) -> Route {
        if let lang = OnlineWikipediaArchive.companion.languageOf(packId: packId) { return .reader(lang: lang, path: path, fragment: fragment) }
        return .packReader(pack: packId, path: path, fragment: fragment)
    }
}
