package app.granger.core.player

/**
 * The player's one-off lines when it gives up on something ([PlayerHub.notices]), in English. The app shows each in
 * the user's language by matching these (Plan L1: the player has no Android resources).
 */
object PlayerNotices {
    const val QUEUE_FAILED = "Couldn't play any of these songs. Their files may be missing or damaged."
    const val SONG_FAILED = "Couldn't play this. The file may be missing or damaged."
    const val BOOK_FAILED = "Couldn't play this chapter. Its file may be missing or damaged: Readiness can check it."
    const val PLACE_LOST = "Couldn't find your place in this book, so it starts at the beginning. Pick a chapter to go on."
}
