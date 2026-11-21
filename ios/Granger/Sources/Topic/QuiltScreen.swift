import GrangerCoreKit
import SwiftUI

/// The connections quilt (Plan EX round 2, D4; Android's TopicQuilt): the topic in the middle, the plates that touch
/// it round it, group by group (from the shared `Quilts`). Touching an article plate re-forms the quilt round it, with
/// your thread kept; Read opens the middle topic's page.
struct QuiltScreen: View {
    let lang: String
    let path: String
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var centre: (packId: String, path: String)?
    @State private var thread: [(packId: String, path: String, title: String)] = []
    @State private var topic: Topic?
    @State private var groups: [QuiltGroup] = []
    @State private var loading = true

    private var here: (packId: String, path: String) { centre ?? (GrangerCoreEntry.shared.onlinePackId(lang: lang), path) }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if !thread.isEmpty {
                    ScrollView(.horizontal, showsIndicators: false) {
                        HStack(spacing: 6) {
                            Text("Your thread").font(.caption).foregroundStyle(palette.onSurfaceVariant)
                            ForEach(Array(thread.enumerated()), id: \.offset) { i, t in
                                Button(t.title) { go(back: i) }.font(.caption).foregroundStyle(palette.primary)
                                Text("›").font(.caption).foregroundStyle(palette.outline)
                            }
                        }
                    }
                }
                if let topic, let header = topic.header {
                    Text(groups.isEmpty && !loading ? "Nothing on this phone stands near it yet." : "Around \(header.title)")
                        .font(Spectral.medium(26)).foregroundStyle(palette.onBackground).accessibilityAddTraits(.isHeader)
                    quilt(topic: topic, header: header)
                    if !groups.isEmpty { index }
                } else {
                    Text("Looking round…").foregroundStyle(palette.onSurfaceVariant).frame(maxWidth: .infinity, minHeight: 300)
                }
            }
            .padding(16)
        }
        .background(palette.background)
        .navigationTitle("Connections")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: "\(here.packId)/\(here.path)") { await load() }
    }

    private func load() async {
        guard let core else { return }
        loading = true
        let (p, a) = here
        var last: Topic?
        for await t in core.library.topics.topic(packId: p, path: a) {
            last = t
            topic = t
            if t.done { break }
        }
        let around = try? await core.library.around(packId: p, path: a, plates: [])
        if let last {
            groups = Quilts.shared.groups(topic: last, around: around, language: lang) { YearWords.english.of(m: $0) }
        }
        loading = false
    }

    @ViewBuilder private func quilt(topic: Topic, header: TopicHeader) -> some View {
        let cells = Quilts.shared.cells(groups: groups, max: Quilts.shared.CELLS)
        let layout = Quilts.shared.layout(cells: cells)
        VStack(spacing: 3) {
            row(layout.above, height: 110)
            HStack(spacing: 3) {
                if let b = layout.beside.first { cell(b).frame(width: 96) }
                middle(header).frame(maxWidth: .infinity)
                if layout.beside.count > 1 { cell(layout.beside[1]).frame(width: 96) }
            }
            .frame(height: 220)
            row(layout.below, height: 110)
            row(layout.foot, height: 90)
        }
    }

    private func row(_ cells: [QuiltCell], height: CGFloat) -> some View {
        HStack(spacing: 3) { ForEach(Array(cells.enumerated()), id: \.offset) { _, c in cell(c) } }.frame(height: cells.isEmpty ? 0 : height)
    }

    private func middle(_ header: TopicHeader) -> some View {
        NavigationLink(value: Route.article(packId: here.packId, path: here.path)) {
            PlateTile(plate: PlateItem(id: "middle", title: header.title, picture: header.picture), showTitle: true, titleSize: 22)
                .overlay(RoundedRectangle(cornerRadius: 0).stroke(palette.tertiary, lineWidth: 3))
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Read \(header.title)")
        .accessibilityIdentifier("quilt-middle")
    }

    @ViewBuilder private func cell(_ c: QuiltCell) -> some View {
        let p = c.plate
        let tile = PlateTile(plate: PlateItem(id: p.key, title: p.title, picture: p.picture, whole: p.picture?.hasSuffix(".w.jpg") == true, sub: p.sub), showTitle: true, titleSize: 13)
            .overlay(alignment: .topLeading) {
                if c.tab {
                    Text(groupName(groups[Int(c.group)])).font(.caption2.weight(.semibold)).foregroundStyle(.white)
                        .padding(.horizontal, 6).padding(.vertical, 3).background(palette.tertiary)
                }
            }
        if p.isArticle {
            Button { go(to: p) } label: { tile }.buttonStyle(.plain)
        } else if let room = p.room {
            NavigationLink(value: Route.room(subject: room.subject, facet: room.id)) { tile }.buttonStyle(.plain)
        } else {
            tile
        }
    }

    /// All of it, by group: every plate, so nothing is out of reach of the quilt's twelve places.
    private var index: some View {
        VStack(alignment: .leading, spacing: 10) {
            Eyebrow(text: "All of it, by group").padding(.top, 16)
            ForEach(Array(groups.enumerated()), id: \.offset) { _, g in
                VStack(alignment: .leading, spacing: 4) {
                    Text("\(groupName(g)) · \(g.plates.count)").font(Spectral.medium(18)).foregroundStyle(palette.onBackground)
                    Text(g.plates.map(\.title).joined(separator: ", ")).font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(3)
                }
            }
        }
    }

    private func groupName(_ g: QuiltGroup) -> String {
        if let n = g.name { return n }
        switch g.kind {
        case .kinds: return "Kinds"
        case .people: return "People"
        case .story: return "Story"
        case .close: return "Close kin"
        case .leads: return "Leads to it"
        case .lives: return "Lives of its time"
        case .events: return "Events of its time"
        case .rooms: return "Its rooms"
        case .books: return "In your books"
        default: return "Chapter"
        }
    }

    private func go(to p: QuiltPlate) {
        guard let pack = p.packId, let path = p.path else { return }
        thread.append((here.packId, here.path, topic?.header?.title ?? ""))
        topic = nil
        groups = []
        centre = (pack, path)
    }

    private func go(back i: Int) {
        let t = thread[i]
        thread.removeSubrange(i...)
        topic = nil
        groups = []
        centre = (t.packId, t.path)
    }
}
