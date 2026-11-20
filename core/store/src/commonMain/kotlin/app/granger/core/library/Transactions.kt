package app.granger.core.library

import androidx.room.RoomDatabase

/**
 * Runs [block] in one write transaction. Android uses Room's `withTransaction`, exactly as before Plan I3; iOS uses
 * the shared form (`useWriterConnection { immediateTransaction { } }`), which shared code must use.
 */
expect suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R
