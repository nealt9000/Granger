import AVFAudio
import MediaPlayer
import XCTest
@testable import Granger

/// Plan I6 Task 5's spike (call 1): music and audiobooks on AVAudioEngine beside the I5 voice. Every fixture is a
/// generated tone written here; the long M4B comes from GRANGER_TEST_M4B (made with afconvert from a tone), skipped
/// without it. Measurements render offline (manual rendering), so they are exact rather than timed by ear.
final class AudioEngineSpikeTests: XCTestCase {
    static let rate = 44100.0

    /// A [seconds] sine at [freq] written as [ext] ("caf" PCM or "m4a" AAC) in the temporary folder.
    static func tone(_ name: String, seconds: Double, freq: Double, ext: String, amp: Float = 0.5, channels: AVAudioChannelCount = 2) throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("\(name).\(ext)")
        try? FileManager.default.removeItem(at: url)
        let settings: [String: Any] = ext == "m4a"
            ? [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: channels, AVEncoderBitRateKey: 128_000]
            : [AVFormatIDKey: kAudioFormatLinearPCM, AVSampleRateKey: rate, AVNumberOfChannelsKey: channels, AVLinearPCMBitDepthKey: 16,
               AVLinearPCMIsFloatKey: false, AVLinearPCMIsBigEndianKey: false]
        let file = try AVAudioFile(forWriting: url, settings: settings)
        let format = file.processingFormat
        let frames = AVAudioFrameCount(seconds * rate)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        buffer.frameLength = frames
        for c in 0..<Int(format.channelCount) {
            let p = buffer.floatChannelData![c]
            for i in 0..<Int(frames) { p[i] = amp * Float(sin(2 * Double.pi * freq * Double(i) / rate)) }
        }
        try file.write(from: buffer)
        return url
    }

    /// Renders [engine] offline for [seconds] after [start] (which plays the nodes) and returns channel 0.
    static func render(_ engine: AVAudioEngine, seconds: Double, start: () -> Void, each: ((Int) -> Void)? = nil) throws -> [Float] {
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: rate, channels: 2))
        try engine.enableManualRenderingMode(.offline, format: format, maximumFrameCount: 1024)
        try engine.start()
        start()
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: engine.manualRenderingFormat, frameCapacity: 1024))
        var out: [Float] = []
        let total = Int(seconds * rate)
        while out.count < total {
            let status = try engine.renderOffline(1024, to: buffer)
            guard status == .success else { break }
            let p = buffer.floatChannelData![0]
            out.append(contentsOf: UnsafeBufferPointer(start: p, count: Int(buffer.frameLength)))
            each?(out.count)
        }
        engine.stop()
        return out
    }

    /// The longest run of near-silence (|x| < 0.005) inside [from, to) seconds, in milliseconds.
    static func longestSilenceMs(_ s: [Float], from: Double, to: Double) -> Double {
        var best = 0, run = 0
        for i in Int(from * rate)..<min(s.count, Int(to * rate)) {
            if abs(s[i]) < 0.005 { run += 1; best = max(best, run) } else { run = 0 }
        }
        return Double(best) / rate * 1000
    }

    static func rms(_ s: ArraySlice<Float>) -> Double {
        sqrt(s.reduce(0.0) { $0 + Double($1) * Double($1) } / Double(max(1, s.count)))
    }

    /// Two files scheduled back to back on one player node: the gap where they meet.
    func gap(ext: String) throws -> Double {
        let a = try AVAudioFile(forReading: Self.tone("gapA", seconds: 2, freq: 440, ext: ext))
        let b = try AVAudioFile(forReading: Self.tone("gapB", seconds: 2, freq: 660, ext: ext))
        let engine = AVAudioEngine()
        let node = AVAudioPlayerNode()
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: a.processingFormat)
        let out = try Self.render(engine, seconds: 4.5) {
            node.scheduleFile(a, at: nil)
            node.scheduleFile(b, at: nil)
            node.play()
        }
        print("AUDIO SPIKE \(ext): A \(a.length) frames, B \(b.length) frames")
        return Self.longestSilenceMs(out, from: 0.3, to: 3.7)
    }

    func testTwoTracksPlayGaplessly() throws {
        let caf = try gap(ext: "caf")
        let m4a = try gap(ext: "m4a")
        print("AUDIO SPIKE gapless: PCM gap \(String(format: "%.2f", caf)) ms, AAC gap \(String(format: "%.2f", m4a)) ms")
        XCTAssertLessThan(caf, 1)
        XCTAssertLessThan(m4a, 5)
    }

    /// Music at Android's MIX_LEVEL (0.3) on a second node under the voice's node (mono 22,050 Hz, as Piper writes).
    func testMusicMixesUnderTheVoice() throws {
        let level: Float = 0.3
        func music(volume: Float, withVoice: Bool) throws -> [Float] {
            let engine = AVAudioEngine()
            let voice = AVAudioPlayerNode(), musicNode = AVAudioPlayerNode()
            let voiceFormat = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 22050, channels: 1))
            let file = try AVAudioFile(forReading: Self.tone("mix", seconds: 2, freq: 440, ext: "caf"))
            engine.attach(voice); engine.attach(musicNode)
            engine.connect(voice, to: engine.mainMixerNode, format: voiceFormat)
            engine.connect(musicNode, to: engine.mainMixerNode, format: file.processingFormat)
            musicNode.volume = volume
            let speech = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: voiceFormat, frameCapacity: 22050))
            speech.frameLength = 22050
            for i in 0..<22050 { speech.floatChannelData![0][i] = 0.4 * Float(sin(2 * Double.pi * 200 * Double(i) / 22050)) }
            return try Self.render(engine, seconds: 1.5) {
                musicNode.scheduleFile(file, at: nil)
                if withVoice { voice.scheduleBuffer(speech) }
                musicNode.play(); voice.play()
            }
        }
        let full = Self.rms(try music(volume: 1, withVoice: false)[4410..<44100])
        let under = Self.rms(try music(volume: level, withVoice: false)[4410..<44100])
        let both = Self.rms(try music(volume: level, withVoice: true)[4410..<44100])
        print("AUDIO SPIKE mix: music \(String(format: "%.3f", under / full)) of full at MIX_LEVEL \(level); with voice rms \(String(format: "%.3f", both)) vs music alone \(String(format: "%.3f", under))")
        XCTAssertEqual(under / full, Double(level), accuracy: 0.01)
        XCTAssertGreaterThan(both, under * 1.5, "the voice is heard over it")
    }

    /// Node volume stops at 1.0; an AVAudioUnitEQ's global gain goes above it (the audiobook boost, ReplayGain's +dB).
    func testGainAboveOne() throws {
        func level(volume: Float, eqDb: Float?) throws -> Double {
            let engine = AVAudioEngine()
            let node = AVAudioPlayerNode()
            let file = try AVAudioFile(forReading: Self.tone("gain", seconds: 1, freq: 440, ext: "caf", amp: 0.2))
            engine.attach(node)
            node.volume = volume
            if let db = eqDb {
                let eq = AVAudioUnitEQ(numberOfBands: 0)
                eq.globalGain = db
                engine.attach(eq)
                engine.connect(node, to: eq, format: file.processingFormat)
                engine.connect(eq, to: engine.mainMixerNode, format: file.processingFormat)
            } else {
                engine.connect(node, to: engine.mainMixerNode, format: file.processingFormat)
            }
            return Self.rms(try Self.render(engine, seconds: 0.8) { node.scheduleFile(file, at: nil); node.play() }[4410..<30000])
        }
        let base = try level(volume: 1, eqDb: nil)
        let volume2 = try level(volume: 2, eqDb: nil)
        let eq6 = try level(volume: 1, eqDb: 6)
        print("AUDIO SPIKE gain: node volume 2.0 gives \(String(format: "%.3f", volume2 / base))×, EQ +6 dB gives \(String(format: "%.3f", eq6 / base))×")
        XCTAssertEqual(eq6 / base, pow(10, 6.0 / 20), accuracy: 0.02)
    }

    /// AVAudioUnitTimePitch at 2× and 0.5×: a 2 s file takes 1 s and 4 s of output.
    func testSpeedThroughTimePitch() throws {
        func played(rate speed: Float) throws -> Double {
            let engine = AVAudioEngine()
            let node = AVAudioPlayerNode()
            let pitch = AVAudioUnitTimePitch()
            pitch.rate = speed
            let file = try AVAudioFile(forReading: Self.tone("speed", seconds: 2, freq: 440, ext: "caf"))
            engine.attach(node); engine.attach(pitch)
            engine.connect(node, to: pitch, format: file.processingFormat)
            engine.connect(pitch, to: engine.mainMixerNode, format: file.processingFormat)
            let out = try Self.render(engine, seconds: 5) { node.scheduleFile(file, at: nil); node.play() }
            let lastLoud = out.lastIndex { abs($0) > 0.05 } ?? 0
            return Double(lastLoud) / Self.rate
        }
        let fast = try played(rate: 2), slow = try played(rate: 0.5)
        print("AUDIO SPIKE speed: 2 s at 2× lasts \(String(format: "%.2f", fast)) s, at 0.5× \(String(format: "%.2f", slow)) s")
        XCTAssertEqual(fast, 1, accuracy: 0.15)
        XCTAssertEqual(slow, 4, accuracy: 0.3)
    }

    /// Now Playing takes and keeps music's info; the remote next/previous commands accept targets and are enabled.
    @MainActor func testNowPlayingAndRemoteNextPrevious() throws {
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = [MPMediaItemPropertyTitle: "Tone", MPMediaItemPropertyArtist: "Spike",
                                 MPMediaItemPropertyPlaybackDuration: 2.0, MPNowPlayingInfoPropertyElapsedPlaybackTime: 0.5,
                                 MPNowPlayingInfoPropertyPlaybackRate: 1.0]
        center.playbackState = .playing
        XCTAssertEqual(center.nowPlayingInfo?[MPMediaItemPropertyTitle] as? String, "Tone")
        let c = MPRemoteCommandCenter.shared()
        let next = c.nextTrackCommand.addTarget { _ in .success }
        let previous = c.previousTrackCommand.addTarget { _ in .success }
        c.skipForwardCommand.preferredIntervals = [30]
        XCTAssertTrue(c.nextTrackCommand.isEnabled)
        XCTAssertTrue(c.previousTrackCommand.isEnabled)
        c.nextTrackCommand.removeTarget(next); c.previousTrackCommand.removeTarget(previous)
        center.nowPlayingInfo = nil
    }

    /// The long M4B (GRANGER_TEST_M4B): open, read its length, schedule from its middle and hear the first frame.
    func testALongM4BStartsQuickly() throws {
        guard let path = ProcessInfo.processInfo.environment["GRANGER_TEST_M4B"], !path.isEmpty else { throw XCTSkip("no GRANGER_TEST_M4B") }
        let size = (try FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)?.int64Value ?? 0
        let t0 = Date()
        let file = try AVAudioFile(forReading: URL(fileURLWithPath: path))
        let length = file.length
        let openMs = Date().timeIntervalSince(t0) * 1000
        let engine = AVAudioEngine()
        let node = AVAudioPlayerNode()
        engine.attach(node)
        engine.connect(node, to: engine.mainMixerNode, format: file.processingFormat)
        try engine.start()
        let t1 = Date()
        let middle = length / 2
        node.scheduleSegment(file, startingFrame: middle, frameCount: AVAudioFrameCount(file.processingFormat.sampleRate * 60), at: nil)
        node.play()
        var heardMs = -1.0
        while Date().timeIntervalSince(t1) < 10 {
            if let t = node.lastRenderTime, let p = node.playerTime(forNodeTime: t), p.sampleTime > 0 { heardMs = Date().timeIntervalSince(t1) * 1000; break }
            Thread.sleep(forTimeInterval: 0.002)
        }
        engine.stop()
        let hours = Double(length) / file.processingFormat.sampleRate / 3600
        print("AUDIO SPIKE m4b: \(size / 1_000_000) MB, \(String(format: "%.2f", hours)) h; open + length \(Int(openMs)) ms; from the middle to the first frame played \(Int(heardMs)) ms")
        XCTAssertGreaterThan(heardMs, 0)
        XCTAssertLessThan(openMs + heardMs, 2000)
    }
}
