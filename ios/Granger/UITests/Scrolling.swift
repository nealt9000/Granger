import XCTest

/// Plan I7 Task 6: the UI tests at every text size. At the accessibility sizes a control a test wants is often below
/// the fold, and SwiftUI's lazy lists don't build rows that are off screen, so the element doesn't exist until the list
/// is scrolled to it. `reveal` swipes until it's there and can be tapped, clear of the mini player (iOS 26's bottom
/// accessory, which XCTest reports as hittable through). At the default size it returns at once.
extension XCUIApplication {
    @discardableResult
    func reveal(_ element: XCUIElement, swipes: Int = 20, timeout: TimeInterval = 5, in container: XCUIElement? = nil) -> Bool {
        if element.waitForExistence(timeout: timeout), element.isHittable, clearOfMiniPlayer(element) { return true }
        // The app itself by default, dragged in its lower half: that moves a half-height sheet's list as well as a page,
        // and can't name an element that's gone by the time it lands (a sheet closing).
        let area: XCUIElement = container ?? self
        // Built but above the screen: back down first. Otherwise down the page, then back past where it started.
        let above = element.exists && element.frame.maxY < windows.firstMatch.frame.midY
        for (direction, count) in (above ? [(false, swipes), (true, swipes * 2)] : [(true, swipes), (false, swipes * 2)]) {
            for _ in 0..<count {
                if element.exists && element.isHittable && clearOfMiniPlayer(element) { return true }
                if direction { drag(area, from: 0.78, to: 0.24) } else { drag(area, from: 0.24, to: 0.78) }
            }
        }
        return element.exists && element.isHittable
    }

    /// A drag, not a flick: the list moves by the drag and stops, so the next check sees where things are.
    /// Near the right edge, past the rows' cards, so it never starts on a slider, yet inside a sheet that floats inset
    /// from the screen's edges; above the keyboard when one is up.
    private func drag(_ area: XCUIElement, from: CGFloat, to: CGFloat) {
        let keyboard = keyboards.firstMatch.exists
        let (a, b) = keyboard ? (from > to ? (0.5, 0.15) : (0.15, 0.5)) : (from, to)
        let start = area.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: a))
        start.press(forDuration: 0.05, thenDragTo: area.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: b)))
        usleep(300_000)
    }

    private func clearOfMiniPlayer(_ element: XCUIElement) -> Bool {
        let mini = descendants(matching: .any)["mini-player"].firstMatch
        // Under a sheet the mini player isn't on top of anything.
        guard mini.exists, mini.isHittable else { return true }
        return element.frame.maxY <= mini.frame.minY - 4 || element.frame.minY >= mini.frame.maxY
    }
}
