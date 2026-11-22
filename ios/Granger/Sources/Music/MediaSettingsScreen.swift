import GrangerCoreKit
import MediaPlayer
import SwiftUI
import UniformTypeIdentifiers

/// Settings › Music & audiobooks (Android's MediaSettingsSection, Plan I6 Task 6), in Android's words where the iPhone
/// works the same: Music library access in place of the audio permission, picked folders (security-scoped bookmarks in
/// place of SAF grants), importing from Files into Granger's own Music folder, Rescan now, ReplayGain, and the skip
/// settings once there's an audiobook. Not here, as on Android: "Show in other apps" (Granger's Documents is always in
/// the Files app) and "Play when headphones connect" (iOS has no such hook; call 10).
struct MediaSettingsScreen: View {
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @State private var replayGain: ReplayGainMode = .auto
    @State private var skipBack = 30
    @State private var skipForward = 30
    @State private var audiobooks = false
    @State private var picking: Pick?
    @State private var importing = false
    @State private var imported: Int?

    private enum Pick: Identifiable { case music, audiobooks; var id: Self { self } }

    var body: some View {
        Form {
            if let center = voice?.music {
                access(center)
                folders(center)
                rescan(center)
                player
            }
        }
        .navigationTitle(audiobooks ? "Music & audiobooks" : "Music")
        .navigationBarTitleDisplayMode(.inline)
        .task {
            guard let music = voice?.music.music else { return }
            replayGain = music.replayGain()
            skipBack = Int(music.skipBackS())
            skipForward = Int(music.skipForwardS())
            for await on in music.hasAudiobooks { audiobooks = on.boolValue }
        }
        .fileImporter(isPresented: Binding(get: { picking != nil }, set: { if !$0 { picking = nil } }), allowedContentTypes: [.folder]) { result in
            let wanted = picking
            if case let .success(url) = result { voice?.music.addFolder(url, audiobooks: wanted == .audiobooks) }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.audio], allowsMultipleSelection: true) { result in
            guard case let .success(urls) = result, let center = voice?.music else { return }
            Task { imported = await center.importFiles(urls) }
        }
        .accessibilityIdentifier("media-settings")
    }

    // MARK: Music library access

    @ViewBuilder private func access(_ center: MusicCenter) -> some View {
        Section {
            AdaptiveStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Find music and audiobooks in the Music library")
                    Text(accessLine(center.libraryStatus)).font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                }
                Spacer()
                switch center.libraryStatus {
                case .authorized: Text("Allowed").foregroundStyle(palette.onSurfaceVariant)
                case .notDetermined: Button("Allow access") { center.requestLibraryAccess() }.buttonStyle(.borderedProminent)
                default: Button("Open app settings") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
                }
            }
            .accessibilityIdentifier("media-access")
            if center.libraryStatus == .authorized && center.leftOut > 0 {
                Text(center.leftOut == 1
                     ? "1 song in the Music library can't play here: it's protected or only in the cloud."
                     : "\(center.leftOut) songs in the Music library can't play here: they're protected or only in the cloud.")
                    .font(.footnote).foregroundStyle(palette.onSurfaceVariant)
            }
        }
    }

    private func accessLine(_ status: MPMediaLibraryAuthorizationStatus) -> String {
        switch status {
        case .authorized: "Granger reads them where they are and never changes them."
        case .notDetermined: "Not allowed. Granger works without it, and folders you add below still work."
        default: "Not allowed. iOS won't ask again: allow Media & Apple Music in Granger's settings."
        }
    }

    // MARK: Folders and imports

    @ViewBuilder private func folders(_ center: MusicCenter) -> some View {
        Section {
            VStack(alignment: .leading, spacing: 8) {
                Text("Add a folder")
                Text("For music in Files, on this iPhone or elsewhere. Granger only reads them.").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                AdaptiveStack(spacing: 8) {
                    Button("Music folder") { picking = .music }.buttonStyle(.bordered)
                    Button("Audiobooks folder") { picking = .audiobooks }.buttonStyle(.bordered)
                }
            }
            ForEach(center.folders) { f in
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(f.name)
                        Text(f.audiobooks ? "Audiobooks folder" : "Music folder").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                    }
                    Spacer()
                    Button { center.removeFolder(f) } label: { Image(systemName: "xmark") }
                        .buttonStyle(.borderless)
                        .accessibilityLabel("Remove \(f.name)")
                }
                .padding(.leading, 16)
                .accessibilityIdentifier("media-folder-\(f.name)")
            }
            VStack(alignment: .leading, spacing: 8) {
                Button("Import from Files") { importing = true }.accessibilityIdentifier("media-import")
                Text(imported.map { $0 == 1 ? "Imported 1 file." : "Imported \($0) files." }
                     ?? "Copies songs into On My iPhone › Granger › Music, where Files shows them.")
                    .font(.footnote).foregroundStyle(palette.onSurfaceVariant)
            }
        }
    }

    // MARK: Rescan now

    private func rescan(_ center: MusicCenter) -> some View {
        Section {
            Button { center.rescan() } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Rescan now").foregroundStyle(palette.onSurface)
                        Text(rescanLine(center.scan)).font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                            .accessibilityIdentifier("media-rescan-state")
                    }
                    Spacer()
                    if center.scan == .running { ProgressView() }
                }
            }
            .disabled(center.scan == .running)
            .accessibilityIdentifier("media-rescan")
        }
    }

    private func rescanLine(_ scan: MusicCenter.Rescan) -> String {
        switch scan {
        case .idle: "Granger rescans on start and when the Music library changes."
        case .running: "Scanning…"
        case let .done(songs, books): "Found \(songs == 1 ? "1 song" : "\(songs) songs") and \(books == 1 ? "1 audiobook" : "\(books) audiobooks")."
        case .failed: "The scan didn't finish. Try again."
        }
    }

    // MARK: The player

    @ViewBuilder private var player: some View {
        Section {
            VStack(alignment: .leading, spacing: 6) {
                Text("ReplayGain")
                Text("Evens out loudness. Auto uses album gain when an album plays in order, else track gain.").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                Picker("ReplayGain", selection: Binding(get: { replayGain }, set: { replayGain = $0; voice?.music.music.setReplayGain(mode: $0) })) {
                    Text("Off").tag(ReplayGainMode.off)
                    Text("Track").tag(ReplayGainMode.track)
                    Text("Album").tag(ReplayGainMode.album)
                    Text("Auto").tag(ReplayGainMode.auto)
                }
                .pickerStyle(.segmented)
                .labelsHidden()
            }
            // Audiobook-only: hidden until there's an audiobook (Plan U2 Decision 7).
            if audiobooks {
                skipRow("Skip back", value: skipBack) { skipBack = $0; voice?.music.music.setSkipBack(seconds: Int32($0)) }
                skipRow("Skip forward", value: skipForward) { skipForward = $0; voice?.music.music.setSkipForward(seconds: Int32($0)) }
            }
        }
    }

    private func skipRow(_ title: String, value: Int, set: @escaping (Int) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
            Text("Audiobooks").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
            Picker(title, selection: Binding(get: { value }, set: set)) {
                ForEach(IosMusic.companion.SKIP_CHOICES.map(\.intValue), id: \.self) { Text("\($0) s").tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()
        }
    }
}
