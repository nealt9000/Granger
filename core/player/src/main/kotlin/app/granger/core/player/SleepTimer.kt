package app.granger.core.player

/** What the sleep timer waits for. */
sealed interface SleepSetting {
    /** Stop [ms] of wall-clock time from now. */
    data class After(val ms: Long) : SleepSetting

    /** Stop at the end of the chapter playing when the timer reaches it. */
    data object EndOfChapter : SleepSetting
}

/**
 * The sleep timer's arithmetic. It holds no thread and never pauses anything itself: the engine polls it (each
 * second while playing), applies [volume] and pauses once [expired] turns true, then calls [cancel].
 *
 * [now] is a wall clock in milliseconds.
 */
class SleepTimer(private val now: () -> Long) {
    private var setting: SleepSetting? = null
    private var endsAt = 0L
    private var chapterEndsIn: () -> Long? = { null }

    /**
     * Starts (or restarts) the timer. [chapterEndsInMs] is used only for [SleepSetting.EndOfChapter]: it returns the
     * wall-clock milliseconds left until the current chapter ends, or null when unknown. It is read every time the
     * timer is queried, so a seek, a chapter change or a speed change moves the end. The caller accounts for speed
     * (chapter time left ÷ speed).
     */
    fun start(setting: SleepSetting, chapterEndsInMs: () -> Long?) {
        this.setting = setting
        chapterEndsIn = chapterEndsInMs
        if (setting is SleepSetting.After) endsAt = now() + setting.ms
    }

    fun cancel() {
        setting = null
        chapterEndsIn = { null }
    }

    /**
     * Milliseconds left, never below 0. Null when no timer is running, or when an end-of-chapter timer can't tell
     * where the chapter ends.
     */
    val remainingMs: Long?
        get() = when (val s = setting) {
            null -> null
            is SleepSetting.After -> (endsAt - now()).coerceAtLeast(0L)
            SleepSetting.EndOfChapter -> chapterEndsIn()?.coerceAtLeast(0L)
        }

    /**
     * Volume multiplier 0..1 for the current moment: 1 until the last 10 s, then linear to 0; null when no timer.
     * A running timer with an unknown end gives 1.
     */
    fun volume(): Float? {
        if (setting == null) return null
        val left = remainingMs ?: return 1f
        return if (left >= FADE_MS) 1f else left.toFloat() / FADE_MS
    }

    /** True once a running timer has 0 ms left. Stays true until [cancel] or a new [start]. */
    fun expired(): Boolean = remainingMs?.let { it <= 0L } ?: false

    private companion object {
        const val FADE_MS = 10_000L
    }
}
