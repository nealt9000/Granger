import XCTest

/// Plan I7 Task 3: the app in Spanish, as iOS runs it when Spanish is its language (Settings › Granger › Language).
/// The first test finds Spanish words where they matter; the walk takes a screenshot of every screen into GRANGER_SHOTS
/// (when set) for a person to look at. Pack and book fixtures as the other tests (GRANGER_TEST_ZIM, GRANGER_TEST_EPUB).
final class SpanishUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private static let spanish = ["-AppleLanguages", "(es)", "-AppleLocale", "es_419"]

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private func launch(_ extra: [String], onboarded: Bool = true) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + Self.spanish
            + (onboarded ? ["-onboarding.done", "YES", "-privacySeen", "YES"] : ["-onboarding.done", "NO", "-privacySeen", "NO"])
            + ["-localOnly", "YES", "-wikiLanguage", "es", "-homeHidden", "()"] + extra
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/es-\(name).png"))
    }

    private func text(_ app: XCUIApplication, _ words: String) -> XCUIElement {
        app.descendants(matching: .any).matching(NSPredicate(format: "label == %@", words)).firstMatch
    }

    /// Home, Settings, a reader's bar and Listen say Spanish words.
    func testSpanishOnHomeSettingsReaderAndListen() throws {
        let zim = try env("GRANGER_TEST_ZIM")
        let app = launch(["-debugPack", zim])
        XCTAssertTrue(app.tabBars.buttons["Inicio"].waitForExistence(timeout: 15), "Home's tab in Spanish")
        XCTAssertTrue(app.tabBars.buttons["Explorar"].exists)
        XCTAssertTrue(app.buttons["Ajustes"].firstMatch.waitForExistence(timeout: 5), "Settings' button")
        XCTAssertTrue(app.buttons["Conseguir más"].firstMatch.exists, "Get more's button")

        app.buttons["Ajustes"].firstMatch.tap()
        XCTAssertTrue(text(app, "Apariencia").waitForExistence(timeout: 5), "Settings' Appearance")
        XCTAssertTrue(app.switches["Solo local"].exists, "Local only")
        app.buttons["Listo"].tap()

        app.tabBars.buttons["Escuchar"].tap()
        XCTAssertTrue(app.navigationBars["Escuchar"].waitForExistence(timeout: 10), "Listen's title")
        XCTAssertEqual(app.buttons["listen-get-more"].label, "Conseguir más")
        XCTAssertEqual(app.buttons["listen-more"].label, "Más")

        app.terminate()
        let reader = launch(["-debugPack", zim, "-debugPackReader", "YES"])
        let listen = reader.buttons["reader-listen"]
        XCTAssertTrue(listen.waitForExistence(timeout: 30), "the pack's reader")
        XCTAssertEqual(listen.label, "Escuchar")
        XCTAssertTrue(reader.buttons["Índice"].exists, "Contents in the reader's bar")
        XCTAssertTrue(reader.buttons["Ajustes de texto"].exists, "Text settings in the reader's bar")
    }

    /// Every screen in Spanish, photographed. Asserts only that each screen came; the pictures are for reading.
    func testWalkEveryScreenInSpanish() throws {
        let zim = try env("GRANGER_TEST_ZIM")
        let epub = try env("GRANGER_TEST_EPUB")

        // Onboarding, from a true first run.
        var app = launch([], onboarded: false)
        sleep(3)
        shot(app, "onboarding-1")
        // The longer way, Outfitting's steps, to see each.
        let outfit = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", "Equipar")).firstMatch
        if outfit.waitForExistence(timeout: 5) { outfit.tap(); sleep(2) }
        for i in 2...8 {
            shot(app, "onboarding-\(i)a")
            let next = app.buttons.matching(NSPredicate(format: "label IN %@", ["Siguiente", "Listo", "Terminar", "Empezar", "Comenzar"])).firstMatch
            guard next.waitForExistence(timeout: 5) else { break }
            next.tap()
            sleep(2)
            shot(app, "onboarding-\(i)")
        }
        app.terminate()

        // The tabs, with a pack on the phone and a tone playing (the mini player).
        let audio = ProcessInfo.processInfo.environment["GRANGER_TEST_AUDIO"].map { ["-debugMusicFolder", $0] } ?? []
        app = launch(["-debugPack", zim, "-debugPlay", "tone:600", "-localOnly", "NO"] + audio)
        XCTAssertTrue(app.tabBars.buttons["Inicio"].waitForExistence(timeout: 15))
        sleep(4)
        shot(app, "home")
        app.swipeUp(); sleep(1)
        shot(app, "home-2")

        app.buttons["Conseguir más"].firstMatch.tap()
        sleep(3)
        shot(app, "get-more")
        app.swipeUp(); sleep(1)
        shot(app, "get-more-2")
        app.navigationBars.buttons.element(boundBy: 0).tap()

        app.buttons["Ajustes"].firstMatch.tap()
        sleep(2)
        shot(app, "settings-1")
        for i in 2...5 { app.swipeUp(); sleep(1); shot(app, "settings-\(i)") }
        app.buttons["Listo"].tap()

        app.tabBars.buttons["Buscar"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        shot(app, "search")
        field.tap()
        field.typeText("pan")
        sleep(4)
        field.typeText("\n")
        sleep(1)
        shot(app, "search-results")

        app.tabBars.buttons["Explorar"].tap()
        sleep(4)
        shot(app, "explore")
        app.swipeUp(); sleep(1)
        shot(app, "explore-2")

        app.tabBars.buttons["Biblioteca"].tap()
        sleep(3)
        shot(app, "library")

        app.tabBars.buttons["Escuchar"].tap()
        sleep(2)
        shot(app, "listen")
        if app.buttons["listen-all-music"].waitForExistence(timeout: 5) {
            app.buttons["listen-all-music"].tap(); sleep(2); shot(app, "music")
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        if app.buttons["listen-all-audiobooks"].waitForExistence(timeout: 5) {
            app.buttons["listen-all-audiobooks"].tap(); sleep(2); shot(app, "audiobooks")
            app.navigationBars.buttons.element(boundBy: 0).tap()
        }
        if app.buttons["listen-get-more"].waitForExistence(timeout: 5) {
            app.buttons["listen-get-more"].tap(); sleep(2); shot(app, "get-more-audio")
        }
        app.terminate()

        // A pack's page in the reader.
        app = launch(["-debugPack", zim, "-debugPackReader", "YES"])
        XCTAssertTrue(app.buttons["reader-listen"].waitForExistence(timeout: 30))
        sleep(2)
        shot(app, "reader-pack")
        app.buttons["Ajustes de texto"].tap(); sleep(2)
        shot(app, "reader-pack-text-settings")
        app.terminate()

        // An e-book in the reader.
        app = launch(["-debugBook", epub, "-reading.theme", "APP"])
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens")
        sleep(3)
        shot(app, "reader-epub")
        if app.buttons["Ajustes de texto"].waitForExistence(timeout: 5) {
            app.buttons["Ajustes de texto"].tap(); sleep(2); shot(app, "reader-epub-text-settings")
            app.swipeDown(velocity: .fast); sleep(1)
        }
        if app.buttons["Índice"].waitForExistence(timeout: 5) {
            app.buttons["Índice"].tap(); sleep(2); shot(app, "reader-epub-contents")
        }
        app.terminate()

        // Read aloud: the mini player and Listen's Reading part, then Settings › Voice.
        guard let voice = ProcessInfo.processInfo.environment["GRANGER_TEST_VOICE"], !voice.isEmpty else { return }
        app = launch(["-debugVoice", voice, "-debugBook", epub, "-reading.theme", "APP"])
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens")
        let listen = app.buttons["book-listen"]
        if !listen.waitForExistence(timeout: 5) { app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap() }
        XCTAssertTrue(listen.waitForExistence(timeout: 10), "Listen in the book's bar")
        listen.tap()
        let pause = app.buttons["mini-play-pause"]
        XCTAssertTrue(pause.waitForExistence(timeout: 30), "the mini player")
        sleep(4)
        shot(app, "mini-player-reading")
        pause.tap(); sleep(1)
        shot(app, "mini-player-paused")
        app.tabBars.buttons["Escuchar"].tap(); sleep(2)
        shot(app, "listen-reading")
        // Home's stack holds the book: a second tap on its tab goes back to Home.
        app.tabBars.buttons["Inicio"].tap(); sleep(1)
        app.tabBars.buttons["Inicio"].tap()
        XCTAssertTrue(app.buttons["Ajustes"].firstMatch.waitForExistence(timeout: 5), "Home's Settings")
        app.buttons["Ajustes"].firstMatch.tap()
        let voiceRow = app.descendants(matching: .any)["settings-voice"].firstMatch
        XCTAssertTrue(app.reveal(voiceRow), "Settings' Voice row")
        voiceRow.tap(); sleep(2)
        shot(app, "settings-voice")
    }
}
