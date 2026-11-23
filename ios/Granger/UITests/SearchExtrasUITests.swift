import XCTest

/// Plan I7 Task 7b: what Search shows of Android's last three features. A one-word query's Define card from the test
/// Wiktionary (GRANGER_TEST_SEARCH), and an album's media card in Best matches that opens the album at its song (the
/// gapless pair from GRANGER_TEST_AUDIO, imported as from Files). Skipped without the fixtures.
final class SearchExtrasUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en"] + extra
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
    }

    /// Search, [query] typed until [element] shows (the library is still being read just after launch).
    private func search(_ app: XCUIApplication, _ query: String, until element: XCUIElement) {
        let tab = app.tabBars.buttons["Search"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
        tab.tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        for attempt in 0..<4 {
            field.tap()
            if attempt > 0, let typed = field.value as? String { field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: typed.count)) }
            field.typeText(query)
            if element.waitForExistence(timeout: 20) { break }
        }
    }

    /// "betimes" shows Android's Define card above the results: Define, the word, its senses (not its etymology) and
    /// the Wiktionary it's from; tapped, the entry opens in the reader.
    func testDefineCardShowsTheWordsEntry() throws {
        let zim = try env("GRANGER_TEST_SEARCH") + "/wiktionary-test.zim"
        guard FileManager.default.fileExists(atPath: zim) else { throw XCTSkip("no \(zim)") }
        let app = launch(["-debugPack", zim])
        let card = app.buttons["search-define"]
        search(app, "betimes", until: card)
        XCTAssertTrue(card.exists, "the Define card")
        shot(app, "define-card")
        let label = card.label
        XCTAssertTrue(label.contains("Define"), label)
        XCTAssertTrue(label.contains("betimes"), label)
        XCTAssertTrue(label.contains("early, especially in the morning"), label)
        XCTAssertFalse(label.contains("Middle English betymes"), "the etymology is left out: \(label)")
        XCTAssertTrue(label.contains("Dictionary entry from Wiktionary test"), label)
        XCTAssertTrue(app.reveal(card))
        card.tap()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 15), "the entry opens in the reader")
        let sense = app.webViews.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "especially in the morning")).firstMatch
        XCTAssertTrue(sense.waitForExistence(timeout: 15), "the entry's words in the reader")
        shot(app, "define-opened")
    }

    /// Two imported songs make the album "Gapless Test"; "gapless" finds its media card in Best matches (its line the
    /// sentence that matched, "Your music"), and the card opens the album with the song it's about marked.
    func testMediaCardOpensTheAlbumAtItsSong() throws {
        let dir = try env("GRANGER_TEST_AUDIO")
        let files = ["gapless-1.mp3", "gapless-2.mp3"].map { "\(dir)/\($0)" }
        guard files.allSatisfy(FileManager.default.fileExists(atPath:)) else { throw XCTSkip("no testdata/audio") }
        let app = launch(["-debugImportMusic", files.joined(separator: "|")])
        let row = app.buttons.matching(identifier: "search-media-hit").firstMatch
        search(app, "gapless", until: row)
        XCTAssertTrue(row.exists, "the album's media card in Best matches")
        XCTAssertTrue(app.staticTexts["Best matches"].exists, "under Best matches")
        shot(app, "media-card")
        let label = row.label
        XCTAssertTrue(label.contains("Gapless Test"), label)
        XCTAssertTrue(label.contains("Gapless 1"), "the songs on the card: \(label)")
        XCTAssertTrue(label.contains("Your music"), label)
        XCTAssertTrue(app.reveal(row))
        row.tap()
        XCTAssertTrue(app.buttons["song-Gapless 1"].waitForExistence(timeout: 10), "the album opens with its song")
        // The card is about the first song (its title holds "gapless" first): it's tinted for two seconds.
        shot(app, "media-card-album")
        XCTAssertTrue(app.descendants(matching: .any)["album-root"].firstMatch.exists, "the album's page")
    }
}
