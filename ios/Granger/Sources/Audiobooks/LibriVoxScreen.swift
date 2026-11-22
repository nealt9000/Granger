import GrangerCoreKit
import SwiftUI

/// The shared LibriVoxModel as SwiftUI sees it (Plan I6 Task 7).
@Observable
final class LibriVoxHolder {
    private(set) var ui: LibriVoxUi?
    private var owned: OwnedViewModel<LibriVoxModel>?
    private var watching: Task<Void, Never>?
    var model: LibriVoxModel? { owned?.model }

    @MainActor func start(_ voice: VoiceCenter?) {
        guard owned == nil, let music = voice?.music.music else { return }
        let o = music.librivox()
        owned = o
        watching = Task { @MainActor [weak self] in for await u in o.model.ui { self?.ui = u } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// LibriVox (Android's LibriVoxScreen over the shared LibriVoxModel): search by title or author, Browse (Recently added
/// and six genres) and On this phone, rows with Keep, Cancel, Retry or Remove, and a book's sheet with its readers,
/// chapters and size. A book downloads through the shared queue as one group of 64 kbps chapters, then sits on the
/// Audiobooks shelf.
struct LibriVoxScreen: View {
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @State private var holder = LibriVoxHolder()
    @State private var query = ""
    @State private var removing: (id: String, title: String?)?

    var body: some View {
        List {
            if let ui = holder.ui, let model = holder.model {
                Section {
                    Picker("Show", selection: Binding(get: { ui.tab }, set: { model.setTab(value: $0) })) {
                        ForEach(LibriVoxTab.allCases, id: \.self) { Text($0.title.words).tag($0) }
                    }
                    .pickerStyle(.segmented)
                    if ui.tab == .browse && ui.query.isEmpty {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(LibriVoxShelf.allCases, id: \.self) { shelf in
                                    Button(ScreenText.strings[shelf.titleKey] ?? shelf.name) { model.selectShelf(shelf: shelf) }
                                        .buttonStyle(.bordered)
                                        .tint(ui.shelf == shelf ? palette.primary : .secondary)
                                        .accessibilityAddTraits(ui.shelf == shelf ? .isSelected : [])
                                }
                            }
                        }
                    }
                    if let notice = ui.notice {
                        HStack { Text(notice.words).foregroundStyle(palette.onSurface); Spacer(); Button("OK") { model.dismissNotice() } }
                    }
                }
                if let error = ui.error {
                    Section {
                        Text(error.words).foregroundStyle(palette.error)
                        Button("Try again") { model.retry() }
                    }
                }
                Section {
                    if !ui.loading && ui.error == nil && ui.rows.isEmpty {
                        Text(ui.tab == .installed ? "Nothing downloaded from LibriVox yet." : "Nothing matches that search.")
                            .foregroundStyle(palette.onSurfaceVariant)
                    }
                    ForEach(ui.rows, id: \.id) { row in
                        HStack {
                            Button { model.openBook(id: row.id) } label: { rowView(row) }.buttonStyle(.plain)
                            buttons(row.status, id: row.id, title: row.title, files: true, model: model)
                        }
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("librivox-row-\(row.id)")
                    }
                    if ui.loading { HStack { Spacer(); ProgressView(); Spacer() } }
                    if ui.canLoadMore { Button("Load more") { model.loadMore() }.frame(maxWidth: .infinity) }
                }
                Section {
                    Text("LibriVox recordings are in the public domain. Files are served by the Internet Archive.")
                        .font(.caption).foregroundStyle(palette.onSurfaceVariant)
                }
            } else {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("LibriVox")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search LibriVox by title or author")
        .onChange(of: query) { _, q in holder.model?.setQuery(value: q) }
        .task { holder.start(voice) }
        .sheet(item: Binding(get: { holder.ui?.sheet.map(LibriVoxSheetItem.init) }, set: { if $0 == nil { holder.model?.closeBook() } })) { item in
            if let model = holder.model {
                LibriVoxBookSheet(sheet: item.sheet, fallbackTitle: holder.ui?.rows.first { $0.id == item.sheet.id }?.title, model: model) {
                    removing = (item.sheet.id, item.sheet.book.map { LibriVoxTextKt.decodeEntities(text: $0.title) } ?? holder.ui?.rows.first { $0.id == item.sheet.id }?.title)
                }
                .presentationDetents([.medium, .large])
            }
        }
        .alert(removing?.title.map { L("Remove \($0)?") } ?? L("Remove this book?"), isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Remove", role: .destructive) { if let id = removing?.id { holder.model?.remove(id: id) }; removing = nil }
            Button("Keep", role: .cancel) { removing = nil }
        } message: { Text("This deletes its files. Your place and bookmarks are kept.") }
        .accessibilityIdentifier("librivox")
    }

    private func rowView(_ row: LibriVoxRow) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(row.title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface).multilineTextAlignment(.leading)
            let details = row.details.words
            if !details.isEmpty { Text(details).font(.callout).foregroundStyle(palette.onSurfaceVariant) }
            if let line = ArchiveWords.status(row.status) { Text(line).font(.caption).foregroundStyle(palette.tertiary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }

    /// A row's buttons (Android's BookRow): Keep, Cancel, Retry, Remove, Keep the rest.
    @ViewBuilder private func buttons(_ status: ItemStatus, id: String, title: String, files: Bool, model: LibriVoxModel) -> some View {
        switch onEnum(of: status) {
        case .notInstalled: Button("Keep") { model.get(id: id) }.buttonStyle(.borderless).accessibilityIdentifier("librivox-keep-\(id)")
        case .queued, .downloading: Button("Cancel") { model.cancel(id: id) }.buttonStyle(.borderless)
        case .failed:
            Button("Cancel") { model.cancel(id: id) }.buttonStyle(.borderless)
            Button("Retry") { model.retryDownload(id: id) }.buttonStyle(.borderless)
        case .installed, .updateAvailable: Button("Remove") { removing = (id, title) }.buttonStyle(.borderless)
        case .partial:
            Button("Remove") { removing = (id, title) }.buttonStyle(.borderless)
            Button("Keep the rest") { model.get(id: id) }.buttonStyle(.borderless)
        default: EmptyView()
        }
    }
}

struct LibriVoxSheetItem: Identifiable {
    let sheet: LibriVoxSheet
    var id: String { sheet.id }
}

/// A LibriVox book's sheet (Android's LibriVoxBookSheet): authors, readers, language and length, genres, the
/// description, its chapters and size (or why it can't be got), where its download stands, Keep, Cancel, Retry or
/// Remove, and its LibriVox page.
struct LibriVoxBookSheet: View {
    let sheet: LibriVoxSheet
    let fallbackTitle: String?
    let model: LibriVoxModel
    let onRemove: () -> Void
    @Environment(\.palette) private var palette
    @Environment(\.openURL) private var openURL

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 10) {
                let book = sheet.book
                let title = book.map { LibriVoxTextKt.decodeEntities(text: $0.title) }.flatMap { $0.isEmpty ? nil : $0 } ?? fallbackTitle ?? L("LibriVox book")
                Text(title).font(Spectral.medium(22)).foregroundStyle(palette.onSurface)
                if sheet.loading { ProgressView().frame(maxWidth: .infinity).padding(24) }
                if let error = sheet.error { Text(error.words).foregroundStyle(palette.error) }
                if let book {
                    if !book.authors.isEmpty { Text(LibriVoxTextKt.decodeEntities(text: book.authors.joined(separator: ", "))).font(.callout) }
                    let readers = Array(NSOrderedSet(array: book.sections.flatMap(\.readers))) as? [String] ?? []
                    if let first = readers.first {
                        Text(readers.count == 1 ? "Read by \(first)" : "Read by \(first) and \(readers.count - 1) more").font(.caption)
                    }
                    let facts = [book.language.isEmpty ? nil : book.language, LibriVoxModelKt.durationLabel(ms: book.totalTimeMs)?.words].compactMap { $0 }
                    if !facts.isEmpty { Text(facts.joined(separator: " · ")).font(.caption) }
                    if !book.genres.isEmpty { Text(book.genres.joined(separator: " · ")).font(.caption).foregroundStyle(palette.onSurfaceVariant) }
                    if !book.description_.isEmpty { Text(LibriVoxTextKt.decodeEntities(text: book.description_)).font(.footnote).lineLimit(6) }
                    if let offer = sheet.offer {
                        switch onEnum(of: offer) {
                        case let .files(f):
                            let n = f.files.count
                            Text(sheet.sizeBytes.map { L("\(n) chapters · \(QueueWords.bytes($0.int64Value))") } ?? L("\(n) chapters")).font(.callout.weight(.medium))
                        case .incomplete:
                            if case .notInstalled = onEnum(of: sheet.status) { Text("This book's files aren't all on archive.org.").foregroundStyle(palette.onSurfaceVariant) }
                        case .notOnArchive:
                            if case .notInstalled = onEnum(of: sheet.status) { Text("This book isn't on archive.org yet.").foregroundStyle(palette.onSurfaceVariant) }
                        }
                    }
                }
                switch onEnum(of: sheet.status) {
                case .queued, .downloading:
                    if sheet.filesTotal > 0 {
                        Text("Downloading: \(sheet.filesDone) of \(sheet.filesTotal) chapters")
                    }
                default: EmptyView()
                }
                if let line = ArchiveWords.status(sheet.status) { Text(line).font(.callout).foregroundStyle(palette.tertiary).accessibilityIdentifier("librivox-sheet-status") }
                HStack(spacing: 8) {
                    switch onEnum(of: sheet.status) {
                    case .queued, .downloading:
                        Button("Cancel") { model.cancel(id: sheet.id) }
                    case .failed:
                        Button("Cancel") { model.cancel(id: sheet.id) }
                        Button("Retry") { model.retryDownload(id: sheet.id) }.buttonStyle(.borderedProminent)
                    case .installed, .updateAvailable:
                        Button("Remove", action: onRemove)
                    case .partial:
                        Button("Remove", action: onRemove)
                        Button("Keep the rest") { model.get(id: sheet.id) }.buttonStyle(.borderedProminent)
                    default:
                        if let offer = sheet.offer, case .files = onEnum(of: offer) {
                            Button("Keep") { model.get(id: sheet.id) }.buttonStyle(.borderedProminent).accessibilityIdentifier("librivox-sheet-keep")
                        }
                    }
                }
                if let page = sheet.book?.pageUrl, let url = URL(string: page), ["https", "http"].contains(url.scheme ?? "") {
                    Button("View on LibriVox") { openURL(url) }.buttonStyle(.bordered)
                }
            }
            .padding(24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("librivox-sheet")
    }
}
