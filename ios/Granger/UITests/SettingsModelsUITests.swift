import XCTest

/// Plan I7 Task 5: Settings › Models lists the embedder search by meaning uses (calls 6), and Settings › About › Open-source
/// licences lists every library with its licence text (call 8). Models reads the signed catalog (real network).
final class SettingsModelsUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func launch() -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "NO",
                                                "-wikiLanguage", "en", "-homeHidden", "()"]
        app.launch()
        let gear = app.navigationBars.buttons["Settings"]
        XCTAssertTrue(gear.waitForExistence(timeout: 20), "Home's Settings button")
        gear.tap()
        return app
    }

    private func reveal(_ app: XCUIApplication, _ element: XCUIElement) {
        for _ in 0..<12 where !(element.exists && element.isHittable) { app.swipeUp() }
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        }
    }

    /// Models: Android's row and screen, with the embedder offered and said to be what Search uses.
    func testModelsListsTheEmbedderSearchUses() {
        let app = launch()
        let row = app.buttons["settings-models"]
        reveal(app, row)
        XCTAssertTrue(row.staticTexts["AI models for this phone"].exists, "Android's summary")
        row.tap()
        let embedder = app.staticTexts["EmbeddingGemma 2 (ranks passages)"]
        XCTAssertTrue(embedder.waitForExistence(timeout: 60), "the catalog's embedder")
        XCTAssertTrue(app.staticTexts["Used by Search to find passages by meaning"].waitForExistence(timeout: 10), "search by meaning uses it")
        XCTAssertFalse(app.staticTexts["Needs more memory"].exists, "the iPhone can run it")
        shot(app, "models")
    }

    /// Open-source licences: every library, and each one's text in full; Granger's with the App Store permission's place.
    func testLicencesListEveryLibraryWithItsText() {
        let app = launch()
        let row = app.buttons["settings-licences"]
        reveal(app, row)
        row.tap()
        XCTAssertTrue(app.navigationBars["Open-source licences"].waitForExistence(timeout: 10), "the licences screen")
        let granger = app.buttons["licence-Granger"]
        XCTAssertTrue(granger.waitForExistence(timeout: 10), "Granger first")
        shot(app, "licences")
        granger.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'GNU GENERAL PUBLIC LICENSE'")).firstMatch.waitForExistence(timeout: 10), "the GPL in full")
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'section 7'")).firstMatch.exists, "the §7 permission's place")
        app.navigationBars.buttons.firstMatch.tap()
        for name in ["Readium Swift Toolkit", "libkiwix (CoreKiwix)", "TagLib", "LiteRT-LM", "Spectral (typeface)"] {
            let lib = app.buttons["licence-\(name)"]
            reveal(app, lib)
            XCTAssertTrue(lib.exists, name)
        }
        let taglib = app.buttons["licence-TagLib"]
        for _ in 0..<12 where !(taglib.exists && taglib.isHittable) { app.swipeDown() }
        taglib.tap()
        XCTAssertTrue(app.staticTexts.containing(NSPredicate(format: "label CONTAINS 'MOZILLA PUBLIC LICENSE'")).firstMatch.waitForExistence(timeout: 10), "TagLib's MPL")
        shot(app, "licence-taglib")
    }
}
