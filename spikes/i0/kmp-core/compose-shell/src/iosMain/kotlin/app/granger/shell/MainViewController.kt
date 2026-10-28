package app.granger.shell

import androidx.compose.ui.viewinterop.UIKitView
import androidx.compose.ui.window.ComposeUIViewController
import platform.Foundation.NSHomeDirectory
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
fun MainViewController() = ComposeUIViewController {
    val core = CoreDemo(NSHomeDirectory() + "/Documents")
    App(core, platform.Foundation.NSProcessInfo.processInfo.arguments.map { it.toString() }) { html, modifier ->
        UIKitView(
            factory = { WKWebView(frame = kotlinx.cinterop.cValue { }, configuration = WKWebViewConfiguration()).apply { loadHTMLString(html, baseURL = null) } },
            modifier = modifier,
        )
    }
}
