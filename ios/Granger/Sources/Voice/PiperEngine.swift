import Foundation
import SherpaOnnxC

/// One loaded Piper voice in sherpa-onnx (Plan I5 call 3): what Android's `SherpaTts` is over the AAR, here over the
/// framework's C API. Loading reads the whole model, so make one off the main thread. [generate] makes one sentence's
/// mono float samples at [sampleRate]; sherpa makes a sentence in one piece, so it isn't cancellable midway, and [close]
/// waits for a sentence in flight before freeing the engine (Android's SIGSEGV on a voice switch, 2026-10-22).
final class PiperEngine: @unchecked Sendable {
    private var tts: OpaquePointer?
    private let gate = NSLock()
    let sampleRate: Int
    let speakers: Int

    /// [model], [tokens] and [espeakData] are absolute paths in a voice folder; the inference values are the voice's
    /// own (`PiperVoices.inference`).
    init?(model: String, tokens: String, espeakData: String, noiseScale: Float, noiseScaleW: Float, lengthScale: Float, threads: Int = 2) {
        var config = SherpaOnnxOfflineTtsConfig()
        let made: OpaquePointer? = model.withCString { cModel in
            tokens.withCString { cTokens in
                espeakData.withCString { cData in
                    "cpu".withCString { cProvider in
                        config.model.vits.model = cModel
                        config.model.vits.tokens = cTokens
                        config.model.vits.data_dir = cData
                        config.model.vits.noise_scale = noiseScale
                        config.model.vits.noise_scale_w = noiseScaleW
                        config.model.vits.length_scale = lengthScale
                        config.model.num_threads = Int32(threads)
                        config.model.provider = cProvider
                        // One sentence per call, as on Android: callers split text themselves.
                        config.max_num_sentences = 1
                        config.silence_scale = 0.2
                        return SherpaOnnxCreateOfflineTts(&config)
                    }
                }
            }
        }
        guard let made else { return nil }
        tts = made
        sampleRate = Int(SherpaOnnxOfflineTtsSampleRate(made))
        speakers = Int(SherpaOnnxOfflineTtsNumSpeakers(made))
    }

    /// One sentence's samples, or nil once closed or when the engine made nothing.
    func generate(_ text: String, speaker: Int = 0, speed: Float = 1) -> [Float]? {
        gate.lock(); defer { gate.unlock() }
        guard let tts else { return nil }
        var gen = SherpaOnnxGenerationConfig()
        gen.speed = speed
        gen.sid = Int32(speaker)
        gen.silence_scale = 0.2
        guard let audio = text.withCString({ SherpaOnnxOfflineTtsGenerateWithConfig(tts, $0, &gen, nil, nil) }) else { return nil }
        defer { SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio) }
        let n = Int(audio.pointee.n)
        guard n > 0, let samples = audio.pointee.samples else { return nil }
        return Array(UnsafeBufferPointer(start: samples, count: n))
    }

    /// Frees the engine after any sentence in flight; safe to call twice.
    func close() {
        gate.lock(); defer { gate.unlock() }
        if let tts { SherpaOnnxDestroyOfflineTts(tts) }
        tts = nil
    }

    deinit { close() }
}
