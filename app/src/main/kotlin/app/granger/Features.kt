package app.granger

/** Features whose code paths exist but stay hidden until they ship. */
object Features {
    /**
     * Research, the on-device librarian (Plan 8), shown as Ask with its Preview tag. Off in every build from Plan SQ (Neal,
     * 2026-11-10: Ask rests until on-device models improve; check back in a few months). Off hides Ask's rail tool, its
     * selection-menu item, its chats on the rail, its onboarding and readiness lines and its small model; the code, the
     * saved chats and the eval tools stay. On again, the device tier still keeps it to ASK phones.
     */
    const val RESEARCH = false

    /** The music and audiobook players, the mini-player and the Player tool (Plan M1). */
    val PLAYER = true

    /** Importing the user's own books; Plan 10 adds the Pro gate. */
    const val IMPORT = true

    /** Read aloud in both readers (Plan V1). Debug builds while V1 is built; `true` once the Pixel 6 walkthrough passes. */
    val READ_ALOUD = true

    /**
     * archive.org's "Show everything" waiver. Off in the Play build (release), so the 1930 public-domain filter is
     * fixed there (Neal, 2026-11-02: Play's intellectual-property policy); Plan 10's F-Droid and direct flavour turns
     * it back on. Debug builds keep it for testing.
     */
    val ARCHIVE_WAIVER = BuildConfig.DEBUG

    /**
     * Ask answering a question in Spanish. Off in release (Neal, 2026-11-02: Spanish ships in the closed test, Spanish
     * Ask stays off until its medical bar passes at the phone baseline, Plan L4), so a Spanish question is declined
     * with "Ask answers in English for now". Debug builds keep it for the L4 baseline.
     */
    val SPANISH_ASK = BuildConfig.DEBUG

    /**
     * Linked Library LL1: reference links in the Classics, and with it the QID index and links sidecars downloaded with
     * their packs. On in every build from the tester build after EX (Neal, 2026-11-18), with [TOPIC_PAGE] and [EXPLORE];
     * before that, release builds kept it off (Neal, 2026-11-12).
     */
    const val LINKED_CLASSICS = true

    /**
     * Linked Library LL2: the topic page in front of a Wikipedia article (Plan LL2). Rides with [LINKED_CLASSICS]: on in
     * every build with it (Neal, 2026-11-18).
     */
    val TOPIC_PAGE = LINKED_CLASSICS

    /**
     * Plan EX: the rail's Browse becomes Explore (subjects, Through time, Your sources), the same tool and id. Rides with
     * [LINKED_CLASSICS], since subjects come in the QID index those builds download. Off: Browse as before.
     */
    val EXPLORE = LINKED_CLASSICS

    /**
     * Wikipedia online (Plan O1): articles, pictures, search and Related read live from Wikimedia through the one network
     * gate. On in debug; release turns it on in O5, with the first-run choice and the privacy line (Decision 3).
     */
    val ONLINE = BuildConfig.DEBUG
}
