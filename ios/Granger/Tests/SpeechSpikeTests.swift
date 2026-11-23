import AVFoundation
import GrangerCoreKit
import Speech
import XCTest
@testable import Granger

/// Plan I6 Task 8's speech spike, kept as a test: Apple's on-device recognizer hears a phrase spoken by the system voice
/// (never the microphone), and the shared `SpokenTitleMatcher` turns it into a library title.
///
/// Spike result, 2026-11-22, iOS 27.1 Simulator: it fails, so the mic waits for a device. `SpeechTranscriber.isAvailable`
/// is false; `DictationTranscriber` lists en-US as installed but `prepareToAnalyze` fails "Model not available" ("Assets
/// do not support requested task name Dictation"); `SFSpeechRecognizer` with `requiresOnDeviceRecognition` fails "Failed
/// to create recognizer" (the Simulator's ASR asset holds no model). Each test skips with that reason when the model
/// isn't there, so it runs for real on a phone (I8).
final class SpeechSpikeTests: XCTestCase {
    private let names = ["Pride and Prejudice", "The King James Bible", "Best of Wikipedia", "Moby Dick", "The Rivals"]

    /// iOS 26's SpeechAnalyzer (call 8): SpeechTranscriber where the phone has it, else DictationTranscriber.
    func testSpeechAnalyzerHearsAPhraseAndTheMatcherFindsTheTitle() async throws {
        let locale = Locale(identifier: "en-US")
        let module: any LocaleDependentSpeechModule = SpeechTranscriber.isAvailable
            ? SpeechTranscriber(locale: locale, preset: .transcription)
            : DictationTranscriber(locale: locale, preset: .shortDictation)
        if await AssetInventory.status(forModules: [module]) != .installed,
           let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
            try await request.downloadAndInstall()
        }
        let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module])
            ?? AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: false)!
        let analyzer = SpeechAnalyzer(modules: [module])
        do {
            try await analyzer.prepareToAnalyze(in: format)
        } catch {
            throw XCTSkip("no on-device speech model here (the Simulator has none): \(error.localizedDescription)")
        }
        let phrase = try converted(try await SpokenPhrase.write("Open Pride and Prejudice", to: tmp("phrase.caf")), to: format)
        let collect = Task { () -> String in
            var text = ""
            if let t = module as? SpeechTranscriber {
                for try await r in t.results where r.isFinal { text += String(r.text.characters) }
            } else if let d = module as? DictationTranscriber {
                for try await r in d.results where r.isFinal { text += String(r.text.characters) }
            }
            return text
        }
        let (stream, input) = AsyncStream<AnalyzerInput>.makeStream()
        try await analyzer.start(inputSequence: stream)
        input.yield(AnalyzerInput(buffer: phrase))
        input.finish()
        try await analyzer.finalizeAndFinishThroughEndOfInput()
        let heard = try await collect.value
        XCTAssertEqual(SpokenTitleMatcher.shared.best(heard: heard, names: names)?.name, "Pride and Prejudice", heard)
    }

    /// The older on-device recognizer, as the parity row allows.
    func testOnDeviceSFSpeechRecognizerHearsAPhrase() async throws {
        let recognizer = try XCTUnwrap(SFSpeechRecognizer(locale: Locale(identifier: "en-US")))
        guard recognizer.supportsOnDeviceRecognition else { throw XCTSkip("no on-device recognition") }
        let request = SFSpeechURLRecognitionRequest(url: try await SpokenPhrase.write("Open Pride and Prejudice", to: tmp("phrase-sf.caf")))
        request.requiresOnDeviceRecognition = true
        let heard: String
        do {
            heard = try await withCheckedThrowingContinuation { c in
                var done = false
                recognizer.recognitionTask(with: request) { result, error in
                    if done { return }
                    if let result, result.isFinal { done = true; c.resume(returning: result.bestTranscription.formattedString) }
                    else if let error { done = true; c.resume(throwing: error) }
                }
            }
        } catch {
            throw XCTSkip("no on-device speech model here (the Simulator has none): \(error.localizedDescription)")
        }
        XCTAssertEqual(SpokenTitleMatcher.shared.best(heard: heard, names: names)?.name, "Pride and Prejudice", heard)
    }

    /// The Device check's own speech check (Plan I8 call 4), English and Spanish, on a spoken file: it passes on a phone
    /// with a recognizer, and on the Simulator says there's none (and never asks for the mic).
    func testDeviceCheckSpeechOnASpokenFile() async throws {
        for phrase in [SpeechCheck.english, SpeechCheck.spanish] {
            let outcome = await SpeechCheck.onSpokenFile(phrase)
            if let problem = outcome.problem, problem.hasPrefix("no recognizer on this phone") {
                print("DEVICE CHECK SPEECH \(phrase.language): \(problem); \(outcome.steps.joined(separator: ", ")) (\(outcome.seconds) s)")
                continue
            }
            XCTAssertTrue(outcome.passed, "\(phrase.language): heard \(outcome.heard ?? "-"), \(outcome.problem ?? "")")
        }
    }

    /// The system voice speaks into a file in both languages (the half of the check the Simulator can run).
    func testTheSystemVoiceSpeaksIntoAFile() async throws {
        for (text, voice) in [("Open Pride and Prejudice", "en-US"), ("Abrir Orgullo y prejuicio", "es-MX")] {
            let url = try await SpokenPhrase.write(text, voice: voice, to: tmp("voice-\(voice).caf"))
            let file = try AVAudioFile(forReading: url)
            XCTAssertGreaterThan(Double(file.length) / file.processingFormat.sampleRate, 0.5, "\(voice): some speech")
        }
    }

    private func tmp(_ name: String) -> URL { FileManager.default.temporaryDirectory.appendingPathComponent(name) }

    private func converted(_ url: URL, to format: AVAudioFormat) throws -> AVAudioPCMBuffer {
        let file = try AVAudioFile(forReading: url)
        let source = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)))
        try file.read(into: source)
        let converter = try XCTUnwrap(AVAudioConverter(from: file.processingFormat, to: format))
        let frames = AVAudioFrameCount(Double(source.frameLength) * format.sampleRate / file.processingFormat.sampleRate) + 1024
        let out = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames))
        var fed = false
        var failure: NSError?
        converter.convert(to: out, error: &failure) { _, status in
            if fed { status.pointee = .endOfStream; return nil }
            fed = true
            status.pointee = .haveData
            return source
        }
        if let failure { throw failure }
        return out
    }
}
