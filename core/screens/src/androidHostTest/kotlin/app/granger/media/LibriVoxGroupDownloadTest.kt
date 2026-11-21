package app.granger.media

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** [LibriVoxGroupDownloadChecks] on the JVM: Robolectric gives the scanner Android's `Uri.fromFile`. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LibriVoxGroupDownloadTest : LibriVoxGroupDownloadChecks()
