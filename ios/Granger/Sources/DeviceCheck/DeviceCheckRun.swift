#if FRIENDS
import AVFoundation
import Darwin
import Foundation
import GrangerCoreKit
import MediaPlayer
import MetricKit
import Speech
import UIKit

/// The Device check (Plan I8 call 5), in the friends' build only: what can be measured on a friend's iPhone without a
/// person, as a plain report that Send feedback carries. The report is English, for Granger's developers, as the crash
/// files are; the screen's own words are translated. It never reads anything outside Granger but the first few Music
/// library songs' playability, and it names no song, pack or book: counts only. Nothing leaves the phone until the
/// tester sends the email.
@MainActor @Observable
final class DeviceCheckRun {
    /// Each part's lines, in the report's order; a part not run yet has none.
    private(set) var parts: [(title: String, lines: [String])] = []
    private(set) var running = false
    private(set) var listening = false
    private(set) var finished: Date?
    /// The live phrase's lines, kept apart so a second try replaces the first.
    private var live: [String] = ["not tried (tap Say a title)"]

    /// The last report, kept so Send feedback from Settings carries it too.
    nonisolated static var saved: String? {
        get { UserDefaults.standard.string(forKey: "deviceCheck.report") }
        set { UserDefaults.standard.set(newValue, forKey: "deviceCheck.report") }
    }

    var report: String {
        var out = ["Device check · \(finished.map(Self.stamp) ?? "not run")"]
        for part in parts {
            out.append("")
            out.append("\(part.title):")
            out.append(contentsOf: part.lines.map { "  \($0)" })
        }
        return out.joined(separator: "\n")
    }

    /// Every measurement, one after another (search by meaning loads a model; the speech check may download one).
    func runAll(core: Core, settings: AppSettings) async {
        guard !running else { return }
        running = true
        defer { running = false }
        parts = []
        add("Phone", phone())
        add("Memory", memoryTier(core))
        add("Speech", await speech())
        add("Search by meaning", await meaning(core, language: settings.mainLanguage))
        add("Music library", await musicLibrary())
        add("App group", appGroup())
        add("MetricKit", metricKit())
        finished = Date()
        Self.saved = report
    }

    /// The live phrase: the tester says the title in the app's language.
    func sayTitle() async {
        guard !listening else { return }
        listening = true
        defer { listening = false }
        let outcome = await SpeechCheck.live(SpeechCheck.forApp)
        live = Self.lines(outcome)
        if let i = parts.firstIndex(where: { $0.title == "Speech" }) {
            parts[i].lines = parts[i].lines.filter { !$0.hasPrefix("live") } + live.map { "live: \($0)" }
        } else {
            add("Speech", live.map { "live: \($0)" })
        }
        if finished == nil { finished = Date() }
        Self.saved = report
    }

    private func add(_ title: String, _ lines: [String]) { parts.append((title, lines)) }

    // MARK: the parts

    private func phone() -> [String] {
        let facts = ThisPhone.facts
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let free = (try? docs.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?.volumeAvailableCapacityForImportantUsage
        let total = (try? docs.resourceValues(forKeys: [.volumeTotalCapacityKey]))?.volumeTotalCapacity
        return [
            "build: Granger \(facts.versionName) (\(facts.versionCode)), \(BuildExtras.kind.rawValue)",
            "model: \(facts.device)",
            "iOS: \(facts.osVersion)",
            "free space: \(free.map { Self.bytes($0) } ?? "?") of \(total.map { Self.bytes(Int64($0)) } ?? "?")",
            "thermal state: \(Self.thermal(ProcessInfo.processInfo.thermalState)), low power: \(ProcessInfo.processInfo.isLowPowerModeEnabled ? "on" : "off")",
        ]
    }

    private func memoryTier(_ core: Core) -> [String] {
        let ram = Int64(ProcessInfo.processInfo.physicalMemory)
        return [
            "physical memory: \(Self.gib(ram)) (\(ram) bytes)",
            "search by meaning allowed: \(core.library.meaning.on ? "yes" : "no") (needs 4.5 GiB, a 6 GB phone)",
            "app footprint now: \(Self.bytes(Self.footprint())), available to the app: \(Self.bytes(Int64(os_proc_available_memory())))",
        ]
    }

    private func speech() async -> [String] {
        var lines = ["SpeechTranscriber available: \(SpeechTranscriber.isAvailable ? "yes" : "no")",
                     "speech recognition permission: \(Self.permission(SFSpeechRecognizer.authorizationStatus()))"]
        for phrase in [SpeechCheck.english, SpeechCheck.spanish] {
            let outcome = await SpeechCheck.onSpokenFile(phrase)
            lines += Self.lines(outcome).map { "spoken file, \(phrase.language): \($0)" }
        }
        return lines + live.map { "live: \($0)" }
    }

    static func lines(_ o: SpeechCheck.Outcome) -> [String] {
        var out = ["\(o.passed ? "PASS" : "FAIL") in \(String(format: "%.1f", o.seconds)) s, \(o.recognizer ?? "no recognizer")\(o.locale.map { " (\($0))" } ?? "")"]
        if let heard = o.heard { out.append("heard \"\(heard)\"") }
        if let m = o.match { out.append("matched \"\(m.name)\" at \(String(format: "%.2f", m.score)) (needs \(SpokenTitleMatcher.shared.THRESHOLD))") }
        if let problem = o.problem { out.append(problem) }
        if !o.steps.isEmpty { out.append("steps: \(o.steps.joined(separator: ", "))") }
        return out
    }

    /// Search by meaning as the friend's phone does it: the model's load, the first query and a later one, memory each
    /// step, then the model let go. Over the friend's own packs and books; none, or no embedder, is said as such.
    private func meaning(_ core: Core, language: String) async -> [String] {
        let library = core.library
        guard library.meaning.on else { return ["off on this phone (memory)"] }
        let queries = language == "es"
            ? ["cómo fabrican las plantas su alimento con la luz del sol", "un plato de sopa caliente para una noche fría"]
            : ["how do plants make food from sunlight", "a warm bowl of broth for a cold winter evening"]
        library.meaning.release()
        let before = Self.footprint()
        var lines = ["footprint before: \(Self.bytes(before))"]
        let load = Date()
        try? await library.warmBest()
        let loaded = Self.footprint()
        lines.append("model load: \(Self.ms(since: load)) ms, footprint after: \(Self.bytes(loaded)) (+\(Self.bytes(loaded - before)))")
        for (i, query) in queries.enumerated() {
            let start = Date()
            let result = try? await library.bestResult(query: query)
            let total = Self.ms(since: start)
            let label = i == 0 ? "first query" : "later query"
            guard let result else {
                lines.append("\(label): no packs or books to search (\(total) ms)")
                continue
            }
            lines.append("\(label): \(total) ms, by meaning: \(result.byMeaning ? "yes" : "no"), embed \(result.embedMs) ms, dense \(result.denseMs) ms, "
                         + "keywords \(result.keywordMs) ms, \(result.matches.count) matches")
        }
        lines.append("footprint with the model loaded: \(Self.bytes(Self.footprint())), available to the app: \(Self.bytes(Int64(os_proc_available_memory())))")
        library.meaning.release()
        lines.append("footprint after letting it go: \(Self.bytes(Self.footprint()))")
        if !lines.contains(where: { $0.contains("by meaning: yes") }) {
            lines.append("no query went by meaning: is the embedder in Settings › Models, and a pack with search vectors?")
        }
        return lines
    }

    /// The first few songs the Music library lists: whether each opens (`ipod-library://` with AVAudioFile). Counts only.
    private func musicLibrary() async -> [String] {
        let status = MPMediaLibrary.authorizationStatus()
        guard status == .authorized else { return ["access: \(Self.permission(status)) (allow it in Settings › Media, then run again)"] }
        let items = MPMediaQuery.songs().items ?? []
        var protected = 0, cloud = 0, noURL = 0, opened = 0, failed = 0
        var failures: [String] = []
        for item in items.prefix(8) {
            if item.hasProtectedAsset { protected += 1; continue }
            if item.isCloudItem { cloud += 1; continue }
            guard let url = item.assetURL else { noURL += 1; continue }
            do {
                let file = try AVAudioFile(forReading: url)
                if file.length > 0 { opened += 1 } else { failed += 1; failures.append("empty") }
            } catch {
                failed += 1
                failures.append((error as NSError).domain + " \((error as NSError).code)")
            }
        }
        var lines = ["songs in the library: \(items.count)",
                     "of the first \(min(items.count, 8)): opened \(opened), failed \(failed), protected \(protected), in the cloud only \(cloud), no file \(noURL)"]
        if !failures.isEmpty { lines.append("failures: \(failures.joined(separator: ", "))") }
        return lines
    }

    /// The widget's side: the group folder there, a probe file written and read back, the snapshot the widget reads.
    private func appGroup() -> [String] {
        var lines = ["group: \(AppGroup.id)"]
        guard let folder = AppGroup.folder else { return lines + ["folder: missing (the build has no app group entitlement)"] }
        lines.append("folder: there")
        let probe = folder.appendingPathComponent("device-check-probe.json")
        let sent = WidgetSnapshot(title: "probe", subtitle: UUID().uuidString, playing: false, secondary: "none", progress: 0.5, empty: false, hasCover: false)
        do {
            try JSONEncoder().encode(sent).write(to: probe, options: .atomic)
            let back = try JSONDecoder().decode(WidgetSnapshot.self, from: Data(contentsOf: probe))
            lines.append("snapshot round trip: \(back == sent ? "ok" : "different")")
        } catch {
            lines.append("snapshot round trip: failed, \(error.localizedDescription)")
        }
        try? FileManager.default.removeItem(at: probe)
        let shown = WidgetSnapshot.load()
        let file = WidgetSnapshot.fileURL.map { FileManager.default.fileExists(atPath: $0.path) } ?? false
        lines.append("widget snapshot: \(file ? (shown.empty ? "there, nothing playing" : "there, \(shown.playing ? "playing" : "paused")") : "not written yet (play something)")")
        let key = "deviceCheck.probe"
        let token = UUID().uuidString
        AppGroup.defaults.set(token, forKey: key)
        lines.append("group defaults: \(AppGroup.defaults.string(forKey: key) == token && AppGroup.defaults !== UserDefaults.standard ? "ok" : "not shared")")
        AppGroup.defaults.removeObject(forKey: key)
        lines.append("Local only (shared): \(SharedLocalOnly.value ? "on" : "off")")
        return lines
    }

    private func metricKit() -> [String] {
        let counts = MetricKitCounts.read()
        return [
            "past diagnostic payloads now: \(MXMetricManager.shared.pastDiagnosticPayloads.count), past metric payloads now: \(MXMetricManager.shared.pastPayloads.count)",
            "delivered since install: diagnostics \(counts.diagnostics), metrics \(counts.metrics)\(counts.last.map { ", last \(Self.stamp($0))" } ?? "")",
        ]
    }

    // MARK: helpers

    static func stamp(_ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.timeZone = TimeZone(identifier: "UTC")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss 'UTC'"
        return f.string(from: date)
    }

    static func bytes(_ n: Int64) -> String {
        // os_proc_available_memory() is 0 where iOS sets no limit (the Simulator).
        if n == 0 { return "0 (not reported)" }
        return ByteCountFormatter.string(fromByteCount: n, countStyle: .memory).replacingOccurrences(of: "\u{00a0}", with: " ")
    }

    static func gib(_ n: Int64) -> String { String(format: "%.1f GiB", Double(n) / 1_073_741_824) }

    static func ms(since start: Date) -> Int { Int(Date().timeIntervalSince(start) * 1000) }

    /// The app's memory as iOS counts it against its limit (phys_footprint).
    static func footprint() -> Int64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
        }
        return kr == KERN_SUCCESS ? Int64(info.phys_footprint) : 0
    }

    static func thermal(_ s: ProcessInfo.ThermalState) -> String {
        switch s {
        case .nominal: "nominal"
        case .fair: "fair"
        case .serious: "serious"
        case .critical: "critical"
        @unknown default: "unknown"
        }
    }

    static func permission(_ s: SFSpeechRecognizerAuthorizationStatus) -> String {
        switch s {
        case .authorized: "allowed"
        case .denied: "denied"
        case .restricted: "restricted"
        case .notDetermined: "not asked yet"
        @unknown default: "unknown"
        }
    }

    static func permission(_ s: MPMediaLibraryAuthorizationStatus) -> String {
        switch s {
        case .authorized: "allowed"
        case .denied: "denied"
        case .restricted: "restricted"
        case .notDetermined: "not asked yet"
        @unknown default: "unknown"
        }
    }
}

/// MetricKit's deliveries counted (friends' builds), so the Device check can say whether a real payload ever arrived.
enum MetricKitCounts {
    static func read() -> (diagnostics: Int, metrics: Int, last: Date?) {
        let d = UserDefaults.standard
        return (d.integer(forKey: "metrickit.diagnostics"), d.integer(forKey: "metrickit.metrics"), d.object(forKey: "metrickit.last") as? Date)
    }

    static func delivered(diagnostics: Int = 0, metrics: Int = 0) {
        let d = UserDefaults.standard
        d.set(d.integer(forKey: "metrickit.diagnostics") + diagnostics, forKey: "metrickit.diagnostics")
        d.set(d.integer(forKey: "metrickit.metrics") + metrics, forKey: "metrickit.metrics")
        d.set(Date(), forKey: "metrickit.last")
    }
}
#endif
