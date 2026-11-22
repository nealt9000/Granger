import GrangerCoreKit
import SwiftUI

/// The shared MusicViewModel as SwiftUI sees it (Plan I6 Task 6): one per music screen, as Android's music routes each
/// get theirs (a collection's tabs, or nil for all music and the detail pages).
@Observable
final class MusicHolder {
    private(set) var ui: MusicUi?
    private(set) var albumView: ShelfView = .list
    private(set) var now: MusicNow?
    private(set) var playlists: [Playlist] = []
    private(set) var targets: [Shelf] = []
    private var owned: OwnedViewModel<MusicViewModel>?
    private var watching: [Task<Void, Never>] = []
    var model: MusicViewModel? { owned?.model }

    @MainActor func start(_ voice: VoiceCenter?, collectionId: String?) {
        guard owned == nil, let music = voice?.music.music else { return }
        let o = music.music(collectionId: collectionId)
        owned = o
        let m = o.model
        watching = [
            Task { @MainActor [weak self] in for await u in m.ui { self?.ui = u } },
            Task { @MainActor [weak self] in for await v in m.albumView { self?.albumView = v } },
            Task { @MainActor [weak self] in for await n in m.musicNow { self?.now = n } },
            Task { @MainActor [weak self] in for await p in m.allPlaylists { self?.playlists = p } },
            Task { @MainActor [weak self] in for await t in m.moveTargets { self?.targets = t } },
        ]
    }

    deinit { watching.forEach { $0.cancel() }; owned?.close() }
}

/// What the long-press menus open: a playlist picker for some songs or an album, the move sheet for an album, or a
/// playlist's name (Android's MusicDialogs and PlaylistDialog).
enum MusicDialog: Identifiable {
    case addTracks([Track])
    case addAlbum(AlbumSummary)
    case moveAlbum(AlbumSummary)

    var id: String {
        switch self {
        case let .addTracks(t): "tracks:\(t.map(\.id).joined(separator: ","))"
        case let .addAlbum(a): "album:\(a.key)"
        case let .moveAlbum(a): "move:\(a.key)"
        }
    }
}

/// A name as shown: the library's "Unknown artist", "Unknown album" and "Unknown genre" placeholders are data, so only
/// their display is words (Android's shownName).
enum MusicWords {
    static func shown(_ name: String) -> String {
        switch name {
        case Albums.shared.UNKNOWN_ARTIST: L("Unknown artist")
        case Albums.shared.UNKNOWN_ALBUM: L("Unknown album")
        case MediaNames.shared.UNKNOWN_GENRE: L("Unknown genre")
        default: name
        }
    }

    static func songs(_ n: Int) -> String { L("\(n) songs") }
    static func albums(_ n: Int) -> String { L("\(n) albums") }

    static func trackArtist(_ t: Track) -> String { MusicCatalog.shared.trackArtistsOf(t: t).map(shown).joined(separator: ", ") }
    static func trackAlbum(_ t: Track) -> String { shown(Albums.shared.title(track: t)) }
    static func title(_ t: Track) -> String { MusicViewModelKt.trackTitle(t: t) }

    static func albumArtistNames(_ a: AlbumSummary) -> [String] {
        let names = a.albumArtist.components(separatedBy: "; ").map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        return names.isEmpty ? [Albums.shared.UNKNOWN_ARTIST] : names
    }

    static func artists(_ a: AlbumSummary) -> String { albumArtistNames(a).map(shown).joined(separator: ", ") }
}

/// A square cover from a file, or the themed tile (Android's MediaTile) with the title and a glyph when there's none.
struct AlbumCover: View {
    let path: String?
    let title: String
    var glyph = "music.note"
    @State private var image: UIImage?

    var body: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay {
                GeometryReader { geo in
                    if let image {
                        Image(uiImage: image).resizable().scaledToFill().frame(width: geo.size.width, height: geo.size.height).clipped()
                    } else {
                        MediaTile(title: title, glyph: glyph, width: geo.size.width)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 8))
            .accessibilityHidden(true)
            .task(id: path ?? "") {
                guard let path, FileManager.default.fileExists(atPath: path) else { image = nil; return }
                image = await Task.detached(priority: .utility) { CoverImages.thumbnail(path) }.value
            }
    }
}

/// The tile for an album or song with no art: Gunmetal, the glyph, the title (as a cover, the same in Field and Night).
struct MediaTile: View {
    let title: String
    let glyph: String
    let width: CGFloat

    var body: some View {
        ZStack {
            Color(red: 0x24 / 255, green: 0x29 / 255, blue: 0x2A / 255)
            VStack(spacing: width < 80 ? 2 : 8) {
                Image(systemName: glyph).font(.system(size: max(12, width * 0.22)))
                    .foregroundStyle(Color(red: 0xF0 / 255, green: 0xA3 / 255, blue: 0x5E / 255))
                if width >= 80 {
                    Text(title).font(.custom("Spectral-Medium", size: min(max(width * 0.08, 12), 22))).lineLimit(3).multilineTextAlignment(.center)
                        .foregroundStyle(Color(red: 0xEF / 255, green: 0xE4 / 255, blue: 0xD0 / 255)).padding(.horizontal, 8)
                }
            }
        }
    }
}

/// The playing mark (Android's PlayingBars): three bars rising and falling while heard, still while paused.
struct PlayingBars: View {
    let heard: Bool
    @Environment(\.palette) private var palette
    private static let still: [CGFloat] = [0.45, 0.85, 0.6]

    var body: some View {
        TimelineView(.animation(paused: !heard)) { context in
            let t = context.date.timeIntervalSinceReferenceDate
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(0..<3, id: \.self) { i in
                    let h = heard ? 0.25 + 0.75 * abs(sin(t * [3.7, 2.8, 3.3][i] + Double(i))) : Self.still[i]
                    Rectangle().fill(palette.primary).frame(width: 3, height: 16 * h)
                }
            }
            .frame(width: 16, height: 16, alignment: .bottom)
        }
        .accessibilityLabel(heard ? "Playing" : "Paused")
        .accessibilityIdentifier("playing-bars")
    }
}

/// A song row (Android's SongRow): a tap plays; a touch and hold opens its menu. A missing song is dimmed and can't play,
/// but still has its menu. [playing] marks the player's song: the bars in place of [leading], the title in the accent.
struct SongRow<Extra: View>: View {
    let track: Track
    let holder: MusicHolder
    @Binding var dialog: MusicDialog?
    let onPlay: () -> Void
    var leading: String? = nil
    var showAlbum = true
    var playing: Bool? = nil
    @ViewBuilder var extra: () -> Extra
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        let playable = track.status == .ok
        Button { if playable { onPlay() } } label: {
            HStack(spacing: 12) {
                if let playing {
                    PlayingBars(heard: playing).frame(width: leading != nil ? 28 : 16, alignment: .trailing)
                } else if let leading {
                    Text(leading).font(.callout.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant).frame(width: 28, alignment: .trailing)
                }
                VStack(alignment: .leading, spacing: 2) {
                    Text(MusicWords.title(track)).foregroundStyle(playing != nil ? palette.primary : palette.onSurface).lineLimit(1)
                    Text(playable ? subtitle : L("File not found")).font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                }
                Spacer(minLength: 8)
                Text(formatTime(track.durationMs)).font(.caption.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
            }
            .padding(.horizontal, 16).padding(.vertical, 8)
            .contentShape(Rectangle())
            .opacity(playable ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .contextMenu {
            Button("Play next") { holder.model?.playNext(tracks: [track]) }.disabled(!playable)
            Button("Add to queue") { holder.model?.addToQueue(tracks: [track]) }.disabled(!playable)
            Button("Add to playlist…") { dialog = .addTracks([track]) }
            extra()
            Button("Go to album") { openRoute(.album(key: track.albumKey)) }
            ForEach(Array(MusicCatalog.shared.trackArtistsOf(t: track).prefix(3)), id: \.self) { artist in
                Button("Go to \(MusicWords.shown(artist))") { openRoute(.artist(name: artist)) }
            }
        }
        .accessibilityIdentifier("song-\(MusicWords.title(track))")
    }

    private var subtitle: String {
        ([MusicWords.trackArtist(track)] + (showAlbum ? [MusicWords.trackAlbum(track)] : [])).joined(separator: " · ")
    }
}

extension SongRow where Extra == EmptyView {
    init(track: Track, holder: MusicHolder, dialog: Binding<MusicDialog?>, onPlay: @escaping () -> Void, leading: String? = nil,
         showAlbum: Bool = true, playing: Bool? = nil) {
        self.init(track: track, holder: holder, dialog: dialog, onPlay: onPlay, leading: leading, showAlbum: showAlbum, playing: playing) { EmptyView() }
    }
}

/// An album's touch-and-hold menu (Android's AlbumMenu): play next, queue, playlist, its artists, move.
struct AlbumMenuItems: View {
    let album: AlbumSummary
    let holder: MusicHolder
    @Binding var dialog: MusicDialog?
    var showArtists = true
    @Environment(\.openRoute) private var openRoute

    var body: some View {
        let playable = album.status == .ok
        Button("Play next") { holder.model?.playNextAlbum(album: album) }.disabled(!playable)
        Button("Add to queue") { holder.model?.queueAlbum(album: album) }.disabled(!playable)
        Button("Add to playlist…") { dialog = .addAlbum(album) }
        if showArtists {
            ForEach(Array(MusicWords.albumArtistNames(album).prefix(3)), id: \.self) { artist in
                Button("Go to \(MusicWords.shown(artist))") { openRoute(.artist(name: artist)) }
            }
        }
        Button("Move to collection…") { dialog = .moveAlbum(album) }
    }
}

/// An album in the list (Android's AlbumListRow): a small cover, the title, and "artist · year".
struct AlbumListRow: View {
    let album: AlbumSummary
    let holder: MusicHolder
    @Binding var dialog: MusicDialog?
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationLink(value: Route.album(key: album.key)) {
            HStack(spacing: 12) {
                AlbumCover(path: album.coverPath, title: MusicWords.shown(album.title)).frame(width: 48, height: 48)
                VStack(alignment: .leading, spacing: 2) {
                    Text(MusicWords.shown(album.title)).foregroundStyle(palette.onSurface).lineLimit(2)
                    Text(([MusicWords.artists(album)] + (album.year.isEmpty ? [] : [album.year])).joined(separator: " · "))
                        .font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .contentShape(Rectangle())
            .opacity(album.status == .ok ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .contextMenu { AlbumMenuItems(album: album, holder: holder, dialog: $dialog) }
        .accessibilityIdentifier("album-\(album.title)")
    }
}

/// An album in a grid (Android's AlbumCell): the square cover, title and artist.
struct AlbumCell: View {
    let album: AlbumSummary
    let holder: MusicHolder
    @Binding var dialog: MusicDialog?
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationLink(value: Route.album(key: album.key)) {
            VStack(alignment: .leading, spacing: 4) {
                AlbumCover(path: album.coverPath, title: MusicWords.shown(album.title))
                Text(MusicWords.shown(album.title)).font(.subheadline).foregroundStyle(palette.onSurface).lineLimit(1)
                Text(MusicWords.artists(album)).font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
            }
            .opacity(album.status == .ok ? 1 : 0.4)
        }
        .buttonStyle(.plain)
        .contextMenu { AlbumMenuItems(album: album, holder: holder, dialog: $dialog) }
        .accessibilityIdentifier("album-cell-\(album.title)")
    }
}

/// "Play" and "Shuffle" over a list of songs; with [onStop] (the album page while its queue plays) Play is Stop.
struct PlayShuffleRow: View {
    let enabled: Bool
    let onPlay: () -> Void
    let onShuffle: () -> Void
    var onStop: (() -> Void)? = nil

    var body: some View {
        HStack(spacing: 12) {
            if let onStop {
                Button(action: onStop) { Label("Stop", systemImage: "stop.fill") }.buttonStyle(.borderedProminent).accessibilityIdentifier("music-stop")
            } else {
                Button("Play", action: onPlay).buttonStyle(.borderedProminent).disabled(!enabled).accessibilityIdentifier("music-play")
            }
            Button("Shuffle", action: onShuffle).buttonStyle(.bordered).disabled(!enabled).accessibilityIdentifier("music-shuffle")
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
    }
}

/// Every music screen's sheets: Add to playlist (pick one or make one) and Move to collection (Android's MusicDialogHost).
struct MusicDialogHost: ViewModifier {
    let holder: MusicHolder
    @Binding var dialog: MusicDialog?
    @State private var name = ""
    @State private var newCollection: AlbumSummary?

    private var isMove: Bool { if case .moveAlbum = dialog { return true } else { return false } }

    private var playlistPick: Binding<MusicDialog?> {
        Binding(get: { isMove ? nil : dialog }, set: { if $0 == nil { dialog = nil } })
    }

    private var movePick: Binding<MusicDialog?> {
        Binding(get: { isMove ? dialog : nil }, set: { if $0 == nil { dialog = nil } })
    }

    func body(content: Content) -> some View {
        content
            .sheet(item: playlistPick) { d in
                AddToPlaylistSheet(playlists: holder.playlists) { id in
                    switch d {
                    case let .addAlbum(a): holder.model?.addAlbumToPlaylist(playlistId: id, album: a)
                    case let .addTracks(t): holder.model?.addToPlaylist(playlistId: id, tracks: t)
                    case .moveAlbum: break
                    }
                    dialog = nil
                } onCreate: { n in
                    switch d {
                    case let .addAlbum(a): holder.model?.createPlaylistFromAlbum(name: n, album: a)
                    case let .addTracks(t): holder.model?.createPlaylist(name: n, tracks: t)
                    case .moveAlbum: break
                    }
                    dialog = nil
                }
            }
            .sheet(item: movePick) { d in
                if case let .moveAlbum(album) = d {
                    MoveSheet(shelves: holder.targets, current: holder.model?.collectionOf(album: album), title: L("Move to collection…"),
                              allowNewMediaType: false) { pick in
                        dialog = nil
                        switch pick {
                        case let .existing(id): holder.model?.moveAlbum(album: album, toCollectionId: id)
                        case .newCollection: name = ""; newCollection = album
                        case .newMediaType: break
                        }
                    }
                }
            }
            .alert("New collection", isPresented: Binding(get: { newCollection != nil }, set: { if !$0 { newCollection = nil } })) {
                TextField("Collection name", text: $name)
                Button("Move") { if let a = newCollection { holder.model?.moveAlbumToNewCollection(album: a, name: name) } }
                Button("Cancel", role: .cancel) {}
            }
    }
}

/// Pick a playlist to add to, or make a new one (Android's AddToPlaylistSheet; New playlist asks for its name).
struct AddToPlaylistSheet: View {
    let playlists: [Playlist]
    let onPick: (Int64) -> Void
    let onCreate: (String) -> Void
    @State private var naming = false
    @State private var name = ""

    var body: some View {
        NavigationStack {
            List {
                Button { name = ""; naming = true } label: { Label("New playlist", systemImage: "plus") }.accessibilityIdentifier("add-new-playlist")
                ForEach(playlists, id: \.id) { p in
                    Button(p.name) { onPick(p.id) }.accessibilityIdentifier("add-to-\(p.name)")
                }
            }
            .navigationTitle("Add to playlist")
            .navigationBarTitleDisplayMode(.inline)
            .alert("New playlist", isPresented: $naming) {
                TextField("Playlist name", text: $name).accessibilityIdentifier("playlist-name")
                Button("Create") { if !name.trimmingCharacters(in: .whitespaces).isEmpty { onCreate(name) } }
                Button("Cancel", role: .cancel) {}
            }
        }
        .halfSheet([.medium, .large])
    }
}
