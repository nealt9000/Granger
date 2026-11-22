import Foundation
import CoreGraphics
import CoreText
import PDFKit

// A small real PDF for the reader's tests: six pages of text, a title and author, and an outline of three chapters.
// Run by tools/ios/test-fixtures.sh: swift tools/ios/fixtures/make-test-pdf.swift <out.pdf>
let out = URL(fileURLWithPath: CommandLine.arguments[1])
var box = CGRect(x: 0, y: 0, width: 420, height: 595)
let info: [CFString: Any] = [kCGPDFContextTitle: "Field Notes on Knots", kCGPDFContextAuthor: "A. Rigger"]
let ctx = CGContext(out as CFURL, mediaBox: &box, info as CFDictionary)!
let chapters = ["The Reef Knot", "The Bowline", "The Clove Hitch"]
for page in 1...6 {
    ctx.beginPDFPage(nil)
    let chapter = chapters[(page - 1) / 2]
    let text = "\(chapter)\n\nPage \(page). The rope is passed over and under, and drawn tight. A good knot holds when it must and comes undone when asked. Practise it with your eyes shut until your hands know it."
    let attr = NSAttributedString(string: text, attributes: [kCTFontAttributeName as NSAttributedString.Key: CTFontCreateWithName("Times-Roman" as CFString, 16, nil)])
    let frame = CTFramesetterCreateFrame(CTFramesetterCreateWithAttributedString(attr), CFRange(), CGPath(rect: box.insetBy(dx: 40, dy: 50), transform: nil), nil)
    CTFrameDraw(frame, ctx)
    ctx.endPDFPage()
}
ctx.closePDF()
let doc = PDFDocument(url: out)!
let root = PDFOutline()
for (i, c) in chapters.enumerated() {
    let item = PDFOutline()
    item.label = c
    item.destination = PDFDestination(page: doc.page(at: i * 2)!, at: CGPoint(x: 0, y: 595))
    root.insertChild(item, at: i)
}
doc.outlineRoot = root
doc.write(to: out)
print("wrote \(out.path), \(doc.pageCount) pages")
