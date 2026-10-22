plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

// The Mac side of pack-time vectors: runs the app's own chunkers (`:core:text`) over JSONL on stdin.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

application {
    mainClass.set("app.granger.tools.chunker.MainKt")
    applicationName = "chunker"
}

dependencies {
    implementation(project(":core:text"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
