import AVFAudio
import XCTest

/// Plan I6 Task 7: audiobooks, LibriVox and music under the voice. Books come from a folder this test fills with sine
/// tones and the app reads as a picked Audiobooks folder (`-debugAudiobookFolder`, for that launch: a chapter a file);
/// LibriVox from `-debugLibriVoxStub` (one stub book answered from a folder of tones, the queue copying them from
/// there: no network). Never real recordings.
final class AudiobookUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en", "-voice.speed", "1.0"] + extra
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
    }

    private func folder(_ name: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("\(name)-\(UUID().uuidString.prefix(6))", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// A [seconds] sine at [hz], written as AAC in M4A.
    private func tone(_ url: URL, seconds: Int, hz: Double) throws {
        let rate = 22050.0
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 32000]
        let out = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let buffer = AVAudioPCMBuffer(pcmFormat: out.processingFormat, frameCapacity: AVAudioFrameCount(rate))!
        buffer.frameLength = AVAudioFrameCount(rate)
        for i in 0..<Int(rate) { buffer.floatChannelData![0][i] = 0.2 * Float(sin(2 * Double.pi * hz * Double(i) / rate)) }
        for _ in 0..<seconds { try out.write(from: buffer) }
    }

    /// A book of two chapters, a file each, in a folder named [name] (the book takes the folder's name).
    private func book(_ name: String) throws -> URL {
        let dir = try folder(name)
        try tone(dir.appendingPathComponent("01 Opening.m4a"), seconds: 30, hz: 330)
        try tone(dir.appendingPathComponent("02 Middle.m4a"), seconds: 60, hz: 440)
        return dir
    }

    /// "1:05" → 65.
    private func seconds(_ label: String) -> Int {
        label.split(separator: ":").reduce(0) { $0 * 60 + (Int($1) ?? 0) }
    }

    private func waitFor(_ element: XCUIElement, timeout: TimeInterval = 20, _ test: @escaping (String) -> Bool) {
        expectation(for: NSPredicate { _, _ in element.exists && test(element.label) }, evaluatedWith: nil)
        waitForExpectations(timeout: timeout)
    }

    /// Listen, back at its root.
    private func listen(_ app: XCUIApplication) {
        app.tabBars.buttons["Listen"].tap()
        for _ in 0..<5 where !app.navigationBars["Listen"].waitForExistence(timeout: 1) { app.navigationBars.buttons.element(boundBy: 0).tap() }
    }

    /// Listen › All audiobooks › the book [title].
    private func openBook(_ app: XCUIApplication, _ title: String) {
        listen(app)
        let all = app.buttons["listen-all-audiobooks"]
        for _ in 0..<4 where !all.waitForExistence(timeout: 5) { app.swipeUp() }
        if !all.exists { shot(app, "listen-no-audiobooks") }
        XCTAssertTrue(all.waitForExistence(timeout: 30), "All audiobooks on Listen")
        all.tap()
        let row = app.buttons["audiobook-\(title)"]
        for _ in 0..<6 where !row.waitForExistence(timeout: 3) { app.swipeUp() }
        if !row.exists { shot(app, "shelf-no-book") }
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the book \(title) on the shelf")
        row.tap()
        XCTAssertTrue(app.buttons["audiobook-play"].waitForExistence(timeout: 10), "the book's page")
    }

    /// The audiobook player, from the mini player.
    private func openPlayer(_ app: XCUIApplication) {
        let mini = app.otherElements["mini-player"]
        XCTAssertTrue(mini.waitForExistence(timeout: 20), "the mini player")
        mini.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
        XCTAssertTrue(app.staticTexts["book-player-chapter"].waitForExistence(timeout: 10), "the audiobook player")
    }

    /// A book played into its second chapter and paused opens there after the app is closed: the shelf's card, the
    /// player's chapter and place, and Resume on its page.
    func testAudiobookOpensAtItsSavedPlaceAfterARelaunch() throws {
        let dir = try book("Relaunch Book")
        let title = dir.lastPathComponent
        let app = launch(["-debugAudiobookFolder", dir.path])
        openBook(app, title)
        app.buttons["audiobook-chapter-2"].tap()
        openPlayer(app)
        let chapter = app.staticTexts["book-player-chapter"]
        waitFor(chapter) { $0.hasSuffix("2 of 2") }
        let elapsed = app.staticTexts["book-player-elapsed"]
        waitFor(elapsed) { self.seconds($0) >= 6 }
        app.buttons["book-player-play-pause"].tap()
        waitFor(app.buttons["book-player-play-pause"]) { $0 == "Play" }
        sleep(1)
        let place = seconds(elapsed.label)
        let chapterLine = chapter.label
        shot(app, "book-paused")
        sleep(2)   // the pause's save

        app.terminate()
        let again = launch(["-debugAudiobookFolder", dir.path])
        listen(again)
        let card = again.otherElements["listen-book-card"]
        XCTAssertTrue(card.waitForExistence(timeout: 20), "Listen's card offers the book")
        XCTAssertTrue(card.staticTexts["Last played"].exists, "the card says Last played")
        card.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
        let chapterAgain = again.staticTexts["book-player-chapter"]
        XCTAssertTrue(chapterAgain.waitForExistence(timeout: 10), "the audiobook player")
        XCTAssertEqual(chapterAgain.label, chapterLine, "the same chapter")
        let elapsedAgain = again.staticTexts["book-player-elapsed"]
        XCTAssertLessThanOrEqual(abs(seconds(elapsedAgain.label) - place), 1, "the same place: \(elapsedAgain.label), was \(place) s")
        shot(again, "book-relaunched")
        // Play carries on from there (auto-rewind may step back a little), in the same chapter.
        again.buttons["book-player-play-pause"].tap()
        waitFor(again.buttons["book-player-play-pause"]) { $0 == "Pause" }
        XCTAssertEqual(chapterAgain.label, chapterLine)
        XCTAssertGreaterThanOrEqual(seconds(elapsedAgain.label), max(0, place - 10), "resumed near the saved place")
        again.buttons["book-player-play-pause"].tap()
        // Its page says Resume.
        again.navigationBars.buttons.element(boundBy: 0).tap()
        openBook(again, title)
        XCTAssertEqual(again.buttons["audiobook-play"].label, "Resume")
    }

    /// A bookmark set in chapter 1 is listed, and playing it from chapter 2 goes back to its chapter and place.
    func testBookmarkIsSetAndReturnedTo() throws {
        let dir = try book("Bookmark Book")
        let app = launch(["-debugAudiobookFolder", dir.path])
        openBook(app, dir.lastPathComponent)
        app.buttons["audiobook-chapter-1"].tap()
        openPlayer(app)
        let chapter = app.staticTexts["book-player-chapter"]
        waitFor(chapter) { $0.hasSuffix("1 of 2") }
        let elapsed = app.staticTexts["book-player-elapsed"]
        waitFor(elapsed) { self.seconds($0) >= 4 }
        app.buttons["book-player-bookmark"].tap()
        let marked = seconds(elapsed.label)
        let notice = app.staticTexts["voice-notice"]
        if notice.waitForExistence(timeout: 5) { XCTAssertTrue(notice.label.hasPrefix("Bookmarked:"), notice.label) }

        app.buttons["book-player-next-chapter"].tap()
        waitFor(chapter) { $0.hasSuffix("2 of 2") }
        app.buttons["book-player-bookmarks"].tap()
        let mark = app.buttons["bookmark-row"].firstMatch
        XCTAssertTrue(mark.waitForExistence(timeout: 10), "the bookmark is listed")
        shot(app, "bookmarks-sheet")
        mark.tap()
        waitFor(chapter) { $0.hasSuffix("1 of 2") }
        XCTAssertLessThanOrEqual(abs(seconds(elapsed.label) - marked), 3, "back at the bookmark: \(elapsed.label), marked at \(marked) s")
        app.buttons["book-player-play-pause"].tap()
    }

    /// LibriVox (stubbed): the book is kept through the shared queue, its chapters downloaded and filed, and it lands on
    /// the Audiobooks shelf.
    func testStubbedLibriVoxDownloadLandsOnTheShelf() throws {
        let dir = try folder("LibriVoxStub")
        try tone(dir.appendingPathComponent("tone1_64kb.m4a"), seconds: 3, hz: 330)
        try tone(dir.appendingPathComponent("tone2_64kb.m4a"), seconds: 3, hz: 440)
        let app = launch(["-debugLibriVoxStub", dir.path])
        listen(app)
        app.buttons["listen-get-more"].tap()
        let librivox = app.buttons["getmore-librivox"]
        XCTAssertTrue(librivox.waitForExistence(timeout: 10), "LibriVox in Get audiobooks & music")
        librivox.tap()
        let row = app.otherElements["librivox-row-817"]
        if !row.waitForExistence(timeout: 30) { shot(app, "librivox-no-row") }
        XCTAssertTrue(row.exists, "the stub book in Recently added")
        // An earlier run may have kept it already: remove it first.
        if !row.buttons["librivox-keep-817"].exists, row.buttons["Remove"].exists {
            row.buttons["Remove"].tap()
            app.alerts.buttons["Remove"].tap()
        }
        let keep = row.buttons["librivox-keep-817"]
        XCTAssertTrue(keep.waitForExistence(timeout: 20), "Keep")
        keep.tap()
        let done = NSPredicate { _, _ in row.staticTexts["In your library"].exists }
        expectation(for: done, evaluatedWith: nil)
        waitForExpectations(timeout: 60)
        shot(app, "librivox-kept")
        openBook(app, "Stub Tones")
        XCTAssertTrue(app.buttons["audiobook-chapter-2"].waitForExistence(timeout: 10), "both chapters")
        XCTAssertTrue(app.staticTexts["Tone 1"].exists, "the chapter's title from LibriVox")
    }

    /// Music plays under the voice: a song is playing when read aloud starts; Listen shows it under the voice with its
    /// level and its own play/pause, the mini player names it, and pausing the music leaves the voice as it was.
    func testMusicPlaysUnderTheVoice() throws {
        let voice = try env("GRANGER_TEST_VOICE")
        let zim = try env("GRANGER_TEST_ZIM")
        let app = launch(["-debugVoice", voice, "-debugPack", zim, "-debugPackReader", "YES", "-debugPlay", "tone:180"])
        let position = app.staticTexts["debug-play-position"]
        XCTAssertTrue(position.waitForExistence(timeout: 20), "the music plays")
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 30), "the pack's page in the reader")
        let listenButton = app.buttons["reader-listen"]
        XCTAssertTrue(listenButton.waitForExistence(timeout: 20), "Listen in the reader's bar")
        sleep(2)
        listenButton.tap()
        let music = app.staticTexts["mini-music"]
        XCTAssertTrue(music.waitForExistence(timeout: 30), "the mini player names the music under the voice")
        XCTAssertTrue(music.label.contains("debug-tone-0"), music.label)

        listen(app)
        let under = app.otherElements["listen-music-under"]
        for _ in 0..<4 where !under.waitForExistence(timeout: 3) { app.swipeUp() }
        XCTAssertTrue(under.exists, "Music under the voice on Listen")
        XCTAssertEqual(app.staticTexts["listen-music-under-title"].label, "debug-tone-0")
        XCTAssertTrue(app.sliders["listen-mix"].exists, "the level")
        let musicButton = app.buttons["listen-music-under-play-pause"]
        XCTAssertEqual(musicButton.label, "Pause music", "the music is heard")
        let heard = Double(position.label) ?? 0
        sleep(3)
        XCTAssertGreaterThan(Double(position.label) ?? 0, heard + 1, "the music goes on under the voice")
        shot(app, "music-under-voice")
        // The music's own button drives the music alone: the voice stays as it was (the page is short, so it may
        // already have read to its end).
        let voiceBefore = app.buttons["listen-play-pause"].label
        musicButton.tap()
        waitFor(musicButton) { $0 == "Play music" }
        XCTAssertEqual(app.buttons["listen-play-pause"].label, voiceBefore, "the voice is left as it was")
        app.buttons["listen-stop"].tap()
    }
}
