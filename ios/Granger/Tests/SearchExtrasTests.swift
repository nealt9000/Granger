import GrangerCoreKit
import XCTest
@testable import Granger

/// Plan I7 Task 7b: Search's last three Android features on the iPhone, through the host app's core and the test ZIMs
/// in GRANGER_TEST_SEARCH (tools/test-zim/make_ios_search_zims.py): a one-word query's Wiktionary entry (the Define
/// card), and a smaller slice of a set giving way to the bigger in Search while both are here. Skipped without them.
/// (Media cards need the music library's import, so they're checked in `SearchExtrasUITests`.)
final class SearchExtrasTests: XCTestCase {
    private func fixture(_ name: String) throws -> String {
        guard let dir = ProcessInfo.processInfo.environment["GRANGER_TEST_SEARCH"], !dir.isEmpty else { throw XCTSkip("no GRANGER_TEST_SEARCH") }
        let file = "\(dir)/\(name)"
        guard FileManager.default.fileExists(atPath: file) else { throw XCTSkip("no \(file)") }
        return file
    }

    /// [names] copied into the packs folder (as `t7b-<name>`) and listed; the packs, in that order.
    private func install(_ core: Core, _ names: [String]) async throws -> [Pack] {
        var paths: [String] = []
        for name in names {
            let to = core.packsFolder.appendingPathComponent("t7b-\(name)")
            if !FileManager.default.fileExists(atPath: to.path) { try FileManager.default.copyItem(atPath: try fixture(name), toPath: to.path) }
            paths.append(to.path)
        }
        try await core.library.refreshPacks()
        let packs = try await core.library.packs.first(where: { list in paths.allSatisfy { p in list.contains { $0.filePath == p } } }) ?? []
        return try paths.map { p in try XCTUnwrap(packs.first { $0.filePath == p && $0.status == .ok }, "\(p) listed") }
    }

    private func uninstall(_ core: Core, _ names: [String]) async {
        for name in names { try? FileManager.default.removeItem(at: core.packsFolder.appendingPathComponent("t7b-\(name)")) }
        try? await core.library.refreshPacks()
    }

    /// A one-word query reads its entry from the Wiktionary pack as Android's Define card: the first language's senses
    /// and etymology without the quotations, translations or the next language; an inflected form leads to its lemma's
    /// entry with the form's line first; more than one word, or a word with no entry, has no card.
    func testDefineReadsAWordsWiktionaryEntry() async throws {
        let core = try XCTUnwrap(Core.shared, "the host app's core")
        let saved = LibraryGate.shared.languages
        defer { LibraryGate.shared.languages = saved }
        LibraryGate.shared.languages = ["en"]
        addTeardownBlock { await self.uninstall(core, ["wiktionary-test.zim"]) }
        let pack = try await install(core, ["wiktionary-test.zim"])[0]
        XCTAssertTrue(pack.name.hasPrefix("wiktionary_"), pack.name)

        let found = try await core.library.wordEntry(query: "betimes")
        let entry = try XCTUnwrap(found, "betimes' entry")
        XCTAssertEqual(entry.sourceId, pack.id)
        XCTAssertEqual(entry.sourceTitle, "betimes")
        XCTAssertEqual(entry.packTitle, "Wiktionary test")
        XCTAssertEqual(entry.location, "betimes")
        XCTAssertTrue(entry.text.contains("Etymology: From Middle English betymes"), entry.text)
        XCTAssertTrue(entry.text.contains("early, especially in the morning"), entry.text)
        XCTAssertTrue(entry.text.contains("In a short time, soon."), entry.text)
        XCTAssertFalse(entry.text.contains("King James"), "the quotations nested in a sense are dropped")
        XCTAssertFalse(entry.text.contains("de bonne heure"), "the translations are left out")
        XCTAssertFalse(entry.text.contains("at an early hour"), "only the first language")
        let line = SearchModel.defineLine(entry)
        XCTAssertFalse(line.contains("Etymology"), "the card leaves the etymology out: \(line)")
        XCTAssertTrue(line.contains("early, especially in the morning"), line)

        let cats = try await core.library.wordEntry(query: "cats")
        let form = try XCTUnwrap(cats, "cats leads to cat")
        XCTAssertEqual(form.sourceTitle, "cat")
        XCTAssertEqual(form.location, "cat")
        XCTAssertTrue(form.text.hasPrefix("cats: plural of cat"), form.text)
        XCTAssertTrue(form.text.contains("A domesticated species of feline"), form.text)
        XCTAssertFalse(form.text.contains("a slap"), "not the Dutch section")

        let none1 = try await core.library.wordEntry(query: "two words")
        let none2 = try await core.library.wordEntry(query: "zebra")
        XCTAssertNil(none1, "a query of two words isn't looked up")
        XCTAssertNil(none2, "a word with no entry has no card")

        await uninstall(core, ["wiktionary-test.zim"])
        let gone = try await core.library.wordEntry(query: "betimes")
        XCTAssertNil(gone, "no Wiktionary, no card")
    }

    /// Two slices of one set, the small one's "Lighthouse" also in the big one: with the catalog naming them a set, the
    /// small slice's hits for it are left out of Titles, Articles and Best matches (Android's CatalogPackSlices and
    /// SliceDedupe), and the big one's "Lighthouse" is what's listed; before the catalog says so, the small one's copy (it
    /// ranks first) is what Search lists.
    func testASmallerSlicesRepeatsGiveWayToTheBiggerOnes() async throws {
        let core = try XCTUnwrap(Core.shared, "the host app's core")
        let saved = LibraryGate.shared.languages
        let savedCatalog = core.library.catalog
        defer {
            LibraryGate.shared.languages = saved
            core.library.catalog = savedCatalog
        }
        LibraryGate.shared.languages = ["en"]
        let packs = try await install(core, ["slice-small.zim", "slice-big.zim"])
        let (small, big) = (packs[0], packs[1])
        addTeardownBlock { await self.uninstall(core, ["slice-small.zim", "slice-big.zim"]) }

        /// Every pack id Search lists for "lighthouse": titles (with their other copies), article text and Best matches.
        func listed() async throws -> (titles: [String], text: [String], best: [String]) {
            let titles = try await core.library.packSearch.titles(query: "lighthouse").hits
                .flatMap { [$0] + $0.also }.filter { $0.title == "Lighthouse" }.map(\.packId)
            let text = try await core.library.packSearch.fullText(query: "lighthouse keeper").filter { $0.path == "Lighthouse" }.map(\.packId)
            let best = try await core.library.best(query: "lighthouse keeper").filter { $0.passage.location == "Lighthouse" }.map(\.passage.sourceId)
            return (titles, text, best)
        }

        core.library.catalog = nil
        let before = try await listed()
        print("SLICES before: titles \(before.titles), text \(before.text), best \(before.best); small \(small.id), big \(big.id)")
        XCTAssertTrue((before.titles + before.text + before.best).contains(small.id),
                      "without the catalog the small slice's Lighthouse is what Search picks (its copy ranks first)")

        let json = """
        {"formatVersion": 1, "generatedAt": "2026-11-23T00:00:00Z", "packs": [
          \(entry("t7b-small", "granger_testset_small", rank: 1)),
          \(entry("t7b-big", "granger_testset_big", rank: 2))
        ]}
        """
        core.library.catalog = try XCTUnwrap(core.library.debugCatalog(json: json), "the test catalog")
        let after = try await listed()
        print("SLICES after: titles \(after.titles), text \(after.text), best \(after.best)")
        XCTAssertFalse(after.titles.isEmpty, "Lighthouse is still a title")
        XCTAssertEqual(Set(after.titles), [big.id], "the title is the big slice's alone")
        XCTAssertFalse(after.text.contains(small.id), "the small slice's article text is left out")
        XCTAssertFalse(after.best.isEmpty, "Lighthouse is a best match")
        XCTAssertEqual(Set(after.best), [big.id], "the best match is the big slice's")
    }

    private func entry(_ id: String, _ zimName: String, rank: Int) -> String {
        """
        {"id": "\(id)", "title": "\(id)", "category": "test", "language": "eng", "sizeBytes": 1, "sha256": "\(String(repeating: "0", count: 64))",
         "urls": ["https://example.org/\(id).zim"], "snapshotDate": "2026-11-23", "license": "CC0", "hasFulltextIndex": true,
         "zimUuid": "u-\(id)", "zimName": "\(zimName)", "set": "granger_testset", "setRank": \(rank)}
        """
    }
}
