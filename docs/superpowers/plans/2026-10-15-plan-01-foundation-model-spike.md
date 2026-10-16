# Granger Plan 1: Foundation + Model Spike — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stand up the Granger Android project and measure, on the Pixel 6, whether Gemma 4 E2B/E4B and EmbeddingGemma run fast enough and fit in memory as the spec assumes.

**Architecture:** A Gradle project with `:app` (Compose) and `:core:ai` (Android library). `:core:ai` holds pure-Kotlin logic (device tier, generation timing, spike runner, report) behind small interfaces (`LlmEngine`, `TextEmbedder`, `MemorySampler`, `MonoClock`), tested on the JVM with fakes, plus thin real adapters over LiteRT-LM and the AI Edge RAG SDK. A debug-only `SpikeActivity` runs the measurements on the phone and writes a Markdown report.

**Tech Stack:** Kotlin 2.4.21 (AGP built-in Kotlin), AGP 9.4.1, Gradle 9.8.1, Jetpack Compose BOM 2026.09.00, LiteRT-LM `com.google.ai.edge.litertlm:litertlm-android:0.18.0`, AI Edge RAG SDK `com.google.ai.edge.localagents:localagents-rag:0.3.0` (for `GemmaEmbeddingModel`), kotlinx-coroutines 1.11.0, JUnit 4.13.2, Truth 1.4.5.

**Spec:** `docs/superpowers/specs/2026-10-15-granger-v1-design.md` (build-order milestone 1). Roadmap: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`.

## Global Constraints

- Android 10+ → `minSdk = 29`; `compileSdk = 36`; `targetSdk = 36`.
- `applicationId` / namespace root: `app.granger` (provisional; finalize before Plan 9's first Play upload).
- Kotlin + Jetpack Compose. Source in `src/<set>/kotlin/`.
- License: GPLv3 — `LICENSE` at repo root contains the GPL-3.0 text.
- Runtime: **LiteRT-LM only**. **At most one LLM loaded at any moment**; the embedder may be loaded alongside the small model.
- Device tiers: < 6 GB RAM → library only · 6 GB → Ask (E2B) · 8 GB+ → Think deeper (E4B).
- No network calls from app code in this plan. Models are side-loaded with `adb`.
- Never commit model files (`*.litertlm`, `*.tflite`, `sentencepiece.model`, `models/`).
- Every commit message ends with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Android reports less RAM than the box says.** An "8 GB" Pixel 6 reports roughly 7.3–7.6 × 10⁹ bytes from `ActivityManager.MemoryInfo.totalMem`. Tier thresholds must put it in Think deeper → `DeviceTierTest` pins real-world values (Task 2).
2. **Two LLMs resident during a swap** would OOM 8 GB phones; the old model must close before the new one is created → `SpikeRunnerTest.neverHoldsTwoLlmsOpen` (Task 4).
3. **A model that returns no output** (empty stream) must be reported, not crash or show a fake TTFT → `GenerationTimingTest.emptyStream` and `SpikeRunnerTest.emptyOutputIsAnError` (Tasks 3, 4).
4. **Missing or unreadable model files on the phone** (wrong push path, scoped storage) must produce a clear "missing: <path>" line, not a native crash → `SpikeRunnerTest.missingFilesStopEarly` (Task 4).
5. **One backend or model failing to load** (e.g. no OpenCL on the Pixel 6 GPU) must not abort the rest of the run, and nothing may stay open → `SpikeRunnerTest.loadFailureContinuesAndCleansUp` (Task 4). Also, an error message containing `|` must not break the Markdown table → `SpikeReportTest.escapesPipes` (Task 5).

---

## File Structure

```
Granger/
├── LICENSE                                  GPL-3.0 text
├── README.md                                What Granger is, how to build
├── .gitignore
├── settings.gradle.kts                      Modules + repositories
├── build.gradle.kts                         Root plugin declarations
├── gradle.properties
├── gradle/libs.versions.toml                Version catalog (single source of versions)
├── gradle/wrapper/…                         Generated wrapper
├── gradlew, gradlew.bat
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/AndroidManifest.xml         MainActivity + GPU native-library declarations
│       ├── main/kotlin/app/granger/MainActivity.kt
│       ├── debug/AndroidManifest.xml        Registers SpikeActivity (debug builds only)
│       └── debug/kotlin/app/granger/spike/SpikeActivity.kt
├── core/ai/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/app/granger/core/ai/
│       │   ├── DeviceTier.kt                Tier enum + RAM thresholds
│       │   ├── LlmEngine.kt                 LlmEngine, LlmEngineFactory, LlmBackend
│       │   ├── GenerationTiming.kt          MonoClock, GenerationResult, timeGeneration()
│       │   ├── LiteRtLlmEngine.kt           LlmEngine over LiteRT-LM
│       │   ├── TextEmbedder.kt              TextEmbedder, TextEmbedderFactory
│       │   ├── RagTextEmbedder.kt           TextEmbedder over GemmaEmbeddingModel
│       │   ├── MemorySampler.kt             MemorySampler + PssMemorySampler
│       │   └── spike/
│       │       ├── SpikePrompts.kt          Realistic rewrite/answer prompts
│       │       ├── SpikeModels.kt           Model file names per backend
│       │       ├── SpikeRunner.kt           The measurement sequence
│       │       └── SpikeReport.kt           Markdown table output
│       └── test/kotlin/app/granger/core/ai/
│           ├── Fakes.kt
│           ├── DeviceTierTest.kt
│           ├── GenerationTimingTest.kt
│           └── spike/SpikeRunnerTest.kt, SpikeReportTest.kt, SpikeModelsTest.kt
├── tools/spike/fetch-models.sh              Download + verify model files on the desktop
├── tools/spike/push-models.sh               adb push to the phone
└── docs/superpowers/spikes/2026-10-model-spike-results.md   Written in Task 8
```

---

### Task 1: Toolchain and project skeleton

**Files:**
- Create: `LICENSE`, `README.md`, `.gitignore`, `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/kotlin/app/granger/MainActivity.kt`, `core/ai/build.gradle.kts`, `core/ai/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: nothing.
- Produces: modules `:app` and `:core:ai`; version-catalog aliases used by every later task (`libs.litertlm.android`, `libs.localagents.rag`, `libs.kotlinx.coroutines.android`, `libs.kotlinx.coroutines.test`, `libs.junit`, `libs.truth`, Compose aliases).

- [ ] **Step 1: Install JDK 21 and the Android SDK (one time, on the Linux desktop)**

```bash
sudo apt update && sudo apt install -y openjdk-21-jdk unzip curl
mkdir -p ~/Android/Sdk/cmdline-tools
cd ~/Android/Sdk/cmdline-tools
curl -LO https://dl.google.com/android/repository/commandlinetools-linux-15859902_latest.zip
unzip -q commandlinetools-linux-15859902_latest.zip && mv cmdline-tools latest && rm commandlinetools-linux-15859902_latest.zip
cat >> ~/.bashrc <<'EOF'
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH
EOF
source ~/.bashrc
yes | sdkmanager --licenses >/dev/null
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.1.0"
java -version && adb version
```

Expected: `openjdk version "21…"` and `Android Debug Bridge version …`.

- [ ] **Step 2: Connect the Pixel 6**

On the phone: Settings → About phone → tap *Build number* 7 times → Settings → System → Developer options → enable *USB debugging*. Plug in USB and accept the RSA prompt.

```bash
adb devices
```

Expected: one line ending in `device` (not `unauthorized`). If no device appears, run `sudo apt install -y android-sdk-platform-tools-common` (installs udev rules), replug, retry.

- [ ] **Step 3: Write repo hygiene files**

```bash
cd /home/neal/Desktop/ai_projects/Granger
curl -sSL -o LICENSE https://www.gnu.org/licenses/gpl-3.0.txt
head -3 LICENSE   # expect "GNU GENERAL PUBLIC LICENSE" / "Version 3, 29 June 2007"
```

`.gitignore`:

```gitignore
.gradle/
.kotlin/
build/
local.properties
.idea/
*.iml
captures/
.DS_Store
# Model files are never committed
models/
*.litertlm
*.tflite
sentencepiece.model
```

`README.md`:

```markdown
# Granger

Civilization, backed up. An offline library for Android with a local AI librarian that always cites its source.

Licensed under GPL-3.0 (see `LICENSE`).

## Build

Requires JDK 21 and the Android SDK (`ANDROID_HOME` set).

    ./gradlew assembleDebug           # build
    ./gradlew test                    # JVM unit tests
    ./gradlew installDebug            # install on a connected phone

Design: `docs/superpowers/specs/2026-10-15-granger-v1-design.md`
```

- [ ] **Step 4: Write the Gradle build files**

`settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Granger"
include(":app", ":core:ai")
```

`gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.4.1"
kotlin = "2.4.21"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
coroutines = "1.11.0"
litertlm = "0.18.0"
localagentsRag = "0.3.0"
junit = "4.13.2"
truth = "1.4.5"

[libraries]
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
litertlm-android = { module = "com.google.ai.edge.litertlm:litertlm-android", version.ref = "litertlm" }
localagents-rag = { module = "com.google.ai.edge.localagents:localagents-rag", version.ref = "localagentsRag" }
junit = { module = "junit:junit", version.ref = "junit" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
```

`build.gradle.kts` (root). AGP 9 compiles Kotlin itself ("built-in Kotlin"), so modules never apply `kotlin-android`; declaring it here with `apply false` only pins the Kotlin Gradle Plugin to 2.4.21 so it matches the Compose compiler plugin:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
```

`core/ai/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "app.granger.core.ai"
    compileSdk = 36
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.litertlm.android)
    implementation(libs.localagents.rag)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

`core/ai/src/main/AndroidManifest.xml`:

```xml
<manifest />
```

`app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.granger"
    compileSdk = 36
    defaultConfig {
        applicationId = "app.granger"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:ai"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 5: Write the app manifest and MainActivity**

`app/src/main/AndroidManifest.xml` (the two `uses-native-library` entries are required by LiteRT-LM's GPU backend):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application
        android:allowBackup="false"
        android:label="Granger"
        android:theme="@android:style/Theme.Material.NoActionBar">
        <uses-native-library android:name="libOpenCL.so" android:required="false" />
        <uses-native-library android:name="libvndksupport.so" android:required="false" />
        <activity
            android:name=".MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`app/src/main/kotlin/app/granger/MainActivity.kt`:

```kotlin
package app.granger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("Granger", style = MaterialTheme.typography.headlineLarge)
                    }
                }
            }
        }
    }
}
```

- [ ] **Step 6: Generate the Gradle wrapper**

```bash
cd ~ && curl -LO https://services.gradle.org/distributions/gradle-9.8.1-bin.zip && unzip -q gradle-9.8.1-bin.zip -d ~/.local && rm gradle-9.8.1-bin.zip
cd /home/neal/Desktop/ai_projects/Granger && ~/.local/gradle-9.8.1/bin/gradle wrapper --gradle-version 9.8.1
```

Expected: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties` created.

- [ ] **Step 7: Build and install**

```bash
./gradlew assembleDebug
./gradlew installDebug && adb shell am start -n app.granger/.MainActivity
```

Expected: `BUILD SUCCESSFUL`; the phone shows "Granger" centered.

If the build fails with a Kotlin/Compose compiler version mismatch, confirm the root `build.gradle.kts` contains `alias(libs.plugins.kotlin.android) apply false`. If it fails because `litertlm-android` or `localagents-rag` requires a higher `minSdk` than 29, stop and report the required value. Raising the floor changes the spec's "Android 10+" decision, so Neal has to approve it.

If the build fails with `2 files found with path 'lib/arm64-v8a/<name>.so'`, add this inside `android { }` in `app/build.gradle.kts`, using the reported `<name>`, and rebuild:

```kotlin
packaging { jniLibs { pickFirsts += setOf("**/<name>.so") } }
```

- [ ] **Step 8: Commit**

```bash
git add LICENSE README.md .gitignore settings.gradle.kts build.gradle.kts gradle.properties gradle/ gradlew gradlew.bat app/ core/
git commit -m "Scaffold Granger Android project with :app and :core:ai

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Device tier

**Files:**
- Create: `core/ai/src/main/kotlin/app/granger/core/ai/DeviceTier.kt`
- Test: `core/ai/src/test/kotlin/app/granger/core/ai/DeviceTierTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces: `enum class DeviceTier { LIBRARY_ONLY, ASK, THINK_DEEPER }`; `object DeviceTiers { fun forTotalRam(totalBytes: Long): DeviceTier; const val ASK_MIN_BYTES: Long; const val THINK_DEEPER_MIN_BYTES: Long }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceTierTest {
    // Values are what ActivityManager.MemoryInfo.totalMem reports, which is below the marketed RAM.
    @Test fun fourGbPhoneIsLibraryOnly() {
        assertThat(DeviceTiers.forTotalRam(3_650_000_000L)).isEqualTo(DeviceTier.LIBRARY_ONLY)
    }

    @Test fun sixGbPhoneGetsAsk() {
        assertThat(DeviceTiers.forTotalRam(5_480_000_000L)).isEqualTo(DeviceTier.ASK)
    }

    @Test fun eightGbPixel6GetsThinkDeeper() {
        assertThat(DeviceTiers.forTotalRam(7_300_000_000L)).isEqualTo(DeviceTier.THINK_DEEPER)
        assertThat(DeviceTiers.forTotalRam(7_650_000_000L)).isEqualTo(DeviceTier.THINK_DEEPER)
    }

    @Test fun twelveGbPhoneGetsThinkDeeper() {
        assertThat(DeviceTiers.forTotalRam(11_400_000_000L)).isEqualTo(DeviceTier.THINK_DEEPER)
    }

    @Test fun boundariesAreInclusive() {
        assertThat(DeviceTiers.forTotalRam(DeviceTiers.ASK_MIN_BYTES)).isEqualTo(DeviceTier.ASK)
        assertThat(DeviceTiers.forTotalRam(DeviceTiers.ASK_MIN_BYTES - 1)).isEqualTo(DeviceTier.LIBRARY_ONLY)
        assertThat(DeviceTiers.forTotalRam(DeviceTiers.THINK_DEEPER_MIN_BYTES)).isEqualTo(DeviceTier.THINK_DEEPER)
        assertThat(DeviceTiers.forTotalRam(DeviceTiers.THINK_DEEPER_MIN_BYTES - 1)).isEqualTo(DeviceTier.ASK)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:ai:testDebugUnitTest --tests 'app.granger.core.ai.DeviceTierTest'`
Expected: FAIL — compilation error, `Unresolved reference 'DeviceTiers'`.

- [ ] **Step 3: Implement**

```kotlin
package app.granger.core.ai

enum class DeviceTier { LIBRARY_ONLY, ASK, THINK_DEEPER }

object DeviceTiers {
    private const val GIB = 1024L * 1024 * 1024

    // totalMem excludes kernel and hardware carve-outs, so phones report less than the marketed RAM:
    // a "6 GB" phone reports ~5.1 GiB and an "8 GB" Pixel 6 ~6.8–7.1 GiB.
    const val ASK_MIN_BYTES: Long = 9 * GIB / 2           // 4.5 GiB → marketed 6 GB phones
    const val THINK_DEEPER_MIN_BYTES: Long = 13 * GIB / 2 // 6.5 GiB → marketed 8 GB phones

    fun forTotalRam(totalBytes: Long): DeviceTier = when {
        totalBytes >= THINK_DEEPER_MIN_BYTES -> DeviceTier.THINK_DEEPER
        totalBytes >= ASK_MIN_BYTES -> DeviceTier.ASK
        else -> DeviceTier.LIBRARY_ONLY
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:ai:testDebugUnitTest --tests 'app.granger.core.ai.DeviceTierTest'`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add core/ai/src/main/kotlin/app/granger/core/ai/DeviceTier.kt core/ai/src/test/kotlin/app/granger/core/ai/DeviceTierTest.kt
git commit -m "Add device tier detection from total RAM

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: LLM engine interface and generation timing

**Files:**
- Create: `core/ai/src/main/kotlin/app/granger/core/ai/LlmEngine.kt`, `core/ai/src/main/kotlin/app/granger/core/ai/GenerationTiming.kt`
- Test: `core/ai/src/test/kotlin/app/granger/core/ai/Fakes.kt`, `core/ai/src/test/kotlin/app/granger/core/ai/GenerationTimingTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum class LlmBackend { CPU, GPU }`
  - `interface LlmEngine : AutoCloseable { fun load(); fun generate(prompt: String): Flow<String> }`
  - `fun interface LlmEngineFactory { fun create(modelPath: String, backend: LlmBackend): LlmEngine }`
  - `fun interface MonoClock { fun nanos(): Long }`, `val SystemMonoClock: MonoClock`
  - `data class GenerationResult(val ttftMs: Long?, val chunks: Int, val decodeMs: Long, val text: String) { val chunksPerSecond: Double }`
  - `suspend fun timeGeneration(engine: LlmEngine, prompt: String, maxChunks: Int, clock: MonoClock): GenerationResult`
  - Test fakes `FakeClock`, `ScriptedLlm` (in `Fakes.kt`, reused by Task 4).

- [ ] **Step 1: Write the fakes and the failing test**

`core/ai/src/test/kotlin/app/granger/core/ai/Fakes.kt`:

```kotlin
package app.granger.core.ai

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class FakeClock : MonoClock {
    var now = 0L
    override fun nanos() = now
    fun advanceMs(ms: Long) { now += ms * 1_000_000 }
}

/** Emits one chunk per entry in [delaysMs], advancing [clock] by that delay before each chunk. */
open class ScriptedLlm(
    private val clock: FakeClock,
    private val delaysMs: List<Long>,
) : LlmEngine {
    var loaded = false
    var closed = false
    override fun load() { loaded = true }
    override fun generate(prompt: String): Flow<String> = flow {
        delaysMs.forEachIndexed { i, d ->
            clock.advanceMs(d)
            emit("t$i ")
        }
    }
    override fun close() { closed = true }
}
```

`core/ai/src/test/kotlin/app/granger/core/ai/GenerationTimingTest.kt`:

```kotlin
package app.granger.core.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GenerationTimingTest {
    @Test fun measuresTimeToFirstChunkAndDecodeRate() = runTest {
        val clock = FakeClock()
        val llm = ScriptedLlm(clock, listOf(500, 100, 100))

        val r = timeGeneration(llm, "q", maxChunks = 100, clock = clock)

        assertThat(r.ttftMs).isEqualTo(500)
        assertThat(r.chunks).isEqualTo(3)
        assertThat(r.decodeMs).isEqualTo(200)
        assertThat(r.chunksPerSecond).isWithin(0.001).of(10.0)
        assertThat(r.text).isEqualTo("t0 t1 t2 ")
    }

    @Test fun stopsAtMaxChunks() = runTest {
        val clock = FakeClock()
        val llm = ScriptedLlm(clock, List(10) { 10L })

        val r = timeGeneration(llm, "q", maxChunks = 3, clock = clock)

        assertThat(r.chunks).isEqualTo(3)
    }

    @Test fun emptyStream() = runTest {
        val clock = FakeClock()
        val llm = ScriptedLlm(clock, emptyList())

        val r = timeGeneration(llm, "q", maxChunks = 10, clock = clock)

        assertThat(r.ttftMs).isNull()
        assertThat(r.chunks).isEqualTo(0)
        assertThat(r.chunksPerSecond).isEqualTo(0.0)
    }

    @Test fun singleChunkHasZeroRateNotInfinity() = runTest {
        val clock = FakeClock()
        val llm = ScriptedLlm(clock, listOf(300))

        val r = timeGeneration(llm, "q", maxChunks = 10, clock = clock)

        assertThat(r.ttftMs).isEqualTo(300)
        assertThat(r.chunksPerSecond).isEqualTo(0.0)
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:ai:testDebugUnitTest --tests 'app.granger.core.ai.GenerationTimingTest'`
Expected: FAIL — `Unresolved reference 'MonoClock'` / `'LlmEngine'`.

- [ ] **Step 3: Implement**

`core/ai/src/main/kotlin/app/granger/core/ai/LlmEngine.kt`:

```kotlin
package app.granger.core.ai

import kotlinx.coroutines.flow.Flow

enum class LlmBackend { CPU, GPU }

/** One on-device language model. Callers must close it before loading another (one LLM resident at a time). */
interface LlmEngine : AutoCloseable {
    /** Blocking and slow (up to ~10 s). Never call on the main thread. */
    fun load()

    /** Streams the reply as text chunks (roughly one token each). */
    fun generate(prompt: String): Flow<String>
}

fun interface LlmEngineFactory {
    fun create(modelPath: String, backend: LlmBackend): LlmEngine
}
```

`core/ai/src/main/kotlin/app/granger/core/ai/GenerationTiming.kt`:

```kotlin
package app.granger.core.ai

import kotlinx.coroutines.flow.take

fun interface MonoClock {
    fun nanos(): Long
}

val SystemMonoClock = MonoClock { System.nanoTime() }

data class GenerationResult(
    /** Time from request to first chunk; null when the model produced nothing. */
    val ttftMs: Long?,
    val chunks: Int,
    /** Time from first to last chunk. */
    val decodeMs: Long,
    val text: String,
) {
    /** Chunks per second after the first chunk. LiteRT-LM chunks approximate tokens. */
    val chunksPerSecond: Double
        get() = if (chunks < 2 || decodeMs <= 0) 0.0 else (chunks - 1) * 1000.0 / decodeMs
}

suspend fun timeGeneration(
    engine: LlmEngine,
    prompt: String,
    maxChunks: Int,
    clock: MonoClock,
): GenerationResult {
    val start = clock.nanos()
    var first = -1L
    var last = -1L
    var count = 0
    val text = StringBuilder()
    engine.generate(prompt).take(maxChunks).collect { chunk ->
        val now = clock.nanos()
        if (first < 0) first = now
        last = now
        count++
        text.append(chunk)
    }
    if (count == 0) return GenerationResult(ttftMs = null, chunks = 0, decodeMs = 0, text = "")
    return GenerationResult(
        ttftMs = (first - start) / 1_000_000,
        chunks = count,
        decodeMs = (last - first) / 1_000_000,
        text = text.toString(),
    )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:ai:testDebugUnitTest --tests 'app.granger.core.ai.GenerationTimingTest'`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add core/ai/src/main/kotlin/app/granger/core/ai/LlmEngine.kt core/ai/src/main/kotlin/app/granger/core/ai/GenerationTiming.kt core/ai/src/test/kotlin/app/granger/core/ai/Fakes.kt core/ai/src/test/kotlin/app/granger/core/ai/GenerationTimingTest.kt
git commit -m "Add LlmEngine interface and generation timing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Spike runner (the measurement sequence)

**Files:**
- Create: `core/ai/src/main/kotlin/app/granger/core/ai/TextEmbedder.kt`, `core/ai/src/main/kotlin/app/granger/core/ai/MemorySampler.kt` (interface only in this task), `core/ai/src/main/kotlin/app/granger/core/ai/spike/SpikePrompts.kt`, `core/ai/src/main/kotlin/app/granger/core/ai/spike/SpikeModels.kt`, `core/ai/src/main/kotlin/app/granger/core/ai/spike/SpikeRunner.kt`
- Modify: `core/ai/src/test/kotlin/app/granger/core/ai/Fakes.kt` (append fakes)
- Test: `core/ai/src/test/kotlin/app/granger/core/ai/spike/SpikeRunnerTest.kt`, `core/ai/src/test/kotlin/app/granger/core/ai/spike/SpikeModelsTest.kt`

**Interfaces:**
- Consumes: `LlmEngine`, `LlmEngineFactory`, `LlmBackend`, `MonoClock`, `timeGeneration`, `GenerationResult` (Task 3); `FakeClock`, `ScriptedLlm` (Task 3 fakes).
- Produces:
  - `interface TextEmbedder : AutoCloseable { fun load(); fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> }`
  - `fun interface TextEmbedderFactory { fun create(modelPath: String, tokenizerPath: String): TextEmbedder }`
  - `interface MemorySampler { suspend fun <T> peakPssMb(block: suspend () -> T): Pair<T, Int> }`
  - `data class SpikeModels(val small: String, val large: String, val embedder: String, val embedderTokenizer: String)` with `companion object { fun forBackend(backend: LlmBackend, dir: String): SpikeModels; const val SMALL_CPU: String; … }`
  - `data class SpikeConfig(val backend: LlmBackend, val models: SpikeModels, val maxChunks: Int = 128)`
  - `data class SpikeMeasurement(val backend: LlmBackend, val step: String, val millis: Long? = null, val peakPssMb: Int? = null, val ttftMs: Long? = null, val chunksPerSecond: Double? = null, val error: String? = null)`
  - `class SpikeRunner(llmFactory, embedderFactory, memory, clock, fileExists: (String) -> Boolean) { suspend fun run(config: SpikeConfig, onProgress: (String) -> Unit = {}): List<SpikeMeasurement>; companion object { val STEPS: List<String> } }`
  - `object SpikePrompts { val QUESTION: String; val REWRITE: String; val ANSWER: String; fun passages(n: Int): List<String> }`

- [ ] **Step 1: Append fakes to `Fakes.kt`**

Add these classes at the end of `Fakes.kt` (no new imports needed):

```kotlin
class FakeLlmFactory(
    private val clock: FakeClock,
    private val failLoadFor: Set<String> = emptySet(),
    private val delaysMs: List<Long> = listOf(400, 50, 50, 50),
) : LlmEngineFactory {
    val openEngines = mutableSetOf<LlmEngine>()
    var maxOpen = 0
    val events = mutableListOf<String>()

    override fun create(modelPath: String, backend: LlmBackend): LlmEngine {
        events += "create $modelPath"
        val engine = object : ScriptedLlm(clock, delaysMs) {
            override fun load() {
                events += "load $modelPath"
                if (modelPath in failLoadFor) error("load failed: $modelPath")
                super.load()
            }
            override fun close() {
                events += "close $modelPath"
                openEngines -= this
                super.close()
            }
        }
        openEngines += engine
        maxOpen = maxOf(maxOpen, openEngines.size)
        return engine
    }
}

class FakeEmbedder : TextEmbedder {
    var closed = false
    override fun load() {}
    override fun embed(texts: List<String>, isQuery: Boolean) = texts.map { FloatArray(4) }
    override fun close() { closed = true }
}

class FakeMemory(private val mb: Int = 1234) : MemorySampler {
    override suspend fun <T> peakPssMb(block: suspend () -> T): Pair<T, Int> = block() to mb
}
```

- [ ] **Step 2: Write the failing tests**

`core/ai/src/test/kotlin/app/granger/core/ai/spike/SpikeModelsTest.kt`:

```kotlin
package app.granger.core.ai.spike

import app.granger.core.ai.LlmBackend
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpikeModelsTest {
    @Test fun cpuUsesGenericFiles() {
        val m = SpikeModels.forBackend(LlmBackend.CPU, "/m")
        assertThat(m.small).isEqualTo("/m/gemma-4-E2B-it.litertlm")
        assertThat(m.large).isEqualTo("/m/gemma-4-E4B-it.litertlm")
    }

    @Test fun gpuUsesGpuFiles() {
        val m = SpikeModels.forBackend(LlmBackend.GPU, "/m")
        assertThat(m.small).isEqualTo("/m/gemma-4-E2B-it-gpu.litertlm")
        assertThat(m.large).isEqualTo("/m/gemma-4-E4B-it-gpu.litertlm")
    }

    @Test fun embedderFilesAreShared() {
        val m = SpikeModels.forBackend(LlmBackend.GPU, "/m")
        assertThat(m.embedder).isEqualTo("/m/embeddinggemma-300M_seq512_mixed-precision.tflite")
        assertThat(m.embedderTokenizer).isEqualTo("/m/sentencepiece.model")
    }
}
```

`core/ai/src/test/kotlin/app/granger/core/ai/spike/SpikeRunnerTest.kt`:

```kotlin
package app.granger.core.ai.spike

import app.granger.core.ai.FakeClock
import app.granger.core.ai.FakeEmbedder
import app.granger.core.ai.FakeLlmFactory
import app.granger.core.ai.FakeMemory
import app.granger.core.ai.LlmBackend
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SpikeRunnerTest {
    private val models = SpikeModels(small = "/m/small", large = "/m/large", embedder = "/m/emb", embedderTokenizer = "/m/spm")
    private val config = SpikeConfig(LlmBackend.CPU, models)

    private fun runner(
        clock: FakeClock,
        llms: FakeLlmFactory,
        embedder: FakeEmbedder = FakeEmbedder(),
        fileExists: (String) -> Boolean = { true },
    ) = SpikeRunner(
        llmFactory = llms,
        embedderFactory = { _, _ -> embedder },
        memory = FakeMemory(),
        clock = clock,
        fileExists = fileExists,
    )

    @Test fun runsEveryStepInOrder() = runTest {
        val clock = FakeClock()
        val rows = runner(clock, FakeLlmFactory(clock)).run(config)

        assertThat(rows.map { it.step }).containsExactlyElementsIn(SpikeRunner.STEPS).inOrder()
        assertThat(rows.all { it.error == null }).isTrue()
    }

    @Test fun generationStepsRecordTtftAndRate() = runTest {
        val clock = FakeClock()
        val rows = runner(clock, FakeLlmFactory(clock)).run(config)

        val answer = rows.single { it.step == "answer (small)" }
        assertThat(answer.ttftMs).isEqualTo(400)
        assertThat(answer.chunksPerSecond).isWithin(0.001).of(20.0)
        assertThat(answer.peakPssMb).isEqualTo(1234)
    }

    @Test fun neverHoldsTwoLlmsOpen() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock)
        val embedder = FakeEmbedder()

        runner(clock, llms, embedder).run(config)

        assertThat(llms.maxOpen).isEqualTo(1)
        assertThat(llms.openEngines).isEmpty()
        assertThat(embedder.closed).isTrue()
    }

    @Test fun missingFilesStopEarly() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock)

        val rows = runner(clock, llms, fileExists = { it != "/m/large" }).run(config)

        assertThat(rows).hasSize(1)
        assertThat(rows.single().step).isEqualTo("check files")
        assertThat(rows.single().error).isEqualTo("missing: /m/large")
        assertThat(llms.events).isEmpty()
    }

    @Test fun loadFailureContinuesAndCleansUp() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock, failLoadFor = setOf("/m/large"))

        val rows = runner(clock, llms).run(config).associateBy { it.step }

        assertThat(rows.getValue("swap small->large").error).isEqualTo("load failed: /m/large")
        assertThat(rows.getValue("answer (large)").error).isEqualTo("model not loaded")
        assertThat(rows.getValue("swap large->small").error).isNull()
        assertThat(llms.openEngines).isEmpty()
        assertThat(llms.maxOpen).isEqualTo(1)
    }

    @Test fun emptyOutputIsAnError() = runTest {
        val clock = FakeClock()
        val llms = FakeLlmFactory(clock, delaysMs = emptyList())

        val rows = runner(clock, llms).run(config).associateBy { it.step }

        assertThat(rows.getValue("answer (small)").error).isEqualTo("no output")
        assertThat(rows.getValue("answer (small)").ttftMs).isNull()
    }
}
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew :core:ai:testDebugUnitTest --tests 'app.granger.core.ai.spike.*'`
Expected: FAIL — `Unresolved reference 'SpikeModels'`, `'SpikeRunner'`, `'TextEmbedder'`, `'MemorySampler'`.

- [ ] **Step 4: Implement the interfaces**

`core/ai/src/main/kotlin/app/granger/core/ai/TextEmbedder.kt`:

```kotlin
package app.granger.core.ai

/** On-device text embedding model used to rerank passages. May stay loaded alongside the small LLM. */
interface TextEmbedder : AutoCloseable {
    fun load()
    fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray>
}

fun interface TextEmbedderFactory {
    fun create(modelPath: String, tokenizerPath: String): TextEmbedder
}
```

`core/ai/src/main/kotlin/app/granger/core/ai/MemorySampler.kt`:

```kotlin
package app.granger.core.ai

/** Runs [block] and reports the process's peak memory (PSS, MB) observed while it ran. */
interface MemorySampler {
    suspend fun <T> peakPssMb(block: suspend () -> T): Pair<T, Int>
}
```

- [ ] **Step 5: Implement prompts and model file names**

`core/ai/src/main/kotlin/app/granger/core/ai/spike/SpikePrompts.kt`. The answer prompt is sized like a real Ask: 4 passages of about 220 words, roughly 1,100–1,200 tokens of prefill.

```kotlin
package app.granger.core.ai.spike

object SpikePrompts {
    val QUESTION =
        "How long do I need to boil water to make it safe to drink, and what changes at high altitude?"

    private val PARAGRAPH =
        "Boiling is the surest method to kill disease-causing organisms, including viruses, bacteria, " +
            "and parasites. If the water is cloudy, let it settle and filter it through a clean cloth, " +
            "paper towel, or coffee filter before boiling. Bring the water to a rolling boil for at least " +
            "one minute. At elevations above 6,500 feet (about 2,000 meters), boil water for three minutes. " +
            "Let the boiled water cool naturally and store it in clean, sanitized containers with tight " +
            "covers. To improve the flat taste of boiled water, add one pinch of salt to each quart or liter " +
            "of water, or pour the water from one clean container to another several times."

    fun passages(n: Int): List<String> =
        (1..n).map { i -> "[$i] Water treatment, section $i. $PARAGRAPH $PARAGRAPH" }

    val REWRITE =
        "Rewrite the user's question as search keywords for an offline encyclopedia. " +
            "Reply only with JSON: {\"searchTerms\": [\"...\"], \"medical\": true or false}.\n" +
            "Question: $QUESTION"

    val ANSWER = buildString {
        appendLine(
            "Answer the question using only the numbered passages. Cite every claim like [1]. " +
                "If the passages do not contain the answer, reply exactly: NOT_IN_LIBRARY.",
        )
        appendLine()
        passages(4).forEach { appendLine(it); appendLine() }
        append("Question: $QUESTION")
    }
}
```

`core/ai/src/main/kotlin/app/granger/core/ai/spike/SpikeModels.kt`:

```kotlin
package app.granger.core.ai.spike

import app.granger.core.ai.LlmBackend

data class SpikeModels(
    val small: String,
    val large: String,
    val embedder: String,
    val embedderTokenizer: String,
) {
    companion object {
        const val SMALL_CPU = "gemma-4-E2B-it.litertlm"
        const val SMALL_GPU = "gemma-4-E2B-it-gpu.litertlm"
        const val LARGE_CPU = "gemma-4-E4B-it.litertlm"
        const val LARGE_GPU = "gemma-4-E4B-it-gpu.litertlm"
        const val EMBEDDER = "embeddinggemma-300M_seq512_mixed-precision.tflite"
        const val EMBEDDER_TOKENIZER = "sentencepiece.model"

        fun forBackend(backend: LlmBackend, dir: String): SpikeModels = SpikeModels(
            small = "$dir/" + if (backend == LlmBackend.GPU) SMALL_GPU else SMALL_CPU,
            large = "$dir/" + if (backend == LlmBackend.GPU) LARGE_GPU else LARGE_CPU,
            embedder = "$dir/$EMBEDDER",
            embedderTokenizer = "$dir/$EMBEDDER_TOKENIZER",
        )
    }
}
```

- [ ] **Step 6: Implement the runner**

`core/ai/src/main/kotlin/app/granger/core/ai/spike/SpikeRunner.kt`:

```kotlin
package app.granger.core.ai.spike

import app.granger.core.ai.GenerationResult
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.LlmEngine
import app.granger.core.ai.LlmEngineFactory
import app.granger.core.ai.MemorySampler
import app.granger.core.ai.MonoClock
import app.granger.core.ai.TextEmbedder
import app.granger.core.ai.TextEmbedderFactory
import app.granger.core.ai.timeGeneration
import kotlin.coroutines.cancellation.CancellationException

data class SpikeConfig(
    val backend: LlmBackend,
    val models: SpikeModels,
    val maxChunks: Int = 128,
)

data class SpikeMeasurement(
    val backend: LlmBackend,
    val step: String,
    val millis: Long? = null,
    val peakPssMb: Int? = null,
    val ttftMs: Long? = null,
    val chunksPerSecond: Double? = null,
    val error: String? = null,
)

/**
 * Measures the spec's riskiest assumptions on a real phone: load and swap cost, time to first token,
 * decode rate, and peak memory with the embedder resident next to the small model.
 * Holds at most one LLM at a time; a failed step is recorded and the run continues.
 */
class SpikeRunner(
    private val llmFactory: LlmEngineFactory,
    private val embedderFactory: TextEmbedderFactory,
    private val memory: MemorySampler,
    private val clock: MonoClock,
    private val fileExists: (String) -> Boolean,
) {
    suspend fun run(config: SpikeConfig, onProgress: (String) -> Unit = {}): List<SpikeMeasurement> {
        val m = config.models
        val b = config.backend
        val missing = listOf(m.small, m.large, m.embedder, m.embedderTokenizer).filterNot(fileExists)
        if (missing.isNotEmpty()) {
            return missing.map { SpikeMeasurement(b, "check files", error = "missing: $it") }
        }

        val out = mutableListOf<SpikeMeasurement>()
        var llm: LlmEngine? = null
        var embedder: TextEmbedder? = null

        suspend fun step(name: String, block: suspend () -> GenerationResult?) =
            measure(b, name, out, onProgress, block)

        suspend fun generate(prompt: String) =
            timeGeneration(checkNotNull(llm) { "model not loaded" }, prompt, config.maxChunks, clock)

        try {
            step("load small") { llm = openLlm(m.small, b); null }
            step("rewrite (small)") { generate(SpikePrompts.REWRITE) }
            step("answer (small)") { generate(SpikePrompts.ANSWER) }
            step("load embedder (small resident)") { embedder = openEmbedder(m.embedder, m.embedderTokenizer); null }
            step("embed query + 8 passages") {
                val e = checkNotNull(embedder) { "embedder not loaded" }
                e.embed(listOf(SpikePrompts.QUESTION), isQuery = true)
                e.embed(SpikePrompts.passages(8), isQuery = false)
                null
            }
            step("answer (small + embedder resident)") { generate(SpikePrompts.ANSWER) }
            embedder?.close()
            embedder = null
            step("swap small->large") { llm?.close(); llm = null; llm = openLlm(m.large, b); null }
            step("answer (large)") { generate(SpikePrompts.ANSWER) }
            step("swap large->small") { llm?.close(); llm = null; llm = openLlm(m.small, b); null }
        } finally {
            embedder?.close()
            llm?.close()
        }
        return out
    }

    private fun openLlm(path: String, backend: LlmBackend): LlmEngine {
        val engine = llmFactory.create(path, backend)
        try {
            engine.load()
        } catch (t: Throwable) {
            engine.close()
            throw t
        }
        return engine
    }

    private fun openEmbedder(model: String, tokenizer: String): TextEmbedder {
        val embedder = embedderFactory.create(model, tokenizer)
        try {
            embedder.load()
        } catch (t: Throwable) {
            embedder.close()
            throw t
        }
        return embedder
    }

    private suspend fun measure(
        backend: LlmBackend,
        name: String,
        out: MutableList<SpikeMeasurement>,
        onProgress: (String) -> Unit,
        block: suspend () -> GenerationResult?,
    ) {
        onProgress(name)
        val start = clock.nanos()
        try {
            val (gen, peak) = memory.peakPssMb { block() }
            out += SpikeMeasurement(
                backend = backend,
                step = name,
                millis = (clock.nanos() - start) / 1_000_000,
                peakPssMb = peak,
                ttftMs = gen?.ttftMs,
                chunksPerSecond = gen?.chunksPerSecond,
                error = if (gen != null && gen.chunks == 0) "no output" else null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            out += SpikeMeasurement(backend, name, error = e.message ?: e::class.simpleName)
        }
    }

    companion object {
        val STEPS = listOf(
            "load small",
            "rewrite (small)",
            "answer (small)",
            "load embedder (small resident)",
            "embed query + 8 passages",
            "answer (small + embedder resident)",
            "swap small->large",
            "answer (large)",
            "swap large->small",
        )
    }
}
```

- [ ] **Step 7: Run tests to verify they pass**

Run: `./gradlew :core:ai:testDebugUnitTest`
Expected: PASS — all tests in `DeviceTierTest`, `GenerationTimingTest`, `SpikeModelsTest`, `SpikeRunnerTest`.

- [ ] **Step 8: Commit**

```bash
git add core/ai/src
git commit -m "Add spike runner measuring load, swap, TTFT, and memory

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Spike report

**Files:**
- Create: `core/ai/src/main/kotlin/app/granger/core/ai/spike/SpikeReport.kt`
- Test: `core/ai/src/test/kotlin/app/granger/core/ai/spike/SpikeReportTest.kt`

**Interfaces:**
- Consumes: `SpikeMeasurement`, `LlmBackend` (Tasks 3–4).
- Produces: `object SpikeReport { fun toMarkdown(header: String, rows: List<SpikeMeasurement>): String }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package app.granger.core.ai.spike

import app.granger.core.ai.LlmBackend
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SpikeReportTest {
    @Test fun formatsSuccessAndErrorRows() {
        val md = SpikeReport.toMarkdown(
            "Pixel 6",
            listOf(
                SpikeMeasurement(LlmBackend.GPU, "answer (small)", millis = 4200, peakPssMb = 2100, ttftMs = 900, chunksPerSecond = 21.456),
                SpikeMeasurement(LlmBackend.GPU, "swap small->large", error = "load failed"),
            ),
        )

        assertThat(md).isEqualTo(
            """
            |## Pixel 6
            |
            || Backend | Step | Time (ms) | Peak PSS (MB) | TTFT (ms) | Chunks/s | Error |
            || --- | --- | --- | --- | --- | --- | --- |
            || GPU | answer (small) | 4200 | 2100 | 900 | 21.5 |  |
            || GPU | swap small->large | — | — | — | — | load failed |
            |
            """.trimMargin(),
        )
    }

    @Test fun escapesPipes() {
        val md = SpikeReport.toMarkdown(
            "h",
            listOf(SpikeMeasurement(LlmBackend.CPU, "load small", error = "a|b")),
        )

        assertThat(md).contains("| a\\|b |")
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :core:ai:testDebugUnitTest --tests 'app.granger.core.ai.spike.SpikeReportTest'`
Expected: FAIL — `Unresolved reference 'SpikeReport'`.

- [ ] **Step 3: Implement**

```kotlin
package app.granger.core.ai.spike

import java.util.Locale

object SpikeReport {
    fun toMarkdown(header: String, rows: List<SpikeMeasurement>): String = buildString {
        appendLine("## $header")
        appendLine()
        appendLine("| Backend | Step | Time (ms) | Peak PSS (MB) | TTFT (ms) | Chunks/s | Error |")
        appendLine("| --- | --- | --- | --- | --- | --- | --- |")
        rows.forEach { r ->
            val rate = r.chunksPerSecond?.let { String.format(Locale.US, "%.1f", it) } ?: DASH
            appendLine(
                "| ${r.backend} | ${r.step} | ${r.millis.orDash()} | ${r.peakPssMb.orDash()} | " +
                    "${r.ttftMs.orDash()} | $rate | ${r.error?.replace("|", "\\|") ?: ""} |",
            )
        }
    }

    private const val DASH = "—"
    private fun Any?.orDash() = this?.toString() ?: DASH
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :core:ai:testDebugUnitTest --tests 'app.granger.core.ai.spike.SpikeReportTest'`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add core/ai/src/main/kotlin/app/granger/core/ai/spike/SpikeReport.kt core/ai/src/test/kotlin/app/granger/core/ai/spike/SpikeReportTest.kt
git commit -m "Add Markdown spike report

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Real adapters (LiteRT-LM, EmbeddingGemma, PSS sampler)

These wrap native libraries and can only be exercised on a phone; Task 8 is their test. This task's gate is that they compile against the real artifacts.

**Files:**
- Create: `core/ai/src/main/kotlin/app/granger/core/ai/LiteRtLlmEngine.kt`, `core/ai/src/main/kotlin/app/granger/core/ai/RagTextEmbedder.kt`
- Modify: `core/ai/src/main/kotlin/app/granger/core/ai/MemorySampler.kt` (add `PssMemorySampler`)

**Interfaces:**
- Consumes: `LlmEngine`, `LlmBackend` (Task 3); `TextEmbedder`, `MemorySampler` (Task 4).
- Produces: `class LiteRtLlmEngine(modelPath: String, backend: LlmBackend, cacheDir: String) : LlmEngine`; `class RagTextEmbedder(modelPath: String, tokenizerPath: String, useGpu: Boolean = false) : TextEmbedder`; `class PssMemorySampler(intervalMs: Long = 200) : MemorySampler`.

- [ ] **Step 1: LiteRT-LM adapter**

`core/ai/src/main/kotlin/app/granger/core/ai/LiteRtLlmEngine.kt`:

```kotlin
package app.granger.core.ai

import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

class LiteRtLlmEngine(
    private val modelPath: String,
    private val backend: LlmBackend,
    private val cacheDir: String,
) : LlmEngine {
    private var engine: Engine? = null

    override fun load() {
        val config = EngineConfig(
            modelPath = modelPath,
            backend = when (backend) {
                LlmBackend.CPU -> Backend.CPU()
                LlmBackend.GPU -> Backend.GPU()
            },
            cacheDir = cacheDir,
        )
        engine = Engine(config).also { it.initialize() }
    }

    override fun generate(prompt: String): Flow<String> = flow {
        val e = checkNotNull(engine) { "load() first" }
        e.createConversation().use { conversation ->
            conversation.sendMessageAsync(prompt).collect { emit(it.toString()) }
        }
    }

    override fun close() {
        engine?.close()
        engine = null
    }
}
```

- [ ] **Step 2: EmbeddingGemma adapter**

`core/ai/src/main/kotlin/app/granger/core/ai/RagTextEmbedder.kt`. The constructor is `GemmaEmbeddingModel(embeddingModelPath: String, sentencePieceModelPath: String, useGpu: Boolean)`, taken from the 0.3.0 AAR. Its embedding call returns a Guava `ListenableFuture<ImmutableList<Float>>`.

```kotlin
package app.granger.core.ai

import com.google.ai.edge.localagents.rag.models.EmbedData
import com.google.ai.edge.localagents.rag.models.EmbeddingRequest
import com.google.ai.edge.localagents.rag.models.GemmaEmbeddingModel

class RagTextEmbedder(
    private val modelPath: String,
    private val tokenizerPath: String,
    private val useGpu: Boolean = false,
) : TextEmbedder {
    private var model: GemmaEmbeddingModel? = null

    override fun load() {
        model = GemmaEmbeddingModel(modelPath, tokenizerPath, useGpu)
    }

    override fun embed(texts: List<String>, isQuery: Boolean): List<FloatArray> {
        val m = checkNotNull(model) { "load() first" }
        val task = if (isQuery) EmbedData.TaskType.RETRIEVAL_QUERY else EmbedData.TaskType.RETRIEVAL_DOCUMENT
        return texts.map { text ->
            val request = EmbeddingRequest.create(listOf(EmbedData.create(text, task)))
            m.getEmbeddings(request).get().toFloatArray()
        }
    }

    override fun close() {
        model = null
    }
}
```

- [ ] **Step 3: PSS memory sampler**

Append to `core/ai/src/main/kotlin/app/granger/core/ai/MemorySampler.kt`, and add the imports at the top of the file:

```kotlin
import android.os.Debug
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
```

```kotlin
/** Samples this process's total PSS every [intervalMs] while the block runs. */
class PssMemorySampler(private val intervalMs: Long = 200) : MemorySampler {
    override suspend fun <T> peakPssMb(block: suspend () -> T): Pair<T, Int> = coroutineScope {
        val peakKb = AtomicInteger(currentPssKb())
        val sampler = launch(Dispatchers.Default) {
            while (isActive) {
                peakKb.accumulateAndGet(currentPssKb()) { a, b -> maxOf(a, b) }
                delay(intervalMs)
            }
        }
        try {
            val result = block()
            peakKb.accumulateAndGet(currentPssKb()) { a, b -> maxOf(a, b) }
            result to peakKb.get() / 1024
        } finally {
            sampler.cancel()
        }
    }

    private fun currentPssKb(): Int = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }.totalPss
}
```

- [ ] **Step 4: Compile against the real artifacts**

Run: `./gradlew :core:ai:assembleDebug :core:ai:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; all unit tests still pass.

If an import in Step 1 does not resolve, list the classes LiteRT-LM actually ships and fix the import to match:

```bash
f=$(find ~/.gradle/caches -name 'litertlm-android-0.18.0*.aar' | head -1); unzip -p "$f" classes.jar > /tmp/litertlm.jar && unzip -l /tmp/litertlm.jar | grep -E 'Engine|Backend|Conversation'
```

- [ ] **Step 5: Commit**

```bash
git add core/ai/src/main/kotlin/app/granger/core/ai/
git commit -m "Add LiteRT-LM, EmbeddingGemma, and PSS sampler adapters

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Debug spike screen and model scripts

**Files:**
- Create: `app/src/debug/AndroidManifest.xml`, `app/src/debug/kotlin/app/granger/spike/SpikeActivity.kt`, `tools/spike/fetch-models.sh`, `tools/spike/push-models.sh`

**Interfaces:**
- Consumes: `DeviceTiers`, `LlmBackend`, `SystemMonoClock`, `LiteRtLlmEngine`, `RagTextEmbedder`, `PssMemorySampler`, `SpikeRunner`, `SpikeConfig`, `SpikeModels`, `SpikeReport`.
- Produces: debug-only launcher activity `app.granger/app.granger.spike.SpikeActivity`; report files `spike-<backend>-<epochMillis>.md` in the app's external files dir.

- [ ] **Step 1: Debug manifest**

`app/src/debug/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application>
        <activity
            android:name="app.granger.spike.SpikeActivity"
            android:exported="true"
            android:label="Granger Spike">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

- [ ] **Step 2: Spike activity**

`app/src/debug/kotlin/app/granger/spike/SpikeActivity.kt`:

```kotlin
package app.granger.spike

import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.core.ai.DeviceTiers
import app.granger.core.ai.LiteRtLlmEngine
import app.granger.core.ai.LlmBackend
import app.granger.core.ai.PssMemorySampler
import app.granger.core.ai.RagTextEmbedder
import app.granger.core.ai.SystemMonoClock
import app.granger.core.ai.spike.SpikeConfig
import app.granger.core.ai.spike.SpikeModels
import app.granger.core.ai.spike.SpikeReport
import app.granger.core.ai.spike.SpikeRunner
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Debug-only: runs the model spike on this phone and saves a Markdown report. */
class SpikeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val totalMem = ActivityManager.MemoryInfo()
            .also { (getSystemService(ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
            .totalMem
        val soc = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else "unknown"
        val deviceLine = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · " +
            "SoC $soc · totalMem $totalMem B · tier ${DeviceTiers.forTotalRam(totalMem)}"
        val modelsDir = findModelsDir()
        val runner = SpikeRunner(
            llmFactory = { path, backend -> LiteRtLlmEngine(path, backend, cacheDir.path) },
            embedderFactory = { model, tokenizer -> RagTextEmbedder(model, tokenizer) },
            memory = PssMemorySampler(),
            clock = SystemMonoClock,
            fileExists = { File(it).canRead() },
        )
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    SpikeScreen(deviceLine, modelsDir, runner, ::saveReport)
                }
            }
        }
    }

    /** Prefers the app's own files dir; falls back to /data/local/tmp if adb could not write there. */
    private fun findModelsDir(): File {
        val candidates = listOfNotNull(getExternalFilesDir("models"), File("/data/local/tmp/granger-models"))
        return candidates.firstOrNull { File(it, SpikeModels.SMALL_CPU).canRead() } ?: candidates.first()
    }

    private fun saveReport(backend: LlmBackend, markdown: String): File {
        val file = File(getExternalFilesDir(null), "spike-${backend.name.lowercase()}-${System.currentTimeMillis()}.md")
        file.writeText(markdown)
        return file
    }
}

@Composable
private fun SpikeScreen(
    deviceLine: String,
    modelsDir: File,
    runner: SpikeRunner,
    save: (LlmBackend, String) -> File,
) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<String>() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Text(deviceLine)
        Text("Models: ${modelsDir.path}")
        Row {
            LlmBackend.entries.forEach { backend ->
                Button(
                    enabled = !running,
                    onClick = {
                        running = true
                        scope.launch {
                            val rows = withContext(Dispatchers.Default) {
                                runner.run(SpikeConfig(backend, SpikeModels.forBackend(backend, modelsDir.path))) { step ->
                                    scope.launch { log += "$backend: $step…" }
                                }
                            }
                            val markdown = SpikeReport.toMarkdown("$deviceLine · $backend", rows)
                            val file = withContext(Dispatchers.IO) { save(backend, markdown) }
                            log += markdown
                            log += "Saved ${file.path}"
                            running = false
                        }
                    },
                ) { Text("Run $backend") }
                Spacer(Modifier.width(8.dp))
            }
        }
        log.forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
    }
}
```

- [ ] **Step 3: Model fetch script**

`tools/spike/fetch-models.sh`. The Gemma 4 hashes were taken from the Hugging Face API on 2026-10-15. The EmbeddingGemma repo is gated, so its hashes aren't public; the script prints them so they can be recorded in the results.

```bash
#!/usr/bin/env bash
# Downloads spike model files to $MODELS_DIR (default ~/granger-models) and verifies them.
# EmbeddingGemma is gated: accept the license at https://huggingface.co/litert-community/embeddinggemma-300m
# while signed in, create a read token at https://huggingface.co/settings/tokens, then run with HF_TOKEN=<token>.
set -euo pipefail

MODELS_DIR="${MODELS_DIR:-$HOME/granger-models}"
mkdir -p "$MODELS_DIR"
cd "$MODELS_DIR"

E2B=https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main
E4B=https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main
EMB=https://huggingface.co/litert-community/embeddinggemma-300m/resolve/main

fetch() { # url file
  echo "Fetching $2"
  curl -fL -C - -o "$2" ${HF_TOKEN:+-H "Authorization: Bearer $HF_TOKEN"} "$1/$2"
}

fetch "$E2B" gemma-4-E2B-it.litertlm
fetch "$E2B" gemma-4-E2B-it-gpu.litertlm
fetch "$E4B" gemma-4-E4B-it.litertlm
fetch "$E4B" gemma-4-E4B-it-gpu.litertlm

sha256sum -c <<'EOF'
181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c  gemma-4-E2B-it.litertlm
a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5  gemma-4-E2B-it-gpu.litertlm
0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0  gemma-4-E4B-it.litertlm
4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff  gemma-4-E4B-it-gpu.litertlm
EOF

if [[ -z "${HF_TOKEN:-}" ]]; then
  echo "HF_TOKEN not set: skipping gated EmbeddingGemma files." >&2
  exit 1
fi
fetch "$EMB" embeddinggemma-300M_seq512_mixed-precision.tflite
fetch "$EMB" sentencepiece.model
echo "Record these hashes in the spike results:"
sha256sum embeddinggemma-300M_seq512_mixed-precision.tflite sentencepiece.model
```

- [ ] **Step 4: Push script**

`tools/spike/push-models.sh`:

```bash
#!/usr/bin/env bash
# Pushes spike model files to the phone where SpikeActivity looks for them.
set -euo pipefail

MODELS_DIR="${MODELS_DIR:-$HOME/granger-models}"
PRIMARY=/sdcard/Android/data/app.granger/files/models
FALLBACK=/data/local/tmp/granger-models

files=(gemma-4-E2B-it.litertlm gemma-4-E2B-it-gpu.litertlm gemma-4-E4B-it.litertlm gemma-4-E4B-it-gpu.litertlm
       embeddinggemma-300M_seq512_mixed-precision.tflite sentencepiece.model)

target="${1:-$PRIMARY}"
[[ "$target" == "fallback" ]] && target=$FALLBACK

adb shell mkdir -p "$target"
for f in "${files[@]}"; do
  adb push "$MODELS_DIR/$f" "$target/$f"
done
[[ "$target" == "$FALLBACK" ]] && adb shell chmod -R a+rX "$FALLBACK"
adb shell ls -l "$target"
```

```bash
chmod +x tools/spike/fetch-models.sh tools/spike/push-models.sh
```

- [ ] **Step 5: Build and launch**

```bash
./gradlew installDebug
adb shell am start -n app.granger/app.granger.spike.SpikeActivity
```

Expected: the screen shows the device line (with `tier THINK_DEEPER` on the Pixel 6), the models path, and buttons **Run CPU** and **Run GPU**. Tapping one before models are pushed logs a report whose rows read `check files … missing: <path>`.

- [ ] **Step 6: Commit**

```bash
git add app/src/debug tools/spike
git commit -m "Add debug spike screen and model fetch/push scripts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Run the spike on the Pixel 6 and record decisions

Neal runs the phone steps; the agent writes the results doc from the saved reports.

**Files:**
- Create: `docs/superpowers/spikes/2026-10-model-spike-results.md`
- Modify: `docs/superpowers/specs/2026-10-15-granger-v1-design.md` (only the lines named in Step 6)

**Interfaces:**
- Consumes: everything above.
- Produces: measured numbers and four decisions that Plans 3, 6, and 8 rely on: backend, model file variants, latency target, embedder co-residency.

- [ ] **Step 1: Fetch and push models (≈ 11 GB)**

```bash
HF_TOKEN=<your token> tools/spike/fetch-models.sh
tools/spike/push-models.sh
```

Expected: all four Gemma files `OK` from `sha256sum -c`, two embedder hashes printed, and `ls -l` on the phone listing six files. If SpikeActivity then still reports `missing:`, push to the fallback location with `tools/spike/push-models.sh fallback`.

- [ ] **Step 2: Run CPU, then GPU, from a clean process each time**

Phone on a table, unplugged except USB, screen kept on:

```bash
adb shell am force-stop app.granger && adb shell am start -n app.granger/app.granger.spike.SpikeActivity
# tap "Run CPU", wait for "Saved …"
adb shell am force-stop app.granger && adb shell am start -n app.granger/app.granger.spike.SpikeActivity
# tap "Run GPU", wait for "Saved …"
```

Run each backend twice: the first load builds caches in `cacheDir`. Keep the second run's numbers and note the first-run load time separately, since that's what a user sees on first launch.

If the process dies mid-run (native OOM or crash), capture `adb logcat -d | grep -iE 'litert|oom|lowmemory|FATAL' | tail -50` and record which step it died on.

- [ ] **Step 3: Pull the reports**

```bash
mkdir -p /tmp/granger-spike && adb pull /sdcard/Android/data/app.granger/files/ /tmp/granger-spike/
ls /tmp/granger-spike/files/spike-*.md
```

- [ ] **Step 4: Write the results doc**

Create `docs/superpowers/spikes/2026-10-model-spike-results.md` with these sections, using the pulled reports verbatim for the tables:

```markdown
# Model Spike Results — Pixel 6

Date: <run date> · Device line: <copied from report header> · LiteRT-LM 0.18.0 · RAG SDK 0.3.0

## Raw results
<paste each spike-*.md table: CPU run 2, GPU run 2>

First-run load times (cold cache): CPU small <ms>, GPU small <ms>, CPU large <ms>, GPU large <ms>

EmbeddingGemma file hashes: <two sha256 lines from fetch-models.sh>

## Decisions
1. Backend: <CPU|GPU> — rule below
2. Model files for the catalog: <file names>
3. Latency target: <value> — rule below
4. Embedder: <co-resident|load per query> — rule below
5. Think deeper on 8 GB phones: <viable|not viable> — rule below
```

Apply these rules to fill **Decisions**:

1. **Backend:** choose the backend whose `answer (small)` row has no error and the lower TTFT. If both fail, stop and escalate to Neal; the architecture doesn't hold.
2. **Model files:** the files used by the chosen backend (`SpikeModels.SMALL_GPU`/`LARGE_GPU` or the `_CPU` pair).
3. **Latency target:** the time to first answer token after retrieval is `rewrite (small).millis + answer (small).ttftMs`. If that is ≤ 3000 ms, keep the spec's ≈3 s target. Otherwise set the target to that sum rounded up to the next 500 ms.
4. **Embedder:** co-resident if `answer (small + embedder resident)` has no error and its peak PSS is ≤ 60% of `totalMem`. Otherwise use per-query loading, and record `load embedder (small resident).millis` as the added per-query cost.
5. **Think deeper:** viable if `answer (large)` has no error and `swap small->large` is ≤ 15,000 ms. Otherwise mark it not viable and escalate to Neal before Plan 6. This is a spec-level change, since the 8 GB tier would lose Think deeper.

- [ ] **Step 5: Verify the decisions doc is complete**

Run: `grep -nE '<[a-zA-Z| ]+>' docs/superpowers/spikes/2026-10-model-spike-results.md`
Expected: no output (every placeholder replaced with a measured value or decision).

- [ ] **Step 6: Update the spec with measured facts**

In `docs/superpowers/specs/2026-10-15-granger-v1-design.md`:
- **Success criteria**, the "E2B first token" bullet: replace the text in parentheses with `(measured: <value> on Pixel 6, see docs/superpowers/spikes/2026-10-model-spike-results.md)`, and change the number only if Decision 3 changed it.
- **ModelManager**, the embedder bullet: replace "(to be confirmed by the milestone-1 memory measurements; if not, it loads per query)" with the Decision 4 outcome.
- **Decisions** table, the Runtime row: append `Backend: <Decision 1>; model files: <Decision 2>.`

- [ ] **Step 7: Commit**

```bash
git add docs/superpowers/spikes/2026-10-model-spike-results.md docs/superpowers/specs/2026-10-15-granger-v1-design.md
git commit -m "Record Pixel 6 model spike results and decisions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
