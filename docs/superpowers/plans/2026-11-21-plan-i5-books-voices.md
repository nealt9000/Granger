# Plan I5: books, Library, read aloud and Listen on the iPhone

Spec: `docs/superpowers/specs/2026-11-20-iphone-design.md` (§6, I5). Parity list: `docs/superpowers/ios-parity.md`. Follows I4 (merged and pushed main 33acb6e2, release ios-core-2026-11-21-2).

**Goal.** The iPhone reads books the way Android does:
- the Library tab (reading shelf, collections, Classics, organize, covers);
- the e-book reader (pages, contents, search in the book, highlights and notes, dictionary, text settings, link peek, fixed layout, PDF);
- Granger Classics with reference links and In your books, archive.org, the Gutenberg shelf and importing your own books;
- reading aloud with the Piper voices and the system voices, Listen's Reading tab and Now Playing.

When I5 merges, every parity row marked I5 is ticked or carries an agreed reason.

**Shape (as I4).** I5a moves Android's book, library and read-aloud rules into the shared core with no change for the Android user. It's checked on the Pixel 6 and merges on its own. I5b builds the iPhone side and merges after the Simulator check. Two native pieces open with a one-hour spike each (Readium Swift in Task 5, sherpa-onnx in Task 8), and the plan stops to report if either fails.

## Builder's calls in this plan (await Neal)

1. **E-books in Readium's Swift toolkit, the same Readium family as Android's Kotlin 3.4.**
   - Android already stores a book's place and its highlights as Readium locator JSON in the shared store (`BookPosition.locatorJson`, `BookAnnotation.locatorJson`). Readium Swift reads the same format, so nothing is converted and a backup carries over.
   - Pages, scrolling, contents, text settings, highlights drawn as decorations, search in the book (Readium's search service), and fixed layout through Readium's FXL navigator.
   - The alternative, our own WKWebView EPUB reader, would mean rewriting what Readium already does on Android.
2. **PDFs through Readium's PDF navigator, which runs on PDFKit.** Android uses Readium's PDFium adapter. On the iPhone, PDFKit is the system's own and needs no extra library. A pack's PDF or EPUB opens in these readers in place of I4's Quick Look.
3. **Piper voices with sherpa-onnx's iOS build, the same 1.13.8 as Android's vendored AAR.**
   - A script fetches the xcframeworks (sherpa-onnx and onnxruntime) into the git-ignored `ios/Vendor` and checks their sha256, like CoreKiwix. If no prebuilt iOS archive is published for 1.13.8, the script builds one with sherpa-onnx's `build-ios.sh` once, and the spike says so.
   - Owl, Nanny and Professor come from the same signed catalog, through the shared download queue.
   - The system voices are `AVSpeechSynthesizer`, where Android uses its TextToSpeech.
4. **The read-aloud rules move to the shared core, and the sound stays native.**
   - What moves: sentences, spoken words, quote spans, pronunciations, utterances, the `ReadAloudEngine`, the sleep timer, chapters and the saved place.
   - Sentence breaks use ICU on both phones: Android's `BreakIterator`, and on the iPhone `CFStringTokenizer`, which is ICU underneath. A test runs the same paragraphs on both and compares the breaks.
   - Sound on the iPhone goes through `AVAudioEngine`. The lock screen gets `MPNowPlayingInfoCenter` and remote commands, with the background audio mode on.
5. **The Library and the e-book reader share their models.**
   - Moving to `:core:screens`: Library, Collection, the remote collection, the reading shelf, the Gutenberg shelf, import rules, archive.org, Listen, and the reader's non-Readium parts (annotations, search results, reading prefs, reference links, the dictionary lookup).
   - Moving to `:core:store`: `Bookshelf`, `Shelves`, `ReadingSessions`, `BookIndex`, `MentionIndex`, `BookLinks`, `Filing` and the Gutenberg and PDF-ebook rows.
   - `core/archive` moves from OkHttp to Ktor, as `:core:online` did in I1.
   - SwiftUI draws the screens. The Readium views are wrapped, not rebuilt.
6. **Converting books keeps its rules shared, and each phone reads the file its own way.**
   - Text PDF → e-book, text → EPUB, and comic → PDF move behind a small `PdfText` and `ComicPages` interface. Android keeps PDFBox and junrar.
   - The iPhone reads PDF text and font sizes with PDFKit, CBZ with the shared zip reader, and CBR (RAR 4) with libarchive, built for iOS by a script (BSD licence; junrar has no iPhone version).
   - The PDFKit and PDFBox text can differ slightly. The test is the same archive.org PDF turned into an EPUB on both phones, with the same chapters and nearly the same words, not identical bytes.
7. **A fifth tab, Listen, beside Library.** The parity row already names five tabs: Home, Search, Explore, Library and Listen. The mini player sits above the tab bar, as the spec says. Music and audiobooks join Listen in I6, so in I5 Listen is the Reading tab only.
8. **Dictionary: a Wiktionary pack when one is installed, as on Android; otherwise the system dictionary** (`UIReferenceLibraryViewController`), where Android hands off to a dictionary app.
9. **archive.org follows the Play rules on the App Store:** only books published before 1930, and no "Show everything".
10. **Onboarding's voice step and the Voice settings come with the voices.** That finishes the onboarding row I4 left open.

## Global constraints

- **Android must not change for the user (I5a).**
  - The whole JVM suite passes and `:app:bundleRelease` builds with R8.
  - A Pixel 6 walkthrough passes on a debug build installed over the phone's data. Check `lastUpdateTime` and the DB version first, use `adb -s` with the Pixel's serial, and start `app.granger/.MainActivity` explicitly.
- **No migration unless a task needs one.** Book tables are already in the shared store (DB 18). If one is needed, it's named here first.
- The repo is on the X10, which must be mounted. One Gradle or Kotlin/Native build at a time.
- Pushing and GrangerCore releases have standing OK, after the noreply author check.
- Branch `i5-books` off main 33acb6e2, in worktree `.claude/worktrees/i0`.
- Licences: Readium (BSD-3), sherpa-onnx and onnxruntime (Apache-2.0 / MIT), libarchive (BSD-2) and the Piper voices (as on Android) go in the iPhone's licence screen in I7.

## I5a: the shared rules

### Task 1: Books to the store
- [ ] Move to `:core:store` commonMain:
  - `Bookshelf` (import, scan, covers, hashes; the file work through the store's `Files`, sha256 through the shared `sha256`), `Shelves`, `ReadingSessions`, `Filing`, `GutenbergBooks`, `PdfEbooks`;
  - `BookIndex` (the books' full text: the FTS table stays in DB 18; the query goes through Room KMP's raw query);
  - `MentionIndex` and `BookLinks` (Android SQLite → `SqlFile`, read-only, as I4 did for the sidecars).
- [ ] Android's classes stay as thin subclasses or factories keeping their constructors. The tests move with the code.
- [ ] Simulator tests: import an EPUB into a shelf, find it by its words, and read a `.gqid` mention and a `.glink` link.

### Task 2: Library, reader and archive.org models to the core
- [ ] Move to `:core:screens`:
  - `LibraryViewModel`, `CollectionViewModel`, `RemoteCollectionViewModel`, the reading shelf's model, `GutenbergShelf`, `ImportRunner`'s rules, `ArchiveViewModel`, `ListenViewModel`;
  - the e-book reader's non-Readium parts: annotations, search in the book, reading prefs values, reference links, the dictionary lookup.
- [ ] Readium types stay on the Android side. The shared side passes locators as JSON and book links as hrefs.
- [ ] Move `core/archive` to KMP on Ktor (OkHttp engine on Android, Darwin on the iPhone), along with `PublicDomain` and the 1930 rule.
- [ ] Search's Books and By kind join the shared `TitleSearch` with `BookIndex`.
- [ ] String keys go through ScreenTextIds and ScreenText (`make_text_ids.py`).

### Task 3: Speech and converting to the core
- [ ] Move `:core:speech`'s rules to KMP: Sentences (ICU on both, per call 4), SpokenText, SpokenWords, QuoteSpans, Pronunciations, Utterance, ReadSource, StreamingTextReadSource, VoiceTitles, InstalledVoices.
- [ ] Move the player's `ReadAloudEngine`, `SleepTimer`, `ChapterMap`, `PositionSaver` and `AutoRewind` to the shared core. `SpeechOutput` (AudioTrack), Sherpa and System speech stay Android, behind the interfaces the engine already uses.
- [ ] Voice downloads (left Android in I4) join the shared queue.
- [ ] Move `core/books`' conversion rules (PdfToEpub, TextToEpub, ComicToPdf, EpubInspector, BookMeta, Chunking, XhtmlText) behind `PdfText` and `ComicPages` (per call 6).
- [ ] Test: the same paragraphs give the same sentence breaks on JVM and on the Simulator.

### Task 4: I5a check and merge
- [ ] Run the JVM suite and `bundleRelease` with R8.
- [ ] Walk through it on the Pixel 6:
  - the Library (reading shelf, a collection, organize, list/grid, letter strip);
  - an EPUB (pages, contents, a highlight and a note, search in the book, text settings, a link peek, the dictionary) and a PDF;
  - a Classics book's reference link, and In your books under an article;
  - Read aloud in both readers with Owl and with a system voice;
  - Listen's Reading tab (speed, sleep, saved place) and the notification controls;
  - archive.org search and a small item, importing an EPUB and a CBR, and Search's Books.
- [ ] Publish a GrangerCore release and pin it. Merge and push.

## I5b: the iPhone

### Task 5: Readium Swift and the readers
- [ ] **Spike, one hour; stop and report if it fails:** the Readium Swift toolkit (the 3.x release nearest Kotlin 3.4) as a Swift package. Open a Classics EPUB, go to a stored Android locator, draw a highlight decoration, and get one search result.
- [ ] Build the e-book reader:
  - pages and scrolling, contents, text settings (the shared prefs values), tap to hide bars;
  - highlights and notes (the shared annotations; the selection menu as Android's), search in the book, link peek, the dictionary (per call 8);
  - fixed layout, and the place saved through the shared `ReadingSessions`.
- [ ] Build the PDF reader on Readium's PDF navigator (PDFKit). A pack's PDF or EPUB opens here, not in Quick Look.
- [ ] UI tests: open, turn pages, highlight, search, reopen at the place.

### Task 6: The Library tab
- [ ] Build the Library tab over the shared models:
  - the reading shelf, collections, the Classics shelf, list and grid views, the letter strip, organize (move, rename, hide), covers;
  - the Gutenberg shelf;
  - book packs read as books (finishing I4's book-pack row), and the online bookshelf (gbooks and reading editions, as BK).
- [ ] Import from Files: EPUB, PDF, CBZ and CBR (libarchive; per call 6), copied in as Android does.
- [ ] Open books on the rail's iPhone form: the last four articles and books from each tab's toolbar, with the reading place.
- [ ] UI tests: import, file into a collection, reopen from Recently open.

### Task 7: Classics, Linked Classics and archive.org
- [ ] Granger Classics and Clásicos Granger from the catalog.
- [ ] Reference links in the e-book reader (MentionIndex and BookLinks), and In your books with the footer views in the article reader.
- [ ] archive.org: search, the item sheet, sets with their parts in order, text PDF → e-book (PDFKit; per call 6), and the 1930 filter (per call 9).
- [ ] UI tests: a reference link opens its topic; an archive.org text PDF becomes an EPUB on the shelf.

### Task 8: Voices, read aloud and Listen
- [ ] **Spike, one hour; stop and report if it fails:** sherpa-onnx 1.13.8 for iOS. Speak one sentence with the Owl voice through `AVAudioEngine` on the Simulator, and time it.
- [ ] The voices:
  - Piper voices from the catalog through the shared queue (in Get more and Voice settings);
  - system voices through `AVSpeechSynthesizer`;
  - Voice settings (voice, pace, downloads), and onboarding's voice step (per call 10).
- [ ] Read aloud in the article reader and the e-book reader with sentence marks: the page scripts' marks, unchanged from Android, and Readium decorations in books.
- [ ] Listen's Reading tab:
  - chapters, speed, sleep, saved place;
  - the mini player above the tabs;
  - Now Playing and remote commands, and audio that goes on with the screen locked (per call 4).
- [ ] UI tests: start reading aloud, pause from the mini player, the sleep timer, reopen at the saved sentence.

### Task 9: I5b check and merge
- [ ] Run the whole Simulator suite and the JVM suite.
- [ ] Walk it in Night and at the largest text size. VoiceOver is checked by tree, as before.
- [ ] Tick the I5 rows in `ios-parity.md`, with reasons where they differ.
- [ ] Publish a GrangerCore release and pin it. Merge and push. Tell the peer sessions what moved.

## Order and size

I5 is the biggest plan so far: about 25,000 lines of Android code are read or moved (ebook, library, archive, speech, player, books).
- I5a is mostly moving code, like I4a, but `Bookshelf`, `BookIndex` and `EbookReaderViewModel` are large. Each goes in its own commit with its tests, so a failure is easy to find.
- I5b's risks are the two native libraries. That's why Tasks 5 and 8 each start with a one-hour spike, and nothing is built on either library until its spike passes.
- If I5 runs long, it splits at Task 7: books first (Tasks 5–7), then voices and Listen (Task 8) as their own merge.
