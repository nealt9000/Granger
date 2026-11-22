import AVFAudio
import GrangerCoreKit
import XCTest
@testable import Granger

/// Plan I6 Task 5: the iPhone's player (`IosPlayerHub` over the one `AudioGraph`). Fixtures are generated tones
/// (AudioEngineSpikeTests.tone); the gapless and mix tests render the graph offline, so they measure exactly.
final class PlayerEngineTests: XCTestCase {
    private func samples(_ a: KotlinFloatArray) -> [Float] { (0..<Int(a.size)).map { a.get(index: Int32($0)) } }

    private func database() throws -> LibraryDatabase {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("player-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return Store_iosKt.openLibraryDatabase(path: dir.appendingPathComponent("library.db").path)
    }

    private func prefs() -> DefaultsPlayerPrefs {
        let suite = "player-tests-\(UUID().uuidString)"
        return DefaultsPlayerPrefs(suiteName: suite)
    }

    private func waitUntil(_ timeout: TimeInterval = 10, _ what: String, _ check: () async throws -> Bool) async throws {
        let end = Date().addingTimeInterval(timeout)
        while Date() < end {
            if try await check() { return }
            try await Task.sleep(for: .milliseconds(50))
        }
        XCTFail("timed out: \(what)")
    }

    /// Two files on the media node, the second scheduled back to back: no gap where they meet, and the player follows
    /// into the second (its key, its place, the Advanced event).
    func testGaplessSchedulingOnTheMediaNode() throws {
        let a = try AudioEngineSpikeTests.tone("gapless-a", seconds: 2, freq: 440, ext: "caf")
        let b = try AudioEngineSpikeTests.tone("gapless-b", seconds: 2, freq: 660, ext: "m4a")
        let graph = AudioGraph(offline: true)
        let player = MediaFilePlayer(graph: graph)
        XCTAssertTrue(player.load(file: MediaFile(key: 0, path: a.path, gainDb: 0), positionMs: 0))
        player.setNext(file: MediaFile(key: 1, path: b.path, gainDb: 0))
        player.play()
        var out = samples(graph.renderOffline(frames: Int32(44100 * 3)))
        XCTAssertEqual(player.currentKey?.intValue, 1, "the second file is heard")
        print("PLAYER position after 3 s: \(player.positionMs()) ms into the second file")
        XCTAssertEqual(Double(player.positionMs()), 1000, accuracy: 100, "a second into the second file")
        out += samples(graph.renderOffline(frames: Int32(44100 * 1.5)))
        let gap = AudioEngineSpikeTests.longestSilenceMs(out, from: 0.3, to: 3.7)
        print("PLAYER gapless: \(String(format: "%.2f", gap)) ms where the files meet")
        XCTAssertLessThan(gap, 1, "no gap beyond a sample or two")
        // Offline rendering plays nothing back, so the node's played-back callbacks (Advanced, Ended) don't come here;
        // testTheHubMovesThroughTheQueue sees them in real time.
    }

    /// The hub follows a queue of two songs from the first into the second by itself.
    func testTheHubMovesThroughTheQueue() async throws {
        let a = try AudioEngineSpikeTests.tone("queue-a", seconds: 1, freq: 440, ext: "caf")
        let b = try AudioEngineSpikeTests.tone("queue-b", seconds: 1, freq: 550, ext: "caf")
        let voiceUi = IosPlayerHub.companion.voiceState(ui: VoiceUi.companion.IDLE)
        let hub = IosPlayerHub.companion.standalone(graph: AudioGraph(), database: try database(), prefs: prefs(), voiceUi: voiceUi)
        let rows = [a, b].map { IosPlayerHub.companion.fileTrack(path: $0.path, title: $0.lastPathComponent, durationMs: 1000) }
        hub.playTrackRows(rows: rows, startIndex: 0, source: PlaySource(key: "test", title: "Test"))
        try await waitUntil(5, "the first song plays") { hub.ui.value.playing }
        try await waitUntil(5, "the second song is current") {
            if case .music(let m) = onEnum(of: hub.ui.value.now) { return m.index == 1 }
            return false
        }
        hub.stop()
    }

    /// Music started under the voice plays at the mix level (0.3 by default), measured in the rendered output; the voice
    /// on its own node is heard over it; when the voice stops, the music comes back to full.
    func testMusicPlaysUnderTheVoiceAtTheMixLevel() async throws {
        let tone = try AudioEngineSpikeTests.tone("mix-music", seconds: 4, freq: 440, ext: "caf", amp: 0.3)
        let graph = AudioGraph(offline: true)
        let voiceUi = IosPlayerHub.companion.voiceState(ui: VoiceUi.companion.IDLE)
        let hub = IosPlayerHub.companion.standalone(graph: graph, database: try database(), prefs: prefs(), voiceUi: voiceUi)
        hub.playTrackRows(rows: [IosPlayerHub.companion.fileTrack(path: tone.path, title: "mix", durationMs: 4000)], startIndex: 0,
                          source: PlaySource(key: "test", title: "Test"))
        try await waitUntil(5, "the music plays") { hub.ui.value.playing }
        let full = AudioEngineSpikeTests.rms(samples(graph.renderOffline(frames: 22050))[2205...])

        let now = VoiceNow(sourceKey: "text:test", title: "Test", section: nil, sentence: "A sentence.", paragraph: 0, speed: 1,
                           voiceId: VoiceIdGranger(dir: "owl", speaker: 0), ended: false)
        voiceUi.value = VoiceUi(now: now, playing: true, sleepRemainingMs: nil, sleepAtChapterEnd: false)
        try await waitUntil(5, "the music goes under the voice") { abs(hub.mediaVolume - PlayerSettingsKeys.shared.DEFAULT_MIX) < 0.001 }
        let under = AudioEngineSpikeTests.rms(samples(graph.renderOffline(frames: 22050))[2205...])
        if case .voice(let v) = onEnum(of: hub.ui.value.now) { XCTAssertNotNil(v.music, "the voice's state names the music under it") } else { XCTFail("the voice is the mode") }

        // The voice's own node, a 200 Hz tone as Piper's mono 22,050 Hz writes it, over the music.
        let format = try XCTUnwrap(AVAudioFormat(standardFormatWithSampleRate: 22050, channels: 1))
        let speech = try XCTUnwrap(AVAudioPCMBuffer(pcmFormat: format, frameCapacity: 22050))
        speech.frameLength = 22050
        for i in 0..<22050 { speech.floatChannelData![0][i] = 0.4 * Float(sin(2 * Double.pi * 200 * Double(i) / 22050)) }
        graph.voice.scheduleBuffer(speech, completionHandler: nil)
        graph.voice.play()
        let both = AudioEngineSpikeTests.rms(samples(graph.renderOffline(frames: 22050))[2205...])

        voiceUi.value = VoiceUi.companion.IDLE
        try await waitUntil(5, "the music comes back") { abs(hub.mediaVolume - 1) < 0.001 }
        print("PLAYER mix: under the voice \(String(format: "%.3f", under / full)) of full; with the voice rms \(String(format: "%.3f", both)) against \(String(format: "%.3f", under))")
        XCTAssertEqual(under / full, Double(PlayerSettingsKeys.shared.DEFAULT_MIX), accuracy: 0.01)
        XCTAssertGreaterThan(both, under * 1.5, "the voice is heard over the music")
        hub.stop()
    }

    /// An audiobook's place is written to `audiobook_states` on pause (its file and the place in it), and a new player
    /// over the same store opens the book there, in the same chapter.
    func testTheSavedPlaceIsWrittenAndRestored() async throws {
        let one = try AudioEngineSpikeTests.tone("book-1", seconds: 3, freq: 300, ext: "caf", amp: 0.1)
        let two = try AudioEngineSpikeTests.tone("book-2", seconds: 4, freq: 350, ext: "m4a", amp: 0.1)
        let db = try database()
        let t1 = IosPlayerHub.companion.fileTrack(path: one.path, title: "One", durationMs: 3000, audiobookId: "book")
        let t2 = IosPlayerHub.companion.fileTrack(path: two.path, title: "Two", durationMs: 4000, audiobookId: "book")
        try await db.mediaDao().upsertTracks(tracks: [t1, t2])
        try await db.mediaDao().upsertAudiobooks(books: [IosPlayerHub.companion.fileAudiobook(id: "book", title: "Tones", author: "Granger")])
        try await db.mediaDao().insertChapters(chapters: [
            AudiobookChapter(audiobookId: "book", ordinal: 0, title: "First", trackId: t1.id, startMs: 0, durationMs: 3000),
            AudiobookChapter(audiobookId: "book", ordinal: 1, title: "Second", trackId: t2.id, startMs: 0, durationMs: 1500),
            AudiobookChapter(audiobookId: "book", ordinal: 2, title: "Third", trackId: t2.id, startMs: 1500, durationMs: 2500),
        ])
        let prefs = prefs()
        let idle = IosPlayerHub.companion.voiceState(ui: VoiceUi.companion.IDLE)
        let hub = IosPlayerHub.companion.standalone(graph: AudioGraph(), database: db, prefs: prefs, voiceUi: idle)
        hub.playAudiobook(id: "book", at: nil)
        try await waitUntil(5, "the book plays") { hub.ui.value.playing }
        hub.seekToChapter(ordinal: 2)
        try await waitUntil(5, "in the third chapter") {
            if case .book(let b) = onEnum(of: hub.ui.value.now) { return b.chapter.ordinal == 2 }
            return false
        }
        try await Task.sleep(for: .milliseconds(600))
        hub.pause()
        var saved: AudiobookState?
        try await waitUntil(5, "the place is saved on pause") {
            saved = try await db.playbackDao().audiobookState(audiobookId: "book")
            return saved?.trackId == t2.id && (saved?.positionMs ?? 0) >= 1900
        }
        let place = try XCTUnwrap(saved)
        print("PLAYER saved place: \(place.trackId) at \(place.positionMs) ms")
        XCTAssertLessThan(place.positionMs, 3000)
        hub.stop()

        let again = IosPlayerHub.companion.standalone(graph: AudioGraph(), database: db, prefs: prefs, voiceUi: idle)
        again.activate(mode: .audiobook)
        try await waitUntil(5, "the book opens again") {
            if case .book = onEnum(of: again.ui.value.now) { return true }
            return false
        }
        guard case .book(let b) = onEnum(of: again.ui.value.now) else { return XCTFail("a book") }
        XCTAssertEqual(b.chapter.ordinal, 2, "the same chapter")
        // Paused for under 10 s: no auto-rewind, so the place in the chapter is the saved one (less the chapter's start).
        XCTAssertEqual(Double(again.ui.value.positionMs), Double(place.positionMs - 1500), accuracy: 300)
        again.stop()
    }
}
