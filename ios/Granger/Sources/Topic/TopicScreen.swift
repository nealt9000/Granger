import GrangerCoreKit
import SwiftUI

/// An article's topic page (Android's TopicPage, Plans LL2 and O6), from the shared `TopicSource`: where it sits in
/// Explore, its picture, title, mark and years, the lead, Read the full article, Keep, the other language, its time
/// (people and events), more in its room, and Related. Each part shows as it arrives.
struct TopicScreen: View {
    let lang: String
    let path: String
    /// A pack's id for a pack article (Plan I4 Task 5); nil for Wikipedia online in [lang].
    var pack: String? = nil

    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(AppSettings.self) private var settings
    @Environment(LibraryModel.self) private var library
    @State private var model = TopicModel()
    @State private var packTitle: String?

    private var packId: String { pack ?? GrangerCoreEntry.shared.onlinePackId(lang: lang) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                if let topic = model.topic, topic.header != nil, let broad = topic.broad {
                    BroadSpreadView(topic: topic, broad: broad, lang: lang, path: path, other: model.other).padding(.horizontal, -16)
                } else if let topic = model.topic, topic.header != nil, !topic.broadPending || topic.done {
                    content(topic)
                } else if model.failed {
                    unavailable
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 240)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .background(palette.background)
        .navigationTitle(packTitle ?? "Wikipedia")
        .navigationBarTitleDisplayMode(.inline)
        // Again once the online index set opens: the trail, the years and the room come from it (a topic opened at launch
        // can come before it).
        .task(id: "\(path)|\(settings.canFetch)|\(library.index == .ready)") {
            if let core { await model.load(core: core, lang: lang, path: path, packId: packId) }
        }
        .task(id: pack) {
            guard let core, let pack else { return }
            packTitle = await Task.detached { core.library.packTitle(packId: pack) }.value
        }
    }

    @ViewBuilder private func content(_ topic: Topic) -> some View {
        let header = topic.header!
        if let place = topic.place { Trail(place: place, lang: lang, title: header.title) }
        TopicHeaderView(header: header, mark: topic.time?.mark, lang: lang, path: path)
        if !header.lead.isEmpty {
            Text(header.lead)
                .font(Spectral.regular(19, relativeTo: .body))
                .foregroundStyle(palette.onBackground)
                .lineSpacing(4)
        }
        actions(topic)
        if let time = topic.time { TimeCard(time: time, words: .app) }
        if let place = topic.place, let room = place.room, !place.more.isEmpty {
            ArticleGrid(title: L("More in \(room.name(language: lang))"), articles: Array(place.more.prefix(6)), lang: lang)
        }
        if let related = topic.related, !related.isEmpty {
            ArticleGrid(title: L("Closest topics"), articles: related, lang: lang)
        }
        NavigationLink(value: Route.wander(packId: packId, path: path)) {
            Text("Wander from here").font(Spectral.medium(17)).frame(maxWidth: .infinity).padding(.vertical, 4)
        }
        .buttonStyle(.bordered)
        .accessibilityIdentifier("wander-here")
    }

    @ViewBuilder private func actions(_ topic: Topic) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            NavigationLink(value: Route.read(packId: packId, path: path)) {
                Text("Read the full article").font(Spectral.medium(18)).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("read-full")
            if pack == nil { HintCard(id: "keep", text: L("Keep puts this article on your phone (●). What you read is saved for now (○) until space runs short.")) }
            // Keeping is for Wikipedia online: a pack's article is on this phone already.
            if pack == nil { KeepButton(lang: lang, title: path) }
            if topic.done && ((topic.related?.count ?? 0) + (topic.time?.people.count ?? 0) + (topic.place?.more.count ?? 0)) >= 3 {
                NavigationLink(value: Route.quilt(lang: lang, path: path)) {
                    Text("Explore connections").font(Spectral.medium(17)).frame(maxWidth: .infinity).padding(.vertical, 4)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("connections")
            }
            if let other = model.other {
                NavigationLink(value: Route.topic(lang: other.lang, path: other.path)) {
                    // The other language's name in its own words, as on Android (topic_read_in_spanish / _english).
                    Text(other.lang == "es" ? "Leer en español" : "Read in English")
                        .font(Spectral.medium(17)).frame(maxWidth: .infinity).padding(.vertical, 4)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("read-other-language")
            }
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
                Button("Retry") { Task { if let core { await model.load(core: core, lang: lang, path: path, packId: packId) } } }
                    .buttonStyle(.bordered)
            }
        }
        .padding(.top, 24)
    }
}

/// Where the topic sits in Explore: Explore › subject › room (Android's trail).
private struct Trail: View {
    let place: TopicPlace
    let lang: String
    let title: String
    @Environment(\.palette) private var palette

    var body: some View {
        let subject = ScreenText.strings[Subject.companion.of(id: place.subject)?.labelKey ?? ""] ?? place.subject
        let steps = [L("Explore"), subject] + (place.room.map { [$0.name(language: lang)] } ?? [])
        trailText(steps: steps, here: title, palette: palette)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("You are here: \(steps.joined(separator: ", ")), \(title)")
        .padding(.top, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The picture, the title with its mark, and the years with their bar.
private struct TopicHeaderView: View {
    let header: TopicHeader
    let mark: TimeMark?
    let lang: String
    let path: String
    @Environment(\.palette) private var palette
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let image = header.picture.flatMap { UIImage(contentsOfFile: $0) }
        // At the accessibility sizes the picture goes above, so the title has the width.
        let wide = typeSize.isAccessibilitySize || (image?.size.width ?? 0) >= (image?.size.height ?? 1) * 1.15
        VStack(alignment: .leading, spacing: 12) {
            if let image, wide {
                Image(uiImage: image).resizable().scaledToFit()
                    .frame(maxWidth: .infinity).clipShape(RoundedRectangle(cornerRadius: 8))
                    .accessibilityHidden(true)
            }
            HStack(alignment: .top, spacing: 14) {
                if let image, !wide {
                    Image(uiImage: image).resizable().scaledToFill()
                        .frame(width: 120, height: 150).clipShape(RoundedRectangle(cornerRadius: 8))
                        .accessibilityHidden(true)
                }
                VStack(alignment: .leading, spacing: 8) {
                    HStack(alignment: .center, spacing: 10) {
                        Text(header.title)
                            .font(Spectral.medium(34, relativeTo: .largeTitle))
                            .foregroundStyle(palette.onBackground)
                            .accessibilityAddTraits(.isHeader)
                        MarkView(lang: lang, title: path)
                    }
                    if let mark { SpanLine(mark: mark, words: .app) }
                }
            }
        }
    }
}

/// A life or span as a line of years with a bar under it: "1769 – 1821 · aged 51".
struct SpanLine: View {
    let mark: TimeMark
    let words: YearWords
    @Environment(\.palette) private var palette

    var body: some View {
        let years = words.of(m: mark)
        let age: Int32? = mark.kind == .life ? mark.y1.map { AgesKt.ageOf(y0: mark.y0, md0: mark.md0, y1: $0.int64Value, md1: mark.md1?.int32Value ?? 0) } : nil
        VStack(alignment: .leading, spacing: 4) {
            Text(age.map { L("\(years) · aged \($0)") } ?? years)
                .font(Spectral.medium(17)).foregroundStyle(palette.primary)
            if mark.kind != .point {
                RoundedRectangle(cornerRadius: 3).fill(palette.primary).frame(height: 6)
            }
        }
    }
}

/// Their time (Decision 2): the people whose lives overlapped, each a bar across the span, and the events of the span.
private struct TimeCard: View {
    let time: TopicTimeView
    let words: YearWords
    @Environment(\.palette) private var palette

    var body: some View {
        let now = Int32(Calendar.current.component(.year, from: Date()))
        let mark = time.mark
        let from = min(mark.y0, time.people.map(\.y0).min() ?? mark.y0)
        let to = max(mark.end(now: now), time.people.map { $0.end(now: now) }.max() ?? mark.y0)
        let span = max(1, to - from)
        Card(title: mark.kind == .life ? L("Their time") : L("At the same time")) {
            NavigationLink(value: Route.timeline(packId: mark.packId, path: mark.path)) {
                Text("Open in Timeline").font(Spectral.medium(17)).foregroundStyle(palette.tertiary)
            }
            .accessibilityIdentifier("open-in-timeline")
            if !time.people.isEmpty {
                Text("People whose lives overlapped").font(.subheadline).foregroundStyle(palette.onSurfaceVariant)
                ForEach(time.people, id: \.qid) { p in
                    NavigationLink(value: Route.article(packId: p.packId, path: p.path)) {
                        HStack(spacing: 10) {
                            Avatar(path: time.pictures[p.path])
                            Text(p.title).font(Spectral.regular(17)).foregroundStyle(palette.onSurface)
                                .frame(width: 130, alignment: .leading).lineLimit(2)
                            GeometryReader { g in
                                let x0 = CGFloat(p.y0 - from) / CGFloat(span) * g.size.width
                                let x1 = CGFloat(p.end(now: now) - from) / CGFloat(span) * g.size.width
                                ZStack(alignment: .leading) {
                                    Capsule().fill(palette.surfaceContainerHighest).frame(height: 10)
                                    Capsule().fill(palette.brass).frame(width: max(6, x1 - x0), height: 10).offset(x: x0)
                                }
                                .frame(maxHeight: .infinity)
                            }
                            .frame(height: 24)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("\(p.title), \(words.of(m: p))")
                }
            }
            if !time.events.isEmpty {
                Text("What happened in those years").font(.subheadline).foregroundStyle(palette.onSurfaceVariant).padding(.top, 6)
                FlowLayout(spacing: 8) {
                    ForEach(time.events, id: \.qid) { e in
                        NavigationLink(value: Route.article(packId: e.packId, path: e.path)) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(e.title).font(Spectral.medium(16)).foregroundStyle(palette.onSurface)
                                Text(words.of(m: e)).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                            }
                            .padding(.horizontal, 12).padding(.vertical, 8)
                            .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 10))
                        }
                        .buttonStyle(.plain)
                    }
                }
            }
        }
    }
}

/// A round picture from a cached file, or a blank disc.
struct Avatar: View {
    let path: String?
    var size: CGFloat = 36
    @Environment(\.palette) private var palette

    var body: some View {
        Group {
            if let path, let image = UIImage(contentsOfFile: path) {
                Image(uiImage: image).resizable().scaledToFill()
            } else {
                palette.surfaceContainerHighest
            }
        }
        .frame(width: size, height: size).clipShape(Circle())
        .accessibilityHidden(true)
    }
}

/// A titled card (Android's topic page cards).
struct Card<Content: View>: View {
    let title: String
    @ViewBuilder let content: Content
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(Spectral.medium(24, relativeTo: .title2)).foregroundStyle(palette.onSurface)
                .accessibilityAddTraits(.isHeader)
            content
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 16))
    }
}

/// Articles as a two-column grid of cards: picture, title, opening words (More in the room, Closest topics).
struct ArticleGrid: View {
    let title: String
    let articles: [TopicArticle]
    let lang: String

    var body: some View {
        Card(title: title) {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 12, alignment: .top), GridItem(.flexible(), spacing: 12, alignment: .top)], spacing: 16) {
                ForEach(articles, id: \.path) { a in ArticleTile(article: a, lang: lang) }
            }
        }
    }
}

/// One article's card.
struct ArticleTile: View {
    let article: TopicArticle
    let lang: String
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationLink(value: Route.article(packId: article.packId, path: article.path)) {
            VStack(alignment: .leading, spacing: 6) {
                Color.clear.frame(height: 110)
                    .overlay {
                        if let p = article.picture, let image = UIImage(contentsOfFile: p) {
                            Image(uiImage: image).resizable().scaledToFill()
                        } else {
                            palette.surfaceContainerHighest
                        }
                    }
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                HStack(spacing: 6) {
                    Text(article.title).font(Spectral.medium(18)).foregroundStyle(palette.onSurface).lineLimit(2)
                    MarkView(lang: lang, title: article.path)
                }
                Text(article.blurb).font(Spectral.regular(15)).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
            }
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
    }
}

/// Lays children out in rows, wrapping (the event chips).
struct FlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let width = proposal.width ?? .infinity
        var x: CGFloat = 0, y: CGFloat = 0, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > 0 && x + s.width > width { x = 0; y += row + spacing; row = 0 }
            x += s.width + spacing
            row = max(row, s.height)
        }
        return CGSize(width: width == .infinity ? x : width, height: y + row)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX, y = bounds.minY, row: CGFloat = 0
        for v in subviews {
            let s = v.sizeThatFits(.unspecified)
            if x > bounds.minX && x + s.width > bounds.maxX { x = bounds.minX; y += row + spacing; row = 0 }
            v.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(s))
            x += s.width + spacing
            row = max(row, s.height)
        }
    }
}

extension YearWords {
    /// Android's wording (home_timeline_*) in the app's language, from ScreenText.
    static let app = YearWords(
        bc: ScreenText.word("home_timeline_bc"), circa: ScreenText.word("home_timeline_circa"),
        millionAgo: ScreenText.word("home_timeline_million_ago"), billionAgo: ScreenText.word("home_timeline_billion_ago"),
        rangeText: ScreenText.word("home_timeline_range"), living: ScreenText.word("home_timeline_living"),
        num: { n in n.int64Value.formatted(.number.grouping(.automatic)) },
        decimal: { v in v.doubleValue.formatted(.number.precision(.fractionLength(1))) }
    )}

@Observable
final class TopicModel {
    var topic: Topic?
    var failed = false
    var other: (lang: String, path: String)?

    @MainActor
    func load(core: Core, lang: String, path: String, packId: String) async {
        failed = false
        other = nil
        let client = core.client
        let to = lang == "es" ? "en" : "es"
        Task { @MainActor in
            if let p = await background({ client.languageLink(lang: lang, title: path, to: to) }) { other = (to, p) }
        }
        var gotHeader = false
        for await t in core.library.topics.topic(packId: packId, path: path) {
            topic = t
            if t.header != nil { gotHeader = true }
            if t.done { break }
        }
        if !gotHeader { failed = true }
    }
}
