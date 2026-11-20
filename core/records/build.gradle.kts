plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// Plan I3 Task 3: what both the store (:core:store) and the online reader (:core:online) need of a topic: its parts,
// the adult flags and the topic-records format. JVM (Android uses it) and iOS.
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
        commonMain.dependencies { implementation(libs.kotlinx.serialization.json) }
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

tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_GRANGER_REPO", rootProject.projectDir.absolutePath)
}
