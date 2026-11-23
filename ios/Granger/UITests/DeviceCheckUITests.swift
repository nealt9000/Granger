import XCTest

/// Plan I8 Task 4: Settings › About › Device check runs in the Simulator (where the speech check finds no recognizer),
/// and Send report puts its report into Send feedback's email beside the usual facts (read through `-debugMailText`).
/// That it's absent from a Release build is `BuildExtrasTests`' (the UI tests run the Debug build).
final class DeviceCheckUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func shot(_ app: XCUIApplication, _ name: String) {
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        }
    }

    func testDeviceCheckRunsAndItsReportReachesTheFeedbackEmail() {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES",
                                                "-wikiLanguage", "en", "-debugMailText", "YES", "-homeHidden", "()"]
        app.launch()
        let gear = app.navigationBars.buttons["Settings"]
        XCTAssertTrue(gear.waitForExistence(timeout: 20), "Home's Settings button")
        gear.tap()
        XCTAssertTrue(app.reveal(app.staticTexts["settings-friends-build"]), "About's build line")
        XCTAssertTrue(app.staticTexts["settings-friends-build"].label.hasPrefix("Debug build 0.1"), app.staticTexts["settings-friends-build"].label)
        let row = app.buttons["settings-device-check"]
        XCTAssertTrue(app.reveal(row), "About's Device check")
        row.tap()

        let run = app.buttons["device-check-run"]
        XCTAssertTrue(run.waitForExistence(timeout: 10), "Run check")
        XCTAssertFalse(app.buttons["device-check-send"].exists, "nothing to send before a run")
        shot(app, "device-check")
        run.tap()
        let report = app.staticTexts["device-check-report"]
        XCTAssertTrue(report.waitForExistence(timeout: 240), "the report, once every part has run")
        let text = report.label
        shot(app, "device-check-report")
        for part in ["Phone:", "Memory:", "Speech:", "Search by meaning:", "Music library:", "App group:", "MetricKit:"] {
            XCTAssertTrue(text.contains(part), "\(part) in \(text)")
        }
        XCTAssertTrue(text.contains("no recognizer on this phone"), "the Simulator has no recognizer: \(text)")
        XCTAssertTrue(text.contains("(Simulator)"), "the model: \(text)")
        XCTAssertTrue(text.contains("snapshot round trip: ok"), "the app group: \(text)")
        XCTAssertTrue(text.contains("live: not tried"), "the live phrase waits for a person: \(text)")

        let send = app.buttons["device-check-send"]
        XCTAssertTrue(app.reveal(send), "Send report")
        send.tap()
        XCTAssertTrue(app.reveal(app.staticTexts["feedback-device-check"], timeout: 20), "the sheet says the report goes too")
        let write = app.buttons["feedback-write"]
        XCTAssertTrue(app.reveal(write), "Write email")
        write.tap()
        let mail = app.staticTexts["debug-mail-text"]
        XCTAssertTrue(mail.waitForExistence(timeout: 10), "the composed email")
        let email = mail.label
        shot(app, "device-check-email")
        XCTAssertTrue(email.contains("To: feedback@opengranger.org"), email)
        XCTAssertTrue(email.contains("0 packs and 0 books (counts only, no titles)"), "the usual facts: \(email)")
        XCTAssertTrue(email.contains("Device check · "), "the report: \(email)")
        XCTAssertTrue(email.contains("no recognizer on this phone"), "the speech check's result: \(email)")
    }
}
