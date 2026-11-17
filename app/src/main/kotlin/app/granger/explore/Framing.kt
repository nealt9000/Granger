package app.granger.explore

import android.graphics.Bitmap
import android.graphics.PointF
import android.media.FaceDetector
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import app.granger.shell.RailCovers
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where a picture's subject is, as fractions of its width and height (Plan EX, the editorial landing: pictures frame their
 * subject). Faces first, through Android's own detector (offline, no model to ship): the middle of the faces found. With
 * none, a little above the middle, where a picture's subject usually sits.
 */
object Framing {
    /** The default focus: the middle, a little high. */
    const val DEFAULT_X = 0.5f
    const val DEFAULT_Y = 0.4f

    private val cache = ConcurrentHashMap<String, Pair<Float, Float>>()

    /** The focus of [bitmap] (cached by [key]); blocking. */
    fun focus(key: String, bitmap: Bitmap): Pair<Float, Float> = cache.getOrPut(key) { detect(bitmap) }

    private fun detect(bitmap: Bitmap): Pair<Float, Float> = try {
        // The detector wants RGB_565 and an even width; a small copy is enough and quick.
        val scale = minOf(1f, 320f / maxOf(bitmap.width, bitmap.height))
        val w = (bitmap.width * scale).toInt() and 1.inv()
        val h = (bitmap.height * scale).toInt()
        if (w < 32 || h < 32) DEFAULT_X to DEFAULT_Y else {
            val small = Bitmap.createScaledBitmap(bitmap, w, h, true).copy(Bitmap.Config.RGB_565, false)
            val faces = arrayOfNulls<FaceDetector.Face>(MAX_FACES)
            val n = FaceDetector(w, h, MAX_FACES).findFaces(small, faces)
            val found = faces.take(n).filterNotNull().filter { it.confidence() >= MIN_CONFIDENCE }
            if (found.isEmpty()) DEFAULT_X to DEFAULT_Y else {
                val p = PointF()
                var x = 0f
                var y = 0f
                for (f in found) { f.getMidPoint(p); x += p.x; y += p.y }
                // A face's midpoint is between the eyes: the face's middle sits a little lower.
                (x / found.size / w) to ((y / found.size + found.first().eyesDistance() * 0.4f) / h)
            }
        }
    } catch (_: Throwable) {
        DEFAULT_X to DEFAULT_Y
    }

    /**
     * The crop alignment that puts focus ([fx], [fy]) of a [imgW]×[imgH] picture as near the middle of a [boxW]×[boxH] box
     * as cropping allows (pure, for tests). Crop scales the picture to cover the box; the spare length on one axis is
     * where the alignment can move it.
     */
    fun alignment(fx: Float, fy: Float, imgW: Int, imgH: Int, boxW: Float, boxH: Float): BiasAlignment {
        if (imgW <= 0 || imgH <= 0 || boxW <= 0f || boxH <= 0f) return BiasAlignment(0f, 0f)
        val scale = maxOf(boxW / imgW, boxH / imgH)
        fun bias(f: Float, content: Float, box: Float): Float {
            val spare = content - box
            if (spare <= 0.5f) return 0f
            // Offset (from the start) that centres f; Alignment's bias maps -1..1 onto 0..-spare.
            val offset = (box / 2 - f * content).coerceIn(-spare, 0f)
            return (-2f * offset / spare - 1f).coerceIn(-1f, 1f)
        }
        return BiasAlignment(bias(fx, imgW * scale, boxW), bias(fy, imgH * scale, boxH))
    }

    private const val MAX_FACES = 4
    private const val MIN_CONFIDENCE = 0.3f
}

/** [path]'s picture filling its box, cropped around its subject ([Framing]); the box's colour while it loads or with none. */
@Composable
fun FramedPicture(path: String?, sizePx: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { sizePx.roundToPx() }
    val loaded by produceState<Triple<Bitmap, Float, Float>?>(null, path, px) {
        value = if (path == null) null else withContext(Dispatchers.IO) {
            RailCovers.load(path, px)?.let { b -> Framing.focus(path, b).let { (x, y) -> Triple(b, x, y) } }
        }
    }
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val shown = loaded ?: return@BoxWithConstraints
        val (bitmap, fx, fy) = shown
        val density = LocalDensity.current
        val align = with(density) { Framing.alignment(fx, fy, bitmap.width, bitmap.height, maxWidth.toPx(), maxHeight.toPx()) }
        Image(bitmap.asImageBitmap(), null, contentScale = ContentScale.Crop, alignment = align, modifier = Modifier.fillMaxSize())
    }
}
