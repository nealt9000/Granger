import Foundation
import GrangerCoreKit
import MetricKit
import UIKit

/// This iPhone's build and model, for the crash reports and Send feedback (Android's `BuildFacts.ofThisPhone()`, Plan I7
/// Task 4): "Granger 0.1 (1) · iPhone 16 Pro · iOS 26.0".
enum ThisPhone {
    static var facts: BuildFacts {
        let info = Bundle.main.infoDictionary
        return BuildFacts(
            versionName: info?["CFBundleShortVersionString"] as? String ?? "0",
            versionCode: Int32(info?["CFBundleVersion"] as? String ?? "0") ?? 0,
            device: model,
            osVersion: UIDevice.current.systemVersion,
            osName: "iOS"
        )
    }

    /// The model by name ("iPhone 16 Pro"); the Simulator says which device it plays. An identifier not listed below (a
    /// newer phone) is given as it is ("iPhone19,1").
    static var model: String {
        let env = ProcessInfo.processInfo.environment
        if let simulated = env["SIMULATOR_DEVICE_NAME"] { return "\(simulated) (Simulator)" }
        return names[identifier] ?? identifier
    }

    private static var identifier: String {
        var system = utsname()
        uname(&system)
        return withUnsafeBytes(of: &system.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
    }

    /// The iPhones iOS 26 runs on.
    private static let names: [String: String] = [
        "iPhone12,1": "iPhone 11", "iPhone12,3": "iPhone 11 Pro", "iPhone12,5": "iPhone 11 Pro Max", "iPhone12,8": "iPhone SE (2nd generation)",
        "iPhone13,1": "iPhone 12 mini", "iPhone13,2": "iPhone 12", "iPhone13,3": "iPhone 12 Pro", "iPhone13,4": "iPhone 12 Pro Max",
        "iPhone14,4": "iPhone 13 mini", "iPhone14,5": "iPhone 13", "iPhone14,2": "iPhone 13 Pro", "iPhone14,3": "iPhone 13 Pro Max",
        "iPhone14,6": "iPhone SE (3rd generation)", "iPhone14,7": "iPhone 14", "iPhone14,8": "iPhone 14 Plus",
        "iPhone15,2": "iPhone 14 Pro", "iPhone15,3": "iPhone 14 Pro Max", "iPhone15,4": "iPhone 15", "iPhone15,5": "iPhone 15 Plus",
        "iPhone16,1": "iPhone 15 Pro", "iPhone16,2": "iPhone 15 Pro Max",
        "iPhone17,1": "iPhone 16 Pro", "iPhone17,2": "iPhone 16 Pro Max", "iPhone17,3": "iPhone 16", "iPhone17,4": "iPhone 16 Plus", "iPhone17,5": "iPhone 16e",
        "iPhone18,1": "iPhone 17 Pro", "iPhone18,2": "iPhone 17 Pro Max", "iPhone18,3": "iPhone 17", "iPhone18,4": "iPhone Air",
    ]
}

/// The crash log on the iPhone (Plan I7 Task 4, call 4), over the shared `CrashRecords` (newest five, the 64 KB cap, the
/// notice for this build only). Two sources write a report at once: Kotlin's unhandled-exception hook and an
/// `NSException` handler. MetricKit's diagnostics (crashes Granger couldn't catch, hangs, too many disk writes) arrive at
/// a later start or a day later and are kept as the system's exits are on Android. Nothing is sent anywhere: they go
/// only into a feedback email the person chooses to send.
@Observable
final class CrashLog {
    /// The one log, for the handlers (a C function pointer can't capture).
    nonisolated(unsafe) static var shared: CrashLog?
    let records: CrashRecords
    /// Home's "Granger closed unexpectedly" line: the newest unanswered crash from this build.
    private(set) var notice: CrashReport?
    /// How many reports are kept (Settings' Send feedback summary).
    private(set) var count = 0
    @ObservationIgnored private var metrics: MetricKitReports?
    @ObservationIgnored private var watching: Task<Void, Never>?

    init(core: Core) {
        records = core.library.crashRecords(facts: ThisPhone.facts)
        CrashLog.shared = self
        IosFeedbackKt.installCrashHook(records: records, afterRecord: CrashLog.afterRecord)
        NSSetUncaughtExceptionHandler { exception in
            CrashLog.shared?.record(exception)
            CrashLog.afterRecord()
        }
        let metrics = MetricKitReports()
        self.metrics = metrics
        MXMetricManager.shared.add(metrics)
        // The start's read (Android's recordExits at launch): MetricKit's past payloads, and the notice from the files.
        keep(MXMetricManager.shared.pastDiagnosticPayloads)
        watching = Task { @MainActor [weak self, records] in
            for await n in records.notice { self?.notice = n; self?.count = records.list().count }
        }
    }

    deinit { watching?.cancel() }

    /// After a crash is written, before the process ends: debug builds' forced test crash (`-debugCrash`) exits cleanly so
    /// XCTest doesn't count it against the test; otherwise nothing, and the crash goes on as usual.
    static func afterRecord() {
        #if DEBUG
        if UserDefaults.standard.string(forKey: "debugCrash") != nil { exit(0) }
        #endif
    }

    /// An Objective-C exception no one caught: written before the process ends.
    func record(_ exception: NSException) {
        let thread = Thread.isMainThread ? "main" : (Thread.current.name.flatMap { $0.isEmpty ? nil : $0 } ?? "background")
        let body = "Thread: \(thread)\n\n\(exception.name.rawValue): \(exception.reason ?? "")\n" + exception.callStackSymbols.joined(separator: "\n")
        records.record(kind: .java, body: body)
    }

    /// MetricKit's payloads as past exits, recorded once each (the log's mark), skipping a crash Granger already wrote.
    func keep(_ payloads: [MXDiagnosticPayload]) {
        let items = payloads.flatMap { MetricKitReports.items(of: $0) }
        let exits = MetricKitReports.exits(items, written: records.list())
        _ = records.recordExits(exits: exits)
        refresh()
    }

    /// The user sent a report or said not now.
    func acknowledge() { records.acknowledge(); refresh() }

    func clear() { records.clear(); refresh() }

    func refresh() {
        records.refreshNotice()
        let n = records.list().count
        DispatchQueue.main.async { self.count = n }
    }

    /// A report's time as Android's sheet shows it: "2026-11-22 10:15:00 GMT".
    static func stamp(_ report: CrashReport) -> String { IosFeedbackKt.crashStamp(timestamp: report.timestamp) }

    /// A kind as the sheet names it, in the phone's language; the file keeps the English.
    static func kindLabel(_ kind: CrashKind) -> String {
        switch kind {
        case .java: ScreenText.word("feedback_kind_crash")
        case .native: ScreenText.word("feedback_kind_native")
        case .anr: ScreenText.word("feedback_kind_anr")
        case .disk: ScreenText.word("feedback_kind_disk")
        }
    }

}

/// MetricKit's subscriber: payloads are delivered on a background queue at a later start, or a day after.
final class MetricKitReports: NSObject, MXMetricManagerSubscriber {
    func didReceive(_ payloads: [MXDiagnosticPayload]) { CrashLog.shared?.keep(payloads) }

    /// One diagnostic as the log needs it.
    struct Item: Equatable {
        let kind: CrashKind
        let begin: Date
        let end: Date
        let description: String
        let trace: String?
    }

    static func items(of payload: MXDiagnosticPayload) -> [Item] {
        func json(_ d: Data) -> String? { String(data: d, encoding: .utf8) }
        var out: [Item] = []
        for c in payload.crashDiagnostics ?? [] {
            var why = ["Exception type \(c.exceptionType?.stringValue ?? "?")", "code \(c.exceptionCode?.stringValue ?? "?")",
                       "signal \(c.signal?.stringValue ?? "?")"].joined(separator: ", ")
            if let reason = c.terminationReason { why += "; \(reason)" }
            if let objc = c.exceptionReason { why += "; \(objc.exceptionName): \(objc.composedMessage)" }
            out.append(Item(kind: .native, begin: payload.timeStampBegin, end: payload.timeStampEnd, description: why, trace: json(c.callStackTree.jsonRepresentation())))
        }
        for h in payload.hangDiagnostics ?? [] {
            out.append(Item(kind: .anr, begin: payload.timeStampBegin, end: payload.timeStampEnd,
                            description: "Hang of \(h.hangDuration.formatted())", trace: json(h.callStackTree.jsonRepresentation())))
        }
        for w in payload.diskWriteExceptionDiagnostics ?? [] {
            out.append(Item(kind: .disk, begin: payload.timeStampBegin, end: payload.timeStampEnd,
                            description: "Writes of \(w.totalWritesCaused.formatted())", trace: json(w.callStackTree.jsonRepresentation())))
        }
        return out
    }

    /// [items] as the shared log's past exits. A crash MetricKit saw that one of Granger's own handlers already wrote (a
    /// "Crash" report within a minute of the payload's window) is left out, so one crash isn't listed twice.
    static func exits(_ items: [Item], written: [CrashReport]) -> [PastExit] {
        let own = written.filter { $0.kind == .java }.map { Date(timeIntervalSince1970: Double($0.timestamp) / 1000) }
        return items.compactMap { item in
            if item.kind == .native, own.contains(where: { $0 >= item.begin.addingTimeInterval(-60) && $0 <= item.end.addingTimeInterval(60) }) { return nil }
            return PastExit(timestamp: Int64(item.end.timeIntervalSince1970 * 1000), kind: item.kind, description: item.description, trace: item.trace)
        }
    }
}
