package app.granger.library

import androidx.annotation.StringRes
import app.granger.R
import app.granger.UiText
import app.granger.core.library.Filing
import app.granger.core.library.ShelfCollection

/**
 * The app-made collections whose names the app says in its own words (Plan L3), by stored default key: the library
 * stores the English name when it makes the collection, so a Spanish app showed "My books". Granger Classics keeps its
 * own name (Clásicos Granger is the Spanish books' collection), and Archive.org, LibriVox and Kiwix are names.
 */
@StringRes
internal fun defaultNameRes(defaultKey: String): Int? = when (defaultKey.substringAfter(':')) {
    "my-books" -> R.string.library_collection_my_books
    "other" -> R.string.library_collection_other
    "on-this-phone" -> R.string.library_collection_on_this_phone
    else -> null
}

/**
 * [ShelfCollection.name] as shown: the app's words for an app-made collection that still has the name it was made with
 * ([Filing.storedDefaultName]), else the stored name (a pack's, or one the user gave it). No schema change: the name
 * resolves when it's shown.
 */
fun ShelfCollection.shownName(): UiText {
    val key = defaultKey?.takeUnless { userCreated } ?: return UiText.Raw(name)
    val res = defaultNameRes(key) ?: return UiText.Raw(name)
    return if (Filing.storedDefaultName(key) == name) UiText.Res(res) else UiText.Raw(name)
}
