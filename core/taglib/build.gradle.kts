plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.granger.core.taglib"
    compileSdk = 37
    ndkVersion = libs.versions.ndk.get()
    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    sourceSets {
        getByName("androidTest") { assets.directories.add(rootProject.file("testdata/audio").path) }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Plan I6 Task 1: TagLib's Kotlin side (and RawTags) moved to :core:media, which ships this native library.
    androidTestImplementation(project(":core:media"))
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
