plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.granger.core.books"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testFixtures { enable = true }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    api(project(":core:text"))
    implementation(libs.jsoup)
    implementation(libs.commonmark)
    // PdfBox-Android brings bcprov-jdk15to18, which clashes with the app's bcprov-jdk18on (same packages).
    // Granger never decrypts a PDF (an encrypted one is refused), so the newer one on the classpath is enough.
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }

    testFixturesImplementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
