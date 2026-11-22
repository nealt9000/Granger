import XCTest

/// Plan I5 Task 7: a Classic's reference link opens its topic, and an archive.org text PDF becomes an EPUB on the shelf.
/// No network: the link and its QID index are written by `-debugLinks` for Pride and Prejudice (GRANGER_TEST_EPUB) and
/// the test pack (GRANGER_TEST_ZIM); the "archive.org" PDF is made on the phone by `-debugArchiveSample` and filed as a
/// finished archive.org download would be. archive.org's own screen is opened with Local only on.
final class ClassicsUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        }
    }

    /// The book's reference link is drawn and tapped (`-debugTapFirstLink`); its card shows the pack's article; Open article shows its topic page.
    func testReferenceLinkOpensItsTopic() throws {
        let epub = try env("GRANGER_TEST_EPUB")
        let zim = try env("GRANGER_TEST_ZIM")
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en",
                               "-reading.theme", "APP", "-debugPack", zim, "-debugLinks", "YES", "-debugTapFirstLink", "YES", "-debugBook", epub]
        app.launch()
        let open = app.buttons["link-open-article"]
        XCTAssertTrue(open.waitForExistence(timeout: 40), "the book's reference link is drawn, and its card opens")
        XCTAssertTrue(app.otherElements["link-peek"].exists || app.descendants(matching: .any)["link-peek"].exists, "the card")
        shot(app, "classics-peek")
        open.tap()
        XCTAssertTrue(app.buttons["read-full"].waitForExistence(timeout: 20), "the article's topic page")
        shot(app, "classics-topic")
    }

    /// A born-digital PDF filed as an archive.org download becomes an e-book: the reader opens it as an EPUB (Search in
    /// book is an EPUB's), and it's on the shelf (Recently open has it). archive.org's screen shows the 1930 rule.
    func testArchiveTextPdfBecomesAnEpubOnTheShelf() throws {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en",
                               "-reading.theme", "APP", "-debugArchiveSample", "YES"]
        app.launch()
        let notice = app.staticTexts["debug-import-notice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 60), "the PDF is filed")
        XCTAssertEqual(notice.label, "archive: epub", "filed as an e-book")
        XCTAssertTrue(app.buttons["Search in book"].waitForExistence(timeout: 30) || {
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap()
            return app.buttons["Search in book"].waitForExistence(timeout: 10)
        }(), "the e-book reader, not the PDF's pages")
        shot(app, "archive-epub")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.tabBars.buttons["Library"].tap()
        let recent = app.buttons["recently-open"]
        XCTAssertTrue(recent.waitForExistence(timeout: 10))
        recent.tap()
        XCTAssertTrue(app.buttons.matching(NSPredicate(format: "label CONTAINS 'A Field Book of Ropes'")).firstMatch.waitForExistence(timeout: 10),
                      "the book is on the shelf")
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.9)).tap()

        app.buttons["Get more"].tap()
        let archive = app.buttons["getmore-archive"]
        XCTAssertTrue(app.reveal(archive, timeout: 10))
        archive.tap()
        XCTAssertTrue(app.staticTexts["Showing public-domain texts only: published 1930 or earlier."].waitForExistence(timeout: 15),
                      "the App Store's 1930 rule (call 9)")
        XCTAssertFalse(app.buttons["Show everything"].exists, "no waiver on the App Store")
        shot(app, "archive-screen")
    }

    /// Classics volumes (Android since 2026-11-21): Granger's own screen lists each collection's volumes under it,
    /// indented, each with its own Get; the screen is titled by its scope's name. Needs the catalog (online).
    func testClassicsVolumesSitUnderTheirCollection() {
        let app = XCUIApplication()
        // Granger's screen, as the Library's Every collection opens it.
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en",
                                                "-debugSource", "GRANGER"]
        app.launch()
        XCTAssertTrue(app.navigationBars["Granger"].waitForExistence(timeout: 20), "titled by its scope, as on Android")
        let whole = app.descendants(matching: .any)["preset-granger-classics"]
        let drama = app.descendants(matching: .any)["preset-granger-classics-drama"]
        XCTAssertTrue(app.reveal(whole, timeout: 60), "the collection's set")
        XCTAssertTrue(app.reveal(drama), "a volume")
        XCTAssertTrue(drama.staticTexts["Drama"].exists, "the volume named without its collection's name")
        XCTAssertTrue(app.reveal(whole))
        shot(app, "classics-volumes")
        XCTAssertGreaterThan(drama.frame.minX, whole.frame.minX + 10, "indented under its collection")
    }
}
