import XCTest

/// Plan I6 Task 8: the widget's snapshot and Control Center's Local only control, through `-debugSystem` (a hidden label
/// with the snapshot as the widget reads it from the app group, and hidden buttons that run the widget's intents).
final class SystemUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-onboarding.done", "YES", "-privacySeen", "YES", "-wikiLanguage", "en", "-debugSystem", "YES"] + extra
        app.launch()
        return app
    }

    private func waitFor(_ element: XCUIElement, label: String, timeout: TimeInterval = 15) -> Bool {
        let done = expectation(for: NSPredicate(format: "label == %@", label), evaluatedWith: element)
        return XCTWaiter().wait(for: [done], timeout: timeout) == .completed
    }

    /// A track starting writes the widget's snapshot (title, playing, the next button); the widget's play/pause intent
    /// pauses it, and the snapshot follows.
    func testWidgetSnapshotFollowsTheTrack() {
        let app = launch(["-localOnly", "YES", "-debugPlay", "tone:30"])
        let snapshot = app.staticTexts["debug-widget-snapshot"]
        XCTAssertTrue(snapshot.waitForExistence(timeout: 15))
        XCTAssertTrue(waitFor(snapshot, label: "debug-tone-0|playing|next"), "the snapshot names the track: \(snapshot.label)")
        let b = app.buttons["debug-play-pause-intent"]
        print("SYSTEM button exists \(b.exists) hittable \(b.isHittable) frame \(b.frame)")
        b.tap()
        XCTAssertTrue(waitFor(snapshot, label: "debug-tone-0|paused|next"), "paused from the widget's intent: \(snapshot.label)")
        app.buttons["debug-play-pause-intent"].tap()
        XCTAssertTrue(waitFor(snapshot, label: "debug-tone-0|playing|next"), "and playing again: \(snapshot.label)")
    }

    /// Control Center's Local only intent sets the group's value; the app follows it (Settings shows it), both ways.
    func testLocalOnlyControlFlipsTheSetting() {
        let app = launch(["-localOnly", "NO"])
        let intent = app.buttons["debug-local-only-intent"]
        XCTAssertTrue(intent.waitForExistence(timeout: 15))
        for expected in ["1", "0"] {
            intent.tap()
            Thread.sleep(forTimeInterval: 1)
            app.buttons["Settings"].firstMatch.tap()
            let toggle = app.switches["Local only"]
            XCTAssertTrue(toggle.waitForExistence(timeout: 5))
            XCTAssertEqual(toggle.value as? String, expected, "Local only after the control's intent")
            app.buttons["Done"].tap()
        }
    }
}
