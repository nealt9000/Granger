package app.granger.core.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The phone's shared audio list, the one Auxio reads: music, audiobooks and podcasts on every external volume.
 * Ringtones, alarms, notifications and recordings are left out. Needs the audio permission; only queries.
 */
class MediaStoreSource(private val context: Context) : AudioSource {
    override fun owns(uri: String): Boolean = uri.startsWith("content://${MediaStore.AUTHORITY}/")

    override suspend fun list(): List<FoundFile> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID, MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.IS_AUDIOBOOK, MediaStore.Audio.Media.IS_MUSIC, MediaStore.Audio.Media.IS_PODCAST,
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.IS_AUDIOBOOK} != 0 OR " +
            "${MediaStore.Audio.Media.IS_PODCAST} != 0"
        val cursor = context.contentResolver.query(collection, projection, selection, null, null)
            ?: throw IOException("MediaStore returned no cursor")
        cursor.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val name = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val path = c.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH)
            val size = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val modified = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val audiobook = c.getColumnIndexOrThrow(MediaStore.Audio.Media.IS_AUDIOBOOK)
            buildList {
                while (c.moveToNext()) {
                    add(FoundFile(
                        uri = ContentUris.withAppendedId(collection, c.getLong(id)).toString(),
                        displayName = c.getString(name).orEmpty(),
                        folder = c.getString(path).orEmpty().let { if (it.isEmpty() || it.endsWith('/')) it else "$it/" },
                        sizeBytes = c.getLong(size),
                        modifiedMs = c.getLong(modified) * 1_000L, // DATE_MODIFIED is in seconds
                        durationMs = if (c.isNull(duration)) 0L else c.getLong(duration),
                        systemAudiobook = !c.isNull(audiobook) && c.getInt(audiobook) != 0,
                        inAudiobookFolder = false,
                        // folder.jpg / cover.jpg are images, outside what the audio permission can see.
                        folderCoverUri = null,
                    ))
                }
            }
        }
    }
}
