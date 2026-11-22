import CarPlay
import GrangerCoreKit
import UIKit

/// CarPlay audio (Plan I6 Task 8, call 6): Android Auto's browse tree, the shared MediaBrowse, drawn in CarPlay's list
/// templates: a tab each for Recent, Music (Albums, Artists, Playlists) and Audiobooks, folders pushed as lists, and a
/// playable row played through the hub (as Auto plays by id) before CarPlay's own Now Playing, which reads the lock
/// screen's Now Playing and remote commands (VoiceCenter).
///
/// The scene is declared in Info.plist (`CPTemplateApplicationSceneSessionRoleApplication`). CarPlay shows the app only
/// with Apple's `com.apple.developer.carplay-audio` entitlement, which Neal requests for I8.
final class CarPlayScene: UIResponder, CPTemplateApplicationSceneDelegate {
    private var interface: CPInterfaceController?
    private var browse: MediaBrowse?

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didConnect interfaceController: CPInterfaceController) {
        interface = interfaceController
        interfaceController.setRootTemplate(makeRoot(), animated: false, completion: nil)
    }

    /// The tab bar: Recent, Music and Audiobooks, each list filled from the shared tree (the unit tests build it too).
    func makeRoot() -> CPTabBarTemplate {
        MainActor.assumeIsolated {
            if let center = VoiceCenter.shared { browse = center.system.browse { key in ScreenText.strings[key] ?? key } }
        }
        let tabs = [MediaBrowse.companion.RECENT, MediaBrowse.companion.MUSIC, MediaBrowse.companion.AUDIOBOOKS].map { id -> CPListTemplate in
            let list = CPListTemplate(title: CarPlayScene.tabTitle(id), sections: [])
            list.tabTitle = CarPlayScene.tabTitle(id)
            list.tabImage = UIImage(systemName: CarPlayScene.tabIcon(id))
            fill(list, id)
            return list
        }
        return CPTabBarTemplate(templates: tabs)
    }

    func templateApplicationScene(_ scene: CPTemplateApplicationScene, didDisconnectInterfaceController interfaceController: CPInterfaceController) {
        interface = nil
        browse = nil
    }

    private static func tabTitle(_ id: String) -> String {
        switch id {
        case MediaBrowse.companion.RECENT: return ScreenText.strings["media_auto_recent"] ?? L("Recent")
        case MediaBrowse.companion.MUSIC: return ScreenText.strings["media_auto_music"] ?? L("Music")
        default: return ScreenText.strings["media_auto_audiobooks"] ?? L("Audiobooks")
        }
    }

    private static func tabIcon(_ id: String) -> String {
        switch id {
        case MediaBrowse.companion.RECENT: return "clock"
        case MediaBrowse.companion.MUSIC: return "music.note"
        default: return "book"
        }
    }

    /// [id]'s children into [list], read off the main thread.
    private func fill(_ list: CPListTemplate, _ id: String) {
        guard let browse else { return }
        Task { @MainActor in
            let nodes = (try? await browse.children(parentId: id, page: 0, pageSize: Int32(CPListTemplate.maximumItemCount))) ?? nil
            let items = (nodes ?? []).map(self.item)
            list.updateSections([CPListSection(items: items)])
        }
    }

    private func item(_ node: BrowseNode) -> CPListItem {
        let image = node.coverPath.flatMap { UIImage(contentsOfFile: $0) }
        let row = CPListItem(text: node.title, detailText: node.subtitle, image: image ?? UIImage(systemName: CarPlayScene.icon(node.kind)))
        row.accessoryType = node.browsable ? .disclosureIndicator : .none
        let id = node.id
        let title = node.title
        let browsable = node.browsable
        let playable = node.playable
        row.handler = { [weak self] _, done in
            guard let self else { return done() }
            if browsable {
                let list = CPListTemplate(title: title, sections: [])
                self.fill(list, id)
                self.interface?.pushTemplate(list, animated: true) { _, _ in done() }
            } else if playable, let browse = self.browse {
                Task { @MainActor in
                    let played = (try? await browse.play(id: id, searchQuery: nil))?.boolValue ?? false
                    if played { self.showNowPlaying() }
                    done()
                }
            } else {
                done()
            }
        }
        return row
    }

    private func showNowPlaying() {
        guard let interface else { return }
        let now = CPNowPlayingTemplate.shared
        if interface.topTemplate === now { return }
        interface.pushTemplate(now, animated: true, completion: nil)
    }

    private static func icon(_ kind: BrowseKind) -> String {
        switch kind {
        case .audioBook, .folderAudioBooks: return "book"
        case .artist, .folderArtists: return "music.mic"
        case .playlist, .folderPlaylists: return "music.note.list"
        case .album, .folderAlbums: return "square.stack"
        default: return "music.note"
        }
    }
}
