package app.granger.core.speech

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SherpaVoiceTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String, vararg files: String): File {
        val d = tmp.newFolder(name)
        for (f in files) {
            if (f.endsWith("/")) File(d, f.dropLast(1)).mkdirs() else File(d, f).writeText("x")
        }
        return d
    }

    @Test
    fun piperVoiceHasOneModelTokensAndEspeakData() {
        val d = dir("vits-piper-en_US-ljspeech-medium", "en_US-ljspeech-medium.onnx", "en_US-ljspeech-medium.onnx.json", "tokens.txt", "espeak-ng-data/", "MODEL_CARD")
        val v = SherpaVoice.detect(d)
        assertThat(v).isInstanceOf(SherpaVoice.Piper::class.java)
        v as SherpaVoice.Piper
        assertThat(v.id).isEqualTo("vits-piper-en_US-ljspeech-medium")
        assertThat(v.model.name).isEqualTo("en_US-ljspeech-medium.onnx")
        assertThat(v.tokens.name).isEqualTo("tokens.txt")
        assertThat(v.espeakData.name).isEqualTo("espeak-ng-data")
    }

    @Test
    fun piperInferenceComesFromTheConfigWithSherpaDefaultsForWhatIsMissing() {
        val d = dir("vits-piper-en_GB-granger_nanny-medium", "en_GB-granger_nanny-medium.onnx", "tokens.txt", "espeak-ng-data/")
        val config = File(d, "en_GB-granger_nanny-medium.onnx.json")
        val v = SherpaVoice.detect(d)!!
        assertThat(SherpaVoice.inference(v)).isEqualTo(SherpaVoice.Inference())
        config.writeText("""{"audio": {"sample_rate": 22050}, "inference": {"noise_scale": 0.75, "length_scale": 1, "noise_w": 1.0}}""")
        assertThat(SherpaVoice.inference(v)).isEqualTo(SherpaVoice.Inference(noiseScale = 0.75f, noiseScaleW = 1f, lengthScale = 1f))
        config.writeText("""{"inference": {"noise_w": 0.9}}""")
        assertThat(SherpaVoice.inference(v)).isEqualTo(SherpaVoice.Inference(noiseScaleW = 0.9f))
    }

    @Test
    fun aDialogueSlotIsReadFromTheConfigAndLeftOutOfTheSpeakers() {
        val d = dir("vits-piper-en_GB-granger_nanny-medium", "en_GB-granger_nanny-medium.onnx", "tokens.txt", "espeak-ng-data/")
        val config = File(d, "en_GB-granger_nanny-medium.onnx.json")
        val v = SherpaVoice.detect(d)!!
        config.writeText("""{"speaker_id_map": {"narration": 0, "dialogue": 1}, "granger": {"dialogue_speaker": 1}}""")
        assertThat(SherpaVoice.dialogueSpeaker(v)).isEqualTo(1)
        assertThat(SherpaVoice.speakerNames(v)).isEmpty()
        config.writeText("""{"speaker_id_map": {"Maggie": 0, "Martin": 1, "dialogue": 2}, "granger": {"dialogue_speaker": 2}}""")
        assertThat(SherpaVoice.speakerNames(v)).containsExactly("Maggie", "Martin").inOrder()
        config.writeText("""{"speaker_id_map": {"Maggie": 0, "Martin": 1}}""")
        assertThat(SherpaVoice.dialogueSpeaker(v)).isNull()
        assertThat(SherpaVoice.speakerNames(v)).containsExactly("Maggie", "Martin").inOrder()
    }

    @Test
    fun kokoroMultiLangVoiceTakesInt8ModelLexiconsAndDict() {
        val d = dir(
            "kokoro-int8-multi-lang-v1_1",
            "model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/", "lexicon-us-en.txt", "lexicon-gb-en.txt", "lexicon-zh.txt", "dict/",
            "date-zh.fst", "LICENSE", "README.md",
        )
        val v = SherpaVoice.detect(d)
        assertThat(v).isInstanceOf(SherpaVoice.Kokoro::class.java)
        v as SherpaVoice.Kokoro
        assertThat(v.model.name).isEqualTo("model.int8.onnx")
        assertThat(v.lexicons.map { it.name }).containsExactly("lexicon-us-en.txt", "lexicon-zh.txt").inOrder()
        assertThat(v.dictDir?.name).isEqualTo("dict")
    }

    @Test
    fun kokoroEnglishOnlyVoiceHasNoLexicons() {
        val d = dir("kokoro-en-v0_19", "model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/")
        val v = SherpaVoice.detect(d) as SherpaVoice.Kokoro
        assertThat(v.model.name).isEqualTo("model.onnx")
        assertThat(v.lexicons).isEmpty()
        assertThat(v.dictDir).isNull()
    }

    @Test
    fun directoriesWithoutTokensOrEspeakDataOrAModelAreNotVoices() {
        assertThat(SherpaVoice.detect(dir("no-tokens", "a.onnx", "espeak-ng-data/"))).isNull()
        assertThat(SherpaVoice.detect(dir("no-espeak", "a.onnx", "tokens.txt"))).isNull()
        assertThat(SherpaVoice.detect(dir("no-model", "tokens.txt", "espeak-ng-data/"))).isNull()
        assertThat(SherpaVoice.detect(dir("two-models", "a.onnx", "b.onnx", "tokens.txt", "espeak-ng-data/"))).isNull()
        assertThat(SherpaVoice.detect(File(tmp.root, "missing"))).isNull()
    }

    @Test
    fun listFindsVoicesUnderARootInNameOrder() {
        dir("vits-piper-en_US-ljspeech-medium", "a.onnx", "tokens.txt", "espeak-ng-data/")
        dir("kokoro-en-v0_19", "model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/")
        dir("music", "song.mp3")
        tmp.newFile("stray.txt")
        assertThat(SherpaVoice.list(tmp.root).map { it.id }).containsExactly("kokoro-en-v0_19", "vits-piper-en_US-ljspeech-medium").inOrder()
    }
}
