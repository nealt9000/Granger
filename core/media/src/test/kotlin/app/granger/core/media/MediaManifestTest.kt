package app.granger.core.media

import app.granger.core.library.MediaOrigin
import app.granger.core.media.ManifestFixtures.HOLST
import app.granger.core.media.ManifestFixtures.HOLST_NAMES
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MediaManifestTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun manifestRoundTrips() {
        val dir = tmp.newFolder("817")
        val tagged = ManifestFixtures.timeMachine().let { m ->
            m.copy(files = m.files.mapIndexed { i, f ->
                if (i == 0) f.copy(tags = CachedTags("Chapter 1", 601_000L, -6.5f, 0.98f, null, null, ".cover-0123456789abcdef.jpg")) else f
            })
        }
        MediaManifests.write(dir, tagged)
        assertThat(MediaManifests.read(dir)).isEqualTo(tagged)
        // Written by temp file and rename: nothing else is left in the folder.
        assertThat(dir.list()!!.toList()).containsExactly(MediaManifests.FILE_NAME)

        // Rewriting replaces it whole.
        val changed = tagged.copy(title = "The Time Machine")
        MediaManifests.write(dir, changed)
        assertThat(MediaManifests.read(dir)).isEqualTo(changed)
    }

    @Test fun missingTruncatedOrForeignManifestReadsAsNull() {
        val dir = tmp.newFolder("x")
        assertThat(MediaManifests.read(dir)).isNull()
        MediaManifests.write(dir, ManifestFixtures.holst())
        val json = File(dir, MediaManifests.FILE_NAME).readText()
        File(dir, MediaManifests.FILE_NAME).writeText(json.substring(0, json.length / 2))
        assertThat(MediaManifests.read(dir)).isNull()
        File(dir, MediaManifests.FILE_NAME).writeText("""{"hello": "world"}""")
        assertThat(MediaManifests.read(dir)).isNull()
        File(dir, MediaManifests.FILE_NAME).writeBytes(byteArrayOf(0, -1, 3))
        assertThat(MediaManifests.read(dir)).isNull()
    }

    @Test fun localNamesAreFatSafeAndOrdered() {
        assertThat(MediaManifests.localName(0, "Mars: Bringer?", "x.mp3")).isEqualTo("01 - Mars_ Bringer_.mp3")
        assertThat(MediaManifests.localName(99, "Last", "x.mp3")).isEqualTo("100 - Last.mp3")
        // No title: the remote name's stem.
        assertThat(MediaManifests.localName(2, "", "timemachine_03_wells_64kb.mp3")).isEqualTo("03 - timemachine_03_wells_64kb.mp3")
        assertThat(MediaManifests.localName(0, "", HOLST_NAMES[0])).isEqualTo("01 - The Planets Op. 32, H. 125_ I. Mars, The Bringer of War.mp3")
        assertThat(MediaManifests.localName(1, "", HOLST_NAMES[1]))
            .isEqualTo("02 - The Planets Op. 32, H. 125_ II. Venus, The Bringer of Peace [B09zijg_gQ4].mp3")
        assertThat(MediaManifests.localName(0, "a/b\\c\"d*e<f>g|h", "x.ogg")).isEqualTo("01 - a_b_c_d_e_f_g_h.ogg")
        assertThat(MediaManifests.localName(0, "Ünïcödé", "x.flac")).isEqualTo("01 - _n_c_d_.flac")
        val long = MediaManifests.localName(4, "word ".repeat(60), "x.mp3")
        assertThat(long.length).isAtMost(120)
        assertThat(long).startsWith("05 - word word")
        assertThat(long).endsWith(".mp3")
        assertThat(long.removeSuffix(".mp3")).doesNotMatch(".*[ .]$")
        // Never a path, a dotfile or empty.
        assertThat(MediaManifests.localName(0, "..", "..")).doesNotContain("/")
        assertThat(MediaManifests.localName(0, "..", "..")).doesNotMatch("^\\..*")
    }

    @Test fun slugTitleComesFromTheFileNames() {
        assertThat(MediaManifests.displayTitle(HOLST, HOLST, HOLST_NAMES)).isEqualTo("The Planets Op. 32, H. 125")
        assertThat(MediaManifests.displayTitle(ManifestFixtures.holst())).isEqualTo("The Planets Op. 32, H. 125")
    }

    @Test fun slugWithNoCommonPrefixReadsAsWords() {
        val words = "Holst The Planets Holst London Symphony Orchestra 1922 1924"
        assertThat(MediaManifests.displayTitle(HOLST, HOLST, listOf("01.mp3", "02.mp3"))).isEqualTo(words)
        // A prefix under 3 words and under 12 characters.
        assertThat(MediaManifests.displayTitle(HOLST, HOLST, listOf("Holst Mars.mp3", "Holst Venus.mp3"))).isEqualTo(words)
        // One file: its whole name is no album title.
        assertThat(MediaManifests.displayTitle(HOLST, HOLST, HOLST_NAMES.take(1))).isEqualTo(words)
        assertThat(MediaManifests.displayTitle(HOLST, HOLST, emptyList())).isEqualTo(words)
        // Cut back to a word boundary: "The Planets Ope" / "The Planets Opus" share "The Planets Op", which ends inside a word.
        assertThat(MediaManifests.displayTitle(HOLST, HOLST, listOf("The Planets Suite Opera 1.mp3", "The Planets Suite Opus 2.mp3")))
            .isEqualTo("The Planets Suite")
        // A blank title reads from its identifier.
        assertThat(MediaManifests.displayTitle("", "benny_bell-songs", emptyList())).isEqualTo("Benny Bell Songs")
    }

    @Test fun aRealTitleIsNeverReplaced() {
        assertThat(MediaManifests.displayTitle("The Time Machine", "x", HOLST_NAMES)).isEqualTo("The Time Machine")
        assertThat(MediaManifests.displayTitle("Mars", HOLST, HOLST_NAMES)).isEqualTo("Mars")
        // Only the identifier itself is a slug: a title that merely looks like one is the uploader's own.
        assertThat(MediaManifests.displayTitle("Jean-Luc_Live", HOLST, HOLST_NAMES)).isEqualTo("Jean-Luc_Live")
        assertThat(MediaManifests.displayTitle("Time Machine", "817", emptyList())).isEqualTo("Time Machine")
    }

    @Test fun relativeDirsFollowTheSpecLayout() {
        assertThat(MediaManifests.relativeDir(ManifestFixtures.timeMachine())).isEqualTo("audiobooks/librivox/817")
        assertThat(MediaManifests.relativeDir(ManifestFixtures.holst())).isEqualTo("music/archive-org/$HOLST")
        val spoken = ManifestFixtures.holst().copy(kind = MediaManifest.AUDIOBOOK)
        assertThat(MediaManifests.relativeDir(spoken)).isEqualTo("audiobooks/archive-org/$HOLST")
    }

    @Test fun idsAreStableAndHaveNoAt() {
        val holst = ManifestFixtures.holst()
        val mars = holst.files[0]
        val id = DownloadedIds.trackId(holst, mars)
        assertThat(id).matches("track:archive-org:$HOLST:[0-9a-f]{16}")
        assertThat(id).doesNotContain("@")
        // Same remote name, same id, whatever the local name, title or size.
        assertThat(DownloadedIds.trackId(holst, mars.copy(name = "x.mp3", title = "Mars", sizeBytes = 5))).isEqualTo(id)
        assertThat(DownloadedIds.trackId(holst, holst.files[1])).isNotEqualTo(id)
        assertThat(DownloadedIds.audiobookId(holst.copy(kind = MediaManifest.AUDIOBOOK))).isEqualTo("audiobook:archive-org:$HOLST")
        assertThat(DownloadedIds.origin(holst)).isEqualTo(MediaOrigin.ARCHIVE_ORG)

        val book = ManifestFixtures.timeMachine()
        assertThat(DownloadedIds.audiobookId(book)).isEqualTo("audiobook:librivox:817")
        assertThat(DownloadedIds.trackId(book, book.files[2])).isEqualTo("track:librivox:817:4002")
        assertThat(DownloadedIds.origin(book)).isEqualTo(MediaOrigin.LIBRIVOX)
    }
}
