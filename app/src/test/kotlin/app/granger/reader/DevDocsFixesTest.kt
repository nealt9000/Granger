package app.granger.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DevDocsFixesTest {
    private val page = """<html><head><title>Python</title></head><body><devdocs-navbar current="index"></devdocs-navbar></body></html>"""

    @Test fun addsTheDrawerBeforeTheEndOfHead() {
        val patched = DevDocsFixes.patch(page)
        val fix = patched.indexOf("granger-devdocs-fix")
        assertThat(fix).isGreaterThan(page.indexOf("<title>"))
        assertThat(fix).isLessThan(patched.indexOf("</head>"))
        assertThat(patched).contains("@media (max-width: 720px)")
        assertThat(patched).contains("☰ Contents")
        assertThat(patched).endsWith("<body><devdocs-navbar current=\"index\"></devdocs-navbar></body></html>")
    }

    @Test fun patchesOnce() {
        val once = DevDocsFixes.patch(page)
        assertThat(DevDocsFixes.patch(once)).isSameInstanceAs(once)
    }

    @Test fun leavesOtherPagesAlone() {
        val html = """<html><head></head><body><div class="_sidebar"></div></body></html>"""
        assertThat(DevDocsFixes.patch(html)).isSameInstanceAs(html)
    }

    @Test fun withoutAHeadGoesAfterTheBodyTag() {
        val html = """<body class="x"><devdocs-navbar></devdocs-navbar></body>"""
        val patched = DevDocsFixes.patch(html)
        assertThat(patched).startsWith("""<body class="x"><style id="granger-devdocs-fix">""")
        assertThat(patched).endsWith("<devdocs-navbar></devdocs-navbar></body>")
    }
}
