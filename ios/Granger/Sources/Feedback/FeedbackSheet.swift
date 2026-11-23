import GrangerCoreKit
import MessageUI
import SwiftUI
import UIKit

/// Where feedback goes (Android's `BuildConfig.FEEDBACK_EMAIL`).
enum FeedbackAddress {
    static let email = "feedback@opengranger.org"
}

/// An email ready to hand over: Mail's compose sheet when Mail is set up, else the share sheet with the same text and
/// attachment (the Simulator never has Mail). Never sent by Granger itself.
struct FeedbackEmail: Identifiable {
    let id = UUID()
    let subject: String
    let body: String
    /// The attachment's text (the facts, then each crash report in full); nil when there's nothing to attach.
    let attachment: String?

    static let attachmentName = "granger-report.txt"

    /// The attachment written fresh to the temporary folder, for the share sheet.
    func attachmentFile() -> URL? {
        guard let attachment else { return nil }
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(Self.attachmentName)
        try? attachment.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    /// Everything the email holds, for the debug hook the UI tests read (`-debugMailText YES`).
    var composed: String {
        "To: \(FeedbackAddress.email)\nSubject: \(subject)\n\n\(body)" + (attachment.map { "\n[\(Self.attachmentName)]\n\($0)" } ?? "")
    }
}

/// Opens the email: Mail's compose sheet, or the share sheet when Mail can't send.
struct EmailHandOff: View {
    let email: FeedbackEmail
    let onDone: () -> Void

    var body: some View {
        #if DEBUG
        if UserDefaults.standard.bool(forKey: "debugMailText") {
            NavigationStack {
                ScrollView {
                    Text(email.composed).font(.caption.monospaced()).textSelection(.enabled).padding()
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("debug-mail-text")
                }
                .navigationTitle(Text(verbatim: "Email"))
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { onDone() } } }
            }
        } else { handOff }
        #else
        handOff
        #endif
    }

    @ViewBuilder private var handOff: some View {
        if MFMailComposeViewController.canSendMail() {
            MailCompose(email: email, onDone: onDone).ignoresSafeArea()
        } else {
            ShareEmail(email: email, onDone: onDone)
        }
    }
}

private struct MailCompose: UIViewControllerRepresentable {
    let email: FeedbackEmail
    let onDone: () -> Void

    func makeCoordinator() -> Coordinator { Coordinator(onDone: onDone) }

    func makeUIViewController(context: Context) -> MFMailComposeViewController {
        let mail = MFMailComposeViewController()
        mail.mailComposeDelegate = context.coordinator
        mail.setToRecipients([FeedbackAddress.email])
        mail.setSubject(email.subject)
        mail.setMessageBody(email.body, isHTML: false)
        if let text = email.attachment, let data = text.data(using: .utf8) {
            mail.addAttachmentData(data, mimeType: "text/plain", fileName: FeedbackEmail.attachmentName)
        }
        return mail
    }

    func updateUIViewController(_ controller: MFMailComposeViewController, context: Context) {}

    final class Coordinator: NSObject, MFMailComposeViewControllerDelegate {
        let onDone: () -> Void
        init(onDone: @escaping () -> Void) { self.onDone = onDone }
        func mailComposeController(_ controller: MFMailComposeViewController, didFinishWith result: MFMailComposeResult, error: Error?) {
            onDone()
        }
    }
}

/// The share sheet with the email's text (its subject for apps that take one) and the attachment.
private struct ShareEmail: UIViewControllerRepresentable {
    let email: FeedbackEmail
    let onDone: () -> Void

    func makeUIViewController(context: Context) -> UIActivityViewController {
        var items: [Any] = [EmailText(email: email)]
        if let file = email.attachmentFile() { items.append(file) }
        let share = UIActivityViewController(activityItems: items, applicationActivities: nil)
        share.completionWithItemsHandler = { _, _, _, _ in onDone() }
        return share
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}

    private final class EmailText: NSObject, UIActivityItemSource {
        let email: FeedbackEmail
        init(email: FeedbackEmail) { self.email = email }
        func activityViewControllerPlaceholderItem(_ controller: UIActivityViewController) -> Any { email.body }
        func activityViewController(_ controller: UIActivityViewController, itemForActivityType type: UIActivity.ActivityType?) -> Any? { email.body }
        func activityViewController(_ controller: UIActivityViewController, subjectForActivityType type: UIActivity.ActivityType?) -> String { email.subject }
    }
}

/// Send feedback (Android's FeedbackSheet, Plan F1 part 2 Decision 2): what the email will carry, shown before Mail opens,
/// a switch that leaves the crash reports out, each report with View, Clear crash reports and Write email.
struct FeedbackSheet: View {
    let log: CrashLog
    /// The friends' build (Plan I8 call 5): the Device check's report, in the email's text beside the usual facts. Send
    /// report gives a fresh one; elsewhere it's the last one run, if any.
    var deviceCheck: String? = FeedbackSheet.lastDeviceCheck
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @State private var facts: FeedbackFacts?
    @State private var withCrashes = true
    @State private var viewing: CrashReport?
    @State private var email: FeedbackEmail?
    @State private var cleared = 0

    var body: some View {
        List {
            Section {
                Text("Your mail app opens with an email to \(FeedbackAddress.email). Say what you tried and what happened, then send it from there. Nothing leaves this phone until you do.")
                    .foregroundStyle(palette.onSurface)
            }
            if let facts {
                Section {
                    ForEach(FeedbackReport.shared.included(facts: facts, withCrashes: withCrashes), id: \.self) { line in
                        Text("• \(line)").accessibilityIdentifier("feedback-line")
                    }
                    if deviceCheck != nil {
                        Text("• \(L("The Device check report (no titles or searches)"))").accessibilityIdentifier("feedback-device-check")
                    }
                } header: {
                    Text("The email includes")
                }
                if !facts.crashes.isEmpty {
                    Section {
                        Toggle(isOn: $withCrashes) {
                            VStack(alignment: .leading, spacing: 2) {
                                Text("Attach crash reports")
                                Text("What the app was doing when it closed. No titles or searches.").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                            }
                        }
                        .accessibilityIdentifier("feedback-attach")
                        ForEach(facts.crashes, id: \.path) { report in
                            Button { viewing = report } label: {
                                HStack(alignment: .firstTextBaseline) {
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text("\(CrashLog.kindLabel(report.kind)) · \(CrashLog.stamp(report))").foregroundStyle(palette.onSurface)
                                        Text(report.summary).font(.footnote).foregroundStyle(palette.onSurfaceVariant).lineLimit(2)
                                    }
                                    Spacer()
                                    Text("View").foregroundStyle(palette.primary)
                                }
                            }
                            .accessibilityIdentifier("feedback-crash")
                        }
                        Button("Clear crash reports", role: .destructive) {
                            log.clear()
                            cleared += 1
                        }
                        .accessibilityIdentifier("feedback-clear")
                    }
                }
                Section {
                    Button("Write email") { write(facts) }
                        .buttonStyle(.borderedProminent)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .listRowBackground(Color.clear)
                        .accessibilityIdentifier("feedback-write")
                }
            } else {
                Text("Reading this phone…").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Send feedback")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        .accessibilityIdentifier("feedback-sheet")
        .task(id: cleared) {
            guard let core else { return }
            let records = log.records
            facts = try? await core.library.feedbackFacts(records: records, build: ThisPhone.facts)
        }
        .sheet(item: $viewing) { report in CrashReportView(report: report, text: log.records.read(report: report) ?? "") }
        .sheet(item: $email) { e in
            EmailHandOff(email: e) {
                email = nil
                log.acknowledge()
                dismiss()
            }
        }
    }

    private func write(_ facts: FeedbackFacts) {
        let report = FeedbackReport.shared
        let records = log.records
        email = FeedbackEmail(
            subject: report.subject(build: facts.build),
            body: report.body(facts: facts, withCrashes: withCrashes) + (deviceCheck.map { "\n\n\($0)\n" } ?? ""),
            attachment: report.attachment(facts: facts, withCrashes: withCrashes) { records.read(report: $0) ?? "" }
        )
    }
}

extension FeedbackSheet {
    static var lastDeviceCheck: String? {
        #if FRIENDS
        DeviceCheckRun.saved
        #else
        nil
        #endif
    }
}

extension CrashReport: @retroactive Identifiable {
    public var id: String { path }
}

/// One report in full, as Android's View dialog shows it.
private struct CrashReportView: View {
    let report: CrashReport
    let text: String
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            ScrollView {
                Text(text).font(.caption.monospaced()).textSelection(.enabled).padding()
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityIdentifier("crash-text")
            }
            .navigationTitle("\(CrashLog.kindLabel(report.kind)) · \(CrashLog.stamp(report))")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Close") { dismiss() }.accessibilityIdentifier("crash-close") } }
        }
    }
}

/// Home's line after a crash in this build (Android's CrashNoticeRow): Not now, or Send (Send feedback's sheet).
struct CrashNoticeRow: View {
    let onSend: () -> Void
    let onDismiss: () -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack { words; Spacer(); buttons }
            VStack(alignment: .leading, spacing: 8) { words; HStack { Spacer(); buttons } }
        }
        .padding(12)
        .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("crash-notice")
    }

    private var words: some View {
        Text("Granger closed unexpectedly. Send a report?").font(.callout).foregroundStyle(palette.onSurface)
    }

    @ViewBuilder private var buttons: some View {
        Button("Not now", action: onDismiss).accessibilityIdentifier("crash-not-now")
        Button("Send", action: onSend).buttonStyle(.borderedProminent).accessibilityIdentifier("crash-send")
    }
}

/// Report this answer (Android's ReportSheet; Play's AI-Generated Content policy): what's wrong, a note, what the email
/// carries. Ask is off on the iPhone, as in Android's release, so nothing opens it there yet but the debug hook
/// (`-debugReportSheet YES`); it's here for when Ask comes back.
struct ReportSheet: View {
    let guess: Bool
    let sources: Int
    let onSend: (_ reason: AskReport.Reason, _ note: String) -> Void
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss
    @State private var reason: AskReport.Reason?
    @State private var note = ""

    var body: some View {
        List {
            Section {
                ForEach([AskReport.Reason.wrong, .offensive, .unsafe, .other], id: \.self) { r in
                    Button { reason = r } label: {
                        HStack {
                            Image(systemName: reason == r ? "largecircle.fill.circle" : "circle").foregroundStyle(palette.primary).accessibilityHidden(true)
                            Text(Self.label(r)).foregroundStyle(palette.onSurface)
                        }
                    }
                    .accessibilityAddTraits(reason == r ? .isSelected : [])
                }
            } header: {
                Text("What's wrong?")
            }
            Section {
                TextField("Anything to add (optional)", text: $note, axis: .vertical)
                    .lineLimit(2...5)
                    .onChange(of: note) { _, n in if n.count > 2_000 { note = String(n.prefix(2_000)) } }
            }
            Section {
                Text("• \(L("Your question"))")
                Text("• \(guess ? L("The best guess") : L("The answer"))")
                if sources > 0 { Text("• \(L("The titles of its \(sources) sources"))") }
                Text("• \(L("Granger's version and your phone's model"))")
            } header: {
                Text("The email includes")
            } footer: {
                Text("Your mail app opens with this email to \(FeedbackAddress.email). Nothing else from your chats is sent, and nothing leaves this phone until you send it.")
            }
            Section {
                Button("Write email") { if let reason { onSend(reason, note) } }
                    .buttonStyle(.borderedProminent)
                    .disabled(reason == nil)
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .listRowBackground(Color.clear)
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle(guess ? L("Report this best guess") : L("Report this answer"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Close") { dismiss() } } }
        .accessibilityIdentifier("report-sheet")
    }

    static func label(_ r: AskReport.Reason) -> String {
        switch r {
        case .wrong: L("Wrong or made up")
        case .offensive: L("Offensive")
        case .unsafe: L("Unsafe or harmful")
        default: L("Something else")
        }
    }
}

/// The Report sheet with its email, for an Ask reply ([report] made when the reason is picked).
struct ReportFlow: View {
    let guess: Bool
    let sources: Int
    let report: (_ reason: AskReport.Reason, _ note: String) -> AskReport
    @Environment(\.dismiss) private var dismiss
    @State private var email: FeedbackEmail?

    var body: some View {
        NavigationStack {
            ReportSheet(guess: guess, sources: sources) { reason, note in
                let r = report(reason, note)
                email = FeedbackEmail(subject: r.subject(), body: r.body(), attachment: nil)
            }
        }
        .sheet(item: $email) { e in EmailHandOff(email: e) { email = nil; dismiss() } }
    }
}
