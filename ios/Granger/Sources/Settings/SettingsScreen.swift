import SwiftUI

/// Settings (Android's, for what I2 has): appearance, text size, Wikipedia language, Local only, history, About.
struct SettingsScreen: View {
    @Environment(AppSettings.self) private var settings
    @Environment(RecentStore.self) private var recent
    @Environment(\.dismiss) private var dismiss
    @State private var clearedSearches = false

    var body: some View {
        @Bindable var settings = settings
        Form {
            Section("Appearance") {
                Picker("Theme", selection: $settings.appearance) {
                    Text("Follow the phone").tag(Appearance.system)
                    Text("Field (light)").tag(Appearance.field)
                    Text("Night (dark)").tag(Appearance.night)
                }
                VStack(alignment: .leading) {
                    Text("Text size")
                    Slider(value: $settings.textScale, in: 0.8...2.0, step: 0.1)
                        .accessibilityValue("\(Int((settings.textScale * 100).rounded())) percent")
                }
            }
            Section("Language") {
                Picker("Wikipedia", selection: $settings.wikiLanguage) {
                    Text("English").tag("en")
                    Text("Español").tag("es")
                }
            }
            Section {
                Toggle("Local only", isOn: $settings.localOnly)
            } footer: {
                Text("Behave exactly as with no signal: show only what this phone holds.")
            }
            Section("History") {
                Button("Clear what you've read", role: .destructive) { recent.clear() }
                    .disabled(recent.entries.isEmpty)
                Button(clearedSearches ? "Recent searches cleared" : "Clear recent searches", role: .destructive) {
                    SearchModel().clearRecent()
                    clearedSearches = true
                }
                .disabled(clearedSearches)
            }
            Section("About") {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Granger").font(Spectral.semibold(22))
                    Text("Your door to knowledge. Always open.").font(Spectral.italic(16))
                    Text("Knowledge is for all who seek it.").font(.footnote).foregroundStyle(.secondary)
                }
                LabeledContent("Version", value: Core.version)
                Link("GPLv3 · source on GitHub", destination: URL(string: "https://github.com/nealt9000/Granger")!)
                Text("© AETHERBOUND LLC").font(.footnote).foregroundStyle(.secondary)
                Link("feedback@opengranger.org", destination: URL(string: "mailto:feedback@opengranger.org")!)
            }
        }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
    }
}
