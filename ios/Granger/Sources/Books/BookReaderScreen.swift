import GrangerCoreKit
import ReadiumNavigator
import ReadiumShared
import SwiftUI

/// The e-book and PDF reader (Android's EbookReaderScreen, Plan I5 Task 5): the page, paginated, under bars that a page
/// turn slides away and a tap brings back. Top: the title. Bottom: where you are ("Chapter · p. N of M") over a slider
/// through the book, then Contents, Search in book (an EPUB with words), Aa (not for a picture book) and the star.
struct BookReaderScreen: View {
    let bookId: String
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.colorScheme) private var scheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.openRoute) private var openRoute
    @Environment(RecentStore.self) private var recent
    @State private var model: BookReaderModel
    @State private var dragging: Double?

    init(bookId: String) {
        self.bookId = bookId
        _model = State(initialValue: BookReaderModel(bookId: bookId))
    }

    var body: some View {
        ZStack {
            Color(BookHostController.pageColor(model.prefs ?? Self.defaultPrefs)).ignoresSafeArea()
            if let publication = model.publication, let prefs = model.prefs {
                BookNavigatorView(
                    model: model, publication: publication, prefs: prefs, decorations: model.decorations, searchHit: model.searchHit,
                    onSelection: { action, selection in select(action, selection) }
                )
                .ignoresSafeArea()
            } else if let error = model.error {
                Text(error).foregroundStyle(palette.onSurfaceVariant).multilineTextAlignment(.center).padding(32)
            } else {
                ProgressView()
            }
        }
        .overlay(alignment: .bottom) { if model.chrome && model.error == nil && model.publication != nil { bottomBar } }
        .overlay(alignment: .top) {
            if let notice = model.notice {
                Text(notice).font(.callout).padding(12).background(palette.inverseSurface, in: RoundedRectangle(cornerRadius: 8))
                    .foregroundStyle(palette.inverseOnSurface).padding(.top, 60)
                    .task { try? await Task.sleep(for: .seconds(3)); model.notice = nil }
            }
        }
        .navigationTitle(model.title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(model.chrome ? .visible : .hidden, for: .navigationBar, .tabBar)
        .statusBarHidden(!model.chrome)
        .animation(.easeInOut(duration: 0.2), value: model.chrome)
        .task {
            guard let core else { return }
            core.library.readingPrefs.setAppNight(night: scheme == .dark)
            let id = bookId
            model.onPlace = { [recent] place in recent.setPlace(bookId: id, place: place) }
            await model.open(core: core)
            // The rail's open books on the iPhone: Recently open, at this place (Plan I5 Task 6).
            if model.error == nil, !model.title.isEmpty { recent.addBook(id: bookId, title: model.title) }
        }
        .onChange(of: scheme) { _, s in core?.library.readingPrefs.setAppNight(night: s == .dark) }
        .onChange(of: scenePhase) { _, phase in if phase != .active { model.flush() } }
        .onDisappear { model.flush() }
        .sheet(isPresented: panelBinding(.contents)) { ContentsPanel(model: model).presentationDetents([.large]) }
        .sheet(isPresented: panelBinding(.search)) { BookSearchPanel(model: model).presentationDetents([.large]) }
        .sheet(isPresented: panelBinding(.settings)) {
            if let prefs = model.prefs {
                TextSettingsSheet(prefs: prefs, isPdf: model.isPdf) { model.model?.updatePrefs(prefs: $0) }
                    .presentationDetents([.medium, .large])
            }
        }
        .sheet(item: editingBinding) { mark in
            HighlightSheet(mark: mark.mark, focusNote: model.shared?.focusNote ?? false, model: model)
                .presentationDetents([.medium])
        }
    }

    static let defaultPrefs = ReadingPrefs(typeface: .serif, fontScale: 1, lineHeight: 1.5, margins: 1, align: .start, theme: .app, appNight: false,
                                           pdfScroll: false, pdfFitWidth: false, wikiTextZoom: 100, referenceLinks: .light, topicFirst: true)

    private func panelBinding(_ p: Panel) -> Binding<Bool> {
        Binding(get: { model.panel == p }, set: { if !$0, model.panel == p { model.model?.open(panel: nil) } })
    }

    private var editingBinding: Binding<EditedMark?> {
        Binding(get: { model.editing.map(EditedMark.init) }, set: { if $0 == nil { model.model?.editHighlight(id: nil) } })
    }

    private var bottomBar: some View {
        VStack(alignment: .leading, spacing: 4) {
            if let label = model.progressLabel {
                Text(label).font(.footnote).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                    .accessibilityIdentifier("book-progress")
            }
            Slider(value: Binding(get: { dragging ?? model.locator?.locations.totalProgression ?? 0 }, set: { dragging = $0 }), in: 0...1) { editing in
                if !editing, let d = dragging { model.goTo(progression: d); dragging = nil }
            }
            .accessibilityLabel("Place in book")
            HStack {
                Button { model.model?.open(panel: .contents) } label: { Image(systemName: "list.bullet") }
                    .accessibilityLabel("Contents")
                Spacer()
                if !model.isPdf {
                    Button { model.model?.open(panel: .search) } label: { Image(systemName: "magnifyingglass") }
                        .accessibilityLabel(BookWords.searchInBook)
                    Spacer()
                }
                if model.isPdf || !model.fixedLayout {
                    Button { model.model?.open(panel: .settings) } label: { Image(systemName: "textformat.size") }
                        .accessibilityLabel("Text settings")
                    Spacer()
                }
                Button { model.toggleBookmark() } label: { Image(systemName: model.isBookmarkedHere ? "star.fill" : "star") }
                    .accessibilityLabel(BookWords.bookmark)
                    .accessibilityValue(model.isBookmarkedHere ? "On" : "Off")
            }
            .font(.title3)
            .padding(.horizontal, 8)
        }
        .padding(.horizontal, 16).padding(.top, 10).padding(.bottom, 6)
        .background(palette.surfaceContainer.opacity(0.97))
        .transition(.move(edge: .bottom).combined(with: .opacity))
    }

    /// The selection menu's tap, with the selection read: act, then say what went wrong (Define).
    private func select(_ action: SelectionAction, _ selection: Selection) {
        let text = (selection.locator.text.highlight ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        guard !text.isEmpty else { return }
        switch action {
        case .copy: UIPasteboard.general.string = text
        case .highlight: Task { await model.highlight(selection) }
        case .note: Task { await model.note(selection) }
        case .search: openRoute(.search(query: String(text.prefix(200))))
        case .define:
            guard let core else { return }
            Task {
                let message = await model.define(text, core: core) { entry in
                    openRoute(.packReader(pack: entry.packId, path: entry.path, fragment: entry.anchor))
                }
                if let message { model.notice = message }
            }
        }
    }
}

/// A highlight as a sheet's item.
struct EditedMark: Identifiable {
    let mark: BookAnnotation
    var id: Int64 { mark.id }
}

/// Contents, Bookmarks and Highlights (Android's ContentsPanel). The row the reader is in is marked. A PDF can't be
/// highlighted, so it has no Highlights tab.
struct ContentsPanel: View {
    let model: BookReaderModel
    @Environment(\.palette) private var palette
    @State private var tab = 0

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                Picker("", selection: $tab) {
                    Text("Contents").tag(0)
                    Text("Bookmarks").tag(1)
                    if !model.isPdf { Text("Highlights").tag(2) }
                }
                .pickerStyle(.segmented)
                .padding(16)
                switch tab {
                case 0: contents
                case 1: bookmarks
                default: highlights
                }
            }
            .navigationTitle(model.title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { model.model?.open(panel: nil) } } }
        }
    }

    @ViewBuilder private var contents: some View {
        if model.toc.isEmpty {
            empty(model.isPdf ? BookWords.noPdfContents : BookWords.noContents)
        } else {
            let current = model.currentTocIndex
            ScrollViewReader { proxy in
                List(Array(model.toc.enumerated()), id: \.offset) { i, row in
                    Button { model.goTo(href: row.href) } label: {
                        Text(row.title.isEmpty ? BookWords.untitled : row.title)
                            .font(i == current ? Spectral.semibold(17) : Spectral.regular(17))
                            .foregroundStyle(i == current ? palette.primary : palette.onSurface)
                            .padding(.leading, CGFloat(row.depth) * 16)
                    }
                    .id(i)
                    .accessibilityAddTraits(i == current ? .isSelected : [])
                }
                .listStyle(.plain)
                .onAppear { if let current { proxy.scrollTo(current, anchor: .center) } }
            }
        }
    }

    @ViewBuilder private var bookmarks: some View {
        if model.bookmarks.isEmpty {
            empty(BookWords.noBookmarks)
        } else {
            List(model.bookmarks, id: \.id) { m in
                Button { model.goTo(mark: m) } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(m.text.isEmpty ? BookWords.bookmark : m.text).lineLimit(1).foregroundStyle(palette.onSurface)
                        if let t = (try? Locator(jsonString: m.locatorJson))?.locations.totalProgression {
                            Text(BookWords.percent(Int(t * 100))).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                }
            }
            .listStyle(.plain)
        }
    }

    @ViewBuilder private var highlights: some View {
        if model.highlights.isEmpty {
            empty(BookWords.noHighlights)
        } else {
            let lost = model.unplaceable
            List(model.highlights, id: \.id) { m in
                Button { model.goTo(mark: m) } label: {
                    HStack(alignment: .top, spacing: 12) {
                        Circle().fill(Color(UIColor(argb: HighlightColors.shared.argb(name: m.color)))).frame(width: 12, height: 12).padding(.top, 4)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(m.text).lineLimit(3).foregroundStyle(palette.onSurface)
                            if lost.contains(m.id) { Text(BookWords.cantFind).font(.caption).foregroundStyle(palette.error) }
                            if !m.note.isEmpty { Text(m.note).font(.caption).foregroundStyle(palette.onSurfaceVariant) }
                        }
                    }
                }
                .accessibilityIdentifier("highlight-row")
            }
            .listStyle(.plain)
        }
    }

    private func empty(_ text: String) -> some View {
        VStack { Text(text).foregroundStyle(palette.onSurfaceVariant).multilineTextAlignment(.center).padding(32); Spacer() }
    }
}

/// Search in the book (Android's BookSearchPanel): your own highlights and notes holding the words first, then the
/// text, each in context; a row opens its place, the match marked.
struct BookSearchPanel: View {
    let model: BookReaderModel
    @Environment(\.palette) private var palette
    @State private var query = ""
    @FocusState private var focused: Bool

    var body: some View {
        NavigationStack {
            List {
                if let s = model.search {
                    if s.running { HStack { Spacer(); ProgressView(); Spacer() } }
                    if !s.running && !s.query.trimmingCharacters(in: .whitespaces).isEmpty && s.results.isEmpty {
                        Text(BookWords.nothingFound(s.query.trimmingCharacters(in: .whitespaces))).foregroundStyle(palette.onSurfaceVariant)
                    }
                    ForEach(Array(s.results.enumerated()), id: \.offset) { _, row in
                        Button { model.goTo(row: row) } label: {
                            VStack(alignment: .leading, spacing: 4) {
                                let chapter = row.chapter.words
                                if !chapter.isEmpty { Text(chapter).font(.caption).foregroundStyle(palette.tertiary) }
                                (Text(row.before) + Text(row.match).bold().foregroundColor(palette.primary) + Text(row.after))
                                    .lineLimit(4).foregroundStyle(palette.onSurface)
                            }
                        }
                        .accessibilityIdentifier(row.markId != nil ? "search-own-mark" : "search-hit")
                    }
                }
            }
            .listStyle(.plain)
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: BookWords.searchInThisBook)
            .searchFocused($focused)
            .onSubmit(of: .search) { model.model?.search(query: query) }
            .navigationTitle(BookWords.searchInBook)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("Done") { model.model?.open(panel: nil) } } }
            .onAppear {
                query = model.search?.query ?? ""
                focused = query.isEmpty
            }
        }
    }
}

/// Typeface, size, spacing, margins, alignment and theme (Android's TextSettingsSheet); a PDF's pages, fit and theme
/// (PdfSettingsSheet). Every change shows live.
struct TextSettingsSheet: View {
    let prefs: ReadingPrefs
    let isPdf: Bool
    let onChange: (ReadingPrefs) -> Void
    @State private var lineHeight: Double?
    @State private var margins: Double?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if isPdf {
                    setting("Pages") {
                        chips([(false, "Page by page"), (true, "Scroll")], prefs.pdfScroll) { v in onChange(change(prefs) { $0.pdfScroll = v }) }
                    }
                    setting("Fit") {
                        chips([(false, "Whole page"), (true, "Width")], prefs.pdfFitWidth) { v in onChange(change(prefs) { $0.pdfFitWidth = v }) }
                    }
                    setting("Theme") {
                        chips([(ReadingTheme.app, "Match app"), (.day, "Day"), (.night, "Night")], prefs.theme == .sepia ? .day : prefs.theme) { v in onChange(change(prefs) { $0.theme = v }) }
                    }
                } else {
                    setting("Typeface") {
                        chips([(Typeface.serif, "Serif"), (.sans, "Sans")], prefs.typeface) { v in onChange(change(prefs) { $0.typeface = v }) }
                    }
                    setting("Size") {
                        HStack(spacing: 12) {
                            Button("−") { onChange(change(prefs) { $0.fontScale -= ReadingPrefs.companion.FONT_SCALE_STEP }) }
                                .buttonStyle(.bordered).disabled(prefs.fontScale <= ReadingPrefs.companion.MIN_FONT_SCALE)
                                .accessibilityLabel("Smaller text")
                            Text(BookWords.percent(Int((prefs.fontScale * 100).rounded()))).font(.headline).frame(width: 64)
                                .accessibilityIdentifier("text-size")
                            Button("+") { onChange(change(prefs) { $0.fontScale += ReadingPrefs.companion.FONT_SCALE_STEP }) }
                                .buttonStyle(.bordered).disabled(prefs.fontScale >= ReadingPrefs.companion.MAX_FONT_SCALE)
                                .accessibilityLabel("Larger text")
                        }
                    }
                    setting("Line spacing") {
                        Slider(value: Binding(get: { lineHeight ?? prefs.lineHeight }, set: { lineHeight = $0 }),
                               in: ReadingPrefs.companion.MIN_LINE_HEIGHT...ReadingPrefs.companion.MAX_LINE_HEIGHT) { editing in
                            if !editing, let v = lineHeight { onChange(change(prefs) { $0.lineHeight = v }); lineHeight = nil }
                        }
                    }
                    setting("Margins") {
                        Slider(value: Binding(get: { margins ?? prefs.margins }, set: { margins = $0 }),
                               in: ReadingPrefs.companion.MIN_MARGINS...ReadingPrefs.companion.MAX_MARGINS) { editing in
                            if !editing, let v = margins { onChange(change(prefs) { $0.margins = v }); margins = nil }
                        }
                    }
                    setting("Alignment") {
                        chips([(Align.start, "Left"), (.justify, "Justified")], prefs.align) { v in onChange(change(prefs) { $0.align = v }) }
                    }
                    setting("Theme") {
                        chips([(ReadingTheme.app, "Match app"), (.day, "Day"), (.sepia, "Sepia"), (.night, "Night")], prefs.theme) { v in onChange(change(prefs) { $0.theme = v }) }
                    }
                }
            }
            .padding(24)
        }
    }

    private func setting<C: View>(_ title: String, @ViewBuilder _ content: () -> C) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(Spectral.medium(16))
            content()
        }
    }

    private func chips<T: Equatable>(_ options: [(T, String)], _ selected: T, _ pick: @escaping (T) -> Void) -> some View {
        HStack(spacing: 8) {
            ForEach(Array(options.enumerated()), id: \.offset) { _, o in
                Button(o.1) { pick(o.0) }
                    .buttonStyle(.bordered)
                    .tint(o.0 == selected ? .accentColor : .secondary)
                    .accessibilityAddTraits(o.0 == selected ? .isSelected : [])
            }
        }
    }
}

/// The shared prefs as a value Swift can change, then made into prefs again (Kotlin's `copy` needs every field).
struct PrefsDraft {
    var typeface: Typeface
    var fontScale: Double
    var lineHeight: Double
    var margins: Double
    var align: Align
    var theme: ReadingTheme
    var pdfScroll: Bool
    var pdfFitWidth: Bool
}

func change(_ p: ReadingPrefs, _ edit: (inout PrefsDraft) -> Void) -> ReadingPrefs {
    var d = PrefsDraft(typeface: p.typeface, fontScale: p.fontScale, lineHeight: p.lineHeight, margins: p.margins, align: p.align, theme: p.theme,
                       pdfScroll: p.pdfScroll, pdfFitWidth: p.pdfFitWidth)
    edit(&d)
    return ReadingPrefs(typeface: d.typeface, fontScale: d.fontScale, lineHeight: d.lineHeight, margins: d.margins, align: d.align, theme: d.theme,
                        appNight: p.appNight, pdfScroll: d.pdfScroll, pdfFitWidth: d.pdfFitWidth, wikiTextZoom: p.wikiTextZoom,
                        referenceLinks: p.referenceLinks, topicFirst: p.topicFirst)
}

/// One highlight's colour, note and Delete (Android's HighlightSheet): a colour tap saves at once; the note when the
/// sheet closes. A new note opens with the keyboard up.
struct HighlightSheet: View {
    let mark: BookAnnotation
    let focusNote: Bool
    let model: BookReaderModel
    @Environment(\.palette) private var palette
    @State private var note = ""
    @State private var color = ""
    @State private var deleted = false
    @FocusState private var noteFocused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(mark.text).lineLimit(3).font(.callout)
            HStack(spacing: 16) {
                ForEach(HighlightColors.shared.names, id: \.self) { name in
                    Circle().fill(Color(UIColor(argb: HighlightColors.shared.argb(name: name))))
                        .frame(width: 36, height: 36)
                        .overlay(Circle().stroke(palette.primary, lineWidth: name == color ? 2 : 0).padding(-3))
                        .onTapGesture {
                            color = name
                            model.model?.updateHighlight(mark: edited())
                        }
                        .accessibilityLabel("\(name) highlight")
                        .accessibilityAddTraits(.isButton)
                }
            }
            TextField("Note", text: $note, axis: .vertical)
                .lineLimit(2...5)
                .textFieldStyle(.roundedBorder)
                .focused($noteFocused)
                .accessibilityIdentifier("highlight-note")
            HStack {
                Button("Delete", role: .destructive) {
                    deleted = true
                    model.model?.deleteHighlight(id: mark.id)
                }
                .foregroundStyle(palette.error)
                Spacer()
                Button("Done") { model.model?.editHighlight(id: nil) }.foregroundStyle(palette.primary)
            }
        }
        .padding(20)
        .onAppear {
            note = mark.note
            color = mark.color
            if focusNote { noteFocused = true }
        }
        .onDisappear { if !deleted && (note != mark.note || color != mark.color) { model.model?.updateHighlight(mark: edited()) } }
    }

    private func edited() -> BookAnnotation {
        BookAnnotation(id: mark.id, bookId: mark.bookId, kind: mark.kind, locatorJson: mark.locatorJson, text: mark.text, color: color, note: note,
                       createdAt: mark.createdAt, updatedAt: mark.updatedAt)
    }
}
