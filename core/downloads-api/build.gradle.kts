plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ksp)
}

// Plan I4 Task 1: the one download queue's rules on both phones: the records table (Room, one schema and migration
// chain), the queue order, the space check, retries, the sidecar rules and the summary the screens show. Each phone
// keeps its own transfer: :core:downloads (Android) has OkHttp and WorkManager and re-exports this module; the iPhone
// has a URLSession transfer behind [FileTransfer].
kotlin {
    android {
        namespace = "app.granger.core.downloads.api"
        compileSdk = 37
        minSdk = 29
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:catalog-api"))
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.io.core)
            // A voice's manifest.json (VoiceDownloads).
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.room.ktx)
        }
        iosMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
