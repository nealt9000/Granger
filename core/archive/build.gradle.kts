plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// Plan I5 Task 2: archive.org and LibriVox on both phones. The clients, queries, offers and the 1930 rule are common;
// each phone sends the GET its own way behind `ArchiveHttp`: Android keeps the app's OkHttpClient exactly as before
// (its Local only gate, pool, retry and User-Agent), the iPhone uses Ktor's Darwin engine. The words live in the app's
// resources (strings_archive_core.xml) and the iPhone's ScreenText, named here by key.
kotlin {
    android {
        namespace = "app.granger.core.archive"
        compileSdk = 37
        minSdk = 29
        // android.util.Log (the HTTP retry's line) is a no-op in the plain JVM tests.
        withHostTestBuilder {}.configure { isReturnDefaultValues = true }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core:text"))
            implementation(libs.kotlinx.serialization.json)
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            api(libs.okhttp)
            implementation(libs.kotlinx.coroutines.android)
        }
        iosMain.dependencies {
            api(libs.ktor.client.core)
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        iosTest.dependencies { implementation(libs.ktor.client.mock) }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.truth)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// `./gradlew testDebugUnitTest` (the app's habit) runs the host tests here too.
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
