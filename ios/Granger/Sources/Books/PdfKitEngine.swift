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

    /// How a page draws its text, for Read as ebook's born-digital check (Android's Looker): its content stream read
    /// with CGPDFScanner, each shown string's characters counted as visible, hidden (render mode 3 or 7) or in an OCR
    /// GlyphLessFont, and whether one picture covers 60% of the page or more.
    func look(index: Int32) -> PdfOrigin.CompanionPageLook {
        guard let page = doc?.page(at: Int(index)), let scan = PdfContentScan.run(page) else {
            return PdfOrigin.CompanionPageLook(visible: 0, hidden: 0, glyphless: 0, pageImage: false)
        }
        let box = page.bounds(for: .mediaBox)
        let area = max(1, abs(box.width * box.height))
        let pageImage = scan.images.contains { abs($0.rect.width * $0.rect.height) >= area * 0.6 }
        return PdfOrigin.CompanionPageLook(visible: Int32(scan.visible), hidden: Int32(scan.hidden), glyphless: Int32(scan.glyphless), pageImage: pageImage)
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

    /// Page [index]'s glyphs as drawn (Plan I5 Task 7, for text PDF → e-book): PDFKit's characters with their boxes
    /// (`characterBounds(at:)`) and fonts (the page's attributed string: size, and bold or italic by the font's traits
    /// and name), y down from the page's top, the baseline where the font's descent puts it. Whitespace is left out (the
    /// shared layout makes spaces from gaps, as with PdfBox), and a glyph drawn twice a hair apart (faked bold) comes once.
    /// Pages turned a quarter are read as stored: PDFKit gives their boxes unturned.
    func glyphs(index: Int32) -> [Glyph] {
        guard let page = doc?.page(at: Int(index)), let attributed = page.attributedString else { return [] }
        let box = page.bounds(for: .mediaBox)
        let text = attributed.string as NSString
        var out: [Glyph] = []
        out.reserveCapacity(text.length)
        var fontCache: [ObjectIdentifier: (size: CGFloat, descent: CGFloat, bold: Bool, italic: Bool)] = [:]
        var i = 0
        while i < text.length {
            let range = text.rangeOfComposedCharacterSequence(at: i)
            defer { i = range.location + range.length }
            let piece = text.substring(with: range)
            if piece.unicodeScalars.allSatisfy({ CharacterSet.whitespacesAndNewlines.contains($0) || $0.value == 0xFFFC }) { continue }
            let rect = page.characterBounds(at: range.location)
            guard rect.width > 0 || rect.height > 0 else { continue }
            let font = attributed.attribute(.font, at: range.location, effectiveRange: nil) as? UIFont
            let style: (size: CGFloat, descent: CGFloat, bold: Bool, italic: Bool) = {
                guard let font else { return (rect.height, 0, false, false) }
                if let known = fontCache[ObjectIdentifier(font)] { return known }
                let made = (font.pointSize, -font.descender, PdfKitFile.isBold(font), PdfKitFile.isItalic(font))
                fontCache[ObjectIdentifier(font)] = made
                return made
            }()
            let baseline = rect.minY + min(style.descent, rect.height * 0.4)
            let x = Float(rect.minX - box.minX)
            let y = Float(box.maxY - baseline)
            if let last = out.last, last.text == piece, abs(last.x - x) < Float(style.size) * 0.1, abs(last.y - y) < 0.5 { continue }
            out.append(Glyph(text: piece, x: x, y: y, width: Float(rect.width), size: Float(style.size), height: Float(rect.height),
                             bold: style.bold, italic: style.italic))
        }
        return out.sorted { $0.y != $1.y ? $0.y < $1.y : $0.x < $1.x }
    }

    static func isBold(_ font: UIFont) -> Bool {
        if font.fontDescriptor.symbolicTraits.contains(.traitBold) { return true }
        let name = font.fontName.lowercased()
        return ["bold", "black", "heavy", "semibold", "demi"].contains { name.contains($0) }
    }

    static func isItalic(_ font: UIFont) -> Bool {
        if font.fontDescriptor.symbolicTraits.contains(.traitItalic) { return true }
        let name = font.fontName.lowercased()
        return name.contains("italic") || name.contains("oblique") || name.hasSuffix("-it") || name.hasSuffix("it") && name.contains("-")
    }

    /// Page [index]'s pictures covering at least [minArea] square points (Android's PictureFinder): the images its
    /// content stream draws, found with CGPDFScanner. A JPEG up to 1,600 pixels a side is written as it is; any other is
    /// the page drawn inside the picture's box, scaled to at most 1,600 pixels, as JPEG.
    func pictures(index: Int32, minArea: Float, scratch: String) -> [PdfPicture] {
        guard let page = doc?.page(at: Int(index)), let scan = PdfContentScan.run(page) else { return [] }
        let box = page.bounds(for: .mediaBox)
        var out: [PdfPicture] = []
        for image in scan.images {
            let r = image.rect.standardized
            guard Float(r.width * r.height) >= minArea else { continue }
            let file = (scratch as NSString).appendingPathComponent("p\(index + 1)-\(out.count + 1)")
            guard let (data, type) = PdfKitFile.encode(image, page: page, rect: r) else { continue }
            guard FileManager.default.createFile(atPath: file, contents: data) else { continue }
            out.append(PdfPicture(x: Float(r.minX - box.minX), y: Float(box.maxY - r.maxY), w: Float(r.width), h: Float(r.height), path: file, mediaType: type))
        }
        return out
    }

    private static let maxPicturePx: CGFloat = 1600

    private static func encode(_ image: PdfContentScan.Image, page: PDFPage, rect: CGRect) -> (Data, String)? {
        if let stream = image.stream {
            var format = CGPDFDataFormat.raw
            if let data = CGPDFStreamCopyData(stream, &format) as Data?, format == .jpegEncoded,
               image.pixelWidth <= Int(maxPicturePx), image.pixelHeight <= Int(maxPicturePx) {
                return (data, "image/jpeg")
            }
        }
        // Drawn from the page: the picture's own pixel size when known, never more than 1,600 a side.
        let natural = CGSize(width: image.pixelWidth > 0 ? CGFloat(image.pixelWidth) : rect.width * 2,
                             height: image.pixelHeight > 0 ? CGFloat(image.pixelHeight) : rect.height * 2)
        let scale = min(1, maxPicturePx / max(natural.width, natural.height, 1))
        let size = CGSize(width: max(1, (natural.width * scale).rounded()), height: max(1, (natural.height * scale).rounded()))
        let format = UIGraphicsImageRendererFormat()
        format.scale = 1
        let rendered = UIGraphicsImageRenderer(size: size, format: format).image { ctx in
            UIColor.white.setFill()
            ctx.fill(CGRect(origin: .zero, size: size))
            let c = ctx.cgContext
            c.translateBy(x: 0, y: size.height)
            c.scaleBy(x: size.width / rect.width, y: -size.height / rect.height)
            c.translateBy(x: -rect.minX, y: -rect.minY)
            page.draw(with: .mediaBox, to: c)
        }
        guard let jpeg = rendered.jpegData(compressionQuality: 0.85) else { return nil }
        return (jpeg, "image/jpeg")
    }

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

/// A page's content stream read with CGPDFScanner (Plan I5 Task 7): what PDFKit doesn't say. Each shown string's
/// characters by how they're drawn (the text render mode, and whether the font is an OCR GlyphLessFont), and each image
/// drawn, with the box the current transformation puts it in. Form XObjects aren't entered: their text and pictures go
/// uncounted, which only makes a page look less like a scan.
final class PdfContentScan {
    struct Image {
        let rect: CGRect
        let stream: CGPDFStreamRef?
        let pixelWidth: Int
        let pixelHeight: Int
    }

    private struct State {
        var ctm = CGAffineTransform.identity
        var renderMode = 0
        var glyphless = false
    }

    private var state = State()
    private var saved: [State] = []
    private(set) var visible = 0
    private(set) var hidden = 0
    private(set) var glyphless = 0
    private(set) var images: [Image] = []

    /// [page]'s content read; nil when it has no content stream.
    static func run(_ page: PDFPage) -> PdfContentScan? {
        guard let ref = page.pageRef else { return nil }
        let scan = PdfContentScan()
        let content = CGPDFContentStreamCreateWithPage(ref)
        guard let table = CGPDFOperatorTableCreate() else { return nil }
        for (op, callback) in callbacks { CGPDFOperatorTableSetCallback(table, op, callback) }
        let scanner = CGPDFScannerCreate(content, table, Unmanaged.passUnretained(scan).toOpaque())
        _ = CGPDFScannerScan(scanner)
        CGPDFScannerRelease(scanner)
        CGPDFContentStreamRelease(content)
        CGPDFOperatorTableRelease(table)
        return scan
    }

    private static func of(_ info: UnsafeMutableRawPointer?) -> PdfContentScan? {
        info.map { Unmanaged<PdfContentScan>.fromOpaque($0).takeUnretainedValue() }
    }

    private func shown(_ chars: Int) {
        if state.glyphless { glyphless += chars }
        else if state.renderMode == 3 || state.renderMode == 7 { hidden += chars }
        else { visible += chars }
    }

    /// The characters in a shown string that aren't spaces (one per byte: a two-byte font counts each twice, alike for
    /// every kind, so the shares stand).
    private static func count(_ string: CGPDFStringRef) -> Int {
        guard let bytes = CGPDFStringGetBytePtr(string) else { return 0 }
        let n = CGPDFStringGetLength(string)
        var c = 0
        for i in 0..<n where bytes[i] != 0x20 { c += 1 }
        return c
    }

    private static func number(_ s: CGPDFScannerRef) -> CGFloat {
        var v: CGPDFReal = 0
        _ = CGPDFScannerPopNumber(s, &v)
        return v
    }

    private static func resource(_ s: CGPDFScannerRef, _ category: String, _ name: UnsafePointer<CChar>) -> CGPDFObjectRef? {
        let content = CGPDFScannerGetContentStream(s)
        return category.withCString { CGPDFContentStreamGetResource(content, $0, name) }
    }

    private static let callbacks: [(String, CGPDFOperatorCallback)] = [
        ("q", { _, info in guard let scan = of(info) else { return }; scan.saved.append(scan.state) }),
        ("Q", { _, info in guard let scan = of(info), let last = scan.saved.popLast() else { return }; scan.state = last }),
        ("cm", { s, info in
            guard let scan = of(info) else { return }
            let f = number(s), e = number(s), d = number(s), c = number(s), b = number(s), a = number(s)
            scan.state.ctm = CGAffineTransform(a: a, b: b, c: c, d: d, tx: e, ty: f).concatenating(scan.state.ctm)
        }),
        ("Tr", { s, info in guard let scan = of(info) else { return }; scan.state.renderMode = Int(number(s)) }),
        ("Tf", { s, info in
            guard let scan = of(info) else { return }
            _ = number(s)
            var name: UnsafePointer<CChar>?
            guard CGPDFScannerPopName(s, &name), let name, let object = resource(s, "Font", name) else { scan.state.glyphless = false; return }
            var dict: CGPDFDictionaryRef?
            var base: UnsafePointer<CChar>?
            if CGPDFObjectGetValue(object, .dictionary, &dict), let dict, CGPDFDictionaryGetName(dict, "BaseFont", &base), let base {
                scan.state.glyphless = String(cString: base).range(of: "GlyphLess", options: .caseInsensitive) != nil
            } else {
                scan.state.glyphless = false
            }
        }),
        ("Tj", { s, info in
            var str: CGPDFStringRef?
            guard let scan = of(info), CGPDFScannerPopString(s, &str), let str else { return }
            scan.shown(count(str))
        }),
        ("'", { s, info in
            var str: CGPDFStringRef?
            guard let scan = of(info), CGPDFScannerPopString(s, &str), let str else { return }
            scan.shown(count(str))
        }),
        ("\"", { s, info in
            var str: CGPDFStringRef?
            guard let scan = of(info), CGPDFScannerPopString(s, &str), let str else { return }
            scan.shown(count(str))
        }),
        ("TJ", { s, info in
            var array: CGPDFArrayRef?
            guard let scan = of(info), CGPDFScannerPopArray(s, &array), let array else { return }
            var n = 0
            for i in 0..<CGPDFArrayGetCount(array) {
                var str: CGPDFStringRef?
                if CGPDFArrayGetString(array, i, &str), let str { n += count(str) }
            }
            scan.shown(n)
        }),
        ("Do", { s, info in
            guard let scan = of(info) else { return }
            var name: UnsafePointer<CChar>?
            guard CGPDFScannerPopName(s, &name), let name, let object = resource(s, "XObject", name) else { return }
            var stream: CGPDFStreamRef?
            guard CGPDFObjectGetValue(object, .stream, &stream), let stream, let dict = CGPDFStreamGetDictionary(stream) else { return }
            var subtype: UnsafePointer<CChar>?
            guard CGPDFDictionaryGetName(dict, "Subtype", &subtype), let subtype, String(cString: subtype) == "Image" else { return }
            var w: CGPDFInteger = 0, h: CGPDFInteger = 0
            _ = CGPDFDictionaryGetInteger(dict, "Width", &w)
            _ = CGPDFDictionaryGetInteger(dict, "Height", &h)
            let rect = CGRect(x: 0, y: 0, width: 1, height: 1).applying(scan.state.ctm)
            scan.images.append(Image(rect: rect, stream: stream, pixelWidth: w, pixelHeight: h))
        }),
    ]
}
