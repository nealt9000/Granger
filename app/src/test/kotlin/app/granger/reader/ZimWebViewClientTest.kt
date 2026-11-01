package app.granger.reader

import android.content.Context
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import app.granger.core.zim.ZimResolver
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ZimWebViewClientTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    /**
     * Found on the Pixel 7 (release 849): a renderer the system kills or crashes took Granger down with it, because
     * an unhandled renderer death crashes the app. The client takes it and hands the dead WebView on to be replaced.
     */
    @Test fun aDeadRendererIsHandledNotFatal() {
        val gone = mutableListOf<WebView>()
        val client = ZimWebViewClient(
            resolver = ZimResolver(archiveFor = { null }),
            assets = context.assets,
            onExternalLink = {},
            onLeavingPage = {},
            onPageShown = { _, _ -> },
            onRendererGone = { gone += it },
        )
        val view = WebView(context)
        val killed = object : RenderProcessGoneDetail() {
            override fun didCrash() = false
            override fun rendererPriorityAtExit() = 0
        }

        assertThat(client.onRenderProcessGone(view, killed)).isTrue()
        assertThat(gone).containsExactly(view)
    }
}
