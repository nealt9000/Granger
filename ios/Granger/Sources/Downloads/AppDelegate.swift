import UIKit

/// The app delegate SwiftUI keeps for the one thing it can't do itself (Plan I4 Task 6): the system relaunching the app
/// in the background to deliver the download session's events. Its completion handler is called once they're in.
final class AppDelegate: NSObject, UIApplicationDelegate {
    static var backgroundEvents: (() -> Void)?

    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String, completionHandler: @escaping () -> Void) {
        AppDelegate.backgroundEvents = completionHandler
        Core.shared?.library.backgroundEventsDone = {
            AppDelegate.backgroundEvents?()
            AppDelegate.backgroundEvents = nil
        }
        Core.shared?.library.transfer.connect()
    }
}
