import GrangerCoreKit
import SwiftUI

/// Music's tabs, in Android's order (MusicTab): Albums first.
enum MusicTab: String, CaseIterable, Identifiable {
    case albums = "Albums", artists = "Artists", songs = "Songs", genres = "Genres", playlists = "Playlists", folders = "Folders"
    var id: String { rawValue }
}

/// One Music collection's tabs, or "All music" (Android's MusicScreen over the shared MusicViewModel, Plan I6 Task 6).
/// Albums as a list or a cover grid, by Title, Artist or Year; Artists and Songs filed under letters when long; Genres;
/// Playlists (New playlist; a touch and hold renames or deletes); Folders. With no music at all: "No music yet", with
/// Music & audiobooks settings.
struct MusicScreen: View {
    let collectionId: String?
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = MusicHolder()
    @State private var tab: MusicTab = .albums
    @State private var dialog: MusicDialog?

    var body: some View {
        VStack(spacing: 0) {
            Picker("Tab", selection: $tab) {
                ForEach(MusicTab.allCases) { Text($0.rawValue).tag($0) }
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 12).padding(.vertical, 6)
            .accessibilityIdentifier("music-tabs")
            if let ui = holder.ui {
                if ui.songs.isEmpty && tab != .playlists {
                    empty
                } else {
                    switch tab {
                    case .albums: AlbumsTab(ui: ui, holder: holder, dialog: $dialog)
                    case .artists: ArtistsTab(ui: ui)
                    case .songs: SongsTab(ui: ui, holder: holder, dialog: $dialog)
                    case .genres: GenresTab(ui: ui)
                    case .playlists: PlaylistsTab(ui: ui, holder: holder)
                    case .folders: FoldersTab(ui: ui)
                    }
                }
            } else {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(palette.background)
        .navigationTitle(holder.ui?.title.words ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { holder.start(voice, collectionId: collectionId) }
        .modifier(MusicDialogHost(holder: holder, dialog: $dialog))
        .accessibilityIdentifier("music-root")
    }

    /// Android's EmptyMedia: the line, archive.org's music, and Music & audiobooks settings.
    private var empty: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("No music yet").font(.body).foregroundStyle(palette.onSurfaceVariant)
            // Android also links Archive.org music here; archive.org's audio comes to the iPhone with Task 7.
            Button("Music & audiobooks settings") { openRoute(.mediaSettings) }.buttonStyle(.bordered)
                .accessibilityIdentifier("music-media-settings")
            Spacer()
        }
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
    }
}

/// A line for a tab with nothing in it.
private struct EmptyLine: View {
    let text: String
    @Environment(\.palette) private var palette
    var body: some View { Text(text).foregroundStyle(palette.onSurfaceVariant).padding(24).frame(maxWidth: .infinity, alignment: .leading) }
}

/// A letter header in a long shelf.
struct MusicLetter: View {
    let letter: String
    @Environment(\.palette) private var palette
    var body: some View {
        Text(letter).font(Spectral.medium(18)).foregroundStyle(palette.primary)
            .padding(.horizontal, 16).padding(.top, 14).padding(.bottom, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityAddTraits(.isHeader)
            .id("letter-\(letter)")
    }
}

/// Songs (Plan LV): the list under letters for Title, Artist and Album, with Shuffle all and the sort. A tap plays the
/// list as shown from that song.
private struct SongsTab: View {
    let ui: MusicUi
    let holder: MusicHolder
    @Binding var dialog: MusicDialog?

    /// Each song's place in the list as shown.
    static func positions(_ songs: [Track]) -> [String: Int] {
        var out: [String: Int] = [:]
        for (i, t) in songs.enumerated() where out[t.id] == nil { out[t.id] = i }
        return out
    }

    var body: some View {
        let songs = ui.songs
        let source = MusicSources.shared.songs(title: ui.title.words.isEmpty ? "Songs" : ui.title.words)
        let index = Self.positions(songs)
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    HStack {
                        Button("Shuffle all") { holder.model?.play(tracks: songs, tapped: nil, source: source, shuffle: true) }
                            .buttonStyle(.bordered).disabled(songs.isEmpty)
                        Spacer()
                        Menu {
                            Picker("Sort", selection: Binding(get: { ui.sort }, set: { holder.model?.setSort(order: $0) })) {
                                ForEach(SongSort.allCases, id: \.self) { Text($0.label.words).tag($0) }
                            }
                        } label: { Label(ui.sort.label.words, systemImage: "arrow.up.arrow.down") }
                        .accessibilityIdentifier("songs-sort")
                    }
                    .padding(.horizontal, 16).padding(.vertical, 6)
                    if songs.isEmpty { EmptyLine(text: "No songs") }
                    ForEach(ui.songSections.sections, id: \.letter) { section in
                        if ui.songSections.lettered { MusicLetter(letter: section.letter) }
                        ForEach(section.items, id: \.key) { item in
                            if let t = item.value {
                                SongRow(track: t, holder: holder, dialog: $dialog) {
                                    holder.model?.play(tracks: songs, tapped: index[t.id].map { KotlinInt(int: Int32($0)) }, source: source, shuffle: false)
                                }
                            }
                        }
                    }
                }
                .padding(.bottom, 24)
                .padding(.trailing, ui.songSections.lettered ? 20 : 0)
            }
            .overlay(alignment: .trailing) {
                if ui.songSections.lettered { LetterStrip(letters: ui.songSections.letters) { proxy.scrollTo("letter-\($0)", anchor: .top) } }
            }
        }
    }
}

/// Albums (Plan LV): a list (the default) or the cover grid, by Title, Artist or Year.
private struct AlbumsTab: View {
    let ui: MusicUi
    let holder: MusicHolder
    @Binding var dialog: MusicDialog?

    var body: some View {
        if ui.albumSections.isEmpty {
            EmptyLine(text: "No albums")
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 8) {
                            Picker("Sort", selection: Binding(get: { ui.albumSort }, set: { holder.model?.setAlbumSort(order: $0) })) {
                                ForEach(AlbumSort.allCases, id: \.self) { Text($0.label.words).tag($0) }
                            }
                            .pickerStyle(.segmented)
                            .accessibilityIdentifier("albums-sort")
                            Button { holder.model?.toggleAlbumView() } label: {
                                Image(systemName: holder.albumView == .list ? "square.grid.2x2" : "list.bullet").frame(width: 36, height: 32)
                            }
                            .accessibilityLabel(holder.albumView == .list ? "Show as grid" : "Show as list")
                            .accessibilityIdentifier("albums-view")
                        }
                        .padding(.horizontal, 12).padding(.vertical, 6)
                        ForEach(ui.albumSections.sections, id: \.letter) { section in
                            if ui.albumSections.lettered { MusicLetter(letter: section.letter) }
                            if holder.albumView == .list {
                                ForEach(section.items, id: \.key) { item in
                                    if let a = item.value { AlbumListRow(album: a, holder: holder, dialog: $dialog) }
                                }
                            } else {
                                LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12, alignment: .top)], alignment: .leading, spacing: 16) {
                                    ForEach(section.items, id: \.key) { item in
                                        if let a = item.value { AlbumCell(album: a, holder: holder, dialog: $dialog) }
                                    }
                                }
                                .padding(.horizontal, 12)
                            }
                        }
                    }
                    .padding(.bottom, 24)
                    .padding(.trailing, ui.albumSections.lettered ? 20 : 0)
                }
                .overlay(alignment: .trailing) {
                    if ui.albumSections.lettered { LetterStrip(letters: ui.albumSections.letters) { proxy.scrollTo("letter-\($0)", anchor: .top) } }
                }
            }
        }
    }
}

private struct ArtistsTab: View {
    let ui: MusicUi
    @Environment(\.palette) private var palette

    var body: some View {
        if ui.artistSections.isEmpty {
            EmptyLine(text: "No artists")
        } else {
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 0) {
                        ForEach(ui.artistSections.sections, id: \.letter) { section in
                            if ui.artistSections.lettered { MusicLetter(letter: section.letter) }
                            ForEach(section.items, id: \.key) { item in
                                if let a = item.value {
                                    NavigationLink(value: Route.artist(name: a.name)) {
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(MusicWords.shown(a.name)).foregroundStyle(palette.onSurface)
                                            Text("\(MusicWords.albums(Int(a.albumCount))) · \(MusicWords.songs(Int(a.songCount)))")
                                                .font(.callout).foregroundStyle(palette.onSurfaceVariant)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading).padding(.horizontal, 16).padding(.vertical, 8)
                                        .contentShape(Rectangle())
                                    }
                                    .buttonStyle(.plain)
                                    .accessibilityIdentifier("artist-\(a.name)")
                                }
                            }
                        }
                    }
                    .padding(.bottom, 24)
                    .padding(.trailing, ui.artistSections.lettered ? 20 : 0)
                }
                .overlay(alignment: .trailing) {
                    if ui.artistSections.lettered { LetterStrip(letters: ui.artistSections.letters) { proxy.scrollTo("letter-\($0)", anchor: .top) } }
                }
            }
        }
    }
}

private struct GenresTab: View {
    let ui: MusicUi
    @Environment(\.palette) private var palette

    var body: some View {
        List {
            if ui.genres.isEmpty { Text("No genres").foregroundStyle(palette.onSurfaceVariant) }
            ForEach(ui.genres, id: \.name) { g in
                NavigationLink(value: Route.genre(name: g.name)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(MusicWords.shown(g.name))
                        Text(MusicWords.songs(Int(g.songCount))).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                    }
                }
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }
}

private struct FoldersTab: View {
    let ui: MusicUi
    @Environment(\.palette) private var palette

    var body: some View {
        List {
            if ui.folders.isEmpty { Text("No folders").foregroundStyle(palette.onSurfaceVariant) }
            ForEach(ui.folders, id: \.path) { f in
                NavigationLink(value: Route.musicFolder(path: f.path)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(f.name).lineLimit(2)
                        Text(MusicWords.songs(Int(f.songCount))).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                    }
                }
                .accessibilityIdentifier("folder-\(f.name)")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
    }
}

/// Playlists: New playlist, then each by name; a touch and hold renames or deletes it.
private struct PlaylistsTab: View {
    let ui: MusicUi
    let holder: MusicHolder
    @State private var naming: PlaylistNaming?
    @State private var deleting: Playlist?
    @State private var name = ""

    var body: some View {
        List {
            Button { name = ""; naming = .new } label: { Label("New playlist", systemImage: "plus") }
                .accessibilityIdentifier("new-playlist")
            ForEach(ui.playlists, id: \.id) { p in
                NavigationLink(value: Route.playlist(id: p.id)) { Text(p.name) }
                    .contextMenu {
                        Button("Rename") { name = p.name; naming = .rename(p) }
                        Button("Delete", role: .destructive) { deleting = p }
                    }
                    .accessibilityIdentifier("playlist-\(p.name)")
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .modifier(PlaylistDialogs(holder: holder, naming: $naming, deleting: $deleting, name: $name))
    }
}

enum PlaylistNaming { case new, rename(Playlist) }

/// New playlist, Rename playlist and Delete playlist?, in Android's words.
struct PlaylistDialogs: ViewModifier {
    let holder: MusicHolder
    @Binding var naming: PlaylistNaming?
    @Binding var deleting: Playlist?
    @Binding var name: String

    func body(content: Content) -> some View {
        content
            .alert(title, isPresented: Binding(get: { naming != nil }, set: { if !$0 { naming = nil } })) {
                TextField("Playlist name", text: $name).accessibilityIdentifier("playlist-name")
                switch naming {
                case .new: Button("Create") { holder.model?.createPlaylist(name: name, tracks: []) }
                case let .rename(p): Button("Rename") { holder.model?.renamePlaylist(id: p.id, name: name) }
                case nil: EmptyView()
                }
                Button("Cancel", role: .cancel) {}
            }
            .alert("Delete playlist?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), presenting: deleting) { p in
                Button("Delete", role: .destructive) { holder.model?.deletePlaylist(id: p.id) }
                Button("Cancel", role: .cancel) {}
            } message: { p in Text("\(p.name) will be deleted. The songs stay on the phone.") }
    }

    private var title: String {
        if case .rename = naming { return "Rename playlist" }
        return "New playlist"
    }
}
