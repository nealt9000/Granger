import GrangerCoreKit
import SwiftUI

/// A shared view model (`OwnedViewModel`) as SwiftUI sees it: its state mirrored as it changes, cleared when it goes.
@Observable
final class ViewModelHolder<VM: Lifecycle_viewmodelViewModel, UI: AnyObject> {
    private(set) var ui: UI?
    private(set) var owned: OwnedViewModel<VM>?
    private var watching: Task<Void, Never>?

    var model: VM? { owned?.model }

    @MainActor
    func start(_ make: () -> OwnedViewModel<VM>?, flow: (VM) -> SkieSwiftOptionalStateFlow<UI>) {
        guard owned == nil, let o = make() else { return }
        owned = o
        let f = flow(o.model)
        watching = Task { @MainActor [weak self] in
            for await ui in f { self?.ui = ui }
        }
    }

    deinit {
        watching?.cancel()
        owned?.close()
    }
}

/// Words for the shared Explore models' keys (Subject and Era labels).
enum Words {
    static func of(_ key: String) -> String { ScreenText.strings[key] ?? key }
    static func subject(_ s: Subject) -> String { of(s.labelKey) }
    static func era(_ e: Era) -> String { of(e.labelKey) }
    static func span(_ e: Era) -> String { of(e.spanKey) }
    static func eraFrom(_ e: Era) -> String {
        switch e {
        case .prehistory: L("before 3000 BC")
        case .ancient: L("the ancient world")
        case .middleAges: L("the Middle Ages")
        case .earlyModern: L("1500 to 1800")
        case .c19: L("the 1800s")
        case .wars: L("1900 to 1945")
        default: L("1946 or later")
        }
    }
    static func dated(_ s: Share) -> String {
        switch s {
        case .nearlyAll: L("Nearly all the dated topics")
        case .threeInFour: L("About three in four dated topics")
        case .twoInThree: L("About two in three dated topics")
        case .half: L("About half the dated topics")
        case .oneInThree: L("About one in three dated topics")
        case .oneInFour: L("About one in four dated topics")
        case .oneInFive: L("About one in five dated topics")
        default: L("About one in ten dated topics")
        }
    }
    static func count(_ n: Int32) -> String { Int(n).formatted() }
    static func topics(_ n: Int32) -> String { n == 1 ? L("1 topic") : L("\(count(n)) topics") }
    static func rooms(_ n: Int32) -> String { n == 1 ? L("1 room") : L("\(count(n)) rooms") }
    static func subjects(_ n: Int) -> String { L("\(n) subjects") }
}

/// Explore's landing (Android's ExploreContent, Plan EX): today's spread, the library's subject doors, Through time,
/// the ways on (Wander, Games, Your sources) and the rooms seen.
struct ExploreScreen: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(LibraryModel.self) private var library
    @State private var holder = ViewModelHolder<ExploreViewModel, ExploreUi>()

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if library.index == .coming {
                    Text("Explore is getting its index for Wikipedia online (19 MB). Subjects, rooms and eras appear when it's here.")
                        .font(Spectral.regular(17)).foregroundStyle(palette.onSurface).padding(16)
                }
                if let ui = holder.ui {
                    if ui.noSubjects && library.index != .coming {
                        Text("Explore needs Wikipedia's index. It comes when you're online.")
                            .font(Spectral.regular(17)).foregroundStyle(palette.onSurface).padding(16)
                    }
                    if let topic = ui.featured { spread(topic, skipped: ui.skipped) }
                    if !ui.doors.isEmpty { library(ui) }
                    if !ui.eras.isEmpty { time(ui) }
                    ways(ui)
                    if let seen = ui.roomsSeen?.int32Value, ui.rooms > 0 { RoomsSeenLine(seen: seen, total: ui.rooms).padding(.top, 12) }
                } else if library.index != .coming {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 300)
                }
            }
            .padding(.bottom, 32)
        }
        .background(palette.background)
        .navigationTitle("Explore")
        .toolbar { ToolbarItem(placement: .topBarTrailing) { RecentlyOpenMenu() } }
        // Built whatever the index's state, as Android's landing: with no index (Local only on a fresh install) it still
        // says so and keeps Ways on, with Your sources and the packs; it builds again when the index comes.
        .task(id: library.index) {
            guard let core else { return }
            holder.start({ core.library.explore() }, flow: { $0.ui })
            holder.model?.refresh()
        }
    }

    private static let dayWords: String = Date().formatted(.dateTime.weekday(.wide).month(.wide).day())

    @ViewBuilder private func spread(_ topic: FeaturedTopic, skipped: Int32) -> some View {
        let a = topic.article
        Eyebrow(text: skipped == 0 ? L("\(Self.dayWords) · today's spread") : L("Another spread")).padding(.horizontal, 16).padding(.bottom, 10)
        let plates = [PlateItem(id: "spread:0", title: a.title, picture: a.picture, whole: a.whole)]
            + topic.figures.enumerated().map { i, f in PlateItem(id: "spread:\(i + 1)", title: f.caption, picture: f.picture, whole: f.picture.hasSuffix(".w.jpg")) }
        NavigationLink(value: route(a)) { OpeningPlates(plates: plates, numbered: plates.count > 1) }
            .buttonStyle(.plain)
            .accessibilityIdentifier("open-spread-plates")
        VStack(alignment: .leading, spacing: 10) {
            let subject = Words.subject(topic.subject)
            SpreadHead(eyebrow: topic.room.map { "\(subject) · \($0.name(language: AppLanguage.code))" } ?? subject, title: a.title, standfirst: topic.sentence, large: true)
            if !topic.figures.isEmpty { Captions(lines: [a.title] + topic.figures.map(\.caption)) }
            let holds = [topic.kinds > 0 ? L("\(topic.kinds) kinds") : nil, topic.people > 0 ? L("\(topic.people) people") : nil, topic.chapters > 0 ? L("\(topic.chapters) chapters") : nil].compactMap { $0 }
            if !holds.isEmpty { Text("On this spread: \(holds.joined(separator: ", ")).").font(.callout).foregroundStyle(palette.onSurfaceVariant) }
            HStack(spacing: 8) {
                NavigationLink(value: route(a)) { Text("Open the spread").font(.callout.weight(.medium)).padding(.horizontal, 18).padding(.vertical, 12) }
                    .background(palette.secondary, in: RoundedRectangle(cornerRadius: 2))
                    .foregroundStyle(palette.onSecondary)
                    .accessibilityIdentifier("open-spread")
                Button { holder.model?.another() } label: { Text("Another").font(.callout.weight(.medium)).padding(.horizontal, 18).padding(.vertical, 12) }
                    .overlay(RoundedRectangle(cornerRadius: 2).stroke(palette.outline))
                    .foregroundStyle(palette.onSurface)
                    .accessibilityIdentifier("another")
            }
            .padding(.top, 4)
        }
        .padding(.horizontal, 16).padding(.top, 16)
    }

    @ViewBuilder private func library(_ ui: ExploreUi) -> some View {
        let counted = ui.doors.filter { $0.count > 0 }
        let subjects = Words.subjects(ui.doors.count)
        let eyebrow = ui.rooms > 0 ? L("The library · \(subjects), \(Words.rooms(ui.rooms))") : L("The library · \(subjects)")
        let finding = counted.count >= 4
        let title = finding ? L("\(Words.subject(counted[0].subject)), \(Words.subject(counted[1].subject)) and \(Words.subject(counted[2].subject)) are your largest subjects") : L("Subjects")
        let standfirst: String = {
            var out: [String] = []
            if finding, let small = counted.last, let big = counted.first {
                let times = Int((Double(big.count) / Double(small.count)).rounded())
                out.append(times >= 2 ? L("\(Words.subject(small.subject)) is the smallest, with \(Words.topics(small.count)); \(Words.subject(big.subject)) has \(times) times as many.")
                           : L("\(Words.subject(small.subject)) is the smallest, with \(Words.topics(small.count))."))
            }
            out.append(L("Each picture opens its subject."))
            return out.joined(separator: " ")
        }()
        SpreadHead(eyebrow: eyebrow, title: title, standfirst: standfirst).padding(.horizontal, 16).padding(.top, 36).padding(.bottom, 12)
        let kinds = ExploreModelKt.doorRows(n: Int32(ui.doors.count))
        let rows = Self.split(ui.doors, by: kinds.map { Int($0.size) })
        ForEach(Array(zip(kinds, rows).enumerated()), id: \.offset) { _, pair in
            DoorRowView(kind: pair.0, doors: pair.1)
        }
    }

    static func split<T>(_ items: [T], by sizes: [Int]) -> [[T]] {
        var at = 0
        return sizes.map { n in defer { at += n }; return Array(items[at..<min(at + n, items.count)]) }
    }

    @ViewBuilder private func time(_ ui: ExploreUi) -> some View {
        let opens = L("Each line opens its era on the Timeline.")
        if let busiest = ExploreModelKt.busiestEra(eras: ui.eras) {
            SpreadHead(eyebrow: L("Through time"), title: L("\(Words.dated(busiest.second! as Share)) are from \(Words.eraFrom(busiest.first!.era))"), standfirst: opens)
                .padding(.horizontal, 16).padding(.top, 36).padding(.bottom, 10)
        } else {
            SpreadHead(eyebrow: nil, title: L("Through time"), standfirst: opens).padding(.horizontal, 16).padding(.top, 36).padding(.bottom, 10)
        }
        let most = max(1, ui.eras.map(\.count).max() ?? 1)
        ForEach(ui.eras, id: \.era) { row in
            let top = row.examples.first
            NavigationLink(value: Route.timeline(packId: top?.packId, path: top?.path)) { EraBar(row: row, most: most) }
                .buttonStyle(.plain)
                .simultaneousGesture(TapGesture().onEnded { holder.model?.sawEra(era: row.era) })
        }
        Divider().padding(.horizontal, 16)
        NavigationLink(value: Route.timeline(packId: nil, path: nil)) {
            Text("Open the Timeline").font(.callout.weight(.medium)).foregroundStyle(palette.tertiary).frame(minHeight: 48)
        }
        .padding(.horizontal, 16)
    }

    @ViewBuilder private func ways(_ ui: ExploreUi) -> some View {
        let games = 6
        let ways: [(String, String, Route)] = [
            ui.noSubjects ? nil : (L("Anywhere in your library"), L("Wander"), Route.wander(packId: nil, path: nil)),
            ui.eras.isEmpty ? nil : (L("\(games) games with the dates in your library"), L("Games"), Route.games),
            (L("Wikipedia and your packs"), L("Your sources"), Route.yourSources),
        ].compactMap { $0 }
        if !ways.isEmpty { WaysOn(title: L("Ways on"), ways: ways).padding(.top, 30) }
    }

    private func route(_ a: ExploreArticle) -> Route {
        Route.article(packId: a.packId, path: a.path)
    }
}

// MARK: - The spread's type and pieces (Android's Spread.kt)

struct Eyebrow: View {
    let text: String
    @Environment(\.palette) private var palette
    var body: some View {
        Text(text.uppercased()).font(.caption.weight(.semibold)).tracking(1.4).foregroundStyle(palette.onSurfaceVariant)
            .fixedSize(horizontal: false, vertical: true)
    }
}

/// An eyebrow, a title in the display face and an optional standfirst.
struct SpreadHead: View {
    let eyebrow: String?
    let title: String
    var standfirst: String? = nil
    var large = false
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let eyebrow { Text(eyebrow.uppercased()).font(.caption.weight(.semibold)).tracking(1.4).foregroundStyle(palette.tertiary) }
            Text(title).font(Spectral.medium(large ? 40 : 30, relativeTo: .largeTitle)).foregroundStyle(palette.onSurface)
                .accessibilityAddTraits(.isHeader)
            if let standfirst, !standfirst.isEmpty {
                Text(standfirst).font(Spectral.regular(19, relativeTo: .body)).foregroundStyle(palette.onSurface).lineSpacing(3)
            }
        }
    }
}

/// The plates' captions, numbered as the plates are.
struct Captions: View {
    let lines: [String]
    @Environment(\.palette) private var palette
    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(lines.enumerated()), id: \.offset) { i, line in
                HStack(alignment: .firstTextBaseline, spacing: 12) {
                    Text("\(i + 1)").font(.callout).foregroundStyle(palette.tertiary)
                    Text(line).font(Spectral.italic(17)).foregroundStyle(palette.onSurface)
                }
            }
        }
    }
}

struct PlateItem: Identifiable {
    let id: String
    let title: String
    let picture: String?
    var whole = false
    var sub: String? = nil
}

/// A picture plate: the picture filling it (a drawing or emblem shown whole on white), the number in its corner.
struct PlateTile: View {
    let plate: PlateItem
    var number: Int? = nil
    var showTitle = false
    var titleSize: CGFloat = 16
    @Environment(\.palette) private var palette

    var body: some View {
        Color.clear
            .overlay {
                if let p = plate.picture, let image = UIImage(contentsOfFile: p) {
                    if plate.whole {
                        Color.white.overlay { Image(uiImage: image).resizable().scaledToFit().padding(6) }
                    } else {
                        Image(uiImage: image).resizable().scaledToFill()
                    }
                } else {
                    palette.surfaceContainerHighest
                }
            }
            .clipped()
            .overlay(alignment: .topLeading) {
                if let number {
                    Text("\(number)").font(.callout.weight(.medium)).foregroundStyle(.white)
                        .padding(.horizontal, 8).padding(.vertical, 4).background(Color.black.opacity(0.75))
                }
            }
            .overlay(alignment: .bottomLeading) {
                if showTitle {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(plate.title).font(Spectral.medium(titleSize)).foregroundStyle(.white).lineLimit(2)
                        if let sub = plate.sub { Text(sub).font(.caption).foregroundStyle(.white.opacity(0.85)) }
                    }
                    .padding(10)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .top, endPoint: .bottom))
                    // Over a picture of fixed size the caption stops at xxxLarge, as the Library's under covers do: at the
                    // accessibility sizes it was a few letters ("Hist…"). VoiceOver reads the whole label.
                    .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(plate.sub.map { "\(plate.title), \($0)" } ?? plate.title)
    }
}

/// The opening plates: a large one beside two stacked (Android's openingRow), or one alone.
struct OpeningPlates: View {
    let plates: [PlateItem]
    var numbered = true

    var body: some View {
        HStack(spacing: 3) {
            PlateTile(plate: plates[0], number: numbered ? 1 : nil).frame(maxWidth: .infinity)
            if plates.count > 1 {
                VStack(spacing: 3) {
                    ForEach(Array(plates.dropFirst().prefix(2).enumerated()), id: \.offset) { i, p in
                        PlateTile(plate: p, number: numbered ? i + 2 : nil)
                    }
                }
                .frame(width: 130)
            }
        }
        .frame(height: 230)
    }
}

/// One row of the subject mosaic: each subject a plate by its own article's picture, opening it.
struct DoorRowView: View {
    let kind: DoorRowKind
    let doors: [SubjectDoor]

    var body: some View {
        let ratio: CGFloat = switch kind {
        case .wideFirst, .wideLast: 334 / 150
        case .three: 334 / 116
        case .pair: 334 / 104
        default: 334 / 132
        }
        GeometryReader { g in
            HStack(spacing: 2) {
                ForEach(Array(doors.enumerated()), id: \.offset) { i, door in
                    let wide = (kind == .wideFirst && i == 0) || (kind == .wideLast && i == 1)
                    let units = CGFloat(doors.enumerated().map { j, _ in ((kind == .wideFirst && j == 0) || (kind == .wideLast && j == 1)) ? 2 : 1 }.reduce(0, +))
                    NavigationLink(value: Route.subject(id: door.subject.id)) {
                        PlateTile(plate: PlateItem(id: door.subject.id, title: Words.subject(door.subject), picture: door.picture, sub: countLine(door)),
                                  showTitle: true, titleSize: wide || kind == .pair || kind == .single ? 18 : 15)
                    }
                    .buttonStyle(.plain)
                    .frame(width: (g.size.width - 2 * CGFloat(doors.count - 1)) * (wide ? 2 : 1) / units)
                    .accessibilityIdentifier("door-\(door.subject.id)")
                }
            }
        }
        .aspectRatio(ratio, contentMode: .fit)
        .padding(.bottom, 2)
    }

    private func countLine(_ door: SubjectDoor) -> String {
        let names = door.packs.map(\.title) + door.collections.map(\.title)
        if door.count > 0 { return Words.topics(door.count) }
        if names.count == 1 { return names[0] }
        return L("\(names.count) sources")
    }
}

/// An era's line: its name and years, a bar as long as its share of the fullest era, and its count.
struct EraBar: View {
    let row: EraRow
    let most: Int32
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text(Words.era(row.era)).font(Spectral.medium(19)).foregroundStyle(palette.onSurface)
                Text(Words.span(row.era)).font(.caption).foregroundStyle(palette.onSurfaceVariant)
                Spacer()
                Text(Words.topics(row.count)).font(.caption).foregroundStyle(palette.onSurfaceVariant)
            }
            GeometryReader { g in
                Capsule().fill(palette.brass).frame(width: max(4, g.size.width * CGFloat(row.count) / CGFloat(most)), height: 6)
            }
            .frame(height: 6)
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

/// Ways on: a line each, what it is and where it leads.
struct WaysOn: View {
    let title: String
    let ways: [(String, String, Route)]
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Eyebrow(text: title).padding(.bottom, 8)
            ForEach(Array(ways.enumerated()), id: \.offset) { _, way in
                Divider()
                NavigationLink(value: way.2) {
                    HStack {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(way.1).font(Spectral.medium(20)).foregroundStyle(palette.onSurface)
                            Text(way.0).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                        }
                        Spacer()
                        Image(systemName: "chevron.right").foregroundStyle(palette.outline)
                    }
                    .padding(.vertical, 12)
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("way-\(way.1)")
            }
        }
        .padding(.horizontal, 16)
    }
}

/// The quiet game layer: how many of the library's rooms the reader has been in.
struct RoomsSeenLine: View {
    let seen: Int32
    let total: Int32
    var label = L("Rooms you have seen")
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(label).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                Spacer()
                Text("\(seen) of \(total)").font(.callout).foregroundStyle(palette.onSurface)
            }
            GeometryReader { g in
                ZStack(alignment: .leading) {
                    Capsule().fill(palette.surfaceContainerHighest)
                    Capsule().fill(palette.tertiary).frame(width: g.size.width * CGFloat(seen) / CGFloat(max(1, total)))
                }
            }
            .frame(height: 4)
        }
        .padding(.horizontal, 16)
        .accessibilityElement(children: .combine)
    }
}
