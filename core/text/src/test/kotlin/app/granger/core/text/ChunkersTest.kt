package app.granger.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChunkersTest {
    // Python: hashlib.sha1("take no thought for the morrow".encode()).hexdigest()[:16]
    @Test fun hashIsTheFirstEightBytesOfSha1() {
        assertThat(Chunkers.hash("take no thought for the morrow")).isEqualTo(PYTHON_HASH)
    }

    @Test fun hashUsesUtf8() {
        assertThat(Chunkers.hash("café")).isNotEqualTo(Chunkers.hash("cafe"))
        assertThat(Chunkers.hash("")).hasLength(16)
    }

    private companion object {
        val PYTHON_HASH = "369fae5d28de7efc"
    }
}

class UnicodeSpaceTest {
    // Android's \s (ICU) splits on these; the desktop JVM's \s doesn't. The chunkers must split as the phone does.
    @Test fun thinAndOtherUnicodeSpacesSplitWordsLikeOnThePhone() {
        val html = "<p>ψ(r, θ, φ) a　b c d</p>"
        val text = app.granger.core.research.HtmlPassages.split(html).single().text
        assertThat(text).isEqualTo("ψ(r, θ, φ) a b c d")
    }

    @Test fun verticalTabIsNotASpaceOnThePhone() {
        assertThat(Regex("[${Chunkers.SPACE}]").containsMatchIn("\u000B")).isFalse()
    }
}
