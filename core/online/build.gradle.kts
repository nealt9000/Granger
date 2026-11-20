plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Plan I1: Wikipedia read online (Plan O1), common to Android (the jvm target, over the app's OkHttpClient) and iOS
// (NSURLSession). The screens, WorkManager jobs and stores stay in :app.
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
        commonMain.dependencies {
            api(project(":core:zim-api"))
            implementation(libs.ksoup)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            api(libs.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.truth)
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.matching { it.name == "test" }.configureEach { dependsOn("jvmTest") }
if (tasks.findByName("test") == null) tasks.register("test") { dependsOn("jvmTest") }
