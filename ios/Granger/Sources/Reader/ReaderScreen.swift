import GrangerCoreKit
import SwiftUI

/// The full article (Android's reader for an online Wikipedia page): the page in Granger's look, Contents, text size,
/// and a tap in the middle of the page to hide or show the bars.
struct ReaderScreen: View {
    let lang: String
    let path: String
    let fragment: String?

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

    private var title: String { GrangerCoreEntry.shared.titleOfKey(key: path) }

    var body: some View {
        Group {
            if let core {
                ArticleWebView(
                    pack: core.pack(for: lang), path: path, fragment: fragment,
                    source: model.tracking(core), textScale: settings.textScale, dark: scheme == .dark, controller: controller,
                    onToggleChrome: { withAnimation(.easeInOut(duration: 0.2)) { chrome.toggle() } },
                    onOpenArticle: { _, p, _ in openRoute(.topic(lang: lang, path: p)) },
                    onLoaded: { recent.add(lang: lang, path: path, title: title) }
                )
                .ignoresSafeArea(edges: chrome ? [] : .all)
            }
        }
        .background(palette.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .toolbar(chrome ? .visible : .hidden, for: .navigationBar, .tabBar)
        .statusBarHidden(!chrome)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button { showContents = true } label: { Image(systemName: "list.bullet") }
                    .accessibilityLabel("Contents")
                    .disabled(model.headings.isEmpty)
                Button { showText = true } label: { Image(systemName: "textformat.size") }
                    .accessibilityLabel("Text settings")
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
