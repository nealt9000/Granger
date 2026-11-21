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
        // Plan I6 Task 3: the common tests run on the JVM host too (Robolectric for Android's file uris).
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
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
            // Plan I6 Task 3: LibriVox and archive.org audio as group downloads with a manifest (MediaDownloads).
            api(project(":core:media"))
            api(libs.androidx.lifecycle.viewmodel)
            // Plan I5 Task 2: a collection screen keeps its Find text and open sections in a SavedStateHandle.
            api(libs.androidx.lifecycle.viewmodel.savedstate)
            api(libs.kotlinx.coroutines.core)
            api(project(":core:text"))
            // Plan I5 Task 8: the read-aloud sources and the iPhone's voice player (ReadAloudEngine) are shared.
            api(project(":core:speech"))
            api(project(":core:playback"))
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ksoup)
            implementation(libs.kotlinx.io.core)
        }
        // Plan I5 Task 7: the online bookshelf's range reads on the iPhone (Android sends them on the app's OkHttp).
        iosMain.dependencies {
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        iosTest.dependencies { implementation(libs.ktor.client.mock) }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
        }
    }
}

// `./gradlew testDebugUnitTest` (the app's habit) runs the host tests here too.
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
