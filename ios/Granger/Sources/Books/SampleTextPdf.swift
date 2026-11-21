#if DEBUG
import CoreGraphics
import CoreText
import Foundation
import PDFKit

/// A born-digital text PDF made on the phone, standing in for an archive.org upload made on a computer (Plan I5 Task 7):
/// four chapters over eight pages of prose, chapter titles in a larger bold face, page numbers at the foot and an
/// outline. Text PDF → e-book's tests read it, so no test waits on archive.org.
enum SampleTextPdf {
    static let title = "A Field Book of Ropes"
    static let author = "M. Hawthorne"
    static let chapters = ["Of Fibres and Lays", "The Common Knots", "Splices and Whippings", "Care of Rope at Sea"]

    private static let sentences = [
        "Every rope begins as a handful of fibres, twisted together until they hold one another fast.",
        "The sailmaker judged a coil by the feel of it in the hand, and by the sound it made when struck against the rail.",
        "Hemp was preferred for standing rigging, for it stretched little once it had been worked and tarred.",
        "Manila rope is lighter and kinder to the hands, though it rots sooner if it is stowed away wet.",
        "A knot that cannot be untied when the work is done is a poor knot, however well it held under load.",
        "The apprentice learned each hitch first on a short length of line, and afterwards in the dark, by touch alone.",
        "When a rope is cut, its end must be whipped at once, or the strands will open and the lay be lost.",
        "Old hands would tell the weather by the stiffness of the halyards on a cold morning before the watch was called.",
        "A long splice joins two ropes without thickening them, so that the joined line still runs freely through a block.",
        "Chafe is the enemy of all cordage, and a careful mate will look over every place where a line rubs against wood.",
        "Coils should be hung where the air moves about them, never thrown down in a damp corner of the hold.",
        "There is an old saying among riggers that a rope well kept will outlast the ship it was bought for.",
    ]

    /// Writes the PDF to [url], replacing any file there.
    static func write(to url: URL) {
        try? FileManager.default.removeItem(at: url)
        var box = CGRect(x: 0, y: 0, width: 432, height: 648)
        let info: [CFString: Any] = [kCGPDFContextTitle: title, kCGPDFContextAuthor: author, kCGPDFContextCreator: "Granger tests"]
        guard let ctx = CGContext(url as CFURL, mediaBox: &box, info as CFDictionary) else { return }
        let body = CTFontCreateWithName("Times-Roman" as CFString, 12, nil)
        let heading = CTFontCreateWithName("Times-Bold" as CFString, 20, nil)
        for page in 0..<8 {
            ctx.beginPDFPage(nil)
            let text = NSMutableAttributedString()
            if page % 2 == 0 {
                text.append(NSAttributedString(string: chapters[page / 2] + "\n\n", attributes: [kCTFontAttributeName as NSAttributedString.Key: heading]))
            }
            for p in 0..<3 {
                let start = (page * 3 + p) % sentences.count
                let paragraph = (0..<4).map { sentences[(start + $0 * 5) % sentences.count] }.joined(separator: " ")
                text.append(NSAttributedString(string: paragraph + (p < 2 ? "\n\n" : ""), attributes: [kCTFontAttributeName as NSAttributedString.Key: body]))
            }
            draw(text, in: box.insetBy(dx: 48, dy: 60), ctx)
            draw(NSAttributedString(string: "\(page + 1)", attributes: [kCTFontAttributeName as NSAttributedString.Key: body]),
                 in: CGRect(x: box.midX - 10, y: 24, width: 40, height: 20), ctx)
            ctx.endPDFPage()
        }
        ctx.closePDF()
        guard let doc = PDFDocument(url: url) else { return }
        let root = PDFOutline()
        for (i, c) in chapters.enumerated() {
            let item = PDFOutline()
            item.label = c
            if let page = doc.page(at: i * 2) { item.destination = PDFDestination(page: page, at: CGPoint(x: 0, y: box.height)) }
            root.insertChild(item, at: i)
        }
        doc.outlineRoot = root
        doc.write(to: url)
    }

    private static func draw(_ text: NSAttributedString, in rect: CGRect, _ ctx: CGContext) {
        let frame = CTFramesetterCreateFrame(CTFramesetterCreateWithAttributedString(text), CFRange(), CGPath(rect: rect, transform: nil), nil)
        CTFrameDraw(frame, ctx)
    }
}
#endif
