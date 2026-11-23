import GrangerCoreKit
import SwiftUI

/// Search (Android's Search for online Wikipedia): Titles and Articles as you type, recent searches under an empty field.
/// Packs' results lead (Plan I4 Task 5); books join in I5.
struct SearchScreen: View {
    /// Words already typed (a book's selection menu); nil for the Search tab.
    var initial: String? = nil
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(AppSettings.self) private var settings
    @Environment(\.openRoute) private var openRoute
    @State private var model = SearchModel()
    @State private var query = ""
    @FocusState private var focused: Bool

    var body: some View {
        List {
            if query.trimmingCharacters(in: .whitespaces).isEmpty {
                recent
            } else if model.searching && model.titles.isEmpty && model.pages.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }
            } else if model.done && model.titles.isEmpty && model.pages.isEmpty && model.best.isEmpty {
                Text(settings.canFetch ? "Nothing in your library for that. Try fewer words." : "Nothing on this phone for that. Online results appear when you're connected.")
                    .foregroundStyle(palette.onSurfaceVariant)
            } else {
                group(L("Best matches"), hits: model.best, all: $model.allBest, showAll: { L("Show all \($0) best matches") },
                      fewer: L("Show fewer best matches"))
                group(L("Titles"), hits: model.titles, all: $model.allTitles, showAll: { L("Show all \($0) titles") },
                      fewer: L("Show fewer titles"))
                group(L("Articles"), hits: model.pages, all: $model.allPages, showAll: { L("Show all \($0) article matches") },
                      fewer: L("Show fewer article matches"))
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Search")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { RecentlyOpenMenu() } }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search your library")
        .searchFocused($focused)
        .onSubmit(of: .search) { model.remember(query) }
        .task(id: "\(query)|\(settings.knowledge.joined(separator: ","))|\(settings.canFetch)") {
            guard let core else { return }
            await model.search(query, lang: settings.mainLanguage, core: core, online: settings.canFetch)
        }
        .task {
            // The embedder loads as Search opens, so the first query's meaning isn't the slow one (Android's warm).
            guard let core else { return }
            try? await core.library.warmBest()
        }
        .onAppear {
            model.reloadRecent()
            if let initial, query.isEmpty { query = initial }
            if query.isEmpty { focused = true }
        }
    }

    @ViewBuilder private var recent: some View {
        if !model.recent.isEmpty {
            Section {
                ForEach(model.recent, id: \.self) { r in
                    Button { query = r } label: { Text(r).font(Spectral.regular(18)).foregroundStyle(palette.onBackground) }
                }
            } header: {
                Text("Recent").font(Spectral.medium(16)).foregroundStyle(palette.tertiary)
            }
        }
    }

    private func group(_ title: String, hits: [SearchRow], all: Binding<Bool>, showAll: (Int) -> String, fewer: String) -> some View {
        let shown = all.wrappedValue ? hits : Array(hits.prefix(SearchModel.preview))
        return Section {
            ForEach(shown) { hit in
                Button {
                    model.remember(query)
                    // A book's Best match opens the book at its passage (Android's CitationOpener); anything else its page.
                    if let book = hit.book { openRoute(.bookAt(id: hit.packId, passage: book)) }
                    else { openRoute(.article(packId: hit.packId, path: hit.path)) }
                } label: {
                    HStack(alignment: .firstTextBaseline, spacing: 12) {
                        if let icon = hit.icon {
                            Image(systemName: icon).foregroundStyle(palette.onSurfaceVariant).accessibilityHidden(true)
                        }
                        VStack(alignment: .leading, spacing: 4) {
                            HStack(spacing: 8) {
                                Text(hit.title).font(Spectral.regular(19)).foregroundStyle(palette.onBackground)
                                if let lang = hit.onlineLang { MarkView(lang: lang, title: hit.path) }
                            }
                            if let d = hit.detail {
                                Text(d).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
                            }
                            if !hit.source.isEmpty { Text(hit.source).font(.caption).foregroundStyle(palette.outline) }
                        }
                    }
                    .padding(.vertical, 2)
                }
                .accessibilityIdentifier(hit.book != nil ? "search-book-hit" : "search-hit")
            }
            if hits.count > SearchModel.preview {
                Button(all.wrappedValue ? fewer : showAll(hits.count)) { all.wrappedValue.toggle() }
                    .font(Spectral.medium(17))
                    .foregroundStyle(palette.primary)
            }
        } header: {
            if !hits.isEmpty { Text(title).font(Spectral.medium(16)).foregroundStyle(palette.tertiary) }
        }
    }
}

/// One search result, from a pack or Wikipedia online: where it opens, its title, a line under it, and its source.
struct SearchRow: Identifiable, Hashable {
    let packId: String
    let path: String
    let title: String
    let detail: String?
    let source: String
    /// Wikipedia online's language, for its mark; nil for a pack's article (on this phone already).
    let onlineLang: String?
    /// A book's Best match: where in the book it opens; nil for an article.
    var book: BookPassage? = nil
    /// The kind's icon (Best matches, as Android's): an article or a book; nil for none.
    var icon: String? = nil
    var id: String { "\(packId)|\(path)" }

    /// A snippet's words without its `<b>` highlights.
    static func plain(_ html: String?) -> String? {
        guard let html, !html.isEmpty else { return nil }
        return html.replacingOccurrences(of: "<[^>]+>", with: "", options: .regularExpression)
    }
}

@Observable
final class SearchModel {
    static let preview = 5
    static let limit = 20
    var best: [SearchRow] = []
    var allBest = false
    var titles: [SearchRow] = []
    var pages: [SearchRow] = []
    var allTitles = false
    var allPages = false
    var searching = false
    var done = false
    private(set) var recent: [String]
    private let defaults: UserDefaults
    private static let key = "recentSearches"

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        recent = defaults.stringArray(forKey: Self.key) ?? []
    }

    /// The packs' titles and articles (Plan I4 Task 5), then Wikipedia online's when [online]; an article in both is listed
    /// once, the pack's first (it opens with no signal).
    @MainActor
    func search(_ raw: String, lang: String, core: Core, online: Bool) async {
        let q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        done = false
        allTitles = false
        allBest = false
        allPages = false
        guard !q.isEmpty else { best = []; titles = []; pages = []; return }
        // A short pause, so typing doesn't send a request a letter.
        try? await Task.sleep(for: .milliseconds(300))
        if Task.isCancelled { return }
        searching = true
        let client = core.client
        let packs = core.library.packSearch
        let onlinePack = core.pack(for: lang)
        async let pt: [SearchRow] = (try? await packs.titles(query: q))?.hits.map(Self.row) ?? []
        async let pp: [SearchRow] = (try? await packs.fullText(query: q))?.map(Self.row) ?? []
        let library = core.library
        async let bm: [SearchRow] = ((try? await library.best(query: q)) ?? []).map(Self.bestRow)
        async let ot: [SearchRow] = online ? background { client.titles(lang: lang, query: q, limit: Int32(Self.limit)) }
            .map { SearchRow(packId: onlinePack, path: $0.key, title: $0.title, detail: $0.description_, source: "Wikipedia", onlineLang: lang) } : []
        async let op: [SearchRow] = online ? background { client.pages(lang: lang, query: q, limit: Int32(Self.limit)) }
            .map { SearchRow(packId: onlinePack, path: $0.key, title: $0.title, detail: $0.excerpt, source: "Wikipedia", onlineLang: lang) } : []
        let (packTitles, packPages, onlineTitles, onlinePages) = await (pt, pp, ot, op)
        if Task.isCancelled { return }
        titles = Self.merged(packTitles, onlineTitles)
        pages = Self.merged(packPages, onlinePages)
        searching = false
        done = true
        // Best matches rank passages, slower than titles: they join when ready (Android shows them as they come too).
        let bestRows = await bm
        if Task.isCancelled { return }
        best = bestRows
    }

    /// A Best match: an article, or a book's chapter that opens at its passage (Android's row: the kind's icon, the title,
    /// the sentence, then the pack or the book's collection).
    static func bestRow(_ m: BestMatches.Match) -> SearchRow {
        let p = m.passage
        let book = p.kind == .book
        return SearchRow(packId: p.sourceId, path: p.location,
                         title: book ? p.sourceTitle : PageTitles.shared.clean(title: p.sourceTitle, packTitle: p.packTitle, mainPage: false),
                         detail: m.quote(), source: p.packTitle.isEmpty ? p.meta.collection : p.packTitle, onlineLang: nil,
                         book: book ? BookPassage(href: p.location, quote: m.quote() ?? "", before: "", progression: p.progression) : nil,
                         icon: book ? "book.closed" : "doc.text")
    }

    private static func row(_ h: SearchHit) -> SearchRow {
        SearchRow(packId: h.packId, path: h.path, title: h.title, detail: SearchRow.plain(h.snippet), source: h.packTitle, onlineLang: nil)
    }

    /// [first] then those of [then] whose title isn't already listed.
    static func merged(_ first: [SearchRow], _ then: [SearchRow]) -> [SearchRow] {
        var ids = Set<String>()
        var out: [SearchRow] = []
        for r in first where ids.insert(r.id).inserted { out.append(r) }
        var seen = Set(out.map { $0.title.lowercased() })
        for r in then where seen.insert(r.title.lowercased()).inserted && ids.insert(r.id).inserted { out.append(r) }
        return out
    }

    /// Remembers a search (the last 10, newest first).
    func remember(_ raw: String) {
        let q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return }
        recent.removeAll { $0.caseInsensitiveCompare(q) == .orderedSame }
        recent.insert(q, at: 0)
        if recent.count > 10 { recent.removeLast(recent.count - 10) }
        defaults.set(recent, forKey: Self.key)
    }

    func reloadRecent() {
        recent = defaults.stringArray(forKey: Self.key) ?? []
    }

    func clearRecent() {
        recent = []
        defaults.removeObject(forKey: Self.key)
    }
}
