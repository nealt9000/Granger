package app.granger.core.speech

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.files.SystemTemporaryDirectory
import kotlinx.io.writeString

/**
 * The shared read-aloud rules on every phone: utterances and their pauses, voice titles, and the voice directories.
 * The paragraphs here break the same way with ICU and with the plain JVM fallback, so the test runs anywhere.
 */
class SpeechRulesTest {
    private fun text(block: String, n: Int): ReadAddress = ReadAddress.Text("t", block.toInt(), n)

    @Test fun blocksBecomeUtterancesWithTheirPauses() {
        val blocks = listOf(
            Block("0", BlockKind.HEADING, "Mars"),
            Block("1", BlockKind.PARAGRAPH, "Mars is red. It has two moons."),
            Block("2", BlockKind.CODE, "x = 1"),
            Block("3", BlockKind.PARAGRAPH, "\"Go now. Hurry.\" She went."),
        )
        val u = Utterances.fromBlocks(blocks, ::text)
        assertEquals(listOf("Mars", "Mars is red.", "It has two moons.", Utterances.CODE_OMITTED, "\"Go now.", "Hurry.\"", "She went."), u.map { it.text })
        assertEquals(listOf(UtteranceKind.HEADING, UtteranceKind.SENTENCE, UtteranceKind.SENTENCE, UtteranceKind.CODE_OMITTED,
            UtteranceKind.SENTENCE, UtteranceKind.SENTENCE, UtteranceKind.SENTENCE), u.map { it.kind })
        assertEquals(listOf(0, 1, 1, 2, 3, 3, 3), u.map { it.paragraph })
        assertEquals(listOf(Utterances.HEADING_PAUSE_MS, Utterances.SENTENCE_PAUSE_MS, Utterances.PARAGRAPH_PAUSE_MS, Utterances.PARAGRAPH_PAUSE_MS,
            Utterances.SENTENCE_PAUSE_MS, Utterances.SENTENCE_PAUSE_MS, Utterances.PARAGRAPH_PAUSE_MS), u.map { it.pauseAfterMs })
        assertEquals(listOf(false, false, false, false, false, true, false), u.map { it.quoteOpen })
        val moons = u[2]
        assertEquals("It has two moons.", Sentences.fold(blocks[1].text).substring(moons.start, moons.end))
        assertEquals(ReadAddress.Text("t", 1, 1), moons.address)
        assertEquals(moons.address, ReadAddress.decode(moons.address.encode()))
    }

    @Test fun aPiperVoiceIsSpokenWithItsWordsWrittenOut() {
        assertEquals("Doctor Watson met Mister Holmes in eighteen eighty-one.", SpokenText.forPiper(SpokenWords.forPiper("Dr. Watson met Mr. Holmes in 1881.")))
        assertEquals("Chapter 3", SpokenWords.forPiper("Chapter III"))
    }

    @Test fun textReadSourcesHandOverSentencesInOrder() = runTest {
        val source = TextReadSource("text:a", "A", "One two. Three four.\n\nFive.")
        val all = source.utterances(null, 10)
        assertEquals(listOf("One two.", "Three four.", "Five."), all.map { it.text })
        assertEquals(listOf("Three four.", "Five."), source.utterances(all[1].address, 10).map { it.text })
    }

    @Test fun voiceTitlesFillTheirTemplates() {
        val english = VoiceTitles("Granger voice (%1\$s)", "System voice %1\$s (%2\$s)", "System voice default (%1\$s)")
        assertEquals("Granger voice (Nanny)", PiperVoices.title("vits-piper-en_GB-granger_nanny-medium", english))
        assertEquals("Granger voice (ljspeech)", PiperVoices.title("vits-piper-en_US-ljspeech-medium", english))
        assertEquals("System voice iom (en-US)", english.system("iom", "en-US"))
        assertEquals("System voice default (en-US)", english.system(null, "en-US"))
        assertEquals("Voz del sistema iom (es-US)", VoiceTitles("", "Voz del sistema %1\$s (%2\$s)", "").system("iom", "es-US"))
        assertEquals("50% (a)", VoiceTitles.fill("50%% (%s)", "a"))
        assertEquals("b then a", VoiceTitles.fill("%2\$s then %1\$s", "a", "b"))
    }

    @Test fun aVoiceDirectoryIsRecognisedByItsFiles() {
        fun e(name: String, dir: Boolean = false) = PiperVoices.Entry(name, dir)
        assertEquals(PiperVoices.Layout.Piper("v.onnx", "tokens.txt", "espeak-ng-data"),
            PiperVoices.detect(listOf(e("v.onnx"), e("v.onnx.json"), e("tokens.txt"), e("espeak-ng-data", true), e("manifest.json"))))
        assertNull(PiperVoices.detect(listOf(e("v.onnx"), e("tokens.txt"))))                                   // no espeak-ng-data
        assertNull(PiperVoices.detect(listOf(e("a.onnx"), e("b.onnx"), e("tokens.txt"), e("espeak-ng-data", true))))
        val kokoro = PiperVoices.detect(listOf(e("model.onnx"), e("model.int8.onnx"), e("voices.bin"), e("tokens.txt"), e("espeak-ng-data", true),
            e("lexicon-us-en.txt"), e("lexicon-gb-en.txt"), e("lexicon-zh.txt"), e("dict", true)))
        assertEquals(PiperVoices.Layout.Kokoro("model.int8.onnx", "voices.bin", "tokens.txt", "espeak-ng-data", listOf("lexicon-us-en.txt", "lexicon-zh.txt"), "dict"), kokoro)
        val config = """{"speaker_id_map": {"Maggie": 0, "Martin": 1, "Maggie (dialogue)": 2}, "granger": {"dialogue_speaker": 2}, "inference": {"noise_scale": 0.75, "noise_w": 1.0}}"""
        assertEquals(listOf("Maggie", "Martin"), PiperVoices.speakerNames(config))
        assertEquals(2, PiperVoices.dialogueSpeaker(config))
        assertEquals(PiperVoices.Inference(0.75f, 1f, 1f), PiperVoices.inference(config))
    }

    @Test fun voicesOnDiskAreListedByIdWithTheirSpeakers() = runTest {
        val root = Path(SystemTemporaryDirectory, "speech-rules-${Random.nextLong().toULong()}")
        fun write(file: Path, text: String = "x") { SystemFileSystem.sink(file).buffered().use { it.writeString(text) } }
        fun voice(id: String, config: String? = null) {
            val dir = Path(root, id)
            SystemFileSystem.createDirectories(Path(dir, "espeak-ng-data"))
            write(Path(dir, "$id.onnx")); write(Path(dir, "tokens.txt"))
            if (config != null) write(Path(dir, "$id.onnx.json"), config)
        }
        try {
            voice("vits-piper-en_US-ljspeech-medium")
            voice("vits-piper-en_GB-granger_owl-medium", """{"speaker_id_map": {"Owl": 0, "Hoot": 1}}""")
            SystemFileSystem.createDirectories(Path(root, "not-a-voice"))
            val found = PiperVoices.list(root.toString())
            assertEquals(listOf("vits-piper-en_GB-granger_owl-medium", "vits-piper-en_US-ljspeech-medium"), found.map { it.id })
            assertEquals(listOf("Owl", "Hoot"), PiperVoices.speakerNames(found[0]))
            assertTrue(PiperVoices.list(Path(root, "missing").toString()).isEmpty())

            val none = object : SpeechFactory { override suspend fun open(voice: Voice): Speech = throw SpeechException("not here") }
            val voices = InstalledVoices({ listOf(root.toString()) }, { emptyList() }, none, none)
            assertEquals(
                listOf(VoiceId.Granger("vits-piper-en_GB-granger_owl-medium", 0), VoiceId.Granger("vits-piper-en_GB-granger_owl-medium", 1),
                    VoiceId.Granger("vits-piper-en_US-ljspeech-medium")),
                voices.refresh().map { it.id },
            )
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(p: Path) {
        if (SystemFileSystem.metadataOrNull(p)?.isDirectory == true) SystemFileSystem.list(p).forEach { deleteTree(it) }
        SystemFileSystem.delete(p, mustExist = false)
    }
}
