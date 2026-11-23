import GrangerCoreKit
import SwiftUI

/// Search, Android's screen over Android's model (the shared `SearchViewModel`, Plan I7 Task 7c): recent searches under
/// an empty field; then "Did you mean", the By kind chips, the medical banner, the Define card, Best matches (with
/// "Looking…" while they load), Titles (with their other copies), Articles, the offline line, Books, Music and
/// Audiobooks, each with Show all / fewer, and the packs searched by title only. No mic yet (I8).
struct SearchScreen: View {
    /// Words already typed (a book's selection menu); nil for the Search tab.
    var initial: String? = nil
    @Environment(\.core) private var core
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(AppSettings.self) private var settings
    @Environment(\.openRoute) private var openRoute
    @State private var holder = SearchHolder()
    @State private var query = ""
    /// A group just folded with Show fewer: its first row, scrolled back to when the list had gone past it (Android's).
    @State private var folded: AnyHashable?
    @FocusState private var focused: Bool

    var body: some View {
        ScrollViewReader { proxy in
            List {
                if let ui = holder.ui, let model = holder.model { content(ui, model) }
            }
            .onChange(of: folded) { _, target in
                guard let target else { return }
                proxy.scrollTo(target, anchor: nil)
                folded = nil
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .scrollDismissesKeyboard(.immediately)
        .background(palette.background)
        .navigationTitle("Search")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { RecentlyOpenMenu() } }
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search your library")
        .searchFocused($focused)
        .onSubmit(of: .search) { holder.model?.refresh() }
        .onChange(of: query) { _, q in if q != holder.model?.searchText { holder.model?.onQueryChange(text: q) } }
        .onChange(of: settings.canFetch) { _, _ in core?.library.reachChanged() }
        .onAppear {
            guard let core else { return }
            if holder.model == nil {
                let start = initial ?? ""
                query = start
                holder.start(core, initial: start)
                if start.isEmpty { focused = true }
            } else {
                // Back from something opened since (a book indexed, a pack added): the query is searched again.
                holder.model?.onShown()
            }
        }
    }

    @ViewBuilder private func content(_ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        let blank = ui.query.trimmingCharacters(in: .whitespaces).isEmpty
        if ui.searching {
            ProgressView().progressViewStyle(.linear).tint(palette.primary)
                .listRowSeparator(.hidden).listRowBackground(palette.background).accessibilityIdentifier("search-searching")
        }
        if let name = ui.didYouMean {
            // Tapped: the field shows the name and the model searches it (the field is never set from the model's
            // state, which lags fast typing).
            Button { query = name; model.acceptSuggestion() } label: {
                Text(SearchLines.shared.didYouMean(name: name).words).font(Spectral.regular(18)).foregroundStyle(palette.onBackground)
            }
            .accessibilityIdentifier("search-did-you-mean")
        }
        if blank {
            recent(ui.recent)
        } else {
            if ui.nothingFound { nothingFound(ui) }
            // Plan SQ Task 5.3: chips narrow the results to one kind, once the query found more than one.
            if ui.kinds.count > 1 || ui.filter != .all { chips(ui, model) }
            // Plan SQ Decision 8: a medical query's results carry Ask's banner; Search only ever quotes.
            if ui.medical && !ui.nothingFound { medicalBanner }
            if let entry = ui.define, ui.showing(kind: .articles) { defineCard(entry, model) }
            best(ui, model)
            titles(ui, model)
            articles(ui, model)
            // Offline: the phone's results above, and a line that online ones come with a connection (Plan O3).
            if !settings.canFetch || !NetworkPath.shared.connected {
                Text("Online results appear when you're connected.")
                    .font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                    .listRowSeparator(.hidden).listRowBackground(palette.background).accessibilityIdentifier("search-offline")
            }
            books(ui, model)
            media(ui, model)
            if !ui.nothingFound && !ui.searching, let note = SearchLines.shared.titleOnly(titles: ui.titleOnly) {
                Text(note.words).font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                    .listRowSeparator(.hidden).listRowBackground(palette.background).accessibilityIdentifier("search-title-only")
            }
        }
    }

    // MARK: - Above the results

    @ViewBuilder private func recent(_ recent: [String]) -> some View {
        if !recent.isEmpty {
            Section {
                ForEach(recent, id: \.self) { r in
                    Button { query = r } label: { Text(r).font(Spectral.regular(18)).foregroundStyle(palette.onBackground) }
                }
            } header: {
                header(L("Recent"))
            }
        }
    }

    /// The search found nothing at all: one plain line, Get more, and the packs searched by title only.
    private func nothingFound(_ ui: SearchViewModel.State) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Nothing in your library for that. Try fewer words.").foregroundStyle(palette.onSurfaceVariant)
            Button("Get more") { openRoute(.getMore) }
                .buttonStyle(.borderless).font(Spectral.medium(17)).foregroundStyle(palette.primary)
                .accessibilityIdentifier("search-get-more")
            if let note = SearchLines.shared.titleOnly(titles: ui.titleOnly) {
                Text(note.words).font(.footnote).foregroundStyle(palette.onSurfaceVariant)
            }
        }
        .padding(.vertical, 8)
        .listRowSeparator(.hidden)
        .listRowBackground(palette.background)
    }

    /// All, then each kind the query found, and the one picked.
    private func chips(_ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        let shown: [SearchFilter] = [.all, .articles, .books, .media].filter { $0 == .all || ui.kinds.contains($0) || $0 == ui.filter }
        return ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(shown, id: \.self) { f in
                    let picked = f == ui.filter
                    Button { model.setFilter(filter: f) } label: {
                        HStack(spacing: 4) {
                            if picked { Image(systemName: "checkmark").accessibilityHidden(true) }
                            Text(Self.chipLabel(f))
                        }
                        .font(.subheadline.weight(.medium))
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        .foregroundStyle(picked ? palette.onSecondaryContainer : palette.onSurfaceVariant)
                        .background(picked ? palette.secondaryContainer : .clear, in: RoundedRectangle(cornerRadius: 8))
                        .overlay(RoundedRectangle(cornerRadius: 8).stroke(picked ? .clear : palette.outline, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(picked ? .isSelected : [])
                    .accessibilityIdentifier("search-filter-\(Self.chipId(f))")
                }
            }
            .padding(.vertical, 4)
        }
        .listRowSeparator(.hidden)
        .listRowBackground(palette.background)
    }

    static func chipLabel(_ f: SearchFilter) -> String {
        switch f {
        case .all: L("All")
        case .articles: L("Articles")
        case .books: L("Books")
        case .media: L("Media")
        }
    }

    private static func chipId(_ f: SearchFilter) -> String {
        switch f {
        case .all: "all"
        case .articles: "articles"
        case .books: "books"
        case .media: "media"
        }
    }

    /// Ask's medical banner over a medical query's results.
    private var medicalBanner: some View {
        Text(SearchLines.shared.medical.words)
            .font(.subheadline)
            .foregroundStyle(palette.onTertiaryContainer)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(palette.tertiaryContainer, in: RoundedRectangle(cornerRadius: 8))
            .listRowSeparator(.hidden)
            .listRowBackground(palette.background)
            .accessibilityIdentifier("search-medical")
    }

    /// Android's Define card: "Define", the headword, the start of its senses, and the Wiktionary it's from; tapped, the
    /// entry opens in the reader.
    private func defineCard(_ entry: Passage, _ model: SearchViewModel) -> some View {
        Button {
            remember(model)
            openRoute(.read(packId: entry.sourceId, path: entry.location))
        } label: {
            VStack(alignment: .leading, spacing: 4) {
                Text(SearchDefine.shared.label.words).font(.caption.weight(.medium)).foregroundStyle(palette.primary)
                Text(entry.sourceTitle).font(Spectral.medium(20)).foregroundStyle(palette.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Text(SearchRows.defineLine(entry)).font(.subheadline).foregroundStyle(palette.onSurface).lineLimit(4)
                Text(SearchDefine.shared.source(packTitle: entry.packTitle).words)
                    .font(.caption).foregroundStyle(palette.onSurfaceVariant).padding(.top, 4)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(12)
            .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .listRowSeparator(.hidden)
        .listRowBackground(palette.background)
        .accessibilityIdentifier("search-define")
    }

    // MARK: - The groups

    /// Best matches lead (Plan SQ), each its article, chapter or media card with the sentence that matched.
    @ViewBuilder private func best(_ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        let all = ui.bestFiltered
        if ui.filter != .media && (!all.isEmpty || ui.bestLoading) {
            Section {
                if ui.bestLoading && all.isEmpty {
                    Text("Looking through your library…").font(.subheadline).foregroundStyle(palette.onSurfaceVariant)
                        .accessibilityIdentifier("search-best-looking")
                }
                ForEach(shown(.best, all, ui), id: \.self) { match in
                    bestRow(match, words: ui.bestWords, model)
                }
                if all.count > Int(SearchGroup.best.preview) {
                    moreRow(SearchLines.shared.bestMore(open: ui.expanded.contains(.best)).words, group: .best, first: all.first, ui, model)
                }
            } header: {
                header(L("Best matches"))
            }
        }
    }

    private func bestRow(_ match: BestMatches.Match, words: [String], _ model: SearchViewModel) -> some View {
        let p = match.passage
        let kind = SearchLines.shared.matchKind(p: p)
        let id = switch kind {
        case .book: "search-book-hit"
        case .music, .audiobook: "search-media-hit"
        default: "search-hit"
        }
        let line = SearchLines.shared.matchLine(match: match)
        return Button {
            remember(model)
            openRoute(SearchRows.bestRoute(match))
        } label: {
            HStack(alignment: .firstTextBaseline, spacing: 12) {
                Image(systemName: SearchRows.icon(kind)).foregroundStyle(palette.onSurfaceVariant)
                    .accessibilityLabel(SearchLines.shared.kindLabel(kind: kind).words)
                    .accessibilityIdentifier("search-kind-\(SearchRows.kindId(kind))")
                VStack(alignment: .leading, spacing: 4) {
                    Text(SearchRows.title(p)).font(Spectral.regular(19)).foregroundStyle(palette.onBackground).lineLimit(2)
                    Text(SearchRows.bold(line, SearchLines.shared.highlights(sentence: line, keywords: words)))
                        .font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
                    // A media card's "Your music" / "Your audiobooks" in the phone's words (Plan I7 Task 7c).
                    Text(SearchLines.shared.matchSource(p: p).words).font(.caption).foregroundStyle(palette.outline)
                }
            }
            .padding(.vertical, 2)
        }
        .accessibilityIdentifier(id)
    }

    /// Titles under the filter: the packs' and the shelf's, then Wikipedia online's not already listed; each with its
    /// other copies ("Also in Wikivoyage, Granger Classics").
    @ViewBuilder private func titles(_ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        let all = ui.titlesFiltered
        if !all.isEmpty {
            Section {
                ForEach(shown(.titles, all, ui), id: \.self) { hit in
                    VStack(alignment: .leading, spacing: 4) {
                        Button { open(hit, model) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                hitTitle(hit)
                                let line = [hit.author, hit.packTitle].filter { !$0.isEmpty }.joined(separator: " · ")
                                if !line.isEmpty { Text(line).font(.caption).foregroundStyle(palette.outline) }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.borderless)
                        .accessibilityIdentifier("search-hit")
                        // The other copies for VoiceOver, as actions on the row.
                        .accessibilityActions {
                            ForEach(hit.also, id: \.self) { other in
                                Button(L("Open in \(SearchRows.copyName(other))")) { open(other, model) }
                            }
                        }
                        if !hit.also.isEmpty { alsoIn(hit.also, model) }
                    }
                    .padding(.vertical, 2)
                }
                moreFoot(.titles, all, ui, model)
            } header: {
                header(L("Titles"))
            }
        }
    }

    /// "Also in Wikivoyage, Granger Classics": each name a link to its copy.
    private func alsoIn(_ also: [SearchHit], _ model: SearchViewModel) -> some View {
        let mark = "\u{1}"
        let template = L("Also in \(mark)")
        let parts = template.components(separatedBy: mark)
        var text = AttributedString(parts.first ?? "")
        for (i, other) in also.enumerated() {
            if i > 0 { text += AttributedString(", ") }
            var name = AttributedString(SearchRows.copyName(other))
            name.link = URL(string: "granger-also:\(i)")
            name.foregroundColor = palette.primary
            text += name
        }
        text += AttributedString(parts.count > 1 ? parts[1] : "")
        return Text(text).font(.caption).foregroundStyle(palette.onSurfaceVariant)
            .environment(\.openURL, OpenURLAction { url in
                if url.scheme == "granger-also", let i = Int(url.absoluteString.dropFirst("granger-also:".count)), i < also.count {
                    open(also[i], model)
                }
                return .handled
            })
            .accessibilityIdentifier("search-also-in")
    }

    /// Articles: the phone's full-text hits, then Wikipedia online's not already listed, each with its snippet.
    @ViewBuilder private func articles(_ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        let all = ui.showing(kind: .articles) ? ui.articles : []
        if !all.isEmpty {
            Section {
                ForEach(shown(.fullText, all, ui), id: \.self) { hit in
                    Button { open(hit, model) } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            hitTitle(hit)
                            if let snippet = hit.snippet, !snippet.isEmpty {
                                Text(SearchRows.snippet(snippet)).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
                            }
                            Text(hit.packTitle).font(.caption).foregroundStyle(palette.outline)
                        }
                        .padding(.vertical, 2)
                    }
                    .accessibilityIdentifier("search-hit")
                }
                moreFoot(.fullText, all, ui, model)
            } header: {
                header(L("Articles"))
            }
        }
    }

    /// Books: hits inside books, one row per book, with how many places matched, a PDF's page and the snippet.
    @ViewBuilder private func books(_ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        let all = ui.showing(kind: .books) ? ui.books : []
        if !all.isEmpty {
            Section {
                ForEach(shown(.books, all, ui), id: \.self) { hit in
                    Button {
                        remember(model)
                        openRoute(SearchRows.bookRoute(hit))
                    } label: {
                        VStack(alignment: .leading, spacing: 4) {
                            Text(hit.bookTitle).font(Spectral.regular(19)).foregroundStyle(palette.onBackground)
                            if let line = SearchLines.shared.bookLine(hit: hit) {
                                Text(line.words).font(.caption).foregroundStyle(palette.outline)
                            }
                            if let page = SearchLines.shared.bookPage(hit: hit) {
                                Text(page.words).font(.caption).foregroundStyle(palette.outline)
                            }
                            if !hit.snippet.isEmpty {
                                Text(SearchRows.snippet(hit.snippet)).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
                            }
                        }
                        .padding(.vertical, 2)
                    }
                    .accessibilityIdentifier("search-books-hit")
                }
                moreFoot(.books, all, ui, model)
            } header: {
                header(L("Books"))
            }
        }
    }

    /// Music (artists, albums, songs) and Audiobooks, with their covers; a song plays within its album.
    @ViewBuilder private func media(_ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        if ui.showing(kind: .media) {
            let m = ui.media
            if !m.artists.isEmpty || !m.albums.isEmpty || !m.songs.isEmpty {
                Section {
                    ForEach(shown(.artists, m.artists, ui), id: \.self) { a in
                        mediaRow(a.name, SearchLines.shared.artist(a: a).words, cover: a.coverPath, glyph: "person", id: "search-artist", model) {
                            openRoute(.artist(name: a.name))
                        }
                    }
                    moreFoot(.artists, m.artists, ui, model)
                    ForEach(shown(.albums, m.albums, ui), id: \.self) { a in
                        mediaRow(a.title, SearchLines.shared.album(a: a).words, cover: a.coverPath, glyph: "music.note", id: "search-album", model) {
                            openRoute(.album(key: a.key))
                        }
                    }
                    moreFoot(.albums, m.albums, ui, model)
                    ForEach(shown(.songs, m.songs, ui), id: \.self) { t in
                        mediaRow(t.title, SearchLines.shared.song(t: t).words, cover: t.coverPath, glyph: "music.note", id: "search-song", model) {
                            guard let system = voice?.system else { return }
                            Task { try? await system.playSong(track: t) }
                        }
                    }
                    moreFoot(.songs, m.songs, ui, model)
                } header: {
                    header(L("Music"))
                }
            }
            if !m.audiobooks.isEmpty {
                Section {
                    ForEach(shown(.audiobooks, m.audiobooks, ui), id: \.self) { b in
                        mediaRow(b.title, SearchLines.shared.audiobook(b: b).words, cover: b.coverPath, glyph: "headphones", id: "search-audiobook", model) {
                            openRoute(.audiobook(id: b.id, chapter: nil))
                        }
                    }
                    moreFoot(.audiobooks, m.audiobooks, ui, model)
                } header: {
                    header(L("Audiobooks"))
                }
            }
        }
    }

    private func mediaRow(_ title: String, _ line: String, cover: String?, glyph: String, id: String, _ model: SearchViewModel,
                          go: @escaping () -> Void) -> some View {
        Button {
            remember(model)
            go()
        } label: {
            HStack(spacing: 12) {
                AlbumCover(path: cover, title: title, glyph: glyph).frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(MusicWords.shown(title)).font(Spectral.regular(18)).foregroundStyle(palette.onBackground).lineLimit(1)
                    Text(line).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                }
            }
        }
        .accessibilityIdentifier(id)
    }

    // MARK: - Parts

    private func header(_ text: String) -> some View {
        Text(text).font(Spectral.medium(16)).foregroundStyle(palette.tertiary).accessibilityAddTraits(.isHeader)
    }

    /// A result's title with its mark: on this phone, or saved for now (Plan O2 Task 4); a book's title alone.
    private func hitTitle(_ hit: SearchHit) -> some View {
        HStack(spacing: 8) {
            Text(hit.title).font(Spectral.regular(19)).foregroundStyle(palette.onBackground)
            if !BookIds.shared.isBook(id: hit.packId) { HitMark(packId: hit.packId, path: hit.path) }
        }
    }

    /// [group]'s rows on screen: its preview, or all once opened with Show all (Android's `State.shown`).
    private func shown<T>(_ group: SearchGroup, _ all: [T], _ ui: SearchViewModel.State) -> [T] {
        ui.expanded.contains(group) ? all : Array(all.prefix(Int(group.preview)))
    }

    /// The foot of a group longer than its preview: Show all (or Show the first, when capped), or Show fewer.
    @ViewBuilder private func moreFoot<T: Hashable>(_ group: SearchGroup, _ all: [T], _ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        if all.count > Int(group.preview) {
            let text = ui.expanded.contains(group)
                ? SearchLines.shared.showFewer(group: group)
                : SearchLines.shared.showAll(group: group, count: Int32(all.count), capped: ui.capped.contains(group))
            moreRow(text.words, group: group, first: all.first, ui, model)
        }
    }

    /// Show all / fewer; folding a group the list has scrolled through brings its first row back (Android's toggle).
    private func moreRow<T: Hashable>(_ text: String, group: SearchGroup, first: T?, _ ui: SearchViewModel.State, _ model: SearchViewModel) -> some View {
        Button(text) {
            let folding = ui.expanded.contains(group)
            model.toggle(group: group)
            if folding, let first { folded = AnyHashable(first) }
        }
        .font(Spectral.medium(17))
        .foregroundStyle(palette.primary)
        .accessibilityIdentifier("search-more")
    }

    private func open(_ hit: SearchHit, _ model: SearchViewModel) {
        remember(model)
        openRoute(.article(packId: hit.packId, path: hit.path))
    }

    /// Opening a result remembers the query as a recent search (Android's open and recordSearch).
    private func remember(_ model: SearchViewModel) {
        Task { try? await model.recordSearch() }
    }
}

/// The shared Search model for the screen's life, and its state as it changes.
@Observable
final class SearchHolder {
    private var owned: OwnedViewModel<SearchViewModel>?
    private var watching: Task<Void, Never>?
    var model: SearchViewModel? { owned?.model }
    var ui: SearchViewModel.State?

    @MainActor
    func start(_ core: Core, initial: String) {
        guard owned == nil else { return }
        let o = core.library.searchModel(initial: initial)
        owned = o
        watching = Task { @MainActor [weak self] in for await s in o.model.state { self?.ui = s } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// A search hit's mark (Android's `AvailabilityMark`): a dot for on this phone, a ring for saved for now.
struct HitMark: View {
    let packId: String
    let path: String
    @Environment(\.core) private var core
    @Environment(LibraryModel.self) private var library
    @State private var mark: Mark = .none

    var body: some View {
        MarkDot(mark: mark)
            .task(id: "\(packId):\(path):\(library.version)") {
                guard let core else { return }
                mark = (try? await core.library.hitMark(packId: packId, path: path)) ?? .none
            }
    }
}

/// How Search's rows open and read on the iPhone, beside the shared `SearchLines`.
enum SearchRows {
    /// Where a Best match opens: a book at its passage, a media card its album or audiobook at the song or chapter
    /// (Android's CitationOpener), an article its topic page.
    static func bestRoute(_ m: BestMatches.Match) -> Route {
        let p = m.passage
        switch p.kind {
        case .book: return .bookAt(id: p.sourceId, passage: BookPassage(href: p.location, quote: m.quote() ?? "", before: "", progression: p.progression))
        case .media: return mediaRoute(p)
        default: return .article(packId: p.sourceId, path: p.location)
        }
    }

    /// A Best match's title: a book's or media card's as it is, an article's without its pack's suffix.
    static func title(_ p: Passage) -> String {
        p.kind == .article ? PageTitles.shared.clean(title: p.sourceTitle, packTitle: p.packTitle, mainPage: false) : p.sourceTitle
    }

    /// The kind's icon: a page, a book, or Listen's headphones for an album's or audiobook's card (Plan I7 Task 7c).
    static func icon(_ kind: SearchLines.MatchKind) -> String {
        switch kind {
        case .book: "book.closed"
        case .music, .audiobook: "headphones"
        default: "doc.text"
        }
    }

    static func kindId(_ kind: SearchLines.MatchKind) -> String {
        switch kind {
        case .book: "book"
        case .music: "music"
        case .audiobook: "audiobook"
        default: "article"
        }
    }

    /// Where a media card opens (Android's `PassageAddress.media` and `CitationOpener`): a song's card its album at the
    /// song, a chapter's its audiobook at the chapter, a whole work's its album or audiobook page.
    static func mediaRoute(_ p: Passage) -> Route {
        let location = p.location
        if location.hasPrefix("chapter:") {
            return .audiobook(id: p.sourceId, chapter: max(0, Int(location.dropFirst("chapter:".count)) ?? 0))
        }
        if p.sourceId.hasPrefix("album:") {
            return .album(key: String(p.sourceId.dropFirst("album:".count)), track: location.hasPrefix("track:") ? location : nil)
        }
        return .audiobook(id: p.sourceId, chapter: nil)
    }

    /// Where a hit inside a book opens: at the hit, the words marked; a book pack's hit (no place) where the reader left it.
    static func bookRoute(_ hit: BookHit) -> Route {
        if hit.href.isEmpty { return .book(id: hit.bookId) }
        return .bookAt(id: hit.bookId, passage: BookPassage(href: hit.href, quote: hit.quote?.highlight ?? "",
                                                             before: hit.quote?.before ?? "", progression: hit.progression))
    }

    /// The name of a title's other copy: its pack, else its author (a shelf book), else its title.
    static func copyName(_ hit: SearchHit) -> String {
        [hit.packTitle, hit.author, hit.title].first { !$0.isEmpty } ?? hit.title
    }

    /// The Define card's line: the senses, without the etymology when there is more (Android's `SearchRows.defineText`).
    static func defineLine(_ entry: Passage) -> String { SearchDefine.shared.text(entry: entry.text) }

    /// [text] with the UTF-16 ranges [bold] (Kotlin's offsets) in semibold.
    static func bold(_ text: String, _ bold: [KotlinIntRange]) -> AttributedString {
        var out = AttributedString(text)
        for r in bold {
            let ns = NSRange(location: Int(r.first), length: Int(r.last) - Int(r.first) + 1)
            guard let range = Range(ns, in: text), let lower = AttributedString.Index(range.lowerBound, within: out),
                  let upper = AttributedString.Index(range.upperBound, within: out) else { continue }
            out[lower..<upper].inlinePresentationIntent = .stronglyEmphasized
        }
        return out
    }

    /// A snippet as Android's `fromHtml` draws it: its words, the `<b>` marks bold.
    static func snippet(_ html: String) -> AttributedString {
        let pair = SearchLines.shared.snippetBold(html: html)
        return bold(pair.first as String? ?? "", pair.second as? [KotlinIntRange] ?? [])
    }
}
