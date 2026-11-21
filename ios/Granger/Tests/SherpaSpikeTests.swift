import AVFAudio
import XCTest
@testable import Granger

/// Plan I5 Task 8's spike: sherpa-onnx 1.13.8 (the iOS framework with onnxruntime inside) loads the Owl Piper voice,
/// makes one sentence and plays it through AVAudioEngine. The voice folder comes from GRANGER_TEST_VOICE (pass
/// TEST_RUNNER_GRANGER_TEST_VOICE to xcodebuild: the catalog's `vits-piper-en_GB-granger_owl-medium` unpacked); without
/// it the test is skipped.
final class SherpaSpikeTests: XCTestCase {
    static func voiceDir() throws -> URL {
        guard let path = ProcessInfo.processInfo.environment["GRANGER_TEST_VOICE"], !path.isEmpty else { throw XCTSkip("no GRANGER_TEST_VOICE") }
        return URL(fileURLWithPath: path)
    }

    static func owl() throws -> PiperEngine {
        let dir = try voiceDir()
        let model = try XCTUnwrap(FileManager.default.contentsOfDirectory(atPath: dir.path).first { $0.hasSuffix(".onnx") })
        return try XCTUnwrap(PiperEngine(
            model: dir.appendingPathComponent(model).path,
            tokens: dir.appendingPathComponent("tokens.txt").path,
            espeakData: dir.appendingPathComponent("espeak-ng-data").path,
            noiseScale: 0.75, noiseScaleW: 1.0, lengthScale: 1.0))
    }

    func testOwlSpeaksOneSentenceThroughAVAudioEngine() throws {
        let loadStart = Date()
        let engine = try Self.owl()
        let loadMs = Date().timeIntervalSince(loadStart) * 1000
        XCTAssertEqual(engine.sampleRate, 22050)

        let sentence = "The owl keeps the door to knowledge open, always."
        let synthStart = Date()
        let samples = try XCTUnwrap(engine.generate(sentence))
        let synthMs = Date().timeIntervalSince(synthStart) * 1000
        let audioMs = Double(samples.count) / Double(engine.sampleRate) * 1000
        XCTAssertGreaterThan(audioMs, 1500, "a sentence of speech")
        XCTAssertGreaterThan(samples.map(abs).max() ?? 0, 0.05, "not silence")

        let audio = AVAudioEngine()
        let player = AVAudioPlayerNode()
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: Double(engine.sampleRate), channels: 1))
        audio.attach(player)
        audio.connect(player, to: audio.mainMixerNode, format: format)
        let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)))
        buffer.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { buffer.floatChannelData![0].update(from: $0.baseAddress!, count: samples.count) }
        try audio.start()
        let played = expectation(description: "played")
        let playStart = Date()
        player.scheduleBuffer(buffer, completionCallbackType: .dataPlayedBack) { _ in played.fulfill() }
        player.play()
        wait(for: [played], timeout: 20)
        let playMs = Date().timeIntervalSince(playStart) * 1000
        audio.stop()
        engine.close()
        print("SHERPA SPIKE load \(Int(loadMs)) ms, synth \(Int(synthMs)) ms for \(Int(audioMs)) ms of audio (rtf \(String(format: "%.2f", synthMs / audioMs))), played in \(Int(playMs)) ms")
    }
}
