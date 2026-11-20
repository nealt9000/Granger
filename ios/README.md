# Granger on the iPhone

The iPhone app is being built (spec `docs/superpowers/specs/2026-11-20-iphone-design.md`). Today this folder holds:

- `GrangerCore/`: a Swift package with the shared Kotlin core (`GrangerCore.xcframework`: text, search, vectors, the
  catalog, Wikipedia online) and `GrangerCoreKit`, its small Swift half (CryptoKit's Ed25519 for the catalog's signature).
- `CoreSmoke/`: the smallest app over it. It opens "Moon" from Wikipedia through the core and checks the catalog's
  signature. The real app replaces it in plan I2.

## Build and run

You need Xcode 27 on an Apple-silicon Mac.

1. `cp ios/Local.xcconfig.example ios/Local.xcconfig` and set your Apple team and a bundle prefix of your own (the
   Simulator needs neither).
2. Get the core:
   - **Prebuilt (default, once a release is pinned):** Xcode downloads it from the GitHub release named in
     `GrangerCore/Package.swift`. Nothing else to install.
   - **From this checkout's Kotlin:** `tools/ios/core-local.sh` (needs JDK 17), then build with `GRANGER_CORE_LOCAL=1`
     in the environment. Until the first release is pinned, the package always uses the local build.
3. Open `ios/CoreSmoke/CoreSmoke.xcodeproj` and run it on a Simulator or your iPhone.

The Xcode project is generated from `project.yml` by XcodeGen and committed, so you only need XcodeGen if you change
`project.yml`.

## Publishing the core (maintainers)

`tools/ios/release-core.sh` builds, zips and checksums the core and prints what it would publish. `--publish` creates
the GitHub release `ios-core-<date>` and pins it in `GrangerCore/Package.swift`.

## Feedback

Email feedback@opengranger.org or open a GitHub issue.
