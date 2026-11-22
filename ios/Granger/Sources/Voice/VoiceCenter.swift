import AVFAudio
import GrangerCoreKit
import MediaPlayer
import SwiftUI

/// Read aloud for the whole app (Plan I5 Task 8), and since Plan I6 Task 5 the whole player: the core's voice
/// (`IosVoice`: the voices, the shared VoicePlayer) and the shared PlayerHub over the one AVAudioEngine graph
/// (`IosPlayerHub`: music, audiobooks, music under the voice), with their state mirrored for SwiftUI; the audio session
/// (playback; spoken audio for the voice and books), so they go on with the screen locked; Now Playing and the lock
/// screen's remote commands for all three; and interruptions (a call, headphones pulled out) pausing them, as Android's
/// media session and audio focus do.
@Observable @MainActor
final class VoiceCenter {
    let voice: IosVoice
    var hub: IosPlayerHub { voice.hub }
    /// Music's sources, scan and models (Plan I6 Task 6).
    let music: MusicCenter
    private(set) var ui: VoiceUi = VoiceUi.companion.IDLE
    private(set) var progress: VoiceProgress?
    private(set) var lastRead: LastRead?
    /// The player as a whole (music, audiobooks, the voice with music under it).
    private(set) var media: PlayerUi = PlayerUi.companion.IDLE
    /// A line for the user (no voice, one that wouldn't start, a song that wouldn't play), shown by whichever screen is up.
    var notice: String?
    private var tasks: [Task<Void, Never>] = []

    init(core: Core) {
        voice = IosVoice(library: core.library, natives: PiperNativesImpl(), audioSession: { on in
            let wanted = on.boolValue
            Task { @MainActor in VoiceCenter.audioSession(wanted, spoken: true) }
        })
        music = MusicCenter(library: core.library, voice: voice)
        let player = voice.player
        let hub = voice.hub
        hub.audioSession = { on in
            let wanted = on.boolValue
            Task { @MainActor in
                var spoken = false
                if case .book = onEnum(of: hub.ui.value.now) { spoken = true }
                VoiceCenter.audioSession(wanted, spoken: spoken)
            }
        }
        tasks.append(Task { @MainActor [weak self] in for await u in player.ui { self?.ui = u; self?.updateNowPlaying() } })
        tasks.append(Task { @MainActor [weak self] in for await p in player.progress { self?.progress = p; self?.updateNowPlaying() } })
        tasks.append(Task { @MainActor [weak self] in for await l in player.lastRead { self?.lastRead = l } })
        tasks.append(Task { @MainActor [weak self] in for await n in player.notices { self?.notice = n.words } })
        tasks.append(Task { @MainActor [weak self] in for await m in hub.ui { self?.media = m; self?.updateNowPlaying() } })
        tasks.append(Task { @MainActor [weak self] in for await n in hub.notices { self?.notice = PlayerViewModelKt.playerNotice(line: n).words } })
        remoteCommands()
        watchSession()
        #if DEBUG
        // UI tests and checks: `-debugVoice <voice folder on the Mac>` installs that unpacked catalog voice.
        if let from = UserDefaults.standard.string(forKey: "debugVoice"), FileManager.default.fileExists(atPath: from) {
            NSLog("debugVoice: %@", voice.debugInstallVoice(from: from) ? "installed" : "failed")
        }
        // `-debugPlay "<file>|tone:<seconds>"` plays those files as a queue (the music screens are Tasks 6 and 7).
        if let entries = DebugPlay.entries {
            let rows = entries.enumerated().compactMap { i, e in DebugPlay.file(e, index: i) }.map { url in
                IosPlayerHub.companion.fileTrack(path: url.path, title: url.deletingPathExtension().lastPathComponent, durationMs: 0)
            }
            hub.playTrackRows(rows: rows, startIndex: 0, source: PlaySource(key: "debug", title: "Debug"))
        }
        #endif
    }

    var reading: Bool { ui.now != nil }

    // MARK: The audio session

    private static func audioSession(_ on: Bool, spoken: Bool) {
        let session = AVAudioSession.sharedInstance()
        if on {
            try? session.setCategory(.playback, mode: spoken ? .spokenAudio : .default, options: [])
            try? session.setActive(true)
        }
        // Left active while paused, so Now Playing keeps what was playing; let go when nothing is loaded.
    }

    private func watchSession() {
        let center = NotificationCenter.default
        center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            let resume = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map { AVAudioSession.InterruptionOptions(rawValue: $0).contains(.shouldResume) } ?? false
            MainActor.assumeIsolated {
                guard let self else { return }
                switch type {
                case .began: if self.anythingPlaying { self.resumeAfterInterruption = true; self.hub.pause() }
                case .ended:
                    if resume, self.resumeAfterInterruption { self.hub.play() }
                    self.resumeAfterInterruption = false
                @unknown default: break
                }
            }
        }
        // Headphones out: everything pauses rather than play to the room (Android's becoming-noisy).
        center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { if self?.anythingPlaying == true { self?.hub.pause() } }
        }
    }

    private var resumeAfterInterruption = false

    /// The voice reads, or music or a book plays (music under the voice counts).
    var anythingPlaying: Bool {
        if ui.playing || media.playing { return true }
        if case .voice(let v) = onEnum(of: media.now) { return v.musicPlaying }
        return false
    }

    /// What Now Playing and the remote commands are about.
    private enum Showing { case none, voice, music(NowPlayingMusic), book(NowPlayingBook) }

    private var showing: Showing {
        switch onEnum(of: media.now) {
        case .music(let m): return .music(m)
        case .book(let b): return .book(b)
        case .voice: return .voice
        case .idle: return ui.now != nil ? .voice : .none
        }
    }

    // MARK: Now Playing and the remote commands

    private func remoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        let hub = voice.hub
        let player = voice.player
        c.playCommand.addTarget { _ in hub.play(); return .success }
        c.pauseCommand.addTarget { _ in hub.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { _ in hub.playPause(); return .success }
        // The voice's next and previous are a sentence (Android's skip buttons for the voice); music's a song; a book's a chapter.
        c.nextTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if case .voice = self?.showing { player.nextSentence() } else { hub.next() } }
            return .success
        }
        c.previousTrackCommand.addTarget { [weak self] _ in
            MainActor.assumeIsolated { if case .voice = self?.showing { player.previousSentence() } else { hub.previous() } }
            return .success
        }
        c.stopCommand.addTarget { _ in hub.stop(); return .success }
        // A book's ± the skip interval (Media settings; 30 s by default).
        c.skipForwardCommand.addTarget { _ in hub.skipForward(); return .success }
        c.skipBackwardCommand.addTarget { _ in hub.skipBack(); return .success }
        c.changePlaybackPositionCommand.addTarget { event in
            guard let e = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            hub.seekTo(positionMs: Int64(e.positionTime * 1000))
            return .success
        }
        c.seekForwardCommand.isEnabled = false
        c.seekBackwardCommand.isEnabled = false
        updateCommands()
    }

    private func updateCommands() {
        let c = MPRemoteCommandCenter.shared()
        var book = false, seekable = false
        switch showing {
        case .book: book = true; seekable = true
        case .music: seekable = true
        default: break
        }
        c.skipForwardCommand.isEnabled = book
        c.skipBackwardCommand.isEnabled = book
        c.skipForwardCommand.preferredIntervals = [NSNumber(value: hub.prefs.getInt(key: PlayerSettingsKeys.shared.SKIP_FORWARD_S, default: 30))]
        c.skipBackwardCommand.preferredIntervals = [NSNumber(value: hub.prefs.getInt(key: PlayerSettingsKeys.shared.SKIP_BACK_S, default: 30))]
        c.changePlaybackPositionCommand.isEnabled = seekable
    }

    private var artworkPath: String?
    private var artwork: MPMediaItemArtwork?

    private func artwork(_ path: String?) -> MPMediaItemArtwork? {
        if path == artworkPath { return artwork }
        artworkPath = path
        artwork = path.flatMap { UIImage(contentsOfFile: $0) }.map { image in MPMediaItemArtwork(boundsSize: image.size) { _ in image } }
        return artwork
    }

    /// The lock screen and Control Center: the voice as "Reading · <title>"; a song with its artist, album and cover; a
    /// book's chapter with the book, its author, its cover and the chapter marks (Android's notification).
    private func updateNowPlaying() {
        let center = MPNowPlayingInfoCenter.default()
        updateCommands()
        var info: [String: Any] = [MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue]
        let playing: Bool
        switch showing {
        case .none:
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            return
        case .voice:
            guard let now = ui.now else { return }
            info[MPMediaItemPropertyTitle] = "Reading · \(now.title)"
            info[MPMediaItemPropertyAlbumTitle] = now.title
            if let section = now.section, !section.isEmpty { info[MPMediaItemPropertyArtist] = section }
            playing = ui.playing
            info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? 1.0 : 0.0
        case .music(let m):
            let t = m.track
            info[MPMediaItemPropertyTitle] = t.title
            info[MPMediaItemPropertyArtist] = t.artists.replacingOccurrences(of: "; ", with: ", ")
            info[MPMediaItemPropertyAlbumTitle] = t.album
            info[MPNowPlayingInfoPropertyPlaybackQueueIndex] = Int(m.index)
            info[MPNowPlayingInfoPropertyPlaybackQueueCount] = Int(m.queueSize)
            if let art = artwork(t.coverPath) { info[MPMediaItemPropertyArtwork] = art }
            playing = media.playing
            info[MPMediaItemPropertyPlaybackDuration] = Double(media.durationMs) / 1000
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(media.positionMs) / 1000
            info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? 1.0 : 0.0
        case .book(let b):
            info[MPMediaItemPropertyTitle] = PlayerViewModelKt.chapterName(book: b).words
            info[MPMediaItemPropertyArtist] = b.book.author
            info[MPMediaItemPropertyAlbumTitle] = b.book.title
            info[MPNowPlayingInfoPropertyChapterNumber] = Int(b.chapter.ordinal)
            info[MPNowPlayingInfoPropertyChapterCount] = Int(b.chapterCount)
            if let art = artwork(b.book.coverPath) { info[MPMediaItemPropertyArtwork] = art }
            playing = media.playing
            info[MPMediaItemPropertyPlaybackDuration] = Double(media.durationMs) / 1000
            info[MPNowPlayingInfoPropertyElapsedPlaybackTime] = Double(media.positionMs) / 1000
            info[MPNowPlayingInfoPropertyPlaybackRate] = playing ? Double(b.speed) : 0.0
            info[MPNowPlayingInfoPropertyDefaultPlaybackRate] = Double(b.speed)
        }
        center.nowPlayingInfo = info
        center.playbackState = playing ? .playing : .paused
    }

    // MARK: What screens ask

    /// The mini player's state line, in Android's words: "Reading · Chapter 3", "Paused · Chapter 3", "Finished reading".
    var stateLine: String {
        guard let now = ui.now else { return "" }
        let head = now.ended ? "Finished reading" : (ui.playing ? "Reading" : "Paused")
        if let s = now.section, !s.isEmpty { return "\(head) · \(s)" }
        return head
    }
}

private struct VoiceKey: EnvironmentKey {
    static let defaultValue: VoiceCenter? = nil
}

extension EnvironmentValues {
    var voice: VoiceCenter? {
        get { self[VoiceKey.self] }
        set { self[VoiceKey.self] = newValue }
    }
}
