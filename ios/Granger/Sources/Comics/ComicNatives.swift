import Foundation
import GrangerCoreKit
import ImageIO
import UIKit

/// What the shared comic rules need from the iPhone (Plan I5 call 6): CBR unpacked with libarchive (Android: junrar),
/// and the pictures drawn into a PDF with UIGraphicsPDFRenderer (Android: PdfBox). CBZ is the shared zip reader's.
/// Set once at launch with `ComicNatives.install()`.
final class ComicNatives: NSObject, IosComicPagesNatives {
    static func install() { IosComicPages.shared.useNatives(natives: ComicNatives()) }

    /// A picture bigger than this on a side isn't drawn (as Android's RailCovers.MAX_SIDE bounds a decode).
    static let maxSide = 12_000

    func unpackRar(archive: String, scratch: String, keep: @escaping (String) -> KotlinBoolean) -> [String]? {
        GRarArchive.unpack(archive, into: scratch) { keep($0).boolValue }
    }

    /// One page per picture that reads, at the picture's own size in points. A JPEG goes in as its own data (Core
    /// Graphics keeps a JPEG source as it is in a PDF, never re-encoded); other pictures as decoded.
    func writePdf(pictures: [String], out: String) -> Bool {
        let renderer = UIGraphicsPDFRenderer(bounds: CGRect(x: 0, y: 0, width: 612, height: 792))
        var pages = 0
        do {
            try renderer.writePDF(to: URL(fileURLWithPath: out)) { context in
                for path in pictures {
                    autoreleasepool {
                        guard let image = Self.image(path) else { return }
                        let size = CGSize(width: image.width, height: image.height)
                        context.beginPage(withBounds: CGRect(origin: .zero, size: size), pageInfo: [:])
                        let cg = context.cgContext
                        // PDF space is bottom-up; UIKit's page context is flipped to top-down, so draw the right way up.
                        cg.saveGState()
                        cg.translateBy(x: 0, y: size.height)
                        cg.scaleBy(x: 1, y: -1)
                        cg.draw(image, in: CGRect(origin: .zero, size: size))
                        cg.restoreGState()
                        pages += 1
                    }
                }
            }
        } catch {
            try? FileManager.default.removeItem(atPath: out)
            return false
        }
        if pages == 0 { try? FileManager.default.removeItem(atPath: out) }
        return pages > 0
    }

    private static func image(_ path: String) -> CGImage? {
        let url = URL(fileURLWithPath: path)
        guard let head = try? FileHandle(forReadingFrom: url).read(upToCount: 3), head.count >= 2 else { return nil }
        if head[0] == 0xFF, head[1] == 0xD8, let provider = CGDataProvider(url: url as CFURL),
           let jpeg = CGImage(jpegDataProviderSource: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent) {
            return jpeg.width <= maxSide && jpeg.height <= maxSide ? jpeg : nil
        }
        guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
              let props = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any],
              let w = props[kCGImagePropertyPixelWidth] as? Int, let h = props[kCGImagePropertyPixelHeight] as? Int,
              w > 0, h > 0, w <= maxSide, h <= maxSide else { return nil }
        return CGImageSourceCreateImageAtIndex(source, 0, nil)
    }
}
