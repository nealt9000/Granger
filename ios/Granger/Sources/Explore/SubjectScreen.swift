import GrangerCoreKit
import SwiftUI

/// One subject (Android's SubjectPage, Plan EX): its opening plates, head, Wander and Quiz, its rooms as plates with
/// what their dates say, and how many rooms the reader has seen.
struct SubjectScreen: View {
    let subjectId: String
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var holder = ViewModelHolder<SubjectViewModel, SubjectUi>()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let ui = holder.ui { content(ui) } else { ProgressView().frame(maxWidth: .infinity, minHeight: 300) }
            }
            .padding(.bottom, 32)
        }
        .background(palette.background)
        .navigationTitle(holder.ui.map { Words.subject($0.subject) } ?? "")
        .navigationBarTitleDisplayMode(.inline)
        .task { if let core { holder.start({ core.library.subject(subjectId: subjectId, facetId: nil, listing: false) }, flow: { $0.ui }) } }
    }

    @ViewBuilder private func content(_ ui: SubjectUi) -> some View {
        let name = Words.subject(ui.subject)
        let cards = ui.facets
        TrailLine(steps: ["Explore"], here: name).padding(.horizontal, 16).padding(.bottom, 8)
        if !ui.opening.isEmpty {
            OpeningPlates(plates: ui.opening.enumerated().map { i, a in PlateItem(id: "o\(i)", title: a.title, picture: a.picture, whole: a.whole) })
        } else if ui.openingPending {
            palette.surfaceContainer.frame(height: 230)
        }
        VStack(alignment: .leading, spacing: 12) {
            SpreadHead(eyebrow: cards.isEmpty ? "A subject" : "A subject · \(Words.rooms(Int32(cards.count)))", title: name, standfirst: ui.hero?.sentence, large: true)
            HStack(spacing: 8) {
                if ui.canWander {
                    NavigationLink(value: Route.wanderIn(subject: ui.subject.id, facet: nil)) { DoLabel(text: "Wander") }
                        .accessibilityIdentifier("subject-wander")
                }
                if ui.canQuiz {
                    NavigationLink(value: Route.quiz(subject: ui.subject.id)) { DoLabel(text: "Quiz") }
                        .accessibilityIdentifier("subject-quiz")
                }
            }
        }
        .padding(.horizontal, 16).padding(.top, 16)
        if !cards.isEmpty {
            let rooms = Words.rooms(Int32(cards.count))
            let said = ui.finding.flatMap { f in f.share.map { "\(Words.dated($0)) are from the \(f.centuryStart!.int64Value)s." } }
            SpreadHead(eyebrow: "The rooms", title: said.map { "\(rooms). \($0)" } ?? "\(rooms), \(Words.topics(ui.count))")
                .padding(.horizontal, 16).padding(.top, 30).padding(.bottom, 12)
            let pairs = stride(from: 0, to: cards.count, by: 2).map { Array(cards[$0..<min($0 + 2, cards.count)]) }
            ForEach(Array(pairs.enumerated()), id: \.offset) { _, pair in
                HStack(alignment: .top, spacing: 12) {
                    ForEach(pair, id: \.facet.id) { c in RoomCard(card: c, seen: ui.seen?.contains(c.facet.key) == true) }
                    if pair.count == 1 { Color.clear.frame(maxWidth: .infinity) }
                }
                .padding(.horizontal, 16).padding(.bottom, 16)
            }
            NavigationLink(value: Route.room(subject: ui.subject.id, facet: nil)) {
                Text("All of \(name)").font(.callout.weight(.medium)).foregroundStyle(palette.tertiary).frame(minHeight: 48)
            }
            .padding(.horizontal, 16)
        } else if !ui.articles.isEmpty {
            Eyebrow(text: "Articles").padding(.horizontal, 16).padding(.top, 28)
            ForEach(ui.articles.prefix(12), id: \.path) { a in ArticleLine(article: a) }
        } else if !ui.loading && !ui.roomsPending {
            Text("Nothing on this subject yet").padding(16)
        }
        if let seen = ui.seen, !cards.isEmpty {
            RoomsSeenLine(seen: Int32(cards.filter { seen.contains($0.facet.key) }.count), total: Int32(cards.count), label: "Rooms seen in \(name)")
                .padding(.top, 18)
        }
        if let doors = ui.doors {
            Doors(before: doors.before.map { (Words.subject($0), Route.subject(id: $0.id)) },
                  after: doors.after.map { (Words.subject($0), Route.subject(id: $0.id)) },
                  middle: "Subject \(doors.position) of \(doors.total)", beforeLabel: "The subject before", afterLabel: "The subject after")
                .padding(.top, 24)
        }
    }
}

/// A room as a plate: its picture, name and count, and its sentence (its finding, else its description).
private struct RoomCard: View {
    let card: FacetCard
    let seen: Bool
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationLink(value: Route.room(subject: card.facet.subject, facet: card.facet.id)) {
            VStack(alignment: .leading, spacing: 6) {
                PlateTile(plate: PlateItem(id: card.facet.id, title: card.facet.name(language: "en"), picture: card.picture, whole: card.picture?.hasSuffix(".w.jpg") == true))
                    .frame(height: 120)
                    .overlay(alignment: .topTrailing) {
                        if seen { Image(systemName: "checkmark.circle.fill").foregroundStyle(.white, palette.tertiary).padding(6).accessibilityLabel("Seen") }
                    }
                Text(card.facet.name(language: "en")).font(Spectral.medium(19)).foregroundStyle(palette.onSurface)
                Text(Words.topics(card.count)).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                if let line = sentence {
                    Text(line).font(Spectral.regular(15)).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("room-\(card.facet.id)")
    }

    private var sentence: String? {
        if let f = card.finding, let oldest = f.oldest, let mark = oldest.mark {
            return "Oldest dated: \(oldest.title) (\(YearWords.english.of(m: mark)))."
        }
        return card.description_
    }
}

/// A room (Android's RoomContent): its head and finding, the room in plates through time, Plates / Through time / A–Z,
/// a page of articles at a time, the other rooms that hold the same things, Wander, and the rooms before and after.
/// [facetId] nil is the whole subject's listing.
struct RoomScreen: View {
    let subjectId: String
    let facetId: String?
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var holder = ViewModelHolder<SubjectViewModel, SubjectUi>()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let ui = holder.ui { content(ui) } else { ProgressView().frame(maxWidth: .infinity, minHeight: 300) }
            }
            .padding(.bottom, 32)
        }
        .background(palette.background)
        .navigationTitle(title)
        .navigationBarTitleDisplayMode(.inline)
        .task { if let core { holder.start({ core.library.subject(subjectId: subjectId, facetId: facetId, listing: true) }, flow: { $0.ui }) } }
    }

    private var title: String {
        guard let ui = holder.ui else { return "" }
        return ui.facet?.name(language: "en") ?? "All of \(Words.subject(ui.subject))"
    }

    @ViewBuilder private func content(_ ui: SubjectUi) -> some View {
        let subjectName = Words.subject(ui.subject)
        TrailLine(steps: ["Explore", subjectName], here: title).padding(.horizontal, 16).padding(.bottom, 8)
        VStack(alignment: .leading, spacing: 10) {
            let eyebrow = ui.facet == nil ? "The whole subject" : (ui.count < 50 ? "A small room in \(subjectName)" : "A room in \(subjectName)")
            SpreadHead(eyebrow: eyebrow, title: title, standfirst: standfirst(ui), large: true)
        }
        .padding(.horizontal, 16)
        if let story = ui.room?.story, !story.isEmpty {
            Eyebrow(text: story.count == 1 ? "The room in 1 plate" : "The room in \(story.count) plates").padding(.horizontal, 16).padding(.top, 22).padding(.bottom, 8)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(alignment: .top, spacing: 8) {
                    ForEach(story, id: \.path) { a in
                        NavigationLink(value: route(a)) {
                            VStack(alignment: .leading, spacing: 4) {
                                PlateTile(plate: PlateItem(id: a.path, title: a.title, picture: a.picture, whole: a.whole)).frame(width: 170, height: 130)
                                if let m = a.mark { Text(YearWords.english.of(m: m)).font(.caption).foregroundStyle(palette.tertiary) }
                                Text(a.title).font(Spectral.medium(17)).foregroundStyle(palette.onSurface).lineLimit(2).frame(width: 170, alignment: .leading)
                            }
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        // Plates / Through time / A–Z.
        HStack(spacing: 20) {
            tab("Plates", .linked, ui)
            if !ui.eras.isEmpty { tab("Through time", .oldest, ui) }
            if ui.count > 0 { tab("A–Z", .az, ui) }
        }
        .padding(.horizontal, 16).padding(.top, 24).padding(.bottom, 8)
        if ui.sort == .az, !ui.letters.isEmpty {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 4) {
                    ForEach(ui.letters, id: \.self) { l in
                        Button(l) { holder.model?.jump(letter: l) }
                            .font(Spectral.medium(17)).frame(minWidth: 32, minHeight: 36)
                            .foregroundStyle(ui.letter == l ? palette.onSecondaryContainer : palette.primary)
                            .background(ui.letter == l ? palette.secondaryContainer : .clear, in: RoundedRectangle(cornerRadius: 6))
                    }
                }
                .padding(.horizontal, 16)
            }
        }
        if !ui.eras.isEmpty && ui.sort != .az {
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    chip("All eras", on: ui.era == nil) { holder.model?.choose(era: nil) }
                    ForEach(eraChips(ui), id: \.label) { c in
                        chip(c.label, on: ui.era == c.era) { holder.model?.choose(era: c.era) }
                    }
                }
                .padding(.horizontal, 16)
            }
            .padding(.bottom, 12)
        }
        let rows = stride(from: 0, to: ui.articles.count, by: 3).map { Array(ui.articles[$0..<min($0 + 3, ui.articles.count)]) }
        ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
            HStack(spacing: 2) {
                ForEach(row, id: \.path) { a in
                    NavigationLink(value: route(a)) {
                        PlateTile(plate: PlateItem(id: a.path, title: a.title, picture: a.picture, whole: a.whole, sub: a.mark.map { YearWords.english.of(m: $0) }), showTitle: true, titleSize: 15)
                            .frame(height: 140)
                    }
                    .buttonStyle(.plain)
                }
                ForEach(0..<(3 - row.count), id: \.self) { _ in Color.clear.frame(height: 140) }
            }
            .padding(.bottom, 2)
        }
        if ui.loading {
            ProgressView().frame(maxWidth: .infinity).padding()
        } else if ui.more {
            Button("Show more") { holder.model?.more() }
                .font(.callout.weight(.medium)).foregroundStyle(palette.tertiary).padding(16)
                .accessibilityIdentifier("show-more")
        } else if ui.articles.isEmpty {
            Text("Nothing on this subject yet").padding(16)
        }
        if let overlaps = ui.room?.overlaps, let lead = overlaps.first {
            VStack(alignment: .leading, spacing: 10) {
                SpreadHead(eyebrow: "The same things, other rooms", title: "\(Words.topics(lead.shared)) also hang in \(lead.facet.name(language: "en"))")
                ForEach(overlaps, id: \.facet.key) { o in
                    NavigationLink(value: Route.room(subject: o.facet.subject, facet: o.facet.id)) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("also hang in \(o.facet.name(language: "en"))").font(.callout.weight(.medium)).foregroundStyle(palette.onSurface)
                            Text("\(Words.count(o.shared)) · \(o.examples.joined(separator: ", "))").font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(2)
                        }
                    }
                    .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 16).padding(.top, 28)
        }
        if ui.canWander {
            WaysOn(title: "Ways on", ways: [
                (ui.facet == nil ? "Anywhere in this subject" : "Anywhere in this room", "Wander", Route.wanderIn(subject: ui.subject.id, facet: ui.facet?.id)),
                ("The whole subject", subjectName, Route.subject(id: ui.subject.id)),
            ])
            .padding(.top, 28)
        }
        if let room = ui.room, room.total > 0 {
            Doors(before: room.before.map { ($0.name(language: "en"), Route.room(subject: $0.subject, facet: $0.id)) },
                  after: room.after.map { ($0.name(language: "en"), Route.room(subject: $0.subject, facet: $0.id)) },
                  middle: "Room \(room.position) of \(room.total)", beforeLabel: "The room before", afterLabel: "The room after")
                .padding(.top, 24)
        }
    }

    private func standfirst(_ ui: SubjectUi) -> String {
        var parts: [String] = []
        if let d = ui.room?.description_ { parts.append(d) }
        if ui.count > 0 { parts.append(Words.topics(ui.count) + ".") }
        if let f = ui.room?.finding ?? ui.finding {
            if let o = f.oldest, let m = o.mark { parts.append("The oldest dated here is \(o.title) (\(YearWords.english.of(m: m))).") }
            if let share = f.share, let c = f.centuryStart { parts.append("\(Words.dated(share)) are from the \(c.int64Value)s.") }
        }
        return parts.joined(separator: " ")
    }

    private func eraChips(_ ui: SubjectUi) -> [(era: Era, label: String)] {
        ui.eras.compactMap { pair -> (era: Era, label: String)? in
            guard let e = pair.first else { return nil }
            let era = e as Era
            let n: Int32 = pair.second?.int32Value ?? 0
            return (era, "\(Words.era(era)) · \(Words.count(n))")
        }
    }

    private func tab(_ label: String, _ sort: SubjectSort, _ ui: SubjectUi) -> some View {
        Button { holder.model?.sort(by: sort) } label: {
            VStack(spacing: 6) {
                Text(label).font(Spectral.medium(18)).foregroundStyle(ui.sort == sort ? palette.onSurface : palette.onSurfaceVariant)
                Rectangle().fill(ui.sort == sort ? palette.tertiary : .clear).frame(height: 3)
            }
            .fixedSize()
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(ui.sort == sort ? .isSelected : [])
        .accessibilityIdentifier("view-\(label)")
    }

    private func chip(_ text: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(text).font(Spectral.regular(15)).padding(.horizontal, 12).padding(.vertical, 7)
                .background(on ? palette.secondaryContainer : palette.surfaceContainer, in: Capsule())
                .foregroundStyle(on ? palette.onSecondaryContainer : palette.onSurface)
        }
        .buttonStyle(.plain)
    }

    private func route(_ a: ExploreArticle) -> Route {
        .topic(lang: OnlineWikipediaArchive.companion.languageOf(packId: a.packId) ?? "en", path: a.path)
    }
}

/// The trail over a page: Explore › subject › here.
struct TrailLine: View {
    let steps: [String]
    let here: String
    @Environment(\.palette) private var palette

    var body: some View {
        trailText(steps: steps, here: here, palette: palette)
            .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("You are here: \((steps + [here]).joined(separator: ", "))")
    }
}

/// The before / position / after doors at the foot of a page.
struct Doors: View {
    let before: (String, Route)?
    let after: (String, Route)?
    let middle: String
    let beforeLabel: String
    let afterLabel: String
    @Environment(\.palette) private var palette

    var body: some View {
        HStack(alignment: .center, spacing: 0) {
            door(before, label: beforeLabel, leading: true)
            Text(middle.uppercased()).font(.caption.weight(.semibold)).tracking(1).foregroundStyle(palette.tertiary)
                .multilineTextAlignment(.center).frame(maxWidth: 110)
            door(after, label: afterLabel, leading: false)
        }
        .padding(.vertical, 12)
        .background(palette.surfaceContainerLow)
    }

    @ViewBuilder private func door(_ d: (String, Route)?, label: String, leading: Bool) -> some View {
        if let d {
            NavigationLink(value: d.1) {
                VStack(alignment: leading ? .leading : .trailing, spacing: 2) {
                    Text(label.uppercased()).font(.caption2.weight(.semibold)).tracking(1).foregroundStyle(palette.onSurfaceVariant)
                    Text(d.0).font(Spectral.medium(18)).foregroundStyle(palette.onSurface).lineLimit(2)
                        .multilineTextAlignment(leading ? .leading : .trailing)
                }
                .frame(maxWidth: .infinity, alignment: leading ? .leading : .trailing)
                .padding(.horizontal, 12)
            }
            .buttonStyle(.plain)
        } else {
            Color.clear.frame(maxWidth: .infinity)
        }
    }
}

/// An article as a line: title and picture.
struct ArticleLine: View {
    let article: ExploreArticle
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationLink(value: Route.topic(lang: OnlineWikipediaArchive.companion.languageOf(packId: article.packId) ?? "en", path: article.path)) {
            HStack(spacing: 12) {
                Avatar(path: article.picture, size: 44)
                Text(article.title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface)
                Spacer()
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }
}

/// A plain outlined button's label (Android's DoButton).
struct DoLabel: View {
    let text: String
    @Environment(\.palette) private var palette
    var body: some View {
        Text(text).font(.callout.weight(.medium)).padding(.horizontal, 18).padding(.vertical, 12)
            .overlay(RoundedRectangle(cornerRadius: 2).stroke(palette.outline))
            .foregroundStyle(palette.onSurface)
    }
}

/// A trail as one run of text, so it wraps between words at any text size: steps in the accent, then where you are.
func trailText(steps: [String], here: String, palette: Palette) -> Text {
    var out = Text("")
    for step in steps {
        let name: Text = Text(step).foregroundColor(palette.primary)
        let sep: Text = Text("  ›  ").foregroundColor(palette.outline)
        out = out + name + sep
    }
    let last: Text = Text(here).foregroundColor(palette.onBackground)
    return (out + last).font(.subheadline)
}
