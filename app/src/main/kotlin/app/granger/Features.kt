package app.granger

/** Features whose code paths exist but stay hidden until they ship. */
object Features {
    /**
     * Research, the on-device librarian (Plan 8), shown as Ask with its Preview tag. On in release since the friend build
     * (Plan F1 part 2); the device tier still keeps it to ASK phones, and release builds offer only evalPassed models.
     */
    const val RESEARCH = true

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
}
