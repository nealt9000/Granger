plugins {
    alias(libs.plugins.kotlin.multiplatform)
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
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.kotlinx.coroutines.core)
            implementation(project(":core:text"))
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}
