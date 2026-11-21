import Foundation
import GrangerCoreKit
import ReadiumNavigator
import ReadiumShared
import ReadiumStreamer
import UIKit

/// Opens book files with Readium's Swift toolkit (Plan I5 call 1), as Android's ReadiumBooks does with the Kotlin one.
/// Nothing is fetched: the HTTP client is only what the parser asks for.
@MainActor
enum ReadiumBooks {
    private static let http = DefaultHTTPClient()
    private static let retriever = AssetRetriever(httpClient: http)
    private static let opener = PublicationOpener(
        parser: DefaultPublicationParser(httpClient: http, assetRetriever: retriever, pdfFactory: DefaultPDFDocumentFactory())
    )

    /// The publication at [path]; nil when it can't be read as a book.
    static func open(_ path: String) async -> Publication? {
        guard let url = FileURL(url: URL(fileURLWithPath: path)),
              let asset = try? await retriever.retrieve(url: url).get()
        else { return nil }
        return try? await opener.open(asset: asset, allowUserInteraction: false).get()
    }
}

/// The words Android's reader shows (strings_ebook.xml), for the iPhone's reader.
enum BookWords {
    static let bookGone = "This book isn't on the phone any more. Check the SD card, or download it again from Granger."
    static let bookDamaged = "This book couldn't be opened. Try removing it, then download or import it again."
    static let searchInBook = "Search in book"
    static let searchInThisBook = "Search in this book"
    static func nothingFound(_ q: String) -> String { "Nothing found for “\(q)”." }
    static func pageOf(_ p: Int, _ n: Int) -> String { "p. \(p) of \(n)" }
    static func percent(_ p: Int) -> String { "\(p)%" }
    static let noContents = "This book has no contents list."
    static let noPdfContents = "This PDF has no contents list of its own. If it has a contents page, it's in the pages."
    static let untitled = "Untitled"
    static let bookmark = "Bookmark"
    static let noBookmarks = "No bookmarks yet. Tap the star on the page you want to keep."
    static let noHighlights = "No highlights yet. Select text in the book to highlight it."
    static let cantFind = "Can't find this passage in the current edition"
    static func noWiktionaryEntry(_ w: String) -> String { "No entry for “\(w)” in Wiktionary" }
    static let noDictionary = "No dictionary app is installed"
    static func lookupFailed(_ w: String) -> String { "Couldn’t look up “\(w)”" }

    static func packProblem(_ p: PackDocumentProblem) -> String {
        switch p {
        case .unreadablePack: "Couldn't open this PDF: the pack isn't readable."
        case .notInPack: "Couldn't open this PDF: it isn't in this pack."
        case .noSpace: "Couldn't open this PDF: not enough space for it."
        case .copyFailed: "Couldn't open this PDF: copying it out of the pack failed."
        case .damaged: "Couldn't open this PDF: it is damaged or protected."
        }
    }

    static let notInPackList = "Couldn't open this book: it isn't in this pack's list."
}

/// The system dictionary for Define when no Wiktionary pack is installed (Plan I5 call 8): `UIReferenceLibraryViewController`,
/// where Android hands the word to a dictionary app.
final class IosSystemDictionary: NSObject, SystemDictionary {
    func available() -> Bool { true }

    func define(text: String) -> Bool {
        let word = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !word.isEmpty else { return false }
        DispatchQueue.main.async {
            guard let top = UIApplication.shared.topViewController else { return }
            top.present(UIReferenceLibraryViewController(term: word), animated: true)
        }
        return true
    }
}

extension UIApplication {
    /// The view controller on top, for a system sheet (the dictionary).
    var topViewController: UIViewController? {
        let root = connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.rootViewController
        var top = root
        while let next = top?.presentedViewController { top = next }
        return top
    }
}

/// The e-book and PDF reader's state on the iPhone (Android's EbookReaderViewModel and EbookUi): the shared
/// `EbookReaderModel` keeps the marks, search and text settings; this keeps Readium's publication, the place, the page
/// count, the contents' current row, the bars and the decorations, and saves the place as Readium's locator JSON.
@Observable @MainActor
final class BookReaderModel {
    let bookId: String
    private(set) var title = ""
    private(set) var loading = true
    private(set) var error: String?
    private(set) var publication: Publication?
    private(set) var initial: Locator?
    private(set) var locator: Locator?
    private(set) var positionsCount = 0
    private(set) var toc: [TocRow] = []
    private(set) var isPdf = false
    private(set) var fixedLayout = false
    /// The bars over the page; a page turn by hand hides them, a middle tap toggles them.
    var chrome = true
    /// The shared state: marks, search, prefs, the open panel and the highlight being edited.
    private(set) var shared: EbookReaderState?
    /// The search hit last jumped to, marked on the page until the next search or a panel opens.
    var searchHit: Locator?
    /// Said once (Define's outcome), then cleared.
    var notice: String?
    /// The book's language (its first), for Define.
    private(set) var language: String?

    private var owned: OwnedViewModel<EbookReaderModel>?
    private var watching: Task<Void, Never>?
    private var anchorStarts: [String: [String: KotlinDouble]] = [:]
    private var saveTask: Task<Void, Never>?
    private var unsaved: Locator?
    private weak var core: Core?
    /// The navigator on screen (set by the host); jumps go through it.
    weak var navigator: (any Navigator)?
    /// When a finger last turned or dragged the page (Android's onPageTouch): a page change within a second of it is a
    /// turn by hand, which hides the bars; any other (opening, a jump, the slider, new text settings) leaves them.
    private var touchedAt = Date.distantPast

    init(bookId: String) { self.bookId = bookId }

    var model: EbookReaderModel? { owned?.model }
    var prefs: ReadingPrefs? { shared?.prefs }
    var highlights: [BookAnnotation] { shared?.highlights ?? [] }
    var bookmarks: [BookAnnotation] { shared?.bookmarks ?? [] }
    var search: BookSearchUi? { shared?.search }
    var panel: Panel? { shared?.panel }

    // MARK: Opening

    func open(core: Core) async {
        guard owned == nil else { return }
        self.core = core
        let o = core.library.ebookReader()
        owned = o
        let flow = o.model.reader
        watching = Task { @MainActor [weak self] in
            for await s in flow { self?.sharedChanged(s) }
        }
        let id = bookId
        guard let book = try? await core.library.openableBook(id: id) else {
            fail(BookWords.bookGone)
            return
        }
        title = book.title
        guard let opened = await ReadiumBooks.open(book.filePath) else {
            fail(BookWords.bookDamaged)
            return
        }
        let pdf = opened.conforms(to: .pdf)
        let saved = try? await core.library.position(bookId: id)
        initial = await StartPlace.choose(opened, savedJson: saved?.locatorJson)
        isPdf = pdf
        fixedLayout = !pdf && opened.metadata.layout == .fixed
        language = opened.metadata.languages.first.map { String($0.prefix { $0 != "-" }).lowercased() }
        let rows = Self.flatten(opened.manifest.tableOfContents, depth: 0)
        toc = rows
        publication = opened
        o.model.openBook(id: id, searcher: pdf ? nil : ReadiumSearcher(opened).shared, toc: rows)
        loading = false
        try? await core.library.bookOpened(book: book)
        if pdf && rows.allSatisfy({ (GrangerCore.TocPlaceKt.pdfPageOf(href: $0.href)?.intValue ?? 0) < 1 }) {
            await pdfOutlineInstead(opened, file: book.filePath)
        }
        if !pdf { await readAnchorStarts(opened) }
        positionsCount = (try? await opened.positions().get().count) ?? 0
    }

    private func fail(_ message: String) {
        loading = false
        error = message
        chrome = true
    }

    private func sharedChanged(_ s: EbookReaderState) {
        let wasSearching = shared?.search.query
        shared = s
        if s.search.query != wasSearching && s.search.running { searchHit = nil }
    }

    func close() {
        flush()
        watching?.cancel()
        owned?.close()
        owned = nil
    }

    private static func flatten(_ links: [ReadiumShared.Link], depth: Int) -> [TocRow] {
        links.flatMap { l in
            [TocRow(title: (l.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines), href: l.href, depth: Int32(depth))] + flatten(l.children, depth: depth + 1)
        }
    }

    /// PDFKit's outline when Readium's has no page to go to (Android reads PdfBox's then).
    private func pdfOutlineInstead(_ opened: Publication, file: String) async {
        guard let href = opened.readingOrder.first?.href else { return }
        let entries = await background { PdfInspector.shared.outline(path: file) }
        toc = entries.map { TocRow(title: $0.title, href: "\(href)#page=\($0.page)", depth: $0.depth) }
    }

    /// Reads the files holding several contents rows, to tell which of them the reader is in.
    private func readAnchorStarts(_ opened: Publication) async {
        let wanted = TocPlace.shared.anchorsByFile(toc: toc)
        guard !wanted.isEmpty else { return }
        var starts: [String: [String: KotlinDouble]] = [:]
        for (file, fragments) in wanted {
            guard let url = AnyURL(string: file), let resource = opened.get(url), let data = try? await resource.read().get() else { continue }
            starts[file] = TocPlace.shared.anchorStarts(html: String(decoding: data, as: UTF8.self), fragments: fragments)
        }
        anchorStarts = starts
    }

    // MARK: The place

    /// The navigator moved: the label, the contents row and the star follow; the place is saved once it has stayed put
    /// for a second. A move the reader didn't ask for, while the bars are up, is a turn by hand: the bars slide away.
    func onLocator(_ l: Locator) {
        let before = locator
        locator = l
        if let before, Date().timeIntervalSince(touchedAt) < 1, chrome, panel == nil, shared?.editingHighlight == nil, moved(before, l) { chrome = false }
        unsaved = l
        saveTask?.cancel()
        saveTask = Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(1))
            guard !Task.isCancelled else { return }
            await self?.save(l)
        }
    }

    private func moved(_ a: Locator, _ b: Locator) -> Bool {
        a.href != b.href || a.locations.progression != b.locations.progression || a.locations.position != b.locations.position
    }

    /// Saves a pending place now (leaving the reader, the app going to the background).
    func flush() {
        guard let l = unsaved else { return }
        saveTask?.cancel()
        Task { @MainActor in await save(l) }
    }

    private func save(_ l: Locator) async {
        if unsaved == l { unsaved = nil }
        guard let core, let json = try? l.jsonString() else { return }
        let chapter = placeTitle(l)
        let progress = l.locations.totalProgression ?? 0
        try? await core.library.savePosition(bookId: bookId, locatorJson: json, totalProgression: progress, chapterTitle: chapter)
        let percent = "\(min(100, max(0, Int((progress * 100).rounded()))))%"
        onPlace?(chapter.isEmpty ? percent : "\(chapter) · \(percent)")
    }

    /// Told the reading place after each save ("Chapter 3 · 42%"), for Recently open.
    var onPlace: ((String) -> Void)?

    /// What the rail and Continue call a place: the chapter, or in a PDF its outline entry, else "Page n".
    private func placeTitle(_ l: Locator) -> String {
        if let t = l.title, !t.trimmingCharacters(in: .whitespaces).isEmpty { return t }
        guard isPdf, let page = l.locations.position else { return "" }
        if let i = GrangerCore.TocPlaceKt.pdfTocIndex(toc: toc, page: Int32(page))?.intValue, !toc[i].title.isEmpty { return toc[i].title }
        return "Page \(page)"
    }

    /// "Chapter · p. 120 of 410", or "Chapter · 29%" while the pages are uncounted.
    var progressLabel: String? {
        guard let l = locator else { return nil }
        let chapter = (l.title ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        let place: String
        if positionsCount > 0, let p = l.locations.position {
            place = BookWords.pageOf(p, positionsCount)
        } else {
            place = BookWords.percent(min(100, max(0, Int((l.locations.totalProgression ?? 0) * 100))))
        }
        return chapter.isEmpty ? place : "\(chapter) · \(place)"
    }

    /// The contents row the reader is in (a PDF: the last outline entry at or before the page).
    var currentTocIndex: Int? {
        guard let l = locator else { return nil }
        if isPdf { return l.locations.position.flatMap { GrangerCore.TocPlaceKt.pdfTocIndex(toc: toc, page: Int32($0))?.intValue } }
        return TocPlace.shared.index(toc: toc, href: l.href.string, progression: l.locations.progression.map { KotlinDouble(value: $0) }, anchorStarts: anchorStarts)?.intValue
    }

    // MARK: Moving

    func go(to target: Locator) {
        chrome = false
        if panel != nil { model?.open(panel: nil) }
        Task { _ = await navigator?.go(to: target, options: NavigatorGoOptions(animated: false)) }
    }

    /// A contents row: the chapter file it points into, with its #fragment.
    func goTo(href: String) {
        guard let publication, let url = AnyURL(string: href) else { return }
        if isPdf, let page = GrangerCore.TocPlaceKt.pdfPageOf(href: href)?.intValue {
            Task {
                guard let positions = try? await publication.positions().get(), !positions.isEmpty else { return }
                go(to: positions[min(max(page, 1), positions.count) - 1])
            }
            return
        }
        guard let link = publication.readingOrder.first(where: { $0.url().removingFragment().isEquivalentTo(url.removingFragment()) }) else { return }
        Task {
            guard var target = await publication.locate(link) else { return }
            if let fragment = url.fragment { target = target.copy(locations: { $0.fragments = [fragment] }) }
            go(to: target)
        }
    }

    /// The slider's release: [p] is the share of the whole book.
    func goTo(progression p: Double) {
        guard let publication else { return }
        Task {
            guard let target = await publication.locate(progression: min(1, max(0, p))) else { return }
                _ = await navigator?.go(to: target, options: NavigatorGoOptions(animated: false))
        }
    }

    /// A bookmark or highlight; one whose chapter isn't in this edition is placed by its progression, else ignored.
    func goTo(mark: BookAnnotation) {
        guard let publication, let stored = try? Locator(jsonString: mark.locatorJson) else { return }
        Task {
            guard let target = await StartPlace.repair(publication, stored) else { return }
            go(to: target)
        }
    }

    func goTo(row: BookSearchRow) {
        guard let json = row.locatorJson, let target = try? Locator(jsonString: json) else { return }
        if let id = row.markId?.int64Value, unplaceable.contains(id) { return }
        searchHit = row.markId == nil ? target : nil
        go(to: target)
    }

    func toggleChrome() {
        if error == nil { chrome.toggle() }
    }

    func prefsChanged() { touchedAt = .distantPast }

    /// A finger turned or dragged the page.
    func pageTouched() { touchedAt = Date() }

    // MARK: Marks

    /// The highlights drawn on the page; those whose chapter isn't in this edition are listed but not drawn.
    var decorations: [Decoration] {
        guard let publication else { return [] }
        return highlights.compactMap { m in
            guard let l = try? Locator(jsonString: m.locatorJson), publication.linkWithHREF(l.href) != nil else { return nil }
            return Decoration(id: String(m.id), locator: l, style: .highlight(tint: UIColor(argb: HighlightColors.shared.argb(name: m.color))))
        }
    }

    var unplaceable: Set<Int64> {
        guard let publication else { return [] }
        return Set(highlights.filter { m in (try? Locator(jsonString: m.locatorJson)).map { publication.linkWithHREF($0.href) == nil } ?? true }.map(\.id))
    }

    var isBookmarkedHere: Bool {
        guard let here = locator else { return false }
        return bookmarks.contains { (try? Locator(jsonString: $0.locatorJson)).map { Self.isSamePlace($0, here) } ?? false }
    }

    func toggleBookmark() {
        guard let here = locator, let json = try? here.jsonString() else { return }
        let text = bookmarkText(here)
        model?.toggleBookmark(hereJson: json, text: { text }, isHere: { other in
            KotlinBoolean(value: (try? Locator(jsonString: other)).map { Self.isSamePlace($0, here) } ?? false)
        })
    }

    /// A PDF's bookmark is named by its page, after the outline entry it falls in.
    private func bookmarkText(_ l: Locator) -> String {
        guard isPdf, let page = l.locations.position else { return l.title ?? "" }
        let entry = GrangerCore.TocPlaceKt.pdfTocIndex(toc: toc, page: Int32(page)).flatMap { toc[$0.intValue].title.isEmpty ? nil : toc[$0.intValue].title }
        return [entry, "p. \(page)"].compactMap { $0 }.joined(separator: " · ")
    }

    /// The same file and page position; without positions on both, the progression within 0.0005.
    static func isSamePlace(_ a: Locator, _ b: Locator) -> Bool {
        guard a.href.removingFragment().isEquivalentTo(b.href.removingFragment()) else { return false }
        if let pa = a.locations.position, let pb = b.locations.position { return pa == pb }
        guard let ta = a.locations.totalProgression, let tb = b.locations.totalProgression else { return false }
        return abs(ta - tb) <= 0.0005
    }

    func highlight(_ selection: Selection) async {
        guard let json = try? selection.locator.jsonString(), let text = selection.locator.text.highlight?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return }
        _ = try? await model?.highlight(locatorJson: json, text: text, color: "yellow")
    }

    func note(_ selection: Selection) async {
        guard let json = try? selection.locator.jsonString(), let text = selection.locator.text.highlight?.trimmingCharacters(in: .whitespacesAndNewlines), !text.isEmpty else { return }
        _ = try? await model?.note(locatorJson: json, text: text)
    }

    var editing: BookAnnotation? {
        guard let id = shared?.editingHighlight?.int64Value else { return nil }
        return highlights.first { $0.id == id }
    }

    // MARK: Define

    /// Define: the word's entry in a Wiktionary pack (opened by [openEntry]), else the system dictionary. What to say, if anything.
    func define(_ text: String, core: Core, openEntry: (DictionaryEntry) -> Void) async -> String? {
        let word = GrangerCore.Dictionary.shared.normalise(selection: text)
        guard !word.isEmpty else { return nil }
        let system = IosSystemDictionary()
        guard let choice = try? await core.library.dictionaryChoice(systemDictionary: system.available()) else { return BookWords.lookupFailed(word) }
        switch onEnum(of: choice) {
        case let .wiktionary(w):
            guard let entry = try? await core.library.define(packId: w.packId, word: word, bookLanguage: language) else { return BookWords.noWiktionaryEntry(word) }
            openEntry(entry)
            return entry.note?.words
        case .processText:
            return system.define(text: word) ? nil : BookWords.noDictionary
        case .none:
            return BookWords.noDictionary
        }
    }
}

/// Search in the book through Readium's search service, as the shared `BookSearcher` (Android's ReadiumBookSearcher).
final class ReadiumSearcher {
    private let publication: Publication

    init(_ publication: Publication) { self.publication = publication }

    /// The shared side's searcher.
    var shared: IosBookSearcher {
        IosBookSearcher { [publication] query, max, done in
            Task {
                let hits = await ReadiumSearcher.search(publication, query: query, max: Int(truncating: max))
                _ = done(hits)
            }
        }
    }

    /// Up to [max] places where [query] occurs, in book order, read page by page until the iterator runs dry.
    static func search(_ publication: Publication, query: String, max: Int) async -> [BookTextHit] {
        guard publication.isSearchable, let iterator = try? await publication.search(query: query).get() else { return [] }
        defer { iterator.close() }
        var found: [Locator] = []
        while found.count < max, let page = try? await iterator.next().get() {
            found += page.locators
        }
        return found.prefix(max).compactMap { l in
            guard let json = try? l.jsonString() else { return nil }
            return BookTextHit(locatorJson: json, href: l.href.string, title: l.title, before: l.text.before, highlight: l.text.highlight, after: l.text.after)
        }
    }
}

/// Where a book opens (Android's StartLocator, without a search target yet): the saved place, or its progression
/// through the book when a rebuilt edition renamed its chapter, else the start. Never an href outside the reading order.
enum StartPlace {
    static func choose(_ publication: Publication, savedJson: String?) async -> Locator? {
        guard let savedJson, !savedJson.isEmpty, let stored = try? Locator(jsonString: savedJson) else { return nil }
        return await repair(publication, stored)
    }

    static func repair(_ publication: Publication, _ stored: Locator) async -> Locator? {
        if publication.readingOrder.contains(where: { $0.url().removingFragment().isEquivalentTo(stored.href.removingFragment()) }) { return stored }
        guard let total = stored.locations.totalProgression,
              let l = await publication.locate(progression: min(1, max(0, total))),
              publication.linkWithHREF(l.href) != nil
        else { return nil }
        return l
    }
}

extension UIColor {
    convenience init(argb: Int32) {
        let v = UInt32(bitPattern: argb)
        self.init(red: CGFloat((v >> 16) & 0xFF) / 255, green: CGFloat((v >> 8) & 0xFF) / 255, blue: CGFloat(v & 0xFF) / 255, alpha: CGFloat((v >> 24) & 0xFF) / 255)
    }
}
