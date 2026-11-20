package app.granger.core.library

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import platform.Foundation.NSString
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUUID
import platform.Foundation.stringWithContentsOfFile
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The library database on the iPhone: a version-1 file migrates to the current version; a fresh one is seeded. */
class StoreIosTest {
    @OptIn(ExperimentalForeignApi::class)
    private val schemas = (getenv("GRANGER_REPO")?.toKString() ?: error("GRANGER_REPO not set")) +
        "/core/store/schemas/app.granger.core.library.LibraryDatabase"

    private fun tmp() = NSTemporaryDirectory() + NSUUID().UUIDString + ".db"

    @OptIn(ExperimentalForeignApi::class)
    private fun schema(version: Int) = Json.parseToJsonElement(
        NSString.stringWithContentsOfFile("$schemas/$version.json", NSUTF8StringEncoding, null) ?: error("no schema $version"),
    ).jsonObject.getValue("database").jsonObject

    /** A database exactly as Room created it at [version], from its exported schema. */
    private fun createAt(version: Int, path: String) {
        val db = schema(version)
        val c: SQLiteConnection = BundledSQLiteDriver().open(path)
        for (e in db.getValue("entities").jsonArray) {
            val table = e.jsonObject.getValue("tableName").jsonPrimitive.content
            c.execSQL(e.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            e.jsonObject["indices"]?.jsonArray?.forEach { i ->
                c.execSQL(i.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
            }
        }
        db.getValue("setupQueries").jsonArray.forEach { c.execSQL(it.jsonPrimitive.content) }
        c.execSQL("PRAGMA user_version = $version")
        c.close()
    }

    private fun count(path: String, sql: String): Long {
        val c = BundledSQLiteDriver().open(path)
        try {
            val st = c.prepare(sql)
            try { st.step(); return st.getLong(0) } finally { st.close() }
        } finally { c.close() }
    }

    @Test fun aVersionOneDatabaseMigratesToTheCurrentOne() = runBlocking {
        val path = tmp()
        createAt(1, path)
        val db = openLibraryDatabase(path)
        // Touching a table opens the database and runs every migration (2 by auto-migration, 3..18 by hand).
        assertEquals(0, db.packDao().getAll().size)
        db.close()
        assertEquals(18L, count(path, "PRAGMA user_version"))
        assertEquals(1L, count(path, "SELECT count(*) FROM pragma_table_info('books') WHERE name = 'loose'"))
        assertEquals(1L, count(path, "SELECT count(*) FROM sqlite_master WHERE name = 'book_text'"))
        assertTrue(count(path, "SELECT count(*) FROM media_types WHERE builtIn = 1") >= 4)
    }

    @Test fun aFreshDatabaseIsSeeded() = runBlocking {
        val path = tmp()
        val db = openLibraryDatabase(path)
        db.inTransaction { db.keptArticleDao().all() }
        db.close()
        assertEquals(5L, count(path, "SELECT count(*) FROM media_types WHERE builtIn = 1"))
        assertEquals(1L, count(path, "SELECT count(*) FROM sqlite_master WHERE name = 'book_text'"))
    }
}
