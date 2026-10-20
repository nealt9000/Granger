package app.granger.widget

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import app.granger.MainActivity
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetCacheTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun eachCoverIsDecodedOnceForEverySize() {
        val decoded = mutableListOf<String>()
        val cache = CoverCache { path -> decoded += path; Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888) }
        // Responsive content asks once per size (small, medium, large).
        val first = cache.get("/covers/a.jpg")
        repeat(2) { assertThat(cache.get("/covers/a.jpg")).isSameInstanceAs(first) }
        val other = cache.get("/covers/b.jpg")
        assertThat(other).isNotSameInstanceAs(first)
        assertThat(cache.get(null)).isNull()
        assertThat(decoded).containsExactly("/covers/a.jpg", "/covers/b.jpg").inOrder()
    }

    @Test fun lastRenderedModelSurvivesTheProcessAsPaused() {
        val store = WidgetModelStore(context.getSharedPreferences("widget-test", Context.MODE_PRIVATE))
        assertThat(store.load()).isNull()
        store.save(WidgetModel("Kaa's Hunting", "The Jungle Book", "/covers/jb.jpg", playing = true, Secondary.BACK, 0.4f, empty = false))
        // A new process: nothing is loaded, so it shows paused, with play (which resumes) as the one button.
        assertThat(store.load()).isEqualTo(
            WidgetModel("Kaa's Hunting", "The Jungle Book", "/covers/jb.jpg", playing = false, Secondary.NONE, 0.4f, empty = false),
        )
        store.save(WidgetModel.EMPTY)
        assertThat(store.load()).isEqualTo(WidgetModel.EMPTY)
    }

    @Test fun relaunchFromRecentsDoesNotReopenThePlayer() {
        val fromWidget = MainActivity.openPlayerIntent(context)
        assertThat(MainActivity.opensPlayer(fromWidget)).isTrue()
        val fromHistory = Intent(fromWidget).addFlags(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY)
        assertThat(MainActivity.opensPlayer(fromHistory)).isFalse()
        assertThat(MainActivity.opensPlayer(Intent(context, MainActivity::class.java))).isFalse()
    }
}
