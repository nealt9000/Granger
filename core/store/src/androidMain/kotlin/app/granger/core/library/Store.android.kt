package app.granger.core.library

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

actual suspend fun <R> RoomDatabase.inTransaction(block: suspend () -> R): R = withTransaction(block)

/** The phone's library database (`library.db`), in Room's framework-SQLite mode as before Plan I3. */
fun LibraryDatabase.Companion.create(context: Context): LibraryDatabase =
    Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db")
        .addMigrations(*MIGRATIONS)
        .addCallback(SeedCallback)
        .build()
