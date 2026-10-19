package app.granger

/** Features whose code paths exist but stay hidden until they ship. */
object Features {
    /** Research, the on-device librarian (Plan 8); also needs a phone of the ASK tier. Debug builds only until the eval passes. */
    val RESEARCH = BuildConfig.DEBUG

    /** The music and audiobook players, the mini-player and the Player tool (Plan M1). */
    val PLAYER = true

    /** Importing the user's own books; Plan 10 adds the Pro gate. */
    const val IMPORT = true

    /** Read aloud in the e-book reader; later. */
    const val READ_ALOUD = false
}
