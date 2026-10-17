package app.granger.core.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
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
    /** The ZIM's `Name` metadata, shared by every snapshot of the same content; blank if unknown. */
    @ColumnInfo(defaultValue = "") val name: String = "",
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
            name = archive.metadata.name,
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

/** A kind of media the library files packs under. The four built-ins are seeded; [sortOrder] orders the toolbar. */
@Entity(tableName = "media_types")
data class MediaType(@PrimaryKey val id: String, val name: String, val builtIn: Boolean, val sortOrder: Int)

/**
 * A named group of packs within a [MediaType]. [defaultKey] is the filing key for collections the app made
 * (`"wikipedia"`, `"other"`, ...); null for ones the user created.
 */
@Entity(tableName = "collections", indices = [Index(value = ["defaultKey"], unique = true)])
data class ShelfCollection(
    @PrimaryKey val id: String,
    val name: String,
    val mediaTypeId: String,
    val userCreated: Boolean,
    val defaultKey: String?,
)

/** Files a pack in a collection. [packKey] is `Pack.name` if not blank, else `Pack.id`; [userSet] marks a manual move. */
@Entity(tableName = "placements")
data class Placement(@PrimaryKey val packKey: String, val collectionId: String, val userSet: Boolean)

/** An open article in the reader; [onRail] tabs show in the side rail. */
@Entity(tableName = "reading_tabs")
data class ReadingTab(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val packId: String,
    val path: String,
    val title: String,
    val openedAt: Long,
    val onRail: Boolean,
)

/** An article that was opened, one row per article. */
@Entity(tableName = "history", primaryKeys = ["packId", "path"], indices = [Index("openedAt")])
data class HistoryEntry(val packId: String, val path: String, val title: String, val openedAt: Long)

@Entity(tableName = "recent_searches")
data class RecentSearch(@PrimaryKey val query: String, val searchedAt: Long)
