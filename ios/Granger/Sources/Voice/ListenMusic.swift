import GrangerCoreKit
import SwiftUI

/// The shared ListenModel's shelves for Listen (Plan I6 Task 6): Music for now; Audiobooks join in Task 7.
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

/// Listen's music (Android's Now playing card for music and the Music shelf): what plays, or the queue that would resume,
/// with play/pause, a tap opening the music player; then the Music shelf, "All music" and each Music collection. With no
/// music yet, Find music on this phone.
struct ListenMusic: View {
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var player = PlayerHolder()
    @State private var shelves = ListenHolder()
    @State private var open = true

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            card
            if let music = shelves.ui?.shelves.first(where: { $0.mediaTypeId == MusicTypesKt.MUSIC_TYPE_ID }) {
                shelf(music)
            } else if shelves.ui != nil {
                Button("Find music on this phone") { openRoute(.mediaSettings) }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier("listen-find-music")
            }
        }
        .padding(.top, 8)
        .task {
            player.start(voice)
            shelves.start(voice)
        }
    }

    @ViewBuilder private var card: some View {
        if let shown = player.shown {
            let live = shown.live
            let label = !live ? "Last played" : shown.ui.playing ? "Now playing" : "Paused"
            HStack(spacing: 12) {
                AlbumCover(path: shown.music.track.coverPath, title: shown.music.track.title).frame(width: 56, height: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(label).font(.caption.weight(.medium)).foregroundStyle(palette.primary)
                    Text(shown.music.track.title).font(.subheadline.weight(.semibold)).lineLimit(1)
                    Text(shown.music.track.artists.isEmpty ? shown.music.track.albumArtists : shown.music.track.artists)
                        .font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                }
                Spacer(minLength: 4)
                Button {
                    if live { player.model?.musicPlayPause() } else { player.model?.activate(mode: .music) { _ in } }
                } label: {
                    Image(systemName: live && shown.ui.playing ? "pause.fill" : "play.fill").font(.title3).frame(width: 40, height: 40)
                }
                .accessibilityLabel(live && shown.ui.playing ? "Pause" : "Play")
                .accessibilityIdentifier("listen-music-play-pause")
            }
            .padding(12)
            .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
            .contentShape(Rectangle())
            .onTapGesture { openRoute(.musicPlayer) }
            .accessibilityElement(children: .contain)
            .accessibilityAction(named: "Open player") { openRoute(.musicPlayer) }
            .accessibilityIdentifier("listen-music-card")
        }
    }

    @ViewBuilder private func shelf(_ kind: KindRow) -> some View {
        Button {
            open.toggle()
        } label: {
            HStack {
                Text(kind.title.words).font(Spectral.medium(18)).foregroundStyle(palette.onSurface)
                Spacer()
                Image(systemName: open ? "chevron.up" : "chevron.down").foregroundStyle(palette.onSurfaceVariant)
            }
            .padding(.vertical, 8)
        }
        .accessibilityValue(open ? "Expanded" : "Collapsed")
        if open {
            NavigationLink(value: Route.music(collectionId: nil)) {
                row("All music", MusicWords.albums(kind.allCollections.reduce(0) { $0 + $1.albums.count }))
            }
            .accessibilityIdentifier("listen-all-music")
            ForEach(kind.collections, id: \.collection.id) { c in
                NavigationLink(value: c.isFolder ? Route.collection(id: c.collection.id) : Route.music(collectionId: c.collection.id)) {
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
