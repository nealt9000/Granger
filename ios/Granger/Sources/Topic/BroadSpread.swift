import GrangerCoreKit
import SwiftUI

/// A broad topic's spread (Plan EX D5; Android's BroadSpread): its opening plates, head, Read and Connections, its kinds
/// as families of plates, its story through the years, its people, its chapters, and the ways on, with the topics
/// before and after it in its room.
struct BroadSpreadView: View {
    let topic: Topic
    let broad: BroadTopic
    let lang: String
    let path: String
    /// The article in the other knowledge language, when Wikipedia links one (Android's `topic.other`).
    var other: (lang: String, path: String)? = nil
    @Environment(\.palette) private var palette
    @State private var opened: Set<Int> = []

    var body: some View {
        let header = topic.header!
        let plates = [PlateItem(id: "b0", title: header.title, picture: header.picture)]
            + broad.figures.prefix(2).enumerated().map { i, f in PlateItem(id: "b\(i + 1)", title: f.caption, picture: f.picture, whole: f.picture.hasSuffix(".w.jpg")) }
        VStack(alignment: .leading, spacing: 0) {
            if let place = broad.place {
                TrailLine(steps: [L("Explore"), Words.of(Subject.companion.of(id: place.subject)?.labelKey ?? "")] + (place.room.map { [$0.name(language: lang)] } ?? []), here: header.title)
                    .padding(.horizontal, 16).padding(.bottom, 8)
            }
            OpeningPlates(plates: plates, numbered: plates.count > 1)
            VStack(alignment: .leading, spacing: 10) {
                SpreadHead(eyebrow: topic.time.map { YearWords.app.of(m: $0.mark) }, title: header.title, standfirst: header.lead, large: true)
                if plates.count > 1 { Captions(lines: plates.map(\.title)) }
                HStack(spacing: 8) {
                    NavigationLink(value: Route.reader(lang: lang, path: path, fragment: nil)) {
                        Text("Read the article").font(.callout.weight(.medium)).padding(.horizontal, 18).padding(.vertical, 12)
                    }
                    .background(palette.secondary, in: RoundedRectangle(cornerRadius: 2))
                    .foregroundStyle(palette.onSecondary)
                    .accessibilityIdentifier("read-full")
                    NavigationLink(value: Route.quilt(lang: lang, path: path)) { DoLabel(text: L("Connections")) }
                        .accessibilityIdentifier("connections")
                }
                if let other {
                    // Beside Connections on Android's spread; its own line here, as the name in its own words runs long.
                    NavigationLink(value: Route.topic(lang: other.lang, path: other.path)) {
                        DoLabel(text: other.lang == "es" ? L("Leer en español") : L("Read in English"))
                    }
                    .accessibilityIdentifier("read-other-language")
                }
                KeepButton(lang: lang, title: path)
            }
            .padding(.horizontal, 16).padding(.top, 16)
            kinds
            if let story = broad.story, !story.entries.isEmpty { storyView(story) }
            if !broad.people.isEmpty { people }
            if !broad.chapters.isEmpty { chapters }
            WaysOn(title: L("Ways on"), ways: [(L("Wander from here"), L("Wander"), Route.wander(packId: topic.packId, path: path))]).padding(.top, 30)
            if let place = broad.place, place.total > 0 {
                Doors(before: place.before.map { ($0.title, route($0)) }, after: place.after.map { ($0.title, route($0)) },
                      middle: L("\(place.position) of \(place.total)"), beforeLabel: L("The topic before"), afterLabel: L("The topic after"))
                    .padding(.top, 24)
            }
        }
    }

    @ViewBuilder private var kinds: some View {
        let families = broad.families.filter { !$0.all.isEmpty }
        if !families.isEmpty {
            let named = families.compactMap(\.name)
            SpreadHead(eyebrow: L("Kinds"), title: named.isEmpty ? L("\(broad.under) kinds") : L("\(broad.groups.count) families: \(named.prefix(3).joined(separator: ", "))"))
                .padding(.horizontal, 16).padding(.top, 32).padding(.bottom, 10)
            ForEach(Array(families.enumerated()), id: \.offset) { i, family in
                VStack(alignment: .leading, spacing: 8) {
                    Text(family.name ?? L("Other kinds")).font(Spectral.medium(20)).foregroundStyle(palette.onSurface)
                    let shown = opened.contains(i) ? family.all : family.plates
                    LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                        ForEach(shown, id: \.path) { a in
                            NavigationLink(value: route(a)) {
                                PlateTile(plate: PlateItem(id: a.path, title: a.title, picture: a.picture, whole: a.picture?.hasSuffix(".w.jpg") == true), showTitle: true, titleSize: 13)
                                    .frame(height: 110)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    if family.all.count > family.plates.count {
                        Button(opened.contains(i) ? "Fewer" : "All \(family.all.count)") {
                            if opened.contains(i) { opened.remove(i) } else { opened.insert(i) }
                        }
                        .font(.callout.weight(.medium)).foregroundStyle(palette.tertiary)
                    }
                }
                .padding(.horizontal, 16).padding(.bottom, 16)
            }
        }
    }

    private func storyView(_ story: BroadStory) -> some View {
        let years = story.entries.map(\.year)
        let first = years.min() ?? 0, last = years.max() ?? 0
        return VStack(alignment: .leading, spacing: 12) {
            SpreadHead(eyebrow: L("Chapter · \(story.title)"), title: first != last ? L("From \(YearWords.app.year(y: first)) to \(YearWords.app.year(y: last))") : story.title)
            ForEach(Array(story.entries.enumerated()), id: \.offset) { _, e in
                HStack(alignment: .top, spacing: 12) {
                    Text(YearWords.app.year(y: e.year)).font(Spectral.medium(17)).foregroundStyle(palette.primary).frame(width: 84, alignment: .trailing)
                    if let p = e.picture {
                        PlateTile(plate: PlateItem(id: p, title: "", picture: p)).frame(width: 110, height: 80)
                    }
                    if let o = e.opens {
                        NavigationLink(value: route(o)) { Text(e.text).font(Spectral.regular(16)).foregroundStyle(palette.onSurface).multilineTextAlignment(.leading) }
                            .buttonStyle(.plain)
                    } else {
                        Text(e.text).font(Spectral.italic(16)).foregroundStyle(palette.onSurface)
                    }
                }
            }
        }
        .padding(.horizontal, 16).padding(.top, 28)
    }

    private var people: some View {
        VStack(alignment: .leading, spacing: 10) {
            SpreadHead(eyebrow: L("People"), title: L("\(broad.people.count) people stand near it"))
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                ForEach(broad.people, id: \.article.path) { p in
                    NavigationLink(value: route(p.article)) {
                        PlateTile(plate: PlateItem(id: p.article.path, title: p.article.title, picture: p.article.picture, sub: p.mark.map { YearWords.app.of(m: $0) }), showTitle: true, titleSize: 13)
                            .frame(height: 130)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .padding(.horizontal, 16).padding(.top, 28)
    }

    private var chapters: some View {
        VStack(alignment: .leading, spacing: 12) {
            SpreadHead(eyebrow: L("Chapters"), title: L("What its article covers"))
            ForEach(Array(broad.chapters.enumerated()), id: \.offset) { _, c in
                VStack(alignment: .leading, spacing: 4) {
                    Text(c.title).font(Spectral.medium(19)).foregroundStyle(palette.onSurface)
                    FlowLayout(spacing: 6) {
                        ForEach(c.links, id: \.path) { a in
                            NavigationLink(value: route(a)) {
                                Text(a.title).font(.callout).padding(.horizontal, 10).padding(.vertical, 5)
                                    .background(palette.surfaceContainerHigh, in: Capsule()).foregroundStyle(palette.onSurface)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
            }
        }
        .padding(.horizontal, 16).padding(.top, 28)
    }

    private func route(_ a: TopicArticle) -> Route {
        Route.article(packId: a.packId, path: a.path)
    }
}
