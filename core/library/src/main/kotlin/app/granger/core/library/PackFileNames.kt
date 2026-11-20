package app.granger.core.library

import app.granger.core.zim.ZimArchive
import java.io.File

/** Android's `File` forms of [Pack.from] and [Pack.unreadable] (the shared ones take a path and a size; Plan I3). */
fun Pack.Companion.from(archive: ZimArchive, file: File, addedAt: Long) = from(archive, file.absolutePath, file.length(), addedAt)

fun Pack.Companion.unreadable(file: File, addedAt: Long) = unreadable(file.absolutePath, file.length(), addedAt)

/** [PackDao.get] for Android's blocking callers, as it was before Plan I3 made it suspend for shared code. Off the main thread. */
fun PackDao.getBlocking(id: String): Pack? = kotlinx.coroutines.runBlocking { get(id) }
