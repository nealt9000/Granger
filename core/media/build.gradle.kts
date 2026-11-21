plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

// Plan I6 Task 1 (call 4): the media rules on both phones. commonMain holds the pure readers (TagParser over RawTags,
// ChapterReader, AudiobookGrouper, Fingerprint, the item manifests, downloaded ids) and the downloaded-media scanner,
// with files through MediaDisk (java.io on Android, kotlinx-io and Foundation on the iPhone). androidMain keeps
// MediaStore, the picked SAF folders, TagLib over :core:taglib's native library, the phone scanner and its triggers.
kotlin {
    android {
        namespace = "app.granger.core.media"
        compileSdk = 37
        minSdk = 29
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Public: the scanners hand ScanResults to MediaLibrary.sync.
            api(project(":core:store"))
            implementation(project(":core:text"))
            api(libs.kotlinx.coroutines.core)
            // The item manifests (.granger-item.json), as :core:archive reads archive.org's JSON.
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            // TagLib's native library (granger_taglib); its Kotlin side (TagLib) is in androidMain here.
            api(project(":core:taglib"))
            // Public: PhoneScanner works on the library's DAOs.
            api(project(":core:library"))
            api(libs.kotlinx.coroutines.android)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest") {
            // The shared audio fixtures, read in place (see tools/dev/make-audio-fixtures.sh).
            resources.srcDir(rootProject.file("testdata/audio"))
            dependencies {
                implementation(libs.junit)
                implementation(libs.truth)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.robolectric)
                implementation(libs.androidx.test.core)
                implementation(libs.androidx.room.runtime)
            }
        }
    }
}

// Robolectric reads FileDescriptor internals by reflection, which JDK 17+ blocks by default.
tasks.withType<Test>().configureEach { jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }

// `./gradlew testDebugUnitTest` (the app's habit) runs the host tests here too.
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
