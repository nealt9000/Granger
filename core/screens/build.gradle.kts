plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
}

// Plan I3 Task 4: the screen models both phones run (Explore, the topic page, Home's feeds, the timeline, the games,
// readiness). Compose and SwiftUI only draw them. Package names are kept, so Android's screens and tests import nothing new.
kotlin {
    android {
        namespace = "app.granger.core.screens"
        compileSdk = 37
        minSdk = 29
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:store"))
            api(project(":core:online"))
            api(project(":core:downloads-api"))
            // Plan I5 Task 2: archive.org's model.
            api(project(":core:archive"))
            api(libs.androidx.lifecycle.viewmodel)
            // Plan I5 Task 2: a collection screen keeps its Find text and open sections in a SavedStateHandle.
            api(libs.androidx.lifecycle.viewmodel.savedstate)
            api(libs.kotlinx.coroutines.core)
            api(project(":core:text"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ksoup)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
