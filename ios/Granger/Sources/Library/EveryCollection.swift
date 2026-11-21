import GrangerCoreKit
import SwiftUI

/// The Library's Every collection (Android's bookSourceShelves under the shelf, Plan BK builder's call 5): everything
/// the catalog's books hold, shelf by shelf, to open online and keep what you want. Shown while the iPhone can fetch.
struct EveryCollectionView: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var holder = SourceHolder()

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Eyebrow(text: BkWords.everyCollection).padding(.horizontal, 16)
            Text(BkWords.everyCollectionSub).font(.footnote).foregroundStyle(palette.onSurfaceVariant).padding(.horizontal, 16)
            if let ui = holder.ui, let model = holder.model {
                ForEach(ui.sections.filter { $0.title != SourceViewModel.companion.BOOK_RESULTS }, id: \.title) { section in
                    let presets = section.title == "literature" ? ui.presets : []
                    if !section.rows.isEmpty || !presets.isEmpty {
                        Text(section.heading.words).font(Spectral.medium(16)).foregroundStyle(palette.tertiary)
                            .padding(.horizontal, 16).padding(.top, 10)
                        VStack(spacing: 0) {
                            ForEach(presets, id: \.id) { p in
                                NavigationLink(value: Route.source(scope: CatalogScope.granger.name, domain: nil, family: nil, bookSet: nil)) {
                                    line(p.title, PresetText.shared.summary(row: p).words)
                                }
                                Divider().padding(.leading, 16)
                            }
                            ForEach(section.rows, id: \.id) { row in
                                BookSourceLine(row: row, model: model)
                                Divider().padding(.leading, 16)
                            }
                        }
                        .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
                        .padding(.horizontal, 16)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity).padding(16)
            }
        }
        .padding(.top, 18)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("every-collection")
        .task {
            guard let core else { return }
            holder.start { core.library.source(scope: .books, domain: nil, family: nil, bookSet: nil) }
        }
    }

    private func line(_ title: String, _ meta: String) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface).multilineTextAlignment(.leading)
                if !meta.isEmpty { Text(meta).font(.footnote).foregroundStyle(palette.onSurfaceVariant).multilineTextAlignment(.leading) }
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundStyle(palette.onSurfaceVariant)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }
}

/// One book source row (Android's BookSourceLine): a folded list or a set opens its own screen; a book pack not on the
/// phone whose list is online opens as its collection, Keep beside it; a book has Read now (online, not kept) and Keep,
/// then Open once it's here.
struct BookSourceLine: View {
    let row: ItemRow
    let model: SourceViewModel
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var reading = false
    @State private var failed = false

    var body: some View {
        Group {
            if let scope = row.opensScope {
                NavigationLink(value: Route.source(scope: scope.name, domain: nil, family: nil, bookSet: row.opensSet)) { label(chevron: true) }
            } else if let set = row.opensSet {
                NavigationLink(value: Route.source(scope: CatalogScope.bookSet.name, domain: nil, family: nil, bookSet: set)) { label(chevron: true) }
            } else {
                HStack(alignment: .center) {
                    if browsable {
                        Button { openRoute(.remoteCollection(packId: row.id)) } label: { label(chevron: true) }.buttonStyle(.plain)
                    } else {
                        Button { open() } label: { label(chevron: false) }.buttonStyle(.plain).disabled(!opens)
                    }
                    actions.padding(.trailing, 12)
                }
            }
        }
        .alert(BkWords.couldntOpen, isPresented: $failed) { Button("OK", role: .cancel) {} }
        .accessibilityIdentifier("book-source-\(row.id)")
    }

    private var browsable: Bool {
        guard case .notInstalled = onEnum(of: row.status) else { return false }
        return core?.library.canBrowseOnline(packEntryId: row.id) == true
    }

    private var opens: Bool { if case .installed = onEnum(of: row.status) { true } else { false } }

    private func open() {
        if let book = row.bookId { openRoute(.book(id: book)); return }
        guard let pack = row.packId, let core else { return }
        Task {
            if let id = try? await core.library.bookPackCollection(packId: pack) { openRoute(.collection(id: id)) } else { openRoute(.bookPack(pack: pack)) }
        }
    }

    @ViewBuilder private var actions: some View {
        switch onEnum(of: row.status) {
        case .notInstalled, .partial:
            HStack(spacing: 6) {
                if case .notInstalled = onEnum(of: row.status), core?.library.canReadNow(itemId: row.id) == true {
                    Button(reading ? "Opening…" : BkWords.readNow) { readNow() }.buttonStyle(.borderless).disabled(reading)
                        .accessibilityIdentifier("read-now-\(row.id)")
                }
                Button("Keep") { model.get(itemId: row.id) }.buttonStyle(.bordered).accessibilityIdentifier("get-\(row.id)")
            }
        case .queued, .downloading:
            Button("Cancel") { model.cancel(itemId: row.id) }.buttonStyle(.bordered)
        case .failed:
            Button("Retry") { model.retry(itemId: row.id) }.buttonStyle(.bordered)
        case .updateAvailable:
            Button("Update") { model.get(itemId: row.id) }.buttonStyle(.bordered)
        case .installed:
            Button("Open") { open() }.buttonStyle(.bordered).accessibilityIdentifier("open-\(row.id)")
        default:
            EmptyView()
        }
    }

    private func readNow() {
        guard let core else { return }
        reading = true
        Task {
            let id = try? await core.library.readWithoutKeeping(itemId: row.id)
            reading = false
            if let id { openRoute(.book(id: id)) } else { failed = true }
        }
    }

    private func label(chevron: Bool) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(row.title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface).multilineTextAlignment(.leading)
                let meta = (row.meta ?? row.details).words
                if !meta.isEmpty { Text(meta).font(.footnote).foregroundStyle(palette.onSurfaceVariant).multilineTextAlignment(.leading).lineLimit(3) }
                if case let .downloading(d) = onEnum(of: row.status) { ProgressView(value: Double(d.fraction)).tint(palette.primary) }
            }
            Spacer()
            if chevron { Image(systemName: "chevron.right").foregroundStyle(palette.onSurfaceVariant) }
        }
        .padding(.leading, 16).padding(.vertical, 10).padding(.trailing, chevron ? 12 : 0)
        .contentShape(Rectangle())
    }
}
