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
                group("Best matches", hits: model.best, all: $model.allBest, showAll: { "Show all \($0) best match\($0 == 1 ? "" : "es")" },
                      fewer: "Show fewer best matches")
                group("Titles", hits: model.titles, all: $model.allTitles, showAll: { "Show all \($0) title\($0 == 1 ? "" : "s")" },
                      fewer: "Show fewer titles")
                group("Articles", hits: model.pages, all: $model.allPages, showAll: { "Show all \($0) article match\($0 == 1 ? "" : "es")" },
                      fewer: "Show fewer article matches")
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
        .task(id: "\(query)|\(settings.wikiLanguage)|\(settings.canFetch)") {
            guard let core else { return }
            await model.search(query, lang: settings.wikiLanguage, core: core, online: settings.canFetch)
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
                    openRoute(.article(packId: hit.packId, path: hit.path))
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        HStack(spacing: 8) {
                            Text(hit.title).font(Spectral.regular(19)).foregroundStyle(palette.onBackground)
                            if let lang = hit.onlineLang { MarkView(lang: lang, title: hit.path) }
                        }
                        if let d = hit.detail {
                            Text(d).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
                        }
                        Text(hit.source).font(.caption).foregroundStyle(palette.outline)
                    }
                    .padding(.vertical, 2)
                }
                .accessibilityIdentifier("search-hit")
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
        async let bm: [SearchRow] = ((try? await library.best(query: q)) ?? []).map { m in
            SearchRow(packId: m.passage.sourceId, path: m.passage.location, title: PageTitles.shared.clean(title: m.passage.sourceTitle, packTitle: m.passage.packTitle, mainPage: false), detail: m.quote(),
                      source: m.passage.packTitle, onlineLang: nil)
        }
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
