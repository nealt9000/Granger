package app.granger.core.books

import kotlinx.io.IOException

/*
 * What the book rules need from each phone (Plan I5 call 6). Android's are the same java.io, java.util.zip and
 * java.time calls the rules made before they were shared, so an EPUB Android writes is byte for byte what it was.
 * Paths are absolute strings with '/' between folders on both phones.
 */

/** An open zip archive to read entries from (Android: `java.util.zip.ZipFile`, so its callers pass one as before). */
expect class BookZip : AutoCloseable

/** Where an EPUB is written (Android: `java.io.OutputStream`). */
expect abstract class BookOutput : AutoCloseable {
    override fun close()
}

/** A file that isn't a zip archive at all. */
class NotAZipException(message: String?) : IOException(message)

/** Opens the zip at [path]: [NotAZipException] when it isn't one, another [IOException] when it can't be read. */
internal expect fun openBookZip(path: String): BookZip

internal expect fun BookZip.has(name: String): Boolean

/**
 * The entry [name], or null when there is none. Enforces [limit] on the bytes actually inflated (the declared size is
 * not trusted): throws [IOException] when the entry is over it or corrupt.
 */
internal expect fun BookZip.readCapped(name: String, limit: Int): ByteArray?

/** Writes a zip into a [BookOutput] and closes it when closed. Entries are deflated unless [stored]. */
internal expect class EpubZip(out: BookOutput) : AutoCloseable {
    /** An uncompressed entry (an EPUB's `mimetype`, which must come first). */
    fun stored(name: String, bytes: ByteArray)
    fun put(name: String, bytes: ByteArray)
    /** The file at [path] as the entry [name]. */
    fun putFile(name: String, path: String)
}

/** A new file at [path] to write into (replacing any). */
internal expect fun outputTo(path: String): BookOutput

/** The folder holding [path], made absolute. */
internal expect fun parentFolder(path: String): String

/** Makes the folder [path] and any above it. */
internal expect fun makeFolders(path: String)

/** Removes [path], and everything in it when it's a folder; quiet when it isn't there. */
internal expect fun deleteAll(path: String)

/** [path]'s size in bytes; 0 when it isn't there. */
internal expect fun fileLength(path: String): Long

/** Moves [from] to [to], replacing [to]; copies when a rename can't (another volume). Throws [IOException] on failure. */
internal expect fun moveOrCopy(from: String, to: String)

/** The file's name, as `java.io.File.name` gives it. */
internal fun nameOf(path: String): String = path.trimEnd('/').substringAfterLast('/')

/** The file's name without its last extension, as `java.io.File.nameWithoutExtension` gives it. */
internal fun stemOf(path: String): String = nameOf(path).substringBeforeLast(".")

/** A name-based (version 3, MD5) UUID of [bytes], as `java.util.UUID.nameUUIDFromBytes` makes it. */
internal expect fun nameUuid(bytes: ByteArray): String

/** A random UUID, for scratch names. */
internal expect fun randomUuid(): String

/** Now, in ISO 8601 to the second (`2026-11-21T09:30:00Z`). */
internal expect fun nowIsoSeconds(): String

/** An href's path part, percent-decoded, as `java.net.URI(href).path` reads it; [href] itself when it can't be read. */
internal expect fun uriPath(href: String): String

/** A text file's bytes as text: by its byte-order mark, else strict UTF-8, else Windows-1252. */
internal expect fun decodeText(bytes: ByteArray): String
