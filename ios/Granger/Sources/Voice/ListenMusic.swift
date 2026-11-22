import GrangerCoreKit
import SwiftUI

/// The shared ListenModel's shelves for Listen (Plan I6 Tasks 6 and 7): Music and Audiobooks, each only when it holds
/// something.
@Observable
final class ListenHolder {
    private(set) var ui: ListenUi?
    private var owned: OwnedViewModel<ListenModel>?
    private var watching: Task<Void, Never>?

    @MainActor func start(_ voice: VoiceCenter?) {
        guard owned == nil, let voice else { return }
        let o = voice.voice.listen()
        owned = o
        let m = o.model
        watching = Task { @MainActor [weak self] in for await u in m.ui { self?.ui = u } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// Listen's media (Android's Now playing card for music and audiobooks, and the audio shelves): what plays, or the song
/// or book that would resume, with play/pause, a tap opening its player; then the Music and Audiobooks shelves, "All
/// music" or "All audiobooks" and each collection. With no audio at all: "Nothing to listen to yet", the audio sources
/// (LibriVox, archive.org's audiobooks and music) and Find music on this phone. Audiobooks are passive: none on the
/// phone, no mention of them beyond the sources.
struct ListenMedia: View {
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var player = PlayerHolder()
    @State private var shelves = ListenHolder()
    @State private var closed: Set<String> = []

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            card
            if let ui = shelves.ui {
                if ui.empty { empty } else { ForEach(ui.shelves, id: \.id) { shelf($0) } }
            }
        }
        .padding(.top, 8)
        .task {
            player.start(voice)
            shelves.start(voice)
        }
    }

    // MARK: The card

    private enum Card {
        case music(NowPlayingMusic, live: Bool, playing: Bool)
        case book(NowPlayingBook, live: Bool, playing: Bool)
    }

    /// The live song or book (music under the voice counts), else what the saved state would resume.
    private var shownCard: Card? {
        if let shown = player.shown, shown.live { return .music(shown.music, live: true, playing: shown.ui.playing) }
        if let shown = player.shownBook, shown.live { return .book(shown.book, live: true, playing: shown.ui.playing) }
        switch player.saved.resumeMode() {
        case .audiobook: if let b = player.shownBook { return .book(b.book, live: false, playing: false) }
        case .music: if let m = player.shown { return .music(m.music, live: false, playing: false) }
        default: break
        }
        return nil
    }

    @ViewBuilder private var card: some View {
        if let card = shownCard {
            let (live, playing): (Bool, Bool) = switch card {
            case let .music(_, l, p), let .book(_, l, p): (l, p)
            }
            let label = !live ? L("Last played") : playing ? L("Now playing") : L("Paused")
            HStack(spacing: 12) {
                switch card {
                case let .music(m, _, _):
                    AlbumCover(path: m.track.coverPath, title: m.track.title).frame(width: 56, height: 56)
                    cardLines(label, m.track.title, m.track.artists.isEmpty ? m.track.albumArtists : m.track.artists)
                case let .book(b, _, _):
                    AudiobookCover(path: b.book.coverPath, title: b.book.title).frame(width: 56, height: 56)
                    cardLines(label, PlayerViewModelKt.chapterName(book: b).words, b.book.title)
                }
                Spacer(minLength: 4)
                Button { playPause(card) } label: {
                    Image(systemName: live && playing ? "pause.fill" : "play.fill").font(.title3).frame(width: 40, height: 40)
                }
                .accessibilityLabel(live && playing ? "Pause" : "Play")
                .accessibilityIdentifier(isBook(card) ? "listen-book-play-pause" : "listen-music-play-pause")
            }
            .padding(12)
            .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle())
            .onTapGesture { openRoute(isBook(card) ? .audiobookPlayer : .musicPlayer) }
            .accessibilityElement(children: .contain)
            .accessibilityAction(named: "Open player") { openRoute(isBook(card) ? .audiobookPlayer : .musicPlayer) }
            .accessibilityIdentifier(isBook(card) ? "listen-book-card" : "listen-music-card")
        }
    }

    private func isBook(_ card: Card) -> Bool { if case .book = card { true } else { false } }

    private func cardLines(_ label: String, _ title: String, _ subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption.weight(.medium)).foregroundStyle(palette.primary)
            Text(title).font(.subheadline.weight(.semibold)).lineLimit(1)
            Text(subtitle).font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
        }
    }

    private func playPause(_ card: Card) {
        guard let model = player.model else { return }
        switch card {
        case let .music(_, live, _): if live { model.musicPlayPause() } else { model.activate(mode: .music) { _ in } }
        case let .book(_, live, _): if live { model.playPause() } else { model.activate(mode: .audiobook) { _ in } }
        }
    }

    // MARK: The shelves

    private var empty: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Nothing to listen to yet").font(Spectral.medium(18)).foregroundStyle(palette.onSurface).padding(.top, 8)
            source("LibriVox", L("Public-domain audiobooks, read by volunteers"), .librivox, id: "listen-librivox")
            source(L("Archive.org audiobooks"), L("Spoken word from the Internet Archive"), .archiveAudio(kind: ArchiveKind.spokenWord.name), id: "listen-archive-audiobooks")
            source(L("Archive.org music"), L("Recordings from the Internet Archive"), .archiveAudio(kind: ArchiveKind.music.name), id: "listen-archive-music")
            Button("Find music on this phone") { openRoute(.mediaSettings) }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("listen-find-music")
        }
    }

    private func source(_ title: String, _ subtitle: String, _ route: Route, id: String) -> some View {
        NavigationLink(value: route) { row(title, subtitle) }.accessibilityIdentifier(id)
    }

    @ViewBuilder private func shelf(_ kind: KindRow) -> some View {
        let music = kind.mediaTypeId == MusicTypesKt.MUSIC_TYPE_ID
        let open = !closed.contains(kind.id)
        Button {
            if open { closed.insert(kind.id) } else { closed.remove(kind.id) }
        } label: {
            HStack {
                Text(kind.shownTitle).font(Spectral.medium(18)).foregroundStyle(palette.onSurface)
                Spacer()
                Image(systemName: open ? "chevron.up" : "chevron.down").foregroundStyle(palette.onSurfaceVariant)
            }
            .padding(.vertical, 8)
        }
        .accessibilityValue(open ? "Expanded" : "Collapsed")
        if open {
            if music {
                NavigationLink(value: Route.music(collectionId: nil)) {
                    row(L("All music"), MusicWords.albums(kind.allCollections.reduce(0) { $0 + $1.albums.count }))
                }
                .accessibilityIdentifier("listen-all-music")
            } else {
                let n = kind.allCollections.reduce(0) { $0 + $1.audiobooks.count }
                NavigationLink(value: Route.audiobooks(collectionId: nil)) {
                    row(L("All audiobooks"), L("\(n) audiobooks"))
                }
                .accessibilityIdentifier("listen-all-audiobooks")
            }
            ForEach(kind.collections, id: \.collection.id) { c in
                NavigationLink(value: c.isFolder ? Route.collection(id: c.collection.id)
                               : music ? Route.music(collectionId: c.collection.id) : Route.audiobooks(collectionId: c.collection.id)) {
                    row(c.collection.shownName().words, ShelfWords.count(c))
                }
            }
        }
    }

    private func row(_ title: String, _ subtitle: String) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(palette.onSurface)
            Text(subtitle).font(.callout).foregroundStyle(palette.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.leading, 16).padding(.vertical, 6)
        .contentShape(Rectangle())
    }
}

/// The music under the voice in Listen's Reading part (Android's MusicRow, Plan V2): its level, and the track with its
/// own play/pause and next.
struct MusicUnderVoiceRow: View {
    let music: NowPlayingMusic
    let playing: Bool
    let hub: IosPlayerHub
    @Environment(\.palette) private var palette
    @State private var level: Double?

    var body: some View {
        let shown = level ?? Double(hub.prefs.getFloat(key: PlayerSettingsKeys.shared.MIX_LEVEL, default: PlayerSettingsKeys.shared.DEFAULT_MIX))
        VStack(alignment: .leading, spacing: 6) {
            Text("Music under the voice")
            Slider(value: Binding(get: { shown }, set: { level = $0; hub.setMixLevel(level: Float($0)) }), in: 0...1)
                .accessibilityLabel("Music under the voice")
                .accessibilityIdentifier("listen-mix")
            HStack(spacing: 12) {
                Image(systemName: "music.note").foregroundStyle(palette.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 1) {
                    Text(music.track.title).lineLimit(1).accessibilityIdentifier("listen-music-under-title")
                    Text(music.track.artists.isEmpty ? music.track.albumArtists : music.track.artists)
                        .font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                }
                Spacer()
                Button { hub.playPauseMusic() } label: { Image(systemName: playing ? "pause.fill" : "play.fill") }
                    .accessibilityLabel(playing ? "Pause music" : "Play music")
                    .accessibilityIdentifier("listen-music-under-play-pause")
                Button { hub.nextMusic() } label: { Image(systemName: "forward.end.fill") }
                    .accessibilityLabel("Next track")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("listen-music-under")
    }
}
