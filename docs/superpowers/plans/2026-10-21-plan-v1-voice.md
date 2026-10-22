# Plan V1: The voice — read-aloud in both readers, through the player — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Granger reads aloud. In the web reader and the e-book reader, "Read aloud" starts a voice at the first visible paragraph (or "Read from here" at a selection), marks the sentence being read, turns the page or scrolls as it goes, and keeps reading with the screen off, as a third thing the player plays beside music and audiobooks: in the mini-player, the notification, the lock screen and on the headset button, with its place saved like an audiobook's. Two engines behind one interface: the phone's own text-to-speech, and the Granger voice (Piper `ljspeech` through sherpa-onnx) downloaded from the catalog. Spec: `docs/superpowers/specs/2026-10-21-voice-design.md` §3–§6, §10. Numbers and decisions from the spike: `docs/superpowers/spikes/2026-10-voice-spike-results.md`.

**Out of this plan (V2):** spoken Research answers, music under the voice and the mix slider, the voice's full-view sheet, the rail entry, `ReadAloud.start` for project 4, the second catalog voice. In V1 starting the voice pauses music, as starting an audiobook does.

**Architecture:**
- **`:core:speech`** (exists from V0, debug-only until now) becomes a real dependency of `:app` and `:core:player`: the `Speech` interface, `SystemSpeech` (Android `TextToSpeech`, `synthesizeToFile` per sentence), `SherpaSpeech` (the V0 `SherpaTts`, two threads, object-expression callback), `Sentences` (ICU), `Pronunciations` (respellings applied before Piper), `Utterance`/`ReadAddress`/`ReadSource` (the engine-neutral text model), `InstalledVoices` (what can speak now, offline).
- **`:core:player`:** `ReadAloudEngine` (the utterance queue: look-ahead synthesis, a streaming `AudioTrack` output, pause at sentence start, next and previous by paragraph, speed through the engine) and a third `PlayerEngine` mode, `NowPlaying.Voice`, carried through the existing `MediaLibrarySession` by a **silent carrier item** in ExoPlayer so the notification, lock screen, headset buttons, focus and sleep timer work unchanged (Decision 2). Saved place in a new `voice_states` table (library v11), restored paused.
- **`:app` readers:** the web reader gets a read-aloud page script (blocks → sentences, the sentence highlight through the CSS Custom Highlight API, Follow), "Read aloud" in ⋯ and "Read from here" in the selection menu. The e-book reader gets the same through Readium's content iterator and `applyDecorations(group = "voice")`, the reserved bottom-bar button and the selection menu.
- **`:core:catalog`, `:core:downloads`, `:app` settings:** `voices` in the catalog (one zip per voice, unpacked after verification into `voices/<id>/`), `ItemKind.VOICE`, **Settings → Voice**, voices in Readiness.

**Tech Stack:** unchanged: Kotlin 2.4.21, Compose Material 3, Media3 1.11.1, Room 2.8.5 (manual migration 10→11), Readium 3.4.0 (`readium-shared`'s content iterator and `DefaultTextContentTokenizer`, experimental API), sherpa-onnx 1.13.8 (vendored AAR, `:core:speech`), Robolectric 4.17, JUnit 4 + Truth. Voice packaging: a shell script on the Mac (`tools/voices/`), nothing new in the APK beyond the AAR already there.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, project 3, after V0 (Done 2026-10-21). "Carried forward from V0" lists what this plan must honour: the screen-off acceptance run, object-expression callbacks, two threads, the +420 MB budget, pronunciation overrides, one shared `espeak-ng-data`, the `lessac` licence finding.

## What the code looks like now (surveyed 2026-10-21, `main` at `87235ed`)

- **`:core:speech`** (`core/speech/`): `SherpaVoice` (detects Piper and Kokoro directories), `SherpaTts(voice, threads)` with `synthesize(text, speaker, speed): Flow<FloatArray>` (one chunk per sentence, `maxNumSentences = 1`), the trimmed AAR, `NOTICE.md`. `debugImplementation` in `app/build.gradle.kts`. The spike's `Sentences` (ICU `BreakIterator`), `Wav`, `SystemSpikeEngine` (`TextToSpeech` + `synthesizeToFile` + `onAudioAvailable`), `PcmPlayer` and `VoiceSpikeActivity` live in `app/src/debug/kotlin/app/granger/voice/`, with tests in `app/src/testDebug/`.
- **Player** (`core/player/src/main/kotlin/app/granger/core/player/`): `PlaybackService : MediaLibraryService` builds ExoPlayer, `PlayerEngine` (a `PlayerHub`; private `Mode { NONE, MUSIC, BOOK }`; every call through `submit {}`), and a `MediaLibrarySession` over `SessionPlayer : ForwardingPlayer`, which routes play/pause/next/previous/seekBack/seekForward to the engine. No custom notification provider: Media3's default notification from the session. `NowPlaying { Idle, Music, Book }` and `PlayerUi` in `PlayerHub.kt`; `PlayerMode { MUSIC, AUDIOBOOK }` drives the full player's tabs. `FocusPolicy` is the one owner of focus (its KDoc reserves the second stream for project 3); `PlayerEngine.attributes(speech)`; headset unplug is ExoPlayer's `handleAudioBecomingNoisy`. Saved place: `audiobook_states`/`music_state` through `PlaybackDao`, `PositionSaver` (books every 5 s and on events), `PAUSED_AT`/`LAST_MODE`/`LAST_AUDIOBOOK` prefs, `restore()` loads paused. `MiniPlayer(ui, onPlayPause, onSecondary, onOpen)` chooses its secondary icon by `now is NowPlaying.Book`; `PlayerViewModel.secondary()`, `modeOf`, `playerTab`, `PlayerScreen`'s `tabTitle`/`shelfLabel`/`topLabel` and `PlayerWidget` are exhaustive `when`s over `NowPlaying`/`PlayerMode`. The sleep timer is `SleepTimer` in the engine with a 10 s volume fade. `RecentPlay` rows feed the rail's `RailEntry.Play`.
- **Web reader** (`app/src/main/kotlin/app/granger/reader/`): `ReaderPageScript.SOURCE` is the chrome-toggle tap script, injected by `ZimWebViewClient.onPageFinished`; the only bridge is `GrangerBridge.toggleChrome()`; Kotlin→JS is `evaluateJavascript` strings. `ReaderWebView` owns the selection `ActionMode` (`SelectionAction { SEARCH, RESEARCH }`, menu ids from `0x6772`) and returns the selected text only. The ⋯ menu is in `ReaderScreen.kt`'s `ReaderTopBar` (Random article, Find in page). `ZimUrl(packId, path, fragment)`; a fragment in the start URL jumps natively. `HtmlPassages` (`core/research`) holds the noise, block, nesting and skipped-section selectors as private constants. No JS test harness; the page script is tested on a device only (`ReaderWebViewTest`, androidTest).
- **E-book reader** (`app/src/main/kotlin/app/granger/ebook/`): `NavigatorHost` hosts `EpubNavigatorFragment`; `EbookReaderViewModel` saves the `Locator` (1 s debounce) and exposes `navigation: Flow<Locator>` (collected into `fragment.go(it, animated = false)`), `decorations` (applied under `HIGHLIGHT_GROUP = "highlights"`; `SEARCH_GROUP = "search"` for the search hit), `toc: List<TocRow>`, `goTo(href)`. `SelectionMenu : ActionMode.Callback` with `SelectionAction { COPY, HIGHLIGHT, NOTE, DEFINE, SEARCH, RESEARCH }` and `SelectionBridge`; the screen resolves `fragment.currentSelection()` to a locator. `EbookBottomBar` has the reserved slot comment "The read-aloud button arrives with Features.READ_ALOUD." `Features.READ_ALOUD = false`. Nothing uses `publication.content()` or `firstVisibleElementLocator()` yet.
- **Catalog** (`core/catalog`): `Catalog(formatVersion, generatedAt, packs, models, books, presets)`; `CatalogItem` is single-file (`id, title, sizeBytes, sha256, urls, license`); `CatalogParser` decodes arrays entry by entry (unknown keys ignored, bad entries dropped), checks `SAFE_NAME`, 64-hex sha256, `https://`; `PublishedCatalogTest` parses the committed `catalog/catalog.json` with nothing dropped. The generator `tools/catalog-gen/catalog_gen.py` copies `curated.json`'s `models` verbatim and signs with `~/.config/granger/catalog-signing.key` (Neal's).
- **Downloads** (`core/downloads`): `ItemKind { PACK, MODEL, BOOK, AUDIO }`; `DownloadManager.specOf` maps kinds to `packs/`, `models/`, `books/granger/`; `DownloadRunner.install` renames `.part`, marks installed, calls `onInstalled(record)` (`GrangerApp.kt:346`, a `when` over kind). Groups exist for media only. No unzip anywhere. `IntegrityChecker.quick` (size) and `full` (re-hash) work on the record's one file. `Volume` has `modelsDir`, `packsDir`, `booksDir`, `musicDir`, `audiobooksDir`.
- **Settings** (`app/.../settings/SettingsScreen.kt`): a `LazyColumn` over `LEADING_KEYS = models, readiness, appearance, storage, wifi, archive`, then the media section and About. "Models" opens `SourceRoute(CatalogScope.MODELS)` (the generic catalog screen with Get/Remove and `ItemStatus`, `DeviceTier.canRun(role)`). Prefs are `SharedPreferences` classes (`AppearancePrefs`, `DownloadPrefs`, the player prefs). Readiness (`ReadinessViewModel`) lists installed records as `CheckRow`s under "AI models" (kind MODEL), Packs, "Music & audiobooks" (AUDIO groups).
- **Library DB** is v10 (`MIGRATION_9_10`), schemas exported. 1652 JVM tests pass.

## Decisions

Neal's, from the spec (2026-10-21) and the spike (confirmed 2026-10-21):
1. **Engines:** the system voice is the fallback, the downloaded Piper voice is the product; Kokoro is dropped. `ljspeech` is the catalog voice; `libritts_r`, `arctic`, `ryan`, `amy`, `hfc_*` do not ship (fine-tuned from the non-commercial `lessac`). Two threads. Look-ahead one to two sentences.
2. **Read aloud** sits in the web reader's ⋯ menu and the e-book bottom bar's reserved slot; "Read from here" in both selection menus. Transport controls drive the voice; the full view is a sheet (V2).

Builder's defaults for V1 (change them only with a note in the plan):
3. **The voice is a third mode of the one player, carried by a silent ExoPlayer item.** While the voice reads, `PlayerEngine` plays a `SilenceMediaSource` item whose `MediaMetadata` is the voice's ("Reading · Hamlet", "Act I"), so the session, the default notification, the lock screen, headset buttons, focus and the sleep timer behave as for an audiobook; the speech itself goes to a separate `AudioTrack` (`SpeechOutput`) that follows ExoPlayer's `playWhenReady`. The alternative, a `SimpleBasePlayer` facade that re-expresses ExoPlayer's state, touches Android Auto browse and resumption and is not worth the risk now. V2's mix (music in ExoPlayer, voice on the `AudioTrack`) will route transport to the voice in `SessionPlayer` and may add a notification provider; nothing in V1 blocks that.
4. **One zip per voice, hosted on Granger's GitHub release, unpacked after verification, zip deleted.** `tools/voices/pack-voice.sh` turns sherpa-onnx's `vits-piper-en_US-ljspeech-medium.tar.bz2` into `vits-piper-en_US-ljspeech-medium.zip` (model, `tokens.txt`, `espeak-ng-data/`, `MODEL_CARD`, `LICENSE`, and a `manifest.json` of names, sizes and sha256s). A voice is then one `CatalogItem` (single file, single sha256), and `ItemKind.VOICE` reuses the ledger as is. Readiness checks the unpacked files against the manifest.
5. **Pronunciations are respellings applied before Piper**, from a built-in table (`Rosetta → Rozetta` first), case-preserving, whole words; the system engine gets the original text. The espeak-ng extra-dictionary route (recompiling `en_dict` into the shipped data) is carried forward, not built.
6. **The sentence highlight in articles uses the CSS Custom Highlight API** (`CSS.highlights`, a `Range` over the sentence's text nodes), with a block-level class as the fallback where the WebView lacks it. No DOM is rewritten.
7. **A long press on a paragraph moves the voice there** while reading (the middle tap stays the chrome toggle).
8. **Music pauses when the voice starts**, and the voice pauses when music or an audiobook starts; nothing mixes until V2.
9. **`Features.READ_ALOUD`** becomes `BuildConfig.DEBUG` in Task 1 and `true` in Task 10 after the walkthrough passes.

## Global Constraints

- **The screen-off run is the acceptance test** (carried from V0): the three spike passages read through `PlaybackService` with the screen off for five minutes, real-time factor ≤ 0.5 per sentence and no stall; measured in Task 9 before anything is called done. If the foreground service doesn't lift the little-core restriction, the look-ahead grows to a paragraph (`ReadAloudEngine.lookAheadMs` is a parameter for that reason).
- **sherpa-onnx callbacks are object expressions**, never lambdas (V0: `NoSuchMethodError` → `SIGABRT`).
- **Memory:** a Piper voice costs about +420 MB while speaking (ONNX Runtime's arena); the engine is closed two minutes after the voice stops (`ReadAloudEngine.idleCloseMs`). No device-tier gate on voices; the combination with a resident E2B was measured at 2.3 GB on the Pixel 6.
- **Nothing network-bound at speech time**, in either engine. A system voice with `isNetworkConnectionRequired` is never listed.
- **Reading the junk is a failure:** the article script shares `HtmlPassages`' noise, block, nesting and skipped-section rules (made `internal`/public in `ArticleText`), so Research and the voice agree on what an article's text is.
- **Don't lose the place:** saves at every pause, stop and sentence change (debounced 1 s); restore paused after process death or reboot if the source still exists; a missing source clears the saved voice state with one notice.
- **Words in the UI:** "Read aloud", "Read from here", "Reading", "Follow", "Voice", "Granger voice", "System voice", "Mark the sentence being read", Settings → "Voice".
- **No regressions** in the media parity rows that touch focus, the mini-player and the notification (`docs/superpowers/plans/media-parity-checklist.md`); `PlayerEngineTest` stays green.
- JVM tests: `./gradlew testDebugUnitTest` and `./gradlew :core:research:test` pass (1652 before this plan). No `connectedDebugAndroidTest` without Neal's go-ahead (it wipes the library).
- Device checks on the Pixel 6 over Wi-Fi, installed over the app with both databases backed up first (v10 → v11 on first open); never while a personal app is in front.
- In a new agent worktree: copy `local.properties`, `git submodule update --init --recursive`, and build with `-Pkotlin.compiler.execution.strategy=in-process`.

## Review Focus

- `ReadAloudEngine`'s queue: cancellation on stop (no audio after Stop), pause at a sentence start, the look-ahead cap, the idle close, and what happens when synthesis fails mid-passage (skip the sentence, log, continue).
- The carrier item: focus requested as speech; the silent item never reaches the audiobook or music code paths (`isMusic()`, `PositionSaver`, `RecentPlay`, resumption); `restore()` never auto-plays the voice.
- Library v11 migration against the exported v10 schema; `voice_states` rows for sources that no longer exist.
- The article script against real pages: Wikipedia (navboxes, references, infobox tables, `sup.reference`), DevDocs (`pre` skipped with "Code omitted"), Stack Exchange (answers as blocks), and a page re-rendered after process death relocating its block by text prefix.
- Readium: the content iterator on a Classics EPUB with footnotes; decorations removed when the voice stops; `go(locator)` not fighting the user's page turns (Follow).
- The zip unpack: zip-slip guard, partial unpack cleanup, the manifest check, and `remove` deleting the directory.

---

### Task 1: `:core:speech` — the `Speech` interface and both engines

**Files:**
- Modify: `core/speech/build.gradle.kts` (add `kotlinx.coroutines.android`, `androidx.annotation`; `testImplementation` Robolectric for ICU and `TextToSpeech` shadows), `app/build.gradle.kts` (`debugImplementation` → `implementation(project(":core:speech"))`), `core/player/build.gradle.kts` (`implementation(project(":core:speech"))`), `app/src/main/kotlin/app/granger/Features.kt` (`READ_ALOUD = BuildConfig.DEBUG`)
- Move from `app/src/debug/kotlin/app/granger/voice/` into `core/speech/src/main/kotlin/app/granger/core/speech/`: `Sentences.kt`, `Wav.kt` (`Pcm`, `Wav.read`; `Wav.write` stays with the spike), the system-engine code of `SpikeEngines.kt` (as `SystemSpeech`)
- Create: `core/speech/src/main/kotlin/app/granger/core/speech/Speech.kt`, `SystemSpeech.kt`, `SherpaSpeech.kt`, `InstalledVoices.kt`, `Pronunciations.kt`
- Move tests: `SentencesTest`, `WavTest` into `core/speech/src/test/`; create `PronunciationsTest`, `InstalledVoicesTest`, `SystemSpeechTest` (Robolectric `ShadowTextToSpeech`)
- Modify the spike (`VoiceSpikeActivity`, `SpikeEngines.kt`) to use the moved classes; it keeps working.

**Interfaces:**
```kotlin
sealed interface VoiceId { data class System(val engine: String, val name: String) : VoiceId; data class Granger(val dir: String) : VoiceId }
data class Voice(val id: VoiceId, val title: String, val language: String, val offline: Boolean = true, val speakers: Int = 1)

interface Speech : AutoCloseable {
    val voice: Voice
    val sampleRate: Int
    /** Mono float samples in [-1, 1], in chunks as the engine produces them; one sentence of [text] per call is the contract. Cancel to stop. */
    fun synthesize(text: String, speed: Float = 1f): Flow<Pcm>
}
class Pcm(val samples: FloatArray, val sampleRate: Int) { val durationMs: Long }

interface SpeechFactory { suspend fun open(voice: Voice): Speech }       // SystemSpeech.Factory(context), SherpaSpeech.Factory(threads = 2)

class InstalledVoices(context: Context, voicesDirs: () -> List<File>, io: CoroutineDispatcher) {
    val voices: StateFlow<List<Voice>>        // Granger voices (SherpaVoice.list over every volume's voices/) first, then the system engine's offline English voices
    suspend fun refresh()
    fun factoryFor(id: VoiceId): SpeechFactory
}

object Pronunciations {
    val builtIn: Map<String, String>          // "Rosetta" to "Rozetta", …
    fun apply(text: String, table: Map<String, String> = builtIn): String   // whole words, case-preserving, Piper only
}
```

- [ ] **Step 1: Failing tests:** `pronunciationsReplaceWholeWordsAndKeepCase` ("Rosetta Stone" → "Rozetta Stone", "rosetta" → "rozetta", "Rosettas" untouched), `pronunciationsLeaveTextWithoutMatchesIdentical`, `installedVoicesListsGrangerVoicesBeforeSystemOnes`, `installedVoicesSkipsNetworkOnlySystemVoices`, `systemSpeechSynthesizesOneSentenceToPcm` (Robolectric shadow: a 24 kHz WAV written by the shadow is read back), `sherpaSpeechPassesAnObjectCallback` (a reflection test: the class of the callback passed to `generateWithCallback` declares `invoke([F)Ljava/lang/Integer;`; the AAR's `OfflineTts` is not constructed), plus the moved `SentencesTest` and `WavTest`.
- [ ] **Step 2:** Run `./gradlew :core:speech:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement.** `SystemSpeech`: `TextToSpeech` per factory (shared, init once), `setVoice` by name, `synthesizeToFile` into `context.cacheDir/speech/`, `Wav.read`, delete; errors become `SpeechException(code)`. `SherpaSpeech`: wraps `SherpaTts(voice, threads = 2)`, applies `Pronunciations.apply` before synthesis, speaker 0. `InstalledVoices`: `SherpaVoice.list` over `volumesRoots.map { File(it, "voices") }`, plus `TextToSpeech.voices` filtered to offline English; `refresh()` after a download or removal.
- [ ] **Step 4:** `./gradlew :core:speech:testDebugUnitTest :app:testDebugUnitTest`. Expected: PASS (the spike's tests moved, still green).
- [ ] **Step 5: Commit.** `Speech: the Speech interface, system and sherpa engines, installed voices, pronunciations`

### Task 2: Utterances and read sources

**Files:**
- Create: `core/speech/src/main/kotlin/app/granger/core/speech/Utterance.kt`, `ReadSource.kt`
- Test: `core/speech/src/test/.../UtterancesTest.kt`

**Interfaces:**
```kotlin
sealed interface ReadAddress {
    val sentence: Int
    data class Article(val packId: String, val path: String, val blockId: String, override val sentence: Int) : ReadAddress     // "pack:<id>/<path>#<blockId>:<n>"
    data class Book(val bookId: String, val locatorJson: String, override val sentence: Int) : ReadAddress                     // "book:<id>@<locator>:<n>"
    fun encode(): String; companion object { fun decode(s: String): ReadAddress? }
}
enum class UtteranceKind { HEADING, SENTENCE, CODE_OMITTED }
data class Utterance(val address: ReadAddress, val kind: UtteranceKind, val text: String, val paragraph: Int, val pauseAfterMs: Long)
// pauses: 350 ms after a sentence, 700 ms after a paragraph's last sentence, 900 ms after a heading

/** A readable thing. Sources are lazy: the reader asks for more as the voice approaches the end of what it has. */
interface ReadSource {
    val key: String                 // "pack:<id>/<path>" or "book:<id>"
    val title: String
    suspend fun sectionTitle(at: ReadAddress): String?
    suspend fun utterances(from: ReadAddress?, limit: Int): List<Utterance>   // from == null: the start; empty list == the end
    suspend fun locate(address: ReadAddress): ReadAddress?                     // re-finds a saved address after re-render (articles by block id, then text prefix)
}

object Utterances {
    fun fromBlocks(blocks: List<Block>, packId: String, path: String): List<Utterance>   // Block(id, kind: H|P|LI|PRE, text) → Sentences.split + kinds + pauses
}
```

- [ ] **Step 1: Failing tests:** `addressesRoundTripThroughEncode`, `blocksBecomeHeadingAndSentenceUtterancesWithPauses`, `preBlocksBecomeOneCodeOmittedUtterance`, `emptyAndPunctuationOnlyBlocksAreDropped`, `paragraphIndexAdvancesPerBlock`.
- [ ] **Step 2:** Run `./gradlew :core:speech:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** The same command. Expected: PASS.
- [ ] **Step 5: Commit.** `Speech: utterances, read addresses and the ReadSource contract`

### Task 3: `ReadAloudEngine` and the speech output

**Files:**
- Create: `core/player/src/main/kotlin/app/granger/core/player/voice/SpeechOutput.kt`, `ReadAloudEngine.kt`, `VoiceState.kt`
- Test: `core/player/src/test/.../voice/ReadAloudEngineTest.kt` (a `FakeSpeech` that yields PCM of a given length per sentence after a given delay; a `FakeSpeechOutput` that records writes and reports its own clock), `SpeechOutputTest.kt` (Robolectric `AudioTrack` shadow: attributes, sample rate, write counts)

**Interfaces:**
```kotlin
interface SpeechOutput : AutoCloseable {          // the second output: one AudioTrack, USAGE_MEDIA + CONTENT_TYPE_SPEECH, streaming mode
    fun open(sampleRate: Int)
    suspend fun write(pcm: Pcm)                    // blocks while the track's buffer is full; suspends cooperatively
    fun play(); fun pause(); fun flush()
    var volume: Float                              // the sleep timer's fade and V2's mix use this
    val playedMs: Long
}

data class VoiceProgress(val address: ReadAddress, val paragraph: Int, val text: String, val index: Int, val total: Int?)

class ReadAloudEngine(
    private val output: SpeechOutput, private val scope: CoroutineScope, private val clock: MonoClock,
    val lookAheadSentences: Int = 2, val lookAheadMs: Long = 30_000, val idleCloseMs: Long = 120_000,
) {
    val progress: StateFlow<VoiceProgress?>; val playing: StateFlow<Boolean>; val ended: SharedFlow<Unit>; val errors: SharedFlow<SpeechException>
    suspend fun start(source: ReadSource, speech: Speech, from: ReadAddress?, speed: Float)
    fun play(); fun pause()                       // pause stops the output now and rewinds to the start of the current sentence
    suspend fun nextParagraph(); suspend fun previousParagraph(); suspend fun previousSentence()
    suspend fun jumpTo(address: ReadAddress)
    fun setSpeed(speed: Float)                     // takes effect from the next sentence synthesized
    suspend fun stop()                             // cancels synthesis, flushes the output, closes the engine after idleCloseMs
}
```

- [ ] **Step 1: Failing tests:** `startSpeaksSentencesInOrderWithPausesBetween`, `lookAheadStaysWithinTwoSentencesAndThirtySeconds`, `pauseRewindsToTheCurrentSentenceStart`, `nextParagraphSkipsTheRestOfThisOne`, `previousSentenceAtTheFirstSentenceStaysThere`, `stopProducesNoFurtherWritesAndClosesSpeechAfterIdle`, `aSentenceThatFailsIsSkippedAndReported`, `theSourceIsAskedForMoreBeforeTheQueueRunsDry`, `endedFiresAfterTheLastSentence`, `speedChangeAppliesFromTheNextSentence`.
- [ ] **Step 2:** `./gradlew :core:player:testDebugUnitTest --tests 'app.granger.core.player.voice.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** A producer coroutine synthesizes ahead into a bounded channel of `(Utterance, Pcm)`; a consumer writes to the output and publishes `progress` as each sentence starts; pauses are silence written to the track (so `playedMs` stays truthful), not sleeps. `AudioTrackSpeechOutput` uses `AudioTrack.Builder` with `MODE_STREAM`, float PCM, a 1 s buffer, `write(..., WRITE_BLOCKING)` on `Dispatchers.IO`.
- [ ] **Step 4:** The same command, then all of `:core:player:testDebugUnitTest`. Expected: PASS.
- [ ] **Step 5: Commit.** `Player: ReadAloudEngine and the speech output (second AudioTrack)`

### Task 4: The voice as a player mode — carrier item, focus, saved place, library v11

**Files:**
- Modify: `core/player/.../PlayerHub.kt` (`NowPlaying.Voice`, `PlayerHub.readAloud(...)`, `SavedVoice`, `SavedModes.voice`), `PlayerEngine.kt` (`Mode.VOICE`, carrier item, focus as speech, transport routing, sleep fade through `output.volume`, `RecentPlay("voice:<key>")`), `PlaybackService.kt` (`SessionPlayer`: no seek commands in voice mode; `Dependencies.readSources: (String) -> ReadSource?` so a restored voice can find its source; `Dependencies.voices: InstalledVoices`), `SavedModesReader.kt`, `MediaControllerPlayerHub.kt`
- Modify: `core/library/.../MediaEntities.kt` (`VoiceState(sourceKey PK, address, speed, title, section, voiceId, updatedAt)`, table `voice_states`), `MediaDaos.kt` (`PlaybackDao.voiceState/upsertVoiceState/deleteVoiceState/observeVoiceStates`), `LibraryDatabase.kt` (version 11, `MIGRATION_10_11`), export the v11 schema
- Test: `core/player/src/test/.../PlayerEngineVoiceTest.kt` (on `PlayerTestRig`), `core/library/src/test/.../MigrationTest.kt` (10→11), `SavedModesReaderTest` additions

**Interfaces:**
```kotlin
// NowPlaying gains:
data class Voice(val sourceKey: String, val title: String, val section: String?, val sentence: String, val paragraph: Int, val speed: Float, val voiceId: VoiceId) : NowPlaying
// PlayerHub gains:
suspend fun readAloud(source: ReadSource, from: ReadAddress?, voice: VoiceId? = null)   // null: the preferred voice from VoicePrefs
fun nextParagraph(); fun previousParagraph(); fun previousSentence(); suspend fun jumpTo(address: ReadAddress)
val voiceProgress: StateFlow<VoiceProgress?>                                           // the readers mark and follow from this
// PlayerSettingsKeys gains LAST_VOICE (the source key); MODE_VOICE = "voice"
```

- [ ] **Step 1: Failing tests:** `readAloudPausesMusicAndRequestsFocusAsSpeech`, `playPauseNextPreviousGoToTheVoiceInVoiceMode`, `theCarrierItemHasTheVoiceMetadataAndNoSeekCommands`, `startingMusicStopsTheVoiceAndSavesItsPlace`, `voiceStateIsSavedOnPauseStopAndSentenceChange`, `restoreLoadsTheVoicePausedAtItsSentence`, `restoreWithAMissingSourceClearsTheStateOnce`, `sleepTimerFadesTheVoiceAndPausesIt`, `transientFocusLossPausesTheVoiceAndGainResumesAtSentenceStart`, `migration10To11AddsVoiceStates`.
- [ ] **Step 2:** `./gradlew :core:player:testDebugUnitTest :core:library:testDebugUnitTest`. Expected: compile failure.
- [ ] **Step 3: Implement.** `MIGRATION_10_11`: `CREATE TABLE voice_states (sourceKey TEXT PRIMARY KEY NOT NULL, address TEXT NOT NULL, speed REAL NOT NULL, title TEXT NOT NULL, section TEXT, voiceId TEXT NOT NULL, updatedAt INTEGER NOT NULL)`. In the engine: `startVoice()` stops the current mode, requests focus with `speech = true`, sets the carrier (`SilenceMediaSource(1h)`, `MediaMetadata` title "Reading · <title>", artist = section, artwork = the book's cover or the pack icon), starts `ReadAloudEngine`; ExoPlayer's `onPlayWhenReadyChanged` drives `readAloud.play()/pause()`; `onEvents` from the voice (sentence change) updates the carrier's metadata section; `PositionSaver` events map to `VoiceState` writes. `SessionPlayer.getAvailableCommands()` drops the seek commands while `engine.ui.value.now is NowPlaying.Voice`.
- [ ] **Step 4:** The same command, then `testDebugUnitTest`. Expected: PASS, including every existing `PlayerEngineTest`.
- [ ] **Step 5: Commit.** `Player: the voice as a third mode — carrier item, focus, saved place; library v11`

### Task 5: Mini-player, widget and player view model for the voice

**Files:**
- Modify: `app/.../player/PlayerViewModel.kt` (`secondary()`: Voice → `previousSentence()`; `modeOf(Voice) = null` so the full player keeps its two tabs; `playerTab` unchanged), `PlayerParts.kt` (`nowInfo(Voice)`: "Reading" over the title and section, a page icon or the book cover), `MiniPlayer.kt` (secondary icon for Voice: "back one sentence"; `onOpen` for Voice opens the reader at the sentence, not the player), `GrangerNavHost.kt` (`nav.togglePlayer()` → `nav.openReading(address)` when Voice), `widget/PlayerWidget.kt`
- Test: `MiniPlayerTest` (+ voice rows), `PlayerViewModelTest` (+ `secondaryOnVoiceStepsBackASentence`, `openOnVoiceGoesToTheReader`), `PlayerWidgetTest`

- [ ] **Step 1: Failing tests** as named, plus `miniPlayerShowsReadingOverTitleAndSection`.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.player.*'`. Expected: compile failure (the exhaustive `when`s).
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** The same command. Expected: PASS.
- [ ] **Step 5: Commit.** `Player UI: the voice in the mini-player and the widget`

### Task 6: Web reader — the read-aloud page script, Read aloud, Read from here, Follow

**Files:**
- Modify: `core/research/.../HtmlPassages.kt` (the selectors move to a public `object ArticleText { NOISE, BLOCKS, NESTING, HEADINGS, SKIPPED_SECTIONS }` in the same module; `HtmlPassages` uses it), `app/.../reader/ReaderPageScript.kt` (`GrangerBridge` gains `onBlocks(json)`, `onUserScroll()`, `onLongPress(blockId, offset)`; `READ_SOURCE` script string, injected with `ArticleText`'s selectors), `ReaderWebView.kt` (`SelectionAction.READ_ALOUD`; the selection handler also returns the anchor block id and offset through JS), `ReaderViewModel.kt` (`readAloud()`, `readFrom(blockId, offset)`, `follow()`, `State.reading: VoiceProgress?`, `State.following: Boolean`), `ReaderScreen.kt` (⋯ "Read aloud" / "Stop reading"; the Follow chip; wiring to `PlayerViewModel`), `ZimWebViewClient.kt` (a page load while reading this tab stops the voice)
- Create: `app/.../reader/ArticleReadSource.kt` (`ReadSource` over a tab: asks the page for its blocks through the bridge once, builds `Utterances.fromBlocks`, `locate` by block id then by the first 40 characters)
- Test: `ReaderViewModelTest` (+ `readAloudStartsAtTheFirstVisibleBlock`, `readFromStartsAtTheSelectionsSentence`, `scrollingAwayStopsFollowingUntilFollowIsTapped`, `aPageLoadStopsTheVoice`), `ArticleReadSourceTest` (blocks from a Wikipedia fixture: references and navboxes absent, `pre` → "Code omitted", relocation by text prefix), `ReaderTopBarTest` (+ the menu item)

**The script (`READ_SOURCE`):** `window.__grangerRead = { blocks(), firstVisibleBlock(), mark(blockId, start, end, scroll), clear(), blockAtSelection() }`. `blocks()` walks `BLOCKS` in document order, skipping `NOISE` subtrees, blocks nested in `NESTING` ancestors, and `SKIPPED_SECTIONS` (heading text match until a heading of the same or higher level), assigns `data-gid` = the element's id or `g<n>`, and returns `[{id, kind, text}]` with whitespace folded. `mark()` builds a `Range` over the block's text nodes from character `start` to `end` and sets `CSS.highlights.set("granger-reading", new Highlight(range))` (style injected once: `::highlight(granger-reading) { background: rgba(232,96,28,.28) }`); without `CSS.highlights`, it toggles a `granger-reading` class on the block. When `scroll` is true and the range's rect is outside the viewport, `scrollIntoView({block: "center"})`, flagging the next scroll event as the script's own so `onUserScroll` isn't sent for it. A `contextmenu`/long-press on a block while reading sends `onLongPress(id, offset)`.

- [ ] **Step 1: Failing tests** as named.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.reader.*' :core:research:test`. Expected: compile failure.
- [ ] **Step 3: Implement.** Kotlin splits each block into sentences and keeps character offsets so `mark` can be given the sentence's range. The reader collects `player.voiceProgress` for its own source key and calls `mark` (with `scroll = following`). "Read aloud" when a voice is already reading this page shows "Stop reading".
- [ ] **Step 4:** The same command. Expected: PASS.
- [ ] **Step 5: Commit.** `Web reader: read aloud — the page script, Read from here, the sentence highlight and Follow`

### Task 7: E-book reader — Readium content, decorations, the bottom-bar button

**Files:**
- Create: `app/.../ebook/BookReadSource.kt` (`ReadSource` over an open `Publication`: `publication.content(start)` iterated element by element, `DefaultTextContentTokenizer(TextUnit.Sentence, Locale.ENGLISH)` for sentences, headings from `Role.Heading`, `Role.Footnote` skipped, each sentence's `Locator` (with `text.highlight`) serialized as the address; `sectionTitle` from the TOC row for the href)
- Modify: `EbookReaderViewModel.kt` (`readAloud()` from `firstVisibleElementLocator()`, `readFrom(locator)`, `follow()`, `EbookUi.reading`, `EbookUi.following`; voice decorations as `VOICE_GROUP = "voice"` with `Decoration.Style.Highlight(tint = READING_TINT)`), `EbookReaderScreen.kt` (apply the voice decoration group; `fragment.go(locator)` when the sentence's locator is on another page and `following`; the Follow chip; a user page turn while reading sets `following = false`), `ReaderControls.kt` (the reserved slot becomes the Read aloud / Stop reading button under `Features.READ_ALOUD`), `SelectionMenu.kt` (`SelectionAction.READ_ALOUD`, "Read from here"), `BookAnnotations.kt` (`VOICE_GROUP`, `READING_TINT`)
- Test: `EbookReaderViewModelTest` (+ `readAloudStartsAtTheFirstVisibleElement`, `readFromStartsAtTheSelection`, `voiceDecorationFollowsTheSentence`, `aUserPageTurnStopsFollowing`), `BookReadSourceTest` (a small EPUB fixture with a footnote and two headings: utterances in order, footnote absent, pauses after headings, locators round-trip), `SelectionMenuTest` (+ the item)

- [ ] **Step 1: Failing tests** as named.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.ebook.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** The content iterator is `@ExperimentalReadiumApi`; opt in at the file. `utterances(from, limit)` resumes the iterator from the address's locator; `locate` returns the locator as is (Readium locators survive re-open).
- [ ] **Step 4:** The same command. Expected: PASS.
- [ ] **Step 5: Commit.** `E-book reader: read aloud through Readium's content iterator, the voice decoration, Read from here`

### Task 8: Voices in the catalog, the download, Settings → Voice, Readiness

**Files:**
- Create: `tools/voices/pack-voice.sh` (downloads sherpa-onnx's `vits-piper-en_US-ljspeech-medium.tar.bz2` by pinned URL and sha256, repacks it as `vits-piper-en_US-ljspeech-medium.zip` with `manifest.json`, prints size and sha256 for `curated.json`), `tools/voices/README.md` (where the zip is hosted: a `voices-v1` GitHub release on `nealt9000/Granger`; how to re-sign the catalog)
- Modify: `tools/catalog-gen/curated.json` (`"voices": [ { id, title, engine: "piper", language: "en-US", speakers: 1, sampleRate: 22050, sizeBytes, sha256, url, license: "Public domain (LJ Speech)", attribution } ]`), `catalog_gen.py` (copies `voices` like `models`; `SAFE_NAME` check), `test_catalog_gen.py`
- Modify: `core/catalog/.../Catalog.kt` (`VoiceEntry : CatalogItem` with `engine, language, speakers, sampleRate, attribution`; `Catalog.voices`, in `items`), `CatalogParser.kt` (the `voices` array, optional so old catalogs parse), `core/downloads/.../DownloadRecord.kt` (`ItemKind.VOICE`), `Downloads.kt`/`DownloadManager.kt` (`specOf(VoiceEntry)` → `voices/<id>.zip`), `Volumes.kt` (`voicesDir`), `IntegrityChecker.kt` (`VOICE`: `quick` = the manifest's files exist with their sizes; `full` = re-hash them)
- Create: `core/downloads/.../VoiceUnpacker.kt` (`unpack(zip, into: File): Result` with the zip-slip guard, a `.part` directory renamed on success, `manifest.json` read back; deletes the zip)
- Modify: `app/.../GrangerApp.kt` (`onInstalled` for `VOICE` → `VoiceUnpacker` then `installedVoices.refresh()`; `InstalledVoices` and `VoicePrefs` built here and handed to `PlaybackService.Dependencies`), `sources/CatalogScope.kt` (+ `VOICES`), `SourceViewModel.kt` (voices listed with licence and attribution; Remove deletes the directory and the record)
- Create: `app/.../settings/VoiceSettingsScreen.kt`, `VoiceSettingsViewModel.kt`, `VoicePrefs.kt` (`SharedVoicePrefs`: `voiceId`, `speed` default 1.0, `markSentence` default true), `VoiceRoute`
- Modify: `settings/SettingsScreen.kt` (`LEADING_KEYS` + `"voice"` after `"models"`: "Voice · Granger voice (ljspeech)" / "System voice" / "No offline voice"), `GrangerNavHost.kt` (the route), `readiness/ReadinessViewModel.kt` + `ReadinessScreen.kt` ("Voices" rows under AI models, `isModel`-like grouping by kind VOICE, verify and repair)
- Test: `CatalogParserTest` (+ `voicesParseAndOldCatalogsWithoutThemStillDo`, `aVoiceWithABadUrlIsDropped`), `PublishedCatalogTest` (unchanged; re-run after the regenerated catalog is committed), `DownloadManagerTest` (+ `voiceSpecGoesToVoicesDir`), `VoiceUnpackerTest` (+ zip-slip, partial failure cleanup, manifest), `IntegrityCheckerTest` (+ voice quick/full), `VoiceSettingsViewModelTest`, `ReadinessViewModelTest` (+ a voice row), `SettingsScreen` row test

- [x] **Step 1:** Run `tools/voices/pack_voice.py` (Python, deterministic zip); record size and sha256 in `curated.json`. *Done 2026-10-22: `vits-piper-en_US-ljspeech-medium.zip`, 67,456,670 B, sha256 `3013e3af7bd4c3d35188a93b8a3ef4ebf26e9946a54292e10f3f961ecc0ac23b`, 81,529,587 B unpacked, at `/Users/s.nealthompson/Developer/Granger/voice-spike-2026-10-21/voices-zip/`. The entry is in `curated.json`.* **Open for Neal:** upload the zip as an asset of the `voices-v1` release on `nealt9000/Granger`.
- [x] **Step 2: Failing tests** as named (parser voices, voice spec folder, `VoiceUnpackerTest`, Readiness voice row, `VoiceSettingsViewModelTest`).
- [x] **Step 3:** compile failure seen, then fixed.
- [x] **Step 4: Implement.** `VoiceEntry` and `Catalog.voices`; `ItemKind.VOICE`, `voices/<id>.zip`, `Volume.voicesDir`; `VoiceUnpacker` (zip-slip guard, `.part` directory, manifest; the zip is deleted); `IntegrityChecker` checks a voice against its manifest; Settings → Voice (`VoicePrefs`, `VoiceSettingsViewModel/Screen`, the Settings row with the chosen voice as its summary); Readiness "Voices"; the container wires `InstalledVoices`, `readSources` (articles from their pack, books through the opener) and the `voice:` play resumer; the generator copies `voices` like models. **Open for Neal:** regenerate `catalog/catalog.json` with `catalog_gen.py build` and sign it (the key is Neal's); the committed catalog is unchanged until then, and the app's parser accepts a catalog without `voices`.
- [x] **Step 5:** `testDebugUnitTest` PASS (`PublishedCatalogTest` on the unchanged catalog).
- [x] **Step 6: Commit.** `Voices: catalog entry, download and unpack, Settings → Voice, Readiness rows`

### Task 9: The screen-off acceptance run

**Files:**
- Modify: `app/src/debug/kotlin/app/granger/voice/VoiceSpikeActivity.kt` (a `--ez service true` mode: reads the three spike passages as a `TextReadSource` through `PlayerHub.readAloud`, does **not** keep the screen on, logs `Voice:` lines from `voiceProgress` with the engine's per-sentence synthesis time and the output's `playedMs`), `tools/spike/voice/run-voice.sh` (a `--screen-off` flag: `input keyevent KEYCODE_SLEEP` five seconds after the start, waits for `auto done`)
- Create: `core/speech/.../TextReadSource.kt` (a `ReadSource` over plain text, used here and by V2's answers)
- Results: `docs/superpowers/spikes/2026-10-voice-spike-results.md` gains "Screen-off run (V1)" with the table

- [ ] Install over Wi-Fi (both databases backed up first; v10 → v11 on first open).
- [ ] Run `ljspeech` and the system voice through the service with the screen off for five minutes each: no stall, no notification flicker, per-sentence RTF ≤ 0.5 for Piper. Record the table.
- [ ] If Piper's RTF with the screen off exceeds 0.5: raise `lookAheadSentences` to a paragraph (`ReadAloudEngine` parameter), re-run, and record both; if that still stalls, stop and bring the numbers to Neal before Task 10.
- [ ] **Commit.** `Voice: screen-off run through the service`

### Task 10: Pixel 6 walkthrough; flag on; roadmap

- [ ] Install over the app (databases backed up first).
- [ ] Check (spec §10):
  - [ ] **Article:** Read aloud from the first visible paragraph of a Wikipedia article; the sentence is marked and the page follows; References, navboxes, infobox tables and `[n]` markers are never spoken; scroll away → Follow chip → tap → back on the sentence; a long press on a later paragraph moves the voice; following a link stops the voice; "Stop reading" works.
  - [ ] **DevDocs page:** code blocks are "Code omitted"; Stack Exchange: the question and answers read in order.
  - [ ] **E-book:** a Classics book reads from the visible page, turns pages as it goes, skips footnotes; a user page turn stops following; Read from here from a selection; after a force-stop the mini-player shows it paused at the same sentence; the same after a reboot.
  - [ ] **Player shell:** one notification throughout; the headset button pauses and resumes at the sentence start; a phone call pauses the voice and the call's end resumes it; the sleep timer fades and stops it; starting music stops the voice and saves its place; the mini-player's secondary button steps back a sentence; tapping the mini-player opens the reader at the sentence.
  - [ ] **Screen off five minutes:** reading continues (Task 9's numbers).
  - [ ] **Engines:** the system voice and the downloaded Granger voice both read; Settings → Voice shows the licence and attribution; removing the voice falls back to the system voice with a notice; a phone with no offline voice (simulate by picking none) shows "No offline voice on this phone" and the Get button.
  - [ ] **Pronunciation:** "Rosetta Stone" is said correctly by the Granger voice.
  - [ ] **No regressions:** the music and audiobook parity rows that touch focus, the mini-player and the notification.
- [ ] `Features.READ_ALOUD = true`.
- [ ] Update the roadmap: the V1 row to Done with what passed and what's open, and a "Carried forward from V1" section (for V2: the mix and transport routing in `SessionPlayer`, the full-view sheet, the rail entry, `ReadAloud.start`, the second voice's licence search, the espeak-ng dictionary route, anything the walkthrough left).
- [ ] **Commit.** `Voice V1: read-aloud in both readers through the player; roadmap`

## Order and size

Tasks 1 and 2 first (half a day; everything else imports them). Then Tasks 3–4 (the engine and the player mode, about a day and a half, the riskiest part: run `PlayerEngineTest` often) in parallel with Task 8 (catalog, download, settings, about a day; needs Neal once for the release upload and the catalog signature). Tasks 5–7 follow 4 (the readers, about a day and a half together; 6 and 7 are independent of each other). Task 9 needs the phone and Task 4; Task 10 closes. About five days of agent work plus Neal's walkthrough; the V2 plan is written once Task 10 is committed.
