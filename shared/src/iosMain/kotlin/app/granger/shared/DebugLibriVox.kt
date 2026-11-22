package app.granger.shared

import app.granger.core.archive.ArchiveHttp
import app.granger.core.archive.HttpReply
import app.granger.core.downloads.DownloadRequest
import app.granger.core.downloads.DownloadResult
import app.granger.core.downloads.FileTransfer
import app.granger.core.media.MediaDisk
import app.granger.core.text.hex
import app.granger.core.text.sha1
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Debug builds and UI tests (Plan I6 Task 7): one LibriVox book made of the audio files in a folder, a chapter a file in
 * name order, answered as LibriVox's feed and archive.org's metadata would answer it, and a transfer that copies each
 * chapter from that folder. A LibriVox download then runs through the shared model, queue, runner and downloaded-media
 * scan with no network at all.
 */
internal object DebugLibriVox {
    const val ID = "817"
    const val IDENTIFIER = "granger_stub_librivox"
    const val TITLE = "Stub Tones"

    fun install(library: IosLibrary, dir: String) {
        val files = (MediaDisk.list(dir) ?: emptyList())
            .filter { MediaDisk.isFile(it) && it.substringAfterLast('.').lowercase() in setOf("m4a", "mp3", "aac") }
            .sortedBy { it.substringAfterLast('/') }
        val chapters = files.map { path ->
            val bytes = SystemFileSystem.source(Path(path)).buffered().use { it.readByteArray() }
            Chapter(path.substringAfterLast('/'), bytes.size.toLong(), sha1(bytes).hex())
        }
        val feed = buildJsonObject {
            put("books", buildJsonArray {
                add(buildJsonObject {
                    put("id", ID)
                    put("title", TITLE)
                    put("authors", buildJsonArray { add(buildJsonObject { put("first_name", "Test"); put("last_name", "Composer") }) })
                    put("description", "Sine tones, made by the test.")
                    put("language", "English")
                    put("totaltimesecs", chapters.size * 3)
                    put("copyright_year", "1900")
                    put("url_iarchive", "https://archive.org/details/$IDENTIFIER")
                    put("url_librivox", "https://librivox.org/stub-tones/")
                    put("num_sections", chapters.size)
                    put("sections", JsonArray(chapters.mapIndexed { i, c ->
                        buildJsonObject {
                            put("id", "${9000 + i}")
                            put("section_number", i + 1)
                            put("title", "Tone ${i + 1}")
                            put("listen_url", "https://archive.org/download/$IDENTIFIER/${c.name}")
                            put("playtime", 3)
                            put("readers", buildJsonArray { add(buildJsonObject { put("display_name", "Test Reader") }) })
                        }
                    }))
                })
            })
        }.toString()
        val item = buildJsonObject {
            put("metadata", buildJsonObject {
                put("identifier", IDENTIFIER)
                put("title", TITLE)
                put("creator", "Test Composer")
                put("mediatype", "audio")
                put("date", "1900")
            })
            put("files", JsonArray(chapters.map { c ->
                buildJsonObject {
                    put("name", c.name)
                    put("format", "64Kbps MP3")
                    put("size", JsonPrimitive(c.size.toString()))
                    put("sha1", c.sha1)
                    put("source", "derivative")
                }
            }))
        }.toString()
        library.debugHttp = ArchiveHttp { url, _ ->
            when {
                "librivox.org/api/" in url -> HttpReply(200, feed)
                "/metadata/$IDENTIFIER" in url -> HttpReply(200, item)
                else -> HttpReply(404, "{}")
            }
        }
        library.debugTransfer = object : FileTransfer {
            override suspend fun download(request: DownloadRequest, partPath: String, onProgress: (Long) -> Unit): DownloadResult {
                val name = request.urls.firstOrNull()?.substringAfterLast('/') ?: return DownloadResult.Failed("no url", permanent = true)
                val from = MediaDisk.child(dir, name)
                if (!MediaDisk.isFile(from)) return DownloadResult.Failed("no stub file $name", permanent = true)
                MediaDisk.parent(partPath)?.let { SystemFileSystem.createDirectories(Path(it)) }
                SystemFileSystem.source(Path(from)).buffered().use { i -> SystemFileSystem.sink(Path(partPath)).buffered().use { o -> i.transferTo(o) } }
                onProgress(MediaDisk.length(partPath))
                return DownloadResult.Done
            }
        }
        library.logLine("debugLibriVoxStub: ${chapters.size} chapters from $dir")
    }

    private class Chapter(val name: String, val size: Long, val sha1: String)
}
