import GrangerCoreKit
import SafariServices
import SwiftUI

/// Home (interim, Plan I2 builder's call 2): the privacy line until it's seen, today's featured article (or the
/// Picture of the day where the wiki's feed has no featured article), and Continue. I3 brings Android's feeds and Wander.
struct HomeScreen: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(AppSettings.self) private var settings
    @Environment(RecentStore.self) private var recent
    @Environment(LibraryModel.self) private var library
    @State private var model = HomeModel()
    @State private var showSettings = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                LocalOnlyBanner()
                if !settings.privacySeen { PrivacyCard(onSettings: { showSettings = true }) }
                if library.index == .coming { IndexCard() }
                featured
                if !recent.entries.isEmpty { continueSection }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(palette.background)
        .navigationTitle("Granger")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button { showSettings = true } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
            }
        }
        .sheet(isPresented: $showSettings) { NavigationStack { SettingsScreen() } }
        .task(id: "\(settings.wikiLanguage)|\(settings.canFetch)") {
            if let core { await model.load(core: core, lang: settings.wikiLanguage, allowed: settings.canFetch) }
        }
    }

    @ViewBuilder private var featured: some View {
        if let article = model.featured?.article {
            NavigationLink(value: Route.topic(lang: settings.wikiLanguage, path: article.key)) {
                Plate(eyebrow: "Today's featured article", title: article.title, text: HomeModel.firstSentence(article.extract), image: model.image)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("featured")
        } else if let picture = model.featured?.picture {
            Button {
                if let page = picture.filePage.flatMap(URL.init(string:)) { ArticleWebView.Coordinator.present(SFSafariViewController(url: page)) }
            } label: {
                Plate(eyebrow: "Picture of the day", title: picture.description_ ?? picture.title,
                      text: picture.artist.map { "Picture: \($0)" } ?? "", image: model.image)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("featured")
        } else if model.loading {
            ProgressView().frame(maxWidth: .infinity, minHeight: 200)
        }
    }

    private var continueSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Continue").font(Spectral.medium(20, relativeTo: .title3)).foregroundStyle(palette.tertiary)
                .accessibilityAddTraits(.isHeader)
            ForEach(recent.entries.prefix(10)) { e in
                NavigationLink(value: Route.topic(lang: e.lang, path: e.path)) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(e.title).font(Spectral.regular(18)).foregroundStyle(palette.onBackground)
                            Text(e.lang == "es" ? "Wikipedia · Español" : "Wikipedia").font(.caption).foregroundStyle(palette.outline)
                        }
                        Spacer()
                        MarkView(lang: e.lang, title: e.path)
                        Image(systemName: "chevron.right").font(.footnote).foregroundStyle(palette.outline)
                    }
                    .padding(.vertical, 6)
                }
                .buttonStyle(.plain)
                Divider()
            }
        }
    }
}

/// A plate: a picture with an eyebrow, a title and a line (Android's Explore/Home plates).
struct Plate: View {
    let eyebrow: String
    let title: String
    let text: String
    let image: UIImage?
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let image {
                // A landscape picture fills the plate; a portrait (a person) is shown whole, so no face is cut off.
                if image.size.width >= image.size.height {
                    // An overlay on a fixed box, so a wide picture can't widen the column.
                    Color.clear.frame(maxWidth: .infinity).frame(height: 220)
                        .overlay { Image(uiImage: image).resizable().scaledToFill() }
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .accessibilityHidden(true)
                } else {
                    Image(uiImage: image).resizable().scaledToFit()
                        .frame(maxHeight: 300)
                        .clipShape(RoundedRectangle(cornerRadius: 8))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityHidden(true)
                }
            }
            Text(eyebrow.uppercased()).font(.caption.weight(.semibold)).tracking(1).foregroundStyle(palette.tertiary)
            Text(title).font(Spectral.medium(26, relativeTo: .title)).foregroundStyle(palette.onBackground)
            if !text.isEmpty {
                Text(text).font(Spectral.regular(17)).foregroundStyle(palette.onSurfaceVariant).lineLimit(4)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

/// The privacy line (Plan O5): before anything is asked of Wikipedia.
struct PrivacyCard: View {
    let onSettings: () -> Void
    @Environment(AppSettings.self) private var settings
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Granger now reads online too").font(Spectral.medium(20)).foregroundStyle(palette.onSurface)
            Text("Reading online asks Wikipedia (and other free libraries) for each page, so they see what you open. Granger itself never sees it. You can keep things offline or switch online off any time.")
                .font(.callout).foregroundStyle(palette.onSurfaceVariant)
            HStack {
                Button("OK") { settings.privacySeen = true }.buttonStyle(.borderedProminent).accessibilityIdentifier("privacy-ok")
                Button("Settings", action: onSettings).buttonStyle(.bordered)
            }
        }
        .padding(16)
        .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
        .padding(.top, 8)
    }
}

@Observable
final class HomeModel {
    var featured: WikimediaClient.Featured?
    var image: UIImage?
    var loading = false

    @MainActor
    func load(core: Core, lang: String, allowed: Bool) async {
        guard allowed else { featured = nil; image = nil; return }
        loading = true
        defer { loading = false }
        let c = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        let client = core.client
        let got = await background { client.featured(lang: lang, year: Int32(c.year!), month: Int32(c.month!), day: Int32(c.day!)) }
        featured = got
        let thumb = got?.article?.thumbnail ?? got?.picture?.thumbnail
        image = await background {
            guard let thumb, let p = client.picture(url: thumb) else { return nil }
            return UIImage(data: GrangerCoreEntry.shared.bytes(of: p.first!))
        }
    }

    /// The extract's first sentence (a plate's line).
    static func firstSentence(_ text: String) -> String {
        guard let r = text.range(of: #"[.!?](\s|$)"#, options: .regularExpression) else { return text }
        return String(text[..<r.lowerBound]) + String(text[r.lowerBound])
    }
}

/// The online index set on its way (Android's index card on Home and Explore, Plan O1 Task 8).
struct IndexCard: View {
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            ProgressView()
            Text("Explore is getting its index for Wikipedia online (19 MB). Subjects, rooms and eras appear when it's here.")
                .font(.callout).foregroundStyle(palette.onSurfaceVariant)
        }
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("index-card")
    }
}
