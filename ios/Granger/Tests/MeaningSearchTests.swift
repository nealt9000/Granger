import XCTest
import GrangerCoreKit
@testable import Granger

/// Plan I7 Task 5 (call 6): search by meaning wired as Android's. The host app's core gets the test pack
/// (GRANGER_TEST_ZIM) and EmbeddingGemma 2 (GRANGER_TEST_EMBEDDER) in place of the installed one; a vector file for a few
/// of the pack's articles is built here the way the catalog's are, and Best matches then find by meaning: the dense half
/// runs, its passages come back rebuilt from the pack with their cosine, and a query sharing no word with its best
/// article still finds it. Skipped without either fixture.
final class MeaningSearchTests: XCTestCase {
    func testBestMatchesFindByMeaning() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let zim = env["GRANGER_TEST_ZIM"], !zim.isEmpty else { throw XCTSkip("no GRANGER_TEST_ZIM") }
        guard let model = env["GRANGER_TEST_EMBEDDER"], !model.isEmpty else { throw XCTSkip("no GRANGER_TEST_EMBEDDER") }
        let core = try XCTUnwrap(Core.shared, "the host app's core")
        let meaning = core.library.meaning
        XCTAssertTrue(meaning.on, "the Simulator has the memory Android's rule asks for")

        let to = core.packsFolder.appendingPathComponent("meaning-test.zim")
        if !FileManager.default.fileExists(atPath: to.path) { try FileManager.default.copyItem(atPath: zim, toPath: to.path) }
        try await core.library.refreshPacks()
        let packs = try await core.library.packs.first(where: { _ in true }) ?? []
        let pack = try XCTUnwrap(packs.first { $0.filePath == to.path && $0.status == .ok }, "the test pack listed")
        defer {
            meaning.debugModelPath = nil
            meaning.debugVectors = []
            meaning.release()
            try? FileManager.default.removeItem(at: to)
        }

        meaning.debugModelPath = model
        let out = FileManager.default.temporaryDirectory.appendingPathComponent("meaning-test.gvec").path
        let rows = try await core.library.debugWriteVectors(packId: pack.id, words: ["soup", "bread", "chicken"], out: out)
        XCTAssertGreaterThan(rows.intValue, 3, "passages embedded into a vector file")

        let result = try await core.library.bestResult(query: "a warm bowl of broth for a cold winter evening")
        let found = try XCTUnwrap(result, "Best matches answered")
        XCTAssertTrue(found.byMeaning, "the dense half ran")
        XCTAssertGreaterThan(found.embedMs, 0, "the query was embedded")
        // Articles: books on the shelf with vectors of their own (Plan I7 Task 7a) can join the dense half too.
        let dense = found.matches.filter { $0.passage.cosine != nil && $0.passage.kind == .article }
        XCTAssertFalse(dense.isEmpty, "passages found by meaning, rebuilt from the pack")
        XCTAssertTrue(dense.allSatisfy { $0.passage.sourceId == pack.id }, "from the test pack")
        print("MEANING SEARCH \(rows.intValue) rows; embed \(found.embedMs) ms, dense \(found.denseMs) ms, keywords \(found.keywordMs) ms; top: "
              + found.matches.prefix(5).map { "\($0.passage.sourceTitle) (\($0.passage.cosine.map { String(format: "%.3f", $0.doubleValue) } ?? "kw"))" }.joined(separator: ", "))

        // Without the embedder Best matches go back to keywords alone.
        meaning.debugModelPath = nil
        let plain = try await core.library.bestResult(query: "a warm bowl of broth for a cold winter evening")
        XCTAssertFalse(plain?.byMeaning ?? false, "keyword-only without an embedder")
    }

    /// Plan I7 Task 7a: a book you add gets vectors made here (Android's BookVectors, shared), and Best matches then find
    /// its chapters by meaning, rebuilt from the e-book with their cosine. Skipped without the e-book or the model.
    func testBestMatchesFindABookByMeaning() async throws {
        let env = ProcessInfo.processInfo.environment
        guard let epub = env["GRANGER_TEST_EPUB"], !epub.isEmpty else { throw XCTSkip("no GRANGER_TEST_EPUB") }
        guard let model = env["GRANGER_TEST_EMBEDDER"], !model.isEmpty else { throw XCTSkip("no GRANGER_TEST_EMBEDDER") }
        let core = try XCTUnwrap(Core.shared, "the host app's core")
        let meaning = core.library.meaning
        let r = try await core.library.importBook(path: epub, displayName: (epub as NSString).lastPathComponent)
        let bookId = try XCTUnwrap((r as? ImportResultImported)?.bookId ?? (r as? ImportResultAlreadyThere)?.bookId)
        let saved = LibraryGate.shared.languages
        var made: [String] = []
        defer {
            LibraryGate.shared.languages = saved
            meaning.debugModelPath = nil
            meaning.release()
            // Every book prepareBooks embedded with the test model, not only this one.
            for f in made { try? FileManager.default.removeItem(atPath: f) }
        }
        LibraryGate.shared.languages = ["en"]
        meaning.debugModelPath = model

        let started = Date()
        try await core.library.prepareBooks()
        let books = try await core.library.books.first(where: { _ in true }) ?? []
        made = books.compactMap { core.library.bookVectors.fileFor(bookId: $0.id) }
        let file = try XCTUnwrap(core.library.bookVectors.fileFor(bookId: bookId))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file), "the book's vectors were made")
        print("BOOK VECTORS made in \(Int(Date().timeIntervalSince(started))) s")

        let result = try await core.library.bestResult(query: "a proud gentleman asks for her hand and she turns him down")
        let found = try XCTUnwrap(result, "Best matches answered")
        XCTAssertTrue(found.byMeaning, "the dense half ran")
        let dense = found.matches.filter { $0.passage.cosine != nil && $0.passage.sourceId == bookId }
        XCTAssertFalse(dense.isEmpty, "the book's chapters found by meaning")
        XCTAssertTrue(dense.allSatisfy { $0.passage.kind == .book && !$0.passage.location.isEmpty })
        print("BOOK MEANING top: " + found.matches.prefix(5).map { "\($0.passage.sourceTitle) \($0.passage.location) (\($0.passage.cosine.map { String(format: "%.3f", $0.doubleValue) } ?? "kw"))" }.joined(separator: ", "))
    }
}
