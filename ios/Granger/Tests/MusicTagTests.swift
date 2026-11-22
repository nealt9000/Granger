import GrangerCoreKit
import XCTest
@testable import Granger

/// Plan I6 Task 6's tags spike, kept as a test: Android's tag fixtures (testdata/audio) through the iPhone's reader
/// (AVAsset, TagLib for MP3) and the shared TagParser give the fields Android's TagLib gives. Ogg Vorbis can't be decoded
/// on iOS, so the scan leaves it out.
final class MusicTagTests: XCTestCase {
    /// A copy of the repository's testdata/audio, from GRANGER_TEST_AUDIO (the Simulator can't read the X10 volume the
    /// checkout lives on, so the fixtures are copied beside the others); skipped without it.
    private var fixtures: String {
        get throws {
            guard let dir = ProcessInfo.processInfo.environment["GRANGER_TEST_AUDIO"], !dir.isEmpty else { throw XCTSkip("no GRANGER_TEST_AUDIO") }
            return dir
        }
    }

    /// The reader blocks on AVAsset's loads, as the scan's own thread does: never on the main thread.
    private func off<T>(_ work: @escaping @Sendable () -> T) async -> T {
        await withCheckedContinuation { (c: CheckedContinuation<T, Never>) in
            nonisolated(unsafe) let done = c
            Thread { done.resume(returning: work()) }.start()
        }
    }

    private func parsed(_ name: String, cover: Bool = true) async throws -> AudioTags {
        let path = "\(try fixtures)/\(name)"
        guard FileManager.default.fileExists(atPath: path) else { throw XCTSkip("no \(path)") }
        nonisolated(unsafe) let tags = await off { MusicNativesImpl().fileTags(path: path) }
        XCTAssertNotNil(tags, name)
        if cover { XCTAssertNotNil(tags?.cover, "\(name): the cover") }
        return TagParser.shared.parse(raw: tags?.raw(), fileName: name, durationMsFallback: 0)
    }

    func testEveryFormatGivesAndroidsFields() async throws {
        for name in ["tagged.mp3", "tagged.m4a", "tagged.flac", "tagged.opus"] {
            let t = try await parsed(name)
            XCTAssertEqual(t.title, "Mars, the Bringer of War", name)
            XCTAssertEqual(t.album, "The Planets", name)
            XCTAssertEqual(t.albumArtists, ["Gustav Holst"], name)
            // MP3's ID3v2.4 multi-value TPE1 is why TagLib reads MP3s: AVAsset gives the first artist only.
            XCTAssertEqual(t.artists, ["London Symphony Orchestra", "Gustav Holst"], name)
            XCTAssertEqual(t.composers, ["Gustav Holst"], name)
            XCTAssertEqual(t.genres, ["Classical"], name)
            XCTAssertEqual(t.disc?.intValue, 1, name)
            XCTAssertEqual(t.discTotal?.intValue, 2, name)
            XCTAssertEqual(t.track?.intValue, 3, name)
            XCTAssertEqual(t.trackTotal?.intValue, 10, name)
            XCTAssertEqual(t.date, "1926", name)
            XCTAssertGreaterThan(t.durationMs, 2000, name)
            if name == "tagged.opus" {
                XCTAssertEqual(t.replayGain?.trackGainDb?.floatValue ?? 0, -1.0, accuracy: 0.01, "R128 as ReplayGain")
            } else {
                XCTAssertEqual(t.replayGain?.trackGainDb?.floatValue ?? 0, -6.5, accuracy: 0.01, name)
                XCTAssertEqual(t.replayGain?.albumPeak?.floatValue ?? 0, 0.999969, accuracy: 0.0001, name)
            }
            if name != "tagged.m4a" { XCTAssertEqual(t.originalDate, "1916", name) }
            if name == "tagged.mp3" || name == "tagged.m4a" {
                XCTAssertEqual(t.sort.title, "Mars", name)
                XCTAssertEqual(t.sort.albumArtist, "Holst, Gustav", name)
            }
        }
    }

    func testUntaggedReadsEmptyAndVorbisIsLeftOut() async throws {
        let untagged = try await parsed("untagged.mp3", cover: false)
        XCTAssertEqual(untagged.title, "untagged", "named from its file")
        XCTAssertTrue(untagged.artists.isEmpty)
        let dir = try fixtures
        let results = await off { () -> [Bool] in
            let natives = MusicNativesImpl()
            return [natives.canPlay(url: "\(dir)/tagged.ogg"), natives.canPlay(url: "\(dir)/tagged.opus"),
                    natives.canPlay(url: "\(dir)/tagged.flac"), natives.fileTags(path: "\(dir)/broken.mp3") == nil]
        }
        XCTAssertFalse(results[0], "iOS can't decode Ogg Vorbis")
        XCTAssertTrue(results[1])
        XCTAssertTrue(results[2])
        XCTAssertTrue(results[3], "unparseable")
    }
}
