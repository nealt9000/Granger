import GrangerCoreKit
import SwiftUI

/// The shared RemoteCollectionViewModel as SwiftUI sees it.
@Observable
final class RemoteCollectionHolder {
    private(set) var ui: RemoteCollectionUi?
    private var owned: OwnedViewModel<RemoteCollectionViewModel>?
    private var watching: [Task<Void, Never>] = []
    var model: RemoteCollectionViewModel? { owned?.model }

    @MainActor func start(_ core: Core, packId: String, onEvent: @escaping @MainActor (RemoteEvent) -> Void) {
        guard owned == nil else { return }
        let o = core.library.remoteCollectionModel(packEntryId: packId)
        owned = o
        watching = [
            Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } },
            Task { @MainActor in for await e in o.model.event { onEvent(e) } },
        ]
    }

    deinit { watching.forEach { $0.cancel() }; owned?.close() }
}

/// A book pack's collection before (or without) the pack (Android's RemoteCollectionScreen, Plan BK): its list read
/// online, each book's mark (on this phone, saved for now, or online), Find, a tap that reads the book from the pack by
/// range (asking first over 50 MB), Keep this book, a reading edition with its Original scan, and Keep the whole
/// collection, the pack's own download.
struct RemoteCollectionScreen: View {
    let packId: String
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = RemoteCollectionHolder()
    @State private var filter = ""
    @State private var notice: String?
    @State private var confirmPack = false

    var body: some View {
        List {
            if let ui = holder.ui, let model = holder.model {
                header(ui)
                if ui.loading {
                    HStack { Spacer(); ProgressView(); Spacer() }
                } else if ui.noList {
                    Text(BkWords.noList).foregroundStyle(palette.onSurfaceVariant)
                }
                ForEach(ui.sections, id: \.name) { section in
                    Section(header: section.name.isEmpty ? nil : Text(section.name).font(Spectral.medium(16)).foregroundStyle(palette.tertiary)) {
                        ForEach(section.rows, id: \.key) { row in bookRow(row, model, enabled: ui.opening == nil) }
                    }
                }
            } else {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle(holder.ui?.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $filter, prompt: BkWords.filter)
        .onChange(of: filter) { _, f in holder.model?.setFilter(text: f) }
        .task {
            guard let core else { return }
            holder.start(core, packId: packId) { event in
                switch onEnum(of: event) {
                case let .open(o): openRoute(.book(id: o.bookId))
                case let .say(s): notice = s.text.words
                }
            }
        }
        .overlay(alignment: .bottom) { noticeView }
        .alert(openingTitle, isPresented: Binding(get: { holder.ui?.opening != nil }, set: { _ in })) {
            Button(BkWords.cancel, role: .cancel) { holder.model?.cancel() }
        } message: {
            if let o = holder.ui?.opening, o.total > 0 { Text(BkWords.progress(o.done, o.total)) }
        }
        .alert(bigTitle, isPresented: Binding(get: { holder.ui?.bigAsk != nil }, set: { if !$0 { holder.model?.answerBig(read: false, keep: false) } })) {
            Button(BkWords.keep) { holder.model?.answerBig(read: false, keep: true) }
            Button(BkWords.readNow) { holder.model?.answerBig(read: true, keep: false) }
            Button(BkWords.cancel, role: .cancel) { holder.model?.answerBig(read: false, keep: false) }
        } message: {
            Text(holder.ui?.bigAsk?.overCap == true ? BkWords.bigOverCap : BkWords.bigBody)
        }
        .alert(BkWords.keepCollection(holder.ui?.packBytes ?? 0), isPresented: $confirmPack) {
            Button(BkWords.keep) { holder.model?.keepWholeCollection() }
            Button(BkWords.cancel, role: .cancel) {}
        }
    }

    private var openingTitle: String { holder.ui?.opening.map { BkWords.opening($0.title) } ?? "" }
    private var bigTitle: String { holder.ui?.bigAsk.map { BkWords.bigTitle($0.bytes) } ?? "" }

    @ViewBuilder private func header(_ ui: RemoteCollectionUi) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text(ui.provider.isEmpty ? "From Kiwix" : "From Kiwix · \(ui.provider)").font(.callout).foregroundStyle(palette.onSurfaceVariant)
                if ui.total > 0 { Text(BkWords.kept(ui.kept, of: Int(ui.total))).font(.callout).foregroundStyle(palette.onSurfaceVariant) }
                if !ui.online { Text(BkWords.needsInternet).font(.caption).foregroundStyle(palette.onSurfaceVariant) }
                if ui.packHere {
                    HStack {
                        Text("On this phone").font(.callout.weight(.medium)).foregroundStyle(palette.primary)
                        Spacer()
                        Button(BkWords.openCollection) {
                            Task {
                                guard let core, let pack = try? await core.library.installedPackOf(packEntryId: packId) else { return }
                                if let id = try? await core.library.bookPackCollection(packId: pack) { openRoute(.collection(id: id)) }
                            }
                        }
                    }
                } else if ui.packQueued {
                    Text(BkWords.collectionQueued).font(.callout.weight(.medium)).foregroundStyle(palette.primary)
                } else {
                    Button(BkWords.keepCollection(ui.packBytes)) { confirmPack = true }
                        .buttonStyle(.bordered)
                        .accessibilityIdentifier("bk-keep-collection")
                }
            }
        }
    }

    private func bookRow(_ row: RemoteBookRow, _ model: RemoteCollectionViewModel, enabled: Bool) -> some View {
        let kept = row.mark.map { if case .onPhone = onEnum(of: $0) { true } else { false } } ?? false
        let saved = row.mark.map { if case .saved = onEnum(of: $0) { true } else { false } } ?? false
        return Button { model.open(row: row, thenKeep: false) } label: {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(row.title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface).multilineTextAlignment(.leading)
                    if !row.author.isEmpty { Text(row.author).font(.callout).foregroundStyle(palette.onSurfaceVariant) }
                    Text(meta(row, kept: kept, saved: saved)).font(.caption).foregroundStyle(kept ? palette.primary : palette.onSurfaceVariant)
                }
                Spacer()
                if kept {
                    Image(systemName: "checkmark.circle.fill").foregroundStyle(palette.primary).accessibilityLabel(BkWords.keptOnPhone)
                }
            }
        }
        .disabled(!enabled || (!row.readable && row.editionId == nil && row.mark == nil))
        .contextMenu {
            if row.editionId != nil { Button(BkWords.originalScan) { model.openOriginal(row: row) }.disabled(!row.readable) }
            if kept { Button(BkWords.keptOnPhone) {}.disabled(true) } else { Button(BkWords.keepBook) { model.keep(row: row) } }
        }
        .accessibilityIdentifier("bk-row-\(row.key)")
    }

    private func meta(_ row: RemoteBookRow, kept: Bool, saved: Bool) -> String {
        if row.edition != nil { return [BkWords.readingEdition, row.editionBytes > 0 ? QueueWords.bytes(row.editionBytes) : nil].compactMap { $0 }.joined(separator: " · ") }
        let size = QueueWords.bytes(row.bytes)
        if kept { return "\(size) · On this phone" }
        if saved { return "\(size) · Saved for now" }
        return BkWords.online(size)
    }

    @ViewBuilder private var noticeView: some View {
        if let notice {
            Text(notice).font(.callout).foregroundStyle(palette.inverseOnSurface).padding(12)
                .background(palette.inverseSurface, in: RoundedRectangle(cornerRadius: 8)).padding(16)
                .task(id: notice) { try? await Task.sleep(for: .seconds(4)); if self.notice == notice { self.notice = nil } }
        }
    }
}

/// Plan BK's words (Android's strings_bookshelf.xml).
enum BkWords {
    static let everyCollection = "Every collection"
    static let everyCollectionSub = "Open any book online. Keep what you want on this phone."
    static func keepCollection(_ bytes: Int64) -> String { "Keep the whole collection · \(QueueWords.bytes(bytes))" }
    static let collectionQueued = "Coming to this phone"
    static let keepBook = "Keep this book"
    static let keptOnPhone = "Kept on this phone"
    static func online(_ size: String) -> String { "\(size) · online" }
    static func opening(_ title: String) -> String { "Opening \(title)…" }
    static func progress(_ done: Int64, _ total: Int64) -> String { "\(QueueWords.bytes(done)) of \(QueueWords.bytes(total))" }
    static let cancel = "Cancel"
    static func bigTitle(_ bytes: Int64) -> String { "This book is \(QueueWords.bytes(bytes))" }
    static let bigBody = "Reading it online saves it for now; keep it to have it offline for good."
    static let bigOverCap = "It's bigger than Saved for now, so it goes when you close it unless you keep it."
    static let readNow = "Read now"
    static let keep = "Keep"
    static let filter = "Find in this collection"
    static let noList = "This collection's list isn't online. Keep the whole collection to read it."
    static let needsInternet = "Needs internet to open its books."
    static let readingEdition = "Reading edition"
    static let originalScan = "Original scan"
    static let openCollection = "Open the collection"
    static func kept(_ kept: Int32, of total: Int) -> String { "\(kept) of \(total) kept" }
    static let couldntOpen = "Couldn't open that book just now."
}
