import SwiftUI

/// Settings (Android's, for what I2 has): appearance, text size, Wikipedia language, Local only, history, About.
struct SettingsScreen: View {
    @Environment(AppSettings.self) private var settings
    @Environment(RecentStore.self) private var recent
    @Environment(\.dismiss) private var dismiss
    @Environment(\.core) private var core
    @Environment(LibraryModel.self) private var library
    @State private var clearedSearches = false
    @State private var savedBytes: Int64 = 0
    @State private var confirmClear = false
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @Environment(CrashLog.self) private var crashes
    @State private var showFeedback = false

    /// Android's summary: what the email is, and how many crash reports are kept.
    private var feedbackSummary: String {
        crashes.count == 0 ? L("An email from your mail app; you see what's included first")
            : L("An email from your mail app · \(crashes.count) crash reports kept on this phone")
    }

    /// The voice that reads, or "Read aloud" (Android's Voice row summary).
    private var voiceSummary: String {
        guard let installed = voice?.voice.installed.voices.value, !installed.isEmpty else { return L("Read aloud") }
        let chosen = voice?.voice.prefs.voiceId.value
        return (installed.first { $0.id.encode() == chosen?.encode() } ?? installed.first)?.title ?? L("Read aloud")
    }

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
                // Settings › Appearance › Language (Android's row; Plan I7 call 2): the app language, then the library's.
                NavigationLink {
                    LanguageSettingsScreen()
                } label: {
                    LabeledContent("Language", value: AppLanguage.name(AppLanguage.code))
                }
                .accessibilityIdentifier("settings-language")
            }
            Section {
                Toggle("Local only", isOn: $settings.localOnly)
            } footer: {
                Text("Behave exactly as with no signal: show only what this phone holds.")
            }
            Section {
                Picker("Offline pictures", selection: $settings.savePictures) {
                    Text("Save pictures").tag(true)
                    Text("Text only (smaller)").tag(false)
                }
            } footer: {
                Text("Whether what you keep, and what's saved for now, carries its pictures.")
            }
            Section {
                Picker("Saved for now", selection: $settings.savedCapMb) {
                    ForEach(AppSettings.capChoices, id: \.self) { Text("\($0) MB").tag($0) }
                }
                .onChange(of: settings.savedCapMb) { core?.library.trimSaved(); refreshSaved() }
                Button("Clear saved for now", role: .destructive) { confirmClear = true }
                    .disabled(savedBytes == 0)
                    .accessibilityIdentifier("clear-saved")
            } footer: {
                Text("What you read online, kept so it opens again without the internet. The oldest goes first past the limit. \(savedBytes / 1_048_576) of \(settings.savedCapMb) MB used.")
            }
            .confirmationDialog("Clear saved for now?", isPresented: $confirmClear, titleVisibility: .visible) {
                Button("Clear", role: .destructive) { core?.library.clearSaved(); refreshSaved() }
            } message: {
                Text("What you read online goes from this phone. What you kept stays.")
            }
            Section {
                NavigationLink {
                    VoiceSettingsScreen()
                } label: {
                    LabeledContent("Voice", value: voiceSummary)
                }
                .accessibilityIdentifier("settings-voice")
                NavigationLink {
                    MediaSettingsScreen()
                } label: {
                    LabeledContent("Music & audiobooks", value: L("Folders on this phone, and the player"))
                }
                .accessibilityIdentifier("settings-media")
            }
            Section("Downloads") {
                Toggle("Download on Wi-Fi only", isOn: $settings.wifiOnly)
                    .onChange(of: settings.wifiOnly) { _, on in
                        Task { try? await core?.library.downloads.setWifiOnly(wifiOnly: on) }
                    }
            }
            Section {
                Toggle("Keep adult topics out of suggestions", isOn: $settings.keepAdultOff)
            } header: {
                Text("Explore")
            } footer: {
                Text("Explore, Home, Timeline and games leave out sexual topics and explicit pictures. Search and the articles you open aren't affected.")
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
            }
            Section {
                // Send feedback (Plan I7 call 3): Android's sheet, then Mail's compose sheet or the share sheet.
                Button { showFeedback = true } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Send feedback").foregroundStyle(palette.onSurface)
                        Text(feedbackSummary).font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                    }
                }
                .accessibilityIdentifier("settings-feedback")
            }
        }
        .task(id: library.version) { refreshSaved() }
        .sheet(isPresented: $showFeedback) { NavigationStack { FeedbackSheet(log: crashes) } }
        .navigationTitle("Settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
    }

    private func refreshSaved() { savedBytes = core?.library.savedBytes() ?? 0 }
}
