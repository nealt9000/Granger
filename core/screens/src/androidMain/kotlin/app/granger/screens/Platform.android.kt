package app.granger.screens

import java.text.Collator
import java.util.Locale

actual fun todayEpochDay(): Long = java.time.LocalDate.now().toEpochDay()
actual fun currentYear(): Int = java.time.Year.now().value
actual fun phoneLanguage(): String = Locale.getDefault().language
actual fun languageName(language: String, inLanguage: String): String =
    Locale.forLanguageTag(language).getDisplayLanguage(Locale.forLanguageTag(inLanguage))
actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun titleComparator(language: String): Comparator<String> {
    val c = Collator.getInstance(Locale.forLanguageTag(language)).apply { strength = Collator.PRIMARY }
    return Comparator { a, b -> c.compare(a, b) }
}

actual fun imageSize(path: String): Pair<Int, Int>? {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(path, o)
    return if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
}

actual val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

actual fun formatFixed(value: Double, decimals: Int): String = String.format(Locale.getDefault(), "%.${decimals}f", value)

actual fun formatInteger(n: Long): String = java.text.NumberFormat.getIntegerInstance(Locale.getDefault()).format(n)

actual fun languageDisplayName(code: String): String = Locale(code).getDisplayLanguage(Locale.getDefault())

actual fun monthYear(year: Int, month: Int): String =
    java.time.LocalDate.of(year, month, 1).format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()))

actual fun mediumDate(year: Int, month: Int, day: Int): String =
    java.time.LocalDate.of(year, month, day).format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.MEDIUM))

actual fun monthName(month: Int): String =
    java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.FULL, Locale.getDefault())

actual fun englishCountryName(code: String): String = Locale("", code).getDisplayCountry(Locale.ENGLISH)

actual fun logWarn(message: String, error: Throwable?) {
    if (error == null) android.util.Log.w("Granger", message) else android.util.Log.w("Granger", message, error)
}

actual fun languageNameIn(language: String, inLanguage: String): String {
    val locale = Locale.forLanguageTag(inLanguage)
    return Locale.forLanguageTag(language).getDisplayLanguage(locale).replaceFirstChar { it.titlecase(locale) }
}
