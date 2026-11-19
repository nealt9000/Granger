package app.granger.online

import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/**
 * Plan O1 Task 1: Local only is honest only while every request goes through the shared client's gate. This reads the
 * app's and the core modules' sources and fails on a second HTTP client or a connection opened another way.
 *
 * Known and fine (O0 Task 5): the reader's WebView serves only what [app.granger.reader.ZimWebViewClient] hands it and
 * blocks network loads; the player plays local files. When LibriVox streaming comes, its player must read through an
 * OkHttp data source on the shared client, or this test's list must say why not.
 */
class NetworkAuditTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private fun sources(): List<File> = (listOf(File(root, "app/src/main")) + (File(root, "core").listFiles().orEmpty().map { File(it, "src/main") }))
        .filter { it.isDirectory }
        .flatMap { dir -> dir.walk().filter { it.isFile && it.extension == "kt" }.toList() }

    @Test fun oneHttpClientForTheWholeApp() {
        val builders = sources().flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (Regex("OkHttpClient(\\.Builder)?\\(\\)").containsMatchIn(l)) "${f.relativeTo(root)}:${i + 1}" else null } }
        assertWithMessage("HTTP clients built outside the shared one: derive with http.newBuilder() instead").that(builders.map { it.substringBefore(':') })
            .containsExactly("app/src/main/kotlin/app/granger/GrangerApp.kt")
    }

    @Test fun noConnectionsAroundTheClient() {
        val bad = Regex("HttpURLConnection|\\.openConnection\\(|URL\\([^)]*\\)\\.(openStream|readText|readBytes)|DefaultHttpDataSource|Socket\\(")
        val found = sources().flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (bad.containsMatchIn(l) && !l.trimStart().startsWith("*") && !l.trimStart().startsWith("//")) "${f.relativeTo(root)}:${i + 1}: ${l.trim()}" else null } }
        assertWithMessage("network use that bypasses the gate").that(found).isEmpty()
    }
}
