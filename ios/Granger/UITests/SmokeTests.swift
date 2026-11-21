import XCTest

/// The walkthrough the app must always pass (Plan I2 Task 7 grows it with each plan). Needs the internet (Wikipedia).
/// Each test pins the settings it relies on through launch arguments (they shadow what a previous test saved).
final class SmokeTests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    func testLaunchesWithTabs() {
        let app = XCUIApplication()
        app.launchArguments = ["-localOnly", "NO"]
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Home"].waitForExistence(timeout: 10))
        XCTAssertTrue(app.tabBars.buttons["Search"].exists)
    }

    /// Topic page → full article → Contents → a section → a link in the page → its topic page → back twice.
    func testTopicReaderContentsAndLinks() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en", "-debugOpen", "en:Moon"]
        app.launch()
        let read = app.buttons["read-full"]
        XCTAssertTrue(read.waitForExistence(timeout: 20), "topic page")
        read.tap()

        let contents = app.buttons["Contents"]
        XCTAssertTrue(contents.waitForExistence(timeout: 10))
        let enabled = NSPredicate(format: "isEnabled == true")
        expectation(for: enabled, evaluatedWith: contents)
        waitForExpectations(timeout: 20)
        contents.tap()
        // The sheet's list (the article has the same words as a heading).
        let orbit = app.collectionViews.buttons["Classification"].firstMatch
        XCTAssertTrue(orbit.waitForExistence(timeout: 5), "Contents lists the sections")
        orbit.tap()

        let link = app.webViews.links["Earth"].firstMatch
        XCTAssertTrue(link.waitForExistence(timeout: 10), "a link in the page")
        link.tap()
        XCTAssertTrue(app.staticTexts["Earth"].waitForExistence(timeout: 20), "the linked article's topic page")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["Contents"].waitForExistence(timeout: 5), "back in the reader")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        XCTAssertTrue(app.buttons["read-full"].waitForExistence(timeout: 5), "back on the topic page")
    }

    /// Search online: type, see Titles and Articles, open a result's topic page, find the search under Recent.
    func testSearchOpensATopic() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en"]
        app.launch()
        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("Mount Everest")
        XCTAssertTrue(app.staticTexts["Titles"].waitForExistence(timeout: 20), "the Titles group")
        XCTAssertTrue(app.staticTexts["Articles"].exists, "the Articles group")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Mount Everest'")).firstMatch.tap()
        XCTAssertTrue(app.buttons["read-full"].waitForExistence(timeout: 20), "its topic page")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        field.buttons["Clear text"].firstMatch.tap()
        XCTAssertTrue(app.staticTexts["Recent"].waitForExistence(timeout: 5), "remembered under Recent")
    }

    /// First run: nothing is fetched until the privacy line's OK; Settings' Local only stops Search going online.
    func testPrivacyLineAndLocalOnly() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "NO", "-localOnly", "NO", "-wikiLanguage", "en", "-homeOrder", "continue,readiness,timeline,feeds", "-homeHidden", "()"]
        app.launch()
        let ok = app.buttons["privacy-ok"]
        XCTAssertTrue(ok.waitForExistence(timeout: 10), "the privacy line on first run")
        ok.tap()
        XCTAssertTrue(app.buttons["full-timeline"].waitForExistence(timeout: 60), "Home's timeline after OK")

        app.buttons["Settings"].firstMatch.tap()
        let toggle = app.switches["Local only"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.switches.firstMatch.tap()
        app.buttons["Done"].tap()

        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        // Words no test pack holds (the pack test leaves a cooking pack in Documents/Packs).
        field.typeText("Jovian magnetosphere")
        // Packs still answer with Local only on (Plan I4); online results wait for a connection.
        XCTAssertTrue(app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", "Online results appear when you're connected")).firstMatch.waitForExistence(timeout: 10), "Local only")
    }

    /// Keep (Plan I3 Task 6): read an article, keep it, see it marked on this phone; with Local only on it still opens,
    /// summary and page, from the phone; Remove takes it off again.
    func testKeepAndLocalOnly() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en", "-debugOpen", "en:Moon"]
        app.launch()
        if app.buttons["keep-remove"].waitForExistence(timeout: 20) { app.buttons["keep-remove"].tap() }
        let keep = app.buttons["keep"]
        XCTAssertTrue(keep.waitForExistence(timeout: 20), "the Keep button")
        keep.tap()
        XCTAssertTrue(app.buttons["keep-remove"].waitForExistence(timeout: 60), "kept")
        XCTAssertTrue(app.otherElements["mark-on-phone"].waitForExistence(timeout: 10) || app.images["mark-on-phone"].exists
            || app.descendants(matching: .any)["mark-on-phone"].exists, "marked on this phone")

        app.terminate()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en", "-debugOpen", "en:Moon"]
        app.launch()
        let read = app.buttons["read-full"]
        XCTAssertTrue(read.waitForExistence(timeout: 20), "the topic page opens with Local only on")
        XCTAssertTrue(app.buttons["keep-remove"].exists, "still kept")
        read.tap()
        XCTAssertTrue(app.webViews.staticTexts["Moon"].firstMatch.waitForExistence(timeout: 20), "the kept page reads offline")
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/kept-offline.png"))
        }
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["keep-remove"].tap()
        XCTAssertTrue(app.buttons["keep"].waitForExistence(timeout: 10), "removed")
    }

    /// The whole topic page (Plan I3 Task 7): trail, years, their time, more in the room, closest topics.
    func testTopicPageWhole() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en", "-debugOpen", "en:Archimedes"]
        app.launch()
        XCTAssertTrue(app.staticTexts["287 BC – 212 BC · aged 75"].waitForExistence(timeout: 30), "years from the time index")
        shot(app, "topic-1")
        let time = app.staticTexts["Their time"]
        for _ in 0..<10 where !time.isHittable { app.scrollViews.firstMatch.swipeUp(velocity: .slow) }
        XCTAssertTrue(time.waitForExistence(timeout: 20), "their time")
        shot(app, "topic-2")
        let more = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'More in '")).firstMatch
        for _ in 0..<14 where !(more.exists && more.isHittable) { app.scrollViews.firstMatch.swipeUp(velocity: .slow) }
        XCTAssertTrue(more.waitForExistence(timeout: 20), "more in the room")
        shot(app, "topic-3")
        let closest = app.staticTexts["Closest topics"]
        for _ in 0..<14 where !(closest.exists && closest.isHittable) { app.scrollViews.firstMatch.swipeUp(velocity: .slow) }
        XCTAssertTrue(closest.waitForExistence(timeout: 20), "closest topics")
        app.swipeUp()
        shot(app, "topic-4")
    }

    /// Home (Plan I3 Task 8): the timeline card, a tap on the river selects a mark, Full timeline, Arrange Home hides it.
    func testHomeTimelineAndArrange() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en", "-homeOrder", "continue,readiness,timeline,feeds", "-homeHidden", "()"]
        app.launch()
        let full = app.buttons["full-timeline"]
        XCTAssertTrue(full.waitForExistence(timeout: 60), "the timeline card")
        XCTAssertTrue(app.buttons["timeline-caption"].waitForExistence(timeout: 30), "a mark selected under the river")
        shot(app, "home-1")
        full.tap()
        XCTAssertTrue(app.otherElements["timeline-river"].waitForExistence(timeout: 30) || app.descendants(matching: .any)["timeline-river"].exists, "the full timeline")
        sleep(3)
        shot(app, "timeline-full")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["Arrange Home"].tap()
        let toggle = app.switches["arrange-timeline"]
        XCTAssertTrue(toggle.waitForExistence(timeout: 5))
        toggle.switches.firstMatch.tap()
        app.buttons["arrange-done"].tap()
        XCTAssertFalse(app.buttons["full-timeline"].waitForExistence(timeout: 3), "hidden from Home")
    }

    /// Explore (Plan I3 Task 9): the landing, a subject, a room, a topic; Wander; a round of a game.
    func testExploreWalk() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en"]
        app.launch()
        app.tabBars.buttons["Explore"].tap()
        let spread = app.buttons["open-spread"]
        XCTAssertTrue(spread.waitForExistence(timeout: 60), "today's spread")
        shot(app, "explore-1")
        let door = app.buttons["door-science"]
        for _ in 0..<8 where !(door.exists && door.isHittable) { app.swipeUp() }
        shot(app, "explore-2")
        door.tap()
        let room = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'room-'")).firstMatch
        XCTAssertTrue(room.waitForExistence(timeout: 30), "the subject's rooms")
        shot(app, "subject-1")
        for _ in 0..<6 where !room.isHittable { app.swipeUp() }
        room.tap()
        XCTAssertTrue(app.buttons["view-Plates"].waitForExistence(timeout: 30), "the room")
        sleep(3)
        shot(app, "room-1")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.navigationBars.buttons.element(boundBy: 0).tap()
        // Ways on: Wander, then Games.
        let wander = app.buttons["way-Wander"]
        for _ in 0..<12 where !(wander.exists && wander.isHittable) { app.swipeUp() }
        shot(app, "explore-3")
        wander.tap()
        XCTAssertTrue(app.buttons["wander-open"].waitForExistence(timeout: 90), "Wander's centre")
        shot(app, "wander-1")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        app.buttons["way-Games"].tap()
        let game = app.buttons["game-BEFORE_AFTER"]
        XCTAssertTrue(game.waitForExistence(timeout: 20), "the games")
        game.tap()
        let before = app.buttons["Before"]
        XCTAssertTrue(before.waitForExistence(timeout: 30), "a round")
        shot(app, "game-1")
        before.tap()
        XCTAssertTrue(app.buttons["game-next"].waitForExistence(timeout: 5), "revealed")
        shot(app, "game-2")
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        // TEST_RUNNER_GRANGER_SHOTS=<dir> on the xcodebuild line saves the walkthrough's screenshots.
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        }
    }

    /// Landscape: the topic page and the reader lay out and stay usable sideways.
    func testLandscape() {
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en", "-debugOpen", "en:Moon"]
        app.launch()
        XCUIDevice.shared.orientation = .landscapeLeft
        defer { XCUIDevice.shared.orientation = .portrait }
        let read = app.buttons["read-full"]
        XCTAssertTrue(read.waitForExistence(timeout: 20))
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/landscape-topic.png"))
        }
        read.tap()
        XCTAssertTrue(app.buttons["Contents"].waitForExistence(timeout: 10))
        sleep(UInt32(ProcessInfo.processInfo.environment["GRANGER_HOLD"].flatMap(UInt32.init) ?? 3))
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/landscape-reader.png"))
        }
    }

    /// Plan I4 Task 5: a pack in Documents/Packs (copied in by `-debugPack`) is searched by title and full text with
    /// no signal, its article opens on a topic page and in the reader. Skipped without TEST_RUNNER_GRANGER_TEST_ZIM.
    func testPackSearchTopicAndReader() throws {
        guard let zim = ProcessInfo.processInfo.environment["GRANGER_TEST_ZIM"], !zim.isEmpty else { throw XCTSkip("no GRANGER_TEST_ZIM") }
        let app = XCUIApplication()
        app.launchArguments = ["-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en", "-debugPack", zim]
        app.launch()
        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("bread")
        XCTAssertTrue(app.staticTexts["Titles"].waitForExistence(timeout: 20), "pack titles with Local only on")
        XCTAssertTrue(app.staticTexts["Articles"].waitForExistence(timeout: 20), "pack full text")
        shot(app, "pack-search")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Bread'")).firstMatch.tap()
        let read = app.buttons["read-full"]
        XCTAssertTrue(read.waitForExistence(timeout: 20), "the pack article's topic page")
        XCTAssertFalse(app.buttons["keep"].exists, "no Keep for a pack article")
        shot(app, "pack-topic")
        read.tap()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 20), "the reader")
        XCTAssertTrue(app.webViews.staticTexts.matching(NSPredicate(format: "label CONTAINS[c] 'bread'")).firstMatch.waitForExistence(timeout: 20), "the article's text from the pack")
        shot(app, "pack-reader")
    }
}
