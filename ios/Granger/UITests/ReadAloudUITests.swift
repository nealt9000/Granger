import XCTest

/// Plan I5 Task 8: read aloud with the Owl (sherpa-onnx through AVAudioEngine), the mini player above the tab bar, Listen's
/// Reading part, the sleep timer, and coming back to the saved sentence. The voice comes in through `-debugVoice`
/// (TEST_RUNNER_GRANGER_TEST_VOICE: the catalog's Owl zip unpacked), the book through `-debugBook`
/// (GRANGER_TEST_EPUB) and the article's pack through `-debugPack` (GRANGER_TEST_ZIM); skipped without them.
final class ReadAloudUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en",
                               "-reading.font_scale", "1.0", "-reading.theme", "APP", "-voice.speed", "1.0"] + extra
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        }
    }

    private func showBookBars(_ app: XCUIApplication) {
        let label = app.staticTexts["book-progress"]
        if label.exists && label.isHittable { return }
        for _ in 0..<3 {
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap()
            if label.waitForExistence(timeout: 3) { return }
        }
        XCTFail("the bars come back")
    }

    private func waitFor(_ element: XCUIElement, label text: String, timeout: TimeInterval = 20) {
        expectation(for: NSPredicate(format: "label CONTAINS %@", text), evaluatedWith: element)
        waitForExpectations(timeout: timeout)
    }

    /// The book: Listen starts the Owl, the mini player shows it reading and pauses it; Listen's Reading part shows the
    /// sentence; after the app is closed, Listen offers to resume the book and goes on at the same sentence.
    func testReadAloudInABookPauseFromTheMiniPlayerAndResumeAtTheSavedSentence() throws {
        let voice = try env("GRANGER_TEST_VOICE")
        let epub = try env("GRANGER_TEST_EPUB")
        let app = launch(["-debugVoice", voice, "-debugBook", epub])
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens")
        showBookBars(app)
        let listen = app.buttons["book-listen"]
        XCTAssertTrue(listen.waitForExistence(timeout: 10), "Listen in the book's bar")
        listen.tap()

        let mini = app.otherElements["mini-player"]
        XCTAssertTrue(mini.waitForExistence(timeout: 30), "the mini player above the tab bar")
        let pause = app.buttons["mini-play-pause"]
        waitFor(pause, label: "Pause", timeout: 30)
        sleep(4)   // a few sentences heard
        shot(app, "readaloud-book-reading")
        pause.tap()
        waitFor(pause, label: "Play")
        shot(app, "readaloud-mini-paused")

        // Listen's Reading part: the sentence the voice stopped on.
        app.tabBars.buttons["Listen"].tap()
        let sentence = app.staticTexts["listen-sentence"]
        XCTAssertTrue(sentence.waitForExistence(timeout: 10), "Listen shows the sentence")
        let stoppedOn = sentence.label
        XCTAssertFalse(stoppedOn.isEmpty)
        shot(app, "readaloud-listen")

        // Closed and opened again: Listen resumes the book at that sentence.
        app.terminate()
        let again = launch([])
        again.tabBars.buttons["Listen"].tap()
        let resume = again.buttons["listen-resume"]
        XCTAssertTrue(resume.waitForExistence(timeout: 15), "Resume the last thing read")
        XCTAssertTrue(resume.label.contains("Pride"), "names the book: \(resume.label)")
        resume.tap()
        let resumed = again.staticTexts["listen-sentence"]
        XCTAssertTrue(resumed.waitForExistence(timeout: 30))
        XCTAssertEqual(resumed.label, stoppedOn, "goes on at the saved sentence")
        shot(again, "readaloud-resumed")
        again.buttons["listen-stop"].tap()
    }

    /// The sleep timer: set from Listen, it says when it stops; cancelled, it's off; End of chapter says so.
    func testSleepTimer() throws {
        let voice = try env("GRANGER_TEST_VOICE")
        let epub = try env("GRANGER_TEST_EPUB")
        let app = launch(["-debugVoice", voice, "-debugBook", epub])
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30))
        showBookBars(app)
        app.buttons["book-listen"].tap()
        XCTAssertTrue(app.otherElements["mini-player"].waitForExistence(timeout: 30))
        app.tabBars.buttons["Listen"].tap()
        let sleepRow = app.buttons["listen-sleep"]
        for _ in 0..<4 where !sleepRow.isHittable { app.swipeUp() }
        XCTAssertTrue(sleepRow.waitForExistence(timeout: 10))
        let state = app.staticTexts["listen-sleep-state"]
        XCTAssertEqual(state.label, "Off")
        sleepRow.tap()
        app.buttons["sleep-5"].tap()
        waitFor(state, label: "Stops in 4:")
        shot(app, "readaloud-sleep")
        sleepRow.tap()
        app.buttons["sleep-cancel"].tap()
        waitFor(state, label: "Off")
        sleepRow.tap()
        app.buttons["sleep-chapter"].tap()
        waitFor(state, label: "End of chapter")
        app.buttons["listen-stop"].tap()
    }

    /// An article from a pack: Listen reads it and the mini player pauses it.
    func testReadAloudInAnArticle() throws {
        let voice = try env("GRANGER_TEST_VOICE")
        let zim = try env("GRANGER_TEST_ZIM")
        let app = launch(["-debugVoice", voice, "-debugPack", zim, "-debugPackReader", "YES"])
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 30), "the pack's page in the reader")
        let listen = app.buttons["reader-listen"]
        XCTAssertTrue(listen.waitForExistence(timeout: 20), "Listen in the reader's bar")
        sleep(2)
        listen.tap()
        let pause = app.buttons["mini-play-pause"]
        XCTAssertTrue(pause.waitForExistence(timeout: 30), "the mini player")
        waitFor(pause, label: "Pause", timeout: 30)
        shot(app, "readaloud-article")
        pause.tap()
        waitFor(pause, label: "Play")
        app.tabBars.buttons["Listen"].tap()
        XCTAssertTrue(app.staticTexts["listen-sentence"].waitForExistence(timeout: 10))
        app.buttons["listen-stop"].tap()
        XCTAssertTrue(app.buttons["mini-play-pause"].waitForNonExistence(timeout: 10), "the mini player goes with the voice")
    }
}
