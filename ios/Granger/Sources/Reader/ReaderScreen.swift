import GrangerCoreKit
import SwiftUI

/// The full article (Android's reader for an online Wikipedia page): the page in Granger's look, Contents, text size,
/// and a tap in the middle of the page to hide or show the bars.
struct ReaderScreen: View {
    let lang: String
    let path: String
    let fragment: String?
    /// A pack's id for a pack article (Plan I4 Task 5); nil for Wikipedia online in [lang].
    var pack: String? = nil

    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.colorScheme) private var scheme
    @Environment(AppSettings.self) private var settings
    @Environment(RecentStore.self) private var recent
    @Environment(\.openRoute) private var openRoute
    @State private var model = ReaderModel()
    @State private var controller = PageController()
    @State private var chrome = true
    @State private var showContents = false
    @State private var showText = false
    /// What the library holds about the article (Plan LL1 Task 9): its book, In your books, the author's books.
    @State private var footer: ArticleFooter?
    @State private var showFooter = false

    /// A pack article's title from its entry (its path is no title: `based.cooking/bread.html`).
    @State private var packTitle: String?
    @State private var problem: String?
    @Environment(\.voice) private var voice
    /// The page follows the voice's sentence until the reader scrolls it (Android's following).
    @State private var following = true
    /// The pack the article is read from: its own, or Wikipedia online's for [lang].
    private var packId: String { pack ?? core?.pack(for: lang) ?? "" }
    private var readKey: String { "pack:\(packId)/\(path)" }
    private var readingThisPage: Bool { voice?.ui.now?.sourceKey == readKey }
    private var title: String { packTitle ?? GrangerCoreEntry.shared.titleOfKey(key: path) }

    var body: some View {
        Group {
            if let core {
                ArticleWebView(
                    pack: pack ?? core.pack(for: lang), path: path, fragment: fragment,
                    source: model.tracking(core), textScale: settings.textScale, dark: scheme == .dark, controller: controller,
                    onToggleChrome: { withAnimation(.easeInOut(duration: 0.2)) { chrome.toggle() } },
                    onOpenArticle: { linkPack, p, _ in
                        // A pack's PDF or EPUB opens in Granger's reader (Plan I5 Task 5), not as an article.
                        if pack != nil, Self.isDocument(p) { openDocument(pack: linkPack, path: p); return }
                        openRoute(pack == nil ? .topic(lang: lang, path: p) : .article(packId: linkPack, path: p))
                    },
                    // Recently open lists Wikipedia online's articles (a pack article's place there comes with the shelf, I5).
                    onLoaded: {
                        if pack == nil { recent.add(lang: lang, path: path, title: title) }
                        markSentence()
                    },
                    onUserScroll: { if readingThisPage { following = false } },
                    onReadFromHere: { readFromHere() }
                )
                .ignoresSafeArea(edges: chrome ? [] : .all)
            }
        }
        .background(palette.background)
        .overlay(alignment: .bottom) {
            if chrome, let footer { ArticleFooterCard(footer: footer) { showFooter = true }.transition(.opacity) }
        }
        .navigationTitle(title)
        .packDocumentProblem($problem)
        // The sentence being read, marked on the page and followed (Android's ReadAloudScript mark).
        .onChange(of: voice?.progress) { markSentence() }
        .onChange(of: readingThisPage) { markSentence() }
        .task(id: path) {
            guard let core else { return }
            let found = try? await core.library.articleFooter(packId: pack ?? core.pack(for: lang), path: path)
            footer = (found?.isEmpty ?? true) ? nil : found
        }
        .sheet(isPresented: $showFooter) {
            if let footer {
                ArticleFooterSheet(footer: footer) { id in
                    showFooter = false
                    openRoute(.book(id: id))
                } onOpenPassage: { id, m in
                    showFooter = false
                    openRoute(.bookAt(id: id, passage: BookPassage(href: m.href, quote: m.quote, before: m.before)))
                }
                .halfSheet([.medium, .large])
            }
        }
        .task(id: pack) {
            guard let core, let pack else { return }
            let p = path
            packTitle = await Task.detached { core.library.archive(packId: pack)?.entry(path: p)?.title }.value.flatMap { $0.isEmpty ? nil : $0 }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(chrome ? .visible : .hidden, for: .navigationBar, .tabBar)
        .statusBarHidden(!chrome)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                listenButton
                Button { showContents = true } label: { Image(systemName: "list.bullet") }
                    .accessibilityLabel("Contents")
                    .disabled(model.headings.isEmpty)
                Button { controller.find() } label: { Image(systemName: "magnifyingglass") }
                    .accessibilityLabel("Find in page")
                Button { showText = true } label: { Image(systemName: "textformat.size") }
                    .accessibilityLabel("Text settings")
                if pack == nil { ReaderKeepMenu(lang: lang, title: path) }
            }
        }
        .sheet(isPresented: $showContents) {
            ContentsSheet(headings: model.headings) { anchor in
                showContents = false
                controller.scroll(to: anchor)
            }
            .halfSheet([.medium, .large])
        }
        .sheet(isPresented: $showText) {
            TextSizeSheet().halfSheet([.height(200)])
        }
    }
}

@Observable
final class ReaderModel {
    var headings: [Heading] = []

    /// The page source, wrapped so the page's HTML, as served, gives Contents its sections (no second fetch).
    func tracking(_ source: PageSource) -> PageSource {
        TrackingSource(inner: source) { [weak self] html in
            let found = GrangerCoreEntry.shared.headings(html: html)
            Task { @MainActor in self?.headings = found }
        }
    }
}

/// Passes requests through, and hands the first HTML page it serves to [onPage].
private final class TrackingSource: PageSource {
    let inner: PageSource
    let onPage: (String) -> Void
    private var seen = false
    private let lock = NSLock()

    init(inner: PageSource, onPage: @escaping (String) -> Void) {
        self.inner = inner
        self.onPage = onPage
    }

    func item(pack: String, path: String) -> (data: Data, mimeType: String)? {
        let got = inner.item(pack: pack, path: path)
        if let got, got.mimeType.hasPrefix("text/html") {
            lock.lock(); let first = !seen; seen = true; lock.unlock()
            if first { onPage(String(decoding: got.data, as: UTF8.self)) }
        }
        return got
    }
}

/// Contents: the article's sections, tap to go there.
struct ContentsSheet: View {
    let headings: [Heading]
    let onPick: (String) -> Void

    var body: some View {
        NavigationStack {
            List(headings, id: \.anchor) { h in
                Button { onPick(h.anchor) } label: {
                    Text(h.title)
                        .font(h.level == 2 ? Spectral.medium(18) : .body)
                        .padding(.leading, h.level == 3 ? 16 : 0)
                }
            }
            .navigationTitle("Contents")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// Text size (Android's Aa): 80 % to 200 %.
struct TextSizeSheet: View {
    @Environment(AppSettings.self) private var settings

    var body: some View {
        @Bindable var settings = settings
        VStack(alignment: .leading, spacing: 16) {
            Text("Text size").font(Spectral.medium(20))
            HStack {
                Image(systemName: "textformat.size.smaller")
                Slider(value: $settings.textScale, in: 0.8...2.0, step: 0.1)
                    .accessibilityValue("\(Int((settings.textScale * 100).rounded())) percent")
                Image(systemName: "textformat.size.larger")
            }
            Text("\(Int((settings.textScale * 100).rounded())) %").font(.footnote).foregroundStyle(.secondary)
        }
        .padding(24)
    }
}

extension ReaderScreen {
    /// Listen (Android's VoiceButton): start on this page, else pause or resume the voice reading it.
    @ViewBuilder var listenButton: some View {
        if readingThisPage, let voice {
            Button { voice.voice.player.playPause() } label: { Image(systemName: voice.ui.playing ? "pause.circle" : "headphones.circle") }
                .accessibilityLabel(voice.ui.playing ? "Pause" : "Resume")
                .accessibilityIdentifier("reader-listen")
        } else {
            Button { listen() } label: { Image(systemName: "headphones") }
                .accessibilityLabel("Listen")
                .accessibilityIdentifier("reader-listen")
        }
    }

    /// Starts the voice at the first block the page shows, or at the article's saved place when that is further on.
    func listen() {
        guard let voice else { return }
        following = true
        let (pack, p, t) = (packId, path, title)
        controller.evaluate(GrangerCoreEntry.shared.readFirstVisible()) { json in
            let ref = GrangerCoreEntry.shared.blockRef(json: json)
            voice.voice.readArticle(packId: pack, path: p, title: t, blockId: ref?.id, offset: ref.flatMap { $0.offset >= 0 ? KotlinInt(value: $0.offset) : nil })
        }
    }

    /// Read from here: the selection's sentence.
    func readFromHere() {
        guard let voice else { return }
        following = true
        let (pack, p, t) = (packId, path, title)
        controller.evaluate(GrangerCoreEntry.shared.readSelectionBlock()) { json in
            guard let ref = GrangerCoreEntry.shared.blockRef(json: json) else { return }
            voice.voice.readArticleFrom(packId: pack, path: p, title: t, blockId: ref.id, offset: ref.offset >= 0 ? KotlinInt(value: ref.offset) : nil)
        }
    }

    /// Marks the voice's sentence when it's on this page and Settings › Voice says to; clears it otherwise.
    func markSentence() {
        guard let voice, controller.ready else { return }
        guard voice.voice.prefs.markSentence.value.boolValue, let mark = voice.voice.articleMark(packId: packId, path: path) else {
            controller.evaluate(GrangerCoreEntry.shared.readClear())
            return
        }
        controller.evaluate(GrangerCoreEntry.shared.readMark(blockId: mark.blockId, prefix: mark.prefix, start: mark.start, end: mark.end, scroll: following))
    }

    static func isDocument(_ path: String) -> Bool {
        let p = path.lowercased().components(separatedBy: "?")[0]
        return p.hasSuffix(".pdf") || p.hasSuffix(".epub")
    }

    /// The document into the shelf and the reader, over the article, so Back returns to the page (Android's Plan P2).
    func openDocument(pack: String, path: String) {
        guard let core else { return }
        Task { problem = await PackDocumentOpener.open(core: core, pack: pack, path: path, linkText: "", openRoute: openRoute) }
    }
}
