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
}

dependencies {
    // Public: TagParser.parse takes RawTags.
    api(project(":core:taglib"))
    // Public: the scanner (Task 6) hands ScannedItems straight to MediaLibrary.sync.
    api(project(":core:library"))

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
