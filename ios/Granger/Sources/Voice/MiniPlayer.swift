import GrangerCoreKit
import SwiftUI

/// The mini player above the tab bar (the spec's §5; Android's MiniPlayer above the content), one for everything (Plan
/// I6 Task 7): the voice (what it reads and its state, with the music under it on a third line), a song (its cover,
/// title and artist) or a book (its cover, the chapter and the book). Play/pause, and one more button: back a sentence
/// for the voice, next track for music, back the skip interval for a book. A tap opens Listen, at the music or
/// audiobook player. With nothing loaded it shows the last song or book from the saved state, paused, as Android's
/// restore() brings it back; a control then makes that the player's mode.
struct MiniPlayer: View {
    let voice: VoiceCenter
    let onOpen: (Route?) -> Void
    @Environment(\.palette) private var palette
    @State private var holder = PlayerHolder()

    private enum Item {
        case voice(VoiceNow, music: NowPlayingMusic?)
        case music(NowPlayingMusic, live: Bool)
        case book(NowPlayingBook, live: Bool)
    }

    /// What it shows: the one-item side (PlayerFocus), else the saved mode.
    private var item: Item? {
        switch onEnum(of: holder.mini.now) {
        case let .music(m): return .music(m, live: true)
        case let .book(b): return .book(b, live: true)
        case let .voice(v): if let now = voice.ui.now { return .voice(now, music: v.music) }
        case .idle: break
        }
        if let now = voice.ui.now { return .voice(now, music: nil) }
        switch voice.saved.lastMode {
        case .music:
            if let s = voice.saved.music {
                return .music(NowPlayingMusic(track: s.track, index: s.index, queueSize: Int32(s.queue.count), shuffle: s.shuffle, repeat: s.repeat, source: ""), live: false)
            }
        case .audiobook:
            if let s = voice.saved.book {
                return .book(NowPlayingBook(book: s.book, chapter: s.chapter, chapterCount: Int32(s.chapters.count), speed: s.speed,
                                            skipSilence: s.skipSilence, volumeBoostMb: s.volumeBoostMb), live: false)
            }
        default: break
        }
        return nil
    }

    var body: some View {
        Group {
            if let item { row(item) }
        }
        .task { holder.start(voice) }
    }

    @ViewBuilder private func row(_ item: Item) -> some View {
        let playing: Bool = switch item {
        case .voice: voice.ui.playing
        case let .music(_, live), let .book(_, live): live && holder.mini.playing
        }
        HStack(spacing: 12) {
            switch item {
            case let .voice(now, music):
                Image(systemName: "headphones").foregroundStyle(palette.primary)
                lines(now.title, voice.stateLine, third: music.map { m in "♪ \(m.track.title) · \(m.track.artists.isEmpty ? m.track.albumArtists : m.track.artists)" })
            case let .music(m, _):
                AlbumCover(path: m.track.coverPath, title: m.track.title).frame(width: 36, height: 36)
                lines(m.track.title, m.track.artists.isEmpty ? m.track.albumArtists : m.track.artists, third: nil)
            case let .book(b, _):
                AudiobookCover(path: b.book.coverPath, title: b.book.title).frame(width: 36, height: 36)
                lines(PlayerViewModelKt.chapterName(book: b).words, b.book.title, third: nil)
            }
            Spacer(minLength: 4)
            Button { playPause(item) } label: {
                Image(systemName: playing ? "pause.fill" : "play.fill").font(.title3).frame(width: 36, height: 36)
            }
            .accessibilityLabel(playing ? "Pause" : "Play")
            .accessibilityIdentifier("mini-play-pause")
            Button { secondary(item) } label: { Image(systemName: secondaryIcon(item)).font(.title3).frame(width: 36, height: 36) }
                .accessibilityLabel(secondaryLabel(item))
                .accessibilityIdentifier("mini-secondary")
        }
        .padding(.horizontal, 16)
        // The bottom accessory keeps its height whatever the text size, so the row's text stops growing at xLarge (as
        // the system's own accessories do) rather than spilling over the page above it; VoiceOver reads it whole.
        .dynamicTypeSize(...DynamicTypeSize.xLarge)
        .contentShape(Rectangle())
        .onTapGesture { open(item) }
        .accessibilityElement(children: .contain)
        .accessibilityAction(named: Text(isVoice(item) ? "Open Listen" : "Open player")) { open(item) }
        .accessibilityIdentifier("mini-player")
    }

    private func lines(_ title: String, _ subtitle: String, third: String?) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(title).font(.subheadline.weight(.semibold)).lineLimit(1).accessibilityIdentifier("mini-title")
            Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            if let third { Text(third).font(.caption2).foregroundStyle(.secondary).lineLimit(1).accessibilityIdentifier("mini-music") }
        }
    }

    private func isVoice(_ item: Item) -> Bool { if case .voice = item { true } else { false } }

    private func playPause(_ item: Item) {
        guard let model = holder.model else { return voice.voice.player.playPause() }
        switch item {
        case .voice: model.miniPlayPause()
        case let .music(_, live): if live { model.miniPlayPause() } else { model.activate(mode: .music) { _ in } }
        case let .book(_, live): if live { model.miniPlayPause() } else { model.activate(mode: .audiobook) { _ in } }
        }
    }

    private func secondary(_ item: Item) {
        guard let model = holder.model else { return }
        switch item {
        case .voice: voice.voice.player.previousSentence()
        case let .music(_, live): if live { model.secondary() } else { model.activate(mode: .music) { $0.next() } }
        case let .book(_, live): if live { model.secondary() } else { model.activate(mode: .audiobook) { $0.skipBack() } }
        }
    }

    private func secondaryIcon(_ item: Item) -> String {
        switch item {
        case .voice: "gobackward"
        case .music: "forward.end.fill"
        case .book: "gobackward.\(holder.model?.skipBackS() ?? 30)"
        }
    }

    private func secondaryLabel(_ item: Item) -> String {
        switch item {
        case .voice: return "Back a sentence"
        case .music: return "Next track"
        case .book:
            let s = holder.model?.skipBackS() ?? 30
            return s == 1 ? "Back 1 second" : "Back \(s) seconds"
        }
    }

    private func open(_ item: Item) {
        switch item {
        case .voice: onOpen(nil)
        case .music: onOpen(.musicPlayer)
        case .book: onOpen(.audiobookPlayer)
        }
    }
}

/// On a full player (music's, the audiobook's): the mini player is hidden while it's on screen, as Android hides it there.
struct HidesMiniPlayer: ViewModifier {
    @Environment(\.voice) private var voice
    @State private var counted = false

    func body(content: Content) -> some View {
        content
            .onAppear { if !counted { counted = true; voice?.fullPlayersShown += 1 } }
            .onDisappear { if counted { counted = false; voice?.fullPlayersShown = max(0, (voice?.fullPlayersShown ?? 1) - 1) } }
    }
}
