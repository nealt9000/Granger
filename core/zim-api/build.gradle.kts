plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Plan I1: what a ZIM archive is, common to Android (the jvm target, re-exported by :core:zim) and iOS. The readers
// themselves stay per platform: java-libkiwix in :core:zim, CoreKiwix in the iOS app (I4).
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.matching { it.name == "test" }.configureEach { dependsOn("jvmTest") }
if (tasks.findByName("test") == null) tasks.register("test") { dependsOn("jvmTest") }
