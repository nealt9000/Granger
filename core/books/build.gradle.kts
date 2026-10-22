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

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
