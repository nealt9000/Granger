import GrangerCoreKit
import SwiftUI

/// The shared CollectionModel as SwiftUI sees it.
@Observable
final class CollectionHolder {
    private(set) var row: CollectionRow?
    private(set) var books: CollectionBooks?
    private(set) var sort: BookSort = .title
    private(set) var view: ShelfView = .list
    private(set) var positions: [String: BookPosition] = [:]
    private(set) var header: GutenbergHeader?
    private(set) var removed: [Book] = []
    private(set) var targets: [Shelf] = []
    private(set) var superseded: Set<String> = []
    /// The collection has been seen: when it goes (emptied, deleted) the screen leaves.
    private(set) var seen = false
    private(set) var gone = false
    private var owned: OwnedViewModel<CollectionModel>?
    private var watching: [Task<Void, Never>] = []
    var model: CollectionModel? { owned?.model }

    @MainActor func start(_ core: Core, id: String) {
        guard owned == nil else { return }
        let o = core.library.collection(collectionId: id)
        owned = o
        let m = o.model
        watching = [
            Task { @MainActor [weak self] in
                for await r in m.ui {
                    guard let self else { return }
                    self.row = r
                    if r != nil { self.seen = true } else if self.seen { self.gone = true }
                }
            },
            Task { @MainActor [weak self] in for await b in m.books { self?.books = b } },
            Task { @MainActor [weak self] in for await s in m.sort { self?.sort = s } },
            Task { @MainActor [weak self] in for await v in m.view { self?.view = v } },
            Task { @MainActor [weak self] in for await p in m.positions { self?.positions = p } },
            Task { @MainActor [weak self] in for await h in m.gutenbergHeader { self?.header = h } },
            Task { @MainActor [weak self] in for await r in m.removed { self?.removed = r } },
            Task { @MainActor [weak self] in for await t in m.targets { self?.targets = t } },
            Task { @MainActor [weak self] in for await s in m.superseded { self?.superseded = s } },
        ]
    }

    deinit { watching.forEach { $0.cancel() }; owned?.close() }
}

/// One collection (Android's CollectionScreen over the shared CollectionModel, Plan I5 Task 6): its packs, then its
/// books, which can be found by title or author, sorted (Title, Author, Recently read), shown as a list or a cover grid,
/// and in Granger Classics narrowed by kind. A long list is filed under letters with the letter strip beside it. A book
/// pack's collection (Gutenberg, nautilus, a Kiwix manifest) says where its books come from, and a manifest's keeps its
/// own sections and works in parts. A touch and hold on a book moves or removes it; More moves, renames or deletes the
/// collection. A folder (Kiwix) lists its collections instead.
struct CollectionScreen: View {
    let collectionId: String
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @Environment(\.dismiss) private var dismiss
    @State private var holder = CollectionHolder()
    @State private var find = ""
    @State private var dialog: CollectionDialog?
    @State private var moving: MoveTarget?
    @State private var newName = ""
    @State private var naming: Naming?
    @State private var cost: SearchableCost?

    var body: some View {
        Group {
            if let row = holder.row {
                if row.isFolder { folder(row) } else { shelf(row) }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(palette.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { if let row = holder.row, !row.isFolder { ToolbarItem(placement: .topBarTrailing) { menu(row) } } }
        .task {
            guard let core else { return }
            holder.start(core, id: collectionId)
            find = holder.model?.findText ?? ""
        }
        .onChange(of: holder.gone) { _, gone in if gone { dismiss() } }
        .onChange(of: find) { _, q in holder.model?.setQuery(text: q) }
        .sheet(item: $moving) { target in
            MoveSheet(shelves: holder.targets, current: holder.row?.collection.id,
                      title: target.collectionFrom == nil ? L("Move to collection…") : L("Move collection to…"), collectionFrom: target.collectionFrom) { choice in
                moving = nil
                switch choice {
                case let .existing(id): target.move(holder.model, id)
                case let .newCollection(type): newName = ""; naming = .newCollection(target, type)
                case .newMediaType: newName = ""; naming = .newMediaType(target)
                }
            }
        }
        .alert(naming?.title ?? "", isPresented: Binding(get: { naming != nil }, set: { if !$0 { naming = nil } })) { namingFields } message: { EmptyView() }
        .alert(dialog?.title ?? "", isPresented: Binding(get: { dialog != nil }, set: { if !$0 { dialog = nil } }), presenting: dialog) { d in
            Button(d.confirm, role: d.destructive ? .destructive : nil) { act(d) }
            Button("Cancel", role: .cancel) {}
        } message: { d in Text(d.message) }
    }

    private var title: String {
        guard let c = holder.row?.collection else { return "" }
        let shown = c.shownName().words
        return holder.model?.titleFor(collection: c, shown: shown) ?? shown
    }

    // MARK: A folder

    private func folder(_ row: CollectionRow) -> some View {
        List(row.children, id: \.collection.id) { child in
            NavigationLink(value: Route.collection(id: child.collection.id)) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(child.collection.shownName().words)
                    Text(ShelfWords.count(child)).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                }
            }
        }
        .scrollContentBackground(.hidden)
    }

    // MARK: The books

    @ViewBuilder private func shelf(_ row: CollectionRow) -> some View {
        if let shown = holder.books { shelf(row, shown) } else { ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity) }
    }

    @ViewBuilder private func shelf(_ row: CollectionRow, _ shown: CollectionBooks) -> some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0, pinnedViews: []) {
                    head(row, shown)
                    if let rows = shown.rows, holder.view == .list {
                        ForEach(rows, id: \.key) { entry in sectionRow(entry) }
                    } else if let rows = shown.rows {
                        grid(rows.compactMap { ($0 as? SectionRowItem)?.book })
                    } else if holder.view == .list {
                        ForEach(shown.sections.sections, id: \.letter) { section in
                            if shown.sections.lettered { letterHeader(section.letter) }
                            ForEach(section.items, id: \.key) { item in if let b = item.value { bookRow(b) } }
                        }
                    } else {
                        ForEach(shown.sections.sections, id: \.letter) { section in
                            if shown.sections.lettered { letterHeader(section.letter) }
                            grid(section.items.compactMap(\.value))
                        }
                    }
                }
                .padding(.bottom, 24)
                .padding(.trailing, shown.sections.lettered ? 20 : 0)
            }
            .overlay(alignment: .trailing) {
                if shown.sections.lettered && shown.rows == nil { LetterStrip(letters: shown.sections.letters) { proxy.scrollTo("letter-\($0)", anchor: .top) } }
            }
        }
    }

    @ViewBuilder private func head(_ row: CollectionRow, _ shown: CollectionBooks) -> some View {
        ForEach(row.packs, id: \.id) { pack in packRow(pack) }
        if let h = holder.header { gutenbergHead(h) }
        if shown.total > 0 {
            HStack {
                Image(systemName: "magnifyingglass").foregroundStyle(palette.onSurfaceVariant)
                TextField("Find a title or author", text: $find).textInputAutocapitalization(.never).autocorrectionDisabled()
                    .accessibilityIdentifier("collection-find")
                if !find.isEmpty { Button { find = "" } label: { Image(systemName: "xmark.circle.fill") }.accessibilityLabel("Clear") }
            }
            .padding(10)
            .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 10))
            .padding(.horizontal, 12).padding(.vertical, 6)
            HStack(spacing: 8) {
                Picker("Sort", selection: Binding(get: { holder.sort }, set: { holder.model?.setSort(sort: $0) })) {
                    ForEach(BookSort.allCases, id: \.self) { s in Text(ScreenText.strings[s.labelKey] ?? s.name).tag(s) }
                }
                .pickerStyle(.segmented)
                .accessibilityIdentifier("collection-sort")
                Button { holder.model?.toggleView() } label: {
                    Image(systemName: holder.view == .list ? "square.grid.2x2" : "list.bullet").frame(width: 36, height: 32)
                }
                .accessibilityLabel(holder.view == .list ? "Show as grid" : "Show as list")
                .accessibilityIdentifier("collection-view")
            }
            .padding(.horizontal, 12).padding(.vertical, 4)
            if !shown.categories.isEmpty {
                chips(shown.categories, chosen: shown.category) { holder.model?.setCategory(category: $0) }
            }
            if !shown.languages.isEmpty {
                chips(shown.languages, chosen: shown.language) { holder.model?.setLanguage(name: $0) }
            }
            if shown.books.isEmpty {
                Text("No books match.").foregroundStyle(palette.onSurfaceVariant).padding(.horizontal, 16).padding(.vertical, 24)
            }
        }
    }

    private func chips(_ names: [String], chosen: String?, pick: @escaping (String?) -> Void) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                chip(L("All"), on: chosen == nil) { pick(nil) }
                ForEach(names, id: \.self) { n in chip(n, on: chosen == n) { pick(chosen == n ? nil : n) } }
            }
            .padding(.horizontal, 12)
        }
        .padding(.vertical, 4)
    }

    private func chip(_ text: String, on: Bool, tap: @escaping () -> Void) -> some View {
        Button(action: tap) {
            Text(text).font(.subheadline).padding(.horizontal, 12).padding(.vertical, 6)
                .foregroundStyle(on ? palette.onSecondaryContainer : palette.onSurface)
                .background(on ? palette.secondaryContainer : Color.clear, in: Capsule())
                .overlay(Capsule().stroke(on ? Color.clear : palette.outline))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }

    private func letterHeader(_ letter: String) -> some View {
        Text(letter).font(Spectral.medium(18)).foregroundStyle(palette.primary)
            .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 4)
            .accessibilityAddTraits(.isHeader)
            .id("letter-\(letter)")
    }

    private func packRow(_ pack: Pack) -> some View {
        let replaced = holder.superseded.contains(pack.id)
        let details = [pack.language, pack.date, QueueWords.bytes(pack.sizeBytes)].filter { !$0.isEmpty }.joined(separator: " · ")
        return VStack(alignment: .leading, spacing: 4) {
            Text(pack.title).foregroundStyle(palette.onSurface)
            Text(details).font(.callout).foregroundStyle(palette.onSurfaceVariant)
            switch pack.status {
            case .missing: Text("File not found. Reinsert the SD card or download the pack again.").font(.callout).foregroundStyle(palette.error)
            case .corrupt: Text("This file is damaged and can't be opened.").font(.callout).foregroundStyle(palette.error)
            default: EmptyView()
            }
            if replaced {
                Text("Replaced by the Granger Classics e-books").font(.callout).foregroundStyle(palette.primary)
                Button("Get the e-books") { openRoute(.source(scope: CatalogScope.granger.name, domain: nil, family: nil, bookSet: nil)) }.buttonStyle(.borderedProminent)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .contentShape(Rectangle())
        .onTapGesture {
            guard pack.status == .ok, let main = core?.library.archive(packId: pack.id)?.mainPath else { return }
            openRoute(.packReader(pack: pack.id, path: main, fragment: nil))
        }
        .contextMenu {
            Button("Move to collection…") { moving = .pack(pack) }
            Button("Remove from phone", role: .destructive) { dialog = .removePack(pack, documents: false) }
        }
    }

    private func gutenbergHead(_ h: GutenbergHeader) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            VStack(alignment: .leading, spacing: 2) {
                Text(h.count.words)
                Text(h.provider.words)
                if let e = h.exceptionText { Text(e.words) }
                if let f = h.fileNamesText { Text(f.words) }
            }
            .font(.callout).foregroundStyle(palette.onSurfaceVariant)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(h.text.words)
            if let p = h.progress {
                Text("Making searchable: \(p.done) of \(p.total) copied").font(.caption.weight(.medium)).foregroundStyle(palette.primary)
                ProgressView(value: p.total == 0 ? 0 : Double(p.done) / Double(p.total))
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .accessibilityIdentifier("collection-provenance")
    }

    // MARK: Rows and cells

    @ViewBuilder private func sectionRow(_ entry: SectionRow) -> some View {
        switch onEnum(of: entry) {
        case let .header(h):
            if h.foldable {
                Button { holder.model?.toggleSection(name: h.name) } label: {
                    HStack {
                        Text("\(h.name) · \(h.count)").font(Spectral.medium(18)).foregroundStyle(palette.primary)
                        Spacer()
                        Image(systemName: h.expanded ? "chevron.up" : "chevron.down").foregroundStyle(palette.primary)
                    }
                    .padding(.horizontal, 16).padding(.top, 12).padding(.bottom, 4)
                }
                .accessibilityAddTraits(.isHeader)
                .accessibilityValue(h.expanded ? "Expanded" : "Collapsed")
            } else {
                Text(h.name).font(Spectral.medium(18)).foregroundStyle(palette.primary)
                    .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 4).accessibilityAddTraits(.isHeader)
            }
        case let .work(w):
            Button { holder.model?.toggleWork(workKey: w.workKey) } label: {
                HStack(spacing: 12) {
                    BookCoverView(book: w.first).frame(height: 64)
                    VStack(alignment: .leading, spacing: 2) {
                        Text(w.title).foregroundStyle(palette.onSurface).lineLimit(2)
                        if !w.author.isEmpty { Text(w.author).font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(1) }
                        Text("\(w.parts.count) parts").font(.caption).foregroundStyle(palette.onSurfaceVariant)
                    }
                    Spacer()
                    Image(systemName: w.expanded ? "chevron.up" : "chevron.down").foregroundStyle(palette.onSurfaceVariant)
                }
                .padding(.horizontal, 16).padding(.vertical, 6)
            }
            .buttonStyle(.plain)
            .accessibilityValue(w.expanded ? "Expanded" : "Collapsed")
        case let .item(i):
            bookRow(i.book).padding(.leading, i.part ? 24 : 0)
        }
    }

    private func bookRow(_ book: Book) -> some View {
        let readable = book.status == .ok
        let edition = holder.model?.editionOf(book: book)
        let progress = holder.positions[book.id]?.totalProgression ?? 0
        return Button { if readable { open(book) } } label: { HStack(spacing: 12) {
            BookCoverView(book: book).frame(height: 64)
            VStack(alignment: .leading, spacing: 2) {
                Text(edition?.title ?? book.title).foregroundStyle(palette.onSurface).lineLimit(2)
                if edition == nil, !book.author.isEmpty { Text(book.author).font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(1) }
                if let label = edition != nil ? L("Reading edition") : BookLabels.format(book) { Text(label).font(.caption).foregroundStyle(palette.onSurfaceVariant) }
                if let problem = BookLabels.problem(book) { Text(problem).font(.caption).foregroundStyle(palette.error) }
            }
            Spacer(minLength: 8)
            if readable && progress > 0 {
                Text("\(min(100, max(1, Int((progress * 100).rounded()))))%").font(.caption.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
            }
        }
        .opacity(readable ? 1 : 0.4)
        .padding(.horizontal, 16).padding(.vertical, 6)
        .contentShape(Rectangle()) }
        .buttonStyle(.plain)
        .contextMenu { bookMenu(book, edition: edition) }
        .accessibilityIdentifier("book-\(book.id)")
    }

    private func grid(_ books: [Book]) -> some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 104), spacing: 16, alignment: .top)], alignment: .leading, spacing: 16) {
            ForEach(books, id: \.id) { book in cell(book) }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }

    private func cell(_ book: Book) -> some View {
        let readable = book.status == .ok
        let progress = holder.positions[book.id]?.totalProgression ?? 0
        return Button { if readable { open(book) } } label: { VStack(alignment: .leading, spacing: 6) {
            BookCoverView(book: book).opacity(readable ? 1 : 0.4)
            Text(book.title).font(.footnote).lineLimit(2, reservesSpace: true).foregroundStyle(palette.onSurface)
            Text(book.author.isEmpty ? " " : book.author).font(.caption2).lineLimit(1).foregroundStyle(palette.onSurfaceVariant)
            if let label = BookLabels.format(book) { Text(label).font(.caption2).foregroundStyle(palette.onSurfaceVariant) }
            if let problem = BookLabels.problem(book) {
                Text(problem).font(.caption2).foregroundStyle(palette.error)
            } else {
                ProgressView(value: min(1, max(0, progress))).tint(palette.primary)
            }
        }
        .contentShape(Rectangle()) }
        .buttonStyle(.plain)
        .contextMenu { bookMenu(book, edition: holder.model?.editionOf(book: book)) }
        .accessibilityIdentifier("cell-\(book.id)")
    }

    @ViewBuilder private func bookMenu(_ book: Book, edition: BookEntry?) -> some View {
        if edition != nil { Button("Original scan") { openOriginal(book) } }
        if book.scanTitleOnly { Text("A scan: title only") } else if book.titleOnlyUntilOpened { Text("Title only until opened") }
        Button("Move to collection…") { moving = .book(book) }
        Button("Remove from phone", role: .destructive) { dialog = .removeBook(book) }
    }

    private func open(_ book: Book) {
        Task {
            guard let model = holder.model, (try? await model.openBook(book: book)) != nil else { return }
            openRoute(.book(id: book.id))
        }
    }

    private func openOriginal(_ book: Book) {
        Task {
            guard let model = holder.model, (try? await model.openOriginal(book: book)) != nil else { return }
            openRoute(.book(id: book.id))
        }
    }

    // MARK: The collection's menu and its dialogs

    private func menu(_ row: CollectionRow) -> some View {
        Menu {
            if let g = holder.header {
                if g.kind != .bookSet && g.progress == nil && g.searchable < g.books {
                    Button("Make all searchable…") {
                        Task { if let c = try? await holder.model?.searchableCost() { dialog = .makeSearchable(c, documents: g.countsDocuments) } }
                    }
                }
                if let pack = g.pack, pack.status == .ok, let main = core?.library.archive(packId: pack.id)?.mainPath {
                    Button("Open the pack's own pages") { openRoute(.packReader(pack: pack.id, path: main, fragment: nil)) }
                }
                if let pack = g.pack { Button("Remove pack", role: .destructive) { dialog = .removePack(pack, documents: g.countsDocuments) } }
            }
            if !holder.removed.isEmpty { Button("Show removed") { holder.model?.showRemoved() } }
            Button("Move collection to…") { moving = .collection(row.collection.mediaTypeId) }
            if row.collection.userCreated {
                Button("Rename") { newName = row.collection.name; naming = .rename }
                Button("Delete", role: .destructive) { dialog = .delete }
            }
        } label: { Image(systemName: "ellipsis.circle") }
        .accessibilityLabel("More")
        .accessibilityIdentifier("collection-more")
    }

    @ViewBuilder private var namingFields: some View {
        switch naming {
        case .rename:
            TextField("Collection name", text: $newName)
            Button("Rename") { let n = newName; if !n.trimmingCharacters(in: .whitespaces).isEmpty { holder.model?.rename(name: n) } }
        case let .newCollection(target, type):
            TextField("Collection name", text: $newName).accessibilityIdentifier("new-collection-name")
            Button("Move") { let n = newName; if !n.trimmingCharacters(in: .whitespaces).isEmpty { target.moveToNew(holder.model, type, n) } }
        case let .newMediaType(target):
            TextField("Media type name", text: $newName)
            Button("Move") { let n = newName; if !n.trimmingCharacters(in: .whitespaces).isEmpty { target.moveToNewType(holder.model, n) } }
        case nil:
            EmptyView()
        }
        Button("Cancel", role: .cancel) {}
    }

    private func act(_ d: CollectionDialog) {
        switch d {
        case let .removeBook(b): holder.model?.removeBook(book: b)
        case let .removePack(p, _): holder.model?.remove(pack: p)
        case let .makeSearchable(c, _): if c.fits { holder.model?.makeAllSearchable() }
        case .delete: holder.model?.delete()
        }
    }
}

/// What a book's row says about its format, and why it can't be opened.
enum BookLabels {
    static func format(_ book: Book) -> String? {
        guard book.format == .pdf else { return nil }
        return book.searchable ? "PDF" : L("PDF · A scan: title only")
    }

    static func problem(_ book: Book) -> String? {
        switch book.status {
        case .missing: L("File not found")
        case .corrupt: L("Damaged")
        default: nil
        }
    }
}

/// The letter strip beside a long shelf: a tap on a letter goes to its books.
struct LetterStrip: View {
    let letters: [String]
    let go: (String) -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(spacing: 1) {
            ForEach(letters, id: \.self) { l in
                Button { go(l) } label: { Text(l).font(.caption2.weight(.semibold)).frame(width: 20, height: 16) }
                    .foregroundStyle(palette.primary)
                    .accessibilityLabel("Jump to \(l)")
            }
        }
        .padding(.vertical, 6)
        .padding(.trailing, 2)
        .accessibilityIdentifier("letter-strip")
    }
}

/// The collection screen's confirmations, in Android's words.
enum CollectionDialog: Identifiable {
    case removeBook(Book)
    case removePack(Pack, documents: Bool)
    case makeSearchable(SearchableCost, documents: Bool)
    case delete

    var id: String {
        switch self {
        case let .removeBook(b): "book:\(b.id)"
        case let .removePack(p, _): "pack:\(p.id)"
        case .makeSearchable: "searchable"
        case .delete: "delete"
        }
    }

    var title: String {
        switch self {
        case .removeBook, .removePack: L("Remove from phone?")
        case let .makeSearchable(c, _): c.fits ? L("Make all searchable?") : L("Not enough space")
        case .delete: L("Delete collection?")
        }
    }

    var message: String {
        switch self {
        case let .removeBook(b):
            b.origin.isPackBook
                ? L("\(b.title) will be deleted from this phone and taken off this list; Show removed brings it back. Your place, bookmarks and highlights are kept.")
                : L("\(b.title) will be deleted from this phone. Your place, bookmarks and highlights are kept.")
        case let .removePack(p, documents):
            !p.isBookPack
                ? L("\(p.title) will be deleted from this phone, with the bookmarks and reading places you saved in it. You can download it again later.")
                : documents
                ? L("\(p.title) will be deleted from this phone, with its documents. You can download it again later. Your places, bookmarks and highlights are kept.")
                : L("\(p.title) will be deleted from this phone, with its books. You can download it again later. Your places, bookmarks and highlights are kept.")
        case let .makeSearchable(c, documents):
            Self.searchable(c, documents: documents)
        case .delete:
            L("Its packs go back to their usual collections. Nothing is removed from the phone.")
        }
    }

    var confirm: String {
        switch self {
        case .removeBook, .removePack: L("Remove")
        case let .makeSearchable(c, _): c.fits ? L("Make searchable") : L("OK")
        case .delete: L("Delete")
        }
    }

    var destructive: Bool {
        switch self {
        case .removeBook, .removePack, .delete: true
        case .makeSearchable: false
        }
    }

    private static func searchable(_ c: SearchableCost, documents: Bool) -> String {
        let n = Int(c.books)
        let size = QueueWords.bytes(c.bytes)
        let free = QueueWords.bytes(c.freeBytes)
        if !c.fits {
            return documents
                ? L("Making all \(n) documents searchable needs about \(size) more, and this phone has \(free) free.")
                : L("Making all \(n) books searchable needs about \(size) more, and this phone has \(free) free.")
        }
        return documents
            ? L("Copies the \(n) documents not yet opened out of the pack, so Search can read them in full. That takes about \(size) more (\(free) free). It runs in the background; you can leave this screen.")
            : L("Copies the \(n) books not yet opened out of the pack, so Search can read them in full. That takes about \(size) more (\(free) free). It runs in the background; you can leave this screen.")
    }
}

/// An installed book pack, opened from Your sources (Android's openPackOrCollection): its Library collection, whose
/// books open in the reader (Plan I5 Task 6 finishes I4's book-pack row). A pack the shelves don't file as a book pack
/// yet lists its books as I4 did.
struct BookPackCollection: View {
    let pack: String
    @Environment(\.core) private var core
    @State private var collectionId: String?
    @State private var looked = false

    var body: some View {
        Group {
            if let collectionId { CollectionScreen(collectionId: collectionId) }
            else if looked { BookPackScreen(pack: pack) }
            else { ProgressView() }
        }
        .task {
            guard let core, !looked else { return }
            collectionId = try? await core.library.bookPackCollection(packId: pack)
            looked = true
        }
    }
}
