package app.granger.core.library

/**
 * A broad topic's page parts (Plan EX T1, tools/topics/parts.py), read from one pack's index: its kinds (the articles
 * under its class, best known first) and their groups, its article's sections with the articles each links, its
 * figures with their captions, and the people characteristic of it. Items are QIDs, to resolve in any installed pack.
 */
data class TopicParts(
    val qid: Long,
    /** The pack whose index these came from (its language; its ZIM holds the figures, when it has pictures). */
    val packId: String,
    /** How many of its pack's articles are under it (the kinds kept are the best known of these). */
    val under: Int,
    val groups: List<Group>,
    val kinds: List<Kind>,
    val sections: List<Section>,
    val links: List<Link>,
    val figures: List<Figure>,
    val people: List<Long>,
    /** How many of its pack's articles link to it. */
    val inlinks: Int = 0,
) {
    /** A group of kinds: its class, its name in the pack's language, how many of the kinds it holds. */
    data class Group(val cls: Long, val name: String, val count: Int)
    /** A kind: its item and its group (an index into [groups]), or -1. */
    data class Kind(val qid: Long, val group: Int)
    /** One of the article's sections: its anchor and title. */
    data class Section(val anchor: String, val title: String)
    /** An article the topic's article links, in [section] (an index into [sections]; -1 the lead). */
    data class Link(val qid: Long, val section: Int)
    /** A figure: its picture's path in the pack's ZIM, its caption, its section (-1 the lead). */
    data class Figure(val image: String, val caption: String, val section: Int)

    companion object {
        const val GROUP = 1
        const val KIND = 2
        const val SECTION = 3
        const val LINK = 4
        const val FIGURE = 5
        const val PERSON = 6
    }
}
