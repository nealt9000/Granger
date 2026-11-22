import XCTest

/// Plan I6 Task 5: the audio engine, through `-debugPlay` (generated tones; the music screens are Tasks 6 and 7).
final class PlayerUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en"] + extra
        app.launch()
        return app
    }

    private func seconds(_ app: XCUIApplication) -> Double {
        Double(app.staticTexts["debug-play-position"].label) ?? -1
    }

    /// Playback goes on while the app is in the background (UIBackgroundModes audio, a playback session).
    func testPlaybackGoesOnInTheBackground() throws {
        let app = launch(["-debugPlay", "tone:60"])
        let label = app.staticTexts["debug-play-position"]
        XCTAssertTrue(label.waitForExistence(timeout: 20))
        expectation(for: NSPredicate { _, _ in self.seconds(app) > 1 }, evaluatedWith: nil)
        waitForExpectations(timeout: 15)
        let before = seconds(app)
        let left = Date()
        XCUIDevice.shared.press(.home)
        Thread.sleep(forTimeInterval: 8)
        app.activate()
        XCTAssertTrue(label.waitForExistence(timeout: 10))
        let away = Date().timeIntervalSince(left)
        let after = seconds(app)
        print("AUDIO SPIKE background: \(String(format: "%.1f", after - before)) s played over \(String(format: "%.1f", away)) s away")
        XCTAssertGreaterThan(after - before, away - 2, "it kept playing while away")
    }
}
