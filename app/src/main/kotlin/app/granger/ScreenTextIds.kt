package app.granger

/**
 * The Android ids for the string keys the shared models use (`UiText.Res("key")`). Written by
 * tools/ios/make_text_ids.py; `ScreenTextIdsTest` fails when a key is missing. An explicit map, not a lookup by
 * name, so resource shrinking keeps every string.
 */
object ScreenTextIds {
    fun string(key: String): Int = when (key) {
        else -> error("no Android string for the shared key '$key' (run tools/ios/make_text_ids.py)")
    }

    fun plural(key: String): Int = when (key) {
        else -> error("no Android plural for the shared key '$key' (run tools/ios/make_text_ids.py)")
    }
}
