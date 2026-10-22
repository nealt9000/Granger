package app.granger.home

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a card goes (Plan H2, Decision 6); stored with the card, opened through the nav host's existing closures. */
@Serializable
sealed interface CardTarget {
    /** An article; [fragment] scrolls to a section (the day article's Events). */
    @Serializable data class Article(val packId: String, val path: String, val fragment: String? = null) : CardTarget

    /** A place in a book; a null [href] is the book's own saved place. */
    @Serializable data class Book(val bookId: String, val href: String? = null, val progression: Double = 0.0) : CardTarget

    @Serializable data class Album(val albumKey: String) : CardTarget

    @Serializable data class Audiobook(val audiobookId: String) : CardTarget

    @Serializable data class Search(val query: String) : CardTarget
}

/** One card in a feed's row; [imagePath] is a cached thumbnail file (an article's lead image), if there is one. */
@Serializable
data class Card(val title: String, val subtitle: String, val target: CardTarget, val imagePath: String? = null)

/** A feed's cards for the day, with the row's title. [version] is [Cards.VERSION] when drawn; older draws are redone. */
@Serializable
data class Drawn(val title: String, val cards: List<Card>, val version: Int = 0)

object Cards {
    /** Bumped when what a draw carries changes (1: article thumbnails), so a cached draw from before is redrawn. */
    const val VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

    fun encode(drawn: Drawn): String = json.encodeToString(Drawn.serializer(), drawn.copy(version = VERSION))

    /** Null for JSON this build can't read (a card kind from a newer app) or a draw from an older format: the feed is redrawn. */
    fun decode(text: String): Drawn? =
        runCatching { json.decodeFromString(Drawn.serializer(), text) }.getOrNull()?.takeIf { it.version == VERSION }
}
