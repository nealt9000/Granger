import GrangerCoreKit
import QuickLook
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

    /// A pack article's title from its entry (its path is no title: `based.cooking/bread.html`).
    @State private var packTitle: String?
    @State private var document: URL?
    private var title: String { packTitle ?? GrangerCoreEntry.shared.titleOfKey(key: path) }

    var body: some View {
        Group {
            if let core {
                ArticleWebView(
                    pack: pack ?? core.pack(for: lang), path: path, fragment: fragment,
                    source: model.tracking(core), textScale: settings.textScale, dark: scheme == .dark, controller: controller,
                    onToggleChrome: { withAnimation(.easeInOut(duration: 0.2)) { chrome.toggle() } },
                    onOpenArticle: { linkPack, p, _ in
                        // A pack's PDF or EPUB (Plan I4): handed on to Quick Look, not opened as an article.
                        if pack != nil, Self.isDocument(p) { openDocument(pack: linkPack, path: p); return }
                        openRoute(pack == nil ? .topic(lang: lang, path: p) : .article(packId: linkPack, path: p))
                    },
                    // Recently open lists Wikipedia online's articles (a pack article's place there comes with the shelf, I5).
                    onLoaded: { if pack == nil { recent.add(lang: lang, path: path, title: title) } }
                )
                .ignoresSafeArea(edges: chrome ? [] : .all)
            }
        }
        .background(palette.background)
        .navigationTitle(title)
        .quickLookPreview($document)
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
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showText) {
            TextSizeSheet().presentationDetents([.height(200)])
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
    static func isDocument(_ path: String) -> Bool {
        let p = path.lowercased().components(separatedBy: "?")[0]
        return p.hasSuffix(".pdf") || p.hasSuffix(".epub")
    }

    /// The document out of its pack into Caches, then shown.
    func openDocument(pack: String, path: String) {
        guard let core else { return }
        Task {
            let url: URL? = await Task.detached {
                guard let item = core.library.archive(packId: pack)?.read(path: path) else { return nil }
                let dir = FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0].appendingPathComponent("pack-files", isDirectory: true)
                try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
                let name = (path.components(separatedBy: "?")[0] as NSString).lastPathComponent.removingPercentEncoding ?? "document"
                let to = dir.appendingPathComponent(name)
                return (try? GrangerCoreEntry.shared.bytes(of: item.bytes).write(to: to)) != nil ? to : nil
            }.value
            document = url
        }
    }
}
