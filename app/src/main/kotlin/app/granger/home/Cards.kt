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

/** One card in a feed's row. */
@Serializable
data class Card(val title: String, val subtitle: String, val target: CardTarget)

/** A feed's cards for the day, with the row's title. */
@Serializable
data class Drawn(val title: String, val cards: List<Card>)

object Cards {
    private val json = Json { ignoreUnknownKeys = true; classDiscriminator = "kind" }

    fun encode(drawn: Drawn): String = json.encodeToString(Drawn.serializer(), drawn)

    /** Null for JSON this build can't read (a card kind from a newer app): the feed is redrawn. */
    fun decode(text: String): Drawn? = runCatching { json.decodeFromString(Drawn.serializer(), text) }.getOrNull()
}
