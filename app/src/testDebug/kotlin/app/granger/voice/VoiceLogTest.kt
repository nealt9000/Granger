package app.granger.voice

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class VoiceLogTest {
    private val row = SentenceRow(
        engine = "vits-piper-en_US-ljspeech-medium", threads = 2, passage = "book", index = 3, chars = 87,
        firstAudioMs = 410, synthMs = 410, audioMs = 2050, rssMb = 612,
    )

    @Test
    fun realTimeFactorIsSynthesisOverAudioAndNaNWithoutAudio() {
        assertThat(VoiceLog.rtf(2000, 4000)).isEqualTo(0.5)
        assertThat(VoiceLog.rtf(3000, 1000)).isEqualTo(3.0)
        assertThat(VoiceLog.rtf(500, 0).isNaN()).isTrue()
        assertThat(row.rtf).isWithin(0.001).of(0.2)
    }

    @Test
    fun sentenceLineHasEveryFieldInOrder() {
        assertThat(VoiceLog.line(row)).isEqualTo(
            "Voice: engine=vits-piper-en_US-ljspeech-medium threads=2 passage=book sentence=3 chars=87 firstAudioMs=410 synthMs=410 audioMs=2050 rtf=0.20 rssMb=612",
        )
    }

    @Test
    fun unknownFirstAudioAndErrorsAreShown() {
        val failed = row.copy(firstAudioMs = null, audioMs = 0, error = "IllegalStateException: TextToSpeech error -3")
        assertThat(VoiceLog.line(failed)).endsWith("firstAudioMs=- synthMs=410 audioMs=0 rtf=- rssMb=612 error=IllegalStateException: TextToSpeech error -3")
    }

    @Test
    fun loadLineCarriesTheEnginesInfo() {
        assertThat(VoiceLog.line(LoadRow("system", 1, 835, 480, "engine=com.google.android.tts chosen=en-us-x-iom-local")))
            .isEqualTo("Voice: engine=system threads=1 load loadMs=835 rssMb=480 engine=com.google.android.tts chosen=en-us-x-iom-local")
        assertThat(VoiceLog.line(LoadRow("x", 4, 10, 20, null))).isEqualTo("Voice: engine=x threads=4 load loadMs=10 rssMb=20")
    }

    @Test
    fun summaryTotalsEachPassageAndCountsErrors() {
        val rows = listOf(
            row.copy(index = 0, synthMs = 300, audioMs = 1000, firstAudioMs = 300, rssMb = 600),
            row.copy(index = 1, synthMs = 700, audioMs = 1000, firstAudioMs = 650, rssMb = 640),
            row.copy(index = 2, synthMs = 100, audioMs = 0, firstAudioMs = null, rssMb = 610, error = "boom"),
            row.copy(passage = "answer", index = 0, synthMs = 500, audioMs = 2000, firstAudioMs = null, rssMb = 500),
        )
        assertThat(VoiceLog.summary(rows)).containsExactly(
            "Voice: engine=vits-piper-en_US-ljspeech-medium threads=2 passage=book summary sentences=3 synthMs=1100 audioMs=2000 rtf=0.55 firstAudioMaxMs=650 rssMaxMb=640 errors=1",
            "Voice: engine=vits-piper-en_US-ljspeech-medium threads=2 passage=answer summary sentences=1 synthMs=500 audioMs=2000 rtf=0.25 firstAudioMaxMs=- rssMaxMb=500",
        ).inOrder()
    }
}
