#if DEBUG
import SwiftUI

/// Plan I6 Task 8's debug hook for the UI tests, with `-debugSystem YES`: a hidden label with what the widget would draw
/// now (the snapshot read back from the app group, as the extension reads it), and hidden buttons that run the widget's play/pause and Control
/// Center's Local only intent (it writes the group's defaults and posts the Darwin notification, as from the extension;
/// the app then follows through its listener, not through this button).
enum DebugSystem {
    static var on: Bool { UserDefaults.standard.bool(forKey: "debugSystem") }
}

struct DebugSystemControls: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            TimelineView(.periodic(from: .now, by: 0.5)) { _ in
                let s = WidgetSnapshot.load()
                Text(s.empty ? "empty" : "\(s.title)|\(s.playing ? "playing" : "paused")|\(s.secondary)")
                    .font(.caption2).opacity(0.01)
                    .accessibilityIdentifier("debug-widget-snapshot")
            }
            Button("Run Local only intent") {
                NSLog("debugSystem: local only intent, now %@", SharedLocalOnly.value ? "on" : "off")
                Task { do { _ = try await SetLocalOnlyIntent(value: !SharedLocalOnly.value).perform() } catch { NSLog("debugSystem: %@", "\(error)") } }
            }
            .font(.caption2).buttonStyle(.borderless)
            .accessibilityIdentifier("debug-local-only-intent")
            Button("Run play/pause intent") {
                NSLog("debugSystem: play/pause intent")
                Task { do { _ = try await PlayPauseIntent().perform() } catch { NSLog("debugSystem: %@", "\(error)") } }
            }
            .font(.caption2).buttonStyle(.borderless)
            .accessibilityIdentifier("debug-play-pause-intent")
        }
    }
}
#endif
