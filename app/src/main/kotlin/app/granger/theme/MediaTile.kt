package app.granger.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.granger.R

/** What a [MediaTile] stands in for; its glyph sits in the tile's corner (or alone in the middle, with no title). */
enum class MediaKind {
    MUSIC, PLAYLIST, ARTIST, AUDIOBOOK, VOICE, ARTICLE, BOOK, FOLDER, REFERENCE, MAP, SEARCH, CHAT;

    @Composable
    fun glyph(): Painter = when (this) {
        MUSIC -> painterResource(R.drawable.ic_music_note)
        PLAYLIST -> painterResource(R.drawable.ic_queue_music)
        ARTIST -> rememberVectorPainter(Icons.Filled.Person)
        AUDIOBOOK -> painterResource(R.drawable.ic_headphones)
        VOICE -> painterResource(R.drawable.ic_voice)
        ARTICLE -> painterResource(R.drawable.ic_article)
        BOOK -> painterResource(R.drawable.ic_menu_book)
        FOLDER -> painterResource(R.drawable.ic_library)
        REFERENCE -> painterResource(R.drawable.ic_browse)
        MAP -> rememberVectorPainter(Icons.Filled.Place)
        SEARCH -> rememberVectorPainter(Icons.Filled.Search)
        CHAT -> painterResource(R.drawable.ic_research)
    }
}

/** Below this a tile shows the initial, not the words: the rail, the mini player, list rows. */
val MEDIA_TILE_SMALL = 64.dp

/** What a tile [size] wide prints for [title]: the initial when small, else the title (the tile ellipsizes it); null for none. */
fun mediaTileText(title: String?, size: Dp): String? {
    val t = title?.trim().orEmpty()
    if (t.isEmpty()) return null
    if (size >= MEDIA_TILE_SMALL) return t
    return (t.firstOrNull { it.isLetterOrDigit() } ?: t.first()).uppercaseChar().toString()
}

/** For tests: what a tile prints, and its kind's name ("" for none). The tile itself is silent to TalkBack. */
val MediaTileShown = SemanticsPropertyKey<String>("MediaTileShown")
val MediaTileKind = SemanticsPropertyKey<String>("MediaTileKind")
const val MEDIA_TILE_TAG = "mediaTile"

/**
 * Missing art, themed (Plan U3c Decision 6, W8): the book cover's look, a gunmetal tile with the title in Spectral over
 * a lantern rule, and [kind]'s glyph in the corner. Small tiles show the initial and the glyph; with no [title] the
 * glyph sits alone in the middle (a search, a chat). The same in Field and Night: a cover is an object. It fills what
 * it's given; the caller clips and sizes it.
 */
@Composable
fun MediaTile(title: String?, kind: MediaKind?, modifier: Modifier = Modifier) {
    val extras = GrangerTheme.extras
    BoxWithConstraints(modifier.fillMaxSize().background(extras.coverTile)) {
        val width = maxWidth
        val height = maxHeight
        val side = minOf(width, height)
        val shown = mediaTileText(title, width)
        Box(
            Modifier.fillMaxSize().clearAndSetSemantics {
                testTag = MEDIA_TILE_TAG
                set(MediaTileShown, shown.orEmpty())
                set(MediaTileKind, kind?.name.orEmpty())
            },
        ) {
            when {
                shown == null -> kind?.let {
                    Icon(it.glyph(), contentDescription = null, tint = extras.coverText, modifier = Modifier.align(Alignment.Center).size(side * 0.5f))
                }
                width < MEDIA_TILE_SMALL -> {
                    Text(
                        shown,
                        color = extras.coverText,
                        fontFamily = Spectral,
                        fontWeight = FontWeight.Medium,
                        fontSize = (side.value * 0.5f).sp,
                        lineHeight = (side.value * 0.5f).sp,
                        modifier = Modifier.align(Alignment.Center).padding(bottom = side * 0.06f),
                    )
                    kind?.let {
                        Icon(
                            it.glyph(), contentDescription = null, tint = extras.coverRule,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(side * 0.07f).size(side * 0.3f),
                        )
                    }
                }
                else -> {
                    // 14 sp on a book cover or an album cell, growing with the big players' covers; 12 sp on a tile under
                    // 96 dp, so a word like "Treasure" fits whole.
                    val fontSize = if (width < 96.dp) 12f else (width.value * 0.085f).coerceIn(14f, 26f)
                    // As many lines as fit over the rule, and a rule short enough to clear the corner glyph.
                    val lines = ((height.value - 20f - fontSize * 0.7f - 2f) / (fontSize * 1.3f)).toInt().coerceIn(1, 5)
                    val rule = minOf(fontSize * 2f, side.value * 0.3f)
                    Column(Modifier.align(Alignment.Center).padding(if (width < 96.dp) 6.dp else 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            shown,
                            color = extras.coverText,
                            fontFamily = Spectral,
                            fontSize = fontSize.sp,
                            lineHeight = (fontSize * 1.3f).sp,
                            textAlign = TextAlign.Center,
                            maxLines = lines,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Box(Modifier.padding(top = (fontSize * 0.7f).dp).width(rule.dp).height(2.dp).background(extras.coverRule))
                    }
                    kind?.let {
                        Icon(
                            it.glyph(), contentDescription = null, tint = extras.coverRule,
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size((side.value * 0.11f).coerceIn(14f, 30f).dp),
                        )
                    }
                }
            }
        }
    }
}
