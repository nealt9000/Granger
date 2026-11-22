import XCTest

/// Plan I7 Task 6: the UI tests at every text size. At the accessibility sizes a control a test wants is often below
/// the fold, and SwiftUI's lazy lists don't build rows that are off screen, so the element doesn't exist until the list
/// is scrolled to it. `reveal` swipes until it's there and can be tapped, clear of the mini player (iOS 26's bottom
/// accessory, which XCTest reports as hittable through). At the default size it returns at once.
extension XCUIApplication {
    @discardableResult
    func reveal(_ element: XCUIElement, swipes: Int = 12, timeout: TimeInterval = 5, in container: XCUIElement? = nil) -> Bool {
        if element.waitForExistence(timeout: timeout), element.isHittable, clearOfMiniPlayer(element) { return true }
        // The app itself by default, dragged in its lower half: that moves a half-height sheet's list as well as a page,
        // and can't name an element that's gone by the time it lands (a sheet closing).
        let area: XCUIElement = container ?? self
        for _ in 0..<swipes {
            if element.exists && element.isHittable && clearOfMiniPlayer(element) { return true }
            drag(area, from: 0.72, to: 0.36)
        }
        // Past it, or it was above: back the other way.
        for _ in 0..<swipes {
            if element.exists && element.isHittable && clearOfMiniPlayer(element) { return true }
            drag(area, from: 0.36, to: 0.72)
        }
        return element.exists && element.isHittable
    }

    /// A drag, not a flick: the list moves by the drag and stops, so the next check sees where things are.
    private func drag(_ area: XCUIElement, from: CGFloat, to: CGFloat) {
        let start = area.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: from))
        start.press(forDuration: 0.05, thenDragTo: area.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: to)))
        usleep(300_000)
    }

    private func clearOfMiniPlayer(_ element: XCUIElement) -> Bool {
        let mini = descendants(matching: .any)["mini-player"].firstMatch
        guard mini.exists else { return true }
        return element.frame.maxY <= mini.frame.minY - 4 || element.frame.minY >= mini.frame.maxY
    }
}
