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
