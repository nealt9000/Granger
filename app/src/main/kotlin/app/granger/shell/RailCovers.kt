package app.granger.shell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cover images are untrusted bytes from inside a book. They are measured before they are decoded, rejected if absurd,
 * and decoded no larger than the rail needs. Anything that goes wrong means no cover (the letter tile shows instead).
 */
internal object RailCovers {
    /** No cover wider or taller than this is decoded at all. */
    const val MAX_SIDE = 8192

    private val cache = object : LruCache<String, Bitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    /** The largest power of two that keeps the decoded image at least [targetPx] on its shorter side. */
    fun sampleSize(width: Int, height: Int, targetPx: Int): Int {
        var sample = 1
        val shorter = minOf(width, height)
        while (shorter / (sample * 2) >= targetPx) sample *= 2
        return sample
    }

    /** Blocking; call off the main thread. Null when the file is missing, not an image, oversized or won't decode. */
    fun load(path: String, targetPx: Int): Bitmap? {
        val key = "$path@$targetPx"
        cache.get(key)?.let { return it }
        val bitmap = try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > MAX_SIDE || bounds.outHeight > MAX_SIDE) {
                null
            } else {
                val options = BitmapFactory.Options().apply { inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, targetPx) }
                BitmapFactory.decodeFile(path, options)
            }
        } catch (_: Throwable) { // Includes OutOfMemoryError; a bad cover must never take the rail down.
            null
        }
        if (bitmap != null) cache.put(key, bitmap)
        return bitmap
    }

    private const val CACHE_BYTES = 4 * 1024 * 1024
}

/** The cover at [path] sized for a [size] tile, decoded off the main thread; null while loading or if unusable. */
@Composable
internal fun rememberRailCover(path: String?, size: Dp): ImageBitmap? {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val cover by produceState<ImageBitmap?>(null, path, px) {
        value = if (path == null) null else withContext(Dispatchers.IO) { RailCovers.load(path, px)?.asImageBitmap() }
    }
    return cover
}
