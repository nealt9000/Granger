import GrangerCoreKit
import SwiftUI

/// The shared WanderModel (Plan H5a) as SwiftUI sees it.
@Observable
final class WanderHolder {
    private(set) var ui: WanderUi?
    private var owned: Owned<WanderModel>?
    private var watching: Task<Void, Never>?
    var model: WanderModel? { owned?.model }

    @MainActor
    func start(core: Core) {
        guard owned == nil else { return }
        let o = core.library.wander()
        owned = o
        watching = Task { @MainActor [weak self] in for await ui in o.model.ui { self?.ui = ui } }
    }

    deinit { watching?.cancel(); owned?.close() }
}

/// Wander (Plan H5a, in Explore's Ways on): walk Wikipedia by the links in each article's lead. The centre with its
/// picture and summary, the articles around it, the path behind, Somewhere else, and a start search. Opened at an
/// article ([packId]/[path]) or somewhere in a subject or room ([subject]/[facet]), else anywhere.
struct WanderScreen: View {
    var packId: String? = nil
    var path: String? = nil
    var subject: String? = nil
    var facet: String? = nil
    @Environment(\.core) private var core
    @Environment(\.palette) private var palette
    @State private var holder = WanderHolder()
    @State private var query = ""

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                if let ui = holder.ui, let model = holder.model {
                    if !query.isEmpty {
                        ForEach(ui.hits, id: \.path) { hit in
                            Button { model.pick(hit: hit); query = "" } label: {
                                Text(hit.title).font(Spectral.regular(18)).foregroundStyle(palette.onBackground).frame(maxWidth: .infinity, alignment: .leading)
                            }
                            .buttonStyle(.plain)
                            Divider()
                        }
                        if ui.hits.isEmpty { Text("No articles match").foregroundStyle(palette.onSurfaceVariant) }
                    } else {
                        if !ui.trail.isEmpty { trail(ui, model) }
                        if let centre = ui.centre { centreCard(centre, summary: ui.summary) }
                        else if ui.loading { Text("Finding somewhere to start…").foregroundStyle(palette.onSurfaceVariant) }
                        orbit(ui, model)
                        Button("Somewhere else") { model.start() }.buttonStyle(.bordered).accessibilityIdentifier("wander-elsewhere")
                    }
                } else {
                    ProgressView().frame(maxWidth: .infinity, minHeight: 300)
                }
            }
            .padding(16)
        }
        .background(palette.background)
        .navigationTitle("Wander")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $query, prompt: "Start from an article")
        .onChange(of: query) { holder.model?.search(query: query) }
        .task {
            guard let core else { return }
            holder.start(core: core)
            if let packId, let path {
                holder.model?.startFrom(packId: packId, path: path)
            } else if let subject, let start = try? await core.library.wanderStart(subjectId: subject, facetId: facet) {
                holder.model?.startFrom(packId: start.packId, path: start.path)
            } else {
                holder.model?.ensureStarted()
            }
        }
    }

    private func centreCard(_ c: WanderNode, summary: String) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            if let p = c.imagePath, let image = UIImage(contentsOfFile: p) {
                Color.clear.frame(height: 200).overlay { Image(uiImage: image).resizable().scaledToFill() }
                    .clipShape(RoundedRectangle(cornerRadius: 12)).accessibilityHidden(true)
            }
            Text(c.title).font(Spectral.medium(32)).foregroundStyle(palette.onBackground).accessibilityAddTraits(.isHeader)
            if !summary.isEmpty { Text(summary).font(Spectral.regular(18)).foregroundStyle(palette.onBackground).lineSpacing(3) }
            NavigationLink(value: Route.article(packId: c.packId, path: c.path)) {
                Text("Open").font(Spectral.medium(18)).padding(.horizontal, 20).padding(.vertical, 8)
            }
            .buttonStyle(.borderedProminent)
            .accessibilityIdentifier("wander-open")
        }
    }

    @ViewBuilder private func orbit(_ ui: WanderUi, _ model: WanderModel) -> some View {
        if !ui.orbit.isEmpty {
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 10), GridItem(.flexible(), spacing: 10)], spacing: 10) {
                ForEach(ui.orbit, id: \.path) { node in
                    Button { model.go(node: node) } label: {
                        HStack(spacing: 8) {
                            Avatar(path: node.imagePath, size: 40)
                            Text(node.title).font(Spectral.regular(16)).foregroundStyle(palette.onSurface).lineLimit(2).multilineTextAlignment(.leading)
                            Spacer(minLength: 0)
                        }
                        .padding(8)
                        .background(palette.surfaceContainerHigh, in: RoundedRectangle(cornerRadius: 10))
                        .overlay(node.onPhone ? nil : RoundedRectangle(cornerRadius: 10).stroke(palette.outline, style: StrokeStyle(lineWidth: 1, dash: [4])))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(node.onPhone ? L("Go to \(node.title)") : L("\(node.title), not on this phone"))
                }
            }
        }
    }

    private func trail(_ ui: WanderUi, _ model: WanderModel) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                Text("Your path:").font(.caption).foregroundStyle(palette.onSurfaceVariant)
                ForEach(Array(ui.trail.enumerated()), id: \.offset) { i, n in
                    Button(n.title) { model.back(index: Int32(i)) }.font(.caption).foregroundStyle(palette.primary)
                    Text("›").font(.caption).foregroundStyle(palette.outline)
                }
            }
        }
    }
}
