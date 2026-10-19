plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.granger.core.media"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        // The shared audio fixtures, read in place (see tools/dev/make-audio-fixtures.sh).
        getByName("test") { resources.directories.add(rootProject.file("testdata/audio").path) }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals by reflection, which JDK 17+ blocks by default.
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
}

dependencies {
    // Public: TagParser.parse takes RawTags.
    api(project(":core:taglib"))
    // Public: the scanner (Task 6) hands ScannedItems straight to MediaLibrary.sync.
    api(project(":core:library"))
    // Public: MediaLibrary and PhoneScanner are suspend/Flow APIs.
    api(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
}
