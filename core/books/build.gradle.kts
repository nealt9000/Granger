plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Plan I5 Task 3 (call 6): the book rules on both phones (EPUB inspection, text and PDF → EPUB, comics → PDF, PDF text
// chunks). Each phone reads files its own way behind small interfaces: Android keeps PdfBox, junrar, commonmark and
// java.util.zip exactly as before; the iPhone gets PDFKit and libarchive in Plan I5b.
kotlin {
    android {
        namespace = "app.granger.core.books"
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
            api(project(":core:text"))
            implementation(libs.ksoup)
            implementation(libs.kotlinx.io.core)
        }
        androidMain.dependencies {
            implementation(libs.jsoup)
            implementation(libs.commonmark)
            implementation(libs.junrar)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        getByName("androidHostTest").dependencies {
            implementation(project(":core:books-fixtures"))
            implementation(libs.junit)
            implementation(libs.truth)
            implementation(libs.robolectric)
            implementation(libs.androidx.test.core)
        }
    }
}

dependencies {
    // PdfBox-Android brings bcprov-jdk15to18, which clashes with the app's bcprov-jdk18on (same packages).
    // Granger never decrypts a PDF (an encrypted one is refused), so the newer one on the classpath is enough.
    "androidMainImplementation"(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
    "androidHostTestImplementation"(libs.pdfbox.android) { exclude(group = "org.bouncycastle") }
}

// `./gradlew testDebugUnitTest` (the app's habit) runs the host tests here too.
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
