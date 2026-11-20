import GrangerCoreKit
import SwiftUI

/// An article's mark (spec §3, Plan O2 Task 4): a filled dot for on this phone, a ring for saved for now, nothing for
/// online only (the common case). 12 pt in the accent colour, as Android's; VoiceOver reads its state.
struct MarkView: View {
    let lang: String
    let title: String
    @Environment(\.core) private var core
    @Environment(LibraryModel.self) private var library
    @State private var mark: Mark = .none

    var body: some View {
        MarkDot(mark: mark)
            .task(id: "\(lang):\(title):\(library.version)") {
                guard let core else { return }
                mark = (try? await core.library.mark(lang: lang, title: title)) ?? .none
            }
    }
}

/// The mark itself, for a known state.
struct MarkDot: View {
    let mark: Mark
    @Environment(\.palette) private var palette

    var body: some View {
        switch mark {
        case .onPhone, .onPhoneShorter:
            Circle().fill(palette.primary).overlay(Circle().stroke(palette.background, lineWidth: 1.5))
                .frame(width: 12, height: 12)
                .accessibilityLabel(mark == .onPhone ? "On this phone" : "On this phone (shorter)")
                .accessibilityIdentifier("mark-on-phone")
        case .saved:
            Circle().fill(palette.background).overlay(Circle().stroke(palette.primary, lineWidth: 2))
                .frame(width: 12, height: 12)
                .accessibilityLabel("Saved for now")
                .accessibilityIdentifier("mark-saved")
        default:
            // Not EmptyView: a view that isn't drawn never runs its task, and the mark would never be asked.
            Color.clear.frame(width: 0, height: 12).accessibilityHidden(true)
        }
    }
}

/// The topic page's Keep button for an online article (Plan O2 Task 5): "Keep · 180 KB" (or "Keep text" under Text
/// only), then "Kept · Remove". Keeping needs the page: with no signal and nothing saved it says so.
struct KeepButton: View {
    let lang: String
    let title: String
    @Environment(\.core) private var core
    @Environment(LibraryModel.self) private var library
    @Environment(AppSettings.self) private var settings
    @State private var kept: KeptArticle?
    @State private var size: Int64?
    @State private var working = false
    @State private var failed = false

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Button(action: act) {
                Text(label).font(Spectral.medium(17)).frame(maxWidth: .infinity).padding(.vertical, 4)
            }
            .buttonStyle(.bordered)
            .disabled(working)
            .accessibilityIdentifier(kept == nil ? "keep" : "keep-remove")
            if failed {
                Text("Couldn't keep it. Try again when online.").font(.footnote).foregroundStyle(.secondary)
            }
        }
        .task(id: "\(lang):\(title):\(library.version):\(settings.savePictures)") { refresh() }
    }

    private var label: String {
        if working { return "Keeping…" }
        if let kept { return kept.withPictures ? "Kept · Remove" : "Kept text · Remove" }
        let word = settings.savePictures ? "Keep" : "Keep text"
        guard let size else { return word }
        return "\(word) · \(ByteCountFormatter.string(fromByteCount: size, countStyle: .file))"
    }

    private func refresh() {
        guard let library = core?.library else { return }
        kept = library.keptState(lang: lang, title: title)
        size = library.keepSize(lang: lang, title: title)?.int64Value
    }

    private func act() {
        guard let library = core?.library else { return }
        working = true
        failed = false
        Task { @MainActor in
            if kept != nil {
                try? await library.remove(lang: lang, title: title)
            } else {
                failed = !(((try? await library.keep(lang: lang, title: title))?.boolValue) ?? false)
            }
            working = false
            refresh()
        }
    }
}

/// Keep and Remove from the reader's bar.
struct ReaderKeepMenu: View {
    let lang: String
    let title: String
    @Environment(\.core) private var core
    @Environment(LibraryModel.self) private var library
    @Environment(AppSettings.self) private var settings
    @State private var kept: KeptArticle?

    var body: some View {
        Menu {
            if let kept {
                Button(kept.withPictures ? "Kept · Remove" : "Kept text · Remove", systemImage: "trash") {
                    Task { @MainActor in try? await core?.library.remove(lang: lang, title: title) }
                }
            } else {
                Button(settings.savePictures ? "Keep" : "Keep text", systemImage: "arrow.down.circle") {
                    Task { @MainActor in _ = try? await core?.library.keep(lang: lang, title: title) }
                }
            }
        } label: {
            Image(systemName: kept == nil ? "arrow.down.circle" : "checkmark.circle.fill")
        }
        .accessibilityLabel(kept == nil ? "Keep" : "Kept")
        .task(id: "\(lang):\(title):\(library.version)") { kept = core?.library.keptState(lang: lang, title: title) }
    }
}

/// A one-time hint (Android's Hints): shown until it's closed.
struct HintCard: View {
    let id: String
    let text: String
    @AppStorage private var seen: Bool
    @Environment(\.palette) private var palette

    init(id: String, text: String) {
        self.id = id
        self.text = text
        _seen = AppStorage(wrappedValue: false, "hint.\(id)")
    }

    var body: some View {
        if !seen {
            HStack(alignment: .top, spacing: 12) {
                Text(text).font(Spectral.regular(17)).foregroundStyle(palette.onSurface)
                Spacer(minLength: 0)
                Button { seen = true } label: { Image(systemName: "xmark") }
                    .accessibilityLabel("Close")
            }
            .padding(14)
            .background(palette.surfaceContainer, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

/// Local only's banner (Plan O3): what's shown and how to turn it off, with the hint about greyed things.
struct LocalOnlyBanner: View {
    @Environment(AppSettings.self) private var settings
    @Environment(\.palette) private var palette

    var body: some View {
        if settings.localOnly {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Local only. Showing what this phone holds.").font(Spectral.regular(17)).foregroundStyle(palette.onSurface)
                    Spacer(minLength: 8)
                    Button("Turn off") { settings.localOnly = false }.font(Spectral.medium(17)).accessibilityIdentifier("local-only-off")
                }
                HintCard(id: "offline", text: "Greyed things need the internet.")
            }
            .padding(14)
            .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 12))
        }
    }
}

extension View {
    /// Greys out something that needs the internet while the gate is shut (Plan O3's greyed tiles), and says so.
    func needsInternet(_ shut: Bool) -> some View {
        opacity(shut ? 0.45 : 1)
            .accessibilityHint(shut ? "Needs internet" : "")
    }
}
