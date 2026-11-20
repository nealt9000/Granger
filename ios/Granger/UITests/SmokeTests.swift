import XCTest

/// The walkthrough the app must always pass (Plan I2 Task 7 grows it with each plan). Needs the internet (Wikipedia).
/// Each test pins the settings it relies on through launch arguments (they shadow what a previous test saved).
final class SmokeTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testLaunchesWithTabs() {
        let app = XCUIApplication()
        app.launchArguments = ["-localOnly", "NO"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Home"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["Search"].exists)
    }

    /// Topic page → full article → Contents → a section → a link in the page → its topic page → back twice.
    func testTopicReaderContentsAndLinks() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en", "-debugOpen", "en:Moon"]
        app.launch()
        let read = app.buttons["read-full"]
        XCTAssertTrue(read.waitForExistence(timeout: 20), "topic page")
        read.tap()

        let contents = app.buttons["Contents"]
        XCTAssertTrue(contents.waitForExistence(timeout: 10))
        let enabled = NSPredicate(format: "isEnabled == true")
        expectation(for: enabled, evaluatedWith: contents)
        waitForExpectations(timeout: 20)
        contents.tap()
        // The sheet's list (the article has the same words as a heading).
        let orbit = app.collectionViews.buttons["Classification"].firstMatch
        XCTAssertTrue(orbit.waitForExistence(timeout: 5), "Contents lists the sections")
        orbit.tap()

        let link = app.webViews.links["Earth"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 10), "a link in the page")
        link.tap()
        XCTAssertTrue(app.staticTexts["Earth"].waitForExistence(timeout: 20), "the linked article's topic page")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["Contents"].waitForExistence(timeout: 5), "back in the reader")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["read-full"].waitForExistence(timeout: 5), "back on the topic page")
    }

    /// Search online: type, see Titles and Articles, open a result's topic page, find the search under Recent.
    func testSearchOpensATopic() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en"]
        app.launch()
        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("Mount Everest")
        XCTAssertTrue(app.staticTexts["Titles"].waitForExistence(timeout: 20), "the Titles group")
        XCTAssertTrue(app.staticTexts["Articles"].exists, "the Articles group")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Mount Everest'")).firstMatch.tap()
        XCTAssertTrue(app.buttons["read-full"].waitForExistence(timeout: 20), "its topic page")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        field.buttons["Clear text"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Recent"].waitForExistence(timeout: 5), "remembered under Recent")
    }

    /// First run: nothing is fetched until the privacy line's OK; Settings' Local only stops Search going online.
    func testPrivacyLineAndLocalOnly() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "NO", "-localOnly", "NO", "-wikiLanguage", "en"]
        app.launch()
        let ok = app.buttons["privacy-ok"]
        XCTAssertTrue(ok.waitForExistence(timeout: 10), "the privacy line on first run")
        XCTAssertFalse(app.buttons["featured"].exists, "nothing fetched before OK")
        ok.tap()
        XCTAssertTrue(app.buttons["featured"].waitForExistence(timeout: 20), "today's featured article after OK")

        app.buttons["Settings"].firstMatch.tap()
        let toggle = app.switches["Local only"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.switches.firstMatch.tap()
        app.buttons["Done"].tap()

        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("Moon")
        XCTAssertTrue(app.staticTexts["Online results appear when you're connected."].waitForExistence(timeout: 5), "Local only")
    }

    /// Keep (Plan I3 Task 6): read an article, keep it, see it marked on this phone; with Local only on it still opens,
    /// summary and page, from the phone; Remove takes it off again.
    func testKeepAndLocalOnly() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en", "-debugOpen", "en:Moon"]
        app.launch()
        if app.buttons["keep-remove"].waitForExistence(timeout: 20) { app.buttons["keep-remove"].tap() }
        let keep = app.buttons["keep"]
        XCTAssertTrue(keep.waitForExistence(timeout: 20), "the Keep button")
        keep.tap()
        XCTAssertTrue(app.buttons["keep-remove"].waitForExistence(timeout: 60), "kept")
        XCTAssertTrue(app.otherElements["mark-on-phone"].waitForExistence(timeout: 10) || app.images["mark-on-phone"].exists
            || app.descendants(matching: .any)["mark-on-phone"].exists, "marked on this phone")

        app.terminate()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en", "-debugOpen", "en:Moon"]
        app.launch()
        let read = app.buttons["read-full"]
        XCTAssertTrue(read.waitForExistence(timeout: 20), "the topic page opens with Local only on")
        XCTAssertTrue(app.buttons["keep-remove"].exists, "still kept")
        read.tap()
        XCTAssertTrue(app.webViews.staticTexts["Moon"].firstMatch.waitForExistence(timeout: 20), "the kept page reads offline")
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/kept-offline.png"))
        }
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["keep-remove"].tap()
        XCTAssertTrue(app.buttons["keep"].waitForExistence(timeout: 10), "removed")
    }

    /// Landscape: the topic page and the reader lay out and stay usable sideways.
    func testLandscape() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en", "-debugOpen", "en:Moon"]
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let read = app.buttons["read-full"]
        XCTAssertTrue(read.waitForExistence(timeout: 20))
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/landscape-topic.png"))
        }
        read.tap()
        XCTAssertTrue(app.buttons["Contents"].waitForExistence(timeout: 10))
        sleep(UInt32(ProcessInfo.processInfo.environment["GRANGER_HOLD"].flatMap(UInt32.init) ?? 3))
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/landscape-reader.png"))
        }
    }
}
