import GrangerCoreKit
import SwiftUI

/// The shared TimelineModel (Plan H5b) as SwiftUI sees it: its state, mirrored as it changes, and its actions.
@Observable
final class TimelineHolder {
    private(set) var ui: TimelineUi?
    private var owned: Owned<TimelineModel>?
    private var watching: Task<Void, Never>?

    var model: TimelineModel? { owned?.model }

    @MainActor
    func start(core: Core, packId: String? = nil, path: String? = nil) {
        guard owned == nil else { return }
        let o = core.library.timeline(packId: packId, path: path)
        owned = o
        o.model.ensureStarted()
        watching = Task { @MainActor [weak self] in
            for await ui in o.model.ui { self?.ui = ui }
        }
    }

    deinit {
        watching?.cancel()
        owned?.close()
    }
}

/// A colour per lane, deeper on Field and lighter on Night (Android's laneColour, spec §7).
func laneColor(_ lane: Lane, night: Bool) -> Color {
    let (day, dark): (UInt32, UInt32) = switch lane {
    case .philosophyReligion: (0x7B4FA0, 0xC7A6E6)
    case .civilisations: (0xA0522D, 0xE3A57A)
    case .languages: (0x2E7D6B, 0x7FD1BC)
    case .filmMusic: (0xB03A6A, 0xF09BBE)
    case .artLiterature: (0x8A6D1E, 0xE2C46E)
    case .science: (0x2F5FA8, 0x8DB4F0)
    case .wars: (0x9E2B25, 0xF08A80)
    case .eventsPolitics: (0x4F6A2A, 0xB2D27A)
    default: (0x6B5B4B, 0xCDBBA6)
    }
    let v = night ? dark : day
    return Color(red: Double((v >> 16) & 0xFF) / 255, green: Double((v >> 8) & 0xFF) / 255, blue: Double(v & 0xFF) / 255)
}

/// A lane's name (Android's home_lane_*).
func laneName(_ lane: Lane) -> String {
    switch lane {
    case .philosophyReligion: "Philosophy & religion"
    case .civilisations: "Civilisations & empires"
    case .languages: "Languages"
    case .filmMusic: "Film & music"
    case .artLiterature: "Art & literature"
    case .science: "Science & invention"
    case .wars: "Wars"
    case .eventsPolitics: "Events & politics"
    default: "People"
    }
}

/// The river (Android's TimelineSection canvas): lifetimes as thin bars, spans as bars, events as dots, the better known
/// on top; a low density histogram along each row; the lens at the selected mark; the ruler. On Home a drag scrubs the
/// lens; full screen a drag pans and a pinch zooms. A tap picks the mark under the finger, else the year.
struct RiverView: View {
    let ui: TimelineUi
    let model: TimelineModel
    var full = false
    var height: CGFloat = 220

    @Environment(\.palette) private var palette
    @Environment(\.colorScheme) private var scheme
    @State private var scrubX: CGFloat?
    @State private var lastDrag: CGFloat = 0
    @State private var lastScale: CGFloat = 1

    private static let ruler: CGFloat = 22

    private var rows: [Lane?] {
        let on = Set(ui.on.map { $0 })
        return (1...3).contains(on.count) ? Lane.allCases.filter { on.contains($0) } : [nil]
    }

    var body: some View {
        GeometryReader { g in
            let w = g.size.width
            let rowH = (height - Self.ruler) / CGFloat(rows.count)
            let tracks = max(3, min(16, Int(rowH / 18)))
            Canvas { ctx, size in draw(ctx: ctx, w: size.width, rowH: rowH, tracks: tracks) }
                .contentShape(Rectangle())
                .gesture(drag(w: w))
                .simultaneousGesture(full ? zoom(w: w) : nil)
                .onTapGesture(coordinateSpace: .local) { p in
                    if let m = hit(at: p, w: w, rowH: rowH, tracks: tracks) { model.select(mark: m) }
                    else { model.lensAt(year: ui.window.year(x: Float(p.x), width: Float(w))) }
                }
                .accessibilityElement()
                .accessibilityLabel(rangeLabel)
                .accessibilityIdentifier("timeline-river")
        }
        .frame(height: height)
    }

    private var rangeLabel: String {
        let ys = ui.window.years()
        return YearWords.english.range(from: ys.first!.int32Value, to: ys.second!.int32Value)
    }

    private func drag(w: CGFloat) -> some Gesture {
        DragGesture(minimumDistance: 8)
            .onChanged { v in
                if full {
                    let dx = v.translation.width - lastDrag
                    lastDrag = v.translation.width
                    model.show(window: ui.window.pan(dx: Float(dx), width: Float(w), bounds: ui.bounds))
                } else {
                    scrubX = min(max(0, v.location.x), w)
                }
            }
            .onEnded { _ in
                if !full, let x = scrubX { model.lensAt(year: ui.window.year(x: Float(x), width: Float(w))) }
                scrubX = nil
                lastDrag = 0
            }
    }

    private func zoom(w: CGFloat) -> some Gesture {
        MagnifyGesture()
            .onChanged { v in
                let factor = v.magnification / lastScale
                lastScale = v.magnification
                model.show(window: ui.window.zoom(factor: Float(factor), focusX: Float(v.startLocation.x), width: Float(w), bounds: ui.bounds))
            }
            .onEnded { _ in lastScale = 1 }
    }

    private func trackY(_ qid: Int64, rowH: CGFloat, tracks: Int) -> CGFloat {
        rowH * (0.08 + 0.84 * (CGFloat(Int(qid % Int64(tracks))) + 0.5) / CGFloat(tracks))
    }

    private func rowOf(_ m: TimeMark) -> Int? {
        rows.firstIndex { lane in lane == nil || m.lanes.contains(lane!) }
    }

    private var visible: [TimeMark] {
        ui.matches.isEmpty ? ui.marks : Array((ui.marks + ui.matches).reduce(into: [Int64: TimeMark]()) { $0[$1.qid] = $1 }.values)
    }

    private func draw(ctx: GraphicsContext, w: CGFloat, rowH: CGFloat, tracks: Int) {
        let night = scheme == .dark
        let bottom = rowH * CGFloat(rows.count)
        // Density along each row's foot, on a log scale so a quiet century still shows.
        let bins = 64
        let barMax = min(rowH * 0.12, 14)
        for (r, lane) in rows.enumerated() {
            let lanes: Set<Lane> = lane.map { [$0] } ?? Set(ui.on.map { $0 })
            let counts = (0..<bins).map { i -> Int32 in
                let a = Int64(ceil(ui.window.year(x: Float(w) * Float(i) / Float(bins), width: Float(w))))
                let b = Int64(ceil(ui.window.year(x: Float(w) * Float(i + 1) / Float(bins), width: Float(w)))) - 1
                return ui.density.count(from: a, to: b, lanes: lanes)
            }
            let peak = max(1, log(1 + Double(counts.max() ?? 0)))
            let foot = rowH * CGFloat(r + 1)
            for (i, n) in counts.enumerated() where n > 0 {
                let h = barMax * CGFloat(0.15 + 0.85 * log(1 + Double(n)) / peak)
                ctx.fill(Path(CGRect(x: w * CGFloat(i) / CGFloat(bins), y: foot - h, width: w / CGFloat(bins) - 1, height: h)),
                         with: .color(palette.onSurfaceVariant.opacity(0.16)))
            }
        }
        // Marks, the better known last.
        let searching = !ui.query.isEmpty
        let matchIds = Set(ui.matches.map(\.qid))
        for m in visible.sorted(by: { (searching && matchIds.contains($0.qid) ? Int32.max : $0.rank) < (searching && matchIds.contains($1.qid) ? Int32.max : $1.rank) }) {
            guard let row = rowOf(m) else { continue }
            let lane = rows[row] ?? Lane.allCases.first { m.lanes.contains($0) }
            let dim = searching && !matchIds.contains(m.qid)
            let c = (lane.map { laneColor($0, night: night) } ?? palette.onSurfaceVariant).opacity(dim ? 0.18 : 1)
            let y = rowH * CGFloat(row) + trackY(m.qid, rowH: rowH, tracks: tracks)
            let x0 = CGFloat(ui.window.x(year: Double(m.y0), width: Float(w)))
            if m.kind == .point {
                let r: CGFloat = searching && !dim ? 4.5 : 3
                ctx.fill(Path(ellipseIn: CGRect(x: x0 - r, y: y - r, width: 2 * r, height: 2 * r)), with: .color(c))
            } else {
                let x1 = max(x0 + 2, CGFloat(ui.window.x(year: Double(m.end(now: ui.now)), width: Float(w))))
                let h: CGFloat = m.kind == .span ? 5 : 2
                ctx.fill(Path(roundedRect: CGRect(x: x0, y: y - h / 2, width: x1 - x0, height: h), cornerRadius: h / 2), with: .color(c.opacity(dim ? 0.18 : 0.85)))
            }
        }
        // The lens.
        if let lensX = scrubX ?? ui.selected.map({ CGFloat(ui.window.x(year: Double($0.y0), width: Float(w))) }) {
            var line = Path(); line.move(to: CGPoint(x: lensX, y: 0)); line.addLine(to: CGPoint(x: lensX, y: bottom))
            ctx.stroke(line, with: .color(palette.primary), lineWidth: 2)
            if scrubX == nil, let s = ui.selected {
                let row = max(0, rows.firstIndex { $0 == nil || s.lanes.contains($0!) } ?? 0)
                let y = rowH * CGFloat(row) + trackY(s.qid, rowH: rowH, tracks: tracks)
                ctx.stroke(Path(ellipseIn: CGRect(x: lensX - 6, y: y - 6, width: 12, height: 12)), with: .color(palette.primary), lineWidth: 2)
            }
        }
        // Row lines and the ruler.
        for r in 1..<max(rows.count, 1) where rows.count > 1 {
            var p = Path(); p.move(to: CGPoint(x: 0, y: rowH * CGFloat(r))); p.addLine(to: CGPoint(x: w, y: rowH * CGFloat(r)))
            ctx.stroke(p, with: .color(palette.outlineVariant), lineWidth: 1)
        }
        var base = Path(); base.move(to: CGPoint(x: 0, y: bottom)); base.addLine(to: CGPoint(x: w, y: bottom))
        ctx.stroke(base, with: .color(palette.outline), lineWidth: 1)
        var latestLeft = CGFloat.infinity
        if ui.window.end >= ui.bounds.end - 1e-6 {
            let t = ctx.resolve(Text(YearWords.english.year(y: Int64(ui.latest))).font(.system(size: 10)).foregroundStyle(palette.primary))
            let s = t.measure(in: CGSize(width: 200, height: 20))
            latestLeft = w - s.width
            ctx.draw(t, at: CGPoint(x: latestLeft, y: bottom + 5), anchor: .topLeading)
        }
        var lastRight: CGFloat = -.infinity
        for y in TimeScale.shared.ticks(window: ui.window, width: Float(w), minGap: 56) {
            let x = CGFloat(ui.window.x(year: Double(y.int64Value), width: Float(w)))
            var tick = Path(); tick.move(to: CGPoint(x: x, y: bottom)); tick.addLine(to: CGPoint(x: x, y: bottom + 4))
            ctx.stroke(tick, with: .color(palette.outline), lineWidth: 1)
            let t = ctx.resolve(Text(YearWords.english.tick(y: y.int64Value)).font(.system(size: 10)).foregroundStyle(palette.onSurfaceVariant))
            let s = t.measure(in: CGSize(width: 200, height: 20))
            let left = min(max(0, x - s.width / 2), w - s.width)
            if left > lastRight + 4 && left + s.width < latestLeft - 4 {
                ctx.draw(t, at: CGPoint(x: left, y: bottom + 5), anchor: .topLeading)
                lastRight = left + s.width
            }
        }
    }

    /// The mark nearest the finger within 24 pt, in its own row and track, as drawn.
    private func hit(at p: CGPoint, w: CGFloat, rowH: CGFloat, tracks: Int) -> TimeMark? {
        var best: TimeMark?
        var bestD: CGFloat = 24
        for m in visible {
            guard let row = rowOf(m) else { continue }
            let y = rowH * CGFloat(row) + trackY(m.qid, rowH: rowH, tracks: tracks)
            let x0 = CGFloat(ui.window.x(year: Double(m.y0), width: Float(w)))
            let x1 = m.kind == .point ? x0 : max(x0, CGFloat(ui.window.x(year: Double(m.end(now: ui.now)), width: Float(w))))
            let dx = p.x < x0 ? x0 - p.x : (p.x > x1 ? p.x - x1 : 0)
            let d = hypot(dx, p.y - y)
            if d < bestD { bestD = d; best = m }
        }
        return best
    }
}

/// The selected mark under the river: its picture, years and title (opens its topic page), and the step arrows.
struct TimelineCaption: View {
    let ui: TimelineUi
    let model: TimelineModel
    @Environment(\.palette) private var palette

    var body: some View {
        if let s = ui.selected {
            HStack(spacing: 10) {
                Button { model.step(by: -1) } label: { Image(systemName: "chevron.left") }
                    .accessibilityLabel("Earlier")
                NavigationLink(value: Route.article(packId: s.packId, path: s.path)) {
                    HStack(spacing: 12) {
                        Color.clear.frame(width: 64, height: 64)
                            .overlay {
                                if let p = ui.picture, let image = UIImage(contentsOfFile: p) { Image(uiImage: image).resizable().scaledToFill() }
                                else { Text(String(s.title.prefix(1))).font(Spectral.medium(28)).foregroundStyle(palette.onSurfaceVariant) }
                            }
                            .background(palette.surfaceContainerHighest)
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                        VStack(alignment: .leading, spacing: 2) {
                            Text(YearWords.english.of(m: s)).font(Spectral.medium(16)).foregroundStyle(palette.primary)
                            Text(s.title).font(Spectral.medium(22)).foregroundStyle(palette.onSurface).lineLimit(2)
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(10)
                    .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 14))
                }
                .buttonStyle(.plain)
                .accessibilityIdentifier("timeline-caption")
                Button { model.step(by: 1) } label: { Image(systemName: "chevron.right") }
                    .accessibilityLabel("Later")
            }
            .font(.title3)
            .foregroundStyle(palette.primary)
        }
    }
}

/// The lanes with marks, as a legend (Home) or as chips that switch lanes on and off (full screen).
struct LaneChips: View {
    let ui: TimelineUi
    let model: TimelineModel
    var toggles = false
    @Environment(\.palette) private var palette
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                if toggles {
                    chip("All", on: ui.on.isEmpty, color: palette.primary) { model.all() }
                }
                ForEach(ui.lanes, id: \.self) { lane in
                    let c = laneColor(lane, night: scheme == .dark)
                    if toggles {
                        chip(laneName(lane), on: ui.on.contains(lane), color: c) { model.toggle(lane: lane) }
                    } else {
                        HStack(spacing: 6) {
                            Circle().fill(c).frame(width: 10, height: 10)
                            Text(laneName(lane)).font(Spectral.regular(15)).foregroundStyle(palette.onSurface)
                        }
                    }
                }
            }
        }
    }

    private func chip(_ text: String, on: Bool, color: Color, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 6) {
                Circle().fill(color).frame(width: 9, height: 9)
                Text(text).font(Spectral.regular(15))
            }
            .padding(.horizontal, 12).padding(.vertical, 7)
            .background(on ? palette.secondaryContainer : palette.surfaceContainer, in: Capsule())
            .foregroundStyle(on ? palette.onSecondaryContainer : palette.onSurface)
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? .isSelected : [])
    }
}

/// Home's timeline card: the river, its legend and the caption, with Full timeline.
struct TimelineCard: View {
    let holder: TimelineHolder
    @Environment(\.palette) private var palette

    var body: some View {
        if let ui = holder.ui, let model = holder.model, ui.available {
            VStack(alignment: .leading, spacing: 12) {
                HStack {
                    Text("Timeline").font(Spectral.medium(24, relativeTo: .title2)).foregroundStyle(palette.tertiary)
                        .accessibilityAddTraits(.isHeader)
                    Spacer()
                    NavigationLink(value: Route.timeline(packId: ui.selected?.packId, path: ui.selected?.path)) {
                        Label("Full timeline", systemImage: "chevron.right").labelStyle(TrailingIcon())
                            .font(Spectral.medium(18)).foregroundStyle(palette.tertiary)
                    }
                    .accessibilityIdentifier("full-timeline")
                }
                if ui.loading && ui.marks.isEmpty {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 200)
                } else {
                    RiverView(ui: ui, model: model)
                    LaneChips(ui: ui, model: model)
                    TimelineCaption(ui: ui, model: model)
                }
            }
            .padding(16)
            .background(palette.surfaceContainerLow, in: RoundedRectangle(cornerRadius: 18))
        }
    }
}

struct TrailingIcon: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) { configuration.title; configuration.icon }
    }
}

/// The full timeline (Plan H5b): search, lanes, a taller river that pans and pinches, the caption and what's nearby.
struct TimelineScreen: View {
    let packId: String?
    let path: String?
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var holder = TimelineHolder()
    @State private var query = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                if let ui = holder.ui, let model = holder.model {
                    LaneChips(ui: ui, model: model, toggles: true)
                    RiverView(ui: ui, model: model, full: true, height: 380)
                    TimelineCaption(ui: ui, model: model)
                    if !ui.nearby.isEmpty {
                        Text("Nearby").font(Spectral.medium(22)).foregroundStyle(palette.onBackground).accessibilityAddTraits(.isHeader)
                        ForEach(ui.nearby, id: \.qid) { m in
                            NavigationLink(value: Route.article(packId: m.packId, path: m.path)) {
                                HStack {
                                    Text(YearWords.english.of(m: m)).font(.callout).foregroundStyle(palette.primary).frame(width: 120, alignment: .leading)
                                    Text(m.title).font(Spectral.regular(18)).foregroundStyle(palette.onBackground)
                                    Spacer()
                                }
                                .padding(.vertical, 4)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 300)
                }
            }
            .padding(.horizontal, 16)
            .padding(.bottom, 32)
        }
        .scrollDisabled(false)
        .background(palette.background)
        .navigationTitle("Timeline")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "Roman, jazz, Newton…")
        .onChange(of: query) { holder.model?.search(text: query) }
        .task { if let core { holder.start(core: core, packId: packId, path: path) } }
    }
}
