import AppIntents
import SwiftUI
import WidgetKit

/// Granger's widget extension (Plan I6 Task 8, call 7): the player widget and Control Center's Local only control. Both
/// read the app group (the snapshot the app writes, and Local only in the group's defaults); the Kotlin core isn't loaded.
@main
struct GrangerWidgets: WidgetBundle {
    var body: some Widget {
        PlayerWidget()
        LocalOnlyControl()
    }
}

// MARK: The player widget

struct PlayerEntry: TimelineEntry {
    let date: Date
    let snapshot: WidgetSnapshot
    let cover: UIImage?
}

struct PlayerProvider: TimelineProvider {
    func placeholder(in context: Context) -> PlayerEntry {
        PlayerEntry(date: .now, snapshot: WidgetSnapshot(title: "Mars, the Bringer of War", subtitle: "Gustav Holst", playing: false, secondary: "next", progress: 0.3, empty: false, hasCover: false), cover: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (PlayerEntry) -> Void) { completion(entry()) }

    /// The app reloads the timeline when the snapshot changes, so one entry with no refresh is enough.
    func getTimeline(in context: Context, completion: @escaping (Timeline<PlayerEntry>) -> Void) {
        completion(Timeline(entries: [entry()], policy: .never))
    }

    private func entry() -> PlayerEntry {
        let s = WidgetSnapshot.load()
        let cover = s.hasCover ? WidgetSnapshot.coverURL.flatMap { UIImage(contentsOfFile: $0.path) } : nil
        return PlayerEntry(date: .now, snapshot: s, cover: cover)
    }
}

/// "Granger player" (Android's PlayerWidget): small, the cover and play/pause; medium, the title and subtitle and the
/// second button too (next for music, back for a book). With nothing ever played: "Nothing playing" / "Open Granger".
struct PlayerWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: WidgetSnapshot.kind, provider: PlayerProvider()) { entry in
            PlayerWidgetView(entry: entry)
                .containerBackground(for: .widget) { Color(red: 0.96, green: 0.94, blue: 0.89) }
        }
        .configurationDisplayName("Granger player")
        .description("What's playing: music, an audiobook or read aloud.")
        .supportedFamilies([.systemSmall, .systemMedium])
    }
}

struct PlayerWidgetView: View {
    let entry: PlayerEntry
    @Environment(\.widgetFamily) private var family
    private let ink = Color(red: 0.16, green: 0.20, blue: 0.16)

    var body: some View {
        let s = entry.snapshot
        if s.empty {
            VStack(alignment: .leading, spacing: 4) {
                Image(systemName: "headphones").font(.title2)
                Text("Nothing playing").font(.headline)
                Text("Open Granger").font(.caption).opacity(0.7)
            }
            .foregroundStyle(ink)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .leading)
        } else if family == .systemSmall {
            VStack(alignment: .leading, spacing: 6) {
                cover(56)
                Text(s.title).font(.caption.weight(.semibold)).lineLimit(2)
                Spacer(minLength: 0)
                HStack { playPause(s); Spacer() }
            }
            .foregroundStyle(ink)
        } else {
            HStack(spacing: 12) {
                cover(96)
                VStack(alignment: .leading, spacing: 4) {
                    Text(s.title).font(.headline).lineLimit(2)
                    Text(s.subtitle).font(.subheadline).opacity(0.7).lineLimit(1)
                    if let p = s.progress { ProgressView(value: p).tint(ink) }
                    Spacer(minLength: 0)
                    HStack(spacing: 16) {
                        playPause(s)
                        if s.secondary != "none" {
                            Button(intent: SecondaryIntent()) {
                                Image(systemName: s.secondary == "next" ? "forward.end.fill" : "gobackward").font(.title3)
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel(s.secondary == "next" ? "Next" : "Back")
                        }
                    }
                }
            }
            .foregroundStyle(ink)
        }
    }

    private func playPause(_ s: WidgetSnapshot) -> some View {
        Button(intent: PlayPauseIntent()) {
            Image(systemName: s.playing ? "pause.circle.fill" : "play.circle.fill").font(.largeTitle)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(s.playing ? "Pause" : "Play")
    }

    @ViewBuilder private func cover(_ side: CGFloat) -> some View {
        if let image = entry.cover {
            Image(uiImage: image).resizable().scaledToFill().frame(width: side, height: side).clipShape(RoundedRectangle(cornerRadius: 8))
        } else {
            RoundedRectangle(cornerRadius: 8).fill(ink.opacity(0.12)).frame(width: side, height: side)
                .overlay(Image(systemName: "music.note").font(.title2).opacity(0.5))
        }
    }
}

// MARK: The Local only control

/// Control Center's Local only (Android's Quick Settings tile): on while Local only is; a tap flips it, and the app follows.
struct LocalOnlyControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: LocalOnlyControlKind.kind, provider: LocalOnlyValue()) { on in
            ControlWidgetToggle("Local only", isOn: on, action: SetLocalOnlyIntent()) { isOn in
                Label(isOn ? "On" : "Off", systemImage: isOn ? "icloud.slash" : "icloud")
            }
        }
        .displayName("Local only")
        .description("Behave exactly as with no signal: show only what this phone holds.")
    }
}

struct LocalOnlyValue: ControlValueProvider {
    var previewValue: Bool { false }
    func currentValue() async throws -> Bool { SharedLocalOnly.value }
}
