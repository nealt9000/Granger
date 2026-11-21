import GrangerCoreKit
import PDFKit
import UIKit
import XCTest
@testable import Granger

/// Plan I5 Task 6: comics (CBZ through the shared zip reader, CBR through libarchive) become PDFs of their pages with
/// UIGraphicsPDFRenderer; Markdown becomes chapters on the iPhone by Android's rules; a book whose container moved is
/// found again under today's Documents/Books. The comic fixtures are made here: pictures drawn, archives stored.
final class LibraryTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("library-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        ComicNatives.install()
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    /// Three pages, named so a plain sort gets them wrong (p1, p2, p10), each its own size; and what archives carry
    /// that isn't a page.
    private func pages() -> [(String, Data)] {
        [
            ("comic/p10.jpg", picture(80, 110, .systemBlue, jpeg: true)),
            ("comic/p2.png", picture(70, 100, .systemRed, jpeg: false)),
            ("comic/p1.jpg", picture(60, 90, .systemGreen, jpeg: true)),
            ("__MACOSX/comic/._p1.jpg", Data("junk".utf8)),
            ("comic/notes.txt", Data("not a page".utf8)),
        ]
    }

    private func picture(_ w: Int, _ h: Int, _ colour: UIColor, jpeg: Bool) -> Data {
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let image = UIGraphicsImageRenderer(size: CGSize(width: w, height: h), format: format).image { c in
            colour.setFill()
            c.fill(CGRect(x: 0, y: 0, width: w, height: h))
        }
        return jpeg ? image.jpegData(compressionQuality: 0.8)! : image.pngData()!
    }

    private func assertComicPdf(_ archive: URL, file: StaticString = #filePath, line: UInt = #line) {
        let out = dir.appendingPathComponent("out-\(UUID().uuidString).pdf")
        XCTAssertTrue(ComicToPdf.shared.convert(archive: archive.path, out: out.path), "converted", file: file, line: line)
        guard let doc = PDFDocument(url: out) else { return XCTFail("a PDF", file: file, line: line) }
        XCTAssertEqual(doc.pageCount, 3, "one page per picture, nothing else", file: file, line: line)
        let sizes = (0..<doc.pageCount).map { doc.page(at: $0)!.bounds(for: .mediaBox).size }
        XCTAssertEqual(sizes, [CGSize(width: 60, height: 90), CGSize(width: 70, height: 100), CGSize(width: 80, height: 110)],
                       "in page order, each at its picture's size", file: file, line: line)
    }

    func testCbzBecomesAPdfOfItsPages() throws {
        let cbz = dir.appendingPathComponent("comic.cbz")
        try StoredArchives.zip(pages()).write(to: cbz)
        assertComicPdf(cbz)
    }

    func testCbrBecomesAPdfThroughLibarchive() throws {
        let cbr = dir.appendingPathComponent("comic.cbr")
        try StoredArchives.rar(pages()).write(to: cbr)
        assertComicPdf(cbr)
        // A RAR that isn't one (damaged after its marker) is refused, not crashed on.
        let bad = dir.appendingPathComponent("bad.cbr")
        try (Data("Rar!\u{1A}\u{07}\u{00}".utf8) + Data(repeating: 7, count: 64)).write(to: bad)
        XCTAssertFalse(ComicToPdf.shared.convert(archive: bad.path, out: dir.appendingPathComponent("bad.pdf").path))
    }

    /// A CBZ picked in Files joins the shelf as a PDF of its pages, through the shared import.
    func testComicImportsAsAPdfBook() async throws {
        let core = try XCTUnwrap(Core.shared, "the host app's core")
        let cbz = dir.appendingPathComponent("Test Comic \(UUID().uuidString.prefix(6)).cbz")
        var entries = pages()
        entries.append(("comic/p11.jpg", picture(50, 50, UIColor(white: CGFloat.random(in: 0...1), alpha: 1), jpeg: true)))
        try StoredArchives.zip(entries).write(to: cbz)
        let summary = try await core.library.importPicked(files: [core.library.pickedFile(path: cbz.path, name: cbz.lastPathComponent)])
        XCTAssertEqual(summary.imported, 1, summary.text().words)
        let books = try await core.library.books.first(where: { _ in true }) ?? []
        let title = cbz.deletingPathExtension().lastPathComponent
        let book = try XCTUnwrap(books.first { $0.title == title && $0.format == .pdf }, "on the shelf as \(title)")
        XCTAssertEqual(PDFDocument(url: URL(fileURLWithPath: book.filePath))?.pageCount, 4)
    }

    /// Markdown on the iPhone: chapters at the shallowest heading level used twice, as Android's commonmark cuts them;
    /// an `.md` file imports as an EPUB titled by its first heading.
    func testMarkdownChaptersAndImport() async throws {
        let md = "# Field notes\n\nA lead.\n\n## Knots\n\nThe *reef* knot.\n\n## Hitches\n\n- clove\n- round turn\n"
        let chapters = BookEngines.shared.markdown.chapters(markdown: md)
        XCTAssertEqual(chapters.map { $0.first as String? }, ["Field notes", "Knots", "Hitches"])
        XCTAssertTrue((chapters[1].second as String? ?? "").contains("<em>reef</em>"))

        let core = try XCTUnwrap(Core.shared, "the host app's core")
        let file = dir.appendingPathComponent("notes.md")
        let title = "Field notes \(UUID().uuidString.prefix(8))"
        try "# \(title)\n\nSome text.\n\n# Two\n\nMore.\n".write(to: file, atomically: true, encoding: .utf8)
        let summary = try await core.library.importPicked(files: [core.library.pickedFile(path: file.path, name: "notes.md")])
        XCTAssertEqual(summary.imported, 1, summary.text().words)
        let books = try await core.library.books.first(where: { _ in true }) ?? []
        XCTAssertTrue(books.contains { $0.title == title && $0.format == .epub })
    }

    /// The path rule: a stored path that's gone is found again under the folder that holds books now; one still there,
    /// or one from outside Documents/Books, is left alone.
    func testMovedContainerRebasesAStoredPath() throws {
        let now = dir.appendingPathComponent("new-container/Documents/Books", isDirectory: true)
        let file = now.appendingPathComponent("imports/abc-Alice.epub")
        try FileManager.default.createDirectory(at: file.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data("x".utf8).write(to: file)
        let old = "/private/var/mobile/Containers/Data/Application/OLD-UUID/Documents/Books/imports/abc-Alice.epub"
        XCTAssertEqual(MovedContainer.shared.rebase(stored: old, currentDir: now.path, exists: { KotlinBoolean(value: FileManager.default.fileExists(atPath: $0)) }), file.path)
        XCTAssertNil(MovedContainer.shared.rebase(stored: file.path, currentDir: now.path, exists: { KotlinBoolean(value: FileManager.default.fileExists(atPath: $0)) }), "still there")
        XCTAssertNil(MovedContainer.shared.rebase(stored: "/elsewhere/Downloads/abc-Alice.epub", currentDir: now.path, exists: { KotlinBoolean(value: FileManager.default.fileExists(atPath: $0)) }))
        XCTAssertNil(MovedContainer.shared.rebase(stored: old.replacingOccurrences(of: "abc-Alice", with: "gone"), currentDir: now.path, exists: { KotlinBoolean(value: FileManager.default.fileExists(atPath: $0)) }), "not there either")
    }

    /// A book stored under an older container's path (as after a reinstall) opens again: the shelf finds it under
    /// today's Documents/Books rather than calling it missing.
    func testShelfFindsABookAfterTheContainerMoves() async throws {
        guard let pdf = ProcessInfo.processInfo.environment["GRANGER_TEST_PDF"], !pdf.isEmpty else { throw XCTSkip("no GRANGER_TEST_PDF") }
        let core = try XCTUnwrap(Core.shared, "the host app's core")
        let result = try await core.library.importBook(path: pdf, displayName: "field-notes.pdf")
        let id: String
        switch onEnum(of: result) {
        case let .imported(r): id = r.bookId
        case let .alreadyThere(r): id = r.bookId
        default: return XCTFail("not imported: \(result)")
        }
        let stored = try await core.library.book(id: id)
        let real = try XCTUnwrap(stored).filePath
        let tail = try XCTUnwrap(real.range(of: "/Documents/Books/")).upperBound
        let old = "/private/var/mobile/Containers/Data/Application/0LD-C0NTA1NER/Documents/Books/" + real[tail...]
        try await core.library.setStoredPath(bookId: id, path: old)
        let before = try await core.library.book(id: id)
        XCTAssertEqual(before?.filePath, old)
        let moved = try await core.library.rebaseMovedBooks()
        XCTAssertGreaterThanOrEqual(moved.intValue, 1)
        let after = try await core.library.book(id: id)
        XCTAssertEqual(after?.filePath, real)
        // And opening goes through the same rule, should the shelf not have looked yet.
        try await core.library.setStoredPath(bookId: id, path: old)
        let opened = try await core.library.openableBook(id: id)
        XCTAssertEqual(opened?.filePath, real)
    }
}

/// Archives with their entries stored (no compression), enough for the comic tests: a zip, and a RAR 4.
enum StoredArchives {
    static func zip(_ entries: [(String, Data)]) -> Data {
        var out = Data()
        var central = Data()
        for (name, data) in entries {
            let offset = UInt32(out.count)
            let crc = crc32(data)
            let n = Data(name.utf8)
            let size = le32(UInt32(data.count))
            let head = le16(20) + le16(0) + le16(0) + le16(0) + le16(0x21) + le32(crc) + size + size + le16(UInt16(n.count)) + le16(0)
            out += le32(0x0403_4B50) + head + n + data
            let tail = le16(0) + le16(0) + le16(0) + le32(0) + le32(offset)
            central += le32(0x0201_4B50) + le16(20) + head + tail + n
        }
        let start = UInt32(out.count)
        out += central
        let count = le16(UInt16(entries.count))
        out += le32(0x0605_4B50) + le16(0) + le16(0) + count + count
        out += le32(UInt32(central.count)) + le32(start) + le16(0)
        return out
    }

    /// RAR 4 (the format junrar and libarchive's `rar` reader read): the marker, the archive header, a stored file
    /// header and its data per entry, and the end block. Each header's CRC is the low half of the CRC-32 of the rest of it.
    static func rar(_ entries: [(String, Data)]) -> Data {
        var out = Data([0x52, 0x61, 0x72, 0x21, 0x1A, 0x07, 0x00])
        out += block(type: 0x73, flags: 0, body: le16(0) + le32(0))
        for (name, data) in entries {
            let n = Data(name.replacingOccurrences(of: "/", with: "\\").utf8)
            let size = le32(UInt32(data.count))
            var body = size + size + Data([2]) + le32(crc32(data))
            body += le32(0x0021_0000) + Data([20, 0x30])
            body += le16(UInt16(n.count)) + le32(0x20) + n
            out += block(type: 0x74, flags: 0x8000, body: body) + data
        }
        out += block(type: 0x7B, flags: 0x4000, body: Data())
        return out
    }

    private static func block(type: UInt8, flags: UInt16, body: Data) -> Data {
        let rest = Data([type]) + le16(flags) + le16(UInt16(7 + body.count)) + body
        return le16(UInt16(crc32(rest) & 0xFFFF)) + rest
    }

    private static func le16(_ v: UInt16) -> Data { Data([UInt8(v & 0xFF), UInt8(v >> 8)]) }
    private static func le32(_ v: UInt32) -> Data { Data((0..<4).map { UInt8((v >> (8 * $0)) & 0xFF) }) }

    private static let table: [UInt32] = (0..<256).map { i -> UInt32 in
        var c = UInt32(i)
        for _ in 0..<8 { c = c & 1 != 0 ? 0xEDB8_8320 ^ (c >> 1) : c >> 1 }
        return c
    }

    static func crc32(_ data: Data) -> UInt32 {
        var c: UInt32 = 0xFFFF_FFFF
        for b in data { c = table[Int((c ^ UInt32(b)) & 0xFF)] ^ (c >> 8) }
        return c ^ 0xFFFF_FFFF
    }
}
