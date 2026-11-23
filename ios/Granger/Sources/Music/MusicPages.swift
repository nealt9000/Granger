import GrangerCoreKit
import SwiftUI

/// Plays [tracks] from [index] (nil: the first playable one) as [source], missing songs left out (MusicViewModel.play).
@MainActor private func play(_ holder: MusicHolder, _ tracks: [Track], _ index: Int?, _ source: PlaySource, shuffle: Bool = false) {
    holder.model?.play(tracks: tracks, tapped: index.map { KotlinInt(int: Int32($0)) }, source: source, shuffle: shuffle)
}

/// One album (Android's AlbumScreen): cover and details, its artists (each opens their page), Play/Shuffle (Stop while
/// its queue plays, Play resuming it while paused), then its songs in disc and track order under "Disc n" headers when
/// it has more than one disc. The player's song is marked wherever it was played from. Opened from a Best match's media
/// card at a song ([marked], a track id), the page scrolls to it and tints it for a moment, as Android's cited row.
struct AlbumScreen: View {
    let albumKey: String
    var marked: String? = nil
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = MusicHolder()
    @State private var page: AlbumPage?
    @State private var dialog: MusicDialog?
    @State private var cited = CitedMark()

    var body: some View {
        ScrollViewReader { reader in
            ScrollView {
                if let page { content(page) }
            }
            .onChange(of: page?.tracks.contains { $0.id == marked } == true) { _, has in
                if has, let marked { cited.show(marked, reader) }
            }
        }
        .background(palette.background)
        .navigationTitle(page.map { MusicWords.shown($0.album.title) } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let album = page?.album {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu { AlbumMenuItems(album: album, holder: holder, dialog: $dialog) } label: { Image(systemName: "ellipsis.circle") }
                        .accessibilityLabel("More")
                }
            }
        }
        .task {
            holder.start(voice, collectionId: nil)
            guard let model = holder.model else { return }
            for await p in model.album(key: albumKey) { page = p }
        }
        .modifier(MusicDialogHost(holder: holder, dialog: $dialog))
        .accessibilityIdentifier("album-root")
    }

    @ViewBuilder private func content(_ page: AlbumPage) -> some View {
        let album = page.album
        let source = MusicSources.shared.album(a: album)
        let tracks = page.tracks
        let multiDisc = Set(tracks.map { $0.disc?.intValue ?? 1 }).count > 1
        LazyVStack(alignment: .leading, spacing: 0) {
            AdaptiveStack(spacing: 16) {
                AlbumCover(path: album.coverPath, title: MusicWords.shown(album.title)).frame(width: 140)
                VStack(alignment: .leading, spacing: 4) {
                    Text(MusicWords.shown(album.title)).font(Spectral.medium(22)).foregroundStyle(palette.onSurface)
                    ForEach(MusicWords.albumArtistNames(album), id: \.self) { artist in
                        Button(MusicWords.shown(artist)) { openRoute(.artist(name: artist)) }.foregroundStyle(palette.primary)
                    }
                    Text(([album.year].filter { !$0.isEmpty } + [MusicWords.songs(Int(album.trackCount)), formatTime(album.durationMs)]).joined(separator: " · "))
                        .font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                }
            }
            .padding(16)
            let now = holder.now
            PlayShuffleRow(
                enabled: tracks.contains { $0.status == .ok },
                onPlay: { if now?.pausedAlbum(album: album) == true { holder.model?.resume() } else { play(holder, tracks, nil, source) } },
                onShuffle: { play(holder, tracks, nil, source, shuffle: true) },
                onStop: now?.playingAlbum(album: album) == true ? { holder.model?.stop() } : nil
            )
            ForEach(Array(tracks.enumerated()), id: \.element.id) { i, t in
                let disc = t.disc?.intValue ?? 1
                if multiDisc && (i == 0 || (tracks[i - 1].disc?.intValue ?? 1) != disc) {
                    Text("Disc \(disc)").font(.subheadline.weight(.semibold)).foregroundStyle(palette.onSurfaceVariant)
                        .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 4)
                }
                SongRow(track: t, holder: holder, dialog: $dialog, onPlay: { play(holder, tracks, i, source) },
                        leading: t.trackNo.map { "\($0.intValue)" } ?? "", showAlbum: false,
                        playing: now?.trackId == t.id ? now?.playing : nil)
                    .background(cited.on(t.id) ? palette.primary.opacity(0.15) : .clear)
                    .id(t.id)
            }
        }
        .padding(.bottom, 24)
    }
}

/// Album sections in a grid (each under its heading), then Play/Shuffle and the songs: the artist and genre pages.
private struct AlbumsAndSongs: View {
    let sections: [(String, [AlbumSummary])]
    let songs: [Track]
    let holder: MusicHolder
    @Binding var dialog: MusicDialog?
    let onPlay: (Int?, Bool) -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                PlayShuffleRow(enabled: songs.contains { $0.status == .ok }, onPlay: { onPlay(nil, false) }, onShuffle: { onPlay(nil, true) })
                ForEach(sections, id: \.0) { title, albums in
                    heading(title)
                    LazyVGrid(columns: [GridItem(.adaptive(minimum: 150), spacing: 12, alignment: .top)], alignment: .leading, spacing: 16) {
                        ForEach(albums, id: \.key) { AlbumCell(album: $0, holder: holder, dialog: $dialog) }
                    }
                    .padding(.horizontal, 16)
                }
                if !songs.isEmpty { heading(L("Songs")) }
                ForEach(Array(songs.enumerated()), id: \.element.id) { i, t in
                    SongRow(track: t, holder: holder, dialog: $dialog, onPlay: { onPlay(i, false) })
                }
            }
            .padding(.bottom, 24)
        }
    }

    private func heading(_ title: String) -> some View {
        Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(palette.primary)
            .padding(.horizontal, 16).padding(.vertical, 8)
            .accessibilityAddTraits(.isHeader)
    }
}

/// One artist (Android's ArtistScreen): their albums by release type, then "Appears on", then their songs.
struct ArtistScreen: View {
    let name: String
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @State private var holder = MusicHolder()
    @State private var page: ArtistPage?
    @State private var dialog: MusicDialog?

    var body: some View {
        Group {
            if let page {
                let source = MusicSources.shared.artist(name: page.name)
                AlbumsAndSongs(
                    sections: page.sections.map { ($0.group.label.words, $0.albums) } + (page.appearsOn.isEmpty ? [] : [(L("Appears on"), page.appearsOn)]),
                    songs: page.songs, holder: holder, dialog: $dialog
                ) { i, shuffle in play(holder, page.songs, i, source, shuffle: shuffle) }
            }
        }
        .background(palette.background)
        .navigationTitle(MusicWords.shown(page?.name ?? name))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            holder.start(voice, collectionId: nil)
            guard let model = holder.model else { return }
            for await p in model.artist(name: name) { page = p }
        }
        .modifier(MusicDialogHost(holder: holder, dialog: $dialog))
    }
}

/// One genre (Android's GenreScreen): the albums with songs in it, then those songs.
struct GenreScreen: View {
    let name: String
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @State private var holder = MusicHolder()
    @State private var page: GenrePage?
    @State private var dialog: MusicDialog?

    var body: some View {
        Group {
            if let page {
                let source = MusicSources.shared.genre(name: page.name)
                AlbumsAndSongs(sections: page.albums.isEmpty ? [] : [(L("Albums"), page.albums)], songs: page.songs, holder: holder, dialog: $dialog) { i, shuffle in
                    play(holder, page.songs, i, source, shuffle: shuffle)
                }
            }
        }
        .background(palette.background)
        .navigationTitle(MusicWords.shown(name))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            holder.start(voice, collectionId: nil)
            guard let model = holder.model else { return }
            for await p in model.genre(name: name) { page = p }
        }
        .modifier(MusicDialogHost(holder: holder, dialog: $dialog))
    }
}

/// The songs in one folder, in album order (Android's FolderScreen).
struct MusicFolderScreen: View {
    let path: String
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @State private var holder = MusicHolder()
    @State private var page: FolderPage?
    @State private var dialog: MusicDialog?

    var body: some View {
        ScrollView {
            if let page {
                let source = MusicSources.shared.folder(f: page)
                LazyVStack(alignment: .leading, spacing: 0) {
                    PlayShuffleRow(enabled: page.songs.contains { $0.status == .ok },
                                   onPlay: { play(holder, page.songs, nil, source) }, onShuffle: { play(holder, page.songs, nil, source, shuffle: true) })
                    if page.songs.isEmpty { Text("No songs").foregroundStyle(palette.onSurfaceVariant).padding(24) }
                    ForEach(Array(page.songs.enumerated()), id: \.element.id) { i, t in
                        SongRow(track: t, holder: holder, dialog: $dialog, onPlay: { play(holder, page.songs, i, source) })
                    }
                }
                .padding(.bottom, 24)
            }
        }
        .background(palette.background)
        .navigationTitle(MusicCatalog.shared.folderName(path: path))
        .navigationBarTitleDisplayMode(.inline)
        .task {
            holder.start(voice, collectionId: nil)
            guard let model = holder.model else { return }
            for await p in model.folder(path: path) { page = p }
        }
        .modifier(MusicDialogHost(holder: holder, dialog: $dialog))
    }
}

/// One playlist, in order (Android's PlaylistScreen); missing songs stay in place, dimmed. A song's menu moves it up or
/// down past the row next to it, or removes it, by its entry ordinal (a removed download's entry shows no row). More
/// renames or deletes the playlist; the screen leaves once it's deleted.
struct PlaylistScreen: View {
    let playlistId: Int64
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @State private var holder = MusicHolder()
    @State private var page: PlaylistPage?
    @State private var seen = false
    @State private var dialog: MusicDialog?
    @State private var naming: PlaylistNaming?
    @State private var deleting: Playlist?
    @State private var name = ""

    var body: some View {
        ScrollView {
            if let page { content(page) }
        }
        .background(palette.background)
        .navigationTitle(page?.playlist.name ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            if let p = page?.playlist {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Rename") { name = p.name; naming = .rename(p) }
                        Button("Delete", role: .destructive) { deleting = p }
                    } label: { Image(systemName: "ellipsis.circle") }
                    .accessibilityLabel("More")
                }
            }
        }
        .task {
            holder.start(voice, collectionId: nil)
            guard let model = holder.model else { return }
            for await p in model.playlist(id: playlistId) {
                page = p
                if p != nil { seen = true } else if seen { dismiss() }
            }
        }
        .modifier(MusicDialogHost(holder: holder, dialog: $dialog))
        .modifier(PlaylistDialogs(holder: holder, naming: $naming, deleting: $deleting, name: $name))
        .accessibilityIdentifier("playlist-root")
    }

    @ViewBuilder private func content(_ page: PlaylistPage) -> some View {
        let tracks = page.tracks
        let items = page.items
        let source = MusicSources.shared.playlist(p: page.playlist)
        LazyVStack(alignment: .leading, spacing: 0) {
            PlayShuffleRow(enabled: tracks.contains { $0.status == .ok },
                           onPlay: { play(holder, tracks, nil, source) }, onShuffle: { play(holder, tracks, nil, source, shuffle: true) })
            if tracks.isEmpty {
                Text("No songs yet. Touch and hold a song or album to add it.").foregroundStyle(palette.onSurfaceVariant).padding(24)
            }
            // A song can be in a playlist twice, so rows go by entry ordinal.
            ForEach(Array(items.enumerated()), id: \.element.ordinal) { i, item in
                SongRow(track: item.track, holder: holder, dialog: $dialog, onPlay: { play(holder, tracks, i, source) }) {
                    if i > 0 {
                        Button("Move up") { holder.model?.moveInPlaylist(id: playlistId, from: item.ordinal, to: items[i - 1].ordinal) }
                    }
                    if i < items.count - 1 {
                        Button("Move down") { holder.model?.moveInPlaylist(id: playlistId, from: item.ordinal, to: items[i + 1].ordinal) }
                    }
                    Button("Remove from playlist", role: .destructive) { holder.model?.removeFromPlaylist(id: playlistId, ordinal: item.ordinal) }
                }
            }
        }
        .padding(.bottom, 24)
    }
}

/// A cited song's or chapter's mark (Android's `citedMark`): the page scrolls to the row once, when it's there, and tints
/// it for [seconds] (as a Research source card flashes). Opened again, it isn't scrolled back to.
@Observable
final class CitedMark {
    static let seconds = 2.0
    private var shown = false
    private(set) var marked: String?

    func on(_ id: String) -> Bool { marked == id }

    @MainActor func show(_ id: String, _ reader: ScrollViewProxy) {
        guard !shown else { return }
        shown = true
        marked = id
        withAnimation { reader.scrollTo(id, anchor: .center) }
        Task { @MainActor in
            try? await Task.sleep(for: .seconds(Self.seconds))
            marked = nil
        }
    }
}
