import AVFAudio
import XCTest

/// Plan I6 Task 9: the Night and largest-text walk. Not a check: it walks Listen, Music, Audiobooks, LibriVox,
/// archive.org audio, Media settings, the mini player, Search, Library and a reader, and takes a screenshot of each
/// into GRANGER_SHOTS for a person to look at. Set the Simulator's appearance and text size first (`xcrun simctl ui`).
/// Kept out of the app's targets so the suite doesn't carry it: copy it into ios/Granger/UITests, run `xcodegen generate`,
/// and run it with GRANGER_WALK set (TEST_RUNNER_GRANGER_WALK=1, plus GRANGER_TEST_EPUB and GRANGER_SHOTS); take it out
/// again after.
final class WalkTests: XCTestCase {
    override func setUp() { continueAfterFailure = true; CleanData.renew() }

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private var n = 0
    private func shot(_ app: XCUIApplication, _ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] else { return }
        n += 1
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + String(format: "/%02d-%@.png", n, name)))
    }

    private func folder(_ name: String) throws -> URL {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent(name, isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    private func tone(_ url: URL, seconds: Int, hz: Double) throws {
        let rate = 22050.0
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 32000]
        let out = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let buffer = AVAudioPCMBuffer(pcmFormat: out.processingFormat, frameCapacity: AVAudioFrameCount(rate))!
        buffer.frameLength = AVAudioFrameCount(rate)
        for i in 0..<Int(rate) { buffer.floatChannelData![0][i] = 0.2 * Float(sin(2 * Double.pi * hz * Double(i) / rate)) }
        for _ in 0..<seconds { try out.write(from: buffer) }
    }

    private func back(_ app: XCUIApplication) {
        let b = app.navigationBars.buttons.element(boundBy: 0)
        if b.exists { b.tap() }
    }

    private func listen(_ app: XCUIApplication) {
        app.tabBars.buttons["Listen"].tap()
        for _ in 0..<5 where !app.navigationBars["Listen"].waitForExistence(timeout: 1) { back(app) }
    }

    private func find(_ app: XCUIApplication, _ e: XCUIElement, swipes: Int = 8) -> Bool {
        for _ in 0..<swipes where !(e.exists && e.isHittable) { app.swipeUp() }
        return e.exists
    }

    /// Music, audiobooks, the players, the mini player, LibriVox (stubbed), Media settings, Library and a reader.
    func testWalkOffline() throws {
        _ = try env("GRANGER_WALK")
        let epub = try env("GRANGER_TEST_EPUB")
        let music = try folder("Walk Album")
        for (i, name) in ["Tone A", "Tone B", "Tone C"].enumerated() { try tone(music.appendingPathComponent("\(name).m4a"), seconds: 60, hz: 330 + 110 * Double(i)) }
        // Other pitches and lengths than the songs': a file the same to the byte is one file to the scan.
        let book = try folder("Walk Audiobook")
        try tone(book.appendingPathComponent("01 Opening.m4a"), seconds: 31, hz: 262)
        try tone(book.appendingPathComponent("02 Middle.m4a"), seconds: 62, hz: 294)
        let stub = try folder("Walk Stub")
        try tone(stub.appendingPathComponent("01.m4a"), seconds: 5, hz: 550)

        let app = XCUIApplication()
        let files = ["Tone A", "Tone B", "Tone C"].map { music.appendingPathComponent("\($0).m4a").path }.joined(separator: "|")
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en",
                                                "-debugImportMusic", files, "-debugAudiobookFolder", book.path, "-debugLibriVoxStub", stub.path]
        app.launch()
        sleep(4)
        listen(app)
        sleep(2)
        shot(app, "listen-empty-player")
        app.swipeUp(); shot(app, "listen-scrolled")

        // Music: tabs, album, play, player, queue.
        listen(app)
        let all = app.buttons["listen-all-music"]
        if find(app, all) {
            all.tap()
            let tabs = app.segmentedControls.firstMatch
            if tabs.waitForExistence(timeout: 10) {
                for tab in ["Albums", "Artists", "Songs", "Genres", "Playlists", "Folders"] {
                    if tabs.buttons[tab].exists { tabs.buttons[tab].tap(); sleep(1); shot(app, "music-\(tab.lowercased())") }
                }
                tabs.buttons["Albums"].tap()
            } else { shot(app, "music-no-tabs") }
            let album = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'album-'")).firstMatch
            if album.waitForExistence(timeout: 10) {
                album.tap(); sleep(1); shot(app, "album")
                let play = app.buttons["music-play"]
                if find(app, play, swipes: 3) { play.tap(); sleep(2); shot(app, "album-playing") }
            }
        } else { shot(app, "listen-no-all-music") }
        listen(app); sleep(1); shot(app, "listen-music-card")
        for _ in 0..<4 { app.swipeUp() }
        shot(app, "listen-bottom-with-mini")
        let card = app.otherElements["listen-music-card"]
        if card.waitForExistence(timeout: 5) {
            card.tap()
            if app.staticTexts["player-title"].waitForExistence(timeout: 10) {
                sleep(1); shot(app, "music-player")
                app.swipeUp(); shot(app, "music-player-scrolled")
                let queue = app.buttons["player-queue"]
                if find(app, queue, swipes: 3) { queue.tap(); sleep(2); shot(app, "queue"); app.swipeDown(velocity: .fast); sleep(1) }
            }
        }
        shot(app, "mini-music")

        // Audiobooks: shelf, book, player.
        listen(app)
        let books = app.buttons["listen-all-audiobooks"]
        if find(app, books) {
            books.tap(); sleep(2); shot(app, "audiobook-shelf")
            let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'audiobook-Walk'")).firstMatch
            if row.waitForExistence(timeout: 10) {
                row.tap(); sleep(1); shot(app, "audiobook-page")
                app.swipeUp(); shot(app, "audiobook-page-scrolled")
                app.swipeDown(); app.swipeDown()
                let play = app.buttons["audiobook-play"]
                if find(app, play, swipes: 3) { play.tap(); sleep(2) }
                shot(app, "audiobook-page-playing")
                let mini = app.otherElements["mini-player"]
                if mini.waitForExistence(timeout: 5) {
                    shot(app, "mini-book")
                    mini.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.5)).tap()
                    if app.staticTexts["book-player-chapter"].waitForExistence(timeout: 10) {
                        sleep(1); shot(app, "audiobook-player")
                        app.swipeUp(); shot(app, "audiobook-player-scrolled")
                        app.swipeUp(); shot(app, "audiobook-player-scrolled2")
                        let pp = app.buttons["book-player-play-pause"]
                        if pp.exists && pp.isHittable { pp.tap() }
                    }
                }
            }
        } else { shot(app, "listen-no-all-audiobooks") }

        // Get more audio, LibriVox (the stub).
        listen(app)
        app.buttons["listen-get-more"].tap(); sleep(2); shot(app, "get-more-audio")
        let libri = app.buttons.matching(NSPredicate(format: "label CONTAINS 'LibriVox'")).firstMatch
        if libri.waitForExistence(timeout: 5) {
            libri.tap(); sleep(3); shot(app, "librivox")
            let row = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'librivox-row-'")).firstMatch
            if row.waitForExistence(timeout: 10) { row.tap(); sleep(2); shot(app, "librivox-sheet"); app.swipeUp(); shot(app, "librivox-sheet-scrolled"); app.swipeDown(velocity: .fast); sleep(1) }
        }

        // Media settings.
        listen(app)
        app.buttons["listen-more"].tap()
        let findMusic = app.buttons["Find music on this phone"]
        if findMusic.waitForExistence(timeout: 5) {
            findMusic.tap(); sleep(2); shot(app, "media-settings")
            app.swipeUp(); shot(app, "media-settings-2")
            app.swipeUp(); shot(app, "media-settings-3")
        }

        // Search, Library, a reader.
        app.tabBars.buttons["Search"].tap(); sleep(1); shot(app, "search")
        let field = app.searchFields.firstMatch
        if field.waitForExistence(timeout: 5) { field.tap(); field.typeText("Tone"); sleep(3); shot(app, "search-tone"); let c = app.buttons["Cancel"].firstMatch; if c.exists { c.tap() } else { app.typeText("\n") } }
        app.tabBars.buttons["Explore"].tap(); sleep(3); shot(app, "explore-local-only")
        app.tabBars.buttons["Library"].tap(); sleep(2); shot(app, "library")
        app.terminate()

        let reader = XCUIApplication()
        reader.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en", "-debugBook", epub]
        reader.launch()
        if reader.staticTexts["book-progress"].waitForExistence(timeout: 30) { sleep(2); shot(reader, "reader") }
        reader.tabBars.buttons["Library"].tap(); sleep(2); shot(reader, "library-with-book")
        reader.swipeUp(); shot(reader, "library-with-book-scrolled")
        reader.terminate()
    }

    /// archive.org's spoken word and music, online.
    func testWalkOnline() throws {
        _ = try env("GRANGER_WALK")
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en"]
        app.launch()
        listen(app)
        app.buttons["listen-get-more"].tap(); sleep(2)
        for (label, name) in [("Archive.org audiobooks", "archive-spoken"), ("Archive.org music", "archive-music")] {
            let b = app.buttons.matching(NSPredicate(format: "label CONTAINS %@", label)).firstMatch
            if find(app, b, swipes: 3) {
                b.tap(); sleep(8); shot(app, name)
                let row = app.cells.firstMatch.exists ? app.cells.firstMatch : app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'archive-row'")).firstMatch
                if row.exists { row.tap(); sleep(5); shot(app, name + "-item"); app.swipeDown(velocity: .fast); sleep(1) }
                back(app); sleep(1)
            } else { shot(app, name + "-missing") }
        }
        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        if field.waitForExistence(timeout: 5) { field.tap(); field.typeText("Moon\n"); sleep(6); shot(app, "search-moon") }
    }
}
