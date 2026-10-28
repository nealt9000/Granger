// I0 Task 5 (A): SwiftUI screens over the shared Kotlin core (Shared.framework), the same content as the Compose shell.
import SwiftUI
import WebKit
import Shared

extension Color {
    static let paper = Color(red: 0xF3 / 255, green: 0xEE / 255, blue: 0xE4 / 255)
    static let paperDeep = Color(red: 0xE1 / 255, green: 0xD9 / 255, blue: 0xC9 / 255)
    static let ink = Color(red: 0x1F / 255, green: 0x18 / 255, blue: 0x13 / 255)
    static let enamel = Color(red: 0x2E / 255, green: 0x4A / 255, blue: 0x45 / 255)
}

@main
struct SwiftUIShellApp: App {
    var body: some Scene { WindowGroup { LibraryView().tint(.enamel) } }
}

struct LibraryView: View {
    let core = CoreDemo(dir: NSHomeDirectory() + "/Documents")
    @State private var query = "incompleteness"
    @State private var result = ""
    @State private var path: [String] = ProcessInfo.processInfo.arguments.contains("-openReader") ? ["Wikipedia (Top 1M)"] : []
    private let packs = [("Wikipedia (Top 1M)", "Kiwix · 17.7 GB"), ("Wiktionary", "Kiwix · 6.1 GB"), ("Granger Classics", "347 books")]

    var body: some View {
        NavigationStack(path: $path) {
            List {
                ForEach(packs, id: \.0) { pack in
                    NavigationLink(value: pack.0) {
                        VStack(alignment: .leading) {
                            Text(pack.0).font(.custom("Spectral-Regular", size: 18))
                            Text(pack.1).font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .listRowBackground(Color.paperDeep)
                }
                Section("Search by meaning (shared core)") {
                    TextField("Query", text: $query)
                    Button("Search") {
                        Task {
                            result = "Searching…"
                            result = (try? await core.search(query: query, rows: 20_000)) ?? "failed"
                        }
                    }
                    Text(shown).font(.system(size: 13)).textSelection(.enabled)
                }
                .listRowBackground(Color.paperDeep)
            }
            .scrollContentBackground(.hidden)
            .background(Color.paper)
            .navigationTitle("Library")
            .task {
                // I0: the screenshot runs start a search from a launch argument, since simctl can't tap.
                if ProcessInfo.processInfo.arguments.contains("-autoSearch") { result = (try? await core.search(query: query, rows: 20_000)) ?? "failed" }
            }
            .toolbar { ToolbarItem(placement: .principal) { Text("GRANGER").font(.custom("Spectral-Medium", size: 17)).tracking(3) } }
            .navigationDestination(for: String.self) { _ in
                ReaderView(html: core.sampleHtml).navigationTitle("Kurt Gödel").navigationBarTitleDisplayMode(.inline).ignoresSafeArea(edges: .bottom)
            }
        }
    }

    private var shown: String {
        (result.isEmpty ? (core.lifespan(text: "Kurt Gödel (April 28, 1906 – January 14, 1978) was a logician.") ?? "") : result)
            + "\n\n" + core.blocks(html: core.sampleHtml).prefix(3).joined(separator: "\n")
    }
}

struct ReaderView: UIViewRepresentable {
    let html: String
    func makeUIView(context: Context) -> WKWebView {
        let web = WKWebView()
        web.loadHTMLString(html, baseURL: nil)
        return web
    }
    func updateUIView(_ uiView: WKWebView, context: Context) {}
}
