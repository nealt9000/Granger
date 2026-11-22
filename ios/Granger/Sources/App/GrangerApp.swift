import GrangerCoreKit
import SwiftUI

@main
struct GrangerApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @Environment(\.scenePhase) private var scenePhase
    /// `-resetData` (UI tests, debug builds): clean data before the settings below read the defaults (Plan I6 Task 9).
    private let reset = DebugReset.runIfAsked()
    @State private var settings = AppSettings()
    @State private var recent = RecentStore()
    @State private var library = LibraryModel()
    private let core: Core
    /// Read aloud and Listen (Plan I5 Task 8), for every screen.
    @State private var voice: VoiceCenter
    /// The crash log (Plan I7 Task 4): its handlers go in first, so a crash while the rest starts is kept.
    @State private var crashes: CrashLog

    init() {
        GrangerCoreKit.start()
        let core = Core(version: Core.version)
        self.core = core
        let crashes = CrashLog(core: core)
        _crashes = State(initialValue: crashes)
        #if DEBUG
        DebugFeedback.crashIfAsked()
        #endif
        _voice = State(initialValue: VoiceCenter(core: core))
        // The rooms' one-line descriptions, bundled from Android's assets.
        core.library.roomsJson = { (Bundle.main.url(forResource: "rooms", withExtension: "json")).flatMap { try? String(contentsOf: $0, encoding: .utf8) } ?? "{}" }
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
                .environment(\.voice, voice)
                .environment(crashes)
                // The knowledge languages (Settings › Language, onboarding) drive the library's.
                .task { await settings.followKnowledge(core) }
                // Local only flipped in Control Center (Plan I6 Task 8): the app follows.
                .onAppear { settings.followLocalOnly() }
                .task(id: "\(settings.mainLanguage)|\(settings.canFetch)") {
                    library.watch(core)
                    // Get more's catalog: the shared source asks, Swift fetches and verifies (Plan I4 Task 6).
                    core.library.catalogSource.fetch = { [library] refresh in
                        Task { @MainActor in
                            if refresh.boolValue { library.forgetCatalog() }
                            let c = await library.loadCatalog()
                            if let c { core.library.catalog = c } else { core.library.catalogSource.publish(catalog: nil) }
                        }
                    }
                    await library.prepareIndexes(core: core, lang: settings.mainLanguage, allowed: settings.canFetch)
                    // The packs in Documents/Packs, opened and listed (Plan I4 Task 5).
                    try? await core.library.refreshPacks()
                }
                .onChange(of: scenePhase) { _, phase in
                    // Packs put in or taken out of Granger's folder in the Files app while away.
                    if phase == .active {
                        settings.refreshLocalOnly()
                        crashes.refresh()
                        Task { try? await core.library.refreshPacks() }
                        voice.music.foregrounded()
                    }
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

/// The tabs (spec §5; Plan I2 builder's call 1): Home, Search, Explore, Library (Plan I5 Task 6) and Listen (Task 8,
/// call 7), with the mini player above the tab bar while the voice is loaded.
/// Each tab keeps its own stack, so swipe-back works and switching tabs keeps your place.
struct RootView: View {
    @Environment(\.core) private var core
    @Environment(\.voice) private var voice
    enum Tab: Hashable { case home, search, explore, library, listen }
    @State private var tab: Tab = .home
    @State private var homePath: [Route] = []
    @State private var searchPath: [Route] = []
    @State private var explorePath: [Route] = []
    @State private var libraryPath: [Route] = []
    @State private var listenPath: [Route] = []
    @Environment(AppSettings.self) private var settings
    /// The first run (Plan I4 Task 8): once, before the privacy line is seen; finished or skipped, it doesn't come back.
    @State private var debugImported = false
    @State private var debugNotice: String?
    /// `-debugReportSheet YES`: the Report sheet over a made-up Ask reply (Ask is off on the iPhone, as in Android's release).
    @State private var debugReport = UserDefaults.standard.bool(forKey: "debugReportSheet")
    @State private var onboarding = !UserDefaults.standard.bool(forKey: "onboarding.done") && !UserDefaults.standard.bool(forKey: "privacySeen")

    var body: some View {
        tabs
            .overlay(alignment: .top) {
                if let debugNotice { Text(debugNotice).font(.caption).padding(6).background(.thinMaterial).accessibilityIdentifier("debug-import-notice")
                    .task { try? await Task.sleep(for: .seconds(8)); self.debugNotice = nil } }
            }
            #if DEBUG
            .overlay(alignment: .bottomLeading) { if DebugPlay.entries != nil { DebugPlayLabel() } }
            .overlay(alignment: .topLeading) { if DebugSystem.on { DebugSystemControls().padding(.top, 60) } }
            #endif
            #if DEBUG
            .sheet(isPresented: $debugReport) {
                ReportFlow(guess: false, sources: 2, report: DebugFeedback.sampleReport)
            }
            #endif
            .fullScreenCover(isPresented: $onboarding) {
                OnboardingScreen { readNow in
                    onboarding = false
                    if readNow { tab = .explore }
                }
            }
    }

    private var tabs: some View {
        TabView(selection: $tab) {
            stack($homePath) { HomeScreen() }
                .tabItem { Label("Home", systemImage: "house") }
                .tag(Tab.home)
                .onAppear(perform: debugOpen)
            stack($searchPath) { SearchScreen() }
                .tabItem { Label("Search", systemImage: "magnifyingglass") }
                .tag(Tab.search)
            stack($explorePath) { ExploreScreen() }
                .tabItem { Label("Explore", systemImage: "safari") }
                .tag(Tab.explore)
            stack($libraryPath) { LibraryScreen() }
                .tabItem { Label("Library", systemImage: "books.vertical") }
                .tag(Tab.library)
            stack($listenPath) { ListenScreen() }
                .tabItem { Label("Listen", systemImage: "headphones") }
                .tag(Tab.listen)
        }
        .modifier(MiniPlayerAccessory(voice: voice) { route in listenPath = route.map { [$0] } ?? []; tab = .listen })
        .overlay(alignment: .bottom) {
            if let notice = voice?.notice {
                Text(notice).font(.callout).padding(12).background(.thinMaterial, in: RoundedRectangle(cornerRadius: 8)).padding(.bottom, 120)
                    .accessibilityIdentifier("voice-notice")
                    .task { try? await Task.sleep(for: .seconds(4)); voice?.notice = nil }
            }
        }
    }

    #if DEBUG
    private func debugLinks(_ core: Core, bookId: String) async {
        try? await core.library.refreshPacks()
        guard let packs = try? await core.library.packs.first(where: { !$0.isEmpty }),
              let pack = packs.first(where: { $0.status == .ok }),
              let main = core.library.archive(packId: pack.id)?.mainPath,
              let book = try? await core.library.book(id: bookId) else { NSLog("debugLinks: no pack or book"); return }
        let ok = core.library.debugLinks(bookId: bookId, epubPath: book.filePath, href: "OEBPS/c001.xhtml", quote: "Netherfield",
                                         qid: 1_234_567, packId: pack.id, articlePath: main)
        NSLog("debugLinks: %@", ok ? "written" : "failed")
    }
    #endif

    /// Debug builds: `-debugOpen en:Moon` opens that topic page at launch (UI tests and checks use it).
    private func debugOpen() {
        #if DEBUG
        // `-debugImport <path on the Mac>`: that file is imported as a pick from Files would be, then the Library shows
        // (Plan I5 Task 6; UI tests can't drive the Files picker).
        if let path = UserDefaults.standard.string(forKey: "debugImport"), let core, !debugImported {
            debugImported = true
            tab = .library
            Task { @MainActor in
                let words = await BookImport.runDebug(path: path, core: core)
                NSLog("debugImport: %@", words)
                debugNotice = words
            }
            return
        }
        // `-debugArchiveSample YES`: a born-digital text PDF, made here, filed as a finished archive.org download would be
        // (Plan I5 Task 7: text PDF → e-book), then opened from the shelf. The notice says which the shelf made of it.
        if homePath.isEmpty, UserDefaults.standard.bool(forKey: "debugArchiveSample"), let core, !debugImported {
            debugImported = true
            Task { @MainActor in
                let url = FileManager.default.temporaryDirectory.appendingPathComponent("archive-sample.pdf")
                SampleTextPdf.write(to: url)
                guard let id = try? await core.library.debugArchiveText(pdfPath: url.path, identifier: "fieldbookofropes00hawt",
                                                                        title: SampleTextPdf.title, author: SampleTextPdf.author),
                      let book = try? await core.library.book(id: id) else { debugNotice = "archive: failed"; return }
                debugNotice = "archive: \(book.format == .epub ? "epub" : "pdf")"
                homePath = [.book(id: id)]
            }
            return
        }
        // `-debugBook <path on the Mac>`: that EPUB or PDF is imported into the shelf and opened (Plan I5 Task 5).
        if homePath.isEmpty, let path = UserDefaults.standard.string(forKey: "debugBook"), let core {
            Task { @MainActor in
                guard let result = try? await core.library.importBook(path: path, displayName: (path as NSString).lastPathComponent) else { return }
                let opened: String? = switch onEnum(of: result) {
                case let .imported(r): r.bookId
                case let .alreadyThere(r): r.bookId
                default: nil
                }
                // `-debugLinks YES` with `-debugPack`: a reference link in chapter 1 ("Netherfield") to the pack's main
                // page, as a Classic's `.glink` and a QID index would give it (Plan I5 Task 7).
                if let id = opened, UserDefaults.standard.bool(forKey: "debugLinks") { await debugLinks(core, bookId: id) }
                if let opened { homePath = [.book(id: opened)] }
            }
            return
        }
        if homePath.isEmpty, UserDefaults.standard.bool(forKey: "debugWander") { homePath = [.wander(packId: nil, path: nil)]; return }
        // `-debugPackReader YES` with `-debugPack`: the pack's main page in the reader (Plan I5 Task 8's read-aloud tests).
        if homePath.isEmpty, UserDefaults.standard.bool(forKey: "debugPackReader"), let core, !debugImported {
            debugImported = true
            Task { @MainActor in
                try? await core.library.refreshPacks()
                guard let packs = try? await core.library.packs.first(where: { !$0.isEmpty }),
                      let pack = packs.first(where: { $0.status == .ok }),
                      let main = core.library.archive(packId: pack.id)?.mainPath else { NSLog("debugPackReader: no pack"); return }
                homePath = [.packReader(pack: pack.id, path: main, fragment: nil)]
            }
            return
        }
        // `-debugSource GRANGER` opens that Get more source screen (Granger's own is reached from the Library's shelves).
        if homePath.isEmpty, let scope = UserDefaults.standard.string(forKey: "debugSource") {
            homePath = [.source(scope: scope, domain: nil, family: nil, bookSet: nil)]
            return
        }
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
                    case let .packTopic(pack, p): TopicScreen(lang: core?.library.languageOf(packId: pack) ?? "en", path: p, pack: pack)
                    case let .packReader(pack, p, fragment): ReaderScreen(lang: core?.library.languageOf(packId: pack) ?? "en", path: p, fragment: fragment, pack: pack)
                    case let .timeline(packId, p): TimelineScreen(packId: packId, path: p)
                    case .downloads: DownloadsScreen()
                    case .getMore: GetMoreScreen()
                    case .outfitting: OutfittingScreen()
                    case .yourSources: YourSourcesScreen()
                    case let .packFiles(pack): PackFilesScreen(pack: pack)
                    case let .bookPack(pack): BookPackCollection(pack: pack)
                    case let .source(scope, domain, family, bookSet):
                        SourceScreen(scope: CatalogScope.allCases.first { $0.name == scope } ?? .kiwix, domain: domain, family: family, bookSet: bookSet)
                    case let .subject(id): SubjectScreen(subjectId: id)
                    case let .room(subject, facet): RoomScreen(subjectId: subject, facetId: facet)
                    case let .wander(packId, p): WanderScreen(packId: packId, path: p)
                    case let .wanderIn(subject, facet): WanderScreen(subject: subject, facet: facet)
                    case .games: GamesScreen()
                    case let .quiz(subject): GamesScreen(subject: subject)
                    case let .quilt(lang, p): QuiltScreen(lang: lang, path: p)
                    case let .book(id): BookReaderScreen(bookId: id)
                    case let .collection(id): CollectionScreen(collectionId: id)
                    case let .search(query): SearchScreen(initial: query)
                    case let .bookAt(id, passage): BookReaderScreen(bookId: id, passage: passage)
                    case let .remoteCollection(packId): RemoteCollectionScreen(packId: packId)
                    case .archive: ArchiveScreen()
                    case .voiceSettings: VoiceSettingsScreen()
                    case let .music(id): MusicScreen(collectionId: id)
                    case let .album(key): AlbumScreen(albumKey: key)
                    case let .artist(name): ArtistScreen(name: name)
                    case let .genre(name): GenreScreen(name: name)
                    case let .musicFolder(p): MusicFolderScreen(path: p)
                    case let .playlist(id): PlaylistScreen(playlistId: id)
                    case .musicPlayer: MusicPlayerScreen()
                    case .mediaSettings: MediaSettingsScreen()
                    case let .audiobooks(id): AudiobookShelfScreen(collectionId: id)
                    case let .audiobook(id): AudiobookScreen(bookId: id)
                    case .audiobookPlayer: AudiobookPlayerScreen()
                    case .librivox: LibriVoxScreen()
                    case .getMoreAudio: GetMoreScreen(audioOnly: true)
                    case let .archiveAudio(kind): ArchiveScreen(kind: ArchiveKind.allCases.first { $0.name == kind } ?? .music)
                    }
                }
        }
        .environment(\.openRoute) { path.wrappedValue.append($0) }
    }
}

/// The mini player above the tab bar while something is loaded or would resume (iOS 26's bottom accessory; 26.0 has no
/// isEnabled, so the accessory is left off there while there's nothing to show).
private struct MiniPlayerAccessory: ViewModifier {
    let voice: VoiceCenter?
    /// Opens Listen, at a player when given (music's or the audiobook's).
    let onOpen: (Route?) -> Void

    func body(content: Content) -> some View {
        if #available(iOS 26.1, *) {
            content.tabViewBottomAccessory(isEnabled: voice?.miniShown ?? false) { mini }
        } else if voice?.miniShown == true {
            content.tabViewBottomAccessory { mini }
        } else {
            content
        }
    }

    @ViewBuilder private var mini: some View {
        if let voice { MiniPlayer(voice: voice, onOpen: onOpen) }
    }
}

