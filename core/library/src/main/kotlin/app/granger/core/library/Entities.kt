package app.granger.core.library

import androidx.room.Entity
import androidx.room.PrimaryKey
import app.granger.core.zim.ZimArchive
import java.io.File

enum class PackStatus {
    OK,
    /** The file is gone (deleted, or its SD card is out). Bookmarks are kept for when it returns. */
    MISSING,
    /** The file is there but libzim can't open it. */
    CORRUPT,
}

/** An installed ZIM pack. [id] is the ZIM's UUID, or `file:<path>` for a file that never opened. */
@Entity(tableName = "packs")
data class Pack(
    @PrimaryKey val id: String,
    val filePath: String,
    val title: String,
    val description: String,
    val language: String,
    val date: String,
    val sizeBytes: Long,
    val hasFulltextIndex: Boolean,
    val status: PackStatus,
    val addedAt: Long,
) {
    companion object {
        fun from(archive: ZimArchive, file: File, addedAt: Long) = Pack(
            id = archive.uuid,
            filePath = file.absolutePath,
            title = archive.metadata.title,
            description = archive.metadata.description,
            language = archive.metadata.language,
            date = archive.metadata.date,
            sizeBytes = file.length(),
            hasFulltextIndex = archive.hasFulltextIndex,
            status = PackStatus.OK,
            addedAt = addedAt,
        )

        fun unreadable(file: File, addedAt: Long) = Pack(
            id = "file:${file.absolutePath}",
            filePath = file.absolutePath,
            title = file.nameWithoutExtension,
            description = "",
            language = "",
            date = "",
            sizeBytes = file.length(),
            hasFulltextIndex = false,
            status = PackStatus.CORRUPT,
            addedAt = addedAt,
        )
    }
}

@Entity(tableName = "bookmarks", primaryKeys = ["packId", "path"])
data class Bookmark(val packId: String, val path: String, val title: String, val createdAt: Long)

@Entity(tableName = "reading_positions", primaryKeys = ["packId", "path"])
data class ReadingPosition(val packId: String, val path: String, val scrollFraction: Float, val updatedAt: Long)
