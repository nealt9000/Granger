package app.granger.core.library

import app.granger.core.research.SourceKind

/**
 * A topic page's place in time (Plan LL2 Decision 2): the people alive with its subject and the events of its span, picked
 * from the marks a time index gives for [window]. Pure, so the rules are tested without a `.gtime`.
 */
object TopicTime {
    /** The years to ask the time index for, around [subject]'s own span. */
    fun window(subject: TimeMark, now: Int): LongRange = when (subject.kind) {
        TimeKind.LIFE -> (subject.y0 - ELDER_YEARS)..subject.end(now)
        else -> (subject.y0 - ADULT_YEARS)..subject.end(now)
    }

    /**
     * Up to [n] people whose adult lives overlapped [subject], best known first. For a life: born no more than
     * [ELDER_YEARS] before, adult before it ended, and alive when the subject was [ADULT_YEARS]. For a span or a point: adult
     * and alive within it.
     */
    fun contemporaries(subject: TimeMark, overlapping: List<TimeMark>, now: Int, n: Int = 8): List<TimeMark> {
        val end = subject.end(now)
        val fits: (TimeMark) -> Boolean = when (subject.kind) {
            TimeKind.LIFE -> { p ->
                p.y0 >= subject.y0 - ELDER_YEARS && p.y0 <= end - ADULT_YEARS - 5 && p.end(now) >= subject.y0 + ADULT_YEARS
            }
            else -> { p -> p.y0 <= end - ADULT_YEARS && p.end(now) >= subject.y0 }
        }
        return overlapping.asSequence()
            .filter { it.kind == TimeKind.LIFE && it.qid != subject.qid && fits(it) }
            .distinctBy { it.qid }
            .sortedByDescending { it.rank }
            .take(n)
            .toList()
    }

    /**
     * Up to [n] events within [subject]'s span, best known first: spans starting in it, and points in the wars lane
     * (battles). Other points are mostly countries' founding years, which read as noise beside a life. For a life the
     * events start once the subject is [ADULT_YEARS] - 5.
     */
    fun events(subject: TimeMark, overlapping: List<TimeMark>, now: Int, n: Int = 6): List<TimeMark> {
        val from = if (subject.kind == TimeKind.LIFE) subject.y0 + ADULT_YEARS - 5 else subject.y0
        val to = subject.end(now)
        return overlapping.asSequence()
            .filter { it.qid != subject.qid && it.y0 in from..to }
            .filter { it.kind == TimeKind.SPAN || (it.kind == TimeKind.POINT && Lane.WARS in it.lanes) }
            .distinctBy { it.qid }
            .sortedByDescending { it.rank }
            .take(n)
            .toList()
    }

    const val ADULT_YEARS = 20
    const val ELDER_YEARS = 45
}

/** One thing near a topic in meaning: an article ([target] a pack's name, [key] its path) or a book passage ([key] its chapter). */
data class RelatedHit(val kind: SourceKind, val target: String, val key: String, val score: Float)

data class Related(val articles: List<RelatedHit>, val books: List<RelatedHit>) {
    val isEmpty: Boolean get() = articles.isEmpty() && books.isEmpty()

    companion object {
        val NONE = Related(emptyList(), emptyList())
    }
}
