import Foundation

/// Which of the three builds this is and what it carries (Plan I8 call 7), from the compilation conditions project.yml
/// sets per configuration:
/// - **Debug** (`DEBUG FRIENDS`): the Simulator's and the UI tests'. Every launch hook (`-debug…`, `-resetData`) and the
///   testers' extras.
/// - **Friends** (`FRIENDS`): the friends' build on TestFlight. Release's optimisation and the testers' extras: the Device
///   check (Settings › About), its report in Send feedback, the "Friends build" line in About. No launch hooks, so none
///   can wipe data or fake a source.
/// - **Release** (neither): the App Store's, later. None of either.
///
/// The launch hooks themselves stay behind `#if DEBUG` and the extras behind `#if FRIENDS`, so a build without them
/// doesn't compile them at all; `BuildExtrasTests` checks both, and the project's settings per configuration.
enum BuildExtras {
    enum Kind: String { case debug = "Debug", friends = "Friends", release = "Release" }

    static var kind: Kind {
        #if DEBUG
        .debug
        #elseif FRIENDS
        .friends
        #else
        .release
        #endif
    }

    /// The testers' extras: the Device check, its report in Send feedback, the "Friends build" line.
    static var testers: Bool {
        #if FRIENDS
        true
        #else
        false
        #endif
    }

    /// The UI tests' launch hooks (`-debug…`, `-resetData`).
    static var launchHooks: Bool {
        #if DEBUG
        true
        #else
        false
        #endif
    }
}
