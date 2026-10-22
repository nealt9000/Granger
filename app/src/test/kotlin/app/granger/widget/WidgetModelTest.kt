package app.granger.widget

import app.granger.core.player.PlayerMode
import app.granger.core.player.PlayerUi
import app.granger.core.player.SavedBook
import app.granger.core.player.SavedModes
import app.granger.core.player.SavedMusic
import app.granger.player.audiobook
import app.granger.player.bookUi
import app.granger.player.chapter
import app.granger.player.musicUi
import app.granger.player.track
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WidgetModelTest {
    private val none = SavedModes(null, null, null)

    @Test fun aSavedVoiceShowsItsTitlePaused() {
        val voice = app.granger.core.player.SavedVoice("book:b", "Hamlet", "Act I", app.granger.core.speech.ReadAddress.Text("k", 0, 0), 1f, null)
        val m = WidgetModel.of(PlayerUi.IDLE, SavedModes(null, null, null, voice = voice, lastWasVoice = true))
        assertThat(m.title).isEqualTo("Hamlet")
        assertThat(m.subtitle).isEqualTo("Reading · Act I")
        assertThat(m.playing).isFalse()
        assertThat(m.empty).isFalse()
        // The voice that was last but isn't the saved mode any more gives way to the players.
        assertThat(WidgetModel.of(PlayerUi.IDLE, SavedModes(null, null, null, voice = voice, lastWasVoice = false))).isEqualTo(WidgetModel.EMPTY)
    }

    @Test fun musicShowsTrackArtistAndNext() {
        val t = track("a", "Mars, the Bringer of War", artists = "Holst").copy(coverPath = "/covers/planets.jpg")
        val m = WidgetModel.of(musicUi(t, positionMs = 50_000L), none)
        assertThat(m.title).isEqualTo("Mars, the Bringer of War")
        assertThat(m.subtitle).isEqualTo("Holst")
        assertThat(m.coverPath).isEqualTo("/covers/planets.jpg")
        assertThat(m.playing).isTrue()
        assertThat(m.secondary).isEqualTo(Secondary.NEXT)
        assertThat(m.progress).isWithin(1e-6f).of(0.25f)
        assertThat(m.empty).isFalse()
    }

    @Test fun bookShowsChapterBookAndBack() {
        val b = audiobook("jb", "The Jungle Book", author = "Kipling").copy(coverPath = "/covers/jb.jpg")
        val c = chapter(b, 2, durationMs = 100_000L, title = "Kaa's Hunting")
        val m = WidgetModel.of(bookUi(b, c, chapterCount = 10, positionMs = 10_000L), none)
        // The chapter is the title, as in the notification and Auto; the book is the subtitle.
        assertThat(m.title).isEqualTo("Kaa's Hunting")
        assertThat(m.subtitle).isEqualTo("The Jungle Book")
        assertThat(m.coverPath).isEqualTo("/covers/jb.jpg")
        assertThat(m.playing).isFalse()
        assertThat(m.secondary).isEqualTo(Secondary.BACK)
        assertThat(m.progress).isWithin(1e-6f).of(0.1f)
        assertThat(m.empty).isFalse()
    }

    @Test fun nothingLoadedShowsTheSavedPlayer() {
        val b = audiobook("jb", "The Jungle Book")
        val chapters = listOf(chapter(b, 0, 100_000L, title = "Mowgli's Brothers"), chapter(b, 1, 80_000L, title = ""))
        val savedBook = SavedBook(b, chapters, chapters[1], inChapterMs = 20_000L, speed = 1f, skipSilence = false,
            volumeBoostMb = 0, finished = false)
        val music = SavedMusic(listOf(track("a", "Song A")), index = 0, positionMs = 0L, shuffle = false, repeat = 0)
        val m = WidgetModel.of(PlayerUi.IDLE, SavedModes(music, savedBook, PlayerMode.AUDIOBOOK))
        assertThat(m.title).isEqualTo("Chapter 2")   // a blank chapter title, named as the player names it
        assertThat(m.subtitle).isEqualTo("The Jungle Book")
        assertThat(m.playing).isFalse()
        // Nothing is loaded to skip in: play resumes it, and that's the one button.
        assertThat(m.secondary).isEqualTo(Secondary.NONE)
        assertThat(m.progress).isWithin(1e-6f).of(0.25f)
        assertThat(m.empty).isFalse()

        val asMusic = WidgetModel.of(PlayerUi.IDLE, SavedModes(music, savedBook, PlayerMode.MUSIC))
        assertThat(asMusic.title).isEqualTo("Song A")
        assertThat(asMusic.subtitle).isEqualTo("Artist")
        assertThat(asMusic.progress).isWithin(1e-6f).of(0f)
    }

    @Test fun neverPlayedIsEmpty() {
        for (saved in listOf(none, SavedModes.UNKNOWN)) {
            val m = WidgetModel.of(PlayerUi.IDLE, saved)
            assertThat(m.empty).isTrue()
            assertThat(m.playing).isFalse()
            assertThat(m.secondary).isEqualTo(Secondary.NONE)
            assertThat(m.progress).isNull()
            assertThat(m.coverPath).isNull()
        }
        // An empty saved queue (every track removed) is nothing to resume either.
        val gone = SavedModes(SavedMusic(emptyList(), 0, 0L, false, 0), null, PlayerMode.MUSIC)
        assertThat(WidgetModel.of(PlayerUi.IDLE, gone).empty).isTrue()
        assertThat(gone.resumeMode()).isNull()
    }
}
