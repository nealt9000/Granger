import XCTest
import GrangerCoreKit
@testable import Granger

/// Plan I4 Task 5's spike: a real Kiwix pack opened with CoreKiwix through the shared ZimArchive. The pack's path comes
/// from GRANGER_TEST_ZIM (pass TEST_RUNNER_GRANGER_TEST_ZIM to xcodebuild); without it the test is skipped.
final class KiwixArchiveTests: XCTestCase {
    private func pack() throws -> String {
        guard let path = ProcessInfo.processInfo.environment["GRANGER_TEST_ZIM"], !path.isEmpty else { throw XCTSkip("no GRANGER_TEST_ZIM") }
        return path
    }

    func testOpensReadsAndSearches() throws {
        let archive = try KiwixArchive.open(try pack()).get()
        XCTAssertFalse(archive.uuid.isEmpty)
        XCTAssertFalse(archive.metadata.title.isEmpty)
        XCTAssertFalse(archive.metadata.name.isEmpty)
        let main = try XCTUnwrap(archive.mainPath)
        XCTAssertEqual(archive.resolvePath(path: main), main)
        let page = try XCTUnwrap(archive.read(path: main))
        XCTAssertTrue(page.mimeType.hasPrefix("text/html"))
        XCTAssertGreaterThan(page.bytes.size, 100)
        XCTAssertNotNil(archive.entry(path: main))
        XCTAssertNil(archive.read(path: "no/such/entry"))
        XCTAssertNotNil(archive.randomPath())

        let suggestions = archive.suggest(query: "bread", limit: 5)
        XCTAssertFalse(suggestions.isEmpty, "title suggestions")
        if archive.hasFulltextIndex {
            let hits = archive.search(query: "flour", limit: 5)
            XCTAssertFalse(hits.isEmpty, "full-text hits")
            XCTAssertNotNil(hits.first?.snippet)
            XCTAssertFalse(archive.searchWithoutSnippets(query: "flour", limit: 5).isEmpty)
        }
        var images = 0
        XCTAssertTrue(archive.list(mimes: ["image/webp", "image/jpeg", "image/png"]) { _ in images += 1; return KotlinBoolean(bool: images < 3) })
        print("KIWIX-SPIKE title=\(archive.metadata.title) name=\(archive.metadata.name) main=\(main) suggest=\(suggestions.map(\.title)) fulltext=\(archive.hasFulltextIndex) images=\(images)")

        archive.close()
        XCTAssertNil(archive.read(path: main))
        XCTAssertTrue(archive.suggest(query: "bread", limit: 5).isEmpty)
    }

    func testABadFileSaysWhy() {
        let tmp = NSTemporaryDirectory() + "not-a-zim.zim"
        FileManager.default.createFile(atPath: tmp, contents: Data("hello".utf8))
        guard case let .failure(why) = KiwixArchive.open(tmp) else { return XCTFail("opened a non-ZIM") }
        XCTAssertFalse(why.reason.isEmpty)
    }
}
