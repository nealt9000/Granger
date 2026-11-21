import GrangerCoreKit
import GrangerCore
import SwiftUI

/// Home's feeds as SwiftUI sees them: the shared HomeFeedsModel (Plan H2, I4 Task 8).
@Observable
final class FeedsHolder {
    private(set) var ui: HomeFeedsUi?
    private var owned: Owned<HomeFeedsModel>?
    private var watching: Task<Void, Never>?
    var model: HomeFeedsModel? { owned?.model }

    @MainActor
    func start(_ core: Core) {
        guard owned == nil else { model?.refresh(); return }
        let o = core.library.homeFeeds()
        owned = o
        watching = Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// One feed on Home: its heading, then its cards in a row (an article opens on its topic page; the day's Mentions at
/// the section). Its menu removes it; Home's + adds one (Add feed).
struct FeedRow: View {
    let f: FeedUi
    let holder: FeedsHolder
    @Environment(\.palette) private var palette

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack {
                Text(f.drawn == nil ? "\(ScreenText.strings[f.feed.kind.labelKey] ?? "") · \(f.scopeName.words)" : f.title.words)
                    .font(Spectral.medium(21)).foregroundStyle(palette.tertiary)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Menu {
                    Button("Remove this feed", role: .destructive) { holder.model?.remove(id: f.feed.id) }
                } label: { Image(systemName: "ellipsis").frame(width: 36, height: 36) }
                .accessibilityLabel("More for \(f.title.words)")
            }
            if let drawn = f.drawn {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(alignment: .top, spacing: 12) {
                        ForEach(Array(drawn.cards.enumerated()), id: \.offset) { _, c in card(c) }
                    }
                }
            } else {
                ProgressView()
            }
        }
        .accessibilityIdentifier("home-feed")
    }

    @ViewBuilder private func card(_ c: GrangerCore.Card) -> some View {
        let label = VStack(alignment: .leading, spacing: 6) {
            Group {
                if let path = c.imagePath, let image = UIImage(contentsOfFile: path) {
                    Image(uiImage: image).resizable().scaledToFill()
                } else {
                    palette.surfaceContainerHigh.overlay(Image(systemName: "doc.text").foregroundStyle(palette.onSurfaceVariant))
                }
            }
            .frame(width: 180, height: 110).clipShape(RoundedRectangle(cornerRadius: 12))
            Text(c.title).font(Spectral.regular(18)).foregroundStyle(palette.onSurface).lineLimit(2)
            if !c.subtitle.isEmpty { Text(c.subtitle).font(.callout).foregroundStyle(palette.onSurfaceVariant).lineLimit(2) }
        }
        .frame(width: 180, alignment: .leading)
        switch onEnum(of: c.target) {
        case let .article(a):
            NavigationLink(value: a.fragment != nil ? Route.read(packId: a.packId, path: a.path, fragment: a.fragment) : Route.article(packId: a.packId, path: a.path)) { label }
                .buttonStyle(.plain)
        default:
            // Books, albums and audiobooks open with the Library and Listen (I5).
            label
        }
    }
}

/// Add feed (Android's AddFeedPanel): where from (the library, a pack, a family), what kind, and for Search, the words.
struct AddFeedSheet: View {
    let holder: FeedsHolder
    @Environment(\.dismiss) private var dismiss
    @Environment(\.palette) private var palette
    @State private var scopeIndex = 0
    @State private var kind: FeedKind = .random
    @State private var query = ""

    var body: some View {
        let choices = holder.ui?.scopeChoices ?? []
        Form {
            Section("From") {
                Picker("From", selection: $scopeIndex) {
                    ForEach(Array(choices.enumerated()), id: \.offset) { i, c in Text(c.label.words).tag(i) }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            Section("Show") {
                Picker("Show", selection: $kind) {
                    ForEach(FeedKind.allCases, id: \.self) { k in Text(ScreenText.strings[k.labelKey] ?? k.name).tag(k) }
                }
                .pickerStyle(.inline)
                .labelsHidden()
                if kind == .search { TextField("Words to find", text: $query) }
            }
        }
        .navigationTitle("Add feed")
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
            ToolbarItem(placement: .confirmationAction) {
                Button("Add") {
                    if scopeIndex < choices.count { holder.model?.add(scope: choices[scopeIndex].scope, kind: kind, query: query) }
                    dismiss()
                }
                .disabled(choices.isEmpty || (kind == .search && query.trimmingCharacters(in: .whitespaces).isEmpty))
            }
        }
    }
}
