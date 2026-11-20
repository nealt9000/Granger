import SwiftUI

/// Home (Task 6 fills it: today's featured article and Recently read).
struct HomeScreen: View {
    @Environment(\.palette) private var palette

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text("Granger").font(Spectral.semibold(34, relativeTo: .largeTitle)).foregroundStyle(palette.onBackground)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
        }
        .background(palette.background)
    }
}
