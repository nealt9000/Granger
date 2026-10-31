package app.granger

import android.content.Context
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UiText resolves the same way from a Context and in Compose, args first. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiTextTest {
    @get:Rule val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun aResourceWithoutArgs() {
        assertThat(UiText.Res(R.string.common_cancel).resolve(context)).isEqualTo("Cancel")
    }

    @Test fun aResourceWithArgs() {
        assertThat(UiText.Res(R.string.common_downloading_named, "Wikipedia").resolve(context)).isEqualTo("Downloading Wikipedia")
    }

    @Test fun aPluralForOneAndForMore() {
        assertThat(UiText.Plural(R.plurals.common_books, 1, 1).resolve(context)).isEqualTo("1 book")
        assertThat(UiText.Plural(R.plurals.common_books, 2, 2).resolve(context)).isEqualTo("2 books")
    }

    @Test fun rawTextIsItself() {
        assertThat(UiText.Raw("Pride and Prejudice").resolve(context)).isEqualTo("Pride and Prejudice")
    }

    @Test fun argsThatAreUiTextResolveFirst() {
        assertThat(UiText.Res(R.string.common_downloading_named, UiText.Plural(R.plurals.common_albums, 3, 3)).resolve(context))
            .isEqualTo("Downloading 3 albums")
        assertThat(UiText.Res(R.string.common_downloading_named, UiText.Raw("Moby-Dick")).resolve(context))
            .isEqualTo("Downloading Moby-Dick")
    }

    @Test fun equalTextIsEqual() {
        assertThat(UiText.Res(R.string.common_downloading_named, "A")).isEqualTo(UiText.Res(R.string.common_downloading_named, "A"))
        assertThat(UiText.Res(R.string.common_downloading_named, "A")).isNotEqualTo(UiText.Res(R.string.common_downloading_named, "B"))
        assertThat(UiText.Plural(R.plurals.common_books, 2, 2)).isEqualTo(UiText.Plural(R.plurals.common_books, 2, 2))
    }

    @Test fun composeResolvesTheSame() {
        compose.setContent {
            Text(UiText.Res(R.string.common_back).resolve())
            Text(UiText.Plural(R.plurals.common_books, 1, 1).resolve())
            Text(UiText.Res(R.string.common_downloading_named, UiText.Plural(R.plurals.common_albums, 2, 2)).resolve())
            Text(UiText.Raw("Dracula").resolve())
        }
        listOf("Back", "1 book", "Downloading 2 albums", "Dracula").forEach { compose.onNodeWithText(it).assertExists() }
    }
}
