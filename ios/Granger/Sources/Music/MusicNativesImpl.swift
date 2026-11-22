import AVFoundation
import GrangerCoreKit
import MediaPlayer
import UIKit

/// What the shared music scan asks of the phone (Plan I6 Task 6): tags, whether a file plays, and the Music library.
///
/// Tags, per the spike (call 3): AVAsset reads every field Android uses from M4A/M4B, FLAC and Opus, so those go
/// through it, turned into TagLib's PropertyMap keys for the shared TagParser. MP3s go through TagLib (`GTagLib`),
/// because AVAsset keeps only the first value of an ID3v2.4 multi-value frame. Chapters are the shared ChapterReader's.
/// Ogg Vorbis can't be decoded on iOS at all, so `canPlay` leaves it out of the scan.
///
/// Every call blocks (AVAsset's loads are waited for), and the scan calls them off the main thread.
final class MusicNativesImpl: NSObject, MusicNatives {
    private let lock = NSLock()
    /// The Music library's items from the last listing, by persistent id, for their tags.
    private var items: [String: MPMediaItem] = [:]

    // MARK: Files

    func fileTags(path: String) -> NativeTags? {
        let url = URL(fileURLWithPath: path)
        let audio = try? AVAudioFile(forReading: url)
        let durationMs = audio.map { Int64(Double($0.length) * 1000 / $0.fileFormat.sampleRate) } ?? 0
        let sampleRate = Int32(audio?.fileFormat.sampleRate ?? 0)
        let channels = Int32(audio?.fileFormat.channelCount ?? 0)
        let size = (try? FileManager.default.attributesOfItem(atPath: path)[.size] as? NSNumber)?.int64Value ?? 0
        let bitrate = durationMs > 0 ? Int32(size * 8 / durationMs) : 0
        if url.pathExtension.lowercased() == "mp3" {
            guard let t = GTagLib.readPath(path) else { return nil }
            return NativeTags(properties: t.properties, durationMs: t.lengthMs > 0 ? Int64(t.lengthMs) : durationMs, sampleRate: Int32(t.sampleRate),
                              bitrateKbps: Int32(t.bitrate), channels: Int32(t.channels), cover: t.cover)
        }
        guard let (properties, cover) = Self.assetTags(url) else { return nil }
        return NativeTags(properties: properties, durationMs: durationMs, sampleRate: sampleRate, bitrateKbps: bitrate, channels: channels, cover: cover)
    }

    func canPlay(url: String) -> Bool {
        let u = url.contains("://") ? URL(string: url) : URL(fileURLWithPath: url)
        guard let u else { return false }
        return (try? AVAudioFile(forReading: u)) != nil
    }

    /// AVAsset's metadata as TagLib's PropertyMap and the first picture; nil when the asset can't be read.
    static func assetTags(_ url: URL) -> ([String: [String]], Data?)? {
        let asset = AVURLAsset(url: url)
        let done = DispatchSemaphore(value: 0)
        nonisolated(unsafe) var result: ([String: [String]], Data?)?
        Task.detached {
            defer { done.signal() }
            guard let formats = try? await asset.load(.availableMetadataFormats) else { return }
            var properties: [String: [String]] = [:]
            var cover: Data?
            for format in formats {
                guard let items = try? await asset.loadMetadata(for: format) else { continue }
                for item in items {
                    let id = item.identifier?.rawValue ?? ""
                    if Self.pictureIds.contains(id) {
                        if cover == nil { cover = try? await item.load(.dataValue) }
                        continue
                    }
                    let extra = (try? await item.load(.extraAttributes)) ?? [:]
                    guard let key = Self.propertyKey(id, info: extra[AVMetadataExtraAttributeKey(rawValue: "info")] as? String) else { continue }
                    let value: String?
                    if id == "itsk/trkn" || id == "itsk/disk" {
                        value = (try? await item.load(.dataValue)).flatMap(Self.numberPair)
                    } else {
                        value = try? await item.load(.stringValue)
                    }
                    if let value, !value.isEmpty { properties[key, default: []].append(value) }
                }
            }
            result = (properties, cover)
        }
        done.wait()
        return result
    }

    private static let pictureIds: Set<String> = ["id3/APIC", "itsk/covr", "vorb/METADATA_BLOCK_PICTURE"]

    /// iTunes' and ID3's keys as TagLib names them in its PropertyMap; a Vorbis comment keeps its own name.
    private static let names: [String: String] = [
        "itsk/%A9nam": "TITLE", "itsk/%A9ART": "ARTIST", "itsk/aART": "ALBUMARTIST", "itsk/%A9alb": "ALBUM",
        "itsk/%A9wrt": "COMPOSER", "itsk/%A9gen": "GENRE", "itsk/trkn": "TRACKNUMBER", "itsk/disk": "DISCNUMBER",
        "itsk/%A9day": "DATE", "itsk/sonm": "TITLESORT", "itsk/soal": "ALBUMSORT", "itsk/soar": "ARTISTSORT", "itsk/soaa": "ALBUMARTISTSORT",
        "id3/TIT2": "TITLE", "id3/TPE1": "ARTIST", "id3/TPE2": "ALBUMARTIST", "id3/TALB": "ALBUM", "id3/TCOM": "COMPOSER",
        "id3/TCON": "GENRE", "id3/TRCK": "TRACKNUMBER", "id3/TPOS": "DISCNUMBER", "id3/TDRC": "DATE", "id3/TYER": "DATE",
        "id3/TDOR": "ORIGINALDATE", "id3/TSOT": "TITLESORT", "id3/TSOA": "ALBUMSORT", "id3/TSOP": "ARTISTSORT", "id3/TSO2": "ALBUMARTISTSORT",
    ]

    /// TagLib's names for iTunes' free-form keys it knows (the rest keep theirs, in capitals).
    private static let freeForm: [String: String] = ["MUSICBRAINZ ALBUM TYPE": "RELEASETYPE", "MUSICBRAINZ ALBUM ARTIST": "ALBUMARTIST"]

    static func propertyKey(_ id: String, info: String?) -> String? {
        if let known = names[id] { return known }
        if id.hasPrefix("vorb/") {
            let name = String(id.dropFirst(5)).uppercased()
            return name == "VENDOR" ? nil : name
        }
        if id.hasPrefix("itlk/com.apple.iTunes.") {
            let name = String(id.dropFirst("itlk/com.apple.iTunes.".count)).uppercased()
            return freeForm[name] ?? name
        }
        if id == "id3/TXXX", let info, !info.isEmpty { return info.uppercased() }
        return nil
    }

    /// iTunes' `trkn`/`disk` atom: two big-endian 16-bit numbers after two zero bytes ("3/10"; "3" with no total).
    static func numberPair(_ data: Data) -> String? {
        let b = [UInt8](data)
        guard b.count >= 4 else { return nil }
        let n = Int(b[2]) << 8 | Int(b[3])
        let total = b.count >= 6 ? Int(b[4]) << 8 | Int(b[5]) : 0
        guard n > 0 else { return nil }
        return total > 0 ? "\(n)/\(total)" : "\(n)"
    }

    // MARK: The Music library

    func libraryAllowed() -> Bool { MPMediaLibrary.authorizationStatus() == .authorized }

    func librarySongs() -> LibrarySongs? {
        guard libraryAllowed(), let found = MPMediaQuery.songs().items else { return nil }
        var playable: [LibrarySong] = []
        var leftOut: Int32 = 0
        var byId: [String: MPMediaItem] = [:]
        for item in found {
            // No asset URL: protected (DRM) or only in the cloud, so the phone can't play it here.
            guard let url = item.assetURL, !item.hasProtectedAsset, !item.isCloudItem else { leftOut += 1; continue }
            let id = String(item.persistentID)
            byId[id] = item
            playable.append(LibrarySong(
                id: id, url: url.absoluteString, title: item.title ?? url.lastPathComponent,
                addedMs: Int64(item.dateAdded.timeIntervalSince1970 * 1000), durationMs: Int64(item.playbackDuration * 1000),
                audiobook: item.mediaType.contains(.audioBook)
            ))
        }
        lock.lock(); items = byId; lock.unlock()
        return LibrarySongs(playable: playable, leftOut: leftOut)
    }

    func libraryTags(id: String) -> NativeTags? {
        lock.lock(); let cached = items[id]; lock.unlock()
        let item = cached ?? {
            let query = MPMediaQuery.songs()
            query.addFilterPredicate(MPMediaPropertyPredicate(value: UInt64(id) ?? 0, forProperty: MPMediaItemPropertyPersistentID))
            return query.items?.first
        }()
        guard let item else { return nil }
        var p: [String: [String]] = [:]
        func put(_ key: String, _ value: String?) { if let value, !value.isEmpty { p[key] = [value] } }
        put("TITLE", item.title)
        put("ARTIST", item.artist)
        put("ALBUMARTIST", item.albumArtist)
        put("ALBUM", item.albumTitle)
        put("GENRE", item.genre)
        put("COMPOSER", item.composer)
        if item.albumTrackNumber > 0 { put("TRACKNUMBER", "\(item.albumTrackNumber)") }
        if item.discNumber > 0 { put("DISCNUMBER", "\(item.discNumber)") }
        if let date = item.releaseDate { put("DATE", String(Calendar(identifier: .gregorian).component(.year, from: date))) }
        let cover = item.artwork?.image(at: CGSize(width: 600, height: 600))?.jpegData(compressionQuality: 0.85)
        return NativeTags(properties: p, durationMs: Int64(item.playbackDuration * 1000), sampleRate: 0, bitrateKbps: 0, channels: 0, cover: cover)
    }
}
