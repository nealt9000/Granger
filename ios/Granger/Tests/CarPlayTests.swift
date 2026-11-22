import CarPlay
import GrangerCoreKit
import XCTest
@testable import Granger

/// Plan I6 Task 8: CarPlay's templates from the shared browse tree, built in the host app (CarPlay itself connects only
/// in the CarPlay Simulator, and shows the app only once Apple grants the CarPlay audio entitlement).
@MainActor
final class CarPlayTests: XCTestCase {
    func testTheTabsAndTheMusicFolderComeFromTheSharedTree() async throws {
        let center = try XCTUnwrap(VoiceCenter.shared, "the host app's player")
        let browse = center.system.browse { key in ScreenText.strings[key] ?? key }
        let root = try await browse.children(parentId: MediaBrowse.companion.ROOT, page: 0, pageSize: 50) ?? []
        XCTAssertEqual(root.map(\.title), ["Recent", "Music", "Audiobooks"])
        let music = try await browse.children(parentId: MediaBrowse.companion.MUSIC, page: 0, pageSize: 50) ?? []
        XCTAssertEqual(music.map(\.title), ["Albums", "Artists", "Playlists"])
        XCTAssertTrue(music.allSatisfy(\.browsable))

        let tabs = CarPlayScene().makeRoot()
        XCTAssertEqual(tabs.templates.map { ($0 as? CPListTemplate)?.title }, ["Recent", "Music", "Audiobooks"])
        let musicTab = try XCTUnwrap(tabs.templates[1] as? CPListTemplate)
        for _ in 0..<50 where musicTab.itemCount < 3 { try await Task.sleep(for: .milliseconds(100)) }
        let rows = musicTab.sections.flatMap(\.items).compactMap { $0 as? CPListItem }
        XCTAssertEqual(rows.map(\.text), ["Albums", "Artists", "Playlists"])
        XCTAssertTrue(rows.allSatisfy { $0.accessoryType == .disclosureIndicator })
    }
}
