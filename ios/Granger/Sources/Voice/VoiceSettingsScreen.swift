import GrangerCoreKit
import SwiftUI

/// The shared VoiceSettingsModel as SwiftUI sees it.
@Observable
final class VoiceSettingsHolder {
    private(set) var ui: VoiceSettingsUi?
    private var owned: OwnedViewModel<VoiceSettingsModel>?
    private var watching: Task<Void, Never>?
    var model: VoiceSettingsModel? { owned?.model }

    @MainActor
    func start(_ make: () -> OwnedViewModel<VoiceSettingsModel>) {
        guard owned == nil else { return }
        let o = make()
        owned = o
        watching = Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// Settings → Voice (Android's VoiceSettingsScreen, Plan V1): the voice that reads aloud (the Granger voices, and the
/// system voice in use; the other system voices behind one row), the catalog's Granger voices to get or remove with
/// their licence, through the shared download queue, the speed, and whether the sentence being read is marked.
struct VoiceSettingsScreen: View {
    @Environment(\.voice) private var voice
    @Environment(\.palette) private var palette
    @State private var holder = VoiceSettingsHolder()
    @State private var showMore = false
    @State private var speed: Double?

    var body: some View {
        Form {
            if let ui = holder.ui, let model = holder.model {
                let pair = ui.split()
                let shown = pair.first as? [Voice] ?? []
                let more = pair.second as? [Voice] ?? []
                Section(header: Text("Reads with")) {
                    if ui.voices.isEmpty {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("No offline voice on this phone")
                            Text("Get the Granger voice below, or add a voice in the iPhone's Settings › Accessibility › Spoken Content.")
                                .font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                        }
                    }
                    ForEach(showMore ? shown + more : shown, id: \.self) { v in voiceRow(v, ui: ui, model: model) }
                    if !more.isEmpty && !showMore {
                        Button("System voices (\(more.count))") { showMore = true }.accessibilityIdentifier("voice-more")
                    }
                }
                Section(header: Text("Granger voices")) {
                    if ui.catalog.isEmpty { Text("The catalog lists no voices yet.").foregroundStyle(palette.onSurfaceVariant) }
                    ForEach(ui.catalog, id: \.entry.id) { row in catalogRow(row, model: model) }
                }
                Section(header: Text("Speed")) {
                    let value = speed ?? Double(ui.speed)
                    HStack {
                        Slider(value: Binding(get: { value }, set: { speed = ($0 * 10).rounded() / 10 }), in: 0.7...2.0, step: 0.1) { editing in
                            if !editing, let s = speed { model.setSpeed(speed: Float(s)); speed = nil }
                        }
                        .accessibilityLabel("Speed")
                        .accessibilityIdentifier("voice-speed")
                        Text(String(format: "%.1f×", value)).monospacedDigit()
                    }
                }
                Section {
                    Toggle("Mark the sentence being read", isOn: Binding(get: { ui.markSentence }, set: { model.setMarkSentence(on: $0) }))
                        .accessibilityIdentifier("voice-mark")
                }
                if let n = ui.notice {
                    Section { HStack { Text(n.words).foregroundStyle(palette.error); Spacer(); Button("OK") { model.dismissNotice() } } }
                }
            } else {
                ProgressView()
            }
        }
        .navigationTitle("Voice")
        .navigationBarTitleDisplayMode(.inline)
        .task { if let voice { holder.start { voice.voice.settings() } } }
    }

    private func voiceRow(_ v: Voice, ui: VoiceSettingsUi, model: VoiceSettingsModel) -> some View {
        let chosen = (ui.chosen ?? ui.voices.first?.id)?.encode() == v.id.encode()
        let granger = v.id is VoiceIdGranger
        return Button { model.choose(id: v.id) } label: {
            HStack {
                Image(systemName: chosen ? "largecircle.fill.circle" : "circle").foregroundStyle(chosen ? palette.primary : palette.onSurfaceVariant)
                VStack(alignment: .leading, spacing: 2) {
                    Text(v.title).foregroundStyle(palette.onSurface)
                    Text(granger ? "Granger voice · \(v.language)" : "System voice · \(v.language)").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                }
            }
        }
        .accessibilityAddTraits(chosen ? .isSelected : [])
        .accessibilityIdentifier("voice-row")
    }

    private func catalogRow(_ row: CatalogVoiceRow, model: VoiceSettingsModel) -> some View {
        let e = row.entry
        return VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .top) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(e.title)
                    Text("\(GrangerCoreEntry.shared.bytesText(bytes: e.sizeBytes)) download · \(e.license)").font(.footnote).foregroundStyle(palette.onSurfaceVariant)
                    if !e.attribution.isEmpty { Text(e.attribution).font(.caption2).foregroundStyle(palette.onSurfaceVariant) }
                    if let error = row.error { Text("Download failed: \(error)").font(.footnote).foregroundStyle(palette.error) }
                }
                Spacer()
                if row.installed {
                    Button("Remove", role: .destructive) { model.remove(entry: e) }.buttonStyle(.borderless).accessibilityIdentifier("voice-remove")
                } else if row.progress != nil || row.queued {
                    Button("Cancel") { model.cancel(entry: e) }.buttonStyle(.borderless)
                } else if row.error != nil {
                    Button("Retry") { model.retry(entry: e) }.buttonStyle(.borderless)
                } else {
                    Button("Get") { model.get(entry: e) }.buttonStyle(.borderless).accessibilityIdentifier("voice-get")
                }
            }
            if let p = row.progress { ProgressView(value: Double(p.floatValue)) }
        }
    }
}
