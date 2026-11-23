import GrangerCoreKit
import PDFKit
import XCTest
@testable import Granger

/// Plan I5 Task 5: the iPhone's PDF engine for the shared book rules (PDFKit), and books imported into the shelf in
/// Documents/Books. Uses GRANGER_TEST_PDF and GRANGER_TEST_EPUB; skipped without them.
final class BookShelfTests: XCTestCase {
    private func env(_ key: String) throws -> String {
        guard let v = ProcessInfo.processInfo.environment[key], !v.isEmpty else { throw XCTSkip("no \(key)") }
        return v
    }

    /// PDFKit reads the test PDF as PdfBox reads it on Android: its pages, information, outline, text and page size.
    func testPdfKitFileReadsPagesOutlineAndText() throws {
        let path = try env("GRANGER_TEST_PDF")
        let opening = PdfKitEngine.open(path)
        let file = try XCTUnwrap(opening.file, opening.reason ?? "")
        defer { try? file.close() }
        XCTAssertEqual(file.pageCount, 6)
        XCTAssertFalse(file.isEncrypted)
        XCTAssertEqual(file.info().title, "Field Notes on Knots")
        XCTAssertEqual(file.info().author, "A. Rigger")
        XCTAssertEqual(file.outline().map(\.title), ["The Reef Knot", "The Bowline", "The Clove Hitch"])
        XCTAssertEqual(file.outline().map(\.page), [1, 3, 5])
        XCTAssertTrue(file.pageText(page: 3).contains("Bowline"), file.pageText(page: 3))
        let size = file.pageSize(index: 0)
        XCTAssertEqual(size.width, 420, accuracy: 1)
        XCTAssertEqual(size.height, 595, accuracy: 1)
        XCTAssertGreaterThan(file.look(index: 0).visible, 80, "a text page")
    }

    /// The shared inspector, through the engine, reads the PDF's metadata; a file PDFKit can't read is refused.
    func testSharedInspectorUsesPdfKit() throws {
        let path = try env("GRANGER_TEST_PDF")
        PdfKitEngine.install()
        let ok = try XCTUnwrap(PdfInspector.shared.inspect(path: path, fallbackTitle: "x") as? InspectionOk)
        XCTAssertEqual(ok.meta.title, "Field Notes on Knots")
        XCTAssertEqual(ok.meta.pageCount, 6)
        XCTAssertEqual(ok.meta.outline.count, 3)
        let bad = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("not.pdf")
        FileManager.default.createFile(atPath: bad.path, contents: Data("%PDF-1.4 nothing".utf8))
        XCTAssertTrue(PdfInspector.shared.inspect(path: bad.path, fallbackTitle: "x") is InspectionInvalid)
        XCTAssertNotNil(PdfKitCovers().firstPage(path: path, heightPx: 300), "a cover from page 1")
    }

    /// Importing copies the file into Documents/Books/imports and lists it on the shelf with its metadata (a PDF with its
    /// first page as cover); the same file again is already there.
    func testImportsAnEpubAndAPdfIntoTheShelf() async throws {
        let epub = try env("GRANGER_TEST_EPUB")
        let pdf = try env("GRANGER_TEST_PDF")
        let core = try XCTUnwrap(Core.shared, "the host app's core")

        let first = try await core.library.importBook(path: pdf, displayName: "field-notes.pdf")
        let id: String
        switch onEnum(of: first) {
        case let .imported(r): id = r.bookId
        case let .alreadyThere(r): id = r.bookId
        default: return XCTFail("not imported: \(first)")
        }
        let found = try await core.library.book(id: id)
        let book = try XCTUnwrap(found)
        XCTAssertEqual(book.title, "Field Notes on Knots")
        XCTAssertEqual(book.format, .pdf)
        XCTAssertTrue(book.filePath.hasPrefix(core.booksFolder.appendingPathComponent("imports").path), book.filePath)
        XCTAssertTrue(FileManager.default.fileExists(atPath: book.filePath))
        XCTAssertNotNil(book.coverPath.flatMap { FileManager.default.fileExists(atPath: $0) ? $0 : nil }, "PDFKit drew its cover")
        let again = try await core.library.importBook(path: pdf, displayName: "again.pdf")
        XCTAssertTrue(again is ImportResultAlreadyThere)

        let e = try await core.library.importBook(path: epub, displayName: (epub as NSString).lastPathComponent)
        let epubId: String
        switch onEnum(of: e) {
        case let .imported(r): epubId = r.bookId
        case let .alreadyThere(r): epubId = r.bookId
        default: return XCTFail("not imported: \(e)")
        }
        let foundEpub = try await core.library.book(id: epubId)
        let pp = try XCTUnwrap(foundEpub)
        XCTAssertTrue(pp.title.contains("Pride"), pp.title)
        XCTAssertEqual(pp.format, .epub)
    }

    /// The place is saved as Readium's locator JSON and read back the same, as Android stores it.
    func testSavesAndReadsThePlace() async throws {
        let pdf = try env("GRANGER_TEST_PDF")
        let core = try XCTUnwrap(Core.shared)
        let r = try await core.library.importBook(path: pdf, displayName: "field-notes.pdf")
        let id = (r as? ImportResultImported)?.bookId ?? (r as? ImportResultAlreadyThere)?.bookId
        let bookId = try XCTUnwrap(id)
        let json = #"{"href":"publication.pdf","type":"application/pdf","locations":{"position":4,"totalProgression":0.5}}"#
        try await core.library.savePosition(bookId: bookId, locatorJson: json, totalProgression: 0.5, chapterTitle: "The Bowline")
        let p = try await core.library.position(bookId: bookId)
        XCTAssertEqual(p?.locatorJson, json)
        XCTAssertEqual(p?.chapterTitle, "The Bowline")
    }

    /// Plan I7 Task 7a: Best matches search the books too, as Android's: an imported e-book's passage is found from the
    /// book index (a quote's book first), Search's row for it is a book row that opens at the passage, and a book
    /// outside the library's languages is left out.
    func testBestMatchesFindABookPassageInTheLibrarysLanguages() async throws {
        let epub = try env("GRANGER_TEST_EPUB")
        let core = try XCTUnwrap(Core.shared, "the host app's core")
        let r = try await core.library.importBook(path: epub, displayName: (epub as NSString).lastPathComponent)
        let bookId = try XCTUnwrap((r as? ImportResultImported)?.bookId ?? (r as? ImportResultAlreadyThere)?.bookId)
        try await core.library.prepareBooks()
        let saved = LibraryGate.shared.languages
        defer { LibraryGate.shared.languages = saved }
        LibraryGate.shared.languages = ["en"]

        let query = "In vain have I struggled"
        let result = try await core.library.bestResult(query: query)
        let found = try XCTUnwrap(result, "Best matches answered")
        let hit = try XCTUnwrap(found.matches.first { $0.passage.kind == .book && $0.passage.sourceId == bookId }, "the book's passage")
        XCTAssertEqual(found.matches.first?.passage.sourceId, bookId, "a quote's book comes first")
        XCTAssertTrue(hit.passage.text.contains(query), hit.passage.text)
        XCTAssertFalse(hit.passage.location.isEmpty, "the passage's chapter")
        XCTAssertTrue(hit.quote()?.contains(query) ?? false, "the sentence the row quotes")

        let row = SearchModel.bestRow(hit)
        XCTAssertTrue(row.title.contains("Pride"), row.title)
        XCTAssertEqual(row.icon, "book.closed")
        XCTAssertEqual(row.book?.href, hit.passage.location)
        XCTAssertEqual(row.book?.quote, hit.quote())

        // A PDF's passage is a page (the test PDF's pages each hold the same body line, so any page).
        if let pdf = ProcessInfo.processInfo.environment["GRANGER_TEST_PDF"], !pdf.isEmpty {
            let pr = try await core.library.importBook(path: pdf, displayName: "field-notes.pdf")
            let pdfId = try XCTUnwrap((pr as? ImportResultImported)?.bookId ?? (pr as? ImportResultAlreadyThere)?.bookId)
            try await core.library.prepareBooks()
            let knots = try await core.library.bestResult(query: "a good knot holds when it must")
            let page = try XCTUnwrap(knots?.matches.first { $0.passage.sourceId == pdfId }, "the PDF's page")
            XCTAssertTrue(page.passage.location.hasPrefix("page/"), page.passage.location)
            XCTAssertEqual(SearchModel.bestRow(page).book?.href, page.passage.location)
        }

        LibraryGate.shared.languages = ["es"]
        let spanish = try await core.library.bestResult(query: query)
        XCTAssertFalse(spanish?.matches.contains { $0.passage.sourceId == bookId } ?? false, "an English book is left out of a Spanish library")
    }
}
