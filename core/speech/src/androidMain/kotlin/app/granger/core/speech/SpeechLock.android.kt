package app.granger.core.speech

internal actual class SpeechLock actual constructor() {
    actual fun <T> with(block: () -> T): T = synchronized(this) { block() }
}
