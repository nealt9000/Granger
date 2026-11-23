// swift-tools-version: 6.0
// The shared Kotlin core (Plan I1), as one binary target. By default Xcode fetches the release named below; with
// GRANGER_CORE_LOCAL=1 in the environment (or before any release is pinned) it uses Local/GrangerCore.xcframework,
// which `tools/ios/core-local.sh` builds from this checkout's Kotlin (needs JDK 17).
import Foundation
import PackageDescription

// Written by tools/ios/release-core.sh; empty until the first release.
let releaseURL = "https://github.com/nealt9000/Granger/releases/download/ios-core-2026-11-23/GrangerCore.xcframework.zip"
let releaseChecksum = "f02fc5bd22aa651fecdc0826ab50096c77d1b4b0b7672d1ae136bda0077622af"

let useLocal = ProcessInfo.processInfo.environment["GRANGER_CORE_LOCAL"] == "1" || releaseURL.isEmpty

let core: Target = useLocal
    ? .binaryTarget(name: "GrangerCore", path: "Local/GrangerCore.xcframework")
    : .binaryTarget(name: "GrangerCore", url: releaseURL, checksum: releaseChecksum)

let package = Package(
    name: "GrangerCore",
    platforms: [.iOS("26.0")],
    products: [.library(name: "GrangerCoreKit", targets: ["GrangerCoreKit"])],
    targets: [
        core,
        // The Swift side of the core: CryptoKit's Ed25519 for the catalog signature, and small conveniences.
        .target(
            name: "GrangerCoreKit",
            dependencies: ["GrangerCore"],
            linkerSettings: [.linkedLibrary("c++")]
        ),
    ]
)
