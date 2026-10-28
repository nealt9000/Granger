# I0 spikes (throwaway; never merged into the Android modules)

- `kmp-core/`: Task 4. A standalone KMP build over copies of `:core:text`, `:core:research`, `:core:vectors` (modules `vectors`, `text-research`), plus Task 5's `shared` (the umbrella framework, `CoreDemo`) and `compose-shell` (Compose Multiplatform screens).
  Tests: `../../gradlew -p kmp-core :vectors:jvmTest :vectors:iosSimulatorArm64Test :text-research:jvmTest :text-research:iosSimulatorArm64Test`.
  Frameworks: `../../gradlew -p kmp-core :shared:linkDebugFrameworkIosSimulatorArm64 :compose-shell:linkDebugFrameworkIosSimulatorArm64 :compose-shell:assembleIosSimulatorArm64MainResources` (release links need `-Dorg.gradle.jvmargs=-Xmx6g`).
- `ui-swiftui/`, `ui-compose/`: Task 5's two shells. Projects come from `project.yml` via `xcodegen generate` (brew install xcodegen). Build for a Simulator with `xcodebuild -project … -scheme … -destination id=<udid> ARCHS=arm64 build`. Launch arguments `-autoSearch` and `-openReader` drive the screenshots.
