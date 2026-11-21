import XCTest

/// Plan I5 Task 6: the Library tab. Books come in through `-debugBook` (imported and opened at launch) and
/// `-debugImport` (imported as a pick from Files would be, through the shared FileImports: UI tests can't drive the
/// Files picker). Uses GRANGER_TEST_EPUB, GRANGER_TEST_PDF and the Alice EPUB beside them; skipped without them.
final class LibraryUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false }

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private func alice() throws -> String {
        let epub = try env("GRANGER_TEST_EPUB")
        let path = (epub as NSString).deletingLastPathComponent + "/gc-alice-s-adventures-in-wonderland_2026-11-09.epub"
        guard FileManager.default.fileExists(atPath: path) else { throw XCTSkip("no Alice EPUB beside \(epub)") }
        return path
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en"] + extra
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
        }
    }

    private func id(_ prefix: String, label: String? = nil) -> NSPredicate {
        if let label { return NSPredicate(format: "identifier BEGINSWITH %@ AND label CONTAINS %@", prefix, label) }
        return NSPredicate(format: "identifier BEGINSWITH %@", prefix)
    }

    /// The Library tab, at its top.
    private func library(_ app: XCUIApplication) {
        let tab = app.tabBars.buttons["Library"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10), "the Library tab")
        tab.tap()
        // Back to the tab's top.
        for _ in 0..<5 where !app.navigationBars["Library"].waitForExistence(timeout: 2) {
            let back = app.navigationBars.buttons.element(boundBy: 0)
            if back.exists { back.tap() }
        }
        XCTAssertTrue(app.navigationBars["Library"].waitForExistence(timeout: 10), "the Library's top")
    }

    private func openStack(_ app: XCUIApplication, containing name: String) {
        let stack = app.buttons.matching(id("stack-", label: name)).firstMatch
        for _ in 0..<6 where !stack.waitForExistence(timeout: 2) { app.swipeUp() }
        XCTAssertTrue(stack.waitForExistence(timeout: 10), "the \(name) collection")
        bringIntoView(stack, app)
        stack.tap()
    }

    /// The stacks scroll sideways: one past the screen's edge is dragged into view (other tests leave more collections).
    private func bringIntoView(_ stack: XCUIElement, _ app: XCUIApplication) {
        let window = app.windows.firstMatch
        for _ in 0..<10 where stack.frame.maxX > window.frame.maxX {
            let y = stack.frame.midY / max(window.frame.height, 1)
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: y)).press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.25, dy: y)))
        }
        for _ in 0..<10 where stack.frame.minX < window.frame.minX {
            let y = stack.frame.midY / max(window.frame.height, 1)
            window.coordinate(withNormalizedOffset: CGVector(dx: 0.15, dy: y)).press(forDuration: 0.05, thenDragTo: window.coordinate(withNormalizedOffset: CGVector(dx: 0.75, dy: y)))
        }
    }

    /// Opens whichever collection holds a book titled with [title] (a run cut short may have left it in another) and
    /// finds it there; its row.
    @discardableResult
    private func openCollectionHolding(_ app: XCUIApplication, _ title: String) -> XCUIElement {
        let stacks = app.buttons.matching(id("stack-"))
        XCTAssertTrue(stacks.firstMatch.waitForExistence(timeout: 15), "the collections")
        let labels = (0..<stacks.count).map { stacks.element(boundBy: $0).label }
        for label in labels.sorted(by: { a, _ in a.contains("My books") }) {
            let stack = app.buttons.matching(NSPredicate(format: "identifier BEGINSWITH 'stack-' AND label == %@", label)).firstMatch
            guard stack.exists else { continue }
            bringIntoView(stack, app)
            stack.tap()
            let find = app.textFields["collection-find"]
            if find.waitForExistence(timeout: 10) {
                find.tap()
                find.typeText(title)
                let row = app.buttons.matching(id("book-", label: title)).firstMatch
                if row.waitForExistence(timeout: 3) { return row }
            }
            library(app)
        }
        XCTFail("no collection holds \(title)")
        return app.buttons["none"]
    }

    private func waitForReader(_ app: XCUIApplication, _ why: String) {
        XCTAssertTrue(app.staticTexts["book-progress"].waitForExistence(timeout: 30), why)
    }

    private func back(_ app: XCUIApplication) {
        let button = app.navigationBars.buttons.element(boundBy: 0)
        if button.waitForExistence(timeout: 3) { button.tap() }
    }

    /// A book read once is under Reading now, with its collection as a stack; it opens from both.
    func testReadingNowAndCollectionsOpenTheBook() throws {
        let epub = try env("GRANGER_TEST_EPUB")
        let app = launch(["-debugBook", epub])
        waitForReader(app, "the book opens")
        sleep(2) // its place is saved a second after it settles
        library(app)
        XCTAssertTrue(app.descendants(matching: .any)["library-reading-now"].waitForExistence(timeout: 15), "Reading now")
        let reading = app.buttons.matching(id("reading-", label: "Pride and Prejudice")).firstMatch
        XCTAssertTrue(reading.waitForExistence(timeout: 10), "the book under Reading now")
        XCTAssertTrue(app.buttons.matching(id("stack-")).firstMatch.exists, "the collections as stacks")
        shot(app, "library")

        reading.tap()
        waitForReader(app, "opened from Reading now")
        back(app)

        openStack(app, containing: "My books")
        let find = app.textFields["collection-find"]
        XCTAssertTrue(find.waitForExistence(timeout: 10), "the collection's books")
        find.tap()
        find.typeText("Pride")
        let row = app.buttons.matching(id("book-", label: "Pride and Prejudice")).firstMatch
        XCTAssertTrue(row.waitForExistence(timeout: 10), "found by title")
        shot(app, "collection")
        row.tap()
        waitForReader(app, "opened from its collection")
    }

    /// Import from Files (through the same shared import as a pick): Alice joins the shelf in My books, and the import's
    /// words say so.
    func testImportAnEpub() throws {
        let path = try alice()
        let app = launch(["-debugImport", path])
        let notice = app.staticTexts["debug-import-notice"]
        XCTAssertTrue(notice.waitForExistence(timeout: 30), "the import's words")
        XCTAssertTrue(notice.label.contains("Imported 1 book.") || notice.label.contains("already"), notice.label)
        library(app)
        XCTAssertTrue(openCollectionHolding(app, "Alice").exists, "Alice on the shelf")
    }

    /// A book moved to a new collection is there, in a list and in a grid; deleting the collection puts it back.
    func testMoveABookToANewCollectionAndSwitchToTheGrid() throws {
        let path = try alice()
        let app = launch(["-debugImport", path])
        XCTAssertTrue(app.staticTexts["debug-import-notice"].waitForExistence(timeout: 30))
        library(app)
        let row = openCollectionHolding(app, "Alice")
        row.press(forDuration: 1.2)
        let move = app.buttons["Move to collection…"]
        XCTAssertTrue(move.waitForExistence(timeout: 5), "the book's menu")
        move.tap()
        let new = app.buttons.matching(id("move-new-")).firstMatch
        XCTAssertTrue(new.waitForExistence(timeout: 5), "New collection…")
        new.tap()
        let name = "Shelf \(Int(Date().timeIntervalSince1970) % 100_000)"
        let field = app.alerts.textFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5), "its name")
        field.typeText(name)
        app.alerts.buttons["Move"].tap()
        sleep(1)

        library(app)
        openStack(app, containing: name)
        let moved = app.buttons.matching(id("book-", label: "Alice")).firstMatch
        XCTAssertTrue(moved.waitForExistence(timeout: 10), "Alice in \(name)")

        // List and grid.
        app.buttons["collection-view"].tap()
        XCTAssertTrue(app.buttons.matching(id("cell-", label: "Alice")).firstMatch.waitForExistence(timeout: 5), "the cover grid")
        shot(app, "collection-grid")
        app.buttons["collection-view"].tap()
        XCTAssertTrue(app.buttons.matching(id("book-", label: "Alice")).firstMatch.waitForExistence(timeout: 5), "the list again")

        // Delete the collection: Alice goes back to My books.
        app.buttons["collection-more"].tap()
        app.buttons["Delete"].tap()
        app.alerts.buttons["Delete"].tap()
        library(app)
        openStack(app, containing: "My books")
        let again = app.textFields["collection-find"]
        XCTAssertTrue(again.waitForExistence(timeout: 10))
        again.tap()
        again.typeText("Alice")
        XCTAssertTrue(app.buttons.matching(id("book-", label: "Alice")).firstMatch.waitForExistence(timeout: 10), "back in My books")
    }

    /// Recently open lists a book read lately, with its place, and opens it there.
    func testRecentlyOpenShowsTheBook() throws {
        let pdf = try env("GRANGER_TEST_PDF")
        let app = launch(["-debugBook", pdf])
        waitForReader(app, "the PDF opens")
        sleep(2)
        library(app)
        let menu = app.buttons["recently-open"].firstMatch
        XCTAssertTrue(menu.waitForExistence(timeout: 10), "Recently open")
        menu.tap()
        let book = app.buttons.matching(NSPredicate(format: "label CONTAINS 'Field Notes'")).firstMatch
        XCTAssertTrue(book.waitForExistence(timeout: 5), "the book under Recently open")
        shot(app, "recently-open-book")
        book.tap()
        waitForReader(app, "it opens from Recently open")
    }
}
