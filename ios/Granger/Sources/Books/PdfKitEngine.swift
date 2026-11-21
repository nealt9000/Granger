import Foundation
import GrangerCoreKit
import PDFKit
import UIKit

/// The iPhone's PDF engine for the shared book rules (Plan I5 call 6): PDFKit, where Android has PdfBox. Import, the
/// cover, the outline and page text read through it. Set once at launch with `PdfKitEngine.install()`.
enum PdfKitEngine {
    static func install() {
        IosBooksKt.usePdfEngine(opener: IosPdfOpener { path in PdfKitEngine.open(path) })
    }

    /// PDFKit's reading of the file: open, locked behind a password, or unreadable.
    static func open(_ path: String) -> PdfOpening {
        guard let doc = PDFDocument(url: URL(fileURLWithPath: path)) else {
            return PdfOpening(file: nil, needsPassword: false, reason: "PDFKit can't read \((path as NSString).lastPathComponent)")
        }
        if doc.isLocked { return PdfOpening(file: nil, needsPassword: true, reason: "needs a password") }
        return PdfOpening(file: PdfKitFile(doc), needsPassword: false, reason: nil)
    }
}

/// One open PDF through PDFKit, as the shared `PdfFile`. Pages count from 0 unless a parameter says otherwise.
final class PdfKitFile: NSObject, PdfFile {
    private var doc: PDFDocument?

    init(_ doc: PDFDocument) { self.doc = doc }

    var pageCount: Int32 { Int32(doc?.pageCount ?? 0) }

    var isEncrypted: Bool { doc?.isEncrypted ?? false }

    func info() -> PdfInfo {
        let a = doc?.documentAttributes ?? [:]
        func text(_ key: PDFDocumentAttribute) -> String? {
            (a[key] as? String).flatMap { $0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : $0 }
        }
        let keywords: String? = {
            if let k = a[PDFDocumentAttribute.keywordsAttribute] as? [String] { return k.isEmpty ? nil : k.joined(separator: ", ") }
            return text(.keywordsAttribute)
        }()
        let created = (a[PDFDocumentAttribute.creationDateAttribute] as? Date).map { d -> String in
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US_POSIX")
            f.timeZone = TimeZone(identifier: "UTC")
            f.dateFormat = "yyyy-MM-dd"
            return f.string(from: d)
        }
        // PDFKit doesn't give the catalog's /Lang; the shared rules treat a PDF without one as the main language.
        return PdfInfo(title: text(.titleAttribute), author: text(.authorAttribute), subject: text(.subjectAttribute),
                       keywords: keywords, created: created, language: nil)
    }

    func outline() -> [OutlineEntry] {
        guard let doc, let root = doc.outlineRoot else { return [] }
        var out: [OutlineEntry] = []
        func walk(_ node: PDFOutline, depth: Int) {
            for i in 0..<node.numberOfChildren {
                guard out.count < 2000, let child = node.child(at: i) else { return }
                let page = child.destination?.page ?? (child.action as? PDFActionGoTo)?.destination.page
                if let page {
                    let index = doc.index(for: page)
                    if index != NSNotFound { out.append(OutlineEntry(title: child.label ?? "", page: Int32(index + 1), depth: Int32(depth))) }
                }
                if depth < 7 { walk(child, depth: depth + 1) }
            }
        }
        walk(root, depth: 0)
        return out
    }

    func pageText(page: Int32) -> String {
        guard let p = doc?.page(at: Int(page) - 1) else { return "" }
        return p.string ?? ""
    }

    /// How a page draws its text. PDFKit can't tell invisible (OCR) text from visible, so every character counts as
    /// visible and no page as a picture: Read as ebook's born-digital check is approximate here (Plan I5 Task 7).
    func look(index: Int32) -> PdfOrigin.CompanionPageLook {
        let n = doc?.page(at: Int(index))?.string?.filter { !$0.isWhitespace }.count ?? 0
        return PdfOrigin.CompanionPageLook(visible: Int32(n), hidden: 0, glyphless: 0, pageImage: false)
    }

    func links(max: Int32) -> [PdfLink] {
        guard let doc else { return [] }
        var out: [PdfLink] = []
        for i in 0..<doc.pageCount {
            guard let page = doc.page(at: i) else { continue }
            for a in page.annotations where a.type == PDFAnnotationSubtype.link.rawValue.replacingOccurrences(of: "/", with: "") || a.type == "Link" {
                let target = a.destination?.page ?? (a.action as? PDFActionGoTo)?.destination.page
                guard let target else { continue }
                let t = doc.index(for: target)
                guard t != NSNotFound else { continue }
                let b = a.bounds
                out.append(PdfLink(page: Int32(i), left: Float(b.minX), bottom: Float(b.minY), right: Float(b.maxX), top: Float(b.maxY), target: Int32(t)))
                if out.count >= Int(max) { return out }
            }
        }
        return out
    }

    func pageSize(index: Int32) -> PdfPageSize {
        guard let p = doc?.page(at: Int(index)) else { return PdfPageSize(width: 0, height: 0) }
        let b = p.bounds(for: .mediaBox)
        let turned = (p.rotation / 90) % 2 != 0
        return PdfPageSize(width: Float(turned ? b.height : b.width), height: Float(turned ? b.width : b.height))
    }

    /// TODO(Plan I5 Task 7): glyphs with font sizes for text PDF → e-book. PDFKit gives characters and their boxes
    /// (`PDFPage.characterBounds(at:)`) but no font size or weight; until then a PDF can't be made an e-book here.
    func glyphs(index: Int32) -> [Glyph] { [] }

    /// TODO(Plan I5 Task 7): a page's pictures, for text PDF → e-book.
    func pictures(index: Int32, minArea: Float, scratch: String) -> [PdfPicture] { [] }

    func close() { doc = nil }
}

/// A PDF's first page as a PNG, for its cover on the shelf (Android renders it with PdfRenderer).
final class PdfKitCovers: NSObject, PdfCovers {
    func firstPage(path: String, heightPx: Int32) -> KotlinByteArray? {
        guard let page = PDFDocument(url: URL(fileURLWithPath: path))?.page(at: 0) else { return nil }
        let box = page.bounds(for: .mediaBox)
        guard box.height > 0 else { return nil }
        let h = CGFloat(heightPx)
        let size = CGSize(width: (box.width * h / box.height).rounded(), height: h)
        let image = page.thumbnail(of: size, for: .mediaBox)
        guard let png = image.pngData() else { return nil }
        return GrangerCoreEntry.shared.byteArray(of: png)
    }
}
