plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.ksp)
}

// Plan I3 Task 1: the library database (Room) on both phones, one schema and one migration chain. Android keeps
// Room's framework SQLite as before (:core:library re-exports this module); iOS uses the bundled SQLite.
kotlin {
    android {
        namespace = "app.granger.core.store"
        compileSdk = 37
        minSdk = 29
    }
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":core:zim-api"))
            api(project(":core:research"))
            api(libs.androidx.room.runtime)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            // Sidecar files through the framework SQLite, the same engine Android used before Plan I3.
            implementation(libs.androidx.sqlite.framework)
        }
        iosMain.dependencies {
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        iosTest.dependencies { implementation(libs.kotlinx.serialization.json) }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

// The iOS tests read the exported schemas by absolute path (the Simulator sees the Mac's disk).
tasks.withType<org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest>().configureEach {
    environment("SIMCTL_CHILD_GRANGER_REPO", rootProject.projectDir.absolutePath)
    // Real release sidecars, for a local check only (SidecarIosTest skips without them).
    environment("SIMCTL_CHILD_GRANGER_TEST_GQID", (project.findProperty("gqidTest") as String?).orEmpty())
    environment("SIMCTL_CHILD_GRANGER_TEST_GTIME", (project.findProperty("gtimeTest") as String?).orEmpty())
}
