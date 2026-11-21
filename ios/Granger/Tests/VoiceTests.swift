import GrangerCoreKit
import XCTest
@testable import Granger

/// Plan I5 Task 8: the Owl through the core's Piper rules (IosPiperSpeech over Swift's sherpa-onnx engine) into the
/// AVAudioEngine output the shared ReadAloudEngine writes to. GRANGER_TEST_VOICE as the spike's; skipped without it.
final class VoiceTests: XCTestCase {
    func testTheOwlSpeaksThroughTheCoreIntoTheOutput() async throws {
        let dir = try SherpaSpikeTests.voiceDir()
        let id = VoiceIdGranger(dir: dir.lastPathComponent, speaker: 0)
        let voice = Voice(id: id, title: "Granger voice (Owl)", language: "en-GB", speakers: 1)
        let factory = IosPiperSpeech.Factory(voicesDirs: { [dir.deletingLastPathComponent().path] }, natives: PiperNativesImpl())
        let speech = try await factory.open(voice: voice)
        var chunks: [Pcm] = []
        for await pcm in speech.synthesize(text: "Mr. Darcy arrived at 10 a.m. on the 3rd.", speed: 1) { chunks.append(pcm) }
        XCTAssertEqual(chunks.count, 1, "one sentence, one piece")
        let pcm = try XCTUnwrap(chunks.first)
        XCTAssertEqual(pcm.sampleRate, 22050)
        XCTAssertGreaterThan(pcm.durationMs, 1500)

        let output = AvAudioSpeechOutput()
        output.open(sampleRate: pcm.sampleRate)
        output.play()
        let started = Date()
        try await output.write(pcm: pcm)
        // The write returns once all but the last fifth of a second is queued; the rest plays out.
        XCTAssertGreaterThan(Date().timeIntervalSince(started) * 1000, Double(pcm.durationMs) - 600, "the write waits for the audio")
        try await Task.sleep(for: .seconds(1))
        XCTAssertGreaterThan(output.playedMs, pcm.durationMs - 300, "the node played it back")
        output.flush()
        XCTAssertEqual(output.playedMs, 0)
        output.close()
        speech.close()
    }

    /// The system voices are listed as Android lists its engine's: English, each named with its language.
    func testSystemVoicesAreListed() {
        let titles = VoiceTitles(granger: "Granger voice (%1$s)", system: "System voice %1$s (%2$s)", systemDefault: "System voice default (%1$s)")
        let voices = IosSystemSpeech.shared.voices(titles: titles)
        for v in voices {
            XCTAssertTrue(v.language.hasPrefix("en"))
            XCTAssertTrue(v.title.hasPrefix("System voice "))
        }
    }
}
