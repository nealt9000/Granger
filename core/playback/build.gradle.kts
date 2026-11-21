plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
}

// Plan I6 Task 2 adds the player's pure rules: PlayerHub and its states, MusicQueue, ReplayGainMath, PlayerNotices and
// the saved-modes reader (behind PlayerPrefs).
// Plan I5 Task 3 (call 4): what the player decides without media3 or AudioTrack, on both phones: the read-aloud engine
// (ReadAloudEngine over a Speech and a SpeechOutput), the sleep timer, an audiobook's chapter map, when the place is
// saved and how far a resume steps back. :core:player (Android) keeps ExoPlayer, the media session and AudioTrack and
// depends on this; the iPhone's player (Plan I5b) plays through AVAudioEngine behind the same SpeechOutput.
// The packages are the player's own (app.granger.core.player[.voice]), so Android's callers didn't change.
kotlin {
    android {
        namespace = "app.granger.core.playback"
        compileSdk = 37
        minSdk = 29
        // The common tests run on the JVM host too (no Robolectric: their paragraphs break the same without ICU).
        withHostTestBuilder {}
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // Public: the engine reads ReadSources and speaks through Speech.
            api(project(":core:speech"))
            // Public: ChapterMap works on the library's AudiobookChapter.
            api(project(":core:store"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        // Plan I6 Task 2: MusicQueueTest and ReplayGainMathTest moved with their code, unchanged (JUnit + Truth).
        getByName("androidHostTest").dependencies {
            implementation(libs.junit)
            implementation(libs.truth)
        }
    }
}

// `./gradlew testDebugUnitTest` (the app's habit) runs the host tests here too.
tasks.register("testDebugUnitTest") { dependsOn("testAndroidHostTest") }
