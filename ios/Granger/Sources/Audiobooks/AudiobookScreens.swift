import GrangerCoreKit
import SwiftUI

/// The shared AudiobooksViewModel as SwiftUI sees it (Plan I6 Task 7): one per shelf or book page, as Android's routes
/// each get theirs.
@Observable
final class AudiobooksHolder {
    private(set) var shelf: AudiobookShelfUi?
    private(set) var view: ShelfView = .list
    private(set) var page: AudiobookPage?
    private(set) var pageLoaded = false
    private var owned: OwnedViewModel<AudiobooksViewModel>?
    private var watching: [Task<Void, Never>] = []
    var model: AudiobooksViewModel? { owned?.model }

    /// The shelf for [collectionId] (nil: every audiobook).
    @MainActor func start(_ voice: VoiceCenter?, collectionId: String?) {
        guard owned == nil, let music = voice?.music.music else { return }
        let o = music.audiobooks(collectionId: collectionId)
        owned = o
        let m = o.model
        watching = [
            Task { @MainActor [weak self] in for await s in m.shelf { self?.shelf = s } },
            Task { @MainActor [weak self] in for await v in m.view { self?.view = v } },
        ]
    }

    /// One book's page.
    @MainActor func start(_ voice: VoiceCenter?, bookId: String) {
        guard owned == nil, let music = voice?.music.music else { return }
        let o = music.audiobooks(collectionId: nil)
        owned = o
        let m = o.model
        watching = [Task { @MainActor [weak self] in for await p in m.book(id: bookId) { self?.page = p; self?.pageLoaded = true } }]
    }

    deinit { watching.forEach { $0.cancel() }; owned?.close() }
}

/// Audiobooks' words (Android's strings_audiobooks.xml).
enum AudiobookWords {
    static func author(_ a: String) -> String { a.trimmingCharacters(in: .whitespaces).isEmpty ? L("Unknown author") : a }
    static func percent(_ f: Float) -> String { "\(min(99, max(1, Int((f * 100).rounded()))))%" }
    static let missing = L("Missing")
    static let finished = L("Finished")
}

/// A book's square cover, or the themed tile with its title and headphones (Android's AudiobookCover).
struct AudiobookCover: View {
    let path: String?
    let title: String
    var body: some View { AlbumCover(path: path, title: title, glyph: "headphones") }
}

/// The audiobook shelf (Android's AudiobookShelfScreen, Plan LV): Continue, the books part-way through; then every book,
/// a list under letters (or the cover grid) by Title, Author or Recent. A missing book is dimmed, a finished one ticked.
/// Empty: "No audiobooks yet", with LibriVox, archive.org's spoken word and Music & audiobooks settings.
struct AudiobookShelfScreen: View {
    let collectionId: String?
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = AudiobooksHolder()

    var body: some View {
        Group {
            if let ui = holder.shelf {
                if ui.sections.isEmpty { empty } else { shelf(ui) }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(palette.background)
        .navigationTitle(holder.shelf?.title.words ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let ui = holder.shelf, !ui.sections.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Picker("Sort", selection: Binding(get: { ui.sort }, set: { holder.model?.setSort(sort: $0) })) {
                            ForEach(AudiobookSort.allCases, id: \.self) { Text($0.label.words).tag($0) }
                        }
                        Button(holder.view == .list ? "Show as grid" : "Show as list") { holder.model?.toggleView() }
                    } label: { Image(systemName: "arrow.up.arrow.down") }
                    .accessibilityLabel("Sort")
                }
            }
        }
        .task { holder.start(voice, collectionId: collectionId) }
        .accessibilityIdentifier("audiobook-shelf")
    }

    private var empty: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("No audiobooks yet").foregroundStyle(palette.onSurfaceVariant)
            Button("LibriVox") { openRoute(.librivox) }.buttonStyle(.bordered)
            Button("Archive.org audiobooks") { openRoute(.archiveAudio(kind: ArchiveKind.spokenWord.name)) }.buttonStyle(.bordered)
            Button("Music & audiobooks settings") { openRoute(.mediaSettings) }.buttonStyle(.bordered)
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }

    private func shelf(_ ui: AudiobookShelfUi) -> some View {
        ScrollViewReader { proxy in
            ZStack(alignment: .trailing) {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        if !ui.continueRow.isEmpty { continueRow(ui.continueRow) }
                        if holder.view == .grid {
                            LazyVGrid(columns: [GridItem(.adaptive(minimum: 140), spacing: 12)], alignment: .leading, spacing: 20) {
                                ForEach(ui.sections.items, id: \.key) { item in if let b = item.value { cell(b, width: nil) } }
                            }
                            .padding(.horizontal, 16)
                        } else {
                            ForEach(ui.sections.sections, id: \.letter) { section in
                                if ui.sections.lettered { MusicLetter(letter: section.letter) }
                                ForEach(section.items, id: \.key) { item in if let b = item.value { row(b) } }
                            }
                        }
                    }
                    .padding(.trailing, ui.sections.lettered && holder.view == .list ? 20 : 0)
                    .padding(.bottom, 16)
                }
                if ui.sections.lettered && holder.view == .list {
                    LetterStrip(letters: ui.sections.letters) { proxy.scrollTo("letter-\($0)", anchor: .top) }
                }
            }
        }
    }

    private func continueRow(_ books: [ShelfBook]) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Continue").font(Spectral.medium(18)).foregroundStyle(palette.onSurface).padding(.horizontal, 16)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 12) {
                    ForEach(books, id: \.book.id) { cell($0, width: 120) }
                }
                .padding(.horizontal, 16)
            }
        }
        .padding(.vertical, 8)
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("audiobooks-continue")
    }

    private func row(_ item: ShelfBook) -> some View {
        NavigationLink(value: Route.audiobook(id: item.book.id)) {
            HStack(spacing: 12) {
                AudiobookCover(path: item.book.coverPath, title: item.book.title).frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.book.title).foregroundStyle(palette.onSurface).lineLimit(2)
                    Text(AudiobookWords.author(item.book.author)).font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                    if item.missing { Text(AudiobookWords.missing).font(.caption).foregroundStyle(palette.error) }
                }
                Spacer(minLength: 4)
                if !item.missing {
                    switch item.listening {
                    case .finished:
                        Label(AudiobookWords.finished, systemImage: "checkmark").font(.caption).foregroundStyle(palette.primary)
                    case .inProgress:
                        Text(AudiobookWords.percent(item.fraction)).font(.caption.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
                    default: EmptyView()
                    }
                }
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .contentShape(Rectangle())
            .opacity(item.missing ? 0.4 : 1)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("audiobook-\(item.book.title)")
    }

    private func cell(_ item: ShelfBook, width: CGFloat?) -> some View {
        NavigationLink(value: Route.audiobook(id: item.book.id)) {
            VStack(alignment: .leading, spacing: 4) {
                AudiobookCover(path: item.book.coverPath, title: item.book.title).opacity(item.missing ? 0.4 : 1)
                ProgressView(value: Double(item.fraction)).tint(palette.primary)
                Text(item.book.title).font(.subheadline).foregroundStyle(palette.onSurface).lineLimit(2).multilineTextAlignment(.leading)
                Text(AudiobookWords.author(item.book.author)).font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                if item.missing {
                    Text(AudiobookWords.missing).font(.caption2).foregroundStyle(palette.error)
                } else if item.listening == .finished {
                    Text(AudiobookWords.finished).font(.caption2).foregroundStyle(palette.primary)
                }
            }
            .frame(width: width)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// One audiobook (Android's AudiobookScreen): cover and details, progress, Play or Resume, Mark as finished; a download's
/// files and Remove; the description; the chapters (a tap plays from there) and the bookmarks (a tap plays from there;
/// edit the note; delete). A missing book shows it all but can't play.
struct AudiobookScreen: View {
    let bookId: String
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @State private var holder = AudiobooksHolder()
    @State private var editing: AudioBookmark?
    @State private var note = ""
    @State private var deleting: AudioBookmark?
    @State private var removing = false
    @State private var expanded = false

    var body: some View {
        Group {
            if let page = holder.page { content(page) } else if holder.pageLoaded { Color.clear } else { ProgressView() }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.background)
        .navigationTitle(holder.page?.book.title ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { holder.start(voice, bookId: bookId) }
        .alert("Note", isPresented: Binding(get: { editing != nil }, set: { if !$0 { editing = nil } })) {
            TextField("Note", text: $note)
            Button("Save") { if let m = editing { holder.model?.editNote(bookmark: m, note: note) }; editing = nil }
            Button("Cancel", role: .cancel) { editing = nil }
        }
        .alert("Delete bookmark?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } })) {
            Button("Delete", role: .destructive) { if let m = deleting { holder.model?.deleteBookmark(bookmark: m) }; deleting = nil }
            Button("Cancel", role: .cancel) { deleting = nil }
        } message: { Text(deleting?.title ?? "") }
        .alert("Remove \(holder.page?.book.title ?? "")?", isPresented: $removing) {
            Button("Remove", role: .destructive) { _ = holder.page?.onRemoveDownload?() }
            Button("Keep", role: .cancel) {}
        } message: { Text("This deletes its files. Your place and bookmarks are kept.") }
        .accessibilityIdentifier("audiobook-page")
    }

    private func content(_ page: AudiobookPage) -> some View {
        let book = page.book
        return List {
            Section {
                AdaptiveStack(spacing: 16) {
                    AudiobookCover(path: book.coverPath, title: book.title).frame(width: 120).opacity(page.missing ? 0.4 : 1)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(book.title).font(Spectral.medium(20)).foregroundStyle(palette.onSurface)
                        Text(AudiobookWords.author(book.author)).font(.subheadline)
                        if !book.narrator.isEmpty { Text("Read by \(book.narrator)").font(.caption).foregroundStyle(palette.onSurfaceVariant) }
                        Text(formatTime(page.progress.durationMs)).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                        if page.missing { Text("Missing: its files aren't on this phone").font(.caption.weight(.medium)).foregroundStyle(palette.error) }
                    }
                }
                VStack(alignment: .leading, spacing: 4) {
                    ProgressView(value: Double(page.fraction)).tint(palette.primary)
                    Text(progressLine(page)).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                        .accessibilityIdentifier("audiobook-progress")
                }
                AdaptiveStack(spacing: 12) {
                    Button(page.playLabel.words) { holder.model?.play(audiobookId: book.id) }
                        .buttonStyle(.borderedProminent).disabled(!page.playable)
                        .accessibilityIdentifier("audiobook-play")
                    let finished = page.listening == .finished
                    Button(finished ? "Mark as unfinished" : "Mark as finished") { holder.model?.setFinished(audiobookId: book.id, finished: !finished) }
                        .buttonStyle(.bordered).disabled(page.chapters.isEmpty)
                }
                .buttonStyle(.borderless)
                if let download = page.download, page.onRemoveDownload != nil {
                    HStack {
                        Text(downloadLine(download, page)).font(.callout).foregroundStyle(palette.tertiary)
                        Spacer()
                        Button("Remove") { removing = true }.buttonStyle(.borderless)
                    }
                }
                if !book.description_.isEmpty {
                    Text(book.description_).font(.callout).lineLimit(expanded ? nil : 4)
                        .onTapGesture { expanded.toggle() }
                }
            }
            Section("Chapters") {
                ForEach(page.chapters, id: \.ordinal) { chapter in
                    let current = chapter.ordinal == page.currentChapter?.int32Value
                    Button { holder.model?.playChapter(audiobookId: book.id, chapter: chapter) } label: {
                        HStack(spacing: 12) {
                            Text("\(chapter.ordinal + 1)").font(.callout.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
                            Text(chapterName(chapter.title, Int(chapter.ordinal))).lineLimit(2)
                                .fontWeight(current ? .semibold : .regular)
                                .foregroundStyle(current ? palette.primary : palette.onSurface)
                            Spacer()
                            Text(formatTime(chapter.durationMs)).font(.caption.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    .disabled(!page.playable)
                    .accessibilityIdentifier("audiobook-chapter-\(chapter.ordinal + 1)")
                }
            }
            Section("Bookmarks") {
                if page.bookmarks.isEmpty {
                    Text("No bookmarks yet. Add them from the player.").foregroundStyle(palette.onSurfaceVariant)
                }
                ForEach(page.bookmarks, id: \.id) { mark in
                    HStack {
                        Button { holder.model?.playBookmark(bookmark: mark) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(mark.title).foregroundStyle(palette.onSurface).lineLimit(2)
                                if !mark.note.isEmpty { Text(mark.note).font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(4) }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .disabled(!page.playable)
                        .accessibilityIdentifier("audiobook-bookmark")
                        Button { note = mark.note; editing = mark } label: { Image(systemName: "pencil") }
                            .buttonStyle(.borderless).accessibilityLabel("Edit note")
                        Button { deleting = mark } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless).accessibilityLabel("Delete bookmark \(mark.title)")
                    }
                }
            }
        }
        .scrollContentBackground(.hidden)
    }

    private func progressLine(_ page: AudiobookPage) -> String {
        switch page.listening {
        case .finished: return L("Finished")
        case .unplayed: return L("Not started")
        default:
            let left = max(0, page.progress.durationMs - page.progress.positionMs)
            return L("\(Int(page.fraction * 100))% · \(formatTime(left)) left")
        }
    }

    private func downloadLine(_ status: ItemStatus, _ page: AudiobookPage) -> String {
        switch onEnum(of: status) {
        case .queued, .downloading: return L("Downloading: \(page.filesDone) of \(page.filesTotal) chapters")
        case let .failed(f): return f.reason.map { L("Stopped: \($0)") } ?? L("Stopped")
        case let .partial(p): return L("\(p.done) of \(p.total) chapters on this phone")
        default: return L("\(page.filesTotal) chapters on this phone")
        }
    }
}
