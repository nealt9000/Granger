plugins {
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework {
            baseName = "ComposeShell"
            isStatic = true
        }
    }
    sourceSets.getByName("commonMain").dependencies {
        implementation(project(":shared"))
        implementation("org.jetbrains.compose.runtime:runtime:1.12.2")
        implementation("org.jetbrains.compose.foundation:foundation:1.12.2")
        implementation("org.jetbrains.compose.material3:material3:1.9.0")
        implementation("org.jetbrains.compose.components:components-resources:1.12.2")
        implementation("org.jetbrains.compose.ui:ui:1.12.2")
    }
}
compose.resources { packageOfResClass = "app.granger.shell.res" }
