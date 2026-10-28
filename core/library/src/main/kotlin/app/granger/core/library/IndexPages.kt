package app.granger.core.library

/**
 * Pages that list or profile rather than say anything: Stack Exchange's user, tag and question indexes, and the people
 * pages of Stack Exchange and iFixit. Search's Titles and Articles, Home's Continue and the feeds leave them out
 * (Plan U3b Decisions 10 and 12).
 *
 * The paths, read with libzim from the real packs (2026-10-28):
 * - Stack Exchange (`cs.stackexchange.com_en_all` and the like): the main page is `questions` ("Highest Voted
 *   Questions"). `users`, `tags` and `questions` are indexes, paged as `users_page=N`, `tags_page=N` and
 *   `questions_page=N`. A user page is `users/<id>/<name>`, titled "User <name>"; a tag page is under `tags/`. An answer
 *   is a redirect stub `a/<id>`, titled "a/<id>". A question is `questions/<id>/<slug>`, and is kept.
 * - iFixit (`ifixit_en_all`): the main page is `home/home`; a person is `User/<id>/<name>`, titled with their name. The
 *   guides are under `Guide/`, `Device/`, `Teardown/` and `Info/`, and are kept.
 */
object IndexPages {
    private val EXACT = setOf("users", "tags", "questions")
    private val PAGED = listOf("users_page=", "tags_page=", "questions_page=")
    private val UNDER = listOf("users/", "tags/", "a/", "User/")

    /** A leading slash, and an old-style ZIM namespace ("A/" for articles, "C/" for content). */
    private val NAMESPACE = Regex("^/?(?:[AC]/)?")

    fun isIndex(path: String): Boolean {
        val p = path.replace(NAMESPACE, "")
        return p in EXACT || PAGED.any { p.startsWith(it) } || UNDER.any { p.startsWith(it) }
    }
}
