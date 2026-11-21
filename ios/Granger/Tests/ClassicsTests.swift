import GrangerCoreKit
import PDFKit
import UIKit
import XCTest
@testable import Granger

/// Plan I5 Task 7: text PDF → e-book on the iPhone. PDFKit's glyphs (with font sizes and weights), pictures and the
/// born-digital check behind the shared `PdfFile`, then the shared PdfToEpub over them. The PDFs are made here.
final class ClassicsTests: XCTestCase {
    private var dir: URL!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory.appendingPathComponent("classics-tests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        PdfKitEngine.install()
    }

    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    private func open(_ url: URL) throws -> PdfKitFile {
        let doc = try XCTUnwrap(PDFDocument(url: url))
        return PdfKitFile(doc)
    }

    func testGlyphsCarryTheirSizesWeightsAndPlaces() throws {
        let url = dir.appendingPathComponent("sample.pdf")
        SampleTextPdf.write(to: url)
        let pdf = try open(url)
        XCTAssertEqual(pdf.pageCount, 8)
        let glyphs = pdf.glyphs(index: 0)
        XCTAssertGreaterThan(glyphs.count, 400)
        XCTAssertFalse(glyphs.contains { ($0.text ?? "").trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }, "no whitespace glyphs")
        let heading = glyphs.filter { $0.size > 18 }
        let body = glyphs.filter { abs($0.size - 12) < 0.5 }
        XCTAssertFalse(heading.isEmpty, "the chapter title at 20 pt")
        XCTAssertTrue(heading.allSatisfy(\.bold), "the title's face is bold")
        XCTAssertGreaterThan(body.count, 300, "the prose at 12 pt")
        XCTAssertFalse(body.contains(where: \.bold))
        // y runs down from the page's top: the title sits above the prose, the page number at the foot.
        XCTAssertLessThan(heading.map(\.y).max()!, body.map(\.y).min()!)
        let size = pdf.pageSize(index: 0)
        XCTAssertGreaterThan(glyphs.last!.y, size.height - 60, "the page number at the foot")
        let look = pdf.look(index: 0)
        XCTAssertGreaterThan(look.visible, 400)
        XCTAssertEqual(look.hidden, 0)
        XCTAssertFalse(look.pageImage)
        XCTAssertEqual(PdfOrigin.companion.of(path: url.path), .bornDigital)
    }

    func testAScanWithHiddenTextIsNotBornDigitalAndItsPictureIsFound() throws {
        let url = dir.appendingPathComponent("scan.pdf")
        var box = CGRect(x: 0, y: 0, width: 400, height: 600)
        let ctx = try XCTUnwrap(CGContext(url as CFURL, mediaBox: &box, nil))
        let picture = UIGraphicsImageRenderer(size: CGSize(width: 200, height: 300)).image { c in
            UIColor.systemGray5.setFill(); c.fill(CGRect(x: 0, y: 0, width: 200, height: 300))
            UIColor.black.setFill(); c.fill(CGRect(x: 20, y: 20, width: 160, height: 10))
        }
        for _ in 0..<3 {
            ctx.beginPDFPage(nil)
            ctx.draw(try XCTUnwrap(picture.cgImage), in: box)
            // OCR's text, drawn invisibly over the page's picture (text render mode 3).
            ctx.setTextDrawingMode(.invisible)
            let line = CTLineCreateWithAttributedString(NSAttributedString(string: String(repeating: "The scanned words of an old book. ", count: 4),
                                                                           attributes: [kCTFontAttributeName as NSAttributedString.Key: CTFontCreateWithName("Times-Roman" as CFString, 10, nil)]))
            for row in 0..<8 {
                ctx.textPosition = CGPoint(x: 30, y: 500 - CGFloat(row) * 14)
                CTLineDraw(line, ctx)
            }
            ctx.endPDFPage()
        }
        ctx.closePDF()
        let pdf = try open(url)
        let look = pdf.look(index: 0)
        XCTAssertGreaterThan(look.hidden, 100, "the OCR text counts as hidden")
        XCTAssertEqual(look.visible, 0)
        XCTAssertTrue(look.pageImage, "a picture covers the page")
        XCTAssertNotEqual(PdfOrigin.companion.of(path: url.path), .bornDigital)
        let scratch = dir.appendingPathComponent("pictures").path
        try FileManager.default.createDirectory(atPath: scratch, withIntermediateDirectories: true)
        let pictures = pdf.pictures(index: 0, minArea: 1000, scratch: scratch)
        XCTAssertEqual(pictures.count, 1)
        let p = try XCTUnwrap(pictures.first)
        XCTAssertEqual(p.w, 400, accuracy: 1)
        XCTAssertEqual(p.h, 600, accuracy: 1)
        XCTAssertNotNil(UIImage(contentsOfFile: p.path), "the picture is written as a picture")
        // Read as an e-book, a scan writes nothing.
        let report = PdfToEpub.shared.convert(pdf: url.path, out: dir.appendingPathComponent("scan.epub").path, title: "", author: "", language: "", trace: nil)
        XCTAssertFalse(report.passes)
        XCTAssertFalse(FileManager.default.fileExists(atPath: dir.appendingPathComponent("scan.epub").path))
    }

    /// The archive.org text's path: the shared PdfToEpub over PDFKit makes an EPUB with the outline's chapters.
    func testATextPdfBecomesAnEpubWithItsChapters() throws {
        let url = dir.appendingPathComponent("ropes.pdf")
        SampleTextPdf.write(to: url)
        let out = dir.appendingPathComponent("ropes.pdf.epub")
        let report = PdfToEpub.shared.convert(pdf: url.path, out: out.path, title: SampleTextPdf.title, author: SampleTextPdf.author, language: "en", trace: nil)
        XCTAssertTrue(report.passes, report.why)
        XCTAssertEqual(report.origin, .bornDigital)
        XCTAssertEqual(report.chapters, 4)
        XCTAssertEqual(report.pages, 8)
        XCTAssertGreaterThan(report.keptShare, 0.92)
        XCTAssertTrue(FileManager.default.fileExists(atPath: out.path))
        guard case let .ok(ok) = onEnum(of: EpubInspector.shared.inspect(path: out.path, fallbackTitle: "x")) else { return XCTFail("the EPUB reads") }
        XCTAssertEqual(ok.meta.title, SampleTextPdf.title)
        XCTAssertEqual(ok.meta.authors, [SampleTextPdf.author])
    }
}
