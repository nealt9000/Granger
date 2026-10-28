plugins { kotlin("multiplatform") version "2.4.21" apply false }

subprojects {
    apply(plugin = "org.jetbrains.kotlin.multiplatform")
    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension> {
        jvm()
        listOf(iosArm64(), iosSimulatorArm64()).forEach { it.binaries.framework { baseName = "GrangerCore" } }
        sourceSets.getByName("commonMain").dependencies {
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        sourceSets.getByName("commonTest").dependencies { implementation(kotlin("test")) }
        sourceSets.getByName("jvmTest").dependencies {
            implementation("junit:junit:4.13.2")
            implementation("com.google.truth:truth:1.4.5")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
        }
    }
    tasks.withType<Test>().configureEach { maxHeapSize = "4g" }
}
