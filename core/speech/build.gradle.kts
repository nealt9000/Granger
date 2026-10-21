plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.granger.core.speech"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        // The vendored AAR carries only these two (see NOTICE.md); the same list as :core:taglib.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // sherpa-onnx 1.13.8, Apache-2.0, static-link-onnxruntime build trimmed to two ABIs: NOTICE.md says how.
    api(files("libs/sherpa-onnx-1.13.8-static-arm64-x86_64.aar"))
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
