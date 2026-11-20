package app.granger.core.library

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedForNowIosTest {
    private var now = 1000L
    private fun store(dir: String = NSTemporaryDirectory() + NSUUID().UUIDString) = SavedForNow(dir) { now++ }

    @Test fun putGetTrimAndClear() {
        val dir = NSTemporaryDirectory() + NSUUID().UUIDString
        val s = store(dir)
        s.put("en:page:Moon", "PAGE", ByteArray(100) { 1 }, "text/html")
        s.put("img:a.jpg", "PICTURE", ByteArray(300) { 2 }, "image/jpeg")
        s.put("en:sum:Moon", "SUMMARY", ByteArray(50) { 3 }, "application/json")
        assertEquals(450L, s.size())
        assertContentEquals(ByteArray(100) { 1 }, s.get("en:page:Moon")!!.bytes)   // opened now: the newest
        s.trim(200)
        assertTrue(s.has("en:page:Moon"), "kept: opened most recently")
        assertFalse(s.has("img:a.jpg"), "the oldest-opened went first")
        // A new store over the same folder finds what was saved.
        assertEquals("text/html", store(dir).get("en:page:Moon")!!.mime)
        s.clear()
        assertNull(s.get("en:page:Moon"))
        assertEquals(0L, s.size())
    }
}
