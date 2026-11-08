package app.granger.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WikiMenuShowInTimeTest {
    private fun menu(showInTime: (() -> Unit)?, hasPage: Boolean = true) =
        wikiBarActions(packTitle = "Wikipedia", hasPack = true, hasPage = hasPage, hasHome = true, bookmarked = false, onBack = {}, onShowInTime = showInTime).menu

    @Test fun showInTimeIsOfferedOnlyForADatedPage() {
        var shown = 0
        val item = menu({ shown++ }).single { (it.label as app.granger.UiText.Res).id == app.granger.R.string.reader_show_in_time }
        item.onClick()
        assertThat(shown).isEqualTo(1)
        assertThat(menu(null).none { (it.label as? app.granger.UiText.Res)?.id == app.granger.R.string.reader_show_in_time }).isTrue()
        assertThat(menu({}, hasPage = false).none { (it.label as? app.granger.UiText.Res)?.id == app.granger.R.string.reader_show_in_time }).isTrue()
    }
}
