package app.granger.core.text

import kotlin.test.Test
import kotlin.test.assertEquals

/** [ZimPaths] on every target (the JVM test holds it equal to `java.net.URI`; this runs the same cases on iOS). */
class ZimPathsTest {
    @Test fun resolvesAsAndroidDid() {
        assertEquals("I/x.jpg", ZimPaths.resolve("../I/x.jpg", "A/Cat"))
        assertEquals("I/x.jpg", ZimPaths.resolve("I/x.jpg", "Cat"))
        assertEquals("x y.png", ZimPaths.resolve("x%20y.png", "Cat"))
        assertEquals("A/Café", ZimPaths.resolve("Caf%C3%A9", "A/Cat"))
        assertEquals("A/Halo:_Reach", ZimPaths.resolve("Halo:_Reach", "A/Cat"))
        assertEquals("1:2", ZimPaths.resolve("1:2", "A/Cat"))
        assertEquals("A/", ZimPaths.resolve("#top", "A/Cat"))
        assertEquals("../x", ZimPaths.resolve("../../x", "A/Cat"))
        assertEquals("a/b.png", ZimPaths.resolve("//upload.wikimedia.org/a/b.png", "A/Cat"))
        assertEquals("100%", ZimPaths.resolve("100%", "A/Cat"))
    }

    @Test fun decodesPercents() {
        assertEquals("Café.jpg", percentDecoded("Caf%C3%A9.jpg"))
        assertEquals("a+b", percentDecoded("a+b"))
        assertEquals(null, percentDecoded("%zz"))
    }
}
