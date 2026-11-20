package app.granger.screens

/** What the screen models ask of the platform (Plan I3 Task 4). */

/** Today, as days since 1970-01-01, in the phone's time zone. */
expect fun todayEpochDay(): Long

/** This year, in the phone's time zone. */
expect fun currentYear(): Int

/** The phone's language ("en", "es"). */
expect fun phoneLanguage(): String

/** Milliseconds since 1970. */
expect fun nowMillis(): Long

/**
 * Orders titles alphabetically for [language], accents and case set aside (`Collator` at PRIMARY strength on Android;
 * a diacritic- and case-insensitive compare on iOS).
 */
expect fun titleComparator(language: String): Comparator<String>

/** A picture file's width and height in pixels, read from its header; null when it isn't a readable picture. */
expect fun imageSize(path: String): Pair<Int, Int>?

/** Where blocking reads run (`Dispatchers.IO` on both phones; common code can't name it). */
expect val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher
