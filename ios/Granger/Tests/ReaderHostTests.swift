import XCTest
@testable import Granger

/// The web host's routing without the Kotlin core: a fake URL parser and a fake page source.
final class ReaderHostTests: XCTestCase {
    override func setUp() {
        GrangerCoreBridge.parser = { url in
            guard url.hasPrefix("granger-zim://zim/") else { return nil }
            let rest = String(url.dropFirst("granger-zim://zim/".count))
            let noFrag = rest.split(separator: "#", maxSplits: 1).map(String.init)
            let parts = noFrag[0].split(separator: "/", maxSplits: 1).map(String.init)
            guard parts.count == 2 else { return nil }
            return (parts[0], parts[1].removingPercentEncoding ?? parts[1], noFrag.count > 1 ? noFrag[1] : nil)
        }
    }

    private func action(_ s: String) -> LinkAction {
        LinkAction.of(URL(string: s)!, currentPack: "wikipedia_en_online", currentPath: "Moon")
    }

    func testLinks() {
        XCTAssertEqual(action("granger-zim://zim/wikipedia_en_online/Earth"), .article(pack: "wikipedia_en_online", path: "Earth", fragment: nil))
        XCTAssertEqual(action("granger-zim://zim/wikipedia_en_online/Earth#Orbit"), .article(pack: "wikipedia_en_online", path: "Earth", fragment: "Orbit"))
        XCTAssertEqual(action("granger-zim://zim/wikipedia_en_online/Moon#Orbit"), .load)
        XCTAssertEqual(action("granger-zim://zim/wikipedia_es_online/Luna"), .ignore)
        XCTAssertEqual(action("granger-zim://zim/_granger/online.css"), .ignore)
        XCTAssertEqual(action("https://example.org/"), .external(URL(string: "https://example.org/")!))
        XCTAssertEqual(action("mailto:a@b.c"), .system(URL(string: "mailto:a@b.c")!))
        XCTAssertEqual(action("javascript:alert(1)"), .ignore)
        XCTAssertEqual(action("file:///etc/passwd"), .ignore)
    }

    func testMimeSplit() {
        XCTAssertEqual(ZimSchemeHandler.split("text/html; charset=utf-8").0, "text/html")
        XCTAssertEqual(ZimSchemeHandler.split("text/html; charset=utf-8").1, "utf-8")
        XCTAssertEqual(ZimSchemeHandler.split("image/png").1, nil)
        XCTAssertEqual(ZimSchemeHandler.split("text/css").1, "utf-8")
    }
}
