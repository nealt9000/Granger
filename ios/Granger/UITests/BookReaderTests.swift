import XCTest

/// Plan I5 Task 5: the e-book and PDF readers on Readium Swift. A book comes in through `-debugBook` (imported into the
/// shelf at launch and opened). Uses GRANGER_TEST_EPUB (Pride and Prejudice) and GRANGER_TEST_PDF; skipped without them.
final class BookReaderTests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private func launch(_ book: String) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en",
                               "-reading.font_scale", "1.0", "-reading.theme", "APP", "-debugBook", book]
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        }
    }

    /// The bars, shown: a tap in the middle of the page brings them back when a turn or a jump hid them.
    private func showBars(_ app: XCUIApplication) {
        let label = app.staticTexts["book-progress"]
        if label.exists && label.isHittable { return }
        // A tap while a sheet is still going away can be lost: tried again.
        for _ in 0..<3 {
            app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap()
            if label.waitForExistence(timeout: 3) { return }
        }
        XCTFail("the bars come back")
    }

    private func progress(_ app: XCUIApplication) -> String {
        showBars(app)
        return app.staticTexts["book-progress"].label
    }

    /// Waits until the progress label says something with [text] in it (the page count may still be coming).
    private func waitForProgress(_ app: XCUIApplication, containing text: String, timeout: TimeInterval = 15) {
        let label = app.staticTexts["book-progress"]
        let p = NSPredicate(format: "label CONTAINS %@", text)
        expectation(for: p, evaluatedWith: label)
        waitForExpectations(timeout: timeout)
    }

    private func openContents(_ app: XCUIApplication) {
        showBars(app)
        app.buttons["Contents"].tap()
    }

    /// Opens, turns a page, jumps from Contents, changes the text size, highlights a word and finds it first in search,
    /// adds a note and deletes it, searches the book's text, and reopens at the same place.
    func testEpubReader() throws {
        let epub = try env("GRANGER_TEST_EPUB")
        let app = launch(epub)
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens with its bars")
        waitForProgress(app, containing: "p. ")
        shot(app, "book-open")

        // Contents: jump to Chapter IV; the bars slide away for the page.
        openContents(app)
        let chapter = app.buttons["Chapter IV"].firstMatch
        XCTAssertTrue(app.reveal(chapter, timeout: 10), "Contents lists the chapters")
        chapter.tap()
        XCTAssertTrue(app.staticTexts["book-progress"].waitForNonExistence(timeout: 5), "the bars go for the page")
        showBars(app)
        waitForProgress(app, containing: "Chapter IV")
        let before = progress(app)

        // A page turn: a swipe moves on a page, and the label follows.
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.45)).tap()
        app.webViews.firstMatch.swipeLeft()
        sleep(1)
        let after = progress(app)
        XCTAssertNotEqual(before, after, "the page turned")
        shot(app, "book-turned")

        // Text size: Aa, larger, 113 %.
        app.buttons["Text settings"].tap()
        let size = app.staticTexts["text-size"]
        XCTAssertTrue(size.waitForExistence(timeout: 5))
        XCTAssertEqual(size.label, "100%")
        app.buttons["Larger text"].tap()
        XCTAssertTrue(app.staticTexts["113%"].waitForExistence(timeout: 5), "the size went up a step")
        shot(app, "book-text-settings")
        app.swipeDown(velocity: .fast)
        sleep(1)

        // Highlight a word: press on the page's text, then Highlight in the selection menu.
        let word = selectWord(app)
        tapMenu(app, "Highlight")
        sleep(1)
        openContents(app)
        app.buttons["Highlights"].tap()
        let row = app.buttons["highlight-row"].firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 5), "the highlight is listed")
        let highlighted = word ?? (row.label.components(separatedBy: " ").first ?? "").trimmingCharacters(in: .punctuationCharacters)
        app.buttons["Done"].tap()

        // Search: the highlight comes first, then the text.
        showBars(app)
        app.buttons["Search in book"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.typeText(highlighted + "\n")
        let own = app.buttons["search-own-mark"].firstMatch
        XCTAssertTrue(own.waitForExistence(timeout: 20), "your highlight is found (\(highlighted))")
        let hits = app.buttons.matching(NSPredicate(format: "identifier == 'search-own-mark' OR identifier == 'search-hit'"))
        XCTAssertEqual(hits.firstMatch.identifier, "search-own-mark", "your highlight comes first")
        shot(app, "book-search-own-first")
        // The row opens its place, and the panel closes.
        own.tap()
        XCTAssertTrue(own.waitForNonExistence(timeout: 5), "the panel closed")

        // A note: a new highlight with its note, then deleted from its sheet.
        _ = selectWord(app, at: CGVector(dx: 0.5, dy: 0.6))
        tapMenu(app, "Note")
        let note = app.textFields["highlight-note"].firstMatch
        XCTAssertTrue(note.waitForExistence(timeout: 5), "the note sheet opens")
        note.typeText("Remember this")
        shot(app, "book-note")
        app.buttons["Done"].tap()
        openContents(app)
        app.buttons["Highlights"].tap()
        XCTAssertTrue(app.staticTexts["Remember this"].waitForExistence(timeout: 5), "the note is listed")
        app.buttons["Done"].tap()
        // Tapping the highlight on the page opens its sheet; Delete takes it away.
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.6)).tap()
        let delete = app.buttons["Delete"]
        XCTAssertTrue(delete.waitForExistence(timeout: 5), "the highlight's sheet")
        delete.tap()
        sleep(1)
        openContents(app)
        app.buttons["Highlights"].tap()
        XCTAssertFalse(app.staticTexts["Remember this"].waitForExistence(timeout: 2), "the note is gone")
        app.buttons["Done"].tap()

        // Search the text: a common word, found in context.
        showBars(app)
        app.buttons["Search in book"].tap()
        let field2 = app.searchFields.firstMatch
        XCTAssertTrue(field2.waitForExistence(timeout: 5))
        if let clear = field2.buttons["Clear text"].firstMatch as XCUIElement?, clear.exists { clear.tap() }
        field2.typeText("Netherfield\n")
        let hit = app.buttons["search-hit"].firstMatch
        XCTAssertTrue(hit.waitForExistence(timeout: 20), "the text is found")
        hit.tap()
        sleep(2)
        let place = progress(app)
        shot(app, "book-search-hit")

        // Reopen: the same place.
        sleep(2)
        XCUIDevice.shared.press(.home)
        sleep(1)
        app.terminate()
        let again = launch(epub)
        XCTAssertTrue(again.staticTexts["book-progress"].waitForExistence(timeout: 30))
        waitForProgress(again, containing: place)
    }

    /// Plan I7 Task 7a: Search's Best matches list a book's passage (Android's: the books' index too), as a book row
    /// with the sentence and its collection; tapped, the book opens at that passage, not at its saved place.
    func testBestMatchOpensTheBookAtItsPassage() throws {
        let epub = try env("GRANGER_TEST_EPUB")
        let app = launch(epub)
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens (imported and indexed)")
        waitForProgress(app, containing: "p. ")
        XCTAssertFalse(progress(app).contains("Chapter XXXIV"), "it opens at its start")
        openBestMatch(app, query: "In vain have I struggled", expecting: "Pride")
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens")
        showBars(app)
        waitForProgress(app, containing: "Chapter XXXIV", timeout: 20)
        shot(app, "best-match-book-passage")
    }

    /// Plan I7 Task 7c: Search's Books group lists the hits inside books (Android's: one row per book, its author and how
    /// many places matched, the snippet); tapped, the book opens at the hit.
    func testBooksGroupOpensTheBookAtItsHit() throws {
        let epub = try env("GRANGER_TEST_EPUB")
        let app = launch(epub)
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens (imported and indexed)")
        let row = app.buttons.matching(identifier: "search-books-hit").firstMatch
        searchFromBook(app, "In vain have I struggled", until: row)
        XCTAssertTrue(row.exists, "a hit inside the book under Books")
        XCTAssertTrue(app.reveal(row))
        XCTAssertTrue(app.staticTexts["Books"].exists, "the Books group")
        XCTAssertTrue(row.label.contains("Pride and Prejudice"), row.label)
        XCTAssertTrue(row.label.contains("struggled"), "the snippet: \(row.label)")
        XCTAssertTrue(app.searchFields.firstMatch.value as? String == "In vain have I struggled", "the words as typed")
        shot(app, "search-books-group")
        row.tap()
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens")
        showBars(app)
        waitForProgress(app, containing: "Chapter XXXIV", timeout: 20)
        shot(app, "search-books-opened")
    }

    /// Plan I7 Task 7c: a typed search whose words find nothing offers the library name it most likely meant (Android's
    /// typed "Did you mean"); tapped, that name is searched.
    func testTypedSearchThatFindsNothingOffersDidYouMean() throws {
        let epub = try env("GRANGER_TEST_EPUB")
        let app = launch(epub)
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens (imported and indexed)")
        let offer = app.buttons["search-did-you-mean"]
        searchFromBook(app, "Pryde and Prejudise", until: offer)
        XCTAssertTrue(offer.exists, "Did you mean")
        XCTAssertEqual(offer.label, "Did you mean Pride and Prejudice?")
        XCTAssertTrue(app.staticTexts["Nothing in your library for that. Try fewer words."].exists, "the empty state under it")
        shot(app, "search-did-you-mean")
        offer.tap()
        XCTAssertTrue(app.reveal(app.staticTexts["Titles"], timeout: 20), "the name searched finds the book")
        XCTAssertEqual(app.searchFields.firstMatch.value as? String, "Pride and Prejudice")
        XCTAssertFalse(offer.exists, "the offer goes")
        shot(app, "search-did-you-mean-taken")
    }

    /// From the open book: Search, [query] typed until [element] shows (the words are indexed just after the import).
    private func searchFromBook(_ app: XCUIApplication, _ query: String, until element: XCUIElement) {
        let tab = app.tabBars.buttons["Search"]
        if !tab.isHittable { showBars(app) }
        if !tab.isHittable { app.navigationBars.buttons.firstMatch.tap() }
        XCTAssertTrue(tab.waitForExistence(timeout: 5))
        tab.tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        for attempt in 0..<4 {
            field.tap()
            if attempt > 0, let typed = field.value as? String { field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: typed.count)) }
            field.typeText(query)
            if element.waitForExistence(timeout: 20) { break }
        }
    }

    /// From the open book: Search, [query], and the first book row in Best matches (titled with [expecting]) tapped.
    /// The book's words are indexed just after its import, so the search is tried again until they are.
    private func openBestMatch(_ app: XCUIApplication, query: String, expecting title: String) {
        let tab = app.tabBars.buttons["Search"]
        if !tab.isHittable { showBars(app) }
        if !tab.isHittable { app.navigationBars.buttons.firstMatch.tap() }
        XCTAssertTrue(tab.waitForExistence(timeout: 5))
        tab.tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        let row = app.buttons.matching(identifier: "search-book-hit").firstMatch
        for attempt in 0..<4 {
            field.tap()
            if attempt > 0, let typed = field.value as? String { field.typeText(String(repeating: XCUIKeyboardKey.delete.rawValue, count: typed.count)) }
            field.typeText(query)
            if row.waitForExistence(timeout: 20) { break }
        }
        XCTAssertTrue(row.exists, "a book's passage in Best matches")
        XCTAssertTrue(row.label.contains(title), row.label)
        shot(app, "best-match-book-row")
        XCTAssertTrue(app.reveal(row))
        row.tap()
    }

    /// A PDF opens with its outline in Contents, jumps to a chapter's page, and reopens at that page.
    func testPdfReaderReopensAtItsPage() throws {
        let pdf = try env("GRANGER_TEST_PDF")
        let app = launch(pdf)
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the PDF opens with its bars")
        waitForProgress(app, containing: "of 6")
        openContents(app)
        let chapter = app.buttons["The Clove Hitch"].firstMatch
        XCTAssertTrue(chapter.waitForExistence(timeout: 10), "the PDF's outline")
        chapter.tap()
        showBars(app)
        waitForProgress(app, containing: "p. 5 of 6")
        shot(app, "pdf-page")
        sleep(2)
        XCUIDevice.shared.press(.home)
        sleep(1)
        app.terminate()
        let again = launch(pdf)
        XCTAssertTrue(again.staticTexts["book-progress"].waitForExistence(timeout: 30))
        waitForProgress(again, containing: "p. 5 of 6")
    }

    /// A book pack's PDF (A Library of Knots, a nautilus pack) opens in the PDF reader, copied out of the pack, where
    /// Quick Look showed it before.
    func testPackBookOpensInTheReader() throws {
        let zim = try env("GRANGER_TEST_BOOKPACK")
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en", "-debugPack", zim]
        app.launch()
        app.tabBars.buttons["Explore"].tap()
        let sources = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Your sources'")).firstMatch
        for _ in 0..<12 where !sources.isHittable { app.swipeUp() }
        sources.tap()
        let pack = app.buttons["source-book-pack"].firstMatch
        XCTAssertTrue(pack.waitForExistence(timeout: 20), "the book pack under Books")
        pack.tap()
        let book = app.buttons.matching(NSPredicate(format: "label CONTAINS 'PDF'")).firstMatch
        XCTAssertTrue(book.waitForExistence(timeout: 20), "its books")
        book.tap()
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), "the book opens in Granger's reader")
        waitForProgress(app, containing: "p. ")
        shot(app, "pack-book-reader")
    }

    /// Presses on the page's text to select a word; its text when the accessibility tree says.
    @discardableResult
    private func selectWord(_ app: XCUIApplication, at offset: CGVector = CGVector(dx: 0.5, dy: 0.4)) -> String? {
        let page = app.webViews.firstMatch
        XCTAssertTrue(page.waitForExistence(timeout: 10))
        page.coordinate(withNormalizedOffset: offset).press(forDuration: 1.2)
        return nil
    }

    private func tapMenu(_ app: XCUIApplication, _ title: String) {
        var item = app.menuItems[title].exists ? app.menuItems[title] : app.buttons[title].firstMatch
        // At the larger text sizes the edit menu holds fewer items a page: its forward arrow shows the rest.
        for _ in 0..<4 where !item.waitForExistence(timeout: 2) {
            let more = ["Next Page", "Show more items", "Forward"].map { app.buttons[$0].firstMatch }.first { $0.exists }
                ?? app.menuItems.matching(NSPredicate(format: "label IN %@", ["Next Page", "Show more items", "Forward"])).firstMatch
            guard more.exists else { break }
            more.tap()
            item = app.menuItems[title].exists ? app.menuItems[title] : app.buttons[title].firstMatch
        }
        if !item.waitForExistence(timeout: 3) {
            print(app.debugDescription)
            XCTFail("the selection menu's \(title)")
        }
        item.tap()
    }
}
