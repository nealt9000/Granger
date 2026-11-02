plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.granger.core.catalog"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}

// PublishedCatalogTest reads the published catalog straight from the repo: rerun it when the catalog changes.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.files("catalog/catalog.json", "catalog/catalog.json.sig")).withPathSensitivity(PathSensitivity.NONE)
}
