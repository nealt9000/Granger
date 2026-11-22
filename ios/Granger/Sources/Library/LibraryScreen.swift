import GrangerCoreKit
import SwiftUI
import UniformTypeIdentifiers

/// The shared Library model as SwiftUI sees it.
@Observable
final class LibraryHolder {
    private(set) var ui: LibraryUi?
    private var owned: OwnedViewModel<GrangerCore.LibraryModel>?
    private var watching: Task<Void, Never>?
    var model: GrangerCore.LibraryModel? { owned?.model }

    @MainActor func start(_ core: Core) {
        guard owned == nil else { return }
        let o = core.library.libraryModel()
        owned = o
        watching = Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// The Library tab (Android's LibraryScreen over the shared LibraryModel, Plan I5 Task 6): the books the iPhone holds.
/// Reading now (the latest book large, two small beside it, the rest in a row), the books' collections as stacks of
/// covers, any other kind's collections as rows, Finished, and Outfitting's line. The header has Search, Downloads,
/// Get more and, under More, Outfitting and Import books…; with no books yet, where to get some.
struct LibraryScreen: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @Environment(\.scenePhase) private var scenePhase
    @State private var holder = LibraryHolder()
    @State private var importing = false
    @State private var notice: String?
    @State private var collapsed: Set<String> = []
    @State private var downloads = DownloadsBadge()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let ui = holder.ui { content(ui) } else { ProgressView().frame(maxWidth: .infinity).padding(32) }
            }
            .padding(.bottom, 24)
        }
        .background(palette.background)
        .navigationTitle("Library")
        .toolbar { toolbar }
        .fileImporter(isPresented: $importing, allowedContentTypes: BookImport.types, allowsMultipleSelection: true) { result in
            guard case let .success(urls) = result, let core else { return }
            Task { notice = await BookImport.run(urls, core: core) }
        }
        .overlay(alignment: .bottom) { noticeView }
        .task {
            guard let core else { return }
            holder.start(core)
            downloads.watch(core)
        }
        .onAppear { holder.model?.refresh() }
        .onChange(of: scenePhase) { _, p in if p == .active { holder.model?.refresh() } }
    }

    @ToolbarContentBuilder private var toolbar: some ToolbarContent {
        ToolbarItemGroup(placement: .topBarTrailing) {
            RecentlyOpenMenu()
            NavigationLink(value: Route.search(query: "")) { Image(systemName: "magnifyingglass") }.accessibilityLabel("Search")
            NavigationLink(value: Route.downloads) {
                Image(systemName: "arrow.down.circle").overlay(alignment: .topTrailing) {
                    if downloads.count > 0 {
                        Text("\(downloads.count)").font(.caption2.bold()).foregroundStyle(palette.onError).padding(.horizontal, 4)
                            .background(palette.error, in: Capsule()).offset(x: 8, y: -6)
                    }
                }
            }
            .accessibilityLabel(downloads.count > 0 ? "Downloads, \(downloads.count)" : "Downloads")
            NavigationLink(value: Route.getMore) { Image(systemName: "plus") }.accessibilityLabel("Get more")
            Menu {
                NavigationLink(value: Route.outfitting) { Text("Outfitting") }
                Button("Import books…") { importing = true }.accessibilityIdentifier("library-import")
            } label: { Image(systemName: "ellipsis.circle") }
            .accessibilityLabel("More")
            .accessibilityIdentifier("library-more")
        }
    }

    @ViewBuilder private func content(_ ui: LibraryUi) -> some View {
        if ui.emptyLibrary {
            Text("No books yet").font(Spectral.medium(20)).foregroundStyle(palette.onSurface).padding(.horizontal, 16).padding(.top, 16)
            NavigationLink(value: Route.source(scope: CatalogScope.books.name, domain: nil, family: nil, bookSet: nil)) {
                VStack(alignment: .leading, spacing: 3) {
                    Text("Books by kind").font(Spectral.regular(20)).foregroundStyle(palette.onSurface)
                    Text("Literature, children's books, manuals and learning").font(.callout).foregroundStyle(palette.onSurfaceVariant)
                }
                .frame(maxWidth: .infinity, alignment: .leading).padding(16)
            }
            Button("Import your own EPUB, PDF, TXT or Markdown books") { importing = true }
                .padding(.horizontal, 16).accessibilityIdentifier("library-import-own")
        }
        ReadingNowView(now: ui.shelf.now) { openRoute(.book(id: $0.id)) }
        ForEach(ui.kinds, id: \.id) { kind in
            if kind.id == "books" {
                VStack(alignment: .leading, spacing: 8) {
                    Eyebrow(text: "Collections").padding(.horizontal, 16)
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 14) {
                            ForEach(kind.collections, id: \.collection.id) { row in
                                CollectionStack(row: row, name: ui.nameOf(row: row, shown: row.collection.shownName().words)) {
                                    openRoute(.collection(id: row.collection.id))
                                }
                            }
                        }
                        .padding(.horizontal, 16)
                    }
                }
                .padding(.top, 18)
            } else {
                kindRows(kind)
            }
        }
        FinishedView(books: ui.shelf.finished) { openRoute(.book(id: $0.id)) }
        // Plan BK: everything the catalog has, to open online and keep what you want (Android shows it while online).
        if core?.library.canFetchOnline() == true { EveryCollectionView() }
        if !ui.emptyLibrary {
            NavigationLink(value: Route.outfitting) {
                Text("Sizes, files and sources are in Outfitting").font(.subheadline.weight(.medium)).foregroundStyle(palette.primary)
            }
            .padding(.horizontal, 16).padding(.vertical, 20)
            .accessibilityIdentifier("shelf-footer")
        }
    }

    /// A kind other than books (Video, one the user made): its name, a tap folds it, then its collections.
    @ViewBuilder private func kindRows(_ kind: KindRow) -> some View {
        let open = !collapsed.contains(kind.id)
        Button {
            if open { collapsed.insert(kind.id) } else { collapsed.remove(kind.id) }
        } label: {
            HStack {
                Text(kind.title.words).font(Spectral.medium(18)).foregroundStyle(palette.onSurface)
                Spacer()
                Image(systemName: open ? "chevron.up" : "chevron.down").foregroundStyle(palette.onSurfaceVariant)
            }
            .padding(16)
        }
        .accessibilityValue(open ? "Expanded" : "Collapsed")
        if open {
            ForEach(kind.collections, id: \.collection.id) { row in
                NavigationLink(value: Route.collection(id: row.collection.id)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(row.collection.shownName().words).foregroundStyle(palette.onSurface)
                        Text(ShelfWords.count(row)).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 32).padding(.vertical, 8)
                }
            }
        }
        Divider()
    }

    @ViewBuilder private var noticeView: some View {
        if let notice {
            Text(notice).font(.callout).foregroundStyle(palette.inverseOnSurface).padding(12)
                .background(palette.inverseSurface, in: RoundedRectangle(cornerRadius: 8))
                .padding(16)
                .accessibilityIdentifier("library-notice")
                .task(id: notice) { try? await Task.sleep(for: .seconds(6)); if self.notice == notice { self.notice = nil } }
                .onTapGesture { self.notice = nil }
        }
    }
}

/// Reading now: the first book's cover large with its progress, the next two small beside it, the rest in a row.
struct ReadingNowView: View {
    let now: [ShelfReading]
    let open: (Book) -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        if !now.isEmpty {
            VStack(alignment: .leading, spacing: 10) {
                Eyebrow(text: "Reading now")
                HStack(alignment: .top, spacing: 12) {
                    cover(now[0], width: 150, large: true)
                    VStack(spacing: 12) { ForEach(now.dropFirst().prefix(2), id: \.book.id) { cover($0, width: 92, large: false) } }
                }
                if now.count > 3 {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(alignment: .top, spacing: 10) { ForEach(now.dropFirst(3), id: \.book.id) { cover($0, width: 72, large: false) } }
                    }
                }
            }
            .padding(.horizontal, 16).padding(.top, 12)
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("library-reading-now")
        }
    }

    private func cover(_ r: ShelfReading, width: CGFloat, large: Bool) -> some View {
        let percent = Int(r.progress * 100)
        return Button { open(r.book) } label: {
            VStack(alignment: .leading, spacing: 4) {
                BookCoverView(book: r.book)
                ProgressView(value: Double(r.progress)).tint(palette.primary).scaleEffect(x: 1, y: 0.75)
                if large {
                    Text(r.book.title).font(Spectral.regular(17)).lineLimit(2).foregroundStyle(palette.onSurface).multilineTextAlignment(.leading)
                    if !r.book.author.isEmpty { Text(r.book.author).font(.footnote).foregroundStyle(palette.onSurfaceVariant).lineLimit(1) }
                }
            }
            .frame(width: width)
            // Under a cover of fixed width the title can't grow past xxxLarge: at the accessibility sizes it took most of
            // the screen, a word a line (I5's walk). VoiceOver reads the title and progress whole.
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(r.book.title), \(percent)% read")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("reading-\(r.book.id)")
    }
}

/// A collection as a fanned stack of up to three of its covers, with its name and count.
struct CollectionStack: View {
    let row: CollectionRow
    let name: String
    let open: () -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        let books = Array((row.books + row.children.flatMap(\.books)).prefix(3))
        let count = row.books.count + row.children.reduce(0) { $0 + $1.books.count }
        Button(action: open) {
            VStack(alignment: .leading, spacing: 6) {
                ZStack(alignment: .topLeading) {
                    ForEach(Array(books.enumerated().reversed()), id: \.element.id) { i, b in
                        BookCoverView(book: b).frame(width: 92).offset(x: CGFloat(i * 18), y: CGFloat(i * 8))
                    }
                }
                .frame(width: 132, height: 150, alignment: .topLeading)
                Text(name).font(.subheadline.weight(.medium)).foregroundStyle(palette.onSurface).lineLimit(2).multilineTextAlignment(.leading)
                Text(ShelfWords.books(count)).font(.footnote).foregroundStyle(palette.onSurfaceVariant)
            }
            .frame(width: 132, alignment: .leading)
            .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(name), \(ShelfWords.books(count))")
        .accessibilityAddTraits(.isButton)
        .accessibilityIdentifier("stack-\(row.collection.id)")
    }
}

/// Finished: a row of small covers.
struct FinishedView: View {
    let books: [Book]
    let open: (Book) -> Void

    var body: some View {
        if !books.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Eyebrow(text: "Finished").padding(.horizontal, 16)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 10) {
                        ForEach(books, id: \.id) { b in
                            Button { open(b) } label: { BookCoverView(book: b).frame(width: 64) }.buttonStyle(.plain).accessibilityLabel(b.title)
                        }
                    }
                    .padding(.horizontal, 16)
                }
            }
            .padding(.top, 18)
        }
    }
}

/// The shelf's counts in words (Android's countText and plurals).
enum ShelfWords {
    static func books(_ n: Int) -> String { n == 1 ? "1 book" : "\(n) books" }

    /// "3 packs · 1 book": what a collection row holds, a folder's being its children's.
    static func count(_ row: CollectionRow) -> String {
        let rows = [row] + row.children
        let packs = rows.reduce(0) { $0 + $1.packs.count }
        let books = rows.reduce(0) { $0 + $1.books.count }
        let albums = rows.reduce(0) { $0 + $1.albums.count }
        return [
            packs > 0 ? (packs == 1 ? "1 pack" : "\(packs) packs") : nil,
            books > 0 ? Self.books(books) : nil,
            albums > 0 ? (albums == 1 ? "1 album" : "\(albums) albums") : nil,
        ].compactMap { $0 }.joined(separator: " · ")
    }
}

/// What's coming or stopped in Downloads, for the Library's badge.
@Observable
final class DownloadsBadge {
    private(set) var count = 0
    private var watching: Task<Void, Never>?

    @MainActor func watch(_ core: Core) {
        guard watching == nil else { return }
        let flow = core.library.queueSummary
        watching = Task { @MainActor [weak self] in
            for await s in flow { self?.count = (s.current == nil ? 0 : 1) + s.waiting.count + s.stopped.count }
        }
    }

    deinit { watching?.cancel() }
}

/// Importing books from Files (Plan I5 Task 6): each picked file is copied out of its provider while Granger may read
/// it, then the shared FileImports adds it to the shelf as Android's ImportRunner does (EPUB, PDF, TXT, Markdown; a CBZ
/// or CBR comic becomes a PDF of its pages first). What happened, in Android's words.
enum BookImport {
    static let types: [UTType] = [.epub, .pdf, .plainText, UTType("net.daringfireball.markdown"), UTType(filenameExtension: "md"),
                                  UTType(filenameExtension: "cbz"), UTType(filenameExtension: "cbr")].compactMap { $0 }

    static func run(_ urls: [URL], core: Core) async -> String {
        let folder = FileManager.default.temporaryDirectory.appendingPathComponent("picked-\(UUID().uuidString)", isDirectory: true)
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: folder) }
        var files: [PickedFile] = []
        for (i, url) in urls.enumerated() {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            let copy = folder.appendingPathComponent("\(i)-\(url.lastPathComponent)")
            if (try? FileManager.default.copyItem(at: url, to: copy)) != nil {
                files.append(core.library.pickedFile(path: copy.path, name: url.lastPathComponent))
            } else {
                // Unreadable where it is: the shared import says so in its words.
                files.append(core.library.pickedFile(path: folder.appendingPathComponent("missing-\(i)").path, name: url.lastPathComponent))
            }
        }
        guard let summary = try? await core.library.importPicked(files: files) else { return "Nothing was imported." }
        return summary.text().words
    }

    /// Debug builds: `-debugImport <path on the Mac>` imports that file as a pick would (UI tests can't drive Files).
    static func runDebug(path: String, core: Core) async -> String {
        await run([URL(fileURLWithPath: path)], core: core)
    }
}
