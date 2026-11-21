package app.granger.readiness

import androidx.annotation.StringRes
import app.granger.ScreenTextIds

/** The source's title as an Android resource (the shared [Source] names it by key; Plan I4 Task 2). */
@get:StringRes
val Source.title: Int get() = ScreenTextIds.string(titleKey)
