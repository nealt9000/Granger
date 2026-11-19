package app.granger.online

/**
 * Home's Ready offline (Plan O3 Task 8; Neal 2026-11-19, direction B in A + B): your recent reads, how many work offline,
 * a bar for each subject split into on this phone / saved for now / online, and what would fill the gaps.
 */
data class ReadyOfflineUi(
    val recent: Int,
    val onPhone: Int,
    val saved: Int,
    val subjects: List<SubjectBar>,
    /** Keep the online-only articles you read (lang, title), and about what that takes. */
    val keepTheRead: List<Pair<String, String>>,
    val keepBytes: Long,
    /** The pack that would cover most of what's online only, and how many of your reads it covers. */
    val pack: PackOffer?,
    val packCovers: Int,
) {
    val offline: Int get() = onPhone + saved
}

/** One subject's reads: on this phone, saved for now, online only. */
data class SubjectBar(val subjectId: String, val onPhone: Int, val saved: Int, val online: Int) {
    val total: Int get() = onPhone + saved + online
}

/** A recent read, as Ready offline counts it. */
data class RecentRead(val packId: String, val path: String, val availability: Availability, val subjectId: String?, val inPack: Boolean)

object ReadyOffline {
    /** Subjects shown as bars at most; the rest count in the totals. */
    const val SUBJECTS = 5

    /**
     * From [reads] (pure, for tests): totals, a bar for each of the most-read subjects, the online-only ones to keep (at
     * [each] bytes apiece), and [pack] if it covers any of them (those [RecentRead.inPack]).
     */
    fun of(reads: List<RecentRead>, each: Long, pack: PackOffer?): ReadyOfflineUi {
        val onPhone = reads.count { it.availability is Availability.OnPhone }
        val saved = reads.count { it.availability == Availability.Saved }
        val bars = reads.filter { it.subjectId != null }.groupBy { it.subjectId!! }.map { (s, rs) ->
            SubjectBar(s, rs.count { it.availability is Availability.OnPhone }, rs.count { it.availability == Availability.Saved },
                rs.count { it.availability == Availability.Online })
        }.sortedByDescending { it.total }.take(SUBJECTS)
        val onlineOnly = reads.filter { it.availability == Availability.Online }
        val keep = onlineOnly.mapNotNull { r -> OnlineWikipediaArchive.languageOf(r.packId)?.let { it to r.path } }
        val covers = onlineOnly.count { it.inPack }
        return ReadyOfflineUi(reads.size, onPhone, saved, bars, keep, keep.size * each, pack?.takeIf { covers > 0 }, covers)
    }
}
