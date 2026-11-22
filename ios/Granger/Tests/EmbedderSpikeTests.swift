import XCTest
@testable import Granger

/// Plan I7 Task 5's spike (call 6): LiteRT-LM 0.18.0's embedding engine loads the catalog's EmbeddingGemma 2 and makes
/// the query vectors Android makes. `Fixtures/embedder-queries.json` holds 20 queries and the vectors Android's
/// `EmbeddingEngine` (litertlm-jvm 0.18.0, the same Kotlin API and engine as litertlm-android, on the Mac's CPU) made
/// for them with `LiteRtTextEmbedder`'s query prompt; each iPhone vector must be within cosine 0.99 of its twin. The
/// model comes from GRANGER_TEST_EMBEDDER (pass TEST_RUNNER_GRANGER_TEST_EMBEDDER to xcodebuild:
/// `embeddinggemma-2-text-270m.litertlm` from the catalog); without it the test is skipped.
final class EmbedderSpikeTests: XCTestCase {
    struct Reference: Decodable {
        struct Query: Decodable { let q: String; let v: [Float] }
        let queries: [Query]
    }

    static func cosine(_ a: [Float], _ b: [Float]) -> Double {
        var dot = 0.0, na = 0.0, nb = 0.0
        for i in 0..<min(a.count, b.count) {
            dot += Double(a[i]) * Double(b[i]); na += Double(a[i]) * Double(a[i]); nb += Double(b[i]) * Double(b[i])
        }
        return dot / (na.squareRoot() * nb.squareRoot())
    }

    static func residentMB() -> Double {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
        }
        return kr == KERN_SUCCESS ? Double(info.phys_footprint) / 1_048_576 : -1
    }

    func testQueryVectorsMatchAndroid() throws {
        guard let model = ProcessInfo.processInfo.environment["GRANGER_TEST_EMBEDDER"], !model.isEmpty else {
            throw XCTSkip("no GRANGER_TEST_EMBEDDER")
        }
        let url = try XCTUnwrap(Bundle(for: Self.self).url(forResource: "embedder-queries", withExtension: "json"))
        let reference = try JSONDecoder().decode(Reference.self, from: Data(contentsOf: url))
        XCTAssertEqual(reference.queries.count, 20)

        let cache = FileManager.default.temporaryDirectory.appendingPathComponent("embedder-cache", isDirectory: true)
        try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)
        let before = Self.residentMB()
        let loadStart = Date()
        let embedder = try LiteRtEmbedder(modelPath: model, cacheDir: cache.path)
        let loadMs = Date().timeIntervalSince(loadStart) * 1000
        var firstMs = 0.0, restMs = 0.0, worst = 1.0
        var lines: [String] = []
        for (i, query) in reference.queries.enumerated() {
            let start = Date()
            let vector = try embedder.embedQuery(query.q)
            let ms = Date().timeIntervalSince(start) * 1000
            if i == 0 { firstMs = ms } else { restMs += ms }
            XCTAssertEqual(vector.count, query.v.count, query.q)
            let cos = Self.cosine(vector, query.v)
            worst = min(worst, cos)
            lines.append(String(format: "%.6f  %@", cos, query.q))
            XCTAssertGreaterThanOrEqual(cos, 0.99, query.q)
        }
        let after = Self.residentMB()
        embedder.close()
        print("EMBEDDER SPIKE\n" + lines.joined(separator: "\n"))
        print(String(format: "EMBEDDER SPIKE load %.0f ms, first query %.0f ms, others %.0f ms each, worst cosine %.6f, footprint %.0f → %.0f MB (+%.0f)",
                     loadMs, firstMs, restMs / Double(reference.queries.count - 1), worst, before, after, after - before))
    }
}
