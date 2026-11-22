import GrangerCoreKit
import SwiftUI
import UIKit

/// The language iOS runs the app in (Plan I7 call 2): iOS's own per-app language, set in the system Settings (Settings ›
/// Granger › Language), which the String Catalog and `ScreenText` follow.
enum AppLanguage {
    /// "en" or "es".
    static var code: String { Bundle.main.preferredLocalizations.first.map { String($0.prefix(2)) } ?? "en" }

    /// A language named in itself, capitalised: "English", "Español" (Android's `languageName`).
    static func name(_ code: String) -> String {
        let locale = Locale(identifier: code)
        return (locale.localizedString(forLanguageCode: code) ?? code).capitalized(with: locale)
    }

    /// Opens Granger's page in the system Settings, where its Language row is.
    @MainActor static func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
    }
}

/// The shared LanguageSettingsModel (Plan I7 Task 1) as SwiftUI sees it.
@Observable
final class LanguageSettingsHolder {
    private(set) var ui: LanguageSettingsUi?
    private var owned: OwnedViewModel<LanguageSettingsModel>?
    private var watching: Task<Void, Never>?
    var model: LanguageSettingsModel? { owned?.model }

    @MainActor
    func start(_ core: Core) {
        guard owned == nil else { return }
        let o = core.library.languageSettings()
        owned = o
        watching = Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// The App language row (call 2): which language iOS runs Granger in; it opens Granger's page in the system Settings.
struct AppLanguageRow: View {
    @Environment(\.palette) private var palette

    var body: some View {
        Button { AppLanguage.openSettings() } label: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    Text("App language").foregroundStyle(palette.onSurface)
                    Text(AppLanguage.name(AppLanguage.code)).font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                }
                Spacer()
                Image(systemName: "arrow.up.forward.app").foregroundStyle(palette.outline)
            }
        }
        .accessibilityIdentifier("app-language")
        .accessibilityHint(L("Opens Granger in the iPhone's Settings, where its Language row is"))
    }
}

/// Settings › Language (Android's LanguageSettingsScreen): the app language, then "Your library's languages" when the
/// catalog has tiers in more than one: each named in itself, ticked when it's one of the library's, the last one ticked
/// locked, the first the main one with "Main language" under it, Make main on the others.
struct LanguageSettingsScreen: View {
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var holder = LanguageSettingsHolder()

    var body: some View {
        Form {
            Section {
                AppLanguageRow()
            } header: {
                Text("App language")
            } footer: {
                Text("iOS keeps the app's language: Settings › Granger › Language.")
            }
            if let ui = holder.ui, ui.showLibrary, let model = holder.model {
                Section {
                    ForEach(ui.rows, id: \.code) { row in
                        HStack {
                            Button { model.toggle(code: row.code) } label: {
                                HStack(spacing: 12) {
                                    Image(systemName: row.on ? "checkmark.square.fill" : "square")
                                        .foregroundStyle(row.locked ? palette.outline : palette.primary)
                                        .accessibilityHidden(true)
                                    VStack(alignment: .leading, spacing: 2) {
                                        Text(AppLanguage.name(row.code)).foregroundStyle(palette.onSurface)
                                        if let note = row.note {
                                            Text(note.words).font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                                        }
                                    }
                                }
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .disabled(row.locked)
                            .accessibilityAddTraits(row.on ? .isSelected : [])
                            .accessibilityIdentifier("knowledge-\(row.code)")
                            if row.canMakeMain {
                                Button(ui.makeMainLabel.words) { model.makeMain(code: row.code) }
                                    .buttonStyle(.borderless)
                                    .accessibilityIdentifier("make-main-\(row.code)")
                            }
                        }
                    }
                } header: {
                    Text(ui.heading.words)
                } footer: {
                    Text(ui.blurb.words)
                }
            }
        }
        .scrollContentBackground(.hidden)
        .background(palette.background)
        .navigationTitle("Language")
        .navigationBarTitleDisplayMode(.inline)
        .task { if let core { holder.start(core) } }
    }
}
