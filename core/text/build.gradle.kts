plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Plan I1: one module for Android (the jvm target, Java 17 bytecode as before) and iOS.
kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // The JDK 17 API only, so nothing newer than Android's desugared library slips in.
            freeCompilerArgs.add("-Xjdk-release=17")
        }
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.ksoup)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.truth)

        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }


// `./gradlew test` (Android's habit) runs the JVM tests here too; KMP names them jvmTest.
tasks.matching { it.name == "test" }.configureEach { dependsOn("jvmTest") }
if (tasks.findByName("test") == null) tasks.register("test") { dependsOn("jvmTest") }
