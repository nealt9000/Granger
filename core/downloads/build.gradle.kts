plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.granger.core.downloads"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("test") { assets.directories.add(rootProject.file("core/downloads-api/schemas").path) }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals by reflection, which JDK 17+ blocks by default.
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
}

dependencies {
    api(project(":core:catalog"))
    // The records table, queue rules and manager, shared with the iPhone (Plan I4).
    api(project(":core:downloads-api"))
    api(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
}
