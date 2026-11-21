package app.granger.archive

import androidx.annotation.StringRes
import app.granger.ScreenTextIds
import app.granger.core.archive.ArchiveProblem
import app.granger.core.archive.ArchiveShelf
import app.granger.core.archive.LibriVoxProblem
import app.granger.core.archive.LibriVoxShelf

// Plan I5: core/archive names its words by key (shared with the iPhone); Android's screens still read resource ids.

/** The chip's words as an Android resource. */
@get:StringRes
val ArchiveShelf.title: Int get() = ScreenTextIds.string(titleKey)

/** The chip's words as an Android resource. */
@get:StringRes
val LibriVoxShelf.title: Int get() = ScreenTextIds.string(titleKey)

/** The error's words as an Android resource, formatted with `ArchiveError.textArgs`. */
@get:StringRes
val ArchiveProblem.text: Int get() = ScreenTextIds.string(key)

/** The error's words as an Android resource, formatted with `LibriVoxError.textArgs`. */
@get:StringRes
val LibriVoxProblem.text: Int get() = ScreenTextIds.string(key)
