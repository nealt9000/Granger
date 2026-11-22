import GrangerCoreKit
import SwiftUI

/// The shared OnboardingViewModel (Android's first run, Plans H3/OB, I4 Task 8) as SwiftUI sees it.
@Observable
final class OnboardingHolder {
    private(set) var ui: OnboardingUi?
    private(set) var finished = false
    private(set) var readingNow = false
    private var owned: OwnedViewModel<OnboardingViewModel>?
    private var watching: [Task<Void, Never>] = []
    var model: OnboardingViewModel? { owned?.model }

    @MainActor
    func start(_ make: () -> OwnedViewModel<OnboardingViewModel>) {
        guard owned == nil else { return }
        let o = make()
        owned = o
        watching.append(Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } })
        watching.append(Task { @MainActor [weak self] in for await f in o.model.finished { if f.boolValue { self?.finished = true } } })
        watching.append(Task { @MainActor [weak self] in for await r in o.model.readingNow { if r.boolValue { self?.readingNow = true } } })
    }

    deinit { watching.forEach { $0.cancel() }; owned?.close() }
}

/// The first run (Android's OnboardingScreen): Welcome (start reading now, or outfit for offline), Pick a start with the
/// region and quick wins (the Granger voice among them since Plan I5 Task 8: the shared Read aloud line), then Review and
/// Start.
struct OnboardingScreen: View {
    var startAt: Step = .welcome
    let onDone: (_ readNow: Bool) -> Void
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(AppSettings.self) private var settings
    @State private var holder = OnboardingHolder()

    var body: some View {
        NavigationStack {
            Group {
                if let ui = holder.ui, let model = holder.model {
                    switch ui.step {
                    case .welcome: welcome(ui, model)
                    case .outfit: outfit(ui, model)
                    default: review(ui, model)
                    }
                } else {
                    ProgressView()
                }
            }
            .background(palette.background)
            .toolbar {
                if holder.ui?.step != .welcome {
                    ToolbarItem(placement: .topBarLeading) { Button("Back") { holder.model?.back() } }
                }
                ToolbarItem(placement: .topBarTrailing) { Button("Skip") { holder.model?.skip() }.accessibilityIdentifier("onboarding-skip") }
            }
        }
        .task {
            guard let core else { return }
            let s = settings
            holder.start {
                core.library.onboarding(
                    startAt: startAt,
                    phoneLanguages: Locale.preferredLanguages.compactMap { Locale(identifier: $0).language.languageCode?.identifier },
                    countryCode: Locale.current.region?.identifier ?? "",
                    // The knowledge languages (Plan I7 call 2): the shared model keeps them; Settings follows ([followKnowledge]).
                    setKnowledge: { langs in core.library.knowledgeLanguages.set(codes: langs) },
                    onPrivacySeen: { Task { @MainActor in s.privacySeen = true } }
                )
            }
            holder.model?.refreshVolumes()
        }
        .onChange(of: holder.finished) { _, f in if f { onDone(false) } }
        .onChange(of: holder.readingNow) { _, r in if r { onDone(true) } }
    }

    private func heading(_ s: String) -> some View { Text(s).font(Spectral.medium(16)).foregroundStyle(palette.tertiary) }

    private func welcome(_ ui: OnboardingUi, _ model: OnboardingViewModel) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Text("GRANGER").font(Spectral.semibold(44)).tracking(6).foregroundStyle(palette.onSurface)
                Text("Your door to knowledge. Always open.").font(Spectral.italic(20)).foregroundStyle(palette.onSurfaceVariant)
                Text(ui.intro.words).font(Spectral.regular(19)).foregroundStyle(palette.onSurface)
                Text("Reading online asks Wikimedia for the articles you open. Nothing about you goes with it, and nothing goes out with Local only on.")
                    .font(.callout).foregroundStyle(palette.onSurfaceVariant)
                if let also = ui.alsoLanguage {
                    // A phone in two languages the catalog has (Plan L3): "Also Spanish?", named in the app's language.
                    Toggle(isOn: Binding(get: { ui.alsoOn }, set: { _ in model.toggleAlso() })) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Also \(languageName(also))?")
                            Text("Adds its encyclopedia to your start, and its packs to Get more.").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    .accessibilityIdentifier("onboarding-also")
                }
                // Which language iOS runs Granger in, with the way to change it (call 2).
                AppLanguageRow()
                Button { model.startReadingNow() } label: {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Start reading now").font(Spectral.medium(21))
                        Text("Read Wikipedia and the classics online, with nothing to download first. Keep what you like for offline as you go.")
                            .font(.callout).multilineTextAlignment(.leading)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading).padding(16)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("onboarding-read-now")
                Button { model.outfit() } label: {
                    Text("Outfit for offline").font(Spectral.medium(19)).frame(maxWidth: .infinity, alignment: .leading).padding(12)
                }
                .buttonStyle(.bordered)
                .accessibilityIdentifier("onboarding-outfit")
            }
            .padding(20)
        }
    }

    /// A language named in the app's language: "Spanish" here, "inglés" in Spanish (Android's onboarding_also_language).
    private func languageName(_ code: String) -> String { Locale(identifier: AppLanguage.code).localizedString(forLanguageCode: code) ?? code }

    private func outfit(_ ui: OnboardingUi, _ model: OnboardingViewModel) -> some View {
        List {
            if ui.noCatalog {
                Section {
                    if ui.catalogFailed {
                        Text("Couldn't get the list of packs.").foregroundStyle(palette.error)
                        Button("Try again") { model.refreshCatalog() }
                    } else {
                        HStack { ProgressView(); Text("Getting the list of packs…").foregroundStyle(palette.onSurfaceVariant) }
                    }
                }
            }
            Section(header: heading(L("Pick a start"))) {
                ForEach(ui.starts, id: \.id) { start in
                    Button { model.chooseStart(id: start.id) } label: {
                        HStack(alignment: .top, spacing: 10) {
                            Image(systemName: ui.startId == start.id ? "largecircle.fill.circle" : "circle").foregroundStyle(palette.primary)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("\(start.title) · \(QueueWords.bytes(start.bytes(unticked: ui.unticked)))").font(Spectral.regular(19)).foregroundStyle(palette.onSurface)
                                Text(start.blurb.words).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                            }
                        }
                    }
                    .accessibilityIdentifier("start-\(start.id)")
                }
                if let start = ui.start {
                    ForEach(start.lines, id: \.id) { line in lineToggle(line, on: !ui.unticked.contains(line.id)) { model.toggleLine(lineId: line.id) } }
                }
                if let second = ui.secondLine { lineToggle(second, on: ui.secondChosen) { model.toggleSecond() } }
            }
            if !ui.regions.countries.isEmpty || ui.region != nil {
                Section(header: heading(L("A map")), footer: Text("A map of where you are, for finding your way with no signal.")) {
                    if let r = ui.region {
                        HStack {
                            Text("\(r.place ?? r.pack.title) · \(QueueWords.bytes(r.pack.sizeBytes))").foregroundStyle(palette.onSurface)
                            Spacer()
                            Button("None") { model.chooseRegion(packId: nil) }
                        }
                    } else if let s = ui.suggestion {
                        Button("Add \(s.place ?? s.pack.title) · \(QueueWords.bytes(s.pack.sizeBytes))") { model.chooseRegion(packId: s.pack.id) }
                    }
                }
            }
            if !ui.additions.isEmpty {
                Section(header: heading(L("Quick wins")), footer: Text("Add packs for what you'll use Granger for. Outfitting can change this later.")) {
                    ForEach(ui.additions, id: \.useCase.id) { a in
                        Toggle(isOn: Binding(get: { ui.useCaseIds.contains(a.useCase.id) }, set: { _ in model.toggleUseCase(id: a.useCase.id) })) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(a.title) · \(QueueWords.bytes(a.bytes))")
                                Text(a.blurb).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            Section {
                Button("Review") { model.next() }.buttonStyle(.borderedProminent).accessibilityIdentifier("onboarding-next")
                Button("Not now") { model.notNow() }
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Outfit for offline")
        .navigationBarTitleDisplayMode(.inline)
    }

    private func lineToggle(_ line: Line, on: Bool, toggle: @escaping () -> Void) -> some View {
        Toggle(isOn: Binding(get: { on }, set: { _ in toggle() })) {
            Text(line.installed ? L("\(line.title.words) · on this iPhone") : "\(line.title.words) · \(QueueWords.bytes(line.bytes))").font(.callout)
        }
        .disabled(line.installed)
    }

    private func review(_ ui: OnboardingUi, _ model: OnboardingViewModel) -> some View {
        List {
            ForEach(Array(ui.review.groups.enumerated()), id: \.offset) { _, g in
                Section(header: heading(g.title.words)) {
                    ForEach(g.lines, id: \.id) { line in
                        HStack { Text(line.title.words); Spacer(); Text(QueueWords.bytes(line.bytes)).foregroundStyle(palette.onSurfaceVariant) }
                    }
                }
            }
            Section {
                Text("\(QueueWords.bytes(ui.review.bytes)) of \(QueueWords.bytes(ui.review.freeBytes)) free on this iPhone")
                    .foregroundStyle(ui.review.fits ? palette.onSurface : palette.error)
                ForEach(Array(ui.review.notes.enumerated()), id: \.offset) { _, n in Text(n.words).font(.callout).foregroundStyle(palette.onSurfaceVariant) }
                Toggle("Download on Wi-Fi only", isOn: Binding(get: { ui.wifiOnly }, set: { model.setWifiOnly(on: $0) }))
                if let notice = ui.notice { Text(notice.words).foregroundStyle(palette.error) }
                Button("Start") { model.start() }
                    .buttonStyle(.borderedProminent)
                    .disabled(ui.starting || !ui.review.fits)
                    .accessibilityIdentifier("onboarding-start")
            }
        }
        .scrollContentBackground(.hidden)
        .navigationTitle("Review")
        .navigationBarTitleDisplayMode(.inline)
    }
}
