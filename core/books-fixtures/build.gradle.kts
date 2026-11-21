plugins {
    alias(libs.plugins.android.library)
}

// Test books built in code (PDFs with PdfBox, EPUBs by hand) for :core:books', :core:library's and the app's tests.
// Was :core:books' testFixtures until Plan I5 made that module multiplatform (the KMP Android target has none).
android {
    namespace = "app.granger.core.books.fixtures"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":core:books"))
    implementation(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
}
