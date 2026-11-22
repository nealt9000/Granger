import Foundation

/// Plan I6 Task 9: every UI test starts from clean app data. Each test's `setUp` takes a new token and every launch in
/// that test passes it as `-resetData`; the app clears its data when the token is new to it (`DebugReset`), so a relaunch
/// inside one test keeps what the test's first launch left, and a failed test leaves nothing for the next.
enum CleanData {
    nonisolated(unsafe) private(set) static var token = UUID().uuidString

    static func renew() { token = UUID().uuidString }

    static var args: [String] { ["-resetData", token] }
}
