package app.granger.screens

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSCaseInsensitiveSearch
import platform.Foundation.NSDate
import platform.Foundation.NSDiacriticInsensitiveSearch
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSTimeZone
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.Foundation.localTimeZone
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.compare
import kotlinx.cinterop.useContents
import kotlinx.coroutines.IO

actual fun todayEpochDay(): Long {
    val now = NSDate()
    val offset = NSTimeZone.localTimeZone.secondsFromGMTForDate(now)
    return kotlin.math.floor((now.timeIntervalSince1970 + offset) / 86400.0).toLong()
}

actual fun currentYear(): Int = NSCalendar.currentCalendar.component(NSCalendarUnitYear, fromDate = NSDate()).toInt()

actual fun phoneLanguage(): String = NSLocale.currentLocale.languageCode

actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

@Suppress("CAST_NEVER_SUCCEEDS")
@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun titleComparator(language: String): Comparator<String> {
    val locale = NSLocale(localeIdentifier = language)
    return Comparator { a, b ->
        (a as NSString).compare(b, options = NSCaseInsensitiveSearch or NSDiacriticInsensitiveSearch, range = platform.Foundation.NSMakeRange(0u, (a as NSString).length), locale = locale).toInt()
    }
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun imageSize(path: String): Pair<Int, Int>? {
    val image = platform.UIKit.UIImage.imageWithContentsOfFile(path) ?: return null
    val (w, h) = image.size.useContents { width to height }
    val scale = image.scale
    return if (w > 0 && h > 0) (w * scale).toInt() to (h * scale).toInt() else null
}

actual val ioDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.IO

private fun fixedFormatter(decimals: Int) = platform.Foundation.NSNumberFormatter().apply {
    numberStyle = platform.Foundation.NSNumberFormatterDecimalStyle
    usesGroupingSeparator = false
    minimumFractionDigits = decimals.toULong()
    maximumFractionDigits = decimals.toULong()
    roundingMode = platform.Foundation.NSNumberFormatterRoundHalfUp
}

actual fun formatFixed(value: Double, decimals: Int): String =
    fixedFormatter(decimals).stringFromNumber(platform.Foundation.NSNumber(value)) ?: value.toString()

actual fun formatInteger(n: Long): String = platform.Foundation.NSNumberFormatter().apply {
    numberStyle = platform.Foundation.NSNumberFormatterDecimalStyle
}.stringFromNumber(platform.Foundation.NSNumber(longLong = n)) ?: n.toString()

actual fun languageDisplayName(code: String): String =
    NSLocale.currentLocale.displayNameForKey(platform.Foundation.NSLocaleLanguageCode, value = code).orEmpty()

private fun dateOf(year: Int, month: Int, day: Int): NSDate? {
    val c = platform.Foundation.NSDateComponents().apply {
        this.year = year.toLong(); this.month = month.toLong(); this.day = day.toLong(); this.hour = 12
    }
    return NSCalendar.currentCalendar.dateFromComponents(c)
}

actual fun monthYear(year: Int, month: Int): String = platform.Foundation.NSDateFormatter().apply {
    setLocalizedDateFormatFromTemplate("MMMMyyyy")
}.stringFromDate(dateOf(year, month, 1) ?: NSDate())

actual fun mediumDate(year: Int, month: Int, day: Int): String = platform.Foundation.NSDateFormatter().apply {
    dateStyle = platform.Foundation.NSDateFormatterMediumStyle
    timeStyle = platform.Foundation.NSDateFormatterNoStyle
}.stringFromDate(dateOf(year, month, day) ?: NSDate())

actual fun monthName(month: Int): String = platform.Foundation.NSDateFormatter().apply {
    setLocalizedDateFormatFromTemplate("MMMM")
}.monthSymbols.getOrNull(month - 1) as? String ?: ""

actual fun englishCountryName(code: String): String =
    NSLocale(localeIdentifier = "en_US").displayNameForKey(platform.Foundation.NSLocaleCountryCode, value = code).orEmpty()

actual fun logWarn(message: String, error: Throwable?) {
    platform.Foundation.NSLog("Granger: %@", if (error == null) message else "$message ($error)")
}

actual fun languageNameIn(language: String, inLanguage: String): String {
    val name = NSLocale(localeIdentifier = inLanguage).displayNameForKey(platform.Foundation.NSLocaleLanguageCode, value = language)
    return name?.ifEmpty { null }?.replaceFirstChar { it.titlecase() } ?: language
}
