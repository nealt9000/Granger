# Plan R3: Deep links — every citation opens at its passage

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every Research citation has one address and opens at it. An article opens at its section with the cited sentence
highlighted. A book opens at the passage with the sentence the answer used highlighted, not the chunk's first 80 characters.
A song or an audiobook chapter can be cited by name, opens its album or audiobook at that part, and has a play button in the
answer. An address that no longer resolves opens the nearest thing that does. Projects 3 and 4 reuse the addresses.

**Spec:** `docs/superpowers/specs/2026-10-21-research-library-design.md` §7, §9, §10, §11 (the walkthrough), §13. R3 doesn't
depend on R1 or R2 (§13).

**Out of this plan:** retrieval and ranking (R1, R2); reading an address aloud (project 3 has `ReadAloud.start` in V2 Task 8;
it can take an address later); plans and playlists of addresses (project 4); PDFs inside ZIMs.

**Architecture (what changes):**
- **`:core:research`:** `CitedQuote`, which picks the sentence of a passage that best matches the answer's claim citing it.
  Pure JVM, `java.text.BreakIterator` for sentences.
- **`:core:library`:** `PassageAddress`, the four address kinds from spec §7 plus the two whole-source ones (a pack's main
  page, an album or audiobook), with `format`, `parse` and `of(passage, quote)`. It reuses `MediaAddress` for tracks and
  audiobooks. Media cards can focus on one part: `MediaWork.partIds`, `MediaCards.bestPart`, and `MediaCards.passage(work, part)`.
- **`:app`:** `CitationOpener` moves out of `ResearchViewModel.target()` and resolves an address with the fallback chain.
  `CitationTarget` gains the quote, the part and the fallbacks. The web reader marks a cited range through `ReadAloudScript`
  (a second highlight name). The e-book reader's existing search-hit mark gets the better quote with its context. The album
  and audiobook pages mark a part. `SourceCard` gets a play button.

**Tech Stack:** unchanged: Kotlin 2.4.21, Compose Material 3, Room 2.8.5 (**no migration**: see the decisions), Readium
3.4.0, Media3 1.11.1, jsoup, Robolectric 4.17, JUnit 4 + Truth.

**Roadmap:** `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`, row R3.

## What the code looks like now (surveyed 2026-10-22, `main` at `0cc92f6`)

- **Passages** (`core/research/.../Passage.kt`): `kind` (ARTICLE, BOOK, CARD, SELECTION, MEDIA), `sourceId` (pack id, book id
  `book:<origin>:<ref>`, or the media work id `album:<albumKey>` / `audiobook:…`), `location` (entry path or spine href; `""`
  for cards and media), `anchor` (the section's heading id), `progression`, `heading`, `text`. Stored per turn in
  `research_passages` (library v11) with the same columns. There's no address anywhere yet.
- **Opening** (`ResearchViewModel.open(passage)` → `target()`): MEDIA goes to `CitationTarget.Album(albumKey)` or
  `Audiobook(id)`. An article opens a rail tab at `location` with `fragment = anchor`, so it lands on the heading, unmarked. A
  card opens the pack's main page. A book (or a placed SELECTION) opens at
  `BookTarget(href, progression, TextQuote("", cutAtWord(text, BOOK_QUOTE), ""))`. Anything missing is `Gone` → snackbar "That
  source is no longer in your library." `GrangerNavHost` (≈ line 719) routes the targets.
- **Book mark:** `StartLocator.choose` turns the target's quote into `Locator.Text(before, highlight, after)`.
  `EbookReaderViewModel` sets `searchHit` when the landing locator has a highlight, and the screen decorates it as an in-book
  search hit is. So the e-book side already highlights. Only the quote is poor.
- **Article page:** `ZimWebViewClient.onPageFinished` injects `ReaderPageScript` and `ReadAloudScript`. `ReadAloudScript`
  (V1) numbers blocks by `ArticleText.blocks()` rules (ids agree with Kotlin, `g<n>` fallback) and marks a character range of
  a block's folded text with the CSS Custom Highlight API (falling back to a class). `ReaderRoute(tabId, fragment)` carries
  only the fragment.
- **Media:** `MediaAddress` (`core/library`) formats and parses `track:<id>@<ms>` and `audiobook:<id>@<chapter>/<ms>`. It's
  used by the player's position saves, but nothing opens one. `MediaCards.passage(work)` is one card per album or audiobook
  with `Tracks:`/`Chapters:` titles. `MediaWork.partTitles` has no ids. `LibraryPassageFinder.mediaCards` takes at most
  `mediaCards = 2`. `PlayerHub.playTracks(ids, startIndex, shuffle, source)` and
  `playAudiobook(id, at: AudioBookmark?)` exist. `AudiobookChapter` has `ordinal, trackId, startMs`.
- **Answer UI** (`ResearchScreen`): `[n]` chips and numbered `SourceCard`s, and both call `open(passage)`. Tests:
  `ResearchViewModelTest`, `CitationTabTest`, `AnswerTextTest`, `MediaAddressTest`. About 1730 JVM tests pass.

## Other sessions (as of 2026-10-22)

| Branch | Shares with R3 | How R3 stays out of the way |
|---|---|---|
| `r1-dense-retrieval` (R1) | `Passage.kt` (adds `cosine`), `LibraryPassageFinder` (+ test), `ResearchPipeline`; moves `ArticleText`/`HtmlPassages` into `:core:text` with the same packages | R3 adds **no field to `Passage`** and doesn't edit `ArticleText` or `HtmlPassages`; it only calls them. Task 3's edit to `LibraryPassageFinder.mediaCards` is small, and rebases over R1 |
| `worktree-voice-v2` (V2) | `ReaderScreen`, `ReaderViewModel`, the e-book reader files, `PlayerHub`/`PlayerEngine`, and in Task 7 `ResearchScreen`/`ResearchViewModel`/`AnswerText` | R3 doesn't change `PlayerHub` (it plays through the existing calls). Reader edits are a route parameter and one `evaluateJavascript`. Whichever lands second rebases. R3 tells the V2 session before touching `ResearchScreen` |
| `h1-readiness-model` (H1), then H2 | H2 plans `home_feeds`, the next library version | R3 needs **no migration**, so v12 stays free for H2 or R2 |
| `p1-brand` | none | none |

The phone is shared with these sessions. Ask the session holding it before the walkthrough.

## Decisions

Spec §12 decisions carry over. The builder's calls below are for Neal's review.

1. **Addresses are derived, not stored.** A passage's address is a pure function of its stored row plus the turn's answer
   (which gives the quote), so `research_passages` keeps its columns and there's no migration. `ResearchChats.addresses(turnId)`
   hands them to projects 3 and 4. *If Neal wants the string stored (say, to survive a change to the quote rule), it's one
   column in v12 later.*
2. **Address strings** follow spec §7, with each field percent-encoded for `%`, `/`, `#`, `@` and `~` (and `?` in the quote),
   so book ids holding `:` and article paths holding `/` split cleanly:
   - `article:<packId>/<path>#<anchor>~<quote>` (anchor and quote optional)
   - `book:<origin>:<ref>/<href>@<progression>~<quote>`
   - `track:<id>@<ms>` and `audiobook:<id>@<chapter>/<ms>` (`MediaAddress`, unchanged)
   - `pack:<packId>` for a card (the main page)
   - `album:<albumKey>` for a whole-album card

   A whole audiobook is the bare `audiobook:<id>` (it resumes where it was left; `@0/0` would be chapter 0's start).
3. **The quote rule.** A claim is each answer sentence that cites `[n]`; with no such sentence, the whole answer is the claim.
   Words are folded (lower case, accents off) and stopwords dropped (`Keywords.isStopword`). Each sentence of the passage
   scores its shared content words over √(its content words). Ties go to the earlier sentence, and sentences under 4 words
   are skipped. With no shared word the first sentence wins. A quote over 240 characters is cut at a word. Books get 40
   characters of passage text before and after as `TextQuote.before/after`, so Readium finds the right one of two repeats.
4. **Article highlight in Kotlin, mark in the page.** `ArticleQuote.locate(blocks, anchor, quote)` runs on `ArticleText.blocks()`
   (the rules the page script shares). It searches the anchor's section first (the heading block to the next heading of the
   same or higher level), then the whole article. Matching is whitespace- and accent-folded, char for char, so offsets stay
   valid in the block's folded text. It returns `(blockId, prefix, start, end)`; the page marks that range with the
   `granger-cite` highlight and scrolls to it. When the quote isn't found it falls back to the heading (today's behaviour).
   The mark stays until the next page load, as an e-book search hit does.
5. **Media parts.** A media card stays one card per work, so Ranking's media cap is unchanged. When a keyword matches a song or
   chapter title as whole words, the card focuses on that part:
   - `location` is `track:<id>` or `chapter:<ordinal>`;
   - `heading` is the part's title;
   - the card's text gains a first line, `Song: Mars, the Bringer of War (1 of 7)` or `Chapter: …`.

   The best part is the one matching the most keyword words; ties go to the earliest.
6. **Open against play.** A tap on a media citation (chip or card) opens the album or audiobook page with the part marked and
   scrolled to, without playing. The card's **Play** button plays from that part and stays in Research (the mini-player
   shows). *This differs from spec §7's "the album with the track playing": playing on every tap would start music while
   someone is reading an answer. Neal to confirm.*
7. **The fallback chain** (spec §7):
   - an article whose path is gone opens the pack's main page, with the snackbar "That page is no longer in this pack.";
   - a book whose href is gone opens the book where it was left;
   - a song or chapter that's gone opens its album or audiobook;
   - a missing pack, book, album or audiobook gives today's "That source is no longer in your library."

## Global Constraints

- Offline; nothing leaves the phone.
- No migration. `research_passages` rows written before R3 resolve, with no quote; they get one from the stored answer.
- Don't add fields to `Passage`, and don't edit `ArticleText`, `HtmlPassages` or `PlayerHub` (other sessions own those
  files right now).
- Words in the UI: "Play", "That page is no longer in this pack.", "That source is no longer in your library." (unchanged).
- JVM tests: `./gradlew testDebugUnitTest` and `./gradlew :core:research:test` pass. No `connectedDebugAndroidTest`
  (it wipes the library). Device checks on the Pixel 6 over Wi-Fi, never while a personal app is in front, never
  `adb reboot`; back up both databases before installing; one Gradle build at a time.
- In the worktree: copy `local.properties`, run `git submodule update --init --recursive`, and build with
  `-Pkotlin.compiler.execution.strategy=in-process`. The sandbox refuses compound shell in worktrees, so split commands.

## Review Focus

- `PassageAddress` round trips for awkward ids: a book ref with `/` and `:`, a ZIM path with `#`, `%` or spaces, a quote with
  `~` and `@`, an empty anchor, progression 0 and 1, and `MediaAddress` strings passing through unchanged. `parse` of junk is
  null, never a throw.
- `CitedQuote`: a passage of one sentence; an answer citing `[1][2]` in one sentence; a claim in a list item; abbreviations
  ("Dr.", "c. 1850") not splitting sentences badly enough to make a 4-word quote; non-English letters folded consistently on
  both sides.
- `ArticleQuote.locate` agrees with the page script's block ids on the same HTML (reuse `ArticleReadSourceTest`'s fixtures):
  a quote crossing an inline `<a>` or `<b>`, a quote in a table cell, a quote that occurs twice (the anchor's section wins).
- A stored pre-R3 turn: opening its citations still works, and the quote comes from its answer.
- `CitationOpener` is the only place that turns a passage into a target. `ResearchViewModel.open` and any later caller
  (projects 3 and 4) go through it.

---

### Task 1: `PassageAddress`

**Files:**
- Create: `core/library/src/main/kotlin/app/granger/core/library/PassageAddress.kt`. `sealed interface PassageAddress` with
  `Article(packId, path, anchor?, quote?)`, `Pack(packId)`, `Book(bookId, href, progression, quote?)`, `Album(albumKey)`,
  `Track(id, ms)`, `Audiobook(id, chapter, ms)`. `format()`; `companion parse(s): PassageAddress?`;
  `companion of(passage: Passage, quote: String?): PassageAddress`. For MEDIA, `of` reads `location` (`track:<id>` →
  `Track(id, 0)`, `chapter:<n>` → `Audiobook(sourceId, n, 0)`, `""` → `Album` or `Audiobook(id, 0, 0)`). Track and audiobook
  format and parse go through `MediaAddress`.
- Test: `core/library/src/test/kotlin/app/granger/core/library/PassageAddressTest.kt`

- [ ] **Step 1: Failing tests:** `everyKindRoundTrips`, `awkwardIdsAndQuotesRoundTrip` (the Review Focus cases),
      `mediaAddressesPassThrough`, `junkParsesToNull`, `ofMapsEachPassageKind` (ARTICLE, CARD → Pack, BOOK, a placed
      SELECTION → Book, an unplaced SELECTION → Book at progression 0 with no href, MEDIA with each location form).
- [ ] **Step 2:** `./gradlew :core:library:testDebugUnitTest --tests '*PassageAddressTest'`. Expected: compile failure.
- [ ] **Step 3: Implement.** Percent-encode per field with a small local encoder (not `URLEncoder`, which turns spaces into
      `+`).
- [ ] **Step 4:** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `R3: PassageAddress — one address for every citation kind`

### Task 2: `CitedQuote` — the sentence the answer used

**Files:**
- Create: `core/research/src/main/kotlin/app/granger/core/research/CitedQuote.kt`.
  `pick(answer: String, n: Int, text: String): Quote?` with `Quote(before, sentence, after)` (character context from the
  passage, decision 3). `claims(answer, n)` uses `Citations`' chip pattern, so `[1][2]` and `[1, 2]` both count.
- Test: `core/research/src/test/kotlin/app/granger/core/research/CitedQuoteTest.kt`

- [ ] **Step 1: Failing tests:** `picksTheSentenceSharingTheClaimsWords`, `onlySentencesCitingNCount`,
      `noCitingSentenceUsesTheWholeAnswer`, `noOverlapFallsBackToTheFirstSentence`, `shortSentencesAreSkipped`,
      `longQuotesAreCutAtAWord`, `contextIsFortyCharsEachSide`, `accentsAndCaseFold`
      (an inline King James Matthew 6:25–34 fixture: "take no thought for the morrow" is picked for an answer about
      not worrying about tomorrow).
- [ ] **Step 2:** `./gradlew :core:research:test --tests '*CitedQuoteTest'`. Expected: compile failure.
- [ ] **Step 3: Implement** as decision 3.
- [ ] **Step 4:** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `R3: CitedQuote — the passage sentence that best matches the answer's claim`

### Task 3: Media cards focus on a song or a chapter

**Files:**
- Modify: `core/library/.../MediaIndex.kt` (`MediaWork.partIds`: track ids in album order, or chapter ordinals as strings
  for an audiobook, as `chapter:<ordinal>`)
- Modify: `core/library/.../MediaCards.kt` (`bestPart(work, words): Int?`, the index into the parts, whole-word matches on
  the folded title, decision 5; scoring stays on the whole-work card so the kept works don't change; `passage(work, part: Int? = null)`: `location`, `heading` and the first line
  `Song: <title> (<i> of <n>)` / `Chapter: <title> (<i> of <n>)`)
- Modify: `core/library/.../LibraryPassageFinder.kt` (`mediaCards`: `MediaCards.passage(work, index.bestPart(work, words))`)
- Tests: `MediaIndexTest` (`workCarriesPartsAndTheAlbumNote` checks `partIds`), `MediaCardsTest`
  (`aFocusedCardLeadsWithItsPart`, `aChapterCardSaysChapter`, `anUnfocusedCardIsUnchanged`, `bestPartPicksTheTitleMatchingMostWords`,
  `noPartWhenOnlyTheAlbumMatches`), `LibraryPassageFinderTest`
  (`aSongTitleFocusesItsAlbumsCard`: "Mars bringer of war" finds *The Planets* focused on track 1)

- [ ] **Step 1: Failing tests** as named.
- [ ] **Step 2:** `./gradlew :core:library:testDebugUnitTest`. Expected: failures on the new tests only.
- [ ] **Step 3: Implement.** Android Auto's browse and Search's media groups don't read `partIds`; check that their tests
      are unchanged.
- [ ] **Step 4:** Same command, plus `./gradlew :core:research:test` (media cue tests). Expected: PASS.
- [ ] **Step 5: Commit.** `R3: media cards name the song or chapter they matched`

### Task 4: `CitationOpener` — addresses resolved, with the fallback chain

**Files:**
- Create: `app/src/main/kotlin/app/granger/research/CitationOpener.kt`. `suspend fun open(address: PassageAddress): CitationTarget`
  and `suspend fun address(turn: ChatTurn, n: Int): PassageAddress` (`PassageAddress.of(passage, CitedQuote.pick(...))`).
  This is the body of `ResearchViewModel.target()`, moved and extended with decision 7. Article existence comes from
  `ZimArchive.resolvePath` (redirects followed) on the pack the reader opens.
- Modify: `CitationTarget.kt`:
  - `Article(tabId, fragment, quote?, movedNote: Boolean)`;
  - `Book(tabId, at)` (the `BookTarget` quote now carries before/after);
  - `Album(albumKey, trackId?)` and `Audiobook(id, chapter?)`;
  - `Gone` stays.
- Modify: `ResearchViewModel.kt` (`open(turnId, n)` replaces `open(passage)`; it logs and returns `Gone` on error as now),
  `ResearchScreen.kt` (chips and cards pass `turnId, n`; the "no longer in this pack" snackbar)
- Modify: `core/library/.../ResearchChats.kt` (`addresses(turnId): List<PassageAddress>` through the same rule, for projects 3
  and 4; the rule lives in `:core:library` with `PassageAddress`, and `CitationOpener.address` calls it)
- Tests: `CitationOpenerTest` (new; fakes from `ResearchFakes`): `articleOpensAtItsSectionWithTheQuote`,
  `aMovedArticleOpensThePackMainPageWithTheNote`, `aCardOpensTheMainPage`, `aBookOpensAtTheBestSentenceWithContext`,
  `aMissingHrefOpensTheBookWhereItWasLeft`, `aSongOpensItsAlbumAtTheTrack`, `aGoneSongOpensTheAlbum`,
  `aChapterOpensItsAudiobookAtTheChapter`, `aMissingSourceIsGone`, `aPreR3TurnGetsItsQuoteFromItsAnswer`. Update
  `ResearchViewModelTest` and `CitationTabTest` for the new `open` signature.

- [ ] **Step 1: Failing tests** as named.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.research.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** Before editing `ResearchScreen`/`ResearchViewModel`, check whether V2 Task 7 has landed on main
      and rebase onto it first if so; tell the V2 session which lines R3 changes (the chip and card `onClick`, `open`).
- [ ] **Step 4:** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `R3: CitationOpener — citations resolve through their address, with fallbacks`

### Task 5: Article highlight at the passage

**Files:**
- Create: `app/src/main/kotlin/app/granger/reader/ArticleQuote.kt`. `locate(blocks: List<ArticleBlock>, anchor: String?, quote: String): CiteRange?`
  with `CiteRange(blockId, prefix, start, end)`: offsets in the block's `ArticleText.fold`ed text, matched char for char
  with accents stripped per character (decision 4).
- Modify: `app/src/main/kotlin/app/granger/reader/ReadAloudScript.kt` (`cite(id, prefix, start, end)`: the same block
  lookup and range code as `mark`, under highlight name `granger-cite`, styled as the e-book search hit with the
  `GrangerColors` flame at 35% alpha; it scrolls the range to a third of the way down; `clearCite()`)
- Modify: `GrangerNavHost.kt` (`ReaderRoute(tabId, fragment, quote: String? = null)`; `showTab(…, quote)`;
  `CitationTarget.Article` routing)
- Modify: `ReaderViewModel.kt` (takes `quote`; once the first page's HTML is read, as `ArticleReadSource` reads it, it
  computes the `CiteRange` and exposes `cite: StateFlow<CiteRange?>`, cleared on the next navigation),
  `ReaderScreen.kt` (on page finished with a `cite`, `evaluateJavascript(ReadAloudScript.call("cite", …))`)
- Tests: `ArticleQuoteTest` (`findsAQuoteAcrossInlineTags`, `theAnchorsSectionWinsOverAnEarlierRepeat`,
  `accentsAndWhitespaceFoldWithOffsetsIntact`, `notFoundIsNull`, `blockIdsMatchTheReadSources` on
  `ArticleReadSourceTest`'s fixture), `ReaderViewModelTest` (`aCitedQuoteYieldsACiteRange`,
  `navigatingAwayClearsTheCite`)

- [ ] **Step 1: Failing tests** as named.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.reader.*'`. Expected: compile failure.
- [ ] **Step 3: Implement.** The JS isn't unit tested; the Kotlin side owns all matching, so the page only marks a given
      range, as V1's `mark` already does on the phone.
- [ ] **Step 4:** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `R3: articles open at the cited sentence, highlighted`

### Task 6: Better book quotes

**Files:**
- Modify: `CitationOpener.kt` (the book target's `TextQuote(before, sentence, after)` from `CitedQuote`; with no quote, the
  old first-80-characters cut, so nothing gets worse)
- Modify: `app/src/main/kotlin/app/granger/ebook/StartLocator.kt` only if a test shows Readium needs the quote trimmed to
  the resource's text (whitespace)
- Tests: `StartLocatorTest` (`aMidPassageQuoteLandsOnItsSentence` on the test's own small EPUB fixture, `beforeAfterPickTheRightRepeat`),
  `CitationOpenerTest` (from Task 4, now asserting the sentence)

- [ ] **Step 1: Failing tests** as named.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest --tests 'app.granger.ebook.*' --tests '*CitationOpenerTest'`. Expected:
      failures on the new tests.
- [ ] **Step 3: Implement.**
- [ ] **Step 4:** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `R3: book citations highlight the sentence the answer used`

### Task 7: Media pages at the part, and the Play button

**Files:**
- Modify: `GrangerNavHost.kt` (`AlbumRoute(albumKey, trackId: String? = null)`,
  `AudiobookRoute(audiobookId, chapter: Int? = null)`; the citation routing)
- Modify: the album screen and its view model (scroll to `trackId` and outline its row for two seconds, as `SourceCard`
  flashes), and the audiobook screen and its view model (the same for the chapter)
- Modify: `ResearchScreen.kt` (`SourceCard` for MEDIA: a **Play** `IconButton` with content description "Play <part or
  title>"), `ResearchViewModel.kt` (`play(turnId, n)`: a track → `playTracks(album track ids, index, shuffle = false,
  PlaySource("album:<key>", album title))`; a chapter → `playAudiobook(id, AudioBookmark(0, id, chapter.trackId,
  chapter.startMs, chapter.title, "", now))`, the bookmark transient and never stored; a whole album → from track 0; a whole
  audiobook → `playAudiobook(id)` at its saved place)
- Tests: `ResearchViewModelTest` (`playStartsTheAlbumAtTheCitedSong`, `playStartsTheAudiobookAtTheCitedChapter`,
  `playOnAWholeAudiobookResumesItsSavedPlace`, `playOnAGoneSongSaysSo`), the album and audiobook view model tests
  (`aRouteTrackIsMarked`, `aRouteChapterIsMarked`)

- [ ] **Step 1: Failing tests** as named.
- [ ] **Step 2:** `./gradlew :app:testDebugUnitTest`. Expected: failures on the new tests.
- [ ] **Step 3: Implement.** Check that `playAudiobook` with a transient bookmark doesn't write a bookmark row (it only reads
      `trackId` and `positionMs`). Spoken answers (V2 Task 7) pause for music like any voice; nothing to do here.
- [ ] **Step 4:** Same command. Expected: PASS.
- [ ] **Step 5: Commit.** `R3: media citations open at the song or chapter, and play`

### Task 8: Pixel 6 walkthrough; roadmap

- [ ] Ask the session holding the phone (R1 or V2) before taking it. Back up both databases; install over Wi-Fi.
- [ ] Check each kind (spec §11):
  - An article citation (Rosetta Stone, "What did Champollion decipher?") opens at its section with the cited sentence
    highlighted.
  - A card citation opens the pack's main page.
  - A book citation ("a verse about not worrying about tomorrow" if R1's vectors are in; otherwise "take no thought for the
    morrow") opens at the passage with the answer's sentence marked.
  - A selection sent from a book opens back at it.
  - A song citation ("Which track of The Planets is about war?") shows Play: Play starts Mars and stays in Research, and a tap
    opens the album with Mars marked.
  - An audiobook chapter citation plays from the chapter and opens at it.
  - Fallbacks: remove a cited book and tap → "That source is no longer in your library."; an article path that's gone (a
    pack updated to a newer snapshot) → the main page with the note.
  - An old chat from before R3 still opens its citations.
- [ ] Update the roadmap: the R3 row to Done with what passed and what's open; "Carried forward from R3" for projects 3 and 4
      (`ResearchChats.addresses`, `CitationOpener.open(address)`).
- [ ] **Commit.** `Research R3: deep links — every citation opens at its passage; roadmap`

## Order and size

Tasks 1–3 are pure JVM and independent: half a day together. Task 4 is the hinge (half a day). Tasks 5, 6 and 7 follow in any
order (5 is the largest, about half a day; 6 and 7 are a few hours each). Task 8 needs the phone and Neal. About two days of
building.
