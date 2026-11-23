import XCTest

/// Plan I7 Task 4: Send feedback's email (read through `-debugMailText`, since the Simulator has no Mail), the crash
/// log after a forced crash, Home's crash line, the Report sheet, and Settings › Language driving the library.
final class FeedbackUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func launch(_ extra: [String] = [], localOnly: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", localOnly ? "YES" : "NO",
                                                "-wikiLanguage", "en", "-debugMailText", "YES", "-homeHidden", "()"] + extra
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        }
    }

    private func reveal(_ app: XCUIApplication, _ element: XCUIElement) { app.reveal(element) }

    private func openSettings(_ app: XCUIApplication) {
        let gear = app.navigationBars.buttons["Settings"]
        XCTAssertTrue(gear.waitForExistence(timeout: 20), "Home's Settings button")
        gear.tap()
    }

    /// The email that would go to Mail: Settings › Send feedback › Write email, read off the debug sheet.
    private func composedEmail(_ app: XCUIApplication, from feedback: () -> Void) -> String {
        feedback()
        let write = app.buttons["feedback-write"]
        // At the list's foot: it's made when scrolled to.
        XCTAssertTrue(app.reveal(write), "the feedback sheet's Write email")
        write.tap()
        let text = app.staticTexts["debug-mail-text"]
        XCTAssertTrue(text.waitForExistence(timeout: 10), "the composed email")
        return text.label
    }

    private func viaSettings(_ app: XCUIApplication) {
        openSettings(app)
        let row = app.buttons["settings-feedback"]
        reveal(app, row)
        row.tap()
    }

    /// No crash: the sheet lists Android's lines, and the email carries the build, the phone and the counts, no attachment.
    func testFeedbackEmailHoldsTheBuildAndTheCounts() {
        let app = launch()
        let email = composedEmail(app) {
            viaSettings(app)
            XCTAssertTrue(app.reveal(app.staticTexts["The email includes"], timeout: 20), "what the email includes")
            XCTAssertTrue(app.reveal(app.staticTexts["• 0 packs and 0 books (counts only, no titles)"], timeout: 10), "the counts")
            XCTAssertFalse(app.switches["feedback-attach"].exists, "no crash reports, no switch")
            shot(app, "feedback-sheet")
        }
        shot(app, "feedback-email")
        XCTAssertTrue(email.contains("To: feedback@opengranger.org"), email)
        XCTAssertTrue(email.contains("Subject: Granger feedback · 0.1 (1)"), email)
        XCTAssertTrue(email.contains("Granger 0.1 (1)\n"), email)
        XCTAssertTrue(email.contains(", iOS "), "the phone and its iOS: \(email)")
        XCTAssertTrue(email.contains("This phone: Library (Ask off)"), email)
        XCTAssertTrue(email.contains(" free\n"), "the free space: \(email)")
        XCTAssertTrue(email.contains("0 packs and 0 books (counts only, no titles)"), email)
        XCTAssertFalse(email.contains("granger-report.txt"), "nothing attached: \(email)")
    }

    /// A forced Kotlin crash is written at once; after a relaunch Home asks, Send opens the sheet with the report, and
    /// the email attaches it. Not now on the next crash line hides it.
    func testAForcedCrashIsLoggedAndHomeAsks() {
        crashThenRelaunch(how: "kotlin") { app in
            let notice = app.descendants(matching: .any)["crash-notice"]
            XCTAssertTrue(notice.waitForExistence(timeout: 20), "Home's crash line")
            XCTAssertTrue(app.staticTexts["Granger closed unexpectedly. Send a report?"].exists)
            shot(app, "crash-notice")
            let email = composedEmail(app) {
                app.buttons["crash-send"].tap()
                XCTAssertTrue(app.reveal(app.staticTexts["• 1 crash report, attached"], timeout: 20), "the report counted")
                let crash = app.buttons["feedback-crash"]
                XCTAssertTrue(app.reveal(crash), "the report's row")
                XCTAssertTrue(crash.label.hasPrefix("Crash · "), crash.label)
                crash.tap()
                let full = app.staticTexts["crash-text"]
                XCTAssertTrue(full.waitForExistence(timeout: 10), "View")
                XCTAssertTrue(full.label.contains("IllegalStateException: Granger debug crash"), full.label)
                XCTAssertTrue(full.label.contains("Granger 0.1 (1) · "), full.label)
                shot(app, "crash-view")
                app.buttons["crash-close"].tap()
                // The switch leaves the reports out, and puts them back.
                let attach = app.switches["feedback-attach"]
                XCTAssertTrue(app.reveal(attach), "the attach switch")
                attach.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
                XCTAssertFalse(app.staticTexts["• 1 crash report, attached"].waitForExistence(timeout: 3))
                XCTAssertTrue(app.reveal(attach))
                attach.coordinate(withNormalizedOffset: CGVector(dx: 0.93, dy: 0.5)).tap()
                XCTAssertTrue(app.reveal(app.staticTexts["• 1 crash report, attached"]))
                shot(app, "crash-sheet")
            }
            XCTAssertTrue(email.contains("1 crash report, attached"), email)
            XCTAssertTrue(email.contains("[granger-report.txt]"), email)
            XCTAssertTrue(email.contains("==========\nCrash at "), email)
            XCTAssertTrue(email.contains("IllegalStateException: Granger debug crash"), email)
            // Sent (handed over): Home stops asking.
            app.buttons["Done"].firstMatch.tap()
            XCTAssertFalse(notice.waitForExistence(timeout: 5), "answered: no crash line")
        }
    }

    /// An Objective-C exception goes through the NSException handler; Not now hides the line, Clear empties the log.
    func testAnObjCExceptionIsLoggedAndNotNowHidesTheLine() {
        crashThenRelaunch(how: "objc") { app in
            let notice = app.descendants(matching: .any)["crash-notice"]
            XCTAssertTrue(notice.waitForExistence(timeout: 20), "Home's crash line")
            app.buttons["crash-not-now"].tap()
            XCTAssertFalse(notice.waitForExistence(timeout: 3), "Not now")
            viaSettings(app)
            let crash = app.buttons["feedback-crash"]
            XCTAssertTrue(app.reveal(crash, timeout: 20), "the report kept for Send feedback")
            XCTAssertTrue(crash.label.contains("NSInternalInconsistencyException"), crash.label)
            let clear = app.buttons["feedback-clear"]
            reveal(app, clear)
            clear.tap()
            XCTAssertFalse(crash.waitForExistence(timeout: 5), "cleared")
        }
    }

    /// The crash is forced through the real handler, which writes the report; the debug build then exits cleanly instead
    /// of aborting (`CrashLog.afterRecord`), so XCTest doesn't record the app's crash against this test.
    private func crashThenRelaunch(how: String, then check: (XCUIApplication) -> Void) {
        let app = launch(["-debugCrash", how])
        XCTAssertTrue(app.wait(for: .notRunning, timeout: 30), "the forced crash ends the app")
        check(launch())
    }

    /// The Report sheet (Ask is off on the iPhone; opened by the debug hook): a reason is needed, and the email carries
    /// the question, the answer, the sources and the build.
    func testReportSheet() {
        let app = launch(["-debugReportSheet", "YES"])
        XCTAssertTrue(app.descendants(matching: .any)["report-sheet"].waitForExistence(timeout: 20), "the Report sheet")
        XCTAssertTrue(app.reveal(app.staticTexts["• The titles of its 2 sources"]), "the sources counted")
        let write = app.buttons["Write email"]
        XCTAssertTrue(app.reveal(write))
        XCTAssertFalse(write.isEnabled, "a reason first")
        // The reason chosen (tried again if the tap landed while the list was still settling), then Write email is on.
        for _ in 0..<3 {
            XCTAssertTrue(app.reveal(app.buttons["Wrong or made up"]))
            app.buttons["Wrong or made up"].tap()
            XCTAssertTrue(app.reveal(write))
            if write.isEnabled { break }
        }
        XCTAssertTrue(write.isEnabled)
        shot(app, "report-sheet")
        XCTAssertTrue(app.reveal(write))
        write.tap()
        let text = app.staticTexts["debug-mail-text"]
        XCTAssertTrue(text.waitForExistence(timeout: 10), "the composed email")
        let email = text.label
        XCTAssertTrue(email.contains("Subject: Granger Ask report · 0.1 (1)"), email)
        XCTAssertTrue(email.contains("Reason: Wrong or made up"), email)
        XCTAssertTrue(email.contains("Question: Who built the Pharos?"), email)
        XCTAssertTrue(email.contains("1. Lighthouse of Alexandria"), email)
    }

    /// Settings › Language: the App language row, the library's languages (Spanish ticked, made main, English unticked:
    /// Spanish is locked), and then Search and Explore read Spanish Wikipedia.
    func testKnowledgeLanguageMovesSearchAndExploreToSpanish() {
        let app = launch(localOnly: false)
        openSettings(app)
        XCTAssertTrue(app.reveal(app.buttons["settings-language"]))
        app.buttons["settings-language"].tap()
        XCTAssertTrue(app.buttons["app-language"].waitForExistence(timeout: 10), "the App language row")
        XCTAssertTrue(app.buttons["app-language"].label.contains("English"), app.buttons["app-language"].label)
        let en = app.buttons["knowledge-en"], es = app.buttons["knowledge-es"]
        XCTAssertTrue(app.reveal(es, timeout: 60), "the library's languages (the catalog has two)")
        XCTAssertFalse(en.isEnabled, "the only one ticked is locked")
        es.tap()
        XCTAssertTrue(app.reveal(app.buttons["make-main-es"]), "Make main on the second")
        XCTAssertTrue(app.reveal(app.staticTexts["Main language"]), "the main one says so")
        XCTAssertTrue(en.isEnabled, "two ticked: neither locked")
        XCTAssertTrue(app.reveal(app.buttons["make-main-es"]))
        app.buttons["make-main-es"].tap()
        XCTAssertTrue(app.reveal(app.buttons["make-main-en"]), "Spanish is main")
        XCTAssertTrue(app.reveal(en))
        en.tap()
        XCTAssertFalse(app.buttons["make-main-en"].waitForExistence(timeout: 3))
        XCTAssertFalse(es.isEnabled, "Spanish alone: locked")
        shot(app, "language-settings")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Done"].firstMatch.tap()

        // Search: Spanish Wikipedia's results; the article's page offers English.
        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("Luna")
        let hit = app.buttons["search-hit"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 30), "results")
        shot(app, "search-es")
        hit.tap()
        XCTAssertTrue(app.reveal(app.buttons["Read in English"], timeout: 30), "a Spanish article")

        // Explore: today's spread from Spanish Wikipedia's index.
        app.tabBars.buttons["Explore"].tap()
        let spread = app.buttons["open-spread"]
        XCTAssertTrue(spread.waitForExistence(timeout: 180), "today's spread")
        shot(app, "explore-es")
        app.buttons["open-spread-plates"].tap()
        sleep(3)
        shot(app, "explore-es-topic")
        // The spread's article, read: Home's Continue names it Spanish Wikipedia.
        let read = app.buttons["read-full"]
        XCTAssertTrue(read.waitForExistence(timeout: 30), "the spread's topic page")
        // The other language from the spread's topic page too (a broad topic's spread lacked it before Task 6).
        XCTAssertTrue(app.reveal(app.buttons["read-other-language"], timeout: 30), "Read in English on the spread's topic page")
        XCTAssertTrue(app.reveal(read))
        read.tap()
        sleep(3)
        app.tabBars.buttons["Home"].tap()
        XCTAssertTrue(app.staticTexts["Wikipedia · Español"].waitForExistence(timeout: 20), "the spread was a Spanish article")
        shot(app, "home-continue-es")
    }
}
