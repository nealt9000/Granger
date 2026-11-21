plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

// Plan I1: the catalog's model, parser and signature check, common to Android (the jvm target, re-exported by
// :core:catalog, which keeps the download and cache) and iOS.
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
            implementation(project(":core:text"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmMain.dependencies {
            implementation(libs.bouncycastle)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(libs.truth)
        }
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

// PublishedCatalogTest reads the published catalog straight from the repo: rerun it when the catalog changes.
tasks.withType<Test>().configureEach {
    inputs.files(rootProject.files("catalog/catalog.json", "catalog/catalog.json.sig")).withPathSensitivity(PathSensitivity.NONE)
}
// The iOS test reads the same files by absolute path (the Simulator sees the Mac's disk); SIMCTL_CHILD_ passes it in.
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_GRANGER_REPO", rootProject.projectDir.absolutePath)
}

tasks.matching { it.name == "test" }.configureEach { dependsOn("jvmTest") }
if (tasks.findByName("test") == null) tasks.register("test") { dependsOn("jvmTest") }
