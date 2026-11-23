import AVFoundation
import XCTest

/// Plan I7 Task 7b: what Search shows of Android's last three features. A one-word query's Define card from the test
/// Wiktionary (GRANGER_TEST_SEARCH), and an album's media card in Best matches that opens the album at its song (the
/// gapless pair from GRANGER_TEST_AUDIO, imported as from Files). Skipped without the fixtures.
final class SearchExtrasUITests: XCTestCase {
    override func setUp() { continueAfterFailure = false; CleanData.renew() }

    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    private func launch(_ extra: [String]) -> XCUIApplication {
        let app = XCUIApplication()
        app.launchArguments = CleanData.args + ["-onboarding.done", "YES", "-privacySeen", "YES", "-localOnly", "YES", "-wikiLanguage", "en"] + extra
        app.launch()
        return app
    }

    private func shot(_ app: XCUIApplication, _ name: String) {
        guard let dir = ProcessInfo.processInfo.environment["GRANGER_SHOTS"] else { return }
        try? FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        try? app.screenshot().pngRepresentation.write(to: URL(fileURLWithPath: dir + "/\(name).png"))
    }

    /// Search, [query] typed until [element] shows (the library is still being read just after launch).
    private func search(_ app: XCUIApplication, _ query: String, until element: XCUIElement) {
        let tab = app.tabBars.buttons["Search"]
        XCTAssertTrue(tab.waitForExistence(timeout: 10))
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

    /// "betimes" shows Android's Define card above the results: Define, the word, its senses (not its etymology) and
    /// the Wiktionary it's from; tapped, the entry opens in the reader.
    func testDefineCardShowsTheWordsEntry() throws {
        let zim = try env("GRANGER_TEST_SEARCH") + "/wiktionary-test.zim"
        guard FileManager.default.fileExists(atPath: zim) else { throw XCTSkip("no \(zim)") }
        let app = launch(["-debugPack", zim])
        let card = app.buttons["search-define"]
        search(app, "betimes", until: card)
        XCTAssertTrue(card.exists, "the Define card")
        shot(app, "define-card")
        let label = card.label
        XCTAssertTrue(label.contains("Define"), label)
        XCTAssertTrue(label.contains("betimes"), label)
        XCTAssertTrue(label.contains("early, especially in the morning"), label)
        XCTAssertFalse(label.contains("Middle English betymes"), "the etymology is left out: \(label)")
        XCTAssertTrue(label.contains("Dictionary entry from Wiktionary test"), label)
        XCTAssertTrue(app.reveal(card))
        card.tap()
        XCTAssertTrue(app.webViews.firstMatch.waitForExistence(timeout: 15), "the entry opens in the reader")
        let sense = app.webViews.staticTexts.containing(NSPredicate(format: "label CONTAINS %@", "especially in the morning")).firstMatch
        XCTAssertTrue(sense.waitForExistence(timeout: 15), "the entry's words in the reader")
        shot(app, "define-opened")
    }

    /// Two imported songs make the album "Gapless Test"; "gapless" finds its media card in Best matches (its line the
    /// sentence that matched, "Your music"), and the card opens the album with the song it's about marked.
    func testMediaCardOpensTheAlbumAtItsSong() throws {
        let dir = try env("GRANGER_TEST_AUDIO")
        let files = ["gapless-1.mp3", "gapless-2.mp3"].map { "\(dir)/\($0)" }
        guard files.allSatisfy(FileManager.default.fileExists(atPath:)) else { throw XCTSkip("no testdata/audio") }
        let app = launch(["-debugImportMusic", files.joined(separator: "|")])
        let row = app.buttons.matching(identifier: "search-media-hit").firstMatch
        search(app, "gapless", until: row)
        XCTAssertTrue(row.exists, "the album's media card in Best matches")
        XCTAssertTrue(app.staticTexts["Best matches"].exists, "under Best matches")
        shot(app, "media-card")
        let label = row.label
        XCTAssertTrue(label.contains("Gapless Test"), label)
        XCTAssertTrue(label.contains("Gapless 1"), "the songs on the card: \(label)")
        XCTAssertTrue(label.contains("Your music"), label)
        XCTAssertTrue(app.reveal(row))
        row.tap()
        XCTAssertTrue(app.buttons["song-Gapless 1"].waitForExistence(timeout: 10), "the album opens with its song")
        // The card is about the first song (its title holds "gapless" first): it's tinted for two seconds.
        shot(app, "media-card-album")
        XCTAssertTrue(app.descendants(matching: .any)["album-root"].firstMatch.exists, "the album's page")
    }

    // MARK: - Plan I7 Task 7c: the rest of Android's Search on the iPhone

    /// The imported gapless pair (album "Gapless Test") and an audiobook folder named "Gapless Tales": "gapless" lists the
    /// Music group (the album, its songs) and the Audiobooks group, with Android's lines; the By kind chips (All, Articles
    /// for the media card in Best matches, Media) narrow the results, and the album row opens the album.
    func testMusicAndAudiobookGroupsWithByKind() throws {
        let dir = try env("GRANGER_TEST_AUDIO")
        let files = ["gapless-1.mp3", "gapless-2.mp3"].map { "\(dir)/\($0)" }
        guard files.allSatisfy(FileManager.default.fileExists(atPath:)) else { throw XCTSkip("no testdata/audio") }
        let book = try audiobook("Gapless Tales")
        let app = launch(["-debugImportMusic", files.joined(separator: "|"), "-debugAudiobookFolder", book.path])
        let album = app.buttons.matching(identifier: "search-album").firstMatch
        search(app, "gapless", until: album)
        XCTAssertTrue(app.reveal(album), "the album under Music")
        XCTAssertTrue(app.staticTexts["Music"].exists, "the Music group")
        XCTAssertTrue(album.label.contains("Gapless Test"), album.label)
        XCTAssertTrue(album.label.contains("Album · "), "Android's album line: \(album.label)")
        let song = app.buttons.matching(identifier: "search-song").firstMatch
        XCTAssertTrue(app.reveal(song), "a song")
        XCTAssertTrue(song.label.contains("Song · "), song.label)
        let tales = app.buttons.matching(identifier: "search-audiobook").firstMatch
        XCTAssertTrue(app.reveal(tales), "the audiobook under Audiobooks")
        XCTAssertTrue(app.staticTexts["Audiobooks"].exists, "the Audiobooks group")
        XCTAssertTrue(tales.label.contains("Gapless Tales"), tales.label)
        shot(app, "search-music-audiobooks")

        // By kind: the media card counts as Articles (as on Android), the albums and books as Media.
        let all = app.buttons["search-filter-all"], media = app.buttons["search-filter-media"], articles = app.buttons["search-filter-articles"]
        XCTAssertTrue(app.reveal(all), "the chips")
        XCTAssertTrue(media.exists && articles.exists, "All, Articles and Media")
        XCTAssertFalse(app.buttons["search-filter-books"].exists, "no Books: nothing of the kind was found")
        media.tap()
        XCTAssertTrue(app.reveal(album), "Media keeps Music")
        XCTAssertFalse(app.staticTexts["Best matches"].exists, "Media leaves Best matches out")
        XCTAssertTrue(media.isSelected, "the chip picked")
        shot(app, "search-filter-media")
        XCTAssertTrue(app.reveal(articles))
        articles.tap()
        XCTAssertTrue(app.reveal(app.buttons.matching(identifier: "search-media-hit").firstMatch), "Articles keeps the media card")
        XCTAssertFalse(app.staticTexts["Music"].exists, "Articles leaves Music out")
        shot(app, "search-filter-articles")
        XCTAssertTrue(app.reveal(all))
        all.tap()
        XCTAssertTrue(app.reveal(album))
        album.tap()
        XCTAssertTrue(app.buttons["song-Gapless 1"].waitForExistence(timeout: 10), "the album opens")
    }

    /// A media card's own icon (Listen's headphones, named Music for VoiceOver) and its place in words.
    func testMediaCardHasTheHeadphones() throws {
        let dir = try env("GRANGER_TEST_AUDIO")
        let files = ["gapless-1.mp3", "gapless-2.mp3"].map { "\(dir)/\($0)" }
        guard files.allSatisfy(FileManager.default.fileExists(atPath:)) else { throw XCTSkip("no testdata/audio") }
        let app = launch(["-debugImportMusic", files.joined(separator: "|")])
        let row = app.buttons.matching(identifier: "search-media-hit").firstMatch
        search(app, "gapless", until: row)
        XCTAssertTrue(app.reveal(row))
        XCTAssertTrue(row.label.contains("Music"), "the kind read out: \(row.label)")
        XCTAssertTrue(row.label.contains("Your music"), row.label)
        XCTAssertTrue(app.images["search-kind-music"].exists || app.descendants(matching: .any)["search-kind-music"].exists, "the headphones")
        shot(app, "media-card-icon")
    }

    /// A medical query's results carry Ask's banner (Android's, Plan SQ Decision 8); and while Best matches are looked for
    /// (held back by `-debugBestDelay`), "Looking through your library…" stands under their heading.
    func testMedicalBannerAndLookingLine() throws {
        let zim = try env("GRANGER_TEST_ZIM")
        let app = launch(["-debugPack", zim, "-debugBestDelay", "6000"])
        let looking = app.staticTexts["search-best-looking"]
        search(app, "bread burns", until: looking)
        XCTAssertTrue(looking.exists, "Looking… while Best matches load")
        XCTAssertEqual(looking.label, "Looking through your library…")
        XCTAssertTrue(app.staticTexts["Best matches"].exists, "under their heading")
        let banner = app.staticTexts["search-medical"]
        XCTAssertTrue(banner.waitForExistence(timeout: 10), "the medical banner")
        XCTAssertTrue(banner.label.hasPrefix("Not medical advice."), banner.label)
        shot(app, "search-medical-looking")
        let best = app.buttons.matching(NSPredicate(format: "identifier == 'search-hit' OR identifier == 'search-book-hit'")).firstMatch
        XCTAssertTrue(best.waitForExistence(timeout: 30), "the matches come")
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: looking)
        wait(for: [gone], timeout: 30)
        shot(app, "search-medical-matches")
    }

    /// Two slices of one set on the phone (the test catalog names them so): Home offers to remove the smaller, as
    /// Android's tidy-up; Keep both is remembered after a relaunch.
    func testTidyOfferKeepBothIsRemembered() throws {
        let (app, offer) = try launchWithSlices()
        let keep = offer.buttons["tidy-keep"]
        XCTAssertEqual(keep.label, "Keep both")
        keep.tap()
        XCTAssertTrue(waitGone(offer), "the offer goes")
        app.terminate()
        app.launch()
        XCTAssertTrue(app.tabBars.buttons["Home"].waitForExistence(timeout: 10))
        XCTAssertFalse(app.otherElements["tidy-offer"].waitForExistence(timeout: 8), "Keep both is remembered")
    }

    /// Remove: the smaller slice gives way to the bigger, and the offer goes.
    func testTidyOfferRemovesTheSmallerSlice() throws {
        let (app, offer) = try launchWithSlices()
        let remove = offer.buttons["tidy-remove"]
        XCTAssertEqual(remove.label, "Remove Small slice")
        remove.tap()
        XCTAssertTrue(waitGone(offer), "the offer goes once the smaller slice is removed")
        // Search finds Lighthouse in the big slice alone: no other copy under its title.
        let hit = app.buttons.matching(NSPredicate(format: "identifier == 'search-hit' AND label BEGINSWITH 'Lighthouse'")).firstMatch
        search(app, "lighthouse", until: hit)
        XCTAssertTrue(hit.exists, "Lighthouse")
        XCTAssertFalse(app.staticTexts["search-also-in"].exists, "one copy left")
        shot(app, "tidy-removed-search")
    }

    /// The two test slices installed with a catalog naming them a set; Home's tidy offer, checked and returned.
    private func launchWithSlices() throws -> (XCUIApplication, XCUIElement) {
        let base = try env("GRANGER_TEST_SEARCH")
        let small = base + "/slice-small.zim", big = base + "/slice-big.zim"
        guard FileManager.default.fileExists(atPath: small), FileManager.default.fileExists(atPath: big) else { throw XCTSkip("no slices") }
        func entry(_ id: String, _ path: String, _ zimName: String, rank: Int, label: String) throws -> String {
            """
            {"id": "\(id)", "title": "\(id)", "category": "test", "language": "eng", "sizeBytes": 1, "sha256": "\(String(repeating: "0", count: 64))",
             "urls": ["https://example.org/\(id).zim"], "snapshotDate": "2026-11-23", "license": "CC0", "hasFulltextIndex": true,
             "zimUuid": "\(try zimUuid(path))", "zimName": "\(zimName)", "set": "granger_testset", "setRank": \(rank), "setLabel": "\(label)"}
            """
        }
        let json = """
        {"formatVersion": 1, "generatedAt": "2026-11-23T00:00:00Z", "packs": [
          \(try entry("t7c-small", small, "granger_testset_small", rank: 1, label: "Small slice articles")),
          \(try entry("t7c-big", big, "granger_testset_big", rank: 2, label: "Big slice articles"))
        ]}
        """
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("t7c-catalog-\(UUID().uuidString.prefix(6)).json")
        try json.write(to: file, atomically: true, encoding: .utf8)
        let app = launch(["-debugPack", "\(small)|\(big)", "-debugCatalog", file.path])
        let offer = app.otherElements["tidy-offer"]
        XCTAssertTrue(offer.waitForExistence(timeout: 30), "Home's tidy offer")
        let text = offer.staticTexts.firstMatch.label
        XCTAssertTrue(text.hasPrefix("Big slice is in. Remove Small slice ("), text)
        XCTAssertTrue(text.hasSuffix("Your places and bookmarks move across."), text)
        shot(app, "tidy-offer")
        return (app, offer)
    }

    /// A ZIM's uuid as libzim prints it (the pack's id): the header's bytes 8–24.
    private func zimUuid(_ path: String) throws -> String {
        let handle = try XCTUnwrap(FileHandle(forReadingAtPath: path))
        defer { try? handle.close() }
        try handle.seek(toOffset: 8)
        let bytes = try XCTUnwrap(try handle.read(upToCount: 16))
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        let i = hex.startIndex
        func part(_ from: Int, _ to: Int) -> String { String(hex[hex.index(i, offsetBy: from)..<hex.index(i, offsetBy: to)]) }
        return [part(0, 8), part(8, 12), part(12, 16), part(16, 20), part(20, 32)].joined(separator: "-")
    }

    private func waitGone(_ element: XCUIElement, timeout: TimeInterval = 15) -> Bool {
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: element)
        return XCTWaiter().wait(for: [gone], timeout: timeout) == .completed
    }

    /// An audiobook folder named [name] (the book takes the folder's name): two chapters of a quiet tone.
    private func audiobook(_ name: String) throws -> URL {
        let parent = FileManager.default.temporaryDirectory.appendingPathComponent("t7c-\(UUID().uuidString.prefix(6))", isDirectory: true)
        let dir = parent.appendingPathComponent(name, isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        for (i, chapter) in ["01 Opening.m4a", "02 Middle.m4a"].enumerated() {
            let rate = 22050.0
            let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: 1, AVEncoderBitRateKey: 32000]
            let out = try AVAudioFile(forWriting: dir.appendingPathComponent(chapter), settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
            let buffer = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: out.processingFormat, frameCapacity: AVAudioFrameCount(rate)))
            buffer.frameLength = AVAudioFrameCount(rate)
            for j in 0..<Int(rate) { buffer.floatChannelData![0][j] = 0.2 * Float(sin(2 * Double.pi * Double(330 + 110 * i) * Double(j) / rate)) }
            for _ in 0..<5 { try out.write(from: buffer) }
        }
        return dir
    }
}
