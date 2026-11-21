import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.skie)
}

// Plan I1 Task 7: the shared cores as one framework for the iPhone app, `GrangerCore` (SKIE makes it Swift-friendly).
// iOS only; Android uses the modules directly.
val exported = listOf(":core:text", ":core:research", ":core:vectors", ":core:zim-api", ":core:catalog-api", ":core:online", ":core:records", ":core:store", ":core:downloads-api", ":core:screens", ":core:speech", ":core:playback")

kotlin {
    val xcf = XCFramework("GrangerCore")
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "GrangerCore"
            isStatic = true
            binaryOption("bundleId", "app.granger.core")
            exported.forEach { export(project(it)) }
            xcf.add(this)
        }
        // The minimum iOS (spec §8.3).
        target.compilations.configureEach {
            compileTaskProvider.configure {
                compilerOptions.freeCompilerArgs.add(
                    "-Xoverride-konan-properties=osVersionMin.ios_arm64=26.0;osVersionMin.ios_simulator_arm64=26.0",
                )
            }
        }
    }

    sourceSets {
        iosMain.dependencies {
            exported.forEach { api(project(it)) }
        }
    }
}
