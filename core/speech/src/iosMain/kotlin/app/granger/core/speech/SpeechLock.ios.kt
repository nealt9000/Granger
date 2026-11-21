package app.granger.core.speech

import platform.Foundation.NSLock

internal actual class SpeechLock actual constructor() {
    private val lock = NSLock()
    actual fun <T> with(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }
}
