package app.granger.core.media

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MediaClassifierTest {
    private fun file(name: String, folder: String, systemAudiobook: Boolean = false, inAudiobookFolder: Boolean = false) =
        FoundFile("content://media/$name", name, folder, 1_000L, 0L, 60_000L, systemAudiobook, inAudiobookFolder)

    @Test fun m4bAndAudiobooksFolderAndSystemFlagAreAudiobooks() {
        assertThat(MediaClassifier.isAudiobook(file("Dune.M4B", "Download/"))).isTrue()
        assertThat(MediaClassifier.isAudiobook(file("01.mp3", "Audiobooks/Dune/"))).isTrue()
        assertThat(MediaClassifier.isAudiobook(file("01.mp3", "Music/audiobooks/"))).isTrue()
        assertThat(MediaClassifier.isAudiobook(file("01.mp3", "Music/", systemAudiobook = true))).isTrue()
        assertThat(MediaClassifier.isAudiobook(file("01.mp3", "Stories/", inAudiobookFolder = true))).isTrue()
    }

    @Test fun plainMp3InMusicIsMusic() {
        assertThat(MediaClassifier.isAudiobook(file("01 Mars.mp3", "Music/Holst/The Planets/"))).isFalse()
        // Only a whole path segment counts: "My Audiobooks" is not "Audiobooks", and an .m4a is not an .m4b.
        assertThat(MediaClassifier.isAudiobook(file("01.mp3", "My Audiobooks/"))).isFalse()
        assertThat(MediaClassifier.isAudiobook(file("song.m4a", "Music/"))).isFalse()
    }
}
