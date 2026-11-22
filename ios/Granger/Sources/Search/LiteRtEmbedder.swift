import CLiteRTLM
import Foundation

/// EmbeddingGemma 2 (.litertlm) on LiteRT-LM 0.18.0's embedding engine (Plan I7 call 6), the iPhone's
/// `LiteRtTextEmbedder`: the same release, the same model and the same prompt formats as Android, so the query vectors
/// match Android's (cosine ≥ 0.99, `EmbedderSpikeTests`) and the catalog's `.gvec` files work unchanged. CPU only: the
/// Simulator has no LiteRT GPU, and one model on the CPU keeps the phone's memory small.
final class LiteRtEmbedder {
    enum Failure: Error { case settings, engine, input, compute }

    private var engine: OpaquePointer?
    private let lock = NSLock()

    /// Loads the model; `cacheDir` must exist and be writable (LiteRT-LM keeps compiled weights there).
    init(modelPath: String, cacheDir: String) throws {
        litert_lm_set_min_log_level(kLiteRtLmLogSeverityError)
        guard let settings = litert_lm_embedding_engine_settings_create(modelPath, "cpu", nil, nil) else { throw Failure.settings }
        defer { litert_lm_embedding_engine_settings_delete(settings) }
        litert_lm_embedding_engine_settings_set_cache_dir(settings, cacheDir)
        guard let made = litert_lm_embedding_engine_create(settings) else { throw Failure.engine }
        engine = made
    }

    deinit { close() }

    func close() {
        lock.lock(); defer { lock.unlock() }
        if let engine { litert_lm_embedding_engine_delete(engine) }
        engine = nil
    }

    /// A search query in EmbeddingGemma's retrieval form, as Android's `embed(…, isQuery = true)`.
    func embedQuery(_ text: String) throws -> [Float] {
        try embed("task: search result | query: \(text)")
    }

    /// A passage with its title, as `tools/vectors/build.py` and Android's `embedDocuments`.
    func embedDocument(title: String, text: String) throws -> [Float] {
        let named = title.trimmingCharacters(in: .whitespacesAndNewlines)
        return try embed("title: \(named.isEmpty ? "none" : named) | text: \(text)")
    }

    private func embed(_ prompt: String) throws -> [Float] {
        lock.lock(); defer { lock.unlock() }
        guard let engine else { throw Failure.engine }
        let bytes = Array(prompt.utf8)
        guard let input = bytes.withUnsafeBufferPointer({ litert_lm_input_data_create(kLiteRtLmInputDataTypeText, $0.baseAddress, $0.count) }) else {
            throw Failure.input
        }
        defer { litert_lm_input_data_delete(input) }
        var inputs: [OpaquePointer?] = [input]
        let response = inputs.withUnsafeMutableBufferPointer { buf in
            litert_lm_embedding_engine_compute_embedding(engine, buf.baseAddress, 1, nil)
        }
        guard let response else { throw Failure.compute }
        defer { litert_lm_embedding_response_delete(response) }
        let count = litert_lm_embedding_response_get_size(response)
        guard count > 0, let values = litert_lm_embedding_response_get_values(response) else { throw Failure.compute }
        return Array(UnsafeBufferPointer(start: values, count: count))
    }
}
