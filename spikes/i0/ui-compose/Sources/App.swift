// I0 Task 5 (B): a Compose Multiplatform screen (ComposeShell.framework) hosted in a SwiftUI app.
import SwiftUI
import ComposeShell

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController { MainViewControllerKt.MainViewController() }
    func updateUIViewController(_ vc: UIViewController, context: Context) {}
}

@main
struct ComposeShellApp: App {
    var body: some Scene { WindowGroup { ComposeView().ignoresSafeArea() } }
}
