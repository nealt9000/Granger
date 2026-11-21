import GrangerCoreKit
import ImageIO
import SwiftUI

/// A 2:3 book cover (Android's BookCover): the book's cover file (an EPUB's own picture, a PDF's first page from
/// PDFKit), else a Gutenberg book's cover read from its pack before its first open (Android's GutenbergCovers), else the
/// generated tile: the title over a lantern rule, the same in Field and Night, as a cover is an object.
struct BookCoverView: View {
    let book: Book
    @Environment(\.core) private var core
    @State private var image: UIImage?

    var body: some View {
        Color.clear
            .aspectRatio(2.0 / 3.0, contentMode: .fit)
            .overlay {
                GeometryReader { geo in
                    if let image {
                        Image(uiImage: image).resizable().scaledToFill().frame(width: geo.size.width, height: geo.size.height).clipped()
                    } else {
                        CoverTile(title: book.title, width: geo.size.width)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 6))
            .accessibilityHidden(true)
            .task(id: "\(book.id)|\(book.coverPath ?? "")") {
                guard let core else { return }
                image = await CoverImages.load(book, core: core)
            }
    }
}

/// The generated cover: Gunmetal, the title in Spectral, a lantern rule under it.
struct CoverTile: View {
    let title: String
    let width: CGFloat

    var body: some View {
        let size: CGFloat = width < 96 ? 12 : min(max(width * 0.085, 14), 26)
        ZStack(alignment: .topLeading) {
            Color(red: 0x24 / 255, green: 0x29 / 255, blue: 0x2A / 255)
            VStack(alignment: .leading, spacing: 6) {
                Text(title)
                    .font(.custom("Spectral-Medium", size: size))
                    .foregroundStyle(Color(red: 0xEF / 255, green: 0xE4 / 255, blue: 0xD0 / 255))
                    .lineLimit(5)
                    .minimumScaleFactor(0.8)
                Rectangle().fill(Color(red: 0xF0 / 255, green: 0xA3 / 255, blue: 0x5E / 255)).frame(width: width * 0.35, height: 2)
            }
            .padding(width < 96 ? 6 : 10)
        }
    }
}

/// Covers read off the main thread and scaled to a cell (ImageIO thumbnails: a huge picture never decodes whole), kept
/// in memory while the app runs.
enum CoverImages {
    nonisolated(unsafe) private static let cache = NSCache<NSString, UIImage>()
    /// The longest side kept: a grid cell's width, three times over.
    static let side = 480

    static func load(_ book: Book, core: Core) async -> UIImage? {
        let path: String?
        if let own = book.coverPath, FileManager.default.fileExists(atPath: own) { path = own }
        else { path = try? await core.library.packCover(book: book) }
        guard let path else { return nil }
        if let hit = cache.object(forKey: path as NSString) { return hit }
        let image = await Task.detached(priority: .utility) { thumbnail(path) }.value
        if let image { cache.setObject(image, forKey: path as NSString) }
        return image
    }

    static func thumbnail(_ path: String) -> UIImage? {
        guard let source = CGImageSourceCreateWithURL(URL(fileURLWithPath: path) as CFURL, nil) else { return nil }
        let options: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: side,
        ]
        guard let cg = CGImageSourceCreateThumbnailAtIndex(source, 0, options as CFDictionary) else { return nil }
        return UIImage(cgImage: cg)
    }
}
