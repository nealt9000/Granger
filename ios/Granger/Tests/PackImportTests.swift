import XCTest
import GrangerCoreKit
@testable import Granger

/// Plan I4 Task 7: Import a pack copies a .zim into Documents/Packs, where the library lists it; the same pack again is
/// "already here"; a file that isn't a pack is refused. Uses GRANGER_TEST_ZIM; skipped without it.
final class PackImportTests: XCTestCase {
    func testImportCopiesListsAndRefusesTwice() async throws {
        guard let zim = ProcessInfo.processInfo.environment["GRANGER_TEST_ZIM"], !zim.isEmpty else { throw XCTSkip("no GRANGER_TEST_ZIM") }
        let core = try XCTUnwrap(Core.shared, "the host app's core")
        // A copy under another name, so the -debugPack copy (if any) doesn't stand in for it.
        let src = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("import-test.zim")
        try? FileManager.default.removeItem(at: src)
        try FileManager.default.copyItem(at: URL(fileURLWithPath: zim), to: src)
        let to = core.packsFolder.appendingPathComponent("import-test.zim")
        // Start clean: neither copy of the test pack in the folder.
        for name in try FileManager.default.contentsOfDirectory(atPath: core.packsFolder.path) where name.hasSuffix(".zim") {
            try? FileManager.default.removeItem(at: core.packsFolder.appendingPathComponent(name))
        }
        try await core.library.refreshPacks()

        let first = await PackImport.copy(src, core: core)
        XCTAssertTrue(first.hasSuffix("is on this iPhone."), first)
        XCTAssertTrue(FileManager.default.fileExists(atPath: to.path))
        let packs = try await core.library.packs.first(where: { _ in true }) ?? []
        XCTAssertTrue(packs.contains { $0.filePath == to.path && $0.status == .ok }, "listed")

        let again = await PackImport.copy(src, core: core)
        XCTAssertTrue(again.hasSuffix("already."), again)

        let bad = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("not-a-pack.zim")
        FileManager.default.createFile(atPath: bad.path, contents: Data("hello".utf8))
        let refused = await PackImport.copy(bad, core: core)
        XCTAssertTrue(refused.contains("isn't a pack"), refused)

        try FileManager.default.removeItem(at: to)
        try await core.library.refreshPacks()
    }
}
