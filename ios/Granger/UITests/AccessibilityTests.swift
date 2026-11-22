import XCTest

/// Plan I7 Task 6 (call 9): VoiceOver by the accessibility tree. Each main screen goes through Xcode's accessibility
/// audit for elements VoiceOver can't describe (an icon-only button or an image with no label) and for text cut short
/// at the current text size. It reads the tree XCTest sees, as VoiceOver's rotor does; it can't listen. With
/// GRANGER_SHOTS set, every issue found (clipping included, which doesn't fail the test) is written to
/// `accessibility-<size>.txt` there, and each screen's screenshot beside it.
final class AccessibilityTests: XCTestCase {
    override func setUp() { continueAfterFailure = true; CleanData.renew() }

    private var found: [String] = []
    private var undescribed: [String] = []

    private func audit(_ app: XCUIApplication, _ screen: String) {
        sleep(2)
        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
            try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/a11y-\(screen).png"))
        }
        try? app.performAccessibilityAudit(for: [.sufficientElementDescription, .textClipped]) { issue in
            let element = issue.element.map { "\($0.elementType.rawValue) id=\($0.identifier) label=\($0.label)" } ?? "?"
            let line = "\(screen): \(issue.auditType == .sufficientElementDescription ? "no description" : "clipped"): \(issue.compactDescription) [\(element)]"
            self.found.append(line)
            // A link with no words is the article's own HTML (Wikipedia's or a pack's, an image link), drawn by the web
            // view as on Android; it's listed, not failed.
            let webLink = issue.element?.elementType == .link && (issue.element?.label.isEmpty ?? true)
            if issue.auditType == .sufficientElementDescription && !webLink { self.undescribed.append(line) }
            return true
        }
    }

    private func back(_ app: XCUIApplication) {
        let b = app.navigationBars.buttons.element(boundBy: 0)
        if b.exists { b.tap() }
    }

    func testEveryMainScreenIsDescribed() throws {
        let app = XCUIApplication()
        var args = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "NO", "-wikiLanguage", "en"]
        if let zim = ProcessInfo.processInfo.environment["GRANGER_TEST_ZIM"], !zim.isEmpty { args += ["-debugPack", zim] }
        app.launchArguments = args
        app.launch()
        // GRANGER_LANDSCAPE=1: the same walk sideways, for the landscape screenshots.
        if ProcessInfo.processInfo.environment["GRANGER_LANDSCAPE"] == "1" { XCUIDevice.shared.orientation = .landscapeLeft }
        defer { XCUIDevice.shared.orientation = .portrait }
        XCTAssertTrue(app.tabBars.buttons["Home"].waitForExistence(timeout: 15))
        sleep(4)
        audit(app, "home")

        app.tabBars.buttons["Search"].tap()
        let field = app.searchFields.firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 10))
        field.tap()
        field.typeText("bread\n")
        _ = app.staticTexts["Titles"].waitForExistence(timeout: 30)
        sleep(4)
        audit(app, "search")

        app.tabBars.buttons["Explore"].tap()
        _ = app.buttons["open-spread"].waitForExistence(timeout: 60)
        audit(app, "explore")
        app.buttons["open-spread"].tap()
        _ = app.buttons["read-full"].waitForExistence(timeout: 30)
        audit(app, "topic")
        if app.buttons["read-full"].exists {
            app.buttons["read-full"].tap()
            _ = app.buttons["Contents"].waitForExistence(timeout: 20)
            audit(app, "reader")
        }

        app.tabBars.buttons["Library"].tap()
        sleep(2)
        audit(app, "library")

        app.tabBars.buttons["Listen"].tap()
        sleep(2)
        audit(app, "listen")

        app.tabBars.buttons["Home"].tap()
        let gear = app.navigationBars.buttons["Settings"]
        for _ in 0..<5 where !gear.exists { back(app) }
        if gear.waitForExistence(timeout: 5) {
            gear.tap()
            _ = app.buttons["Done"].waitForExistence(timeout: 5)
            audit(app, "settings")
            app.buttons["Done"].tap()
        }
        let getMore = app.navigationBars.buttons["Get more"]
        if getMore.waitForExistence(timeout: 5) {
            getMore.tap()
            sleep(4)
            audit(app, "get-more")
        }

        if let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] {
            let size = (ProcessInfo.processInfo.environment["GRANGER_TEXT_SIZE"] ?? "default")
                + (ProcessInfo.processInfo.environment["GRANGER_LANDSCAPE"] == "1" ? "-landscape" : "")
            try? (found.joined(separator: "\n") + "\n").write(toFile: dir + "/accessibility-\(size).txt", atomically: true, encoding: .utf8)
        }
        XCTAssertEqual(undescribed, [], "elements VoiceOver can't describe")
    }
}
