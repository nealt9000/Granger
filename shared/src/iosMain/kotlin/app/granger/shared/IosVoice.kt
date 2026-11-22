@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package app.granger.shared

import app.granger.UiText
import app.granger.core.downloads.VoiceDownloads
import app.granger.core.player.voice.SpeechOutput
import app.granger.core.speech.InstalledVoices
import app.granger.core.speech.Pcm
import app.granger.core.speech.PiperVoices
import app.granger.core.speech.Pronunciations
import app.granger.core.speech.QuoteSpans
import app.granger.core.speech.ReadAddress
import app.granger.core.speech.ReadSource
import app.granger.core.speech.ReadSourceKey
import app.granger.core.speech.Speech
import app.granger.core.speech.SpeechException
import app.granger.core.speech.SpeechFactory
import app.granger.core.speech.SpokenText
import app.granger.core.speech.SpokenWords
import app.granger.core.speech.Utterance
import app.granger.core.speech.Voice
import app.granger.core.speech.VoiceId
import app.granger.core.speech.VoiceTitles
import app.granger.listen.CardKind
import app.granger.listen.ListenModel
import app.granger.listen.NowPlayingCard
import app.granger.readaloud.EpubReadSource
import app.granger.readaloud.EpubTocEntry
import app.granger.readaloud.LastRead
import app.granger.readaloud.VoicePlayer
import app.granger.readaloud.VoiceUi
import app.granger.reader.ArticleReadSource
import app.granger.settings.VoicePrefs
import app.granger.settings.VoiceSettingsModel
import app.granger.voice.listenStart
import kotlin.concurrent.AtomicLong
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioFormat
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioPlayerNode
import platform.AVFAudio.AVAudioPlayerNodeCompletionDataPlayedBack
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMaximumSpeechRate
import platform.AVFAudio.AVSpeechUtteranceMinimumSpeechRate
import platform.Foundation.NSData
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSUserDefaults
import platform.posix.memcpy

// ---- Piper through sherpa-onnx (Plan I5 call 3): the engine is Swift's (the framework's C API), the rules are here ----

/** Loads a Piper voice in sherpa-onnx; Swift's `PiperEngine` behind it. */
interface PiperNatives {
    /** The voice in these files with its own sampling, or null when it won't load. */
    fun load(model: String, tokens: String, espeakData: String, noiseScale: Float, noiseScaleW: Float, lengthScale: Float): PiperHandle?
}

/** One loaded voice: one sentence per [generate] call, mono float samples (4 bytes each, native order) at [sampleRate]. */
interface PiperHandle {
    fun sampleRate(): Int
    fun generate(text: String, speaker: Int, speed: Float): NSData?
    fun close()
}

/**
 * A Granger voice on the iPhone: Android's `SherpaSpeech` over [PiperHandle]. The text is written out for the
 * phonemizer first ([SpokenWords], [SpokenText]) and respelled ([Pronunciations]); a voice with a dialogue speaker reads
 * each run in and out of quotes with its own speaker (V4b).
 */
class IosPiperSpeech internal constructor(
    override val voice: Voice,
    private val handle: PiperHandle,
    private val dialogueSpeaker: Int?,
) : Speech {
    private val narrator: Int get() = (voice.id as? VoiceId.Granger)?.speaker ?: 0

    override fun synthesize(text: String, speed: Float): Flow<Pcm> = synthesize(text, speed, quoteOpen = false)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun synthesize(text: String, speed: Float, quoteOpen: Boolean): Flow<Pcm> {
        val dialogue = dialogueSpeaker ?: return one(text, narrator, speed)
        val spans = QuoteSpans.split(text, quoteOpen).ifEmpty { listOf(QuoteSpans.Span(text, quoteOpen)) }
        return spans.asFlow().flatMapConcat { span -> one(span.text, if (span.quoted) dialogue else narrator, speed) }
    }

    private fun one(text: String, speaker: Int, speed: Float): Flow<Pcm> = flow {
        val data = handle.generate(spoken(text), speaker, speed) ?: return@flow
        emit(Pcm(floats(data), handle.sampleRate()))
    }.flowOn(Dispatchers.Default)

    internal fun spoken(text: String): String = Pronunciations.apply(SpokenText.forPiper(SpokenWords.forPiper(text)))

    override fun close() = handle.close()

    /** Opens Granger voices found under any of [voicesDirs]. */
    class Factory(private val voicesDirs: () -> List<String>, private val natives: PiperNatives) : SpeechFactory {
        override suspend fun open(voice: Voice): Speech {
            val id = voice.id as? VoiceId.Granger ?: throw SpeechException("Not a Granger voice: ${voice.id.encode()}")
            val found = withContext(Dispatchers.Default) { voicesDirs().firstNotNullOfOrNull { PiperVoices.detect("$it/${id.dir}") } }
                ?: throw SpeechException("The voice ${id.dir} is not installed")
            val layout = found.layout as? PiperVoices.Layout.Piper ?: throw SpeechException("The voice ${id.dir} isn't a Piper voice")
            val config = PiperVoices.config(found)
            val inference = PiperVoices.inference(config)
            val handle = withContext(Dispatchers.Default) {
                natives.load(
                    "${found.dir}/${layout.model}", "${found.dir}/${layout.tokens}", "${found.dir}/${layout.espeakData}",
                    inference.noiseScale, inference.noiseScaleW, inference.lengthScale,
                )
            } ?: throw SpeechException("The voice ${id.dir} didn't load")
            return IosPiperSpeech(voice, handle, PiperVoices.dialogueSpeaker(config))
        }
    }
}

private fun floats(data: NSData): FloatArray {
    val n = (data.length / 4u).toInt()
    val out = FloatArray(n)
    if (n > 0) out.usePinned { memcpy(it.addressOf(0), data.bytes, (n * 4).convert()) }
    return out
}

// ---- the system voices (AVSpeechSynthesizer), where Android uses its TextToSpeech ----

/**
 * The phone's own voices: AVSpeechSynthesizer writes a sentence's audio into buffers ([AVSpeechSynthesizer.writeUtterance])
 * and they go through the same engine and output as a Granger voice. English voices only, best quality first, novelty
 * voices left out, as Android lists its engine's offline English voices.
 */
object IosSystemSpeech {
    const val ENGINE = "com.apple.speech"

    fun voices(titles: VoiceTitles): List<Voice> =
        AVSpeechSynthesisVoice.speechVoices().filterIsInstance<AVSpeechSynthesisVoice>()
            .filter { it.language.startsWith("en") && (it.voiceTraits and NOVELTY) == 0uL }
            .sortedWith(compareByDescending<AVSpeechSynthesisVoice> { it.quality }.thenBy { it.name })
            .map { Voice(VoiceId.System(ENGINE, it.identifier), titles.system(it.name, it.language), it.language) }

    private const val NOVELTY = 2uL   // AVSpeechSynthesisVoiceTraitIsNoveltyVoice

    val factory: SpeechFactory = object : SpeechFactory {
        override suspend fun open(voice: Voice): Speech {
            val id = voice.id as? VoiceId.System ?: throw SpeechException("Not a system voice")
            val v = AVSpeechSynthesisVoice.voiceWithIdentifier(id.name) ?: throw SpeechException("The voice ${voice.title} is gone")
            return SystemSpeech(voice, v)
        }
    }

    private class SystemSpeech(override val voice: Voice, private val native: AVSpeechSynthesisVoice) : Speech {
        private val synthesizer = AVSpeechSynthesizer()

        override fun synthesize(text: String, speed: Float): Flow<Pcm> = callbackFlow {
            val utterance = AVSpeechUtterance(string = text)
            utterance.voice = native
            utterance.rate = (AVSpeechUtteranceDefaultSpeechRate * speed).coerceIn(AVSpeechUtteranceMinimumSpeechRate, AVSpeechUtteranceMaximumSpeechRate)
            synthesizer.writeUtterance(utterance) { buffer ->
                val pcm = buffer as? AVAudioPCMBuffer
                val frames = pcm?.frameLength?.toInt() ?: 0
                if (pcm == null || frames == 0) { close(); return@writeUtterance }
                val rate = pcm.format.sampleRate.toInt()
                val samples = FloatArray(frames)
                val f = pcm.floatChannelData
                val i16 = pcm.int16ChannelData
                if (f != null) {
                    val ch = f[0]!!
                    for (k in 0 until frames) samples[k] = ch[k]
                } else if (i16 != null) {
                    val ch = i16[0]!!
                    for (k in 0 until frames) samples[k] = ch[k] / 32768f
                }
                trySend(Pcm(samples, rate))
            }
            awaitClose { }
        }

        override fun close() { synthesizer.stopSpeakingAtBoundary(platform.AVFAudio.AVSpeechBoundary.AVSpeechBoundaryImmediate) }
    }
}

// ---- the output: AVAudioEngine (call 4), Android's AudioTrack ----

/**
 * The voice's output on the iPhone: the voice node of the app's one [AudioGraph] (Plan I6), beside the media node. A write is cut into pieces of about a
 * tenth of a second and waits while more than [AHEAD_MS] is queued unheard, as AudioTrack's small buffer makes Android's
 * write wait, so the sentence marked is the sentence heard. [playedMs] counts what the node has played back since the
 * last flush; [volume] is the node's (the sleep timer's fade). The audio session is the app's (Swift).
 */
class AvAudioSpeechOutput(private val graph: AudioGraph) : SpeechOutput {
    /** A graph of its own (tests, and the voice alone). */
    constructor() : this(AudioGraph())

    private val node get() = graph.voice
    private var rate = 0
    private var format: AVAudioFormat? = null
    private val queued = AtomicLong(0)      // frames scheduled and not yet played back
    private val played = AtomicLong(0)      // frames played back since the last flush
    private val generation = AtomicLong(0)  // bumped by flush, so a stopped buffer's callback counts nothing
    private var playing = false

    init {
        // The engine stopped and started again (another rate's reconnect, a route change): what was queued is gone.
        graph.onRestart(before = {}, after = {
            graph.locked {
                generation.incrementAndGet()
                node.stop()
                queued.value = 0
                if (playing && format != null) node.play()
            }
        })
    }

    private inline fun <T> locked(block: () -> T): T = graph.locked(block)

    override fun open(sampleRate: Int) = locked {
        if (sampleRate == rate && format != null) return@locked
        val f = AVAudioFormat(standardFormatWithSampleRate = sampleRate.toDouble(), channels = 1u)
        generation.incrementAndGet()
        node.stop()
        graph.connectVoice(sampleRate)
        format = f
        rate = sampleRate
        queued.value = 0; played.value = 0
        generation.incrementAndGet()
        if (playing) startEngine()
    }

    override suspend fun write(pcm: Pcm) {
        val f = format ?: return
        val chunk = (rate / 10).coerceAtLeast(1)
        var at = 0
        while (at < pcm.samples.size) {
            while (queued.value * 1000 / rate.coerceAtLeast(1) > AHEAD_MS) delay(15)
            val n = minOf(chunk, pcm.samples.size - at)
            val buffer = AVAudioPCMBuffer(pCMFormat = f, frameCapacity = n.toUInt()) ?: return
            buffer.frameLength = n.toUInt()
            val dst = buffer.floatChannelData!![0]!!
            pcm.samples.usePinned { memcpy(dst, it.addressOf(at), (n * 4).convert()) }
            val gen = generation.value
            locked {
                queued.addAndGet(n.toLong())
                node.scheduleBuffer(buffer, AVAudioPlayerNodeCompletionDataPlayedBack) { _ ->
                    if (generation.value == gen) { queued.addAndGet(-n.toLong()); played.addAndGet(n.toLong()) }
                }
            }
            at += n
        }
    }

    private fun startEngine() {
        graph.startEngine()
        node.play()
    }

    override fun play() = locked {
        playing = true
        if (format != null) startEngine()
    }

    override fun pause() = locked {
        playing = false
        node.pause()
    }

    override fun flush() = locked {
        generation.incrementAndGet()
        node.stop()
        queued.value = 0; played.value = 0
        if (playing && format != null) node.play()
    }

    override var volume: Float
        get() = node.volume
        set(value) { node.volume = value.coerceIn(0f, 1f) }

    override val playedMs: Long get() = if (rate <= 0) 0L else played.value * 1000 / rate

    /** The voice node stops; the engine goes on for the music (Plan I6: one graph). */
    override fun close() = locked {
        playing = false
        generation.incrementAndGet()
        node.stop()
        queued.value = 0
    }

    private companion object {
        const val AHEAD_MS = 200L
    }
}

// ---- unpacking a voice zip (Android's VoiceUnpacker over java.util.zip) ----

object IosVoiceUnpacker {
    /** `voices/<id>.zip` → `voices/<id>/` through `voices/<id>.part/`; the zip goes. The rules are [VoiceDownloads]'. */
    fun unpack(zipPath: String): Result<String> = runCatching {
        val target = VoiceDownloads.voiceDir(zipPath)
        val part = VoiceDownloads.partDir(zipPath)
        deleteTree(part)
        val names = app.granger.core.books.unzipInto(zipPath, part, VoiceDownloads::isSafeEntry)
        if (VoiceDownloads.MANIFEST !in names) throw IllegalStateException("No ${VoiceDownloads.MANIFEST} in $zipPath")
        deleteTree(target)
        kotlinx.io.files.SystemFileSystem.atomicMove(kotlinx.io.files.Path(part), kotlinx.io.files.Path(target))
        kotlinx.io.files.SystemFileSystem.delete(kotlinx.io.files.Path(zipPath), mustExist = false)
        target
    }.onFailure { deleteTree(VoiceDownloads.partDir(zipPath)) }

    internal fun deleteTree(path: String) {
        val p = kotlinx.io.files.Path(path)
        runCatching {
            val meta = kotlinx.io.files.SystemFileSystem.metadataOrNull(p) ?: return
            if (meta.isDirectory) kotlinx.io.files.SystemFileSystem.list(p).forEach { deleteTree(it.toString()) }
            kotlinx.io.files.SystemFileSystem.delete(p, mustExist = false)
        }
    }
}

// ---- Settings → Voice in UserDefaults ----

class DefaultsVoicePrefs : VoicePrefs {
    private val d = NSUserDefaults.standardUserDefaults
    private val voice = MutableStateFlow(d.stringForKey(KEY_VOICE)?.let(VoiceId::decode))
    private val rate = MutableStateFlow((if (d.objectForKey(KEY_SPEED) != null) d.doubleForKey(KEY_SPEED).toFloat() else 1f).coerceIn(VoicePrefs.MIN_SPEED, VoicePrefs.MAX_SPEED))
    private val mark = MutableStateFlow(if (d.objectForKey(KEY_MARK) != null) d.boolForKey(KEY_MARK) else true)
    private val speak = MutableStateFlow(d.boolForKey(KEY_SPEAK))

    override val voiceId: StateFlow<VoiceId?> = voice
    override val speed: StateFlow<Float> = rate
    override val markSentence: StateFlow<Boolean> = mark
    override val speakAnswers: StateFlow<Boolean> = speak

    override fun setVoice(id: VoiceId?) { if (id == null) d.removeObjectForKey(KEY_VOICE) else d.setObject(id.encode(), KEY_VOICE); voice.value = id }
    override fun setSpeed(speed: Float) { val s = speed.coerceIn(VoicePrefs.MIN_SPEED, VoicePrefs.MAX_SPEED); d.setDouble(s.toDouble(), KEY_SPEED); rate.value = s }
    override fun setMarkSentence(on: Boolean) { d.setBool(on, KEY_MARK); mark.value = on }
    override fun setSpeakAnswers(on: Boolean) { d.setBool(on, KEY_SPEAK); speak.value = on }

    private companion object {
        const val KEY_VOICE = "voice.voiceId"
        const val KEY_SPEED = "voice.speed"
        const val KEY_MARK = "voice.markSentence"
        const val KEY_SPEAK = "voice.speakAnswers"
    }
}

// ---- the iPhone's voice, wired as Android's GrangerApp wires its player's voice ----

/** The sentence a reader marks: its block (articles) or locator (books), the range, and whether the page follows it. */
data class ArticleMark(val blockId: String, val prefix: String, val start: Int, val end: Int)

/**
 * Read aloud on the iPhone (Plan I5 Task 8): the voices (Granger voices unpacked under `.granger/voices/`, then the
 * system's), Settings → Voice, the shared [VoicePlayer] over [AvAudioSpeechOutput], and what the readers ask of it.
 * [audioSession] is told when the voice wants the audio session (Swift activates AVAudioSession for playback).
 */
class IosVoice(
    private val library: IosLibrary,
    natives: PiperNatives,
    audioSession: (Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val prefs = DefaultsVoicePrefs()
    private val titles = VoiceTitles("Granger voice (%1\$s)", "System voice %1\$s (%2\$s)", "System voice default (%1\$s)")

    /** The folder the queue unpacks voices into: Documents/.granger/voices. */
    val voicesDir: String get() = VoiceDownloads.voicesDir(library.downloadRoot)

    val installed = InstalledVoices(
        voicesDirs = { listOf(voicesDir) },
        systemVoices = { IosSystemSpeech.voices(titles) },
        granger = IosPiperSpeech.Factory({ listOf(voicesDir) }, natives),
        system = IosSystemSpeech.factory,
        io = app.granger.screens.ioDispatcher,
        titles = titles,
    )

    /** The app's one audio graph (Plan I6 call 1): the voice and the media node mix in it. */
    val graph = AudioGraph()
    private val output = AvAudioSpeechOutput(graph)
    private val playback = library.database.playbackDao()

    val player = VoicePlayer(output, installed, prefs, playback, ::source, scope, { app.granger.screens.nowMillis() }, audioSession)

    /** Music, audiobooks and the voice behind the shared PlayerHub (Plan I6 Task 5). */
    val hub = IosPlayerHub(graph, IosMediaLibrary(library.database), playback, DefaultsPlayerPrefs(), player, player.ui, scope,
        { app.granger.screens.nowMillis() })

    // Books' contents for a restore with no reader open: remembered as the reader opens each book.
    private val tocs = HashMap<String, Pair<List<EpubTocEntry>, List<String>>>()

    init {
        scope.launch { installed.refresh() }
        scope.launch { library.voicesChanged.collect { installed.refresh() } }
    }

    fun refreshVoices() { scope.launch { installed.refresh() } }

    /** Settings → Voice: the shared model over the queue, with Documents as the one volume. */
    fun settings(): OwnedViewModel<VoiceSettingsModel> = OwnedViewModel {
        VoiceSettingsModel(
            prefs, installed, library.catalogSource, library.downloads,
            voiceRoot = { library.downloadRoot }, voicesDirs = { listOf(voicesDir) }, wifiOnly = { library.wifiOnlyNow() },
            appScope = scope,
        )
    }

    /** Listen's home: the shared ListenModel with the voice's Now playing card (music and audiobooks join in I6). */
    fun listen(): OwnedViewModel<ListenModel> = OwnedViewModel { ListenModel(library.shelves, card) }

    /** The Now playing card for the voice: what it reads, else the last thing read. */
    val card: Flow<NowPlayingCard?> = combine(player.ui, player.lastRead) { ui, last -> cardOf(ui, last) }

    private fun cardOf(ui: VoiceUi, last: LastRead?): NowPlayingCard? {
        val now = ui.now
        if (now != null) {
            val label = when {
                !ui.playing -> NowPlayingCard.PAUSED
                else -> NowPlayingCard.READING_ALOUD
            }
            return NowPlayingCard(label, UiText.Raw(now.title), UiText.Raw(now.section.orEmpty()), null, CardKind.VOICE, live = true, playing = ui.playing)
        }
        last ?: return null
        return NowPlayingCard(NowPlayingCard.LAST_READ, UiText.Raw(last.title), UiText.Raw(last.section.orEmpty()), null, CardKind.VOICE, live = false, playing = false)
    }

    // ---- sources ----

    private val articleSources = HashMap<String, ArticleReadSource>()

    /** The article [path] of [packId] as the voice reads it (one kept per article, so its sentences are split once). */
    fun articleSource(packId: String, path: String, title: String): ArticleReadSource? {
        val key = ReadSourceKey.article(packId, path)
        articleSources[key]?.let { return it }
        val archive = library.archive(packId) ?: return null
        return ArticleReadSource(archive, packId, path, title).also { articleSources[key] = it }
    }

    /** The EPUB [bookId] at [path] with the reader's contents and reading order (Readium's hrefs). */
    fun bookSource(bookId: String, title: String, path: String, toc: List<EpubTocEntry>, readingOrder: List<String>): EpubReadSource {
        tocs[bookId] = toc to readingOrder
        val existing = player.ui.value.now?.takeIf { it.sourceKey == ReadSourceKey.book(bookId) }?.let { current as? EpubReadSource }
        return existing ?: EpubReadSource(bookId, title, path, toc, readingOrder).also { current = it }
    }

    private var current: ReadSource? = null

    /** A saved source reopened (Listen's Resume, a restore): an article from its pack or Wikipedia online, a book from the shelf. */
    private suspend fun source(key: String, title: String): ReadSource? = when (val k = ReadSourceKey.parse(key)) {
        is ReadSourceKey.Article -> articleSource(k.packId, k.path, title)
        is ReadSourceKey.Book -> {
            val book = library.openableBook(k.bookId)
            if (book == null || !book.filePath.endsWith(".epub", ignoreCase = true)) null
            else {
                val (toc, order) = tocs[k.bookId] ?: loadToc(k.bookId)
                EpubReadSource(k.bookId, book.title, book.filePath, toc, order).also { current = it }
            }
        }
        else -> null
    }

    // ---- what the readers ask ----

    /**
     * Listen in the article reader: from the first block the page shows ([blockId], [offset]), else the article's first
     * sentence, or its saved place when that is further on (Android's `listenStart`).
     */
    fun readArticle(packId: String, path: String, title: String, blockId: String?, offset: Int?) {
        scope.launch {
            val src = articleSource(packId, path, title) ?: return@launch
            val visible = blockId?.let { src.addressAt(it, offset) } ?: src.firstAddress() ?: return@launch
            val saved = runCatching { playback.voiceState(src.key)?.address?.let(ReadAddress::decode) }.getOrNull()
            player.readAloud(src, listenStart(src, visible, saved))
        }
    }

    /** Read from here (a selection) in an article: moves the voice when it reads this page, else starts it there. */
    fun readArticleFrom(packId: String, path: String, title: String, blockId: String, offset: Int?) {
        scope.launch {
            val src = articleSource(packId, path, title) ?: return@launch
            val at = src.addressAt(blockId, offset) ?: return@launch
            if (player.ui.value.now?.sourceKey == src.key) player.jumpTo(at) else player.readAloud(src, at)
        }
    }

    /** The mark for the sentence being read when it's in this article; null otherwise. */
    fun articleMark(packId: String, path: String): ArticleMark? {
        val ui = player.ui.value
        if (ui.now?.sourceKey != ReadSourceKey.article(packId, path)) return null
        val p = player.progress.value ?: return null
        val a = p.address as? ReadAddress.Article ?: return null
        return ArticleMark(a.blockId, if (p.utterance.start == 0) p.utterance.text.take(40) else "", p.utterance.start, p.utterance.end)
    }

    /**
     * Listen in the e-book reader: at the page's first words ([visibleText]) in the file [href] points into, or at the
     * book's saved place when that is further on; on the cover or the contents, past the front matter.
     */
    fun readBook(source: EpubReadSource, href: String?, visibleText: String?) {
        scope.launch {
            val visible = href?.let { source.addressAtText(it, visibleText) }?.let { source.pastFrontMatter(it) }
                ?: source.firstAfterFrontMatter() ?: return@launch
            val saved = runCatching { playback.voiceState(source.key)?.address?.let(ReadAddress::decode) }.getOrNull()
            player.readAloud(source, listenStart(source, visible, saved))
        }
    }

    /** Read from here in a book: the sentence holding [text] in [href]'s file. */
    fun readBookFrom(source: EpubReadSource, href: String, text: String) {
        scope.launch {
            val at = source.addressAtText(href, text) ?: return@launch
            if (player.ui.value.now?.sourceKey == source.key) player.jumpTo(at) else player.readAloud(source, at)
        }
    }

    /** The Readium locator (JSON) of the sentence being read when it's in [source]'s book; null otherwise. */
    suspend fun bookMark(source: EpubReadSource): String? {
        if (player.ui.value.now?.sourceKey != source.key) return null
        val u: Utterance = player.progress.value?.utterance ?: return null
        return source.locatorJson(u)
    }

    // A book's contents kept for restores, in UserDefaults (`voice.toc.<id>`): one line per entry, depth, href, title.
    fun rememberToc(bookId: String, toc: List<EpubTocEntry>, readingOrder: List<String>) {
        tocs[bookId] = toc to readingOrder
        val text = toc.joinToString("\n") { "${it.depth}\t${it.href}\t${it.title.replace('\n', ' ').replace('\t', ' ')}" } + "\n\n" + readingOrder.joinToString("\n")
        NSUserDefaults.standardUserDefaults.setObject(text, "voice.toc.$bookId")
    }

    private fun loadToc(bookId: String): Pair<List<EpubTocEntry>, List<String>> {
        val text = NSUserDefaults.standardUserDefaults.stringForKey("voice.toc.$bookId") ?: return emptyList<EpubTocEntry>() to emptyList()
        val (tocPart, orderPart) = text.split("\n\n", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        val toc = tocPart.lines().mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) null else EpubTocEntry(parts[2], parts[1], parts[0].toIntOrNull() ?: 0)
        }
        return toc to orderPart.lines().filter { it.isNotBlank() }
    }

    /** Debug builds and UI tests: the voice folder at [from] (an unpacked catalog voice on the Mac) copied in as installed. */
    fun debugInstallVoice(from: String): Boolean = runCatching {
        val name = from.trimEnd('/').substringAfterLast('/')
        val target = "$voicesDir/$name"
        if (PiperVoices.detect(target) == null) copyTree(from, target)
        scope.launch { installed.refresh() }
        PiperVoices.detect(target) != null
    }.getOrDefault(false)

    private fun copyTree(from: String, to: String) {
        val fs = kotlinx.io.files.SystemFileSystem
        val src = kotlinx.io.files.Path(from)
        val meta = fs.metadataOrNull(src) ?: return
        if (meta.isDirectory) {
            fs.createDirectories(kotlinx.io.files.Path(to))
            fs.list(src).forEach { copyTree(it.toString(), "$to/${it.name}") }
        } else {
            fs.source(src).buffered().use { input -> fs.sink(kotlinx.io.files.Path(to)).buffered().use { out -> input.transferTo(out) } }
        }
    }
}
