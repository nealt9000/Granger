import GrangerCoreKit
import SwiftUI

/// The card under an article (Android's ArticleFooterCard, Plan LL1 Task 9): "In your library" and a line of what it
/// holds; a tap opens [ArticleFooterSheet]. ≈ Android shows it once the page is read to its end; on the iPhone it
/// rides with the bars.
struct ArticleFooterCard: View {
    let footer: ArticleFooter
    let onOpen: () -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        Button(action: onOpen) {
            VStack(alignment: .leading, spacing: 2) {
                Text(FooterWords.title).font(Spectral.medium(18)).foregroundStyle(palette.onSurface)
                Text(FooterWords.parts(footer)).font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(2)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
            .shadow(color: .black.opacity(0.12), radius: 3, y: 1)
        }
        .buttonStyle(.plain)
        .padding(.horizontal, 16).padding(.bottom, 8)
        .accessibilityIdentifier("article-footer-card")
    }
}

/// Everything the library holds about the article (Android's ArticleFooterSheet): its book, the passages in your
/// books (a tap opens the book there), how many more books mention it, and the author's books.
struct ArticleFooterSheet: View {
    let footer: ArticleFooter
    let onOpenBook: (String) -> Void
    let onOpenPassage: (String, BookMention) -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if !footer.thisWork.isEmpty {
                    heading(FooterWords.title)
                    ForEach(footer.thisWork, id: \.bookId) { book in row(book, read: true) }
                }
                if !footer.inBooks.isEmpty {
                    heading(FooterWords.inBooksHeading)
                    ForEach(footer.inBooks, id: \.book.bookId) { hit in
                        VStack(alignment: .leading, spacing: 4) {
                            HStack {
                                Text(hit.book.title).font(.subheadline.weight(.semibold)).lineLimit(2)
                                Spacer()
                                Text(FooterWords.mentions(Int(hit.count))).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                            }
                            ForEach(Array(hit.passages.enumerated()), id: \.offset) { _, m in
                                Button { onOpenPassage(hit.book.bookId, m) } label: {
                                    Text("\(FooterWords.lead(m))\(Text(m.quote).fontWeight(.semibold).foregroundColor(palette.primary))…")
                                        .font(.callout).foregroundStyle(palette.onSurface).lineLimit(2).multilineTextAlignment(.leading)
                                        .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 6)
                                }
                                .buttonStyle(.plain)
                            }
                        }
                    }
                    let more = Int(footer.inBooksTotal) - footer.inBooks.count
                    if more > 0 { Text(FooterWords.moreBooks(more)).font(.callout).foregroundStyle(palette.onSurfaceVariant) }
                }
                if !footer.byAuthor.isEmpty {
                    heading(FooterWords.byAuthorHeading)
                    ForEach(footer.byAuthor, id: \.bookId) { book in row(book, read: false) }
                }
            }
            .padding(24)
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("article-footer-sheet")
    }

    private func heading(_ s: String) -> some View { Text(s).font(.footnote.weight(.medium)).foregroundStyle(palette.onSurfaceVariant) }

    private func row(_ book: LibraryBook, read: Bool) -> some View {
        Button { onOpenBook(book.bookId) } label: {
            HStack(spacing: 12) {
                if let cover = book.cover, let image = UIImage(contentsOfFile: cover) {
                    Image(uiImage: image).resizable().scaledToFill().frame(width: 40, height: 60).clipShape(RoundedRectangle(cornerRadius: 4))
                } else {
                    Color.clear.frame(width: 40, height: 60)
                }
                Text(book.title).font(.subheadline.weight(.semibold)).lineLimit(2).foregroundStyle(palette.onSurface)
                Spacer()
                if read { Text(FooterWords.read).foregroundStyle(palette.primary) }
            }
        }
        .buttonStyle(.plain)
    }
}

/// The footer's words (Android's strings_reader.xml) and its summary line.
enum FooterWords {
    static let title = L("In your library")
    static let thisWork = L("You have this book")
    static let inBooksHeading = L("In your books")
    static let byAuthorHeading = L("By this author in your library")
    static let read = L("Read")
    static func inBooks(_ n: Int) -> String { L("Mentioned in \(n) of your books") }
    static func byAuthor(_ n: Int) -> String { L("\(n) books by this author") }
    static func mentions(_ n: Int) -> String { L("\(n) mentions") }
    static func moreBooks(_ n: Int) -> String { L("and \(n) more of your books") }

    /// The card's line: the work on the phone, how many books mention the subject, how many by this author.
    static func parts(_ f: ArticleFooter) -> String {
        [
            f.thisWork.isEmpty ? nil : thisWork,
            f.inBooks.isEmpty ? nil : inBooks(Int(f.inBooksTotal)),
            f.byAuthor.isEmpty ? nil : byAuthor(f.byAuthor.count),
        ].compactMap { $0 }.joined(separator: " · ")
    }

    /// A passage's words before the mention, from a word's start ("…as he goes from Madrid to").
    static func lead(_ m: BookMention) -> String {
        guard let context = LinkMentions.shared.contextOf(before: m.before) else { return "" }
        let paragraph = m.before.components(separatedBy: "\n").last ?? m.before
        let cut = paragraph.count > context.count && !(context.first?.isWhitespace ?? true)
        let words = cut ? (context.firstIndex(of: " ").map { String(context[context.index(after: $0)...]) } ?? "") : context
        let trimmed = words.drop { $0.isWhitespace }
        return trimmed.isEmpty ? "" : "…" + trimmed
    }
}
