package app.granger.archive

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the user accepted the "Show everything on archive.org" waiver. Shared since Plan I5 (Android's is `SharedArchivePrefs`). */
interface ArchivePrefs {
    val waiverAccepted: StateFlow<Boolean>
    fun accept()
    fun withdraw()
}

/**
 * The Play build's archive.org, and the App Store's (Plan I5 call 9): the waiver can't be accepted, so the public-domain
 * filter always applies.
 */
object FixedFilterArchivePrefs : ArchivePrefs {
    override val waiverAccepted: StateFlow<Boolean> = MutableStateFlow(false)
    override fun accept() = Unit
    override fun withdraw() = Unit
}
