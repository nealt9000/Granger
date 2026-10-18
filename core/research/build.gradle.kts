plugins {
    alias(libs.plugins.kotlin.jvm)
}

// No toolchain: only JDK 21 is installed here, so target Java 17 bytecode (like the Android modules) with whatever JDK runs the build.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // The JDK 17 API only, so nothing newer than Android's desugared library slips in.
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

tasks.withType<JavaCompile>().configureEach { options.release.set(17) }

dependencies {
    api(libs.kotlinx.coroutines.core)
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
