package app.granger.research

import app.granger.R
import app.granger.core.ai.CheckFailure

/** Why Ask is off on this phone, as Ask and Readiness say it. */
internal fun checkText(why: CheckFailure): Int = when (why) {
    CheckFailure.CRASHED -> R.string.research_check_crashed
    CheckFailure.LOAD_FAILED -> R.string.research_check_load_failed
    CheckFailure.WRONG_REPLY -> R.string.research_check_wrong_reply
    CheckFailure.TOO_SLOW -> R.string.research_check_too_slow
}
