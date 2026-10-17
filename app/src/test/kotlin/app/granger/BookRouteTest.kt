package app.granger

import app.granger.core.books.TextQuote
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BookRouteTest {
    @Test fun routeCarriesTheHitText() {
        val route = BookRoute(1, "OEBPS/c006.xhtml", 0.39, "quality of mercy", "The ", " is not")
        assertThat(route.target()).isEqualTo(BookTarget("OEBPS/c006.xhtml", 0.39, TextQuote("The ", "quality of mercy", " is not")))
    }

    @Test fun routeWithoutTextOrPlace() {
        assertThat(BookRoute(1, "c.xhtml", null).target()).isEqualTo(BookTarget("c.xhtml", 0.0, null))
        assertThat(BookRoute(1, "c.xhtml", 0.2, highlight = " ").target()!!.quote).isNull()
        assertThat(BookRoute(1).target()).isNull()
    }
}
