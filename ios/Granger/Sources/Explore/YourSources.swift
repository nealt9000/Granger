import GrangerCoreKit
import QuickLook
import SwiftUI

/// Your sources (Android's Browse from Explore, Plan U3c Decision 1 / I4 Task 7): Wikipedia online, then the packs by
/// knowledge area, then map files; each opens its main page, its files (when it holds PDFs or EPUBs), or goes. With
/// Local only on, Wikipedia online is greyed: it can't open. Packs are on this phone and always open.
struct YourSourcesScreen: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(AppSettings.self) private var settings
    @Environment(\.openRoute) private var openRoute
    @State private var groups: [SourceGroup] = []
    @State private var bookPacks: [Pack] = []
    @State private var loaded = false
    @State private var removing: Pack?

    var body: some View {
        List {
            Section(header: heading("Online")) {
                HStack {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Wikipedia").font(Spectral.regular(19))
                        Text(settings.canFetch ? "Read online · what you read is saved for now" : "Local only is on: it opens again when you turn it off")
                            .font(.callout).foregroundStyle(palette.onSurfaceVariant)
                    }
                    Spacer()
                }
                .foregroundStyle(settings.canFetch ? palette.onSurface : palette.outline)
                .opacity(settings.canFetch ? 1 : 0.55)
                .accessibilityIdentifier("source-online")
            }
            if !bookPacks.isEmpty {
                Section(header: heading("Books")) {
                    ForEach(bookPacks, id: \.id) { p in
                        NavigationLink(value: Route.bookPack(pack: p.id)) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(p.title).font(Spectral.regular(19)).foregroundStyle(palette.onSurface)
                                Text(QueueWords.bytes(p.sizeBytes)).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                            }
                        }
                        .accessibilityIdentifier("source-book-pack")
                    }
                }
            }
            ForEach(groups, id: \.id) { g in
                Section(header: heading(g.title)) {
                    ForEach(g.rows, id: \.pack.id) { row in packRow(row) }
                }
            }
            if loaded && groups.isEmpty {
                Text("No packs on this iPhone yet. Get more adds them; a .zim put in Granger's folder in the Files app shows here too.")
                    .foregroundStyle(palette.onSurfaceVariant)
                NavigationLink(value: Route.getMore) { Text("Get more").foregroundStyle(palette.primary) }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Your sources")
        .navigationBarTitleDisplayMode(.inline)
        .task { await load() }
        .alert("Remove \(removing?.title ?? "")?", isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } })) {
            Button("Remove", role: .destructive) {
                if let p = removing, let core { Task { _ = try? await core.library.removePack(packId: p.id); await load() } }
                removing = nil
            }
            Button("Keep", role: .cancel) { removing = nil }
        } message: { Text("This deletes the pack from this iPhone. Your places and bookmarks in it are kept.") }
    }

    private func load() async {
        guard let core else { return }
        groups = await Task.detached { core.library.yourSources() }.value
        bookPacks = await Task.detached { core.library.bookPacks() }.value
        loaded = true
    }

    private func heading(_ s: String) -> some View { Text(s).font(Spectral.medium(16)).foregroundStyle(palette.tertiary) }

    private func packRow(_ row: SourceRow) -> some View {
        let p = row.pack
        let ok = p.status == .ok
        return HStack {
            Button {
                guard ok, let core else { return }
                Task {
                    let main = await Task.detached { core.library.archive(packId: p.id)?.mainPath }.value
                    if let main { openRoute(.packReader(pack: p.id, path: main, fragment: nil)) }
                }
            } label: {
                VStack(alignment: .leading, spacing: 3) {
                    Text(p.title).font(Spectral.regular(19)).foregroundStyle(ok ? palette.onSurface : palette.outline)
                    Text(line(p)).font(.callout).foregroundStyle(ok ? palette.onSurfaceVariant : palette.error)
                }
            }
            .buttonStyle(.plain)
            Spacer()
            Menu {
                if row.files > 0 { NavigationLink(value: Route.packFiles(pack: p.id)) { Label("Files in this pack", systemImage: "doc.on.doc") } }
                Button("Remove", role: .destructive) { removing = p }
            } label: { Image(systemName: "ellipsis").frame(width: 44, height: 44) }
            .accessibilityLabel("More for \(p.title)")
        }
        .accessibilityIdentifier("source-pack")
    }

    private func line(_ p: Pack) -> String {
        switch p.status {
        case .missing: return "Missing: the file is gone"
        case .corrupt: return "Damaged: it can't be read"
        default: return [QueueWords.bytes(p.sizeBytes), String(p.date.prefix(7))].filter { !$0.isEmpty }.joined(separator: " · ")
        }
    }
}

/// Files in this pack (Android's PackFilesScreen, Plan KB2 Task 4.6): the PDFs and EPUBs a website pack holds, A–Z;
/// one opens in Quick Look (Granger's own EPUB reader comes with the Library, I5).
struct PackFilesScreen: View {
    let pack: String
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var files: [PackFile]?
    @State private var preview: URL?

    var body: some View {
        List {
            if let files {
                if files.isEmpty { Text("No PDFs or EPUBs in this pack.").foregroundStyle(palette.onSurfaceVariant) }
                ForEach(files, id: \.path) { f in
                    Button { open(f) } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(f.title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface)
                            Text("\(f.mimeType == "application/pdf" ? "PDF" : "EPUB") · \(QueueWords.bytes(f.sizeBytes))").font(.callout).foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                }
            } else {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Files in this pack")
        .navigationBarTitleDisplayMode(.inline)
        .quickLookPreview($preview)
        .task {
            guard let core else { return }
            files = await Task.detached { core.library.packFiles(packId: pack) ?? [] }.value
        }
    }

    /// The file out of the pack into Caches, named by its title, then shown.
    private func open(_ f: PackFile) {
        guard let core else { return }
        Task {
            let url: URL? = await Task.detached {
                guard let item = core.library.archive(packId: pack)?.read(path: f.path) else { return nil }
                let ext = f.mimeType == "application/pdf" ? "pdf" : "epub"
                let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("pack-files", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let to = dir.appendingPathComponent("\(f.title.replacingOccurrences(of: "/", with: "-")).\(ext)")
                let data = GrangerCoreEntry.shared.bytes(of: item.bytes)
                return (try? data.write(to: to)) != nil ? to : nil
            }.value
            preview = url
        }
    }
}

/// A book pack as a collection (Plan I4 Task 8): its books in the pack's own order, by section; one opens in Quick Look
/// (Granger's own readers come with the Library, I5).
struct BookPackScreen: View {
    let pack: String
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var books: [PackBookEntry]?
    @State private var title = ""
    @State private var preview: URL?

    var body: some View {
        List {
            if let books {
                if books.isEmpty { Text("This pack's list of books can't be read.").foregroundStyle(palette.onSurfaceVariant) }
                let sections = Swift.Dictionary(grouping: books, by: { $0.group ?? "" })
                let order = books.reduce(into: [String]()) { acc, b in let g = b.group ?? ""; if !acc.contains(g) { acc.append(g) } }
                ForEach(order, id: \.self) { g in
                    Section(header: g.isEmpty ? nil : Text(g).font(Spectral.medium(16)).foregroundStyle(palette.tertiary)) {
                        ForEach(sections[g] ?? [], id: \.path) { b in
                            Button { open(b) } label: {
                                VStack(alignment: .leading, spacing: 3) {
                                    Text(b.title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface)
                                    Text([b.author, b.format == .pdf ? "PDF" : "EPUB", QueueWords.bytes(b.sizeBytes)].filter { !$0.isEmpty }.joined(separator: " · "))
                                        .font(.callout).foregroundStyle(palette.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            } else {
                HStack { Spacer(); ProgressView(); Spacer() }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .quickLookPreview($preview)
        .task {
            guard let core else { return }
            title = await Task.detached { core.library.packTitle(packId: pack) }.value
            books = await Task.detached { core.library.packBooks(packId: pack) ?? [] }.value
        }
    }

    private func open(_ b: PackBookEntry) {
        guard let core else { return }
        Task {
            let url: URL? = await Task.detached {
                guard let item = core.library.archive(packId: pack)?.read(path: b.path) else { return nil }
                let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("pack-files", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let to = dir.appendingPathComponent("\(b.title.replacingOccurrences(of: "/", with: "-")).\(b.format == .pdf ? "pdf" : "epub")")
                return (try? GrangerCoreEntry.shared.bytes(of: item.bytes).write(to: to)) != nil ? to : nil
            }.value
            preview = url
        }
    }
}
