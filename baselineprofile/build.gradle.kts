// Generates the app's Baseline Profile (Plan E1 item 6) on a connected phone, against :app's benchmark build
// (app.granger.benchmark): its own install and data, so the phone's app.granger is never touched.
//   tools/dev/baseline-profile.sh
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "app.granger.baselineprofile"
    compileSdk = 37
    defaultConfig {
        minSdk = 29
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // A profile can be collected on a phone that isn't rooted from API 33 on (the Pixel 6 is).
        testInstrumentationRunnerArguments["androidx.benchmark.enabledRules"] = "BaselineProfile"
    }
    buildTypes {
        create("benchmark") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }
    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// Only the benchmark variant: a debug one would target the debug app.
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType == "benchmark" }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
}
