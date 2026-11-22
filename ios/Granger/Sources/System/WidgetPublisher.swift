import GrangerCoreKit
import UIKit
import WidgetKit

/// Writes the player widget's snapshot to the app group when what it shows changes (Plan I6 Task 8, call 7; Android's
/// WidgetUpdater and WidgetModelStore): the shared WidgetModel in words, and the cover as a small JPEG beside it. Progress
/// alone doesn't redraw the widget, as on Android, so it never ticks. Also redraws Control Center's Local only control
/// when Local only changes.
@MainActor
final class WidgetPublisher {
    private let system: IosSystem
    private var last: WidgetModel?
    private var coverPath: String?

    init(system: IosSystem) {
        self.system = system
        AppSettings.localOnlyChanged = { _ in
            Task { @MainActor in ControlCenter.shared.reloadControls(ofKind: LocalOnlyControlKind.kind) }
        }
    }

    func update(media: PlayerUi, saved: SavedModes) {
        let model = system.widgetModel(ui: media, saved: saved)
        guard system.widgetChanged(last: last, next: model) else { return }
        last = model
        let cover = model.coverPath
        if cover != coverPath {
            coverPath = cover
            writeCover(cover)
        }
        WidgetSnapshot(
            title: model.title.words, subtitle: model.subtitle.words, playing: model.playing,
            secondary: system.secondaryName(model: model), progress: model.progress.map { Double($0.floatValue) },
            empty: model.empty, hasCover: cover != nil && WidgetSnapshot.coverURL.map { FileManager.default.fileExists(atPath: $0.path) } == true
        ).save()
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetSnapshot.kind)
    }

    /// A widget draws small: the cover is scaled to 300 points across before it's copied for the extension.
    private func writeCover(_ path: String?) {
        guard let url = WidgetSnapshot.coverURL else { return }
        guard let path, let image = UIImage(contentsOfFile: path) else { try? FileManager.default.removeItem(at: url); return }
        let side: CGFloat = 300
        let scale = min(1, side / max(image.size.width, image.size.height))
        let size = CGSize(width: image.size.width * scale, height: image.size.height * scale)
        let format = UIGraphicsImageRendererFormat.default()
        format.scale = 1
        let small = UIGraphicsImageRenderer(size: size, format: format).image { _ in image.draw(in: CGRect(origin: .zero, size: size)) }
        try? small.jpegData(compressionQuality: 0.8)?.write(to: url, options: .atomic)
    }
}
