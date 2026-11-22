import GrangerCoreKit
import SwiftUI

/// What the audiobook player shows: the live book, or the last book's saved place, paused (Android's MediaTab for books).
struct ShownBook {
    let ui: PlayerUi
    let book: NowPlayingBook
    let chapters: [AudiobookChapter]
    let progress: BookProgress?
    let live: Bool
}

extension PlayerHolder {
    /// The live book, else the saved one paused at its place (any control then makes the audiobook the player's mode).
    var shownBook: ShownBook? {
        if case let .book(b) = onEnum(of: ui.now) { return ShownBook(ui: ui, book: b, chapters: chapters, progress: progress, live: true) }
        guard let s = saved.book else { return nil }
        // SavedBook.toUi() and progress(): paused at its saved chapter and place; a finished book is complete.
        let b = NowPlayingBook(book: s.book, chapter: s.chapter, chapterCount: Int32(s.chapters.count), speed: s.speed,
                               skipSilence: s.skipSilence, volumeBoostMb: s.volumeBoostMb)
        let ui = PlayerUi(now: b, playing: false, positionMs: s.inChapterMs, durationMs: s.chapter.durationMs, sleepRemainingMs: nil)
        var p = PlayerViewModelKt.bookProgress(chapters: s.chapters, chapter: s.chapter, inChapterMs: s.inChapterMs, bookDurationMs: s.book.durationMs)
        if s.finished { p = BookProgress(positionMs: p.durationMs, durationMs: p.durationMs) }
        return ShownBook(ui: ui, book: b, chapters: s.chapters, progress: p, live: false)
    }
}

/// The audiobook player (Android's AudiobookPlayer, Voice-style): the cover, book and chapter, a seek bar in the chapter
/// with the whole book's progress under it, big skip back and forward, chapter steps; then Chapters, the speed, the
/// sleep timer, a bookmark here and the bookmarks; Skip silence and Volume boost per book; and the way to the shelf. On
/// the saved book (audiobooks aren't the player's mode) it shows paused and the per-book actions are left out; a
/// control makes the audiobook the mode and plays from there.
struct AudiobookPlayerScreen: View {
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(\.openRoute) private var openRoute
    @State private var holder = PlayerHolder()
    @State private var sheet: Sheet?
    @State private var seeking: Double?
    @State private var watching: Task<Void, Never>?

    private enum Sheet: String, Identifiable { case chapters, speed, sleep, bookmarks; var id: String { rawValue } }

    var body: some View {
        Group {
            if let shown = holder.shownBook { player(shown) } else if holder.saved.loaded { nothing } else { ProgressView() }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(palette.background)
        .navigationTitle(topLabel)
        .navigationBarTitleDisplayMode(.inline)
        .task {
            holder.start(voice)
            // The player's own lines ("Bookmarked: Chapter 2 · 0:03"), shown where the player's notices are.
            if watching == nil, let m = holder.model {
                watching = Task { @MainActor in for await n in m.notices { voice?.notice = n.words } }
            }
        }
        .onDisappear { watching?.cancel(); watching = nil }
        .sheet(item: $sheet) { which in sheetView(which) }
        .accessibilityIdentifier("audiobook-player")
    }

    private var topLabel: String {
        if case .book = onEnum(of: holder.ui.now) { return "Audiobook" }
        return holder.saved.book != nil ? "Audiobook · paused" : "Audiobooks"
    }

    private var nothing: some View {
        VStack(spacing: 16) {
            Text("Nothing played yet").foregroundStyle(palette.onSurfaceVariant)
            Button("Audiobooks shelf") { openRoute(.audiobooks(collectionId: nil)) }
        }
    }

    private func control(_ live: Bool, _ action: @escaping (PlayerViewModel) -> Void) {
        guard let model = holder.model else { return }
        if live { action(model) } else { model.activate(mode: .audiobook) { action($0) } }
    }

    /// The saved book's chapter steps go by its own list; the live one's by the player's.
    private func stepChapter(_ shown: ShownBook, by: Int) {
        guard let model = holder.model else { return }
        if shown.live { if by < 0 { model.previousChapter() } else { model.nextChapter() }; return }
        guard let at = shown.chapters.firstIndex(where: { $0.ordinal == shown.book.chapter.ordinal }),
              shown.chapters.indices.contains(at + by) else { return }
        let ordinal = shown.chapters[at + by].ordinal
        model.activate(mode: .audiobook) { $0.seekToChapter(ordinal: ordinal) }
    }

    @ViewBuilder private func player(_ shown: ShownBook) -> some View {
        let book = shown.book
        let back = holder.model?.skipBackS() ?? 30
        let forward = holder.model?.skipForwardS() ?? 30
        GeometryReader { geo in
            ScrollView {
                VStack(spacing: 14) {
                    AudiobookCover(path: book.book.coverPath, title: book.book.title)
                        .frame(width: min(geo.size.width - 48, geo.size.height * 0.4, 320))
                    VStack(spacing: 2) {
                        Text(book.book.title).font(Spectral.medium(22)).multilineTextAlignment(.center).lineLimit(2)
                            .accessibilityIdentifier("book-player-title")
                        if !book.book.author.isEmpty { Text(book.book.author).font(.subheadline).foregroundStyle(palette.onSurfaceVariant).lineLimit(1) }
                        Text("\(PlayerViewModelKt.chapterName(book: book).words) · \(book.chapter.ordinal + 1) of \(book.chapterCount)")
                            .foregroundStyle(palette.primary).lineLimit(1)
                            .accessibilityIdentifier("book-player-chapter")
                    }
                    seekBar(shown)
                    if let p = shown.progress { progressLine(p) }
                    HStack {
                        Button { stepChapter(shown, by: -1) } label: { Image(systemName: "backward.end.fill") }
                            .disabled(book.chapter.ordinal <= 0)
                            .accessibilityLabel("Previous chapter")
                            .accessibilityIdentifier("book-player-previous-chapter")
                        Spacer()
                        Button { control(shown.live) { $0.skipBack() } } label: { Image(systemName: "gobackward.\(back)").font(.largeTitle) }
                            .accessibilityLabel(back == 1 ? "Back 1 second" : "Back \(back) seconds")
                        Spacer()
                        Button {
                            if shown.live { holder.model?.playPause() } else { holder.model?.activate(mode: .audiobook) { _ in } }
                        } label: {
                            Image(systemName: shown.ui.playing ? "pause.fill" : "play.fill").font(.title).frame(width: 72, height: 72)
                                .background(palette.primary, in: Circle()).foregroundStyle(palette.onPrimary)
                        }
                        .accessibilityLabel(shown.ui.playing ? "Pause" : "Play")
                        .accessibilityIdentifier("book-player-play-pause")
                        Spacer()
                        Button { control(shown.live) { $0.skipForward() } } label: { Image(systemName: "goforward.\(forward)").font(.largeTitle) }
                            .accessibilityLabel(forward == 1 ? "Forward 1 second" : "Forward \(forward) seconds")
                        Spacer()
                        Button { stepChapter(shown, by: 1) } label: { Image(systemName: "forward.end.fill") }
                            .disabled(book.chapter.ordinal >= book.chapterCount - 1)
                            .accessibilityLabel("Next chapter")
                            .accessibilityIdentifier("book-player-next-chapter")
                    }
                    .font(.title2)
                    if shown.live { actions(shown) }
                    Button("Audiobooks shelf") { openRoute(.audiobooks(collectionId: nil)) }.font(.subheadline)
                }
                .padding(.horizontal, 24).padding(.vertical, 8)
                .frame(maxWidth: .infinity)
            }
        }
    }

    @ViewBuilder private func actions(_ shown: ShownBook) -> some View {
        let book = shown.book
        HStack {
            action("list.bullet", "Chapters", "Chapters") { sheet = .chapters }
            Spacer()
            action("gauge.with.dots.needle.33percent", speedLabel(book.speed), "Playback speed \(speedLabel(book.speed))") { sheet = .speed }
            Spacer()
            let sleep = shown.ui.sleepRemainingMs?.int64Value
            action("timer", sleep.map { formatTime($0) } ?? "Sleep", sleep.map { "Sleep timer, \(formatTime($0)) left" } ?? "Sleep timer", active: sleep != nil) { sheet = .sleep }
            Spacer()
            action("bookmark", "Bookmark", "Add bookmark", id: "book-player-bookmark") { holder.model?.addBookmark { $0.words } }
            Spacer()
            action("star", "Bookmarks", "Bookmarks", id: "book-player-bookmarks") { sheet = .bookmarks }
        }
        Toggle("Skip silence", isOn: Binding(get: { book.skipSilence }, set: { holder.model?.setSkipSilence(on: $0) }))
        Toggle("Volume boost", isOn: Binding(get: { book.volumeBoostMb > 0 }, set: { holder.model?.setVolumeBoost(on: $0) }))
    }

    private func action(_ icon: String, _ label: String, _ description: String, active: Bool = false, id: String? = nil, _ run: @escaping () -> Void) -> some View {
        Button(action: run) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title3)
                Text(label).font(.caption2).lineLimit(1)
            }
            .foregroundStyle(active ? palette.primary : palette.onSurface)
        }
        .accessibilityLabel(description)
        .accessibilityIdentifier(id ?? "book-player-\(label)")
    }

    /// The chapter's bar: it follows the player until the finger is down, and seeks once, on release.
    private func seekBar(_ shown: ShownBook) -> some View {
        let duration = Double(max(shown.ui.durationMs, 0))
        let at = seeking ?? (duration > 0 ? min(1, Double(shown.ui.positionMs) / duration) : 0)
        return VStack(spacing: 2) {
            Slider(value: Binding(get: { at }, set: { seeking = $0 }), in: 0...1) { editing in
                if !editing, let f = seeking {
                    let to = Int64(f * duration)
                    control(shown.live) { $0.seekTo(positionMs: to) }
                    seeking = nil
                }
            }
            .disabled(duration <= 0)
            .accessibilityLabel("Seek in chapter")
            HStack {
                Text(formatTime(Int64(at * duration))).accessibilityIdentifier("book-player-elapsed")
                Spacer()
                Text(formatTime(Int64(duration)))
            }
            .font(.caption.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
        }
    }

    private func progressLine(_ p: BookProgress) -> some View {
        let f = p.durationMs > 0 ? min(1, Double(p.positionMs) / Double(p.durationMs)) : 0
        return VStack(alignment: .leading, spacing: 4) {
            ProgressView(value: f).tint(palette.tertiary)
            Text("Book: \(formatTime(p.positionMs)) of \(formatTime(p.durationMs)) · \(Int(f * 100))%")
                .font(.caption.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
                .accessibilityIdentifier("book-player-progress")
        }
    }

    @ViewBuilder private func sheetView(_ which: Sheet) -> some View {
        if let shown = holder.shownBook, let model = holder.model {
            switch which {
            case .chapters:
                BookChaptersSheet(chapters: shown.chapters, current: Int(shown.book.chapter.ordinal)) { model.seekToChapter(ordinal: $0); sheet = nil }
            case .speed:
                SpeedSheet(speed: Double(shown.book.speed)) { model.setSpeed(speed: Float($0)) }
                    .presentationDetents([.medium])
            case .sleep:
                MediaSleepSheet(remainingMs: shown.ui.sleepRemainingMs?.int64Value,
                                onStart: { model.startSleepTimer(setting: $0); sheet = nil },
                                onCancel: { model.cancelSleepTimer(); sheet = nil })
                    .presentationDetents([.medium, .large])
            case .bookmarks:
                BookmarksSheet(bookmarks: holder.bookmarks, onAdd: { model.addBookmark { $0.words } },
                               onPlay: { model.playBookmark(bookmark: $0); sheet = nil },
                               onDelete: { model.removeBookmark(bookmark: $0) })
                    .presentationDetents([.medium, .large])
            }
        }
    }
}

/// "1.0×", as Android's speedLabel.
func speedLabel(_ speed: Float) -> String { String(format: "%.1f×", speed) }

/// An audiobook's chapters, the current one marked; a tap goes to its start.
struct BookChaptersSheet: View {
    let chapters: [AudiobookChapter]
    let current: Int
    let onPick: (Int32) -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationStack {
            ScrollViewReader { proxy in
                List(chapters, id: \.ordinal) { c in
                    Button { onPick(c.ordinal) } label: {
                        HStack {
                            Text(chapterName(c.title, Int(c.ordinal)))
                                .foregroundStyle(Int(c.ordinal) == current ? palette.primary : palette.onSurface)
                                .fontWeight(Int(c.ordinal) == current ? .semibold : .regular)
                            Spacer()
                            Text(formatTime(c.durationMs)).font(.caption.monospacedDigit()).foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    .id(Int(c.ordinal))
                }
                .onAppear { proxy.scrollTo(current, anchor: .center) }
            }
            .navigationTitle("Chapters")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// This book's speed, 0.5× to 3.0× in 0.1 steps (Android's SpeedSheet): − and +, the slider (applied on release), presets.
struct SpeedSheet: View {
    let speed: Double
    let onSpeed: (Double) -> Void
    @State private var value: Double?
    @Environment(\.palette) private var palette
    private static let presets = [0.8, 1.0, 1.2, 1.5, 2.0]

    private func snap(_ v: Double) -> Double { (min(30, max(5, (v * 10).rounded()))) / 10 }

    var body: some View {
        let shown = value ?? snap(speed)
        VStack(alignment: .leading, spacing: 16) {
            Text("Playback speed").font(Spectral.medium(20))
            HStack(spacing: 16) {
                Button("−") { onSpeed(snap(shown - 0.1)) }.buttonStyle(.bordered).disabled(shown <= 0.5).accessibilityLabel("Slower")
                Text(speedLabel(Float(shown))).font(.title2.monospacedDigit()).frame(width: 80)
                Button("+") { onSpeed(snap(shown + 0.1)) }.buttonStyle(.bordered).disabled(shown >= 3.0).accessibilityLabel("Faster")
            }
            Slider(value: Binding(get: { shown }, set: { value = snap($0) }), in: 0.5...3.0, step: 0.1) { editing in
                if !editing, let v = value { onSpeed(v); value = nil }
            }
            .accessibilityLabel("Playback speed")
            HStack {
                ForEach(Self.presets, id: \.self) { p in
                    Button(speedLabel(Float(p))) { onSpeed(p) }
                        .buttonStyle(.bordered).tint(abs(shown - p) < 0.01 ? palette.primary : palette.onSurfaceVariant).font(.footnote)
                }
            }
        }
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// The player's sleep timer (Android's SleepTimerSheet for music and books): 5–60 minutes with the fade, or the end of
/// the chapter (the song, for music). While one runs, its time left with Cancel.
struct MediaSleepSheet: View {
    let remainingMs: Int64?
    let onStart: (SleepSetting) -> Void
    let onCancel: () -> Void

    var body: some View {
        NavigationStack {
            List {
                if let ms = remainingMs {
                    HStack {
                        Text("Stops in \(formatTime(ms))")
                        Spacer()
                        Button("Cancel timer", action: onCancel).accessibilityIdentifier("sleep-cancel")
                    }
                }
                ForEach(SleepTimerSheet.minutes, id: \.self) { m in
                    Button("\(m) minutes") { onStart(SleepSettingAfter(ms: Int64(m) * 60_000)) }.accessibilityIdentifier("sleep-\(m)")
                }
                Button("End of chapter") { onStart(SleepSettingEndOfChapter.shared) }.accessibilityIdentifier("sleep-chapter")
            }
            .navigationTitle("Sleep timer")
            .navigationBarTitleDisplayMode(.inline)
        }
    }
}

/// This book's bookmarks, newest first (Android's AudioBookmarksSheet): Add here; a tap plays from one; the bin deletes it.
struct BookmarksSheet: View {
    let bookmarks: [AudioBookmark]
    let onAdd: () -> Void
    let onPlay: (AudioBookmark) -> Void
    let onDelete: (AudioBookmark) -> Void
    @Environment(\.palette) private var palette

    var body: some View {
        NavigationStack {
            List {
                if bookmarks.isEmpty { Text("No bookmarks in this book yet").foregroundStyle(palette.onSurfaceVariant) }
                ForEach(bookmarks.sorted { $0.createdAt > $1.createdAt }, id: \.id) { mark in
                    HStack {
                        Button { onPlay(mark) } label: {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(mark.title).foregroundStyle(palette.onSurface).lineLimit(2)
                                if !mark.note.isEmpty { Text(mark.note).font(.caption).foregroundStyle(palette.onSurfaceVariant).lineLimit(3) }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .accessibilityIdentifier("bookmark-row")
                        Button { onDelete(mark) } label: { Image(systemName: "trash") }
                            .buttonStyle(.borderless).accessibilityLabel("Delete bookmark \(mark.title)")
                    }
                }
            }
            .navigationTitle("Bookmarks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) { Button("Add here", action: onAdd).accessibilityIdentifier("bookmark-add-here") }
            }
        }
    }
}
