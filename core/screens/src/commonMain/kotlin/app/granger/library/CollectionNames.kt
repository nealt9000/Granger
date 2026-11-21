package app.granger.library

import app.granger.UiText
import app.granger.core.library.Filing
import app.granger.core.library.ShelfCollection

/**
 * The app-made collections whose names the app says in its own words (Plan L3), by stored default key: the library
 * stores the English name when it makes the collection, so a Spanish app showed "My books". Granger Classics keeps its
 * own name (Clásicos Granger is the Spanish books' collection), and Archive.org, LibriVox and Kiwix are names. The
 * string key of the words (Android: `defaultNameRes`, its resource).
 */
fun defaultNameKey(defaultKey: String): String? = when (defaultKey.substringAfter(':')) {
    "my-books" -> "library_collection_my_books"
    "other" -> "library_collection_other"
    "on-this-phone" -> "library_collection_on_this_phone"
    else -> null
}

/**
 * [ShelfCollection.name] as shown: the app's words for an app-made collection that still has the name it was made with
 * ([Filing.storedDefaultName]), else the stored name (a pack's, or one the user gave it). No schema change: the name
 * resolves when it's shown.
 */
fun ShelfCollection.shownName(): UiText {
    val key = defaultKey?.takeUnless { userCreated } ?: return UiText.Raw(name)
    val words = defaultNameKey(key) ?: return UiText.Raw(name)
    return if (Filing.storedDefaultName(key) == name) UiText.Res(words) else UiText.Raw(name)
}
