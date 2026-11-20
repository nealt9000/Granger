import SwiftUI
import XCTest
@testable import Granger

/// The palette is generated from Android's GrangerTheme.kt (tools/ios/make_palette.py --check keeps it in step); this
/// checks a few anchor colours survived the trip.
final class PaletteTests: XCTestCase {
    func testAnchors() {
        XCTAssertEqual(Palette.field.primary, Color(argb: 0xFF2E4A45))   // Enamel
        XCTAssertEqual(Palette.night.primary, Color(argb: 0xFFF0A35E))   // Lantern
        XCTAssertEqual(Palette.night.onSurface, Color(argb: 0xFFEFE4D0)) // Paper
    }
}
