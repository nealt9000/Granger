import GrangerCoreKit
import SwiftUI

/// What's being moved (Android's OrganizeDialogs, Plan I5 Task 6): a book, a pack, or the whole collection to another
/// media type.
enum MoveTarget: Identifiable {
    case book(Book)
    case pack(Pack)
    /// The collection, from the media type it's under now.
    case collection(String)

    var id: String {
        switch self {
        case let .book(b): "book:\(b.id)"
        case let .pack(p): "pack:\(p.id)"
        case let .collection(t): "collection:\(t)"
        }
    }

    /// The media type a whole collection moves from; nil for a book or pack.
    var collectionFrom: String? { if case let .collection(t) = self { t } else { nil } }

    func move(_ model: CollectionModel?, _ to: String) {
        switch self {
        case let .book(b): model?.moveBook(book: b, toCollectionId: to)
        case let .pack(p): model?.movePack(pack: p, toCollectionId: to)
        case .collection: model?.moveCollection(toMediaTypeId: to)
        }
    }

    func moveToNew(_ model: CollectionModel?, _ mediaTypeId: String, _ name: String) {
        switch self {
        case let .book(b): model?.moveBookToNewCollection(book: b, mediaTypeId: mediaTypeId, name: name)
        case let .pack(p): model?.movePackToNewCollection(pack: p, mediaTypeId: mediaTypeId, name: name)
        case .collection: break
        }
    }

    /// To a new media type: for a book or pack its first collection takes the same name, as Android's two fields
    /// default to one; for the collection the type is all that's asked.
    func moveToNewType(_ model: CollectionModel?, _ name: String) {
        switch self {
        case let .book(b): model?.moveBookToNewMediaType(book: b, mediaTypeName: name, collectionName: name)
        case let .pack(p): model?.movePackToNewMediaType(pack: p, mediaTypeName: name, collectionName: name)
        case .collection: model?.moveCollectionToNewMediaType(name: name)
        }
    }
}

/// The name being asked for.
enum Naming {
    case rename
    case newCollection(MoveTarget, String)
    case newMediaType(MoveTarget)

    var title: String {
        switch self {
        case .rename: L("Rename collection")
        case .newCollection: L("New collection")
        case .newMediaType: L("New media type")
        }
    }
}

/// A row picked in the Move sheet.
enum MovePick {
    case existing(String)
    case newCollection(String)
    case newMediaType
}

/// "Move to collection…": each media type's collections (a folder's children indented under it), "New collection…" for
/// each, then "New media type…", from the shared `moveChoices`. For a whole collection, the other media types.
struct MoveSheet: View {
    let shelves: [Shelf]
    let current: String?
    let title: String
    var collectionFrom: String? = nil
    /// An album moves only between Music's collections (Android's allowNewMediaType = false).
    var allowNewMediaType = true
    let pick: (MovePick) -> Void
    @Environment(\.palette) private var palette
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(Array(choices.enumerated()), id: \.offset) { _, choice in row(choice) }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } } }
        }
        .presentationDetents([.medium, .large])
    }

    private var choices: [MoveChoice] {
        if let from = collectionFrom {
            return shelves.filter { $0.mediaType.id != from }.map { MoveChoiceExisting(id: $0.mediaType.id, name: $0.mediaType.name, indented: false) }
                + [MoveChoiceNewMediaType.shared]
        }
        return MoveChoicesKt.moveChoices(shelves: shelves, currentCollectionId: current, allowNewMediaType: allowNewMediaType)
    }

    @ViewBuilder private func row(_ choice: MoveChoice) -> some View {
        switch onEnum(of: choice) {
        case let .header(h):
            Text(h.name).font(.subheadline.weight(.semibold)).foregroundStyle(palette.primary)
        case let .folder(f):
            Text(f.name).foregroundStyle(palette.onSurfaceVariant)
        case let .existing(e):
            Button(e.name) { pick(.existing(e.id)) }.padding(.leading, e.indented ? 16 : 0).accessibilityIdentifier("move-to-\(e.name)")
        case let .newCollection(n):
            Button("New collection…") { pick(.newCollection(n.mediaTypeId)) }.accessibilityIdentifier("move-new-\(n.mediaTypeName)")
        case .newMediaType:
            Button("New media type…") { pick(.newMediaType) }
        }
    }
}
