import SwiftUI

/// A sheet that opens at half height (or a set height) opens whole at the accessibility text sizes, where half the
/// screen held a row or two and its last choices were out of reach (Plan I7 Task 6).
private struct HalfSheet: ViewModifier {
    let detents: Set<PresentationDetent>
    @Environment(\.dynamicTypeSize) private var size
    func body(content: Content) -> some View {
        content.presentationDetents(size.isAccessibilitySize ? [.large] : detents)
    }
}

extension View {
    func halfSheet(_ detents: Set<PresentationDetent>) -> some View { modifier(HalfSheet(detents: detents)) }
}
