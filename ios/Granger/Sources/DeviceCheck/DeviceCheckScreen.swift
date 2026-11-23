#if FRIENDS
import SwiftUI

/// Settings › About › Device check (Plan I8 call 5), in the friends' build only: Run check measures what it can, Say a
/// title tries the mic's recognizer on the tester's voice, Send report opens Send feedback with the report in the email.
struct DeviceCheckScreen: View {
    @Environment(\.core) private var core
    @Environment(AppSettings.self) private var settings
    @Environment(CrashLog.self) private var crashes
    @Environment(\.palette) private var palette
    @State private var run = DeviceCheckRun()
    @State private var sending: String?

    var body: some View {
        List {
            Section {
                Text("This checks what Granger can measure on this iPhone by itself: speech recognition, search by meaning, memory, a few songs from your Music library (counts only, never their names), the widget's shared folder and the system's reports. It takes a minute or two. Nothing leaves this phone until you send the report.")
                    .foregroundStyle(palette.onSurface)
            }
            Section {
                Button {
                    guard let core else { return }
                    Task { await run.runAll(core: core, settings: settings) }
                } label: {
                    HStack {
                        Text(run.finished == nil ? "Run check" : "Run again")
                        if run.running { Spacer(); ProgressView() }
                    }
                }
                .disabled(run.running || run.listening)
                .accessibilityIdentifier("device-check-run")
                Button {
                    Task { await run.sayTitle() }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack {
                            Text("Say a title")
                            if run.listening { Spacer(); ProgressView() }
                        }
                        Text(run.listening ? L("Listening… say “\(SpeechCheck.forApp.title)” now.")
                                           : L("Tap, then say “\(SpeechCheck.forApp.title)” within four seconds."))
                            .font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                    }
                }
                .disabled(run.running || run.listening)
                .accessibilityIdentifier("device-check-say")
            }
            if run.finished != nil || run.running {
                Section {
                    Text(verbatim: run.report).font(.caption.monospaced()).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier(run.running ? "device-check-report-partial" : "device-check-report")
                } header: {
                    Text("The report")
                } footer: {
                    Text("In English, for Granger's developers.")
                }
                Section {
                    Button("Send report") { sending = run.report }
                        .buttonStyle(.borderedProminent)
                        .disabled(run.running || run.listening)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .listRowBackground(Color.clear)
                        .accessibilityIdentifier("device-check-send")
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Device check")
        .navigationBarTitleDisplayMode(.inline)
        .sheet(item: Binding(get: { sending.map(Sending.init) }, set: { sending = $0?.report })) { s in
            NavigationStack { FeedbackSheet(log: crashes, deviceCheck: s.report) }
        }
    }

    private struct Sending: Identifiable {
        let report: String
        var id: String { report }
    }
}
#endif
