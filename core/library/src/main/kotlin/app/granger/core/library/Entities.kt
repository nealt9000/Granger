package app.granger.core.library

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import app.granger.core.research.SourceKind
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

/**
 * Files a pack or a book in a collection. [packKey] is `Pack.name` if not blank, else `Pack.id`; for a book it is
 * `Book.id`. [userSet] marks a manual move.
 */
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

/** An e-book on the shelf. [sourceRef] is the catalog id, the import's sha256, or the archive.org identifier. */
@Entity(tableName = "books")
data class Book(
    @PrimaryKey val id: String,
    val origin: BookOrigin,
    val sourceRef: String,
    val filePath: String,
    val title: String,
    val author: String,
    val translator: String,
    val date: String,
    val language: String,
    /** `"; "`-joined. */
    val subjects: String,
    val description: String,
    val coverPath: String?,
    val sizeBytes: Long,
    val status: PackStatus,
    /** When the text was last put into the `book_text` index; null if not indexed. */
    val indexedAt: Long?,
    val addedAt: Long,
)

/** Where the reader left off in a book. [locatorJson] is opaque to the library. */
@Entity(tableName = "book_positions")
data class BookPosition(
    @PrimaryKey val bookId: String,
    val locatorJson: String,
    val totalProgression: Double,
    val chapterTitle: String,
    val updatedAt: Long,
)

enum class MarkKind { BOOKMARK, HIGHLIGHT }

/** A bookmark or highlight. [color] is "yellow", "green", "blue" or "pink"; blank for bookmarks. */
@Entity(tableName = "book_marks", indices = [Index("bookId")])
data class BookMark(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val bookId: String,
    val kind: MarkKind,
    val locatorJson: String,
    val text: String,
    val color: String,
    val note: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/**
 * A searchable slice of a book. Its [id] is the `docid` of the matching `book_text` row. AUTOINCREMENT means ids
 * are never reused: contentless FTS4 rows can't be deleted, so a reused id would match stale text.
 */
@Entity(tableName = "book_chunks", indices = [Index("bookId")])
data class BookChunk(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val bookId: String,
    val href: String,
    val ordinal: Int,
    val progression: Double,
)

/** [FAILED] is the model's failure; [SEARCH_FAILED] is the library search or its storage. */
enum class TurnOutcome { PENDING, ANSWERED, DECLINED, UNVERIFIED, FAILED, INTERRUPTED, SEARCH_FAILED }

/** A kept Research conversation. */
@Entity(tableName = "research_chats")
data class ResearchChat(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** One question and its answer in a chat. [keywords] is `"; "`-joined; blank when there are none. */
@Entity(tableName = "research_turns", indices = [Index("chatId")])
data class ResearchTurn(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val chatId: Long,
    val question: String,
    val outcome: TurnOutcome,
    val answer: String,
    val medical: Boolean,
    val usedLarge: Boolean,
    val askedDeeper: Boolean,
    val keywords: String,
    val createdAt: Long,
    /** The model's own best guess after a decline; blank until asked. */
    @ColumnInfo(defaultValue = "''") val guess: String = "",
    @ColumnInfo(defaultValue = "0") val guessLarge: Boolean = false,
    @ColumnInfo(defaultValue = "0") val guessFellBack: Boolean = false,
)

enum class PassageRole {
    /** The numbered passages given to the model, in order. */
    SHOWN,
    /** Extras kept for Think deeper. */
    CANDIDATE,
}

/** A cited or candidate passage of a turn. [ordinal] orders them within the turn, shown ones first. */
@Entity(tableName = "research_passages", primaryKeys = ["turnId", "ordinal"])
data class ResearchPassage(
    val turnId: Long,
    val ordinal: Int,
    val role: PassageRole,
    val kind: SourceKind,
    val sourceId: String,
    val sourceTitle: String,
    val packTitle: String,
    val location: String,
    val anchor: String?,
    val progression: Double,
    val heading: String,
    val text: String,
    val author: String,
    val date: String,
    val subjects: String,
    val collection: String,
)
