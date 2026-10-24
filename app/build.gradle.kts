plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.granger"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.granger"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField(
            "String", "CATALOG_URL",
            "\"https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json\"",
        )
        buildConfigField(
            "String", "CATALOG_PUBLIC_KEY",
            "\"${rootProject.file("catalog/public-key.txt").readText().trim()}\"",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest") { assets.directories.add(rootProject.file("testdata").path) }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals by reflection, which JDK 17+ blocks by default.
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
    compileOptions {
        // Readium's AARs require it (checkDebugAarMetadata).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:zim"))
    implementation(project(":core:library"))
    implementation(project(":core:archive"))
    implementation(project(":core:catalog"))
    implementation(project(":core:downloads"))
    implementation(project(":core:books"))
    implementation(project(":core:research"))
    implementation(project(":core:media"))
    implementation(project(":core:player"))
    implementation(project(":core:speech"))        // the voice (Plan V1): Speech, both engines, installed voices
    implementation(libs.media3.session)   // MediaConstants for the Android Auto browse tree
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.jsoup)   // Home's "On this day": the day article's sections (Plan H2)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.adapter.pdfium)
    implementation(libs.android.pdf.viewer)
    implementation(libs.androidx.fragment.compose)
    implementation(libs.androidx.glance.appwidget)   // the home-screen player widget
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.glance.appwidget.testing)
    // Puts the empty activity Compose tests host their content in into the debug manifest Robolectric reads.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
