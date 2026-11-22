import GrangerCoreKit
import SwiftUI

/// Listen, the fifth tab (Plan I5 call 7): in I5 its Reading part, Android's player Reader tab. While the voice is loaded:
/// what it reads and the section, the sentence in large type between the ones around it, previous paragraph, back a
/// sentence, play/pause, next sentence, next paragraph, Stop and Open; the text's chapters; the speed and the voice;
/// the sleep timer, and the music under the voice with its level (Plan I6 Task 7). With nothing loaded: Resume the last
/// thing read, and how to start. Then the media (Plan I6 Tasks 6 and 7): the card for a song or a book, and the Music and
/// Audiobooks shelves, as Android shows them under the card.
struct ListenScreen: View {
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var chapters: ReadChapters?
    @State private var showChapters = false
    @State private var showSleep = false
    @State private var speed: Double?
    @State private var voices: [Voice] = []

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let voice, let now = voice.ui.now {
                    reader(voice, now)
                } else {
                    nothingRead
                }
                ListenMedia()
            }
            .padding(.horizontal, 24)
            .padding(.vertical, 16)
        }
        .background(palette.background)
        .navigationTitle("Listen")
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                NavigationLink(value: Route.getMoreAudio) { Image(systemName: "plus") }
                    .accessibilityLabel("Get more")
                    .accessibilityIdentifier("listen-get-more")
            }
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Find music on this phone") { openRoute(.mediaSettings) }
                } label: { Image(systemName: "ellipsis.circle") }
                .accessibilityLabel("More")
                .accessibilityIdentifier("listen-more")
            }
        }
        .accessibilityIdentifier("listen-root")
        .task(id: "\(voice?.ui.now?.sourceKey ?? "")|\(voice?.ui.now?.section ?? "")") {
            chapters = try? await voice?.voice.player.chapters()
            voices = voice?.voice.installed.voices.value ?? []
        }
        .sheet(isPresented: $showChapters) {
            if let chapters { ChaptersSheet(chapters: chapters) { i in showChapters = false; voice?.voice.player.jumpToChapter(index: Int32(i)) } }
        }
        .sheet(isPresented: $showSleep) {
            if let voice { SleepTimerSheet(ui: voice.ui, player: voice.voice.player) { showSleep = false }.presentationDetents([.medium, .large]) }
        }
    }

    @ViewBuilder private func reader(_ voice: VoiceCenter, _ now: VoiceNow) -> some View {
        let player = voice.voice.player
        Text([now.title, now.section].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
            .font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(2)
            .accessibilityIdentifier("listen-title")
        VStack(alignment: .leading, spacing: 10) {
            if let before = voice.progress?.previous { Text(before).font(Spectral.regular(17)).foregroundStyle(palette.onSurfaceVariant.opacity(0.6)).lineLimit(3) }
            Text(voice.progress?.utterance.text ?? now.sentence)
                .font(Spectral.medium(24)).foregroundStyle(palette.onSurface)
                .accessibilityIdentifier("listen-sentence")
            if let after = voice.progress?.next { Text(after).font(Spectral.regular(17)).foregroundStyle(palette.onSurfaceVariant.opacity(0.6)).lineLimit(3) }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        HStack {
            Button { player.previousParagraph() } label: { Image(systemName: "backward.end.fill") }.accessibilityLabel("Previous paragraph")
            Spacer()
            Button { player.previousSentence() } label: { Image(systemName: "gobackward") }.accessibilityLabel("Back a sentence")
            Spacer()
            Button { player.playPause() } label: {
                Image(systemName: voice.ui.playing ? "pause.fill" : "play.fill").font(.title).frame(width: 64, height: 64)
                    .background(palette.primary, in: Circle()).foregroundStyle(palette.onPrimary)
            }
            .accessibilityLabel(voice.ui.playing ? "Pause" : "Play")
            .accessibilityIdentifier("listen-play-pause")
            Spacer()
            Button { player.nextSentence() } label: { Image(systemName: "goforward") }.accessibilityLabel("Next sentence")
            Spacer()
            Button { player.nextParagraph() } label: { Image(systemName: "forward.end.fill") }.accessibilityLabel("Next paragraph")
        }
        .font(.title2)
        HStack(spacing: 12) {
            Button("Stop") { player.stop() }.buttonStyle(.bordered).frame(maxWidth: .infinity).accessibilityIdentifier("listen-stop")
            Button("Open") { open(now) }.buttonStyle(.borderedProminent).frame(maxWidth: .infinity).accessibilityIdentifier("listen-open")
        }
        if let chapters {
            Divider()
            Button { showChapters = true } label: {
                HStack {
                    Image(systemName: "list.bullet")
                    Text("Chapters")
                    Spacer()
                    if let i = chapters.current?.intValue, i < chapters.chapters.count {
                        Text(chapterName(chapters.chapters[i].title, i)).foregroundStyle(palette.onSurfaceVariant).lineLimit(1)
                    }
                }
            }
            .accessibilityIdentifier("listen-chapters")
        }
        Divider()
        let shown = speed ?? Double(now.speed)
        HStack { Text("Speed"); Spacer(); Text(String(format: "%.1f×", shown)).font(.headline) }
        Slider(value: Binding(get: { shown }, set: { speed = ($0 * 10).rounded() / 10 }), in: 0.7...2.0, step: 0.1) { editing in
            if !editing, let s = speed { player.setSpeed(value: Float(s)); speed = nil }
        }
        .accessibilityLabel("Reading speed")
        HStack {
            ForEach([0.8, 1.0, 1.2, 1.5, 2.0], id: \.self) { preset in
                Button(String(format: "%.1f×", preset)) { player.setSpeed(value: Float(preset)) }
                    .buttonStyle(.bordered).tint(abs(shown - preset) < 0.01 ? palette.primary : palette.onSurfaceVariant).font(.footnote)
            }
        }
        if !voices.isEmpty {
            Picker("Voice", selection: Binding(get: { now.voiceId.encode() }, set: { id in
                if let v = voices.first(where: { $0.id.encode() == id }) { player.setVoice(voice: v.id) }
            })) {
                ForEach(voices, id: \.self) { v in Text(v.title).tag(v.id.encode()) }
            }
        }
        if case let .voice(v) = onEnum(of: voice.media.now), let music = v.music {
            Divider()
            MusicUnderVoiceRow(music: music, playing: v.musicPlaying, hub: voice.hub)
        }
        Divider()
        Button { showSleep = true } label: {
            HStack {
                Image(systemName: "timer")
                Text("Sleep timer")
                Spacer()
                Text(sleepLine(voice.ui)).foregroundStyle(voice.ui.sleeping ? palette.primary : palette.onSurfaceVariant)
                    .accessibilityIdentifier("listen-sleep-state")
            }
        }
        .accessibilityIdentifier("listen-sleep")
    }

    private var nothingRead: some View {
        VStack(spacing: 16) {
            if let last = voice?.lastRead {
                Button {
                    Task { _ = try? await voice?.voice.player.resumeLast() }
                } label: {
                    Label(resumeLabel(last), systemImage: "headphones").lineLimit(2)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityIdentifier("listen-resume")
            }
            Text("Open a book or article and tap Listen")
                .font(.body).foregroundStyle(palette.onSurfaceVariant).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 40)
    }

    private func resumeLabel(_ last: LastRead) -> String {
        if let s = last.section, !s.isEmpty { return "Resume \(last.title) · \(s)" }
        return "Resume \(last.title)"
    }

    private func sleepLine(_ ui: VoiceUi) -> String {
        if ui.sleepAtChapterEnd { return "End of chapter" }
        if let ms = ui.sleepRemainingMs?.int64Value { return "Stops in \(formatTime(ms))" }
        return "Off"
    }

    /// The page being read, in its reader (Android's Open).
    private func open(_ now: VoiceNow) {
        guard let key = ReadSourceKeyCompanion.shared.parse(key: now.sourceKey) else { return }
        switch onEnum(of: key) {
        case let .article(a): openRoute(.packReader(pack: a.packId, path: a.path, fragment: nil))
        case let .book(b): openRoute(.book(id: b.bookId))
        case .text: break
        }
    }
}

/// "Chapter 3" for an untitled chapter, as Android's chapterName.
func chapterName(_ title: String, _ index: Int) -> String {
    title.trimmingCharacters(in: .whitespaces).isEmpty ? "Chapter \(index + 1)" : title
}

/// "4:05" or "1:02:03".
func formatTime(_ ms: Int64) -> String {
    let s = Int(ms / 1000)
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}

/// The text's chapters, the current one marked; a tap goes to its first sentence.
struct ChaptersSheet: View {
    let chapters: ReadChapters
    let onPick: (Int) -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List(Array(chapters.chapters.enumerated()), id: \.offset) { i, c in
                    Button { onPick(i) } label: {
                        Text(chapterName(c.title, i))
                            .padding(.leading, CGFloat(c.depth) * 16)
                            .foregroundStyle(chapters.current?.intValue == i ? palette.primary : palette.onSurface)
                            .fontWeight(chapters.current?.intValue == i ? .semibold : .regular)
                    }
                    .id(i)
                }
                .onAppear { if let c = chapters.current?.intValue { proxy.scrollTo(c, anchor: .center) } }
            }
            .navigationTitle("Chapters")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// The sleep timer (Android's SleepTimerSheet): 5–60 minutes or the end of the chapter, with a fade over the last
/// seconds. While one runs, its time left with Cancel; picking another restarts it.
struct SleepTimerSheet: View {
    let ui: VoiceUi
    let player: VoicePlayer
    let onDone: () -> Void
    static let minutes = [5, 10, 15, 30, 45, 60]

    var body: some View {
        NavigationStack {
            List {
                if ui.sleeping {
                    HStack {
                        Text(ui.sleepAtChapterEnd ? "End of chapter" : "Stops in \(formatTime(ui.sleepRemainingMs?.int64Value ?? 0))")
                        Spacer()
                        Button("Cancel timer") { player.cancelSleepTimer(); onDone() }.accessibilityIdentifier("sleep-cancel")
                    }
                }
                ForEach(Self.minutes, id: \.self) { m in
                    Button(m == 1 ? "1 minute" : "\(m) minutes") {
                        player.startSleepTimer(setting: SleepSettingAfter(ms: Int64(m) * 60_000))
                        onDone()
                    }
                    .accessibilityIdentifier("sleep-\(m)")
                }
                Button("End of chapter") { player.startSleepTimer(setting: SleepSettingEndOfChapter.shared); onDone() }
                    .accessibilityIdentifier("sleep-chapter")
            }
            .navigationTitle("Sleep timer")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}
