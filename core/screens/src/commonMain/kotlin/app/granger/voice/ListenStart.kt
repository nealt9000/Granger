package app.granger.voice

import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource

// Shared since Plan I5 Task 8: both phones' readers start Listen by this rule.

/**
 * Where a reader's Listen starts on a text (Plan U2): at its saved place ([saved], the voice's `voice_states` row) when
 * the page is at or before it, so coming back to a text goes on where the voice stopped; when the page is past it
 * (scrolled or turned on), the first visible sentence ([visible]) wins. A saved place the text no longer has, or one
 * it can't place against the page, leaves [visible].
 */
suspend fun listenStart(source: ReadSource, visible: ReadAddress, saved: ReadAddress?): ReadAddress {
    if (saved == null) return visible
    val located = runCatching { source.locate(saved) }.getOrNull() ?: return visible
    val order = runCatching { source.compare(visible, located) }.getOrNull() ?: return visible
    return if (order <= 0) located else visible
}
