import GrangerCoreKit
import SwiftUI
import UniformTypeIdentifiers

/// Get more (Android's, Plan U3b Decision 2): where packs and books come from. On the iPhone in I4: Kiwix's knowledge
/// packs, Books by kind, archive.org and (Plan I5 Task 8) Models & voices: the Granger voices; LibriVox and audio come
/// with I6.
struct GetMoreScreen: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var importing = false
    @State private var importNote: String?

    var body: some View {
        List {
            Section(header: heading("Knowledge packs")) {
                link(.kiwix, "Kiwix", "Wikipedia, Stack Exchange, medicine, how-to and more, offline")
                Button { importing = true } label: {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Import a pack").font(Spectral.regular(20)).foregroundStyle(palette.onSurface)
                        Text("A .zim file from Files, copied onto this iPhone").font(.callout).foregroundStyle(palette.onSurfaceVariant)
                    }
                    .padding(.vertical, 4)
                }
                .accessibilityIdentifier("import-pack")
                if let importNote { Text(importNote).font(.callout).foregroundStyle(palette.tertiary) }
            }
            Section(header: heading("Books")) {
                link(.books, "Books by kind", "Literature, children's books, manuals and learning")
                NavigationLink(value: Route.archive) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("archive.org").font(Spectral.regular(20)).foregroundStyle(palette.onSurface)
                        Text("Public-domain books published before 1930").font(.callout).foregroundStyle(palette.onSurfaceVariant)
                    }
                    .padding(.vertical, 4)
                }
                .accessibilityIdentifier("getmore-archive")
            }
            Section(header: heading("Models & voices")) {
                NavigationLink(value: Route.voiceSettings) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Models and voices").font(Spectral.regular(20)).foregroundStyle(palette.onSurface)
                        Text("What Read aloud runs on").font(.callout).foregroundStyle(palette.onSurfaceVariant)
                    }
                    .padding(.vertical, 4)
                }
                .accessibilityIdentifier("getmore-voices")
            }
            Section {
                NavigationLink(value: Route.downloads) { Label("Downloads", systemImage: "arrow.down.circle") }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Get more")
        .navigationBarTitleDisplayMode(.inline)
        .fileImporter(isPresented: $importing, allowedContentTypes: [UTType(filenameExtension: "zim") ?? .data]) { result in
            guard case let .success(url) = result, let core else { return }
            importNote = "Copying \(url.lastPathComponent)…"
            Task { importNote = await PackImport.copy(url, core: core) }
        }
    }

    private func heading(_ s: String) -> some View { Text(s).font(Spectral.medium(16)).foregroundStyle(palette.tertiary) }

    private func link(_ scope: CatalogScope, _ title: String, _ sub: String) -> some View {
        NavigationLink(value: Route.source(scope: scope.name, domain: nil, family: nil, bookSet: nil)) {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(Spectral.regular(20)).foregroundStyle(palette.onSurface)
                Text(sub).font(.callout).foregroundStyle(palette.onSurfaceVariant)
            }
            .padding(.vertical, 4)
        }
        .accessibilityIdentifier("getmore-\(scope.name.lowercased())")
    }
}

/// The shared SourceViewModel as SwiftUI sees it.
@Observable
final class SourceHolder {
    private(set) var ui: SourceUi?
    private var owned: OwnedViewModel<SourceViewModel>?
    private var watching: Task<Void, Never>?
    var model: SourceViewModel? { owned?.model }

    @MainActor
    func start(_ make: () -> OwnedViewModel<SourceViewModel>) {
        guard owned == nil else { return }
        let o = make()
        owned = o
        watching = Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// One source's screen (Android's SourceScreen over the same SourceViewModel): Browse / On this phone / Updates, a
/// search, the presets, and the catalog's sections, each row with its size, plain words, flavours and Get / Cancel /
/// Retry / Open / Remove. A download over 10 GB asks first, offering a smaller flavour.
struct SourceScreen: View {
    let scope: CatalogScope
    var domain: String? = nil
    var family: String? = nil
    var bookSet: String? = nil

    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = SourceHolder()
    @State private var query = ""
    @State private var removing: ItemRow?
    @State private var removeBody = ""
    /// The catalog book being read without keeping, while it comes down.
    @State private var reading: String?
    @State private var readFailed = false

    var body: some View {
        List {
            if let ui = holder.ui, let model = holder.model {
                Section {
                    Picker("Show", selection: Binding(get: { ui.tab }, set: { model.setTab(value: $0) })) {
                        ForEach(SourceTab.allCases, id: \.self) { t in Text(ScreenText.strings[t.titleKey] ?? t.name).tag(t) }
                    }
                    .pickerStyle(.segmented)
                    if let v = ui.volumes.first { volumeLine(v, wifiOnly: ui.wifiOnly) }
                    if let problem = ui.catalogProblem { Text(problem.words).foregroundStyle(palette.error) }
                    if let notice = ui.notice {
                        HStack { Text(notice.words).foregroundStyle(palette.onSurface); Spacer(); Button("OK") { model.dismissNotice() } }
                    }
                }
                if ui.loading && ui.sections.isEmpty {
                    HStack { Spacer(); ProgressView(); Spacer() }
                }
                if !ui.presets.isEmpty && ui.tab == .browse {
                    Section(header: heading("Sets")) {
                        ForEach(ui.presets, id: \.id) { p in preset(p, model) }
                    }
                }
                ForEach(ui.sections, id: \.title) { section in
                    Section(header: heading(section.heading.words)) {
                        ForEach(section.rows, id: \.id) { row in itemRow(row, model) }
                    }
                }
            } else {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle(holder.ui?.screenTitle?.words ?? holder.ui?.familyTitle?.words ?? title)
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: "Search")
        .onChange(of: query) { _, q in holder.model?.setQuery(value: q) }
        .refreshable { holder.model?.refreshCatalog() }
        .task {
            guard let core else { return }
            holder.start { core.library.source(scope: scope, domain: domain, family: family, bookSet: bookSet) }
            holder.model?.refreshVolumes()
            if query.isEmpty, let seed = holder.model?.searchText.value as String?, !seed.isEmpty { query = seed }
        }
        .alert(sizeCheckTitle, isPresented: Binding(get: { holder.ui?.sizeCheck != nil }, set: { if !$0 { holder.model?.dismissSizeCheck() } })) {
            if let check = holder.ui?.sizeCheck {
                if check.sizeBytes < check.freeBytes { Button("Get it") { holder.model?.confirmGet(itemId: check.itemId) } }
                if let smaller = check.smaller { Button("Get \(smaller.label.words) · \(QueueWords.bytes(smaller.sizeBytes))") { holder.model?.chooseFlavour(itemId: smaller.id); holder.model?.confirmGet(itemId: smaller.id) } }
                Button("Not now", role: .cancel) { holder.model?.dismissSizeCheck() }
            }
        } message: {
            if let check = holder.ui?.sizeCheck {
                Text("You have \(QueueWords.bytes(check.freeBytes)) free on this iPhone." + (check.sizeBytes >= check.freeBytes ? " That isn't enough room for it." : ""))
            }
        }
        .alert(BkWords.couldntOpen, isPresented: $readFailed) { Button("OK", role: .cancel) {} }
        .alert("Remove \(removing?.title ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Remove", role: .destructive) { if let r = removing { holder.model?.remove(itemId: r.id) }; removing = nil }
            Button("Keep", role: .cancel) { removing = nil }
        } message: { Text(removeBody) }
    }

    private func canBrowse(_ row: ItemRow) -> Bool {
        guard case .notInstalled = onEnum(of: row.status) else { return false }
        return core?.library.canBrowseOnline(packEntryId: row.id) == true
    }

    /// Read now: the Classic fetched into Saved for now and opened, off the shelf until it's kept.
    private func readNow(_ row: ItemRow) {
        guard let core else { return }
        reading = row.id
        Task {
            let id = try? await core.library.readWithoutKeeping(itemId: row.id)
            reading = nil
            if let id { openRoute(.book(id: id)) } else { readFailed = true }
        }
    }

    private func volumeLine(_ v: VolumeRow, wifiOnly: Bool) -> some View {
        let free: String = QueueWords.bytes(v.freeBytes) + " free on this iPhone"
        let line: String = wifiOnly ? free + " · Downloads on Wi-Fi only" : free
        return Text(line).font(.callout).foregroundStyle(palette.onSurfaceVariant)
    }

    private var title: String {
        switch scope {
        case .books: "Get books"
        case .kiwix: "Kiwix"
        default: "Get more"
        }
    }

    private var sizeCheckTitle: String {
        guard let c = holder.ui?.sizeCheck else { return "" }
        return "\(c.title) is \(QueueWords.bytes(c.sizeBytes))"
    }

    private func heading(_ s: String) -> some View { Text(s).font(Spectral.medium(16)).foregroundStyle(palette.tertiary) }

    private func preset(_ p: PresetRow, _ model: SourceViewModel) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 3) {
                Text(p.title).font(Spectral.regular(19)).foregroundStyle(palette.onSurface)
                Text(PresetText.shared.summary(row: p).words).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                Text(PresetText.shared.status(row: p).words).font(.caption).foregroundStyle(palette.tertiary)
            }
            Spacer()
            if p.remainingCount > 0 { Button("Get") { model.getPreset(presetId: p.id) }.buttonStyle(.bordered) }
        }
    }

    @ViewBuilder private func itemRow(_ row: ItemRow, _ model: SourceViewModel) -> some View {
        if let opens = row.opensScope {
            NavigationLink(value: Route.source(scope: opens.name, domain: nil, family: nil, bookSet: row.opensSet)) { rowText(row) }
        } else if let fam = row.opensFamily {
            NavigationLink(value: Route.source(scope: scope.name, domain: nil, family: fam, bookSet: nil)) { rowText(row) }
        } else {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .top) {
                    // A book pack not on the phone whose list is online opens as its collection (Plan BK); Keep stays
                    // beside it as the whole pack's download.
                    if canBrowse(row) {
                        Button { openRoute(.remoteCollection(packId: row.id)) } label: {
                            HStack(alignment: .top) { rowText(row); Spacer(); Image(systemName: "chevron.right").foregroundStyle(palette.onSurfaceVariant) }
                        }
                        .buttonStyle(.plain)
                        .accessibilityIdentifier("browse-\(row.id)")
                    } else {
                        rowText(row)
                        Spacer()
                    }
                    action(row, model)
                }
                if row.flavours.count > 1 && !row.status.isBusy {
                    Picker("Version", selection: Binding(get: { row.flavours.first(where: { $0.chosen })?.id ?? row.id }, set: { model.chooseFlavour(itemId: $0) })) {
                        ForEach(row.flavours, id: \.id) { f in Text("\(f.label.words) · \(QueueWords.bytes(f.sizeBytes))").tag(f.id) }
                    }
                    .pickerStyle(.menu)
                    .font(.callout)
                }
                // A set's row (Plan WF): how much of it (its slices), then with or without pictures.
                if let setId = row.setId, !row.slices.isEmpty, !row.status.isBusy {
                    VStack(alignment: .leading, spacing: 6) {
                        ForEach(row.slices, id: \.zimName) { slice in sliceChoice(slice, setId: setId, model) }
                        if row.pictures.count > 1 {
                            Picker("Pictures", selection: Binding(get: { row.pictures.first(where: { $0.chosen })?.flavour ?? "" }, set: { model.choosePictures(setId: setId, flavour: $0) })) {
                                ForEach(row.pictures, id: \.flavour) { p in Text(p.label.words).tag(p.flavour) }
                            }
                            .pickerStyle(.segmented)
                            if let reason = row.pictures.first(where: { !$0.enabled })?.reason?.words {
                                Text(reason).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                            }
                        }
                    }
                }
                if let inside = row.insideOf?.words { Text(inside).font(.caption).foregroundStyle(palette.tertiary) }
                if case let .downloading(d) = onEnum(of: row.status) {
                    ProgressView(value: Double(d.fraction)).tint(palette.primary)
                }
            }
            .accessibilityElement(children: .contain)
        }
    }

    /// One slice of a set: chosen or not, its size, and whether it's on the phone or inside a bigger one already.
    private func sliceChoice(_ slice: SliceChoice, setId: String, _ model: SourceViewModel) -> some View {
        Button { model.chooseSlice(setId: setId, zimName: slice.zimName) } label: {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: slice.chosen ? "largecircle.fill.circle" : "circle").foregroundStyle(palette.primary)
                VStack(alignment: .leading, spacing: 2) {
                    Text("\(slice.label) · \(QueueWords.bytes(slice.sizeBytes))").foregroundStyle(palette.onSurface)
                    if slice.installed { Text("On this phone").font(.caption).foregroundStyle(palette.tertiary) }
                    if let inside = slice.inside?.words { Text(inside).font(.caption).foregroundStyle(palette.onSurfaceVariant) }
                    if let note = slice.note?.words { Text(note).font(.caption).foregroundStyle(palette.onSurfaceVariant) }
                }
                Spacer()
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(slice.chosen ? .isSelected : [])
    }

    private func rowText(_ row: ItemRow) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(row.title).font(Spectral.regular(19)).foregroundStyle(palette.onSurface)
            let details = row.details.words
            Text(([QueueWords.bytes(row.sizeBytes)] + (details.isEmpty ? [] : [details])).joined(separator: " · "))
                .font(.callout).foregroundStyle(palette.onSurfaceVariant)
            let description = row.description_.words
            if !description.isEmpty { Text(description).font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(3) }
            if let adds = row.adds?.words { Text(adds).font(.caption).foregroundStyle(palette.tertiary) }
            if let line = statusLine(row.status) { Text(line).font(.caption).foregroundStyle(palette.tertiary) }
        }
    }

    private func statusLine(_ s: ItemStatus) -> String? {
        switch onEnum(of: s) {
        case let .queued(q): q.lastError.map { "Retrying: \($0)" } ?? (q.wifiOnly ? "Waiting for Wi-Fi" : "Waiting")
        case let .downloading(d): "Downloading · \(Int(d.fraction * 100))%"
        case let .failed(f): f.reason ?? "Stopped"
        case .updateAvailable: "Update available"
        case let .partial(p): "\(p.done) of \(p.total) on this phone"
        case .needsMoreMemory: "Needs a phone with more memory"
        default: nil
        }
    }

    @ViewBuilder private func action(_ row: ItemRow, _ model: SourceViewModel) -> some View {
        switch onEnum(of: row.status) {
        case .notInstalled, .partial:
            HStack(spacing: 6) {
                // Read it now, saved for now, before choosing to keep it (Plan O2 Task 8).
                if case .notInstalled = onEnum(of: row.status), core?.library.canReadNow(itemId: row.id) == true {
                    Button(reading == row.id ? "Opening…" : BkWords.readNow) { readNow(row) }
                        .buttonStyle(.borderless).disabled(reading != nil).accessibilityIdentifier("read-now-\(row.id)")
                }
                Button("Keep") { model.get(itemId: row.id) }.buttonStyle(.bordered).accessibilityIdentifier("get-\(row.id)")
            }
        case .queued, .downloading:
            Button("Cancel") { model.cancel(itemId: row.id) }.buttonStyle(.bordered).accessibilityIdentifier("cancel-\(row.id)")
        case .failed:
            Button("Retry") { model.retry(itemId: row.id) }.buttonStyle(.bordered)
        case .updateAvailable:
            Button("Update") { model.get(itemId: row.id) }.buttonStyle(.bordered)
        case .installed:
            HStack(spacing: 4) {
                if let book = row.bookId {
                    Button("Open") { openRoute(.book(id: book)) }.buttonStyle(.bordered).accessibilityIdentifier("open-\(row.id)")
                } else if let pack = row.packId, let path = core?.library.archive(packId: pack)?.mainPath {
                    Button("Open") { openRoute(.packReader(pack: pack, path: path, fragment: nil)) }.buttonStyle(.bordered)
                }
                Menu {
                    Button("Remove", role: .destructive) {
                        Task {
                            let kind = try? await model.removeKind(itemId: row.id)
                            removeBody = (kind.flatMap { ScreenText.strings[$0.bodyKey] }).map { UiTextWords.fill($0, [row.title]) } ?? ""
                            removing = row
                        }
                    }
                } label: { Image(systemName: "ellipsis").frame(width: 32, height: 32) }
                .accessibilityLabel("More for \(row.title)")
            }
        default:
            EmptyView()
        }
    }
}

private extension ItemStatus {
    var isBusy: Bool {
        switch onEnum(of: self) {
        case .queued, .downloading: true
        default: false
        }
    }
}

/// Import a pack (Plan I4 builder's call 6): the picked .zim is opened to check it's a pack and not one already here,
/// then copied into Documents/Packs, where the library finds it. A file put in that folder from the Files app is found
/// the same way the next time Granger comes to the front.
enum PackImport {
    static func copy(_ url: URL, core: Core) async -> String {
        await Task.detached(priority: .userInitiated) { () -> String in
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard case let .success(archive) = KiwixArchive.open(url.path) else { return "\(url.lastPathComponent) isn't a pack Granger can read." }
            let uuid = archive.uuid
            let title = archive.metadata.title
            archive.close()
            let packs = (try? await core.library.packs.first(where: { _ in true })) ?? []
            if packs.contains(where: { $0.id.caseInsensitiveCompare(uuid) == .orderedSame && $0.status == .ok }) { return "\(title) is on this iPhone already." }
            let to = core.packsFolder.appendingPathComponent(url.lastPathComponent)
            let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
            if size > core.library.freeBytes() { return "Not enough room: \(title) needs \(QueueWords.bytes(size))." }
            do {
                if FileManager.default.fileExists(atPath: to.path) { try FileManager.default.removeItem(at: to) }
                try FileManager.default.copyItem(at: url, to: to)
            } catch {
                return "Couldn't copy \(url.lastPathComponent)."
            }
            try? await core.library.refreshPacks()
            return "\(title) is on this iPhone."
        }.value
    }
}
