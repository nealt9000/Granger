# The Voice: read-aloud and spoken answers — design

2026-10-21 · Neal Thompson (decisions) · written up by Claude

Status: draft for Neal's review. This is project 3 of the four set out in the Media spec (`2026-10-19-media-design.md` §1). Neal moved it ahead of the Home, Readiness and Onboarding work on 2026-10-21. It is built before Plans 10 (Monetization) and 11 (Beta).

## 1. Why

Granger's mission is low-friction access to knowledge when the internet is out. Reading needs eyes and hands; a voice frees both. The three target examples from the Media spec all need it before project 4 (Actions) can deliver them:

- "Read the book of Mark." Research opens the e-reader at Mark and starts reading it aloud.
- A reading plan that reads along to a playlist: the voice over music, with a mix slider.
- A Research answer spoken as it arrives, so a question can be asked and answered without looking at the phone.

This project gives the app **one voice**: one on-device speech engine behind one `Speech` interface, used by the web reader, the e-book reader and Research, played through the player shell that Media built, with no internet involved at any point.

## 2. Goals, non-goals, anti-goals

**Goals**
- Read aloud in both readers: any Wikipedia-style article and any e-book, from where you are, with the sentence being read marked on the page and the page following the voice.
- Research answers spoken, as they stream, when the user wants that.
- The voice is a **third thing the player can play**, beside music and audiobooks: it shows in the mini-player, the notification, the lock screen and on headset buttons, keeps reading with the screen off, and saves its place like an audiobook.
- **Music and voice together.** Music keeps playing under the voice at a level the user sets with a mix slider, remembered across sessions.
- Two engines behind the interface: the phone's own text-to-speech (zero download) and a bundled neural voice the user downloads from the catalog, so a phone with no usable system voice, or no Google services, still speaks.
- An app-level `ReadAloud.start(target)` call that project 4 can drive.

**Non-goals (this project)**
- Voice input (speech to text), wake words, and conversation.
- Languages beyond English (the strings and the engine interface stay language-aware; only English voices ship).
- Voice cloning, expressive styles, SSML authoring.
- Reading audiobooks (they're audio already), PDFs (the PDF pass is unscheduled), and video.
- Research deciding *what* to read (project 4). Opening at an exact passage from a citation is project 2; the voice starts wherever it is told to.
- Android Auto for read-aloud. The service work is shared, so it may come free; it is not checked.

**Anti-goals: failures even if it technically works**
- **Losing your place.** After a crash, a call, a reboot or switching to music and back, the voice resumes at the sentence it was on.
- **Reading the junk.** Navigation text, edit links, reference markers, tables of numbers, footnote lists, "[1]" citation chips and Markdown asterisks must never be spoken.
- **Gaps between sentences.** Speech is synthesized ahead so sentences flow; a pause is a chosen pause (at a heading or paragraph), not a stall.
- **Reading something other than what is on screen.** The page follows the voice; when the user scrolls away, one tap brings the view back to the voice.
- **Needing the internet.** Neither engine may fetch anything at speech time. A system voice that only works online is reported as unavailable, not used.
- **Two notifications, two players.** The voice shares the one media session, foreground service and mini-player.
- **Stopping when the screen turns off**, or when the app goes to the background.

## 3. The engines

### 3.1 One interface

```kotlin
interface Speech {
    val voices: StateFlow<List<Voice>>                 // what can speak now, offline, with language and engine
    suspend fun open(voice: Voice)                     // loads the engine; idempotent
    fun synthesize(text: String, speed: Float): Flow<Pcm> // 16-bit mono chunks as they are produced; cancellable
    val sampleRate: Int
    fun close()
}
```

Everything above this line (readers, Research, the player) speaks to `Speech` and never to an engine. The engine produces audio; **it never plays it**. Playback is the player's job (§5), so the mix, focus, the notification and the saved place are the same whichever engine is in use.

### 3.2 System voice: Android's `TextToSpeech`

- Zero download, present on most phones (Google's engine on Pixels, Samsung's on Galaxy). The app lists the engine's voices that are **installed and marked offline-capable** (`Voice.isNetworkConnectionRequired == false`, features not requiring the network) and offers only those.
- Audio is taken with `synthesizeToFile` into a pipe or temp file per sentence, not `speak()`, so the player owns playback. *Builder's call: pipe (`ParcelFileDescriptor`) against a temp file, by what Android 10–17 reliably supports.*
- Phones with no offline voice (de-Googled phones, the F-Droid channel) show "No offline voice on this phone" with the Granger voice download beside it.
- Known limits: quality varies by phone; the Android 11+ manifest needs a `<queries>` entry for `android.intent.action.TTS_SERVICE`; word-position callbacks exist (`onRangeStart`) but aren't needed, because the app highlights by sentence and knows the sentence boundaries itself.

### 3.3 Granger voice: sherpa-onnx running a Piper voice

- **sherpa-onnx** (k2-fsa, Apache-2.0; latest release 1.13.8, 2026-09-10) publishes a prebuilt Android AAR (`sherpa-onnx-1.13.8.aar`, with a static-link variant) carrying the JNI libraries and the Kotlin API (`com.k2fsa.sherpa.onnx.OfflineTts`). It is not on Maven Central; the AAR is vendored into a new `:core:speech` module, trimmed to the ABIs the app already builds (`arm64-v8a`, `x86_64`, as `:core:taglib`). `OfflineTts.generateWithCallback` hands out audio in chunks as they're produced, which is what streaming sentence playback needs. CPU only (`provider = "cpu"`); no GPU or NNAPI, so it never competes with LiteRT-LM for the GPU.
- **Piper voices** (MIT engine; VITS models, 22,050 Hz, about 60–80 MB each with `espeak-ng-data`). Voice licenses are per voice and were checked on 2026-10-21 against their model cards:
  - `en_US-ljspeech-medium`: **public domain**, one female speaker. The default candidate.
  - `en_US-libritts_r-medium`: its dataset is **CC BY 4.0** and it has 904 speakers in one file, but its model card says it was **fine-tuned from `lessac`**, whose data is licensed for non-commercial use only. Until that provenance is cleared it is measured in the spike and **not shipped**; Most of Piper's other English voices (`arctic`, `ryan`, `amy`, `hfc_*`) are fine-tuned from `lessac` too, so a second clean voice is more likely Kokoro (Apache-2.0) than another Piper voice; V1 settles it.
  - `en_US-lessac` (Blizzard 2013) and `en_US-hfc_*` (CC BY-NC-SA) are **out**: research-only and non-commercial terms. `en_US-amy` points at Mimic 3's terms, unverified; out unless checked.
  - `espeak-ng-data` (the phonemizer's data) is GPL-3.0, fine for a GPL app and shipped inside the voice download, not the APK.
- **Kokoro-82M** *(V0 result, 2026-10-21: dropped for this device class; on the Pixel 6 it is 1.5–2.2× slower than real time, slower with more threads, 890 MB; see `docs/superpowers/spikes/2026-10-voice-spike-results.md`)* (Apache-2.0; sherpa-onnx builds `kokoro-multi-lang-v1_1`, with an int8 variant, and `kokoro-en-v0_19`: about 310–330 MB of model plus voices) sounds better than Piper but is heavy: sherpa-onnx's own numbers put it at a real-time factor of 2.8–7.6 on a Raspberry Pi 4, that is 3–8 s of CPU per second of speech. The Pixel 6 is a few times faster per core, which lands near real time at best. **Kokoro ships only if the spike (§12) shows it beats real time on the Pixel 6 with E2B resident, and Neal prefers it after listening.** Otherwise it is noted as a later "better voice" for stronger phones.

### 3.4 Voices in the catalog

- A new catalog array, `voices`: `id, title, engine (piper | kokoro), language, speakers, sizeBytes, files[] (name, url, sha256), license, sampleRate`. A voice is **one download group** of a few files (`model.onnx`, `tokens.txt`, `espeak-ng-data.zip`), using the ledger's `groupId` as albums do, with the zip unpacked after verification. New `ItemKind.VOICE`.
- Voices are hosted the way models are (Hugging Face, or a GitHub release asset if a file isn't stable there); the catalog pins sha256s. No gating, no sign-in.
- Voices appear in **Settings → Voice** (get, remove, pick), and in Readiness under AI models as "Voices". They don't count toward knowledge coverage.
- Default choice, in order: the Granger voice the user downloaded, else the system voice if one is offline-capable, else none, with the reasons shown. Onboarding (H3) may offer the voice with the Efficient set; that is H3's call.

## 4. What gets read

Every source becomes the same thing: an ordered list of **utterances** (sentences, with the heading or paragraph they belong to) each with an **address** the reader can show and the player can save.

### 4.1 Articles (the web reader)
- A page script (beside `ReaderPageScript`) walks the article's block elements in order (`h1–h4, p, li, dd, blockquote`), skipping what `HtmlPassages` already treats as noise (`script, style, sup.reference, .mw-editsection, .navbox, table, nav, footer`) and the sections it skips (References, Notes, External links, See also…), gives each block an id, and hands `[{id, kind, text}]` to Kotlin through the bridge. Kotlin splits blocks into sentences (ICU `BreakIterator`, English). Headings are read as their own utterance with a longer pause after them; list items as sentences; image alt text, captions and math are not read.
- The address is `pack:<id>/<path>#<blockId>` plus the sentence index. Blocks are also located by the text they start with, so a page re-rendered after process death finds its place again.
- Start points: **Read aloud** in the bar's ⋯ menu reads from the first block on screen; **Read from here** in the selection menu reads from the selection; a tap on a block while reading moves the voice there (*builder's call: tap, or long press, so it doesn't fight the chrome toggle*).
- The current sentence is marked in the page (a low-alpha flame background, a CSS class) and scrolled into the upper third when it leaves the screen. If the user scrolls away, a small **Follow** chip appears on the reader; the page stops following until it is tapped. Following a link stops the voice.
- DevDocs, Stack Exchange and other non-article packs are read the same way; code blocks (`pre`) are skipped with a spoken "code omitted" (*builder's call on the wording*).

### 4.2 E-books (the Readium reader)
- Readium's **Content service** (`publication.content(start)`, experimental API in `readium-shared` 3.4.0) iterates the book's text elements with a `Locator` per segment, and its `DefaultTextContentTokenizer(TextUnit.Sentence, language)` splits sentences with ICU. Footnotes (`Role.Footnote`) and figure captions are skipped; headings get the longer pause. The TTS navigator Readium also ships (`readium-navigator-media-tts`) was considered and set aside: its engines play their own audio and bring their own media session, and the app needs one session and the mix. The content iterator alone gives the text and the addresses.
- The address is `book:<id>@<locator>` plus the sentence index. The current sentence is marked with `applyDecorations(group = "voice")` and the navigator turns the page with `go(locator)` when the sentence is off screen; the same Follow rule as articles.
- Start points: the reserved **Read aloud** button in the e-book bottom bar (`Features.READ_ALOUD`), from the first visible element (`firstVisibleElementLocator()`); **Read from here** in the selection menu; the Contents panel's chapters ("Read the book of Mark" is this, driven from project 4).

### 4.3 Research answers
- The spoken text is the answer with Markdown and `[n]` citation markers removed (`AnswerText.segments` already separates them) and numbers left as written. While an answer **streams**, complete sentences are spoken as they close; the last fragment waits for the end. A decline is spoken as written. The medical banner is spoken once before a flagged answer. "Think deeper" and Best guess answers speak the same way.
- Each answer gets a speaker button. A setting, **Speak answers as they arrive**, turns the streaming case on; off by default (Neal, 2026-10-21).
- The address is `answer:<turnId>` plus the sentence index, so a spoken answer shows in Recent and can be replayed.

## 5. Playback: the voice in the player

- **A second output beside ExoPlayer**, in `:core:player`: `SpeechPlayer` writes PCM to an `AudioTrack` (`USAGE_MEDIA`, `CONTENT_TYPE_SPEECH`). ExoPlayer stays the one music and audiobook player. This is what lets music and the voice play at once, which Media §10 reserved for.
- **Look-ahead.** Synthesis runs at least two sentences ahead of playback and never more than about thirty seconds, so sentences flow and Stop is immediate. Both engines are driven through the same queue.
- **Transport.** Play/pause pauses and resumes at the start of the current sentence (a short overlap is fine; a cut mid-word is not). Next and previous move by paragraph in articles and e-books, and by sentence in answers (*builder's call*). Speed 0.7–2.0× through the engine (Piper's `speed`, the system engine's rate), not time-stretch. The sleep timer applies to the voice as it does to audiobooks.
- **Saved place.** As for audiobooks: written at every pause, stop and sentence change, debounced, so a crash loses at most a sentence. A voice session is restored **paused** after process death or a reboot, at its sentence, if its source is still there.
- **One session.** `PlaybackService` already owns the media session and the foreground service. The voice is a third **mode** of the engine's state beside music and audiobook (`NowPlaying.Voice`), and the notification shows "Reading · Hamlet · Act I" with play/pause, previous and next.
- **With music.** Starting the voice does **not** stop music; it ducks it to the **music-under-voice** level (default 30 %, slider 0–100 %, remembered). Starting music while the voice reads keeps the voice. An audiobook and the voice don't mix: starting one pauses the other (both are speech). While both music and the voice play, **the transport controls (headset, notification, mini-player play/pause) drive the voice**; music is controlled from its own row in the player. (Neal, 2026-10-21; pause-both was the alternative.)
- **Focus and calls.** One audio-focus request for the app (the existing `FocusPolicy`), requested as speech while the voice is active. A call pauses both; the end of the call resumes both (the voice from its sentence start). A navigation prompt ducks both. A headset unplug pauses both.
- **Memory.** Piper adds on the order of 100–200 MB while open; the engine is closed two minutes after the voice stops, as the LLMs are. The voice must work on every tier, including LIBRARY_ONLY phones, and alongside a resident E2B on the ASK tier; the spike measures both.

## 6. In the UI

- **Readers:** Read aloud in the web reader's ⋯ menu and in the e-book bottom bar's reserved slot; Read from here in both selection menus; the sentence mark and the Follow chip (§4).
- **Mini-player:** the same height and layout as for music and audiobooks: a page icon or the book's cover, "Reading" over the title and section, play/pause, and the secondary button is **back one sentence**. Tapping it opens the voice's full view. When music plays under the voice, a second thin line under the title names the music, and the mix slider lives in the full view.
- **Full view:** the current sentence in large type with the previous and next ones dimmed, previous and next paragraph, speed, voice (a sheet listing offline voices), the sleep timer, the music-under-voice slider when music is playing, and **Open** to jump to the page. A sheet over the current player, not a third Player tab (Neal, 2026-10-21).
- **Rail:** a voice session is a play in the rail's list ("Reading aloud: Hamlet"); tapping it resumes.
- **Research:** the speaker button on answers; the setting.
- **Settings → Voice:** engine and voice, Get / Remove for catalog voices with license shown, speed default, Speak answers as they arrive, music-under-voice level, "Mark the sentence being read" on/off.
- Words in the UI: "Read aloud", "Read from here", "Reading", "Follow", "Voice", "Music under the voice".

## 7. Built so later projects fit

- **Project 4 (Actions):** `ReadAloud.start(target: ReadTarget, from: Address? = null)` is a plain app-level function, as playlists were made for the same reason. Targets: an article (`pack:<id>/<path>`), a book or a chapter of it (`book:<id>` with a TOC locator), an answer. "Read the book of Mark" is `start(book, chapter("Mark"))`.
- **Project 2 (deep links):** addresses are the same shape as the deep links project 2 opens (`track:<id>@<ms>` for audio, `book:<id>@<locator>` here), so a citation can both open and be read from its passage.
- **Languages:** utterances carry a language; the voice list is filtered by it. Nothing but English is tested.
- **Other speech uses** (a spoken Readiness summary on Home, feeds read aloud) only need `ReadAloud.start` with a text target. Not built now.

## 8. Catalog, licenses, size

- sherpa-onnx: Apache-2.0. Piper: MIT. Voices: public domain (`ljspeech`), CC BY 4.0 (`libritts_r`, attribution shown in Settings and About). espeak-ng data: GPL-3.0. Kokoro: Apache-2.0, not adopted after V0. All GPL-3.0-compatible.
- APK growth: the AAR's native libraries for two ABIs (a few MB; measured in the spike). Voice data is never in the APK.
- Android 15's 16 KB page-size requirement for new Play uploads: the AAR's `.so` files are checked for 16 KB alignment in the spike; a failing library blocks adoption until a build that passes.

## 9. Constraints

- Development on the Mac Studio; device checks on the Pixel 6 over Wi-Fi. **The phone is not touched until the Research spike (R0) is finished and hands-off** (Neal, 2026-10-21); after that, never while a personal app is in front.
- No `connectedDebugAndroidTest` without Neal's go-ahead (it wipes the library). JVM tests: `./gradlew testDebugUnitTest` and `./gradlew :core:research:test` stay green (1620 now).
- minSdk 29. `:core:speech` follows `:core:taglib`'s ABI list.
- Nothing network-bound at speech time, in either engine.

## 10. Done means

On the Pixel 6:
- A Wikipedia article reads from the first visible paragraph with the sentence marked and the page following; References and navboxes are never spoken; Follow comes back after a scroll; a tap moves the voice.
- A Granger Classics book reads from the visible page, turns pages as it goes, skips footnotes, and resumes at the same sentence after a force-stop and after a reboot (paused).
- A Research answer is spoken as it streams, without Markdown or citation markers, and the medical banner is spoken once when it applies.
- Music keeps playing under the voice at the slider's level; the headset button pauses and resumes the voice; a phone call pauses both and the call's end resumes both.
- The screen off for five minutes doesn't stop the reading; one notification shows throughout.
- Both engines work: the system voice (offline) and a downloaded Piper voice; Settings shows the license; removing the voice falls back to the system one with a notice.
- No regressions in the music and audiobook walkthrough rows that touch focus, the mini-player and the notification (`media-parity-checklist.md`).

## 11. Decisions (Neal, 2026-10-21)

1. Transport controls (headset, notification, mini-player play/pause) drive the voice while music plays under it; music is controlled from its own row. Not pause-both.
2. "Speak answers as they arrive" is **off by default**.
3. The two catalog voices are `ljspeech` (public domain) and a second one. `libritts_r` was the plan, but it is fine-tuned from the non-commercial `lessac` model (found 2026-10-21 after the decision), so the second voice is chosen in V1 from voices trained from scratch on permissive data. Kokoro only on the spike's numbers and Neal's listening.
4. The voice's full view is a **sheet** over the current player, not a third Player tab.
5. Read aloud sits in the web reader's ⋯ menu.
6. **The phone is off limits until the Research spike (R0) is finished and hands-off.** No voice measurement, install or walkthrough touches the Pixel 6 before then.

## 12. Order

1. **V0, the engine spike** (`2026-10-21-plan-v0-voice-spike.md`): build the AAR into the app, measure both engines and Kokoro on the Pixel 6, listen, decide. Results in `docs/superpowers/spikes/2026-10-voice-spike-results.md`.
2. **V1, the voice**: `:core:speech`, the voice in the player, both readers, Settings → Voice, catalog voices. Written after V0 lands.
3. **V2, spoken answers and the mix**: Research speech, music under the voice, the full view, the rail entry, `ReadAloud.start`. Written after V1; may fold into V1 if V1 comes in small.
