package app.granger.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.granger.Features
import app.granger.GrangerApp
import app.granger.MainActivity
import app.granger.R
import app.granger.theme.GrangerColors
import java.io.File
import kotlinx.coroutines.flow.filterNotNull

object PlayerWidgetTags {
    const val ROOT = "widget_root"
    const val COVER = "widget_cover"
    const val TITLE = "widget_title"
    const val SUBTITLE = "widget_subtitle"
    const val PLAY_PAUSE = "widget_play_pause"
    const val SECONDARY = "widget_secondary"
    const val PROGRESS = "widget_progress"
}

/**
 * "Granger player" (spec §6): one widget for whichever player is current. Small (2×1): cover and play/pause;
 * medium: cover, title, subtitle, play/pause and the secondary button; large (two rows high): a progress line too.
 * It recomposes only when [WidgetUpdater] publishes a new model ([app.granger.AppContainer.widgetModel]).
 */
class PlayerWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, MEDIUM, LARGE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as GrangerApp).container
        val models = container.widgetModel
        // Before the first publish in this process: what was last drawn, not "Nothing playing" (no cold-start flash).
        val initial = models.value ?: container.widgetStore.load() ?: if (Features.PLAYER) {
            WidgetModel.of(container.playerHub.ui.value, container.playerHub.saved.value)
        } else {
            WidgetModel.EMPTY
        }
        // One per session, outside the per-size content: every size gets the same Bitmap, which RemoteViews sends once.
        val covers = CoverCache()
        provideContent {
            val model by models.filterNotNull().collectAsState(initial)
            val cover = remember(model.coverPath) { covers.get(model.coverPath)?.let(::ImageProvider) }
            PlayerWidgetContent(model, cover)
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 40.dp)
        val MEDIUM = DpSize(180.dp, 40.dp)
        val LARGE = DpSize(180.dp, 100.dp)

        /** The longest side a cover is decoded at: RemoteViews bitmaps cross a binder, which caps their size. */
        const val COVER_MAX_PX = 192
    }
}

private val Background = ColorProvider(GrangerColors.SurfaceVariant)
private val OnSurface = ColorProvider(GrangerColors.OnSurface)
private val OnSurfaceVariant = ColorProvider(GrangerColors.OnSurfaceVariant)

/** The Player screen of Granger, opened by the cover and text. */
internal fun openPlayerAction(context: Context): Action = actionStartActivity(MainActivity.openPlayerIntent(context))

@Composable
fun PlayerWidgetContent(model: WidgetModel, cover: ImageProvider?) {
    val context = LocalContext.current
    val size = LocalSize.current
    val open = openPlayerAction(context)
    Box(
        GlanceModifier.fillMaxSize().background(Background).cornerRadius(16.dp).padding(8.dp)
            .semantics { testTag = PlayerWidgetTags.ROOT },
        contentAlignment = Alignment.CenterStart,
    ) {
        if (model.empty) {
            Row(GlanceModifier.fillMaxSize().clickable(open), verticalAlignment = Alignment.CenterVertically) {
                Image(
                    ImageProvider(R.drawable.ic_music_note), contentDescription = null,
                    colorFilter = ColorFilter.tint(OnSurfaceVariant), modifier = GlanceModifier.size(32.dp),
                )
                Spacer(GlanceModifier.width(8.dp))
                Column {
                    Text(context.getString(R.string.widget_nothing_playing), maxLines = 1,
                        style = TextStyle(color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                        modifier = GlanceModifier.semantics { testTag = PlayerWidgetTags.TITLE })
                    Text(context.getString(R.string.widget_open_granger), maxLines = 1,
                        style = TextStyle(color = OnSurfaceVariant, fontSize = 12.sp),
                        modifier = GlanceModifier.semantics { testTag = PlayerWidgetTags.SUBTITLE })
                }
            }
            return@Box
        }
        val small = size.width < PlayerWidget.MEDIUM.width
        val large = !small && size.height >= PlayerWidget.LARGE.height
        Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Cover(cover, model.secondary == Secondary.BACK, if (large) 64 else 40, open)
                if (small) {
                    Spacer(GlanceModifier.defaultWeight())
                } else {
                    Spacer(GlanceModifier.width(8.dp))
                    Column(GlanceModifier.defaultWeight().clickable(open)) {
                        Text(model.title, maxLines = 1,
                            style = TextStyle(color = OnSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium),
                            modifier = GlanceModifier.semantics { testTag = PlayerWidgetTags.TITLE })
                        Text(model.subtitle, maxLines = 1, style = TextStyle(color = OnSurfaceVariant, fontSize = 12.sp),
                            modifier = GlanceModifier.semantics { testTag = PlayerWidgetTags.SUBTITLE })
                    }
                }
                Button(
                    if (model.playing) R.drawable.ic_pause else R.drawable.ic_play,
                    if (model.playing) "Pause" else "Play",
                    actionRunCallback<PlayPauseAction>(), PlayerWidgetTags.PLAY_PAUSE,
                )
                if (!small && model.secondary != Secondary.NONE) {
                    val back = model.secondary == Secondary.BACK
                    Button(
                        if (back) R.drawable.ic_replay else R.drawable.ic_skip_next,
                        if (back) "Back" else "Next track",
                        actionRunCallback<SecondaryAction>(), PlayerWidgetTags.SECONDARY,
                    )
                }
            }
            if (large && model.progress != null) {
                Spacer(GlanceModifier.height(8.dp))
                LinearProgressIndicator(
                    progress = model.progress,
                    modifier = GlanceModifier.fillMaxWidth().height(3.dp).semantics { testTag = PlayerWidgetTags.PROGRESS },
                    color = ColorProvider(GrangerColors.Flame),
                    backgroundColor = ColorProvider(GrangerColors.OutlineVariant),
                )
            }
        }
    }
}

@Composable
private fun Cover(cover: ImageProvider?, book: Boolean, sizeDp: Int, open: Action) {
    val modifier = GlanceModifier.size(sizeDp.dp).cornerRadius(8.dp).clickable(open)
        .semantics { testTag = PlayerWidgetTags.COVER; contentDescription = "Open player" }
    if (cover != null) {
        Image(cover, contentDescription = "Open player", contentScale = ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(ColorProvider(GrangerColors.SurfaceHigh)), contentAlignment = Alignment.Center) {
            Image(
                ImageProvider(if (book) R.drawable.ic_headphones else R.drawable.ic_music_note), contentDescription = null,
                colorFilter = ColorFilter.tint(OnSurfaceVariant), modifier = GlanceModifier.size((sizeDp / 2).dp),
            )
        }
    }
}

@Composable
private fun Button(icon: Int, label: String, action: Action, tag: String) {
    Box(
        GlanceModifier.size(44.dp).cornerRadius(22.dp).clickable(action).semantics { testTag = tag; contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Image(ImageProvider(icon), contentDescription = label, colorFilter = ColorFilter.tint(OnSurface),
            modifier = GlanceModifier.size(28.dp))
    }
}

/**
 * The last cover decoded, by path: Responsive content composes once per size, and each must get the same Bitmap
 * (one copy in the RemoteViews, not three). A new path replaces it.
 */
internal class CoverCache(private val decode: (String) -> Bitmap? = { loadCover(it) }) {
    private var path: String? = null
    private var bitmap: Bitmap? = null

    @Synchronized
    fun get(coverPath: String?): Bitmap? {
        if (coverPath == null) return null
        if (coverPath != path) {
            bitmap = decode(coverPath)
            path = coverPath
        }
        return bitmap
    }
}

/**
 * The cover at [path], decoded in-process and scaled so its longer side is at most [PlayerWidget.COVER_MAX_PX];
 * null when it's missing or undecodable (the widget then draws its placeholder).
 */
internal fun loadCover(path: String, maxPx: Int = PlayerWidget.COVER_MAX_PX): Bitmap? = try {
    val file = File(path)
    if (!file.isFile) {
        null
    } else {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val decoded = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            decoded?.let { scaleDown(it, maxPx) }
        }
    }
} catch (e: Exception) {
    null
} catch (e: OutOfMemoryError) {
    null
}

private fun scaleDown(bitmap: Bitmap, maxPx: Int): Bitmap {
    val longer = maxOf(bitmap.width, bitmap.height)
    if (longer <= maxPx) return bitmap
    val scale = maxPx.toFloat() / longer
    val w = (bitmap.width * scale).toInt().coerceAtLeast(1)
    val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
    return Bitmap.createScaledBitmap(bitmap, w, h, true).also { if (it !== bitmap) bitmap.recycle() }
}
