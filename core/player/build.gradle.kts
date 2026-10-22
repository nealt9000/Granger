plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.granger.core.player"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        // The shared audio fixtures, read in place (see tools/dev/make-audio-fixtures.sh).
        getByName("test") { resources.directories.add(rootProject.file("testdata/audio").path) }
        getByName("androidTest") { assets.directories.add(rootProject.file("testdata/audio").path) }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals by reflection, which JDK 17+ blocks by default.
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
}

dependencies {
    // Public: the player works on Track, Audiobook, MusicState and the playback DAOs.
    api(project(":core:library"))
    // Public: PlayerHub reads aloud through Speech, ReadSource and VoiceId.
    api(project(":core:speech"))
    // Public: PlayerHub exposes StateFlows and suspend functions.
    api(libs.kotlinx.coroutines.android)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    api(libs.media3.common)               // MediaBrowseTree hands MediaItems across

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.media3.test.utils)
    testImplementation(libs.media3.test.utils.robolectric)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
