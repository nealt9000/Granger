package app.granger.media

import app.granger.core.archive.ArchiveItem
import app.granger.core.archive.ArchiveKind
import app.granger.core.archive.ArchiveOrgClient
import app.granger.core.archive.ArchiveAudioOffers
import app.granger.core.archive.AudioOffer
import app.granger.core.archive.LibriVoxBook
import app.granger.core.archive.LibriVoxClient
import app.granger.core.archive.LibriVoxOffer
import app.granger.core.archive.LibriVoxOffers
import app.granger.core.media.ManifestFile
import app.granger.core.media.MediaManifest
import app.granger.core.media.MediaManifests
import java.io.File
import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody

/** The recorded archive.org and LibriVox fixtures (copied from core/archive), served with no network. */
object MediaFixtures {
    const val HOLST = "holst-the-planets-holst-london-symphony-orchestra-1922-1924"
    const val TIME_MACHINE = "time_machine_0805_librivox"

    private fun resource(name: String) =
        MediaFixtures::class.java.classLoader!!.getResourceAsStream("archive/$name")!!.readBytes().decodeToString()

    private fun http(body: (String) -> String) = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).message("").code(200)
            .body(body(chain.request().url.encodedPath).toResponseBody()).build()
    }).build()

    val archive = ArchiveOrgClient(
        http { path ->
            when {
                path.endsWith("/$HOLST") -> resource("item-holst-planets.json")
                path.endsWith("/$TIME_MACHINE") -> resource("item-librivox-time-machine.json")
                else -> "{}"
            }
        },
        "Granger/1.0 (+test)",
    )

    val holstItem: ArchiveItem by lazy { runBlocking { archive.item(HOLST) } }

    val holstOffer: AudioOffer.Tracks by lazy { ArchiveAudioOffers.choose(holstItem) as AudioOffer.Tracks }

    fun holst(now: Long = 1L): MediaManifest = MediaDownloads.albumManifest(holstItem, holstOffer, ArchiveKind.MUSIC, archive, now)

    val timeMachineBook: LibriVoxBook by lazy {
        runBlocking { LibriVoxClient(http { resource("librivox-book-817.json") }, "Granger/1.0 (+test)", sleep = {}).book("817") }
    }

    val timeMachineOffer: LibriVoxOffer.Files by lazy {
        LibriVoxOffers.choose(timeMachineBook, runBlocking { archive.item(TIME_MACHINE) }) as LibriVoxOffer.Files
    }

    fun timeMachine(now: Long = 2L): MediaManifest = MediaDownloads.bookManifest(timeMachineOffer, archive, now)

    /** Writes [f] into [m]'s folder on [root] at its full size (sparse: no bytes are written). */
    fun arrive(root: File, m: MediaManifest, f: ManifestFile): File {
        val file = File(File(root, MediaManifests.relativeDir(m)).apply { mkdirs() }, f.name)
        RandomAccessFile(file, "rw").use { it.setLength(f.sizeBytes) }
        return file
    }
}
