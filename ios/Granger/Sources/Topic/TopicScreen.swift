import GrangerCoreKit
import SwiftUI

/// An article's topic page (Android's TopicPage for an online article): its picture, title and summary, Read the full
/// article, and the other language's Wikipedia when it has the article. Records, rooms and Related come in I3.
struct TopicScreen: View {
    let lang: String
    let path: String

    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(AppSettings.self) private var settings
    @State private var model = TopicModel()

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                switch model.state {
                case .loading:
                    ProgressView().frame(maxWidth: .infinity, minHeight: 240)
                case .failed:
                    unavailable
                case let .loaded(summary):
                    content(summary)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(palette.background)
        .navigationTitle("Wikipedia")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: path) { if let core { await model.load(core: core, lang: lang, path: path) } }
    }

    @ViewBuilder private func content(_ s: WikimediaClient.Summary) -> some View {
        if let image = model.picture {
            Image(uiImage: image)
                .resizable()
                .scaledToFit()
                .frame(maxWidth: .infinity)
                .clipShape(RoundedRectangle(cornerRadius: 8))
                .accessibilityLabel(s.title)
        }
        HStack(alignment: .center, spacing: 10) {
            Text(s.title)
                .font(Spectral.medium(34, relativeTo: .largeTitle))
                .foregroundStyle(palette.onBackground)
                .accessibilityAddTraits(.isHeader)
            MarkView(lang: lang, title: s.key)
        }
        if let description = s.description_ {
            Text(description).font(.subheadline).foregroundStyle(palette.onSurfaceVariant)
        }
        Text(s.extract)
            .font(Spectral.regular(19, relativeTo: .body))
            .foregroundStyle(palette.onBackground)
            .lineSpacing(4)
        NavigationLink(value: Route.reader(lang: lang, path: s.key, fragment: nil)) {
            Text("Read the full article").font(Spectral.medium(18)).frame(maxWidth: .infinity).padding(.vertical, 6)
        }
        .buttonStyle(.borderedProminent)
        .accessibilityIdentifier("read-full")
        HintCard(id: "keep", text: "Keep puts this article on your phone (●). What you read is saved for now (○) until space runs short.")
        KeepButton(lang: lang, title: s.key)
        if let other = model.other {
            NavigationLink(value: Route.topic(lang: other.lang, path: other.path)) {
                // The other language's name in its own words, as on Android (topic_read_in_spanish / _english).
                Text(other.lang == "es" ? "Leer en español" : "Read in English")
                    .font(Spectral.medium(17)).frame(maxWidth: .infinity).padding(.vertical, 4)
            }
            .buttonStyle(.bordered)
        }
    }

    private var unavailable: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(GrangerCoreEntry.shared.titleOfKey(key: path))
                .font(Spectral.medium(30, relativeTo: .largeTitle))
                .foregroundStyle(palette.onBackground)
            Text("Needs internet. This article is read online and isn't kept on this phone.")
                .foregroundStyle(palette.onSurfaceVariant)
            if settings.canFetch {
                Button("Retry") { Task { if let core { await model.load(core: core, lang: lang, path: path) } } }
                    .buttonStyle(.bordered)
            }
        }
        .padding(.top, 24)
    }
}

@Observable
final class TopicModel {
    enum State { case loading, failed, loaded(WikimediaClient.Summary) }
    var state: State = .loading
    var picture: UIImage?
    var other: (lang: String, path: String)?

    @MainActor
    func load(core: Core, lang: String, path: String) async {
        state = .loading
        picture = nil
        other = nil
        let client = core.client
        let wiki = core.wikipedia(lang)
        guard let summary = await background({ wiki.summary(path: path) }) else {
            state = .failed
            return
        }
        state = .loaded(summary)
        let to = lang == "es" ? "en" : "es"
        async let pic: UIImage? = background {
            guard let url = summary.thumbnail, let got = client.picture(url: url) else { return nil }
            return UIImage(data: GrangerCoreEntry.shared.bytes(of: got.first!))
        }
        async let link: String? = background { client.languageLink(lang: lang, title: summary.key, to: to) }
        picture = await pic
        if let p = await link { other = (to, p) }
    }
}
