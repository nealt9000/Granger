import GrangerCoreKit
import SwiftUI

/// The shared PlayerViewModel as the music player sees it.
@Observable
final class PlayerHolder {
    private(set) var ui: PlayerUi = PlayerUi.companion.IDLE
    private(set) var queue: [Track] = []
    private(set) var saved: SavedModes = SavedModes.companion.UNKNOWN
    private(set) var playlists: [Playlist] = []
    private var owned: OwnedViewModel<PlayerViewModel>?
    private var watching: [Task<Void, Never>] = []
    var model: PlayerViewModel? { owned?.model }

    @MainActor func start(_ voice: VoiceCenter?) {
        guard owned == nil, let music = voice?.music.music else { return }
        let o = music.player()
        owned = o
        let m = o.model
        watching = [
            Task { @MainActor [weak self] in for await u in m.ui { self?.ui = u } },
            Task { @MainActor [weak self] in for await q in m.queue { self?.queue = q } },
            Task { @MainActor [weak self] in for await s in m.saved { self?.saved = s } },
            Task { @MainActor [weak self] in for await p in m.allPlaylists { self?.playlists = p } },
        ]
    }

    deinit { watching.forEach { $0.cancel() }; owned?.close() }

    /// What the music player shows (Android's MediaTab for music): the live music, the music under the voice with its own
    /// transport, or the saved queue, paused (any transport control then makes music the player's mode). Nil: nothing.
    var shown: (ui: PlayerUi, music: NowPlayingMusic, queue: [Track], live: Bool)? {
        switch onEnum(of: ui.now) {
        case let .music(m): return (ui, m, queue, true)
        case let .voice(v):
            if let m = v.music {
                return (PlayerUi(now: m, playing: v.musicPlaying, positionMs: v.musicPositionMs, durationMs: v.musicDurationMs, sleepRemainingMs: ui.sleepRemainingMs), m, queue, true)
            }
        default: break
        }
        if let s = saved.music {
            // SavedMusic.toUi(): paused at its saved track and place.
            let m = NowPlayingMusic(track: s.track, index: s.index, queueSize: Int32(s.queue.count), shuffle: s.shuffle, repeat: s.repeat, source: "")
            return (PlayerUi(now: m, playing: false, positionMs: s.positionMs, durationMs: s.track.durationMs, sleepRemainingMs: nil), m, s.queue, false)
        }
        return nil
    }

    /// "Now playing · 2 of 10", "Under the voice · 2 of 10", "Paused · 2 of 10" (Android's top label for music).
    var topLabel: String {
        switch onEnum(of: ui.now) {
        case let .music(m): return "Now playing · \(m.index + 1) of \(m.queueSize)"
        case let .voice(v): if let m = v.music { return "Under the voice · \(m.index + 1) of \(m.queueSize)" }
        default: break
        }
        if let s = saved.music { return "Paused · \(s.index + 1) of \(s.queue.count)" }
        return "Music"
    }
}

/// The music player (Android's MusicPlayer, Auxio-style): the cover, title, artist and album, the seek bar, shuffle,
/// previous, play/pause, next and repeat, the queue and Add to playlist, and the way to the Music shelf. On the saved
/// queue (music isn't the player's mode) it shows paused; a transport control makes music the mode and plays from there,
/// shuffle and repeat only show, and the queue is read-only.
struct MusicPlayerScreen: View {
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = PlayerHolder()
    @State private var showQueue = false
    @State private var adding = false
    @State private var seeking: Double?

    var body: some View {
        Group {
            if let shown = holder.shown { player(shown) } else if holder.saved.loaded { nothing } else { ProgressView() }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.background)
        .navigationTitle(holder.topLabel)
        .navigationBarTitleDisplayMode(.inline)
        .task { holder.start(voice) }
        .accessibilityIdentifier("music-player")
    }

    private var nothing: some View {
        VStack(spacing: 16) {
            Text("Nothing played yet").foregroundStyle(palette.onSurfaceVariant)
            Button("Music shelf") { openRoute(.music(collectionId: nil)) }
        }
    }

    private func control(_ live: Bool, _ action: @escaping (PlayerViewModel) -> Void) {
        guard let model = holder.model else { return }
        if live { action(model) } else { model.activate(mode: .music) { action($0) } }
    }

    @ViewBuilder private func player(_ shown: (ui: PlayerUi, music: NowPlayingMusic, queue: [Track], live: Bool)) -> some View {
        let track = shown.music.track
        let live = shown.live
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 16) {
                    AlbumCover(path: track.coverPath, title: track.title).frame(width: min(geo.size.width - 48, geo.size.height * 0.45, 360))
                    VStack(spacing: 2) {
                        Text(track.title).font(Spectral.medium(22)).multilineTextAlignment(.center).lineLimit(2)
                            .accessibilityIdentifier("player-title")
                        Text(track.artists.isEmpty ? track.albumArtists : track.artists).foregroundStyle(palette.primary).lineLimit(1)
                        Text(MusicWords.trackAlbum(track)).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                    }
                    seekBar(shown.ui, live: live)
                    HStack {
                        Button { if live { holder.model?.toggleShuffle() } } label: {
                            Image(systemName: "shuffle").foregroundStyle(shown.music.shuffle ? palette.primary : palette.onSurface)
                        }
                        .disabled(!live)
                        .accessibilityLabel(shown.music.shuffle ? "Shuffle on" : "Shuffle off")
                        Spacer()
                        Button { control(live) { $0.musicPrevious() } } label: { Image(systemName: "backward.end.fill").font(.title) }
                            .accessibilityLabel("Previous track")
                        Spacer()
                        Button {
                            if live { holder.model?.musicPlayPause() } else { holder.model?.activate(mode: .music) { _ in } }
                        } label: {
                            Image(systemName: shown.ui.playing ? "pause.fill" : "play.fill").font(.title).frame(width: 68, height: 68)
                                .background(palette.primary, in: Circle()).foregroundStyle(palette.onPrimary)
                        }
                        .accessibilityLabel(shown.ui.playing ? "Pause" : "Play")
                        .accessibilityIdentifier("player-play-pause")
                        Spacer()
                        Button { control(live) { $0.musicNext() } } label: { Image(systemName: "forward.end.fill").font(.title) }
                            .accessibilityLabel("Next track")
                            .accessibilityIdentifier("player-next")
                        Spacer()
                        Button { if live { holder.model?.cycleRepeat() } } label: {
                            Image(systemName: shown.music.repeat == 2 ? "repeat.1" : "repeat")
                                .foregroundStyle(shown.music.repeat != 0 ? palette.primary : palette.onSurface)
                        }
                        .disabled(!live)
                        .accessibilityLabel(["Repeat off", "Repeat all", "Repeat one"][Int(shown.music.repeat) % 3])
                    }
                    .font(.title3)
                    HStack(spacing: 16) {
                        Button { showQueue = true } label: { Label("Queue", systemImage: "list.bullet") }
                            .accessibilityIdentifier("player-queue")
                        if holder.model?.canAddToPlaylist == true {
                            Button { adding = true } label: { Label("Add to playlist", systemImage: "plus") }
                        }
                    }
                    Button("Music shelf") { openRoute(.music(collectionId: nil)) }.font(.subheadline)
                }
                .padding(.horizontal, 24).padding(.vertical, 8)
                .frame(maxWidth: .infinity)
            }
        }
        .sheet(isPresented: $showQueue) {
            QueueSheet(queue: shown.queue, current: Int(shown.music.index), editable: live, heard: live && shown.ui.playing,
                       onMove: { holder.model?.moveInQueue(from: Int32($0), to: Int32($1)) },
                       onRemove: { holder.model?.removeFromQueue(i: Int32($0)) },
                       onPlay: { holder.model?.playQueueItem(position: Int32($0)) })
        }
        .sheet(isPresented: $adding) {
            AddToPlaylistSheet(playlists: holder.playlists) { id in
                holder.model?.addNowPlayingToPlaylist(playlistId: id); adding = false
            } onCreate: { name in
                holder.model?.doNewPlaylistWithNowPlaying(name: name); adding = false
            }
        }
    }

    /// The seek bar with elapsed and total times: it follows the player until the finger is down, and seeks once, on
    /// release. Whole seconds, updated each second, so the media clock's lead of about 90 ms (Task 5) is under a tick.
    private func seekBar(_ ui: PlayerUi, live: Bool) -> some View {
        let duration = Double(max(ui.durationMs, 0))
        let shown = seeking ?? (duration > 0 ? min(1, Double(ui.positionMs) / duration) : 0)
        return VStack(spacing: 2) {
            Slider(value: Binding(get: { shown }, set: { seeking = $0 }), in: 0...1) { editing in
                if !editing, let f = seeking {
                    let at = Int64(f * duration)
                    control(live) { $0.musicSeekTo(positionMs: at) }
                    seeking = nil
                }
            }
            .disabled(duration <= 0)
            .accessibilityLabel("Seek in track")
            HStack {
                Text(formatTime(Int64(shown * duration))).accessibilityIdentifier("player-elapsed")
                Spacer()
                Text(formatTime(Int64(duration)))
            }
            .font(.caption.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
        }
    }
}

/// The music queue in play order (Android's QueueSheet): drag a song by its handle to move it, the cross removes it
/// (except the last), a tap plays it; the playing song is marked. Read-only on the saved queue.
struct QueueSheet: View {
    let queue: [Track]
    let current: Int
    let editable: Bool
    let heard: Bool
    let onMove: (Int, Int) -> Void
    let onRemove: (Int) -> Void
    let onPlay: (Int) -> Void
    @Environment(\.palette) private var palette

    /// A row: [key] stays with the song while it's dragged, even when the same song is queued twice.
    private struct Item: Identifiable { let id: Int; let track: Track }

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List {
                    ForEach(Array(queue.enumerated().map { Item(id: $0.offset, track: $0.element) }.enumerated()), id: \.element.id) { index, item in
                        row(item.track, index: index)
                            .id(index)
                            .accessibilityActions {
                                if editable && index > 0 { Button("Move up") { onMove(index, index - 1) } }
                                if editable && index < queue.count - 1 { Button("Move down") { onMove(index, index + 1) } }
                            }
                    }
                    .onMove(perform: editable ? { from, to in
                        guard let f = from.first else { return }
                        // List's destination counts the gap before the moved row; the queue wants the final position.
                        let target = to > f ? to - 1 : to
                        if target != f { onMove(f, target) }
                    } : nil)
                }
                .listStyle(.plain)
                .environment(\.editMode, .constant(editable ? .active : .inactive))
                .onAppear { proxy.scrollTo(current, anchor: .center) }
            }
            .navigationTitle("Queue")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
        .accessibilityIdentifier("queue-sheet")
    }

    private func row(_ track: Track, index: Int) -> some View {
        let playing = index == current
        return HStack(spacing: 12) {
            AlbumCover(path: track.coverPath, title: track.title).frame(width: 40, height: 40)
            if playing { PlayingBars(heard: heard) }
            VStack(alignment: .leading, spacing: 1) {
                Text(track.title).foregroundStyle(playing ? palette.primary : palette.onSurface).lineLimit(1)
                Text(track.artists.isEmpty ? track.albumArtists : track.artists).font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
            }
            Spacer(minLength: 4)
            if editable {
                Button { onRemove(index) } label: { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
                    .disabled(queue.count <= 1)
                    .accessibilityLabel("Remove \(track.title) from queue")
            }
        }
        .contentShape(Rectangle())
        .onTapGesture { if editable { onPlay(index) } }
        .accessibilityIdentifier("queue-row-\(index)")
        .accessibilityValue(track.title)
    }
}
