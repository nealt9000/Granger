package app.granger.core.library

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaTextTest {
    @Test fun foldDropsAccentsAndCase() {
        assertThat(MediaText.fold("Dvořák Ñandú")).isEqualTo("dvorak nandu")
    }

    @Test fun wordsSplitOnPunctuation() {
        assertThat(MediaText.words("Op. 32, H. 125_ I. Mars")).containsExactly("op", "32", "h", "125", "i", "mars").inOrder()
    }
}
