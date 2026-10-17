package app.granger.core.library

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider

fun inMemoryDatabase(): LibraryDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), LibraryDatabase::class.java)
        .addCallback(LibraryDatabase.SeedCallback)
        .allowMainThreadQueries()
        .build()

fun pack(id: String, title: String = id, status: PackStatus = PackStatus.OK) =
    Pack(id, "/packs/$id.zim", title, "", "eng", "2026-10-15", 1_000L, true, status, 0L)
