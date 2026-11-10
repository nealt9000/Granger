package app.granger.home

/** One of Home's sections the user can move or hide (Plan HY, Neal 2026-11-10). Notices always stay at the top. */
sealed interface HomePart {
    val token: String

    data object Continue : HomePart { override val token = "continue" }
    data object Timeline : HomePart { override val token = "timeline" }
    data object Play : HomePart { override val token = "play" }
    data object Wander : HomePart { override val token = "wander" }
    data object Readiness : HomePart { override val token = "readiness" }
    data class Feed(val id: Long) : HomePart { override val token = "feed:$id" }

    companion object {
        val BUILT_IN: List<HomePart> = listOf(Continue, Timeline, Play, Wander, Readiness)
    }
}

/**
 * Home's order and what's hidden, as tokens. [FEEDS] marks where feeds not placed yet go: a new feed lands there, so it
 * shows up beside the others rather than at the end.
 */
data class HomeLayout(val order: List<String> = DEFAULT, val hidden: Set<String> = emptySet()) {

    /** Every part in order, given the feeds on Home now ([feedIds], in their own order): hidden ones included. */
    fun parts(feedIds: List<Long>): List<HomePart> {
        val feeds = feedIds.associateBy { "feed:$it" }
        val placed = order.filter { it in feeds }.toSet()
        val loose = feedIds.filter { "feed:$it" !in placed }.map { HomePart.Feed(it) }
        val out = mutableListOf<HomePart>()
        for (t in order) {
            when {
                t == FEEDS -> out += loose
                t in feeds -> out += HomePart.Feed(feeds.getValue(t))
                else -> HomePart.BUILT_IN.firstOrNull { it.token == t }?.let { out += it }
            }
        }
        if (FEEDS !in order) out += loose
        // A section added in a later version than the saved order goes where it would by default.
        for (part in HomePart.BUILT_IN) {
            if (part in out) continue
            val at = DEFAULT.indexOf(part.token)
            val after = DEFAULT.take(at).lastOrNull { t -> out.any { it.token == t } }
            out.add(after?.let { t -> out.indexOfFirst { it.token == t } + 1 } ?: 0, part)
        }
        return out.distinct()
    }

    /** What Home shows, in order. */
    fun visible(feedIds: List<Long>): List<HomePart> = parts(feedIds).filter { it.token !in hidden }

    /** Moves [token] one place up or down among the shown parts. */
    fun move(token: String, up: Boolean, feedIds: List<Long>): HomeLayout {
        val all = parts(feedIds).toMutableList()
        val at = all.indexOfFirst { it.token == token }
        if (at < 0) return this
        val shown = all.indices.filter { all[it].token !in hidden }
        val pos = shown.indexOf(at)
        val to = shown.getOrNull(if (up) pos - 1 else pos + 1) ?: return this
        all.add(to, all.removeAt(at))
        return arranged(all, hidden)
    }

    companion object {
        const val FEEDS = "feeds"
        val DEFAULT = listOf("continue", "timeline", "play", "wander", FEEDS, "readiness")

        /** The layout for [parts] as arranged, the feeds marker after the last feed (or where feeds were by default). */
        fun arranged(parts: List<HomePart>, hidden: Set<String>): HomeLayout {
            val tokens = parts.map { it.token }.toMutableList()
            val lastFeed = parts.indexOfLast { it is HomePart.Feed }
            if (lastFeed >= 0) tokens.add(lastFeed + 1, FEEDS)
            else tokens.add(tokens.indexOf("readiness").takeIf { it >= 0 } ?: tokens.size, FEEDS)
            return HomeLayout(tokens, hidden.filterTo(HashSet()) { it in tokens })
        }

        fun encode(layout: HomeLayout): Pair<String, Set<String>> = layout.order.joinToString(",") to layout.hidden

        fun decode(order: String?, hidden: Set<String>?): HomeLayout =
            HomeLayout(order?.split(',')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() } ?: DEFAULT, hidden.orEmpty())
    }
}
