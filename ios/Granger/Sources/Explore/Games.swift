import GrangerCoreKit
import SwiftUI

/// The shared GameModel (Plan H5c / HY) as SwiftUI sees it.
@Observable
final class GameHolder {
    private(set) var ui: GameUi?
    private var owned: Owned<GameModel>?
    private var watching: Task<Void, Never>?
    var model: GameModel? { owned?.model }

    @MainActor
    func start(_ make: () -> Owned<GameModel>?) {
        guard owned == nil, let o = make() else { return }
        owned = o
        watching = Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

extension GameKind {
    var title: String {
        switch self {
        case .order: "Which came first?"
        case .guess: "Guess the year"
        case .beforeAfter: "Before or after?"
        case .overlap: "Alive at the same time?"
        case .oddOneOut: "Odd one out"
        default: "Which century?"
        }
    }
    var about: String {
        switch self {
        case .order: "Put two or three in order, earliest first."
        case .guess: "Slide to the year it happened."
        case .beforeAfter: "Place one against another's year."
        case .overlap: "Could these two people have met?"
        case .oddOneOut: "Three are from about the same time. Find the one that isn't."
        default: "Pick the century from four."
        }
    }
}

/// The Games screen (Plan HY): the six games with their best runs; each opens a run of ten. [subject] makes it a
/// subject's quiz (its own dated articles, its own bests).
struct GamesScreen: View {
    var subject: String? = nil
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var holder = GameHolder()

    var body: some View {
        Group {
            if let ui = holder.ui, let model = holder.model {
                if let run = ui.run {
                    GameRunView(ui: ui, run: run, model: model)
                } else {
                    List {
                        ForEach(GameKind.allCases, id: \.self) { kind in
                            Button { model.startRun(kind: kind, carry: nil, pictures: [:]) } label: {
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(kind.title).font(Spectral.medium(21)).foregroundStyle(palette.onSurface)
                                    Text(kind.about).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                                    if let best = ui.best[kind]?.int32Value, best > 0 {
                                        Text("Best: \(best) of \(GameModel.companion.RUN)").font(.caption).foregroundStyle(palette.tertiary)
                                    }
                                }
                                .padding(.vertical, 6)
                            }
                            .accessibilityIdentifier("game-\(kind.name)")
                        }
                    }
                    .scrollContentBackground(.hidden)
                }
            } else {
                ProgressView()
            }
        }
        .background(palette.background)
        .navigationTitle(subject.flatMap { Subject.companion.of(id: $0) }.map { "\(Words.subject($0)) quiz" } ?? "Games")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let core else { return }
            holder.start { subject.map { core.library.subjectGames(subjectId: $0) } ?? core.library.games(mixed: false, start: nil) }
        }
    }
}

/// A run of ten rounds of one game, then the score.
private struct GameRunView: View {
    let ui: GameUi
    let run: GameRun
    let model: GameModel
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if run.finished {
                    Text(run.newBest ? "A new best!" : run.kind.title).font(Spectral.medium(30)).foregroundStyle(palette.onSurface)
                    Text("\(run.right) of \(run.size)").font(Spectral.medium(56)).foregroundStyle(palette.primary)
                        .accessibilityIdentifier("game-score")
                    Text("Best: \(run.best) of \(run.size)").foregroundStyle(palette.onSurfaceVariant)
                    HStack {
                        Button("Play again") { model.startRun(kind: run.kind, carry: nil, pictures: [:]) }.buttonStyle(.borderedProminent)
                        Button("All games") { model.endRun() }.buttonStyle(.bordered)
                    }
                } else {
                    Text("Round \(run.number) of \(run.size)").font(.callout).foregroundStyle(palette.tertiary)
                    RoundView(ui: ui, model: model)
                }
            }
            .padding(16)
        }
        .toolbar { ToolbarItem(placement: .topBarTrailing) { Button("All games") { model.endRun() } } }
    }
}

/// One round of any of the six games: the question, the choices, the reveal and Next.
struct RoundView: View {
    let ui: GameUi
    let model: GameModel
    @Environment(\.palette) private var palette

    var body: some View {
        if let round = ui.round {
            VStack(alignment: .leading, spacing: 14) {
                Text(round.kind.title).font(Spectral.medium(26)).foregroundStyle(palette.onSurface).accessibilityAddTraits(.isHeader)
                switch onEnum(of: round) {
                case let .order(r): order(r)
                case let .guess(r): guess(r)
                case let .beforeAfter(r): beforeAfter(r)
                case let .overlap(r): overlap(r)
                case let .oddOneOut(r): odd(r)
                case let .century(r): century(r)
                }
                if round.revealed {
                    Button(ui.run?.last == true ? "See your score" : "Next round") { model.next() }
                        .buttonStyle(.borderedProminent)
                        .accessibilityIdentifier("game-next")
                }
            }
        } else if ui.loading {
            Text("Dealing a round…").foregroundStyle(palette.onSurfaceVariant)
        } else {
            Text("Not enough dated articles here for a round yet.").foregroundStyle(palette.onSurfaceVariant)
        }
    }

    private func years(_ m: TimeMark) -> String { YearWords.english.of(m: m) }

    private func card(_ m: TimeMark, reveal: Bool, note: String? = nil, tint: Color? = nil, action: (() -> Void)? = nil) -> some View {
        Button { action?() } label: {
            HStack(spacing: 12) {
                Avatar(path: ui.pictures[KotlinLong(value: m.qid)], size: 56)
                VStack(alignment: .leading, spacing: 2) {
                    Text(m.title).font(Spectral.medium(19)).foregroundStyle(palette.onSurface).multilineTextAlignment(.leading)
                    if reveal { Text(years(m)).font(.callout).foregroundStyle(palette.primary) }
                    if let note { Text(note).font(.caption).foregroundStyle(palette.onSurfaceVariant) }
                }
                Spacer()
            }
            .padding(12)
            .background(tint ?? palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 12))
        }
        .buttonStyle(.plain)
        .allowsHitTesting(action != nil)
    }

    private func verdict(_ right: Bool) -> some View {
        Text(right ? "Right!" : "Not quite").font(Spectral.medium(22)).foregroundStyle(right ? palette.primary : palette.error)
    }

    @ViewBuilder private func order(_ r: RoundOrder) -> some View {
        Text(r.marks.count == 2 ? "Tap the one that came first." : "Tap them in order, earliest first.").foregroundStyle(palette.onSurfaceVariant)
        ForEach(r.revealed ? r.answer : r.marks, id: \.qid) { m in
            let n = r.picked.firstIndex(of: KotlinLong(value: m.qid)).map { $0 + 1 }
            card(m, reveal: r.revealed, note: n.map { "Chosen as number \($0)" }, action: r.revealed ? nil : { model.pick(qid: m.qid) })
        }
        if r.revealed { verdict(r.right) }
    }

    @ViewBuilder private func guess(_ r: RoundGuess) -> some View {
        Text(r.mark.kind == .life ? "When was this person born?" : r.mark.kind == .span ? "When did this begin?" : "When did this happen?").foregroundStyle(palette.onSurfaceVariant)
        card(r.mark, reveal: r.revealed)
        if r.revealed {
            let c = GameModelKt.closeness(off: r.off, answer: r.mark.year, now: ui.now)
            Text(c == .spotOn ? "Spot on!" : c == .veryClose ? "Very close" : c == .close ? "Close" : "Further off").font(Spectral.medium(22)).foregroundStyle(palette.primary)
            Text("It was \(years(r.mark))").foregroundStyle(palette.onSurface)
        } else {
            Text("Your guess, \(YearWords.english.year(y: Int64(r.guess)))").font(Spectral.medium(22)).foregroundStyle(palette.primary)
            HStack {
                Button("−10") { model.nudge(by: -10) }.accessibilityLabel("Ten years earlier")
                Button("−1") { model.nudge(by: -1) }.accessibilityLabel("One year earlier")
                Spacer()
                Button("+1") { model.nudge(by: 1) }.accessibilityLabel("One year later")
                Button("+10") { model.nudge(by: 10) }.accessibilityLabel("Ten years later")
            }
            .buttonStyle(.bordered)
            Slider(value: Binding(get: { Double(r.guess) }, set: { model.guess(year: Int32($0)) }),
                   in: Double(min(r.guess, -3000))...Double(ui.latest))
                .accessibilityLabel("Your guess")
            Button("Guess") { model.submit() }.buttonStyle(.borderedProminent).accessibilityIdentifier("game-guess")
        }
    }

    @ViewBuilder private func beforeAfter(_ r: RoundBeforeAfter) -> some View {
        Text("Did this come before or after \(r.anchor.title) (\(years(r.anchor)))?").foregroundStyle(palette.onSurfaceVariant)
        card(r.mark, reveal: r.revealed)
        if r.revealed { verdict(r.right) } else {
            HStack { Button("Before") { model.say(yes: true) }; Button("After") { model.say(yes: false) } }.buttonStyle(.borderedProminent)
        }
    }

    @ViewBuilder private func overlap(_ r: RoundOverlap) -> some View {
        Text("Were these two alive at the same time?").foregroundStyle(palette.onSurfaceVariant)
        card(r.a, reveal: r.revealed)
        card(r.b, reveal: r.revealed)
        if r.revealed { verdict(r.right) } else {
            HStack { Button("Yes") { model.say(yes: true) }; Button("No") { model.say(yes: false) } }.buttonStyle(.borderedProminent)
        }
    }

    @ViewBuilder private func odd(_ r: RoundOddOneOut) -> some View {
        Text("Three of these are from about the same time. Which one isn't?").foregroundStyle(palette.onSurfaceVariant)
        ForEach(r.marks, id: \.qid) { m in
            card(m, reveal: r.revealed, note: r.revealed && m.qid == r.odd ? "The odd one out" : nil,
                 tint: r.revealed && m.qid == r.odd ? palette.secondaryContainer : nil,
                 action: r.revealed ? nil : { model.pick(qid: m.qid) })
        }
        if r.revealed { verdict(r.right) }
    }

    @ViewBuilder private func century(_ r: RoundCentury) -> some View {
        Text(r.mark.kind == .life ? "Which century was this person born in?" : r.mark.kind == .span ? "Which century did this begin in?" : "Which century did this happen in?").foregroundStyle(palette.onSurfaceVariant)
        card(r.mark, reveal: r.revealed)
        ForEach(r.choices.map(\.int32Value), id: \.self) { c in
            Button { model.pickCentury(century: c) } label: {
                Text(Self.centuryName(c)).frame(maxWidth: .infinity).padding(.vertical, 6)
            }
            .buttonStyle(.bordered)
            .tint(r.revealed && c == r.answer ? palette.primary : (r.revealed && r.picked?.int32Value == c ? palette.error : nil))
            .disabled(r.revealed)
        }
        if r.revealed { verdict(r.right) }
    }

    static func centuryName(_ c: Int32) -> String {
        let n = c >= 0 ? Int(c) + 1 : Int(-c)
        let suffix = (11...13).contains(n % 100) ? "th" : [1: "st", 2: "nd", 3: "rd"][n % 10] ?? "th"
        return c >= 0 ? "\(n)\(suffix) century" : "\(n)\(suffix) century BC"
    }
}
