package app.granger.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.granger.core.library.ShelfCollection
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The app-made collections' names in the app's language (Plan L3 Task 8). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CollectionNamesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun collection(name: String, key: String?, userCreated: Boolean = false) = ShelfCollection("c:$key", name, "literature", userCreated, key)
    private fun ShelfCollection.shown() = shownName().resolve(context)

    @Test fun inEnglishTheNamesAreAsStored() {
        assertThat(collection("My books", "my-books").shown()).isEqualTo("My books")
        assertThat(collection("Granger Classics", "granger-classics").shown()).isEqualTo("Granger Classics")
    }

    @Config(qualifiers = "es")
    @Test fun inSpanishTheDefaultNamesAreTheAppsOwn() {
        assertThat(collection("My books", "my-books").shown()).isEqualTo("Mis libros")
        assertThat(collection("Other", "other").shown()).isEqualTo("Otros")
        assertThat(ShelfCollection("c:music:on-this-phone", "On this phone", "music", false, "music:on-this-phone").shown()).isEqualTo("En este teléfono")
        // Names, not words: as stored.
        assertThat(collection("Granger Classics", "granger-classics").shown()).isEqualTo("Granger Classics")
        assertThat(collection("Clásicos Granger", "clasicos-granger").shown()).isEqualTo("Clásicos Granger")
        assertThat(collection("Archive.org", "archive-org").shown()).isEqualTo("Archive.org")
        // Renamed, or the user's own: as the user wrote it.
        assertThat(collection("Mis cosas", "my-books").shown()).isEqualTo("Mis cosas")
        assertThat(collection("My books", null, userCreated = true).shown()).isEqualTo("My books")
    }
}
