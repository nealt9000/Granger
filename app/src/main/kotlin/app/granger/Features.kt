package app.granger

/** Features whose code paths exist but stay hidden until they ship. */
object Features {
    /** Research, the on-device librarian (Plan 8); also needs a phone of the ASK tier. */
    const val RESEARCH = true

    /** The audio/video player and its mini-player; after v1. */
    const val PLAYER = false

    /** Importing the user's own books; Plan 10 adds the Pro gate. */
    const val IMPORT = true

    /** Read aloud in the e-book reader; later. */
    const val READ_ALOUD = false
}
