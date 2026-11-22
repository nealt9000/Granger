import SwiftUI

/// Settings › About › Open-source licences (Plan I7 call 8): every library and asset the app bundles, each with its
/// licence text. The list is `Licences.json`, written by `tools/ios/make_licences.py` from `tools/ios/licences/`; the
/// names, licences and texts are data, in English as their authors wrote them.
struct LicencesScreen: View {
    @Environment(\.palette) private var palette
    private let licences = Licences.bundled

    var body: some View {
        List(licences.libraries) { lib in
            NavigationLink {
                LicenceScreen(library: lib, texts: lib.texts.compactMap { licences.texts[$0] })
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: lib.name).foregroundStyle(palette.onSurface)
                    Text(verbatim: [lib.version, lib.licence].compactMap { $0 }.joined(separator: " · "))
                        .font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                }
            }
            .accessibilityIdentifier("licence-\(lib.name)")
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Open-source licences")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// One library: what it is, where it's from, and its licence in full.
private struct LicenceScreen: View {
    let library: Licences.Library
    let texts: [String]
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(verbatim: [library.version, library.licence].compactMap { $0 }.joined(separator: " · "))
                    .font(.subheadline).foregroundStyle(palette.onSurfaceVariant)
                if let url = URL(string: library.url) {
                    Link(destination: url) { Text(verbatim: library.url) }.font(.subheadline)
                }
                if let note = library.note {
                    Text(verbatim: note).font(.subheadline).foregroundStyle(palette.onSurface)
                }
                ForEach(Array(texts.enumerated()), id: \.offset) { _, text in
                    Divider()
                    Text(verbatim: text).font(.caption.monospaced()).foregroundStyle(palette.onSurface).textSelection(.enabled)
                }
            }
            .padding()
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(palette.background)
        .navigationTitle(Text(verbatim: library.name))
        .navigationBarTitleDisplayMode(.inline)
        .accessibilityIdentifier("licence-text")
    }
}

/// `Licences.json`: the libraries in the screen's order, and each licence text once by its key.
struct Licences: Decodable {
    struct Library: Decodable, Identifiable {
        let name: String
        let version: String?
        let licence: String
        let url: String
        let note: String?
        let texts: [String]
        var id: String { name }
    }

    let libraries: [Library]
    let texts: [String: String]

    static let bundled: Licences = {
        guard let url = Bundle.main.url(forResource: "Licences", withExtension: "json"),
              let data = try? Data(contentsOf: url),
              let decoded = try? JSONDecoder().decode(Licences.self, from: data) else { return Licences(libraries: [], texts: [:]) }
        return decoded
    }()
}
