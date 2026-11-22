import SwiftUI

/// Settings → Appearance, as on Android (Plan T1): follow the phone, or Field or Night always.
enum Appearance: String, CaseIterable, Identifiable {
    case system, field, night
    var id: String { rawValue }

    /// The colour scheme to force, or nil to follow the phone.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: nil
        case .field: .light
        case .night: .dark
        }
    }
}

private struct PaletteKey: EnvironmentKey {
    static let defaultValue = Palette.night
}

extension EnvironmentValues {
    /// Field or Night, resolved from Appearance and the phone.
    var palette: Palette {
        get { self[PaletteKey.self] }
        set { self[PaletteKey.self] = newValue }
    }
}

/// Spectral for titles and labels (Android's GrangerTypography); body text stays in the system font so Dynamic Type
/// and VoiceOver behave (spec §5).
enum Spectral {
    static func regular(_ size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Spectral-Regular", size: size, relativeTo: style)
    }
    static func medium(_ size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Spectral-Medium", size: size, relativeTo: style)
    }
    static func semibold(_ size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Spectral-SemiBold", size: size, relativeTo: style)
    }
    static func italic(_ size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        .custom("Spectral-Italic", size: size, relativeTo: style)
    }
}

/// Applies the palette for the resolved scheme and tints controls with its primary, as Android's GrangerTheme does.
struct Themed: ViewModifier {
    @Environment(\.colorScheme) private var scheme

    func body(content: Content) -> some View {
        let palette = scheme == .dark ? Palette.night : Palette.field
        content
            .environment(\.palette, palette)
            .tint(palette.primary)
    }
}

extension View {
    func themed() -> some View { modifier(Themed()) }
}

/// A row that stands its parts in a column at the accessibility text sizes (Plan I6 Task 9's largest-text walk): side by
/// side, a button beside a long line got a few letters wide and broke its words ("Al-low ac-cess").
struct AdaptiveStack<Content: View>: View {
    var spacing: CGFloat? = nil
    var alignment: VerticalAlignment = .center
    @ViewBuilder let content: () -> Content
    @Environment(\.dynamicTypeSize) private var size

    var body: some View {
        let layout = size.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: spacing))
            : AnyLayout(HStackLayout(alignment: alignment, spacing: spacing))
        layout { content() }
    }
}
