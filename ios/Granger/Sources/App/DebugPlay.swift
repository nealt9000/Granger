#if DEBUG
import AVFAudio
import GrangerCoreKit
import SwiftUI

/// Plan I6 Task 5's debug hook: `-debugPlay "<file>|<file>|tone:<seconds>"` plays those files as a queue at launch (through
/// the player hub, in VoiceCenter), so the UI tests can check playback without the music screens (Tasks 6 and 7). `tone:<seconds>` is a generated sine written to
/// the app's temporary folder (never real music). A hidden label, `debug-play-position`, shows the seconds played.
enum DebugPlay {
    static var entries: [String]? {
        guard let raw = UserDefaults.standard.string(forKey: "debugPlay"), !raw.isEmpty else { return nil }
        return raw.split(separator: "|").map(String.init)
    }

    /// The file for one entry: a path as given, or a generated tone.
    static func file(_ entry: String, index: Int) -> URL? {
        if entry.hasPrefix("tone:") {
            let seconds = Double(entry.dropFirst(5)) ?? 30
            let url = FileManager.default.temporaryDirectory.appendingPathComponent("debug-tone-\(index).caf")
            try? FileManager.default.removeItem(at: url)
            let rate = 44100.0
            let settings: [String: Any] = [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: rate, AVNumberOfChannelsKey: 2,
                                           AVLinearPCMBitDepthKey: 16, AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false]
            guard let out = try? AVAudioFile(forWriting: url, settings: settings),
                  let buffer = AVAudioPCMBuffer(pcmFormat: out.processingFormat, frameCapacity: AVAudioFrameCount(rate)) else { return nil }
            buffer.frameLength = AVAudioFrameCount(rate)
            let freq = 330.0 + 110.0 * Double(index)
            for c in 0..<Int(out.processingFormat.channelCount) {
                for i in 0..<Int(rate) { buffer.floatChannelData![c][i] = 0.2 * Float(sin(2 * Double.pi * freq * Double(i) / rate)) }
            }
            for _ in 0..<Int(seconds) { try? out.write(from: buffer) }
            return url
        }
        return FileManager.default.fileExists(atPath: entry) ? URL(fileURLWithPath: entry) : nil
    }
}

/// The seconds played into what plays now, for the UI tests (hidden).
struct DebugPlayLabel: View {
    @Environment(\.voice) private var voice
    var body: some View {
        Text(String(format: "%.1f", Double(ms) / 1000))
            .font(.caption2).opacity(0.01)
            .accessibilityIdentifier("debug-play-position")
    }

    /// The media's place; under the voice, the music's own (Plan I6 Task 7).
    private var ms: Int64 {
        guard let media = voice?.media else { return 0 }
        if case let .voice(v) = onEnum(of: media.now) { return v.musicPositionMs }
        return media.positionMs
    }
}
#endif
