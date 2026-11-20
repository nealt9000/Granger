import SwiftUI

/// Search (Task 5 fills it: online titles and articles).
struct SearchScreen: View {
    @Environment(\.palette) private var palette

    var body: some View {
        Text("Search").font(Spectral.medium(22)).frame(maxWidth: .infinity, maxHeight: .infinity).background(palette.background)
    }
}
