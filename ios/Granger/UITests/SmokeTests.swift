import XCTest

/// The walkthrough the app must always pass (Plan I2 Task 7 grows it with each plan).
final class SmokeTests: XCTestCase {
    func testLaunchesWithTabs() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Home"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["Search"].exists)
    }
}
