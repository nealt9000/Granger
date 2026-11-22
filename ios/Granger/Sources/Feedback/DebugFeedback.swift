#if DEBUG
import Foundation
import GrangerCoreKit

/// Debug builds' hooks for Send feedback and the crash log (Plan I7 Task 4), for the UI tests.
enum DebugFeedback {
    /// `-debugCrash kotlin` or `-debugCrash objc`: a crash a second after the start, through the matching handler, so the
    /// next launch finds it in the log and on Home.
    static func crashIfAsked() {
        guard let how = UserDefaults.standard.string(forKey: "debugCrash") else { return }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            NSLog("Granger: -debugCrash \(how)")
            if how == "objc" {
                NSException(name: .internalInconsistencyException, reason: "Granger debug crash (forced for a test)").raise()
            } else {
                IosFeedbackKt.debugCrash()
            }
        }
    }

    /// `-debugReportSheet YES`'s made-up Ask reply.
    static func sampleReport(reason: AskReport.Reason, note: String) -> AskReport {
        AskReport(reason: reason, note: note, question: "Who built the Pharos?", reply: "Sostratus of Cnidus.", guess: false,
                  sources: ["Lighthouse of Alexandria", "Sostratus of Cnidus"], build: ThisPhone.facts)
    }
}
#endif
