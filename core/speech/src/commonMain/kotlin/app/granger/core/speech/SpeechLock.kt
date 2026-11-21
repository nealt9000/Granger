package app.granger.core.speech

/** A plain mutual-exclusion lock (common Kotlin has no `@Synchronized`): a monitor on Android, an NSLock on the iPhone. */
internal expect class SpeechLock() {
    fun <T> with(block: () -> T): T
}
