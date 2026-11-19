package app.granger.online

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Plan O2 Task 2: Offline pictures and Saved for now's cap. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OnlinePrefsTest {
    @Test fun defaultsAreSavePicturesAnd300MbAndChoicesLast() {
        val prefs = OnlinePrefs(ApplicationProvider.getApplicationContext())
        assertThat(prefs.offlinePictures.value).isEqualTo(OnlinePrefs.OfflinePictures.SAVE)
        assertThat(prefs.savedCapMb.value).isEqualTo(300)
        prefs.setOfflinePictures(OnlinePrefs.OfflinePictures.TEXT_ONLY)
        prefs.setSavedCapMb(1000)
        val again = OnlinePrefs(ApplicationProvider.getApplicationContext())
        assertThat(again.offlinePictures.value).isEqualTo(OnlinePrefs.OfflinePictures.TEXT_ONLY)
        assertThat(again.savedCapMb.value).isEqualTo(1000)
        assertThat(mb(0)).isEqualTo(0); assertThat(mb(10)).isEqualTo(1); assertThat(mb(5L shl 20)).isEqualTo(5)
    }
}
