package app.granger.core.player

/**
 * Decides when to write the playback position (Builder's call in Plan M1: books every 5 s while playing, music every
 * 15 s, both on every pause, seek, chapter change, track change and stop). [write] does the actual save.
 *
 * The tick clock counts from construction and from the last write of any reason, so an event restarts it. After a
 * long gap (resuming from a pause) the first tick writes at once, which is harmless. Not thread-safe: call it from
 * one thread (the player's).
 */
class PositionSaver(private val now: () -> Long, private val write: suspend (SaveReason) -> Unit) {
    enum class SaveReason { TICK, PAUSE, SEEK, CHAPTER, TRACK, STOP }

    private var lastWriteAt = now()

    /** Called every second while playing; writes on TICK every [intervalMs] (5000 for books, 15_000 for music). */
    suspend fun onTick(intervalMs: Long) {
        if (now() - lastWriteAt >= intervalMs) save(SaveReason.TICK)
    }

    /** Writes now, whatever the tick clock says. */
    suspend fun onEvent(reason: SaveReason) = save(reason)

    private suspend fun save(reason: SaveReason) {
        lastWriteAt = now()   // before the write, so a slow write can't double up with the next tick
        write(reason)
    }
}
