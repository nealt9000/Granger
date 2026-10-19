package app.granger.core.library

import app.granger.core.library.MediaAddress.Parsed
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class MediaAddressTest {
    @Test fun roundTrips() {
        assertThat(MediaAddress.parse(MediaAddress.audiobook("audiobook:phone:ab12", 3, 90_500)))
            .isEqualTo(Parsed.AudiobookAt("audiobook:phone:ab12", 3, 90_500))
        assertThat(MediaAddress.parse(MediaAddress.track("track:phone:ab12", 151_000)))
            .isEqualTo(Parsed.TrackAt("track:phone:ab12", 151_000))
        assertThat(MediaAddress.track("track:phone:ab12", 0)).isEqualTo("track:phone:ab12@0")
        assertThat(MediaAddress.audiobook("audiobook:librivox:42", 0, 7)).isEqualTo("audiobook:librivox:42@0/7")
    }

    @Test fun rejectsGarbage() {
        assertThat(MediaAddress.parse("track:x@")).isNull()
        for (s in listOf(
            "", "track:x", "@5", "track:@5", "track:x@-5", "track:x@+5", "track:x@5s", "track:x@1/2",
            "audiobook:x@3", "audiobook:x@/5", "audiobook:x@3/", "audiobook:x@-1/5", "audiobook:x@1/-5",
            "audiobook:x@a/5", "audiobook:x@1/2/3", "book:x@5", "x@5", "track:x@99999999999999999999",
        )) {
            assertWithMessage(s).that(MediaAddress.parse(s)).isNull()
        }
    }

    @Test fun formattingRejectsBadInput() {
        for (bad in listOf<() -> Unit>(
            { MediaAddress.track("audiobook:phone:a", 1) },
            { MediaAddress.track("track:a@b", 1) },
            { MediaAddress.track("track:a", -1) },
            { MediaAddress.audiobook("track:phone:a", 0, 1) },
            { MediaAddress.audiobook("audiobook:a", -1, 1) },
        )) {
            assertThat(runCatching(bad).exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        }
    }
}
