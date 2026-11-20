package app.granger.topic

/** An article path as its title shows (shared since Plan I3; `TopicSource.titleOf` on Android). */
object TopicTitles {
    fun titleOf(path: String): String = app.granger.core.library.PageTitles.clean(path.substringAfterLast('/').replace('_', ' '))
}
