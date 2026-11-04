import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// The upload key for Play (Plan F1) lives outside the repo, like the catalog key; backed up by Neal.
// Without the file a release build still builds, unsigned.
val uploadProps = Properties().apply {
    val f = File(System.getProperty("user.home"), ".config/granger/upload.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

// Each commit on main is one version: Play needs the code to climb with every upload. -PversionCode overrides.
val gitCommitCount: Int = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
    .standardOutput.asText.get().trim().toInt()

android {
    namespace = "app.granger"
    compileSdk = 37
    defaultConfig {
        applicationId = "app.granger"
        minSdk = 29
        targetSdk = 36
        versionCode = (findProperty("versionCode") as String?)?.toInt() ?: gitCommitCount
        versionName = "0.1.$gitCommitCount"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        manifestPlaceholders["appLabel"] = "Granger"
        // -PcatalogUrl=http://localhost:8765/catalog.json (with `adb reverse`) tries a catalog before it's on main.
        buildConfigField(
            "String", "CATALOG_URL",
            "\"${findProperty("catalogUrl") ?: "https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json"}\"",
        )
        // Settings → Send feedback writes here (Plan F1 part 2; Neal, 2026-10-31).
        buildConfigField("long", "FAKE_RAM_BYTES", "0L")
        buildConfigField("String", "FEEDBACK_EMAIL", "\"nealt900@gmail.com\"")
        buildConfigField(
            "String", "CATALOG_PUBLIC_KEY",
            "\"${rootProject.file("catalog/public-key.txt").readText().trim()}\"",
        )
    }
    signingConfigs {
        if (uploadProps.isNotEmpty()) create("upload") {
            storeFile = File(uploadProps.getProperty("storeFile"))
            storePassword = uploadProps.getProperty("storePassword")
            keyAlias = uploadProps.getProperty("keyAlias")
            keyPassword = uploadProps.getProperty("keyPassword")
        }
    }
    buildTypes {
        // The friend build on Play internal testing: ./gradlew bundleRelease → app/build/outputs/bundle/release/
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("upload")?.let { signingConfig = it }
        }
        // The Baseline Profile's build (Plan E1 item 6): release code without R8, so the profile's names are the
        // source's, installed beside the real app as app.granger.benchmark with its own empty data. Generate with
        // tools/dev/baseline-profile.sh; it never touches an installed app.granger.
        create("benchmark") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            applicationIdSuffix = ".benchmark"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
            isProfileable = true
            manifestPlaceholders["appLabel"] = "Granger Benchmark"
        }
        // A second debug install beside the first, with its own empty data, for fresh-install walkthroughs:
        // ./gradlew installDebug -Pfresh
        getByName("debug") {
            // en-XA (accented, longer) and ar-XB (right to left): a phone set to either shows any text that isn't a
            // resource as plain English (Plan L1 Task 9). PseudoLocaleTest checks the main screens with en-XA.
            isPseudoLocalesEnabled = true
            // -PfakeRamGb=4 makes a debug build size itself as a phone with that much memory (Plan E1 phone check).
            findProperty("fakeRamGb")?.let {
                buildConfigField("long", "FAKE_RAM_BYTES", "${(it.toString().toDouble() * (1L shl 30)).toLong()}L")
            }
            if (project.hasProperty("fresh")) {
                applicationIdSuffix = ".fresh"
                manifestPlaceholders["appLabel"] = "Granger Fresh"
            }
        }
    }
    // Android 13+ lists the app's languages (English, Español) in Settings → Apps → Granger → Language (Plan L2
    // Task 3). The locales come from the values-* folders; res/resources.properties names the default's.
    androidResources {
        generateLocaleConfig = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    sourceSets {
        getByName("androidTest") { assets.directories.add(rootProject.file("testdata").path) }
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals by reflection, which JDK 17+ blocks by default.
        unitTests.all {
            it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED")
            // ThemeShots renders the main screens to PNGs only when asked: ./gradlew testDebugUnitTest --tests '*ThemeShots*' -PthemeShots
            it.systemProperty("granger.themeShots", if (project.hasProperty("themeShots")) layout.buildDirectory.dir("theme-shots").get().asFile.path else "")
            // Tests read the published catalog straight from the repo: rerun them when it changes.
            it.inputs.files(rootProject.files("catalog/catalog.json", "catalog/catalog.json.sig")).withPathSensitivity(PathSensitivity.NONE)
        }
    }
    compileOptions {
        // Readium's AARs require it (checkDebugAarMetadata).
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:ai"))
    implementation(project(":core:zim"))
    implementation(project(":core:library"))
    implementation(project(":core:archive"))
    implementation(project(":core:catalog"))
    implementation(project(":core:downloads"))
    implementation(project(":core:books"))
    implementation(project(":core:research"))
    implementation(project(":core:media"))
    implementation(project(":core:player"))
    implementation(project(":core:speech"))        // the voice (Plan V1): Speech, both engines, installed voices
    implementation(libs.media3.session)   // MediaConstants for the Android Auto browse tree
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.material.icons)
    // Installs the Baseline Profile (src/main/baseline-prof.txt) on phones that didn't get it from Play (Plan E1).
    implementation(libs.androidx.profileinstaller)
    implementation(libs.jsoup)   // Home's "On this day": the day article's sections (Plan H2)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.room.runtime)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.webkit)
    implementation(libs.readium.shared)
    implementation(libs.readium.streamer)
    implementation(libs.readium.navigator)
    implementation(libs.readium.adapter.pdfium)
    implementation(libs.android.pdf.viewer)
    implementation(libs.androidx.fragment.compose)
    implementation(libs.androidx.glance.appwidget)   // the home-screen player widget
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.glance.appwidget.testing)
    // Puts the empty activity Compose tests host their content in into the debug manifest Robolectric reads.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
}
