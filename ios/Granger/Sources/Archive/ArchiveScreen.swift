import GrangerCoreKit
import SwiftUI

/// The shared ArchiveModel as SwiftUI sees it.
@Observable
final class ArchiveHolder {
    private(set) var ui: ArchiveUi?
    private var owned: OwnedViewModel<ArchiveModel>?
    private var watching: Task<Void, Never>?
    var model: ArchiveModel? { owned?.model }

    @MainActor func start(_ core: Core, voice: VoiceCenter?, kind: ArchiveKind) {
        guard owned == nil else { return }
        let o: OwnedViewModel<ArchiveModel>
        if kind == .texts {
            o = core.library.archiveOrg()
        } else {
            // Spoken word and music (Plan I6 Task 7) download through the media downloads, as a book or an album.
            guard let music = voice?.music.music else { return }
            o = music.archiveAudio(kind: kind)
        }
        owned = o
        watching = Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// archive.org's texts (Android's ArchiveScreen over the shared ArchiveModel, Plan I5 Task 7): Browse and On this
/// phone, a search, the shelves, rows that load more at the end, and an item's sheet with Keep (through the shared
/// queue), its PDF beside it, a set's parts in order, and Open once it's here. The App Store's rule (call 9): only
/// books published before 1930, and no "Show everything". Since Plan I6 Task 7 also spoken word and music ([kind]),
/// with Android's release-build rules: recordings before the filter year, and spoken word (undated) only its note and
/// the way to LibriVox.
struct ArchiveScreen: View {
    var kind: ArchiveKind = .texts
    @Environment(\.core) private var core
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = ArchiveHolder()
    @State private var query = ""
    @State private var removing: String?

    var body: some View {
        List {
            if let ui = holder.ui, let model = holder.model {
                Section {
                    Picker("Show", selection: Binding(get: { ui.tab }, set: { model.setTab(value: $0) })) {
                        ForEach(ArchiveTab.allCases, id: \.self) { t in Text(ScreenText.strings[t.titleKey] ?? t.name).tag(t) }
                    }
                    .pickerStyle(.segmented)
                    if ui.tab == .browse && ui.waiverNeeded {
                        Text(ArchiveWords.spokenWordNote).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                        Button("LibriVox") { openRoute(.librivox) }.buttonStyle(.bordered).accessibilityIdentifier("archive-librivox")
                    } else if ui.tab == .browse {
                        ScrollView(.horizontal, showsIndicators: false) {
                            HStack(spacing: 8) {
                                ForEach(ArchiveShelf.allCases.filter { $0.kind == ui.kind }, id: \.self) { shelf in
                                    Button(ScreenText.strings[shelf.titleKey] ?? shelf.name) { query = ""; model.selectShelf(shelf: shelf) }
                                        .buttonStyle(.bordered)
                                        .tint(ui.shelf == shelf ? palette.primary : .secondary)
                                        .accessibilityAddTraits(ui.shelf == shelf ? .isSelected : [])
                                }
                            }
                        }
                        Text(ui.kind == .texts ? ArchiveWords.filterNote : "Showing public-domain recordings only: published before \(ui.filterYear).")
                            .font(.caption).foregroundStyle(palette.onSurfaceVariant)
                    }
                    if let notice = ui.notice {
                        HStack { Text(notice.words).foregroundStyle(palette.onSurface); Spacer(); Button("OK") { model.dismissNotice() } }
                    }
                }
                if let error = ui.error, ui.rows.isEmpty {
                    Section {
                        Text(error.words).foregroundStyle(palette.error)
                        Button("Try again") { model.retry() }
                    }
                }
                if !(ui.waiverNeeded && ui.tab == .browse) { Section {
                    ForEach(ui.rows, id: \.identifier) { row in
                        Button { model.openItem(identifier: row.identifier) } label: { rowView(row) }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("archive-row-\(row.identifier)")
                            .onAppear { if row.identifier == ui.rows.last?.identifier, ui.canLoadMore { model.loadMore() } }
                    }
                    if ui.loading { HStack { Spacer(); ProgressView(); Spacer() } }
                } }
            } else {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Archive.org")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: prompt)
        .onChange(of: query) { _, q in holder.model?.setQuery(value: q) }
        .task { if let core { holder.start(core, voice: voice, kind: kind) } }
        .sheet(item: Binding(get: { holder.ui?.sheet.map(SheetItem.init) }, set: { if $0 == nil { holder.model?.closeItem() } })) { item in
            if let model = holder.model {
                ArchiveItemSheet(sheet: item.sheet, model: model, audio: holder.ui?.sheetAudio, kind: kind) {
                    if kind == .texts { open(item.sheet.identifier) } else { openInLibrary(item.sheet.identifier) }
                } onRemove: { removing = item.sheet.identifier }
                    .presentationDetents([.medium, .large])
            }
        }
        .alert(largeTitle, isPresented: Binding(get: { holder.ui?.confirm != nil }, set: { if !$0 { holder.model?.dismissLarge() } })) {
            Button(ArchiveWords.download) { holder.model?.confirmLarge(dontAskAgain: false) }
            Button(ArchiveWords.dontAskAgain) { holder.model?.confirmLarge(dontAskAgain: true) }
            Button("Cancel", role: .cancel) { holder.model?.dismissLarge() }
        }
        .alert("Remove?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Remove", role: .destructive) { if let id = removing { holder.model?.remove(identifier: id) }; removing = nil }
            Button("Keep", role: .cancel) { removing = nil }
        } message: { Text(kind == .texts ? ArchiveWords.removeBody : "This deletes its files. Your place and bookmarks are kept.") }
    }

    private var prompt: String {
        switch kind {
        case .spokenWord: "Search archive.org audiobooks"
        case .music: "Search archive.org music"
        default: "Search archive.org texts"
        }
    }

    /// An audio item got from the other audio view, where it lives: a book (got from Audiobooks) seen in Music, or an
    /// album (got from Music) seen in Audiobooks (Android's inLibraryRoute).
    private func openInLibrary(_ identifier: String) {
        holder.model?.closeItem()
        if kind == .music {
            openRoute(.audiobook(id: "audiobook:\(ArchiveAudioCompanion.shared.SOURCE):\(identifier)"))
        } else {
            openRoute(.album(key: Albums.shared.itemKey(source: ArchiveAudioCompanion.shared.SOURCE, ref: identifier)))
        }
    }

    private var largeTitle: String {
        guard let c = holder.ui?.confirm else { return "" }
        let size = QueueWords.bytes(c.bytes)
        switch c.what {
        case .pdf: return "This PDF is \(size). Download it?"
        case .part: return "This part is \(size). Download it?"
        case .set: return "This set is \(size). Download it?"
        case .parts: return c.count == 1 ? "This part is \(size). Download it?" : "These \(c.count) parts are \(size). Download them?"
        default: return "This download is \(size). Download it?"
        }
    }

    /// Open on an installed text: one book in the reader, or a set's parts in their collection.
    private func open(_ identifier: String) {
        guard let core, let model = holder.model else { return }
        Task {
            guard let target = try? await core.library.archiveOpenTarget(model: model, identifier: identifier) else { return }
            model.closeItem()
            switch onEnum(of: target) {
            case let .book(b): openRoute(.book(id: b.bookId))
            case let .collection(c): openRoute(.collection(id: c.collectionId))
            }
        }
    }

    private func rowView(_ row: ArchiveRow) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(row.title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface).multilineTextAlignment(.leading)
            if !row.details.isEmpty { Text(row.details).font(.callout).foregroundStyle(palette.onSurfaceVariant) }
            if let label = ArchiveWords.offer(row.preview, sheet: nil) ?? ArchiveWords.audio(row.audio) { Text(label).font(.caption).foregroundStyle(palette.tertiary) }
            if let line = ArchiveWords.status(row.status) { Text(line).font(.caption).foregroundStyle(palette.tertiary) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

struct SheetItem: Identifiable {
    let sheet: ItemSheet
    var id: String { sheet.identifier }
}

/// An item's details with Keep, its PDF beside it, a set's parts, Open and Remove (Android's ArchiveItemSheet).
struct ArchiveItemSheet: View {
    let sheet: ItemSheet
    let model: ArchiveModel
    /// What an audio item offers (texts use the sheet's offer), and the view it's seen in.
    var audio: AudioOffer? = nil
    var kind: ArchiveKind = .texts
    let onOpen: () -> Void
    let onRemove: () -> Void
    @Environment(\.palette) private var palette
    @Environment(\.openURL) private var openURL
    @State private var choosing = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 10) {
                    if choosing && !sheet.parts.isEmpty { parts } else { details }
                }
                .padding(24)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                if choosing { ToolbarItem(placement: .topBarLeading) { Button("Back") { choosing = false } } }
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("archive-sheet")
    }

    @ViewBuilder private var details: some View {
        let item = sheet.item
        Text(item.map { $0.title.isEmpty ? sheet.identifier : $0.title } ?? sheet.identifier).font(Spectral.medium(22)).foregroundStyle(palette.onSurface)
        if sheet.loading { ProgressView().frame(maxWidth: .infinity).padding(24) }
        if let error = sheet.error { Text(error.words).foregroundStyle(palette.error) }
        if let item {
            let byline = [item.creator, item.date].filter { !$0.isEmpty }.joined(separator: " · ")
            if !byline.isEmpty { Text(byline).font(.callout) }
            if !item.subjects.isEmpty { Text(item.subjects.prefix(6).joined(separator: " · ")).font(.caption).foregroundStyle(palette.onSurfaceVariant) }
            if !item.description_.isEmpty { Text(item.description_).font(.footnote).lineLimit(6) }
            if let label = ArchiveWords.offer(sheet.offer, sheet: sheet) ?? ArchiveWords.audio(audio) { Text(label).font(.callout.weight(.medium)) }
        }
        if kind != .texts, sheet.filesTotal > 0 {
            switch onEnum(of: sheet.status) {
            case .queued, .downloading: Text("Downloading: \(sheet.filesDone) of \(sheet.filesTotal)").font(.callout)
            default: EmptyView()
            }
        }
        if let line = ArchiveWords.status(sheet.status) { Text(line).font(.callout).foregroundStyle(palette.tertiary) }
        HStack(spacing: 8) {
            buttons
            if let pdf = sheet.pdfSizeBytes?.int64Value {
                switch onEnum(of: sheet.pdfStatus) {
                case .notInstalled: Button(ArchiveWords.getPdf(pdf)) { model.get(identifier: sheet.identifier, pdf: true) }.buttonStyle(.bordered)
                case .installed, .updateAvailable: Text("PDF in your library").font(.caption)
                default: Text(ArchiveWords.status(sheet.pdfStatus).map { "PDF: \($0)" } ?? "").font(.caption).foregroundStyle(palette.onSurfaceVariant)
                }
            }
        }
        if !sheet.parts.isEmpty { Button("Choose parts") { choosing = true }.buttonStyle(.bordered).accessibilityIdentifier("archive-choose-parts") }
        if let url = URL(string: "https://archive.org/details/\(sheet.identifier)") {
            Button("View on archive.org") { openURL(url) }.buttonStyle(.bordered)
        }
    }

    @ViewBuilder private var buttons: some View {
        switch onEnum(of: sheet.status) {
        case .queued, .downloading:
            Button("Cancel") { model.cancel(identifier: sheet.identifier) }
        case .failed:
            Button("Cancel") { model.cancel(identifier: sheet.identifier) }
            Button("Retry") { model.retryDownload(identifier: sheet.identifier) }.buttonStyle(.borderedProminent)
        case .installed, .updateAvailable:
            Button("Remove", action: onRemove)
            // Audio is opened from its shelf, as on Android; a text opens as its book.
            if kind == .texts { Button("Open", action: onOpen).buttonStyle(.borderedProminent).accessibilityIdentifier("archive-open") }
        case .partial:
            Button("Remove", action: onRemove)
            Button("Keep the rest") { model.get(identifier: sheet.identifier, pdf: false) }.buttonStyle(.borderedProminent)
        case .inLibrary:
            Button("Open", action: onOpen).buttonStyle(.borderedProminent).accessibilityIdentifier("archive-open")
        default:
            if let audio {
                switch onEnum(of: audio) {
                case .tracks: Button("Keep") { model.get(identifier: sheet.identifier, pdf: false) }.buttonStyle(.borderedProminent)
                    .accessibilityIdentifier("archive-keep")
                case .lendOnly:
                    if let url = URL(string: "https://archive.org/details/\(sheet.identifier)") { Button("Borrow on archive.org") { openURL(url) } }
                case .nothing: EmptyView()
                }
            } else if let offer = sheet.offer {
                switch onEnum(of: offer) {
                case .download:
                    let all = !sheet.parts.isEmpty
                    let label = sheet.sizeBytes.map { (all ? "Keep all · " : "Keep · ") + QueueWords.bytes($0.int64Value) } ?? (all ? "Keep all" : "Keep")
                    Button(label) { model.get(identifier: sheet.identifier, pdf: false) }.buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("archive-keep")
                case .lendOnly:
                    if let url = URL(string: "https://archive.org/details/\(sheet.identifier)") { Button("Borrow on archive.org") { openURL(url) } }
                case .nothing:
                    EmptyView()
                }
            }
        }
    }

    /// A set's parts in order, each with its size and where its download stands, Keep or Remove on its own.
    @ViewBuilder private var parts: some View {
        Text(sheet.parts.count == 1 ? "1 part" : "\(sheet.parts.count) parts").font(Spectral.medium(20))
        ForEach(sheet.parts, id: \.number) { part in
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(part.title).font(.body).foregroundStyle(palette.onSurface)
                    Text(ArchiveWords.part(part)).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                }
                Spacer()
                switch onEnum(of: part.status) {
                case .notInstalled: Button("Keep") { model.getPart(identifier: sheet.identifier, number: part.number) }.buttonStyle(.bordered)
                case .failed: Button("Retry") { model.getPart(identifier: sheet.identifier, number: part.number) }.buttonStyle(.bordered)
                case .installed, .updateAvailable: Button("Remove") { model.removePart(identifier: sheet.identifier, number: part.number) }.buttonStyle(.bordered)
                default: EmptyView()
                }
            }
            .accessibilityElement(children: .contain)
            .accessibilityIdentifier("archive-part-\(part.number)")
            Divider()
        }
    }
}

/// archive.org's words (Android's strings_archive.xml).
enum ArchiveWords {
    static let filterNote = "Showing public-domain texts only: published 1930 or earlier."
    static let download = "Keep"
    static let dontAskAgain = "Don't ask again"
    static let removeBody = "This deletes the file. Your place, bookmarks and highlights are kept."
    static let spokenWordNote = "Archive.org dates spoken-word recordings by their text, not the recording, so Granger can't tell which are in the public domain. LibriVox recordings all are."

    /// What an audio item offers: its format on a row, "7 tracks · VBR MP3" once its files are known.
    static func audio(_ offer: AudioOffer?) -> String? {
        guard let offer else { return nil }
        switch onEnum(of: offer) {
        case let .tracks(t): return t.files.isEmpty ? t.format : (t.files.count == 1 ? "1 track · \(t.format)" : "\(t.files.count) tracks · \(t.format)")
        case .lendOnly: return "Borrow on archive.org"
        case .nothing: return "No downloadable audio"
        }
    }
    static func getPdf(_ bytes: Int64) -> String { "Keep PDF · \(QueueWords.bytes(bytes))" }

    /// What an item offers ("EPUB", "OCR text: may have errors · PDF", "Comic: its pages, to zoom · 4 parts").
    static func offer(_ offer: Offer?, sheet: ItemSheet?) -> String? {
        guard let offer else { return nil }
        switch onEnum(of: offer) {
        case let .download(d):
            let kind: String = switch d.kind {
            case .epub: d.pdf != nil ? "EPUB made from a scan · PDF" : "EPUB"
            case .text: "Plain text"
            case .ocrText: d.pdf != nil ? "OCR text: may have errors · PDF" : "OCR text: may have errors"
            case .pdf: "PDF"
            case .comic: "Comic: its pages, to zoom"
            }
            return d.isSet ? "\(kind) · \(d.parts.count) parts" : kind
        case .lendOnly: return "Borrow on archive.org"
        case .nothing: return "No downloadable text"
        }
    }

    /// Where a download stands, in a row or the sheet; nil when there's nothing to say.
    static func status(_ s: ItemStatus) -> String? {
        switch onEnum(of: s) {
        case let .queued(q): q.lastError.map { "Retrying: \($0)" } ?? (q.wifiOnly ? "Waiting for Wi-Fi" : "Queued")
        case let .downloading(d): "Downloading · \(Int(d.fraction * 100))%"
        case let .failed(f): f.reason.map { "Stopped: \($0)" } ?? "Stopped"
        case .installed: "In your library"
        case let .partial(p): "\(p.done) of \(p.total) in your library"
        default: nil
        }
    }

    static func part(_ p: PartRow) -> String {
        let size = QueueWords.bytes(p.sizeBytes)
        switch onEnum(of: p.status) {
        case .queued: return "\(size) · queued"
        case let .downloading(d): return "\(size) · downloading, \(Int(d.fraction * 100))%"
        case let .failed(f): return "\(size) · stopped: \(f.reason ?? "")"
        case .installed, .updateAvailable: return "\(size) · in your library"
        default: return size
        }
    }
}
