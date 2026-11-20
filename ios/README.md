# Granger on the iPhone

The iPhone app is being built (spec `docs/superpowers/specs/2026-11-20-iphone-design.md`). Today this folder holds:

- `GrangerCore/`: a Swift package with the shared Kotlin core (`GrangerCore.xcframework`: text, search, vectors, the
  catalog, Wikipedia online) and `GrangerCoreKit`, its small Swift half (CryptoKit's Ed25519 for the catalog's signature).
- `Granger/`: the iPhone app (SwiftUI). It's being built to match the Android app 1:1
  (`docs/superpowers/ios-parity.md`) before it's shared.

## Build and run

You need Xcode 27 on an Apple-silicon Mac.

1. `cp ios/Local.xcconfig.example ios/Local.xcconfig` and set your Apple team and a bundle prefix of your own (the
   Simulator needs neither).
2. Get the core:
   - **Prebuilt (default, once a release is pinned):** Xcode downloads it from the GitHub release named in
     `GrangerCore/Package.swift`. Nothing else to install.
   - **From this checkout's Kotlin:** `tools/ios/core-local.sh` (needs JDK 17), then build with `GRANGER_CORE_LOCAL=1`
     in the environment. Until the first release is pinned, the package always uses the local build.
3. Open `ios/Granger/Granger.xcodeproj` and run the `Granger` scheme on a Simulator or your iPhone. `⌘U` runs the unit
   tests and the UI smoke test.

The Xcode project is generated from `project.yml` by XcodeGen and committed, so you only need XcodeGen if you change
`project.yml`. The colours come from Android's theme through `tools/ios/make_palette.py` (`--check` says whether they
match), and the icon from the brand mark through `tools/ios/make_appicon.py`.

## Publishing the core (maintainers)

`tools/ios/release-core.sh` builds, zips and checksums the core and prints what it would publish. `--publish` creates
the GitHub release `ios-core-<date>` and pins it in `GrangerCore/Package.swift`.

## Feedback

Email feedback@opengranger.org or open a GitHub issue.
