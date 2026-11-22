import AVFAudio
import XCTest

/// Plan I6 Task 6: Listen's Music. Files come in through `-debugImportMusic` (copied into Documents/Music as a pick from
/// Files would be: the picker can't be driven) and `-debugMusicFolder` (read in place as a picked folder, for that
/// launch). The tagged pair is Android's own MP3 fixtures (testdata/audio, read through TagLib); the rest are sine tones
/// this test writes (never real music).
final class MusicUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    /// The repository's testdata/audio, from this file's place in the checkout (the Simulator reads the Mac's disk).
    /// A copy of the repository's testdata/audio, from GRANGER_TEST_AUDIO (the Simulator can't read the X10 volume the
    /// checkout lives on, so the fixtures are copied beside the others); skipped without it.
    private var fixtures: String {
        get throws {
            guard let dir = ProcessInfo.processInfo.environment["GRANGER_TEST_AUDIO"], !dir.isEmpty else { throw XCTSkip("no GRANGER_TEST_AUDIO") }
            return dir
        }
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en"] + extra
        app.launch()
        return app
    }

    /// A screenshot into GRANGER_SHOTS, when it's set.
    private func shot(_ app: XCUIApplication, _ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
    }

    /// A folder of its own for this run, under the runner's temporary folder.
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

    /// Listen › All music, on [tab].
    private func allMusic(_ app: XCUIApplication, tab: String = "Albums") {
        app.tabBars.buttons["Listen"].tap()
        let all = app.buttons["listen-all-music"]
        for _ in 0..<4 where !all.waitForExistence(timeout: 5) { app.swipeUp() }
        if !all.exists { shot(app, "listen-no-music") }
        XCTAssertTrue(all.waitForExistence(timeout: 20), "All music on Listen")
        all.tap()
        let tabs = app.segmentedControls.firstMatch
        if !tabs.waitForExistence(timeout: 10) { shot(app, "music-no-tabs") }
        XCTAssertTrue(tabs.exists, "Music's tabs")
        tabs.buttons[tab].tap()
    }

    private func album(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        let row = app.buttons["album-\(title)"]
        for _ in 0..<6 where !row.waitForExistence(timeout: 3) { app.swipeUp() }
        XCTAssertTrue(row.waitForExistence(timeout: 20), "the album \(title)")
        return row
    }

    private func song(_ app: XCUIApplication, _ title: String) -> XCUIElement { app.buttons["song-\(title)"] }

    /// Two files imported from "Files" make an album with its tags (TagLib for MP3), and the album plays.
    func testImportTwoTracksAndPlayAnAlbum() throws {
        let dir = try fixtures
        let files = ["gapless-1.mp3", "gapless-2.mp3"].map { "\(dir)/\($0)" }
        guard files.allSatisfy(FileManager.default.fileExists(atPath:)) else { throw XCTSkip("no testdata/audio") }
        let app = launch(["-debugImportMusic", files.joined(separator: "|")])
        allMusic(app)
        album(app, "Gapless Test").tap()
        XCTAssertTrue(song(app, "Gapless 1").waitForExistence(timeout: 10), "the first song")
        XCTAssertTrue(song(app, "Gapless 2").exists, "the second song")
        XCTAssertTrue(app.buttons["Test Artist"].exists, "the album artist from the tags")
        app.buttons["music-play"].tap()
        XCTAssertTrue(app.buttons["music-stop"].waitForExistence(timeout: 10), "Play becomes Stop while the album plays")
        XCTAssertTrue(app.otherElements["playing-bars"].firstMatch.waitForExistence(timeout: 5) || app.images["playing-bars"].firstMatch.exists
                      || app.descendants(matching: .any)["playing-bars"].firstMatch.exists, "the playing song is marked")
        app.buttons["music-stop"].tap()
        XCTAssertTrue(app.buttons["music-play"].waitForExistence(timeout: 10), "stopped")
    }

    /// A song goes into a new playlist, and the queue is reordered in the music player.
    func testPlaylistAndQueue() throws {
        let dir = try folder("Tones")
        for (i, name) in ["Tone A", "Tone B", "Tone C"].enumerated() { try tone(dir.appendingPathComponent("\(name).m4a"), seconds: 40, hz: 330 + 110 * Double(i)) }
        let app = launch(["-debugMusicFolder", dir.path])
        allMusic(app)
        album(app, dir.lastPathComponent).tap()
        XCTAssertTrue(song(app, "Tone A").waitForExistence(timeout: 10))

        // Add to playlist › New playlist.
        let list = "Trip \(UUID().uuidString.prefix(4))"
        song(app, "Tone B").press(forDuration: 1.2)
        app.buttons["Add to playlist…"].tap()
        app.buttons["add-new-playlist"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText(list)
        app.alerts.buttons["Create"].tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.segmentedControls.firstMatch.buttons["Playlists"].tap()
        let playlist = app.buttons["playlist-\(list)"]
        XCTAssertTrue(playlist.waitForExistence(timeout: 10), "the new playlist")
        playlist.tap()
        XCTAssertTrue(song(app, "Tone B").waitForExistence(timeout: 10), "the song is in the playlist")

        // Play the album, then move the first song below the second in the queue.
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.segmentedControls.firstMatch.buttons["Albums"].tap()
        album(app, dir.lastPathComponent).tap()
        app.buttons["music-play"].tap()
        XCTAssertTrue(app.buttons["music-stop"].waitForExistence(timeout: 10))
        app.tabBars.buttons["Listen"].tap()
        for _ in 0..<5 where !app.navigationBars["Listen"].waitForExistence(timeout: 1) { app.navigationBars.buttons.element(boundBy: 0).tap() }
        let card = app.otherElements["listen-music-card"]
        XCTAssertTrue(card.waitForExistence(timeout: 10), "the music card")
        card.tap()
        XCTAssertTrue(app.staticTexts["player-title"].waitForExistence(timeout: 10), "the music player")
        XCTAssertEqual(app.staticTexts["player-title"].label, "Tone A")
        app.buttons["player-queue"].tap()
        // The queue's rows, top to bottom, by the title each shows.
        func row(_ i: Int) -> String { app.cells.element(boundBy: i).staticTexts.firstMatch.label }
        XCTAssertTrue(app.cells.element(boundBy: 1).waitForExistence(timeout: 10), "the queue")
        XCTAssertEqual(row(0), "Tone A")
        XCTAssertEqual(row(1), "Tone B")
        let handle = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Reorder'")).firstMatch
        if !handle.waitForExistence(timeout: 5) { shot(app, "queue-no-handle") }
        XCTAssertTrue(handle.exists, "the reorder handle")
        let below = app.cells.element(boundBy: 1).coordinate(withNormalizedOffset: CGVector(dx: 0.95, dy: 0.9))
        handle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 0.6, thenDragTo: below)
        expectation(for: NSPredicate { _, _ in row(0) == "Tone B" }, evaluatedWith: nil)
        waitForExpectations(timeout: 10)
        XCTAssertEqual(row(1), "Tone A", "the first song moved below the second")
    }

    /// Rescan now finds a file added to a picked folder.
    func testRescanFindsANewFile() throws {
        let dir = try folder("Rescan")
        try tone(dir.appendingPathComponent("First.m4a"), seconds: 3, hz: 440)
        let app = launch(["-debugMusicFolder", dir.path])
        allMusic(app)
        album(app, dir.lastPathComponent).tap()
        XCTAssertTrue(song(app, "First").waitForExistence(timeout: 10))
        XCTAssertFalse(song(app, "Second").exists)

        try tone(dir.appendingPathComponent("Second.m4a"), seconds: 3, hz: 550)
        app.tabBars.buttons["Listen"].tap()
        for _ in 0..<5 where !app.navigationBars["Listen"].waitForExistence(timeout: 1) { app.navigationBars.buttons.element(boundBy: 0).tap() }
        app.buttons["listen-more"].tap()
        app.buttons["Find music on this phone"].tap()
        let rescan = app.buttons["media-rescan"]
        XCTAssertTrue(rescan.waitForExistence(timeout: 10))
        rescan.tap()
        let found = NSPredicate { _, _ in app.staticTexts["media-rescan-state"].label.hasPrefix("Found") }
        expectation(for: found, evaluatedWith: nil)
        waitForExpectations(timeout: 30)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        allMusic(app)
        album(app, dir.lastPathComponent).tap()
        XCTAssertTrue(song(app, "Second").waitForExistence(timeout: 10), "Rescan now found the new file")
    }
}
