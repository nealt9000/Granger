import GrangerCoreKit
import SwiftUI

@main
struct GrangerApp: App {
    @State private var settings = AppSettings()
    private let core: Core

    init() {
        GrangerCoreKit.start()
        core = Core(version: Core.version)
    }

    var body: some Scene {
        WindowGroup {
            RootView()
                .environment(settings)
                .environment(\.core, core)
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

/// The tabs (spec §5; Plan I2 builder's call 1): Home and Search now; Explore, Library and Listen join as they're built.
struct RootView: View {
    enum Tab: Hashable { case home, search }
    @State private var tab: Tab = .home

    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { HomeScreen() }
                .tabItem { Label("Home", systemImage: "house") }
                .tag(Tab.home)
            NavigationStack { SearchScreen() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(Tab.search)
        }
    }
}
