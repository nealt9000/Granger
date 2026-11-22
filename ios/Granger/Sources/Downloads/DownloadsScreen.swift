import GrangerCoreKit
import SwiftUI

/// The shared download queue as SwiftUI sees it (Plan I4 Task 6): its summary (what downloads now, what waits, how much,
/// how fast, how long) and the records, for the Downloads screen and Home's downloads card.
@Observable
final class DownloadsHolder {
    private(set) var summary: QueueSummary?
    private(set) var records: [DownloadRecord] = []
    private var watching: [Task<Void, Never>] = []

    @MainActor
    func watch(_ core: Core) {
        guard watching.isEmpty else { return }
        let library = core.library
        watching.append(Task { @MainActor [weak self] in for await s in library.queueSummary { self?.summary = s } })
        watching.append(Task { @MainActor [weak self] in for await r in library.downloads.records { self?.records = r } })
    }

    deinit { watching.forEach { $0.cancel() } }
}

/// Plain words for the queue's figures, as Android's QueueText: rounded so they don't jump about.
enum QueueWords {
    static func bytes(_ n: Int64) -> String { GrangerCoreEntry.shared.bytesText(bytes: n) }

    /// "less than a minute left", "about 25 min left", "about 3 h left", "about 2 days left".
    static func timeLeft(_ millis: Int64) -> String {
        let minutes = Double(millis) / 60_000
        switch minutes {
        case ..<1: return L("less than a minute left")
        case ..<10: return L("about \(max(1, Int(minutes.rounded()))) min left")
        case ..<55: return L("about \(Int((minutes / 5).rounded()) * 5) min left")
        case ..<(47.5 * 60): return L("about \(max(1, Int((minutes / 60).rounded()))) h left")
        default: let d = Int((minutes / 1440).rounded()); return L("about \(d) days left")
        }
    }

    /// "in 40 s", "in 2 min", "in 3 h".
    static func inTime(_ millis: Int64) -> String {
        let s = max(1, millis / 1000)
        if s < 60 { return L("in \(s) s") }
        if s < 3600 { return L("in \((s + 30) / 60) min") }
        return L("in \((s + 1800) / 3600) h")
    }

    /// 1 is "Next", then "2nd in line", "3rd in line"…
    static func place(_ n: Int32) -> String {
        if n <= 1 { return L("Next") }
        let f = NumberFormatter()
        f.numberStyle = .ordinal
        let ordinal = f.string(from: NSNumber(value: n)) ?? "\(n)"
        return L("\(ordinal) in line")
    }

    static func reason(_ r: WaitReason, now: Int64) -> String {
        switch onEnum(of: r) {
        case .paused: L("Paused")
        case .waitingForWifi: L("Waiting for Wi-Fi")
        case .meteredWifi: L("Waiting: this Wi-Fi is marked as metered")
        case let .retrying(t): L("Trying again \(inTime(t.at - now)) (attempt \(t.attempt) of \(DownloadRecordKt.MAX_ATTEMPTS))")
        case let .needsSpace(s): L("Needs \(bytes(s.bytes)) more space")
        case let .inLine(p): place(p.place)
        }
    }

    /// "3 of 14 · 1.2 GB of 9.8 GB · about 25 min left", or "Paused · 2 waiting".
    static func header(_ s: QueueSummary) -> String {
        if s.paused { return L("Paused · \(s.waiting.count) waiting") }
        if let left = s.timeLeftMillis?.int64Value {
            return L("\(s.filesDone) of \(s.files) · \(bytes(s.bytesDone)) of \(bytes(s.bytes)) · \(timeLeft(left))")
        }
        return L("\(s.filesDone) of \(s.files) · \(bytes(s.bytesDone)) of \(bytes(s.bytes))")
    }
}

/// Downloads (Android's Downloads screen, Plan DL): the round's progress with Pause all / Resume, the file now, what's
/// up next with why each waits, what stopped (Retry, Remove), and what finished today (Open).
struct DownloadsScreen: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = DownloadsHolder()

    var body: some View {
        List {
            if let s = holder.summary, s.isActive || !s.stopped.isEmpty {
                header(s)
                if let current = s.current {
                    Section(header: heading("Now")) { currentRow(current, s) }
                }
                if !s.waiting.isEmpty {
                    Section(header: heading("Up next")) {
                        ForEach(s.waiting, id: \.record.itemId) { w in
                            row(w.record, line: L("\(QueueWords.bytes(w.record.bytesDone)) of \(QueueWords.bytes(w.record.sizeBytes)) · \(QueueWords.reason(w.reason, now: Int64(Date().timeIntervalSince1970 * 1000)))"))
                        }
                    }
                }
                if !s.stopped.isEmpty {
                    Section(header: heading("Stopped")) {
                        ForEach(s.stopped, id: \.itemId) { r in row(r, line: r.error ?? L("Stopped"), stopped: true) }
                    }
                }
            }
            let today = doneToday
            if !today.isEmpty {
                Section(header: heading("Done today")) {
                    ForEach(today, id: \.itemId) { r in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(r.title).font(Spectral.regular(19)).foregroundStyle(palette.onSurface)
                                Text(r.kind.isCompanion ? L("Search index · \(QueueWords.bytes(r.sizeBytes))") : QueueWords.bytes(r.sizeBytes))
                                    .font(.callout).foregroundStyle(palette.onSurfaceVariant)
                            }
                            Spacer()
                        }
                    }
                }
            }
            if holder.summary?.isActive != true && holder.summary?.stopped.isEmpty != false && doneToday.isEmpty {
                Text("Nothing downloading. Get more adds packs, and they download here one at a time.")
                    .foregroundStyle(palette.onSurfaceVariant)
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Downloads")
        .navigationBarTitleDisplayMode(.inline)
        .task { if let core { holder.watch(core) } }
    }

    private var doneToday: [DownloadRecord] {
        let start = Calendar.current.startOfDay(for: Date()).timeIntervalSince1970 * 1000
        return holder.records.filter { $0.state == .installed && Double($0.updatedAt) >= start }.sorted { $0.updatedAt > $1.updatedAt }
    }

    private func heading(_ s: LocalizedStringKey) -> some View { Text(s).font(Spectral.medium(16)).foregroundStyle(palette.tertiary) }

    @ViewBuilder private func header(_ s: QueueSummary) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 10) {
                ProgressView(value: Double(s.fraction)).tint(palette.primary)
                Text(QueueWords.header(s)).font(Spectral.regular(18)).foregroundStyle(palette.onSurface)
                    .accessibilityIdentifier("downloads-header")
                HStack {
                    if let rate = s.bytesPerSecond?.int64Value { Text("\(QueueWords.bytes(rate))/s").foregroundStyle(palette.onSurfaceVariant) }
                    Spacer()
                    Button {
                        Task { if s.paused { try? await core?.library.downloads.resume() } else { try? await core?.library.downloads.pause() } }
                    } label: {
                        Label(s.paused ? "Resume" : "Pause all", systemImage: s.paused ? "play.fill" : "pause.fill")
                    }
                    .buttonStyle(.bordered)
                    .accessibilityIdentifier(s.paused ? "downloads-resume" : "downloads-pause")
                }
                Text("\(QueueWords.bytes(s.bytesToGo)) to go · \(QueueWords.bytes(core?.library.freeBytes() ?? 0)) free on this iPhone")
                    .font(.callout).foregroundStyle(palette.onSurfaceVariant)
            }
            .padding(.vertical, 4)
        }
    }

    private func currentRow(_ r: DownloadRecord, _ s: QueueSummary) -> some View {
        let line = s.bytesPerSecond.map { L("\(QueueWords.bytes(s.currentBytesDone)) of \(QueueWords.bytes(r.sizeBytes)) · \(QueueWords.bytes($0.int64Value))/s") }
            ?? L("\(QueueWords.bytes(s.currentBytesDone)) of \(QueueWords.bytes(r.sizeBytes))")
        return VStack(alignment: .leading, spacing: 6) {
            row(r, line: line)
            ProgressView(value: r.sizeBytes > 0 ? Double(s.currentBytesDone) / Double(r.sizeBytes) : 0).tint(palette.primary)
        }
    }

    private func row(_ r: DownloadRecord, line: String, stopped: Bool = false) -> some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(r.title).font(Spectral.regular(19)).foregroundStyle(palette.onSurface)
                Text(line).font(.callout).foregroundStyle(stopped ? palette.error : palette.onSurfaceVariant)
            }
            Spacer()
            Menu {
                if stopped {
                    Button("Retry") { Task { try? await core?.library.downloads.retry(itemId: r.itemId) } }
                    Button("Remove", role: .destructive) { Task { try? await core?.library.downloads.remove(itemId: r.itemId) } }
                } else {
                    Button("Download next") { Task { try? await core?.library.downloads.downloadNext(itemId: r.itemId) } }
                    Button("Cancel", role: .destructive) { Task { try? await core?.library.downloads.cancel(itemId: r.itemId) } }
                }
            } label: {
                Image(systemName: "ellipsis").frame(width: 44, height: 44)
            }
            .accessibilityLabel("More for \(r.title)")
        }
    }
}

/// Home's downloads card (Android's, Plan DL Decision 6): while anything downloads or waits, the round's progress in
/// one line; it opens Downloads.
struct DownloadsCard: View {
    let summary: QueueSummary
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationLink(value: Route.downloads) {
            VStack(alignment: .leading, spacing: 8) {
                Text(summary.paused ? L("Downloads paused") : (summary.current.map { L("Downloading \($0.title)") } ?? L("Downloads waiting")))
                    .font(Spectral.medium(19)).foregroundStyle(palette.onSurface)
                ProgressView(value: Double(summary.fraction)).tint(palette.primary)
                Text(QueueWords.header(summary)).font(.callout).foregroundStyle(palette.onSurfaceVariant)
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 16))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("downloads-card")
    }
}
