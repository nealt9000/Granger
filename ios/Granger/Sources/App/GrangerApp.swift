import GrangerCoreKit
import SwiftUI

@main
struct GrangerApp: App {
    @State private var settings = AppSettings()
    @State private var recent = RecentStore()
    @State private var library = LibraryModel()
    private let core: Core

    init() {
        GrangerCoreKit.start()
        core = Core(version: Core.version)
        // Navigation titles in Spectral, as Android's top bars (Plan T1), scaled with Dynamic Type.
        let metrics = UIFontMetrics(forTextStyle: .largeTitle)
        if let large = UIFont(name: "Spectral-SemiBold", size: 34), let inline = UIFont(name: "Spectral-Medium", size: 18) {
            UINavigationBar.appearance().largeTitleTextAttributes = [.font: metrics.scaledFont(for: large)]
            UINavigationBar.appearance().titleTextAttributes = [.font: UIFontMetrics(forTextStyle: .headline).scaledFont(for: inline)]
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(settings)
                .environment(recent)
                .environment(library)
                .environment(\.core, core)
                .task(id: "\(settings.wikiLanguage)|\(settings.canFetch)") {
                    library.watch(core)
                    await library.prepareIndexes(core: core, lang: settings.wikiLanguage, allowed: settings.canFetch)
                }
                .preferredColorScheme(settings.appearance.colorScheme)
                .themed()
        }
    }
}

private struct CoreKey: EnvironmentKey {
    static let defaultValue: Core? = nil
}

extension EnvironmentValues {
    var core: Core? {
        get { self[CoreKey.self] }
        set { self[CoreKey.self] = newValue }
    }
}

private struct OpenRouteKey: EnvironmentKey {
    static let defaultValue: (Route) -> Void = { _ in }
}

extension EnvironmentValues {
    /// Pushes a place on the current tab's stack (a link tapped in a page, a search result).
    var openRoute: (Route) -> Void {
        get { self[OpenRouteKey.self] }
        set { self[OpenRouteKey.self] = newValue }
    }
}

/// The tabs (spec §5; Plan I2 builder's call 1): Home and Search now; Explore, Library and Listen join as they're built.
/// Each tab keeps its own stack, so swipe-back works and switching tabs keeps your place.
struct RootView: View {
    enum Tab: Hashable { case home, search }
    @State private var tab: Tab = .home
    @State private var homePath: [Route] = []
    @State private var searchPath: [Route] = []

    var body: some View {
        TabView(selection: $tab) {
            stack($homePath) { HomeScreen() }
                .tabItem { Label("Home", systemImage: "house") }
                .tag(Tab.home)
                .onAppear(perform: debugOpen)
            stack($searchPath) { SearchScreen() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(Tab.search)
        }
    }

    /// Debug builds: `-debugOpen en:Moon` opens that topic page at launch (UI tests and checks use it).
    private func debugOpen() {
        #if DEBUG
        guard homePath.isEmpty, let spec = UserDefaults.standard.string(forKey: "debugOpen") else { return }
        let parts = spec.split(separator: ":", maxSplits: 1).map(String.init)
        guard parts.count == 2 else { return }
        homePath = [.topic(lang: parts[0], path: parts[1])]
        if UserDefaults.standard.bool(forKey: "debugReader") { homePath.append(.reader(lang: parts[0], path: parts[1], fragment: nil)) }
        #endif
    }

    private func stack<Root: View>(_ path: Binding<[Route]>, @ViewBuilder root: () -> Root) -> some View {
        NavigationStack(path: path) {
            root()
                .navigationDestination(for: Route.self) { route in
                    switch route {
                    case let .topic(lang, p): TopicScreen(lang: lang, path: p)
                    case let .reader(lang, p, fragment): ReaderScreen(lang: lang, path: p, fragment: fragment)
                    case let .timeline(packId, p): TimelineScreen(packId: packId, path: p)
                    }
                }
        }
        .environment(\.openRoute) { path.wrappedValue.append($0) }
    }
}
