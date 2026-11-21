import ReadiumNavigator
import ReadiumShared
import ReadiumStreamer
import UIKit
import XCTest

/// Plan I5 Task 5's spike: Readium's Swift toolkit opens a Granger Classics EPUB, goes to a place Android stored (the
/// same locator JSON in `book_positions`), draws a highlight and finds a word. The EPUB comes from GRANGER_TEST_EPUB
/// (pass TEST_RUNNER_GRANGER_TEST_EPUB to xcodebuild); without it the test is skipped.
@MainActor
final class ReadiumSpikeTests: XCTestCase, EPUBNavigatorDelegate {
    /// Pride and Prejudice's place as the Pixel 6 stored it (Readium Kotlin 3.4).
    private let androidLocator = #"{"href":"OEBPS\/c005.xhtml","type":"application\/xhtml+xml","title":"He rode a black horse. CHAPTER III.","locations":{"progression":0.125,"position":23,"totalProgression":0.056265984654731455}}"#
    private var landed: XCTestExpectation?
    private var landedAt: Locator?

    private func epub() throws -> URL {
        guard let path = ProcessInfo.processInfo.environment["GRANGER_TEST_EPUB"], !path.isEmpty else { throw XCTSkip("no GRANGER_TEST_EPUB") }
        return URL(fileURLWithPath: path)
    }

    private func open(_ url: URL) async throws -> Publication {
        let http = DefaultHTTPClient()
        let retriever = AssetRetriever(httpClient: http)
        let opener = PublicationOpener(parser: DefaultPublicationParser(httpClient: http, assetRetriever: retriever, pdfFactory: DefaultPDFDocumentFactory()))
        let asset = try await retriever.retrieve(url: XCTUnwrap(FileURL(url: url))).get()
        return try await opener.open(asset: asset, allowUserInteraction: false).get()
    }

    func testOpensGoesToAndroidPlaceHighlightsAndSearches() async throws {
        let started = Date()
        let publication = try await open(try epub())
        XCTAssertTrue(publication.metadata.title?.contains("Pride") ?? false)
        let positions = try await publication.positions().get()
        XCTAssertGreaterThan(positions.count, 100)

        // Android's stored locator decodes, and names a resource in this book's reading order.
        let locator = try Locator(jsonString: androidLocator)
        XCTAssertEqual(locator.locations.position, 23)
        XCTAssertNotNil(publication.linkWithHREF(locator.href), "the href resolves")

        // The navigator opens there, and says where it landed.
        let navigator = try EPUBNavigatorViewController(publication: publication, initialLocation: locator)
        navigator.delegate = self
        landed = expectation(description: "location")
        let window = UIWindow(frame: CGRect(x: 0, y: 0, width: 390, height: 844))
        window.rootViewController = navigator
        window.makeKeyAndVisible()
        await fulfillment(of: [landed!], timeout: 20)
        let at = try XCTUnwrap(landedAt)
        XCTAssertEqual(at.href.string, locator.href.string, "lands in Android's chapter")
        XCTAssertEqual(at.locations.position ?? 0, 23, accuracy: 2, "near Android's position")

        // A highlight decoration is accepted at that place.
        let highlight = Decoration(id: "spike", locator: at, style: .highlight(tint: .yellow))
        navigator.apply(decorations: [highlight], in: "highlights")

        // Readium's search finds a word, with text around it.
        let iterator = try await publication.search(query: "Netherfield").get()
        let page = try await iterator.next().get()
        let hit = try XCTUnwrap(page?.locators.first)
        XCTAssertEqual(hit.text.highlight?.lowercased(), "netherfield")
        XCTAssertNotNil(hit.text.before)
        // Its JSON is what the shared store keeps, and reads back the same.
        let json = try hit.jsonString()
        XCTAssertEqual(try Locator(jsonString: json).href.string, hit.href.string)
        print("ReadiumSpike: \(Date().timeIntervalSince(started))s, landed at \(at.href) position \(at.locations.position ?? -1)")
        window.isHidden = true
    }

    nonisolated func navigator(_ navigator: Navigator, locationDidChange locator: Locator) {
        MainActor.assumeIsolated {
            guard landedAt == nil else { return }
            landedAt = locator
            landed?.fulfill()
        }
    }

    nonisolated func navigator(_ navigator: Navigator, presentError error: NavigatorError) {}
}
