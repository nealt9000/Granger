package app.granger.screens

import java.text.Collator
import java.util.Locale

actual fun todayEpochDay(): Long = java.time.LocalDate.now().toEpochDay()
actual fun currentYear(): Int = java.time.Year.now().value
actual fun phoneLanguage(): String = Locale.getDefault().language
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
