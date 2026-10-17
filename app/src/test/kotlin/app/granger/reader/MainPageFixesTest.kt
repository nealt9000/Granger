package app.granger.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MainPageFixesTest {
    private val jquery = """<script src="https://zim.granger.invalid/_granger/jquery-3.7.1.min.js"></script>"""

    @Test fun insertsJqueryBeforeResScript() {
        val html = """<html><head><script src="./_res_/script.js"></script></head><body></body></html>"""
        assertThat(MainPageFixes.patch(html))
            .isEqualTo("""<html><head>$jquery<script src="./_res_/script.js"></script></head><body></body></html>""")
    }

    @Test fun insertsOnlyBeforeTheFirstResScript() {
        val html = """<script>var a = 1;</script><script defer src='_res_/script.js'></script><script src="_res_/script.js"></script>"""
        assertThat(MainPageFixes.patch(html))
            .isEqualTo("""<script>var a = 1;</script>$jquery<script defer src='_res_/script.js'></script><script src="_res_/script.js"></script>""")
    }

    @Test fun leavesPagesThatLoadJqueryAlone() {
        val html = """<script src="../-/j/jquery.min.js"></script><script src="_res_/script.js"></script>"""
        assertThat(MainPageFixes.patch(html)).isSameInstanceAs(html)
    }

    @Test fun leavesPagesWithoutResScriptAlone() {
        val html = """<html><head><script src="_res_/other.js"></script><script src="script.js"></script></head></html>"""
        assertThat(MainPageFixes.patch(html)).isSameInstanceAs(html)
    }
}
