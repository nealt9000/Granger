import GrangerCoreKit
import SwiftUI

/// Search (Android's Search for online Wikipedia): Titles and Articles as you type, recent searches under an empty field.
/// Packs and books join the results in I4 and I5.
struct SearchScreen: View {
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
            } else if !settings.canFetch {
                Text("Online results appear when you're connected.").foregroundStyle(palette.onSurfaceVariant)
            } else if model.searching && model.titles.isEmpty && model.pages.isEmpty {
                HStack { Spacer(); ProgressView(); Spacer() }
            } else if model.done && model.titles.isEmpty && model.pages.isEmpty {
                Text("Nothing in your library for that. Try fewer words.").foregroundStyle(palette.onSurfaceVariant)
            } else {
                group("Titles", hits: model.titles, all: $model.allTitles, showAll: { "Show all \($0) title\($0 == 1 ? "" : "s")" },
                      fewer: "Show fewer titles", excerpts: false)
                group("Articles", hits: model.pages, all: $model.allPages, showAll: { "Show all \($0) article match\($0 == 1 ? "" : "es")" },
                      fewer: "Show fewer article matches", excerpts: true)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Search")
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search your library")
        .searchFocused($focused)
        .onSubmit(of: .search) { model.remember(query) }
        .task(id: "\(query)|\(settings.wikiLanguage)|\(settings.canFetch)") {
            guard let core else { return }
            await model.search(query, lang: settings.wikiLanguage, core: core, allowed: settings.canFetch)
        }
        .onAppear {
            model.reloadRecent()
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

    private func group(_ title: String, hits: [WikimediaClient.Hit], all: Binding<Bool>, showAll: (Int) -> String, fewer: String,
                       excerpts: Bool) -> some View {
        let shown = all.wrappedValue ? hits : Array(hits.prefix(SearchModel.preview))
        return Section {
            ForEach(shown, id: \.key) { hit in
                Button {
                    model.remember(query)
                    openRoute(.topic(lang: settings.wikiLanguage, path: hit.key))
                } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text(hit.title).font(Spectral.regular(19)).foregroundStyle(palette.onBackground)
                        if excerpts, let e = hit.excerpt {
                            Text(e).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
                        } else if let d = hit.description_ {
                            Text(d).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(2)
                        }
                        Text("Wikipedia").font(.caption).foregroundStyle(palette.outline)
                    }
                    .padding(.vertical, 2)
                }
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

@Observable
final class SearchModel {
    static let preview = 5
    static let limit = 20
    var titles: [WikimediaClient.Hit] = []
    var pages: [WikimediaClient.Hit] = []
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

    @MainActor
    func search(_ raw: String, lang: String, core: Core, allowed: Bool) async {
        let q = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        done = false
        allTitles = false
        allPages = false
        guard !q.isEmpty, allowed else { titles = []; pages = []; return }
        // A short pause, so typing doesn't send a request a letter.
        try? await Task.sleep(for: .milliseconds(300))
        if Task.isCancelled { return }
        searching = true
        let client = core.client
        async let t = background { client.titles(lang: lang, query: q, limit: Int32(Self.limit)) }
        async let p = background { client.pages(lang: lang, query: q, limit: Int32(Self.limit)) }
        let (tt, pp) = await (t, p)
        if Task.isCancelled { return }
        titles = tt
        pages = pp
        searching = false
        done = true
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
