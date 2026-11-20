plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.granger.core.library"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    sourceSets {
        getByName("test") { assets.directories.add(rootProject.file("core/store/schemas").path) }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals by reflection, which JDK 17+ blocks by default.
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
}

dependencies {
    // The Room database, entities and DAOs, shared with the iPhone (Plan I3).
    api(project(":core:store"))
    api(project(":core:zim"))
    // Public: ImportResult.Protected exposes DrmKind.
    api(project(":core:books"))
    // Public: ResearchChats exposes Passage.
    api(project(":core:research"))
    // Public: DensePassageFinder takes VectorSources.
    api(project(":core:vectors"))
    implementation(libs.jsoup)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(testFixtures(project(":core:books")))
    // TextPdfFixtures draws its lines in PdfBox's standard fonts.
    testImplementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
}
