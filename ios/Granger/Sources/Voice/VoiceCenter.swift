import AVFAudio
import GrangerCoreKit
import MediaPlayer
import SwiftUI

/// Read aloud for the whole app (Plan I5 Task 8): the core's voice (`IosVoice`: the voices, the shared VoicePlayer over
/// AVAudioEngine) with its state mirrored for SwiftUI, the audio session (playback, spoken audio; on while the voice
/// reads, so it goes on with the screen locked: call 4), Now Playing and the lock screen's remote commands, and
/// interruptions (a call, headphones pulled out) pausing it, as Android's media session and audio focus do.
@Observable @MainActor
final class VoiceCenter {
    let voice: IosVoice
    private(set) var ui: VoiceUi = VoiceUi.companion.IDLE
    private(set) var progress: VoiceProgress?
    private(set) var lastRead: LastRead?
    /// A line for the user (no voice, one that wouldn't start), shown by whichever screen is up.
    var notice: String?
    private var tasks: [Task<Void, Never>] = []

    init(core: Core) {
        voice = IosVoice(library: core.library, natives: PiperNativesImpl(), audioSession: { on in
            let wanted = on.boolValue
            Task { @MainActor in VoiceCenter.audioSession(wanted) }
        })
        let player = voice.player
        tasks.append(Task { @MainActor [weak self] in for await u in player.ui { self?.ui = u; self?.updateNowPlaying() } })
        tasks.append(Task { @MainActor [weak self] in for await p in player.progress { self?.progress = p; self?.updateNowPlaying() } })
        tasks.append(Task { @MainActor [weak self] in for await l in player.lastRead { self?.lastRead = l } })
        tasks.append(Task { @MainActor [weak self] in for await n in player.notices { self?.notice = n.words } })
        remoteCommands()
        watchSession()
        #if DEBUG
        // UI tests and checks: `-debugVoice <voice folder on the Mac>` installs that unpacked catalog voice.
        if let from = UserDefaults.standard.string(forKey: "debugVoice"), FileManager.default.fileExists(atPath: from) {
            NSLog("debugVoice: %@", voice.debugInstallVoice(from: from) ? "installed" : "failed")
        }
        #endif
    }

    var reading: Bool { ui.now != nil }

    // MARK: The audio session

    private static func audioSession(_ on: Bool) {
        let session = AVAudioSession.sharedInstance()
        if on {
            try? session.setCategory(.playback, mode: .spokenAudio, options: [])
            try? session.setActive(true)
        }
        // Left active while paused, so Now Playing keeps the voice; let go when nothing is loaded.
    }

    private func watchSession() {
        let center = NotificationCenter.default
        center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt, let type = AVAudioSession.InterruptionType(rawValue: raw) else { return }
            let resume = (note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt).map { AVAudioSession.InterruptionOptions(rawValue: $0).contains(.shouldResume) } ?? false
            MainActor.assumeIsolated {
                switch type {
                case .began: if self?.ui.playing == true { self?.resumeAfterInterruption = true; self?.voice.player.pause() }
                case .ended:
                    if resume, self?.resumeAfterInterruption == true { self?.voice.player.play() }
                    self?.resumeAfterInterruption = false
                @unknown default: break
                }
            }
        }
        // Headphones out: the voice pauses rather than read aloud to the room (Android's becoming-noisy).
        center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt,
                  AVAudioSession.RouteChangeReason(rawValue: raw) == .oldDeviceUnavailable else { return }
            MainActor.assumeIsolated { if self?.ui.playing == true { self?.voice.player.pause() } }
        }
    }

    private var resumeAfterInterruption = false

    // MARK: Now Playing and the remote commands

    private func remoteCommands() {
        let c = MPRemoteCommandCenter.shared()
        let player = voice.player
        c.playCommand.addTarget { _ in player.play(); return .success }
        c.pauseCommand.addTarget { _ in player.pause(); return .success }
        c.togglePlayPauseCommand.addTarget { _ in player.playPause(); return .success }
        // The voice's next and previous are a sentence (Android's skip buttons for the voice).
        c.nextTrackCommand.addTarget { _ in player.nextSentence(); return .success }
        c.previousTrackCommand.addTarget { _ in player.previousSentence(); return .success }
        c.stopCommand.addTarget { _ in player.stop(); return .success }
        for command in [c.skipForwardCommand, c.skipBackwardCommand, c.seekForwardCommand, c.seekBackwardCommand, c.changePlaybackPositionCommand] {
            command.isEnabled = false
        }
    }

    /// The lock screen and Control Center: "Reading · <title>", the section, playing or paused (Android's notification).
    private func updateNowPlaying() {
        let center = MPNowPlayingInfoCenter.default()
        guard let now = ui.now else {
            center.nowPlayingInfo = nil
            center.playbackState = .stopped
            try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            return
        }
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: "Reading · \(now.title)",
            MPMediaItemPropertyAlbumTitle: now.title,
            MPNowPlayingInfoPropertyPlaybackRate: ui.playing ? 1.0 : 0.0,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.audio.rawValue,
        ]
        if let section = now.section, !section.isEmpty { info[MPMediaItemPropertyArtist] = section }
        center.nowPlayingInfo = info
        center.playbackState = ui.playing ? .playing : .paused
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
