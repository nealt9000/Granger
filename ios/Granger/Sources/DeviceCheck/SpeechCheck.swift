#if FRIENDS
import AVFoundation
import GrangerCoreKit
import Speech

/// The Device check's speech check (Plan I8 call 4): iOS 26's SpeechAnalyzer on the phone (SpeechTranscriber where the
/// phone has it, else DictationTranscriber), never a server, with the shared `SpokenTitleMatcher` turning what it heard
/// into a title, as the mic will. Two ways: a phrase the system voice speaks into a file, and the tester saying a title.
/// `SpeechSpikeTests` runs the same code on a device. The Simulator has no recognizer model, so there it says so.
enum SpeechCheck {
    /// One language's phrase, title and the names it's matched against.
    struct Phrase {
        let language: String
        /// The system voice for the spoken file: neutral Latin American Spanish, as Android's.
        let voice: String
        let spoken: String
        let title: String
        let names: [String]
    }

    static let english = Phrase(language: "en", voice: "en-US", spoken: "Open Pride and Prejudice", title: "Pride and Prejudice",
                                names: ["Pride and Prejudice", "The King James Bible", "Best of Wikipedia", "Moby Dick", "The Rivals"])
    static let spanish = Phrase(language: "es", voice: "es-MX", spoken: "Abrir Orgullo y prejuicio", title: "Orgullo y prejuicio",
                                names: ["Orgullo y prejuicio", "Don Quijote de la Mancha", "Lo mejor de Wikipedia", "Moby Dick", "La Biblia"])

    /// The phrase in the app's language (Spanish when iOS shows Granger in Spanish).
    static var forApp: Phrase { Locale.current.language.languageCode?.identifier == "es" ? spanish : english }

    /// What one try found.
    struct Outcome {
        let phrase: Phrase
        let how: String
        var locale: String?
        var recognizer: String?
        var heard: String?
        var match: SpokenTitleMatcher.Match?
        var problem: String?
        var seconds: Double = 0
        /// Each step's time ("locales 0.1 s, model 2.3 s, …"), to see where a slow phone spends it.
        var steps: [String] = []
        var passed: Bool { match?.name == phrase.title }
    }

    enum Problem: Error, LocalizedError {
        case noRecognizer(String), noLanguage, noVoice, noMic, failed(String)
        var errorDescription: String? {
            switch self {
            case let .noRecognizer(why): "no recognizer on this phone (\(why))"
            case .noLanguage: "no recognizer language for this phrase"
            case .noVoice: "the system voice made no audio"
            case .noMic: "the microphone wasn't allowed"
            case let .failed(why): why
            }
        }
    }

    /// The phrase spoken by the system voice into a file, then heard.
    static func onSpokenFile(_ phrase: Phrase) async -> Outcome {
        var out = Outcome(phrase: phrase, how: "spoken file")
        let start = Date()
        do {
            let prepared = try await prepare(phrase, into: &out)
            let file = try await SpokenPhrase.write(phrase.spoken, voice: phrase.voice,
                                                    to: FileManager.default.temporaryDirectory.appendingPathComponent("device-check-\(phrase.language).caf"))
            try await hear(file, with: prepared, into: &out)
        } catch {
            out.problem = error.localizedDescription
        }
        out.seconds = Date().timeIntervalSince(start)
        return out
    }

    /// The tester says the title; a few seconds from the mic, then heard. The mic is asked for only once the recognizer
    /// is known to be there, so a phone without one (the Simulator) never asks.
    static func live(_ phrase: Phrase, seconds: Double = 4) async -> Outcome {
        var out = Outcome(phrase: phrase, how: "live")
        let start = Date()
        do {
            let prepared = try await prepare(phrase, into: &out)
            guard await AVAudioApplication.requestRecordPermission() else { throw Problem.noMic }
            let file = try await record(seconds: seconds, to: FileManager.default.temporaryDirectory.appendingPathComponent("device-check-live.caf"))
            try await hear(file, with: prepared, into: &out)
        } catch {
            out.problem = error.localizedDescription
        }
        out.seconds = Date().timeIntervalSince(start)
        return out
    }

    /// A recognizer ready for [phrase]'s language: the shared `RecognizerTags` picks the locale from what the phone has.
    private struct Prepared {
        let analyzer: SpeechAnalyzer
        let module: any LocaleDependentSpeechModule
        let format: AVAudioFormat
    }

    private static func prepare(_ phrase: Phrase, into out: inout Outcome) async throws -> Prepared {
        var mark = Date()
        func step(_ name: String) {
            out.steps.append("\(name) \(String(format: "%.1f", Date().timeIntervalSince(mark))) s")
            mark = Date()
        }
        let transcriber = SpeechTranscriber.isAvailable
        let installed = transcriber ? await SpeechTranscriber.installedLocales : await DictationTranscriber.installedLocales
        let supported = transcriber ? await SpeechTranscriber.supportedLocales : await DictationTranscriber.supportedLocales
        let languages = RecognizerLanguages(installed: installed.map { $0.identifier(.bcp47) }, supported: supported.map { $0.identifier(.bcp47) })
        guard let tag = RecognizerTags.shared.forApp(appLanguage: phrase.language, englishTag: RecognizerTags.shared.ENGLISH_US, languages: languages) else {
            throw supported.isEmpty ? Problem.noRecognizer("no languages") : Problem.noLanguage
        }
        step("locales")
        let locale = Locale(identifier: tag)
        out.locale = tag
        let module: any LocaleDependentSpeechModule = transcriber
            ? SpeechTranscriber(locale: locale, preset: .transcription)
            : DictationTranscriber(locale: locale, preset: .shortDictation)
        out.recognizer = transcriber ? "SpeechTranscriber" : "DictationTranscriber"
        if await AssetInventory.status(forModules: [module]) != .installed,
           let request = try await AssetInventory.assetInstallationRequest(supporting: [module]) {
            try await request.downloadAndInstall()
        }
        step("model")
        let format = await SpeechAnalyzer.bestAvailableAudioFormat(compatibleWith: [module])
            ?? AVAudioFormat(commonFormat: .pcmFormatInt16, sampleRate: 16000, channels: 1, interleaved: false)!
        let analyzer = SpeechAnalyzer(modules: [module])
        do {
            try await analyzer.prepareToAnalyze(in: format)
            step("prepare")
        } catch {
            step("prepare")
            throw Problem.noRecognizer(error.localizedDescription)
        }
        // On a phone with a recognizer: speech recognition's own permission, in case the analyzer wants it too.
        if SFSpeechRecognizer.authorizationStatus() == .notDetermined {
            _ = await withCheckedContinuation { c in SFSpeechRecognizer.requestAuthorization { c.resume(returning: $0) } }
        }
        return Prepared(analyzer: analyzer, module: module, format: format)
    }

    private static func hear(_ file: URL, with p: Prepared, into out: inout Outcome) async throws {
        let buffer = try converted(file, to: p.format)
        let module = p.module
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
        try await p.analyzer.start(inputSequence: stream)
        input.yield(AnalyzerInput(buffer: buffer))
        input.finish()
        try await p.analyzer.finalizeAndFinishThroughEndOfInput()
        let heard = try await collect.value
        out.heard = heard
        out.match = SpokenTitleMatcher.shared.bestAny(heard: heard, names: out.phrase.names)
    }

    /// [seconds] from the mic into a file, the audio session put back for playback after.
    private static func record(seconds: Double, to url: URL) async throws -> URL {
        let session = AVAudioSession.sharedInstance()
        try session.setCategory(.playAndRecord, mode: .measurement, options: [.defaultToSpeaker])
        try session.setActive(true)
        defer {
            try? session.setCategory(.playback, mode: .default, options: [])
        }
        try? FileManager.default.removeItem(at: url)
        let engine = AVAudioEngine()
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else { throw Problem.noMic }
        let file = try AVAudioFile(forWriting: url, settings: format.settings, commonFormat: format.commonFormat, interleaved: format.isInterleaved)
        input.installTap(onBus: 0, bufferSize: 4096, format: format) { buffer, _ in try? file.write(from: buffer) }
        engine.prepare()
        try engine.start()
        try await Task.sleep(for: .seconds(seconds))
        input.removeTap(onBus: 0)
        engine.stop()
        return url
    }

    /// The file's audio in the analyzer's format.
    static func converted(_ url: URL, to format: AVAudioFormat) throws -> AVAudioPCMBuffer {
        let file = try AVAudioFile(forReading: url)
        guard let source = AVAudioPCMBuffer(pcmFormat: file.processingFormat, frameCapacity: AVAudioFrameCount(file.length)) else {
            throw Problem.failed("couldn't read the audio")
        }
        try file.read(into: source)
        guard let converter = AVAudioConverter(from: file.processingFormat, to: format) else { throw Problem.failed("no audio converter") }
        let frames = AVAudioFrameCount(Double(source.frameLength) * format.sampleRate / file.processingFormat.sampleRate) + 1024
        guard let out = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: frames) else { throw Problem.failed("couldn't convert the audio") }
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

/// A phrase spoken by the system voice into a file (the speech check, and the mic's tests).
enum SpokenPhrase {
    static func write(_ text: String, voice: String = "en-US", to url: URL) async throws -> URL {
        try? FileManager.default.removeItem(at: url)
        let synth = AVSpeechSynthesizer()
        let utterance = AVSpeechUtterance(string: text)
        utterance.voice = AVSpeechSynthesisVoice(language: voice)
        let box = Box()
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
            box.continuation = c
            synth.write(utterance) { buffer in
                guard let pcm = buffer as? AVAudioPCMBuffer else { return }
                if pcm.frameLength == 0 { box.finish(nil); return }
                do {
                    if box.file == nil {
                        box.file = try AVAudioFile(forWriting: url, settings: pcm.format.settings, commonFormat: pcm.format.commonFormat,
                                                   interleaved: pcm.format.isInterleaved)
                    }
                    try box.file?.write(from: pcm)
                } catch { box.finish(error) }
            }
            // A voice that never answers (none installed for the language) mustn't hold the check.
            DispatchQueue.global().asyncAfter(deadline: .now() + 30) { box.finish(SpeechCheck.Problem.noVoice) }
        }
        _ = synth
        guard FileManager.default.fileExists(atPath: url.path) else { throw SpeechCheck.Problem.noVoice }
        return url
    }

    /// The synthesizer's callbacks finish the wait once.
    private final class Box: @unchecked Sendable {
        var continuation: CheckedContinuation<Void, Error>?
        var file: AVAudioFile?
        private let lock = NSLock()

        func finish(_ error: Error?) {
            lock.lock()
            let c = continuation
            continuation = nil
            if c != nil { file = nil }
            lock.unlock()
            if let error { c?.resume(throwing: error) } else { c?.resume() }
        }
    }
}
#endif
