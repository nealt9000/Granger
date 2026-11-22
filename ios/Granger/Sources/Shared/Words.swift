import Foundation

/// The words on screen that aren't in a SwiftUI text view's own literal (a `Words` enum, a computed label, a String
/// helper's argument), looked up in the String Catalog (Plan I7 Task 3). The compiler lists every literal passed here,
/// so `tools/ios/make_text_ids.py --check` sees it. Interpolate the pieces inside the literal, never join translated
/// fragments; an integer count interpolated as is gets the catalog's plural forms.
func L(_ words: String.LocalizationValue) -> String { String(localized: words) }
