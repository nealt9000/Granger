import XCTest

/// The walkthrough the app must always pass (Plan I2 Task 7 grows it with each plan). Needs the internet (Wikipedia).
final class SmokeTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testLaunchesWithTabs() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Home"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["Search"].exists)
    }

    /// Topic page → full article → Contents → a section → a link in the page → its topic page → back twice.
    func testTopicReaderContentsAndLinks() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-debugOpen", "en:Moon"]
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
}
