package app.granger.core.media

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MediaStoreSourceTest {
    /** A MediaStore stand-in answering every query with [rows], in the columns asked for (missing ones null). */
    class FakeMediaProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor {
            lastProjection = projection!!.toList()
            return MatrixCursor(projection).apply { rows.forEach { row -> addRow(projection.map { row[it] }) } }
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0

        companion object {
            var rows: List<Map<String, Any?>> = emptyList()
            var lastProjection: List<String> = emptyList()
        }
    }

    private var nextId = 1L

    private fun row(name: String, path: String?, recording: Int? = 0) = mapOf(
        MediaStore.Audio.Media._ID to nextId++,
        MediaStore.Audio.Media.DISPLAY_NAME to name,
        MediaStore.Audio.Media.RELATIVE_PATH to path,
        MediaStore.Audio.Media.SIZE to 1000L,
        MediaStore.Audio.Media.DATE_MODIFIED to 10L,
        MediaStore.Audio.Media.DURATION to 60_000L,
        MediaStore.Audio.Media.IS_AUDIOBOOK to 0,
        MediaStore.Audio.Media.IS_MUSIC to 1,
        MediaStore.Audio.Media.IS_PODCAST to 0,
        MediaStore.Audio.Media.IS_RECORDING to recording,
    )

    @Before fun setUp() {
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
    }

    private suspend fun names() = MediaStoreSource(ApplicationProvider.getApplicationContext()).list().map { it.displayName }

    @Test fun skipsFilesInAppOwnedAndroidFolders() = runTest {
        FakeMediaProvider.rows = listOf(
            row("song.mp3", "Music/Band/"),
            row("voice.opus", "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Voice Notes/202642/"),
            row("doc.mp3", "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/WhatsApp Business Documents/"),
            row("cache.mp3", "Android/data/com.example/files/"),
            row("obb.ogg", "Android/obb/com.game/"),
            row("upper.mp3", "ANDROID/Media/com.example/"),
            row("lower.mp3", "android/media/com.example/"),
            row("android-named.mp3", "Music/Android/"),
            row("androidish.mp3", "AndroidMusic/"),
            row("root.mp3", null),
        )
        assertThat(names()).containsExactly("song.mp3", "android-named.mp3", "androidish.mp3", "root.mp3").inOrder()
    }

    @Test fun skipsRecordingsWhereTheColumnExists() = runTest {
        FakeMediaProvider.rows = listOf(
            row("song.mp3", "Music/"),
            row("memo.m4a", "Recordings/", recording = 1),
            row("unknown.mp3", "Music/", recording = null),
        )
        assertThat(names()).containsExactly("song.mp3", "unknown.mp3").inOrder()
        assertThat(FakeMediaProvider.lastProjection).contains(MediaStore.Audio.Media.IS_RECORDING)
    }

    @Config(sdk = [29])
    @Test fun leavesOutTheRecordingColumnBeforeApi31() = runTest {
        FakeMediaProvider.rows = listOf(row("song.mp3", "Music/"), row("voice.opus", "Android/media/com.whatsapp/"))
        assertThat(names()).containsExactly("song.mp3")
        assertThat(FakeMediaProvider.lastProjection).doesNotContain(MediaStore.Audio.Media.IS_RECORDING)
    }
}
