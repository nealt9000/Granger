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

// Plan I4 Task 2: the phone's own number, language and date words, for Get more, Outfitting and Readiness.

/** [value] with exactly [decimals] places and the phone's decimal separator ("2.0", "2,0"); half rounds up. */
expect fun formatFixed(value: Double, decimals: Int): String

/** [n] with the phone's grouping ("69,600", "69.600"). */
expect fun formatInteger(n: Long): String

/** A language code's name in the phone's language ("en" → "English"); blank when nothing names it. */
expect fun languageDisplayName(code: String): String

/** "March 2026" in the phone's language. */
expect fun monthYear(year: Int, month: Int): String

/** A date in the phone's medium style ("5 Mar 2026", "Mar 5, 2026"). */
expect fun mediumDate(year: Int, month: Int, day: Int): String

/** [month]'s full name in the phone's language ("October", "octubre"), as a date says it (Home's day feed, Plan I4b). */
expect fun monthName(month: Int): String

/** A country code's name in English ("PE" → "Peru"), as onboarding matches the catalog's English map titles; blank or the code when unknown. */
expect fun englishCountryName(code: String): String

/** A warning in the phone's log under "Granger" (Android: `Log.w`; Plan I5). */
expect fun logWarn(message: String, error: Throwable? = null)
