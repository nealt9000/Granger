package app.granger.sources

import androidx.annotation.StringRes
import app.granger.ScreenTextIds

/** The scope's title as an Android resource (the shared [CatalogScope] names it by key; Plan I4 Task 2). */
@get:StringRes
val CatalogScope.title: Int get() = ScreenTextIds.string(titleKey)
