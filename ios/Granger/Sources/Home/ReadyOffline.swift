import GrangerCoreKit
import SwiftUI

/// Ready offline (Android's, Plan O3 Task 8) worked out from the last month's reads: the shared ReadyOffline model.
@Observable
final class ReadyOfflineHolder {
    private(set) var ui: ReadyOfflineUi?

    @MainActor
    func load(_ core: Core, recent: RecentStore) async {
        let since = Date().addingTimeInterval(-30 * 24 * 3600)
        let reads = recent.entries.filter { $0.at >= since }.map { ArticleRef(packId: core.pack(for: $0.lang), path: $0.path) }
        ui = (try? await core.library.readyOffline(reads: reads)) ?? nil
    }
}

enum ReadyWords {
    static func subject(_ id: String) -> String {
        ScreenText.strings[Subject.companion.of(id: id)?.labelKey ?? ""] ?? id
    }

    static func eyebrow(_ ui: ReadyOfflineUi) -> String {
        ui.recent == 1 ? "Ready offline · your last read" : "Ready offline · your last \(ui.recent) reads"
    }

    static func headline(_ ui: ReadyOfflineUi) -> String {
        "\(ui.offline) of your \(ui.recent) recent read\(ui.recent == 1 ? " works" : "s work") offline"
    }

    static func suggestion(_ s: Suggestion) -> String {
        if s.packId == nil { return s.covers == 1 ? "Keep the one you read · \(QueueWords.bytes(s.bytes))" : "Keep the \(s.covers) you read · \(QueueWords.bytes(s.bytes))" }
        return "Get \(s.title) · \(QueueWords.bytes(s.bytes)) · covers \(s.about ? "about " : "")\(s.covers) of them"
    }
}

/// The bars and the ways to fill the gaps, for Home's card and Outfitting's Your use.
struct ReadyOfflineBody: View {
    let ui: ReadyOfflineUi
    var allSubjects = false
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var note: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Eyebrow(text: ReadyWords.eyebrow(ui))
            Text(ReadyWords.headline(ui)).font(Spectral.medium(26)).foregroundStyle(palette.onSurface)
                .accessibilityIdentifier("ready-headline")
            HStack(spacing: 14) {
                legend(palette.secondary, "On this phone")
                legend(palette.tertiary, "Saved for now")
                legend(palette.surfaceContainerHighest, "Online")
            }
            .font(.caption)
            ForEach(ui.subjects, id: \.subjectId) { bar in subjectBar(bar) }
            ForEach(Array(ui.suggestions.enumerated()), id: \.offset) { _, s in
                Button(ReadyWords.suggestion(s)) { take(s) }
                    .buttonStyle(.bordered)
                    .font(.callout)
            }
            if let note { Text(note).font(.callout).foregroundStyle(palette.tertiary) }
        }
    }

    private func legend(_ color: Color, _ text: String) -> some View {
        HStack(spacing: 4) { RoundedRectangle(cornerRadius: 2).fill(color).frame(width: 10, height: 10); Text(text).foregroundStyle(palette.onSurfaceVariant) }
    }

    private func subjectBar(_ bar: SubjectBar) -> some View {
        let total = max(1, Int(bar.total))
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(ReadyWords.subject(bar.subjectId)).foregroundStyle(palette.onSurface)
                Spacer()
                Text("\(bar.onPhone + bar.saved) / \(bar.total)").foregroundStyle(palette.onSurfaceVariant)
            }
            .font(.callout)
            GeometryReader { g in
                HStack(spacing: 0) {
                    palette.secondary.frame(width: g.size.width * CGFloat(bar.onPhone) / CGFloat(total))
                    palette.tertiary.frame(width: g.size.width * CGFloat(bar.saved) / CGFloat(total))
                    palette.surfaceContainerHighest
                }
                .clipShape(RoundedRectangle(cornerRadius: 3))
            }
            .frame(height: 8)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("\(ReadyWords.subject(bar.subjectId)): \(bar.onPhone) on this phone, \(bar.saved) saved for now, \(bar.online) online")
    }

    private func take(_ s: Suggestion) {
        guard let core else { return }
        if let packId = s.packId {
            Task {
                let r = try? await core.library.getPack(id: packId)
                note = r is EnqueueResultNoSpace ? "Not enough room for \(s.title)." : "\(s.title) is on its way: see Downloads."
            }
        } else {
            let refs = s.articles.compactMap { p -> ArticleRef? in
                guard let lang = p.first as? String, let title = p.second as? String else { return nil }
                return ArticleRef(packId: lang, path: title)
            }
            core.library.keepTheRead(articles: refs)
            note = "Keeping them: each is marked ● when it's on this phone."
        }
    }
}

/// Home's Ready offline card; See everything on this phone opens Outfitting.
struct ReadyOfflineCard: View {
    let ui: ReadyOfflineUi
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 14) {
            ReadyOfflineBody(ui: ui)
            NavigationLink(value: Route.outfitting) {
                Text("See everything on this phone").font(.callout.weight(.medium)).foregroundStyle(palette.tertiary)
            }
            .accessibilityIdentifier("ready-see-all")
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
    }
}

/// Outfitting (Android's, Plan O4): Try Local only, Your use, what's on this phone (kept articles, Saved for now), the
/// knowledge domains (Ready for anything) and storage by source.
struct OutfittingScreen: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(AppSettings.self) private var settings
    @Environment(RecentStore.self) private var recent
    @Environment(\.openRoute) private var openRoute
    @State private var ready = ReadyOfflineHolder()
    @State private var report: ReadinessReport?
    @State private var kept: [KeptArticle] = []
    @State private var updates: [KeptArticle] = []
    @State private var savedBytes: Int64 = 0

    var body: some View {
        @Bindable var settings = settings
        List {
            Section {
                Toggle(isOn: $settings.localOnly) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Try Local only").font(Spectral.regular(19))
                        Text("See exactly what this phone holds, as with no signal.").font(.callout).foregroundStyle(palette.onSurfaceVariant)
                    }
                }
            }
            if let ui = ready.ui { Section { ReadyOfflineBody(ui: ui).padding(.vertical, 6) } }
            Section(header: heading("Kept articles")) {
                if !updates.isEmpty {
                    HStack {
                        Text(updates.count == 1 ? "1 kept article has a newer version" : "\(updates.count) kept articles have a newer version").foregroundStyle(palette.onSurface)
                        Spacer()
                        Button("Update all") { let u = updates; Task { try? await core?.library.updateKept(articles: u) } }
                            .buttonStyle(.bordered).accessibilityIdentifier("kept-update-all")
                    }
                }
                if kept.isEmpty { Text("No articles kept yet. Keep one from its topic page.").foregroundStyle(palette.onSurfaceVariant) }
                ForEach(kept, id: \.title) { k in
                    Button { openRoute(.topic(lang: k.lang, path: k.title)) } label: {
                        HStack {
                            Text(GrangerCoreEntry.shared.titleOfKey(key: k.title)).foregroundStyle(palette.onSurface)
                            Spacer()
                            Text(QueueWords.bytes(k.bytes)).font(.callout).foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    .swipeActions { Button("Remove", role: .destructive) { Task { try? await core?.library.remove(lang: k.lang, title: k.title) } } }
                }
                Text("Saved for now · \(QueueWords.bytes(savedBytes)) of \(QueueWords.bytes(Int64(settings.savedCapMb) * 1024 * 1024))")
                    .foregroundStyle(palette.onSurfaceVariant)
            }
            if let report {
                Section(header: heading("Ready for anything"), footer: Text("What a phone needs when nothing else is there.")) {
                    ForEach(report.domains, id: \.id) { d in domainRow(d) }
                }
                Section(header: heading("By source")) {
                    ForEach(report.storage.filter { $0.items > 0 || $0.bytes > 0 }, id: \.source) { row in
                        HStack {
                            Text(ScreenText.strings[row.source.titleKey] ?? row.source.name).foregroundStyle(palette.onSurface)
                            Spacer()
                            Text("\(QueueWords.bytes(row.bytes)) · \(row.items) item\(row.items == 1 ? "" : "s")").foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    NavigationLink(value: Route.downloads) { Text("Downloads") }
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Outfitting")
        .navigationBarTitleDisplayMode(.inline)
        .task(id: settings.localOnly) {
            guard let core else { return }
            await ready.load(core, recent: recent)
            report = try? await core.library.readinessReport()
            savedBytes = core.library.savedBytes()
        }
        .task {
            guard let core else { return }
            for await k in core.library.keptArticles { kept = k }
        }
        .task {
            guard let core else { return }
            try? await core.library.checkKeptUpdates()
            for await u in core.library.keptUpdates.updates { updates = u }
        }
    }

    private func heading(_ s: String) -> some View { Text(s).font(Spectral.medium(16)).foregroundStyle(palette.tertiary) }

    private func domainRow(_ d: DomainCoverage) -> some View {
        let held = QueueWords.bytes(d.installedBytes)
        let essentials = QueueWords.bytes(d.essentialBytes)
        let line: String
        switch d.coverage {
        case .comprehensive: line = "Comprehensive · \(held)"
        case .basic: line = "Basic · \(held) of \(QueueWords.bytes(d.comprehensiveBytes))"
        default:
            if d.installedBytes == 0, let note = d.emptyNote { line = note.words }
            else if d.installedBytes > 0 && d.essentialBytes > 0 { line = "No essentials yet · \(held) held · the essentials are \(essentials)" }
            else if d.installedBytes > 0 { line = "No essentials yet · \(held) held" }
            else if d.essentialBytes > 0 { line = "Nothing yet · the essentials are \(essentials)" }
            else { line = "Nothing yet" }
        }
        let fraction = d.comprehensiveBytes > 0 ? min(1, Double(d.installedBytes) / Double(d.comprehensiveBytes)) : (d.coverage == .comprehensive ? 1 : 0)
        return VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(d.title).foregroundStyle(palette.onSurface)
                Spacer()
                if let first = d.missingEssential.first {
                    Button("Get \(QueueWords.bytes(first.downloadBytes))") { Task { _ = try? await core?.library.getPack(id: first.id) } }
                        .font(.callout).buttonStyle(.bordered)
                }
            }
            ProgressView(value: fraction).tint(palette.secondary)
            Text(line).font(.caption).foregroundStyle(palette.onSurfaceVariant)
            ForEach(Array(d.covers.enumerated()), id: \.offset) { _, c in Text(c.words).font(.caption).foregroundStyle(palette.tertiary) }
        }
        .padding(.vertical, 2)
    }
}
