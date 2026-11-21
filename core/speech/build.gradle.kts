plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Plan I5 Task 3 (call 4): the read-aloud rules on both phones (sentences, spoken words, quote spans, pronunciations,
// utterances, the read sources, the installed-voice list). The sound stays native: Android keeps sherpa-onnx's AAR
// (SherpaTts, SherpaSpeech) and the system TextToSpeech; the iPhone gets sherpa-onnx's iOS build and
// AVSpeechSynthesizer in Plan I5b.
kotlin {
    android {
        namespace = "app.granger.core.speech"
        compileSdk = 37
        minSdk = 29
        // The voice titles' words (speech_granger_voice, …) are this module's own resources.
        androidResources { enable = true }
        withHostTestBuilder {}.configure {
            isIncludeAndroidResources = true
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Public: Speech hands out Flows and StateFlows.
            api(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            api(libs.kotlinx.coroutines.android)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.truth)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.robolectric)          // ICU's sentence breaks come from the framework
            implementation(libs.androidx.test.core)
        }
    }
}

dependencies {
    // sherpa-onnx 1.13.8, Apache-2.0, static-link-onnxruntime build trimmed to arm64-v8a and x86_64 (the app's ABIs,
    // the same list as :core:taglib): NOTICE.md says how. The app's R8 keep rule for it is in app/proguard-rules.pro.
    "androidMainApi"(files("libs/sherpa-onnx-1.13.8-static-arm64-x86_64.aar"))
}

// `./gradlew testDebugUnitTest` (the app's habit) runs the host tests here too.
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
