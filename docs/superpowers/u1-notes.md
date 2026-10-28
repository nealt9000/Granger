# U1 walkthrough notes (raw, 2026-10-28)

Pixel 6, main install `app.granger` (Neal's library, 35 GB) and `app.granger.fresh` (empty). Shots in `u1-shots/`.

## Library install

### Home (01-open)
- Rail: 5 tools, then 12 recent entries, then Settings. Recents are mostly letter tiles (U, H, M, H, C) and four identical speech-bubble icons for Research chats; no way to tell them apart without long-press. The rail is the busiest thing on screen.
- Mini player sits above Home's title on every tool, taking ~120 px, even when paused.
- Readiness strip: nine unlabelled dots, one paler; meaning not discoverable.
- Continue: Stack Exchange index pages ("Users - Biblical Hermeneutics Stack Exchange", "Highest Voted Questions") count as things to continue; letter-tile art; "Play again" as a subtitle on an article card.
- Feeds show "Drawing…" on open.
- Feed headings change once drawn: "On this day · Best of Wikipedia" → "October 28"; "Random · Best of Wikipedia" → "Random from Best of Wikipedia".
- Titles keep the site suffix: "| Based Cooking", "- Parenting Stack Exchange". Pack name "based.cooking LukeSmith" is a raw ZIM name.
- One card's lead image renders solid black (Catalan declaration); others get a generic grey icon tile.

### Search (03–05)
- Empty state: just "Recent" queries. No hint of what's searched (packs, books, media), no scope chips.
- Results come in three blocks: Titles (top 10, then "Show the first 50 titles"), article full text ("Show the first 18 article matches"), and "In books" ("Show all 23 book matches"). There are no headings for the article block, so the Titles block runs straight into it.
- Ranking: "215 Cassiopeia solar apocalypse computer" (Appropedia) is ahead of Wikipedia's "Apocalypse". Stack Exchange user pages ("User apocalypse", "User kviiri", iFixit "Zihan Shu") fill the title and article results.
- Article snippets are page chrome: authority-control and category footers, IEP's nav menu, SE profile stats. The match itself isn't shown or highlighted.
- Books: each hit repeats the title + author (The Idiot ×3, Les Misérables ×4); not grouped by book.
- Granger Classics' War and Peace shows up under articles, while the e-books show up under books; same kind of thing in two places.
- Swiping with the keyboard up glides text into the field (gesture typing): the keyboard stays up over results. (Mine; but Search should drop the keyboard on submit.)

### Research (06–09)
- Landing: "Recent chats" list + input. No sense of what Research can do, what it reads, or that it's a preview / can be wrong.
- "what causes the tides?": "Loading the model…" → "Writing…" → a good grounded answer with [1][3] marks in ~20 s. Four source cards below, each "N · Title / Pack · Title / passage"; the title shows twice.
- The headphones icon (spoken answer) has no label.
- Every chat goes straight into the rail's recents: a fifth identical speech-bubble tile.
- The Enter key from adb put a newline in the box instead of sending (the IME shows a Send action). Check with the real keyboard's Send key.
- With the keyboard up the rail gets clipped mid-tile.

### Library (10–12)
- One long list with two jobs: "Media" (what's held: Literature → Archive.org 3 books, Granger Classics 1 pack · 347 books, Kiwix 33 packs; Audiobooks ×4 rows; Music; Maps) and "Archive sources" (where to get more: Literature → Granger, Kiwix, Archive.org; Audiobooks → LibriVox, Archive.org; Music → Archive.org).
- Kiwix (Wikipedia, Stack Exchange, iFixit…) is filed under "Literature".
- Archive.org appears three times under sources, once per media type.
- Header icons with no labels: search, history, a filled check-circle (Readiness?), overflow.
- Audiobooks get four rows (All, Archive.org, LibriVox, On this phone) for 3 audiobooks.

### Sources → Kiwix (13–14)
- Search, category chips (All, Medical, Reference, Religion & Philosophy… scrolls off), Browse / Installed / Updates tabs, then the storage line "18 GB free · Granger uses 35 GB".
- Installed tab still has the "Recommended" heading. Each installed pack's only button is **Remove**; there's no Open. To read a pack you go back to Library → Media → Kiwix.
- "WikiMed (no pictures)" and "WikiMed" are two rows (flavours of one ZIM): Get on one, Remove on the other.

### Readiness (15–16), from the Home strip
- Reached only from the Home strip (or the Library check icon?); it isn't a rail tool or a named place.
- Reads well: This phone (Library and Research · 8 GB memory), What you hold offline (nine domains: "Basic · 2.2 GB of 4.4 GB"…), the deficiency lines with See, Storage, By source, AI models, Voices.
- "Basic · 79 MB of 4.6 GB": Basic with 2% of the domain held. What the "of" counts against isn't said.
- Counts disagree: Library says Kiwix 33 packs, Readiness says 36 items.
- Downloads in flight aren't shown here either (carried-forward gap).

### Player (17)
- Tabs Music / Audiobooks; read-aloud isn't a tab (it lives as the speaker tile in the rail's recents). U2 territory.
- It opens like a sheet ("⌄ Now playing · 6 of 31") yet it's a rail tool; the chevron goes back somewhere unclear.
- Clean otherwise: art, title/artist/album, seek, shuffle/prev/play/next/repeat, Queue, Music shelf.

### Settings (18–20)
- One long flat list: Models, Voice, Readiness, Set up suggestions again, Appearance (Theme, Toolbar side, Open to), Storage & downloads (Wi-Fi only, Ask over 100 MB, Archive.org: show everything), Music & audiobooks (permission, folders, Rescan, Show in other apps, ReplayGain, Skip back/forward, Play when headphones connect), then the brand footer.
- About half the screen is music/audiobook options; the core mission (read, ask) has two rows.
- **Bug:** the Voice row says "No offline voice on this phone" while three Granger voices are installed (ljspeech, Maggie, Martin) and Readiness counts Voices 67 MB.
- Voice list: Granger voices, then a long run of raw system voice ids ("System voice iob (en-US)", "iog", "iol", "msm00013"…). Subtitle repeats the kind ("Granger voice · en-US").
- Readiness is a Settings row as well as the Home strip.
- Footer: tagline, "Knowledge is a human right.", Version 0.1.0, GPLv3. No Send feedback / crash log yet (F1).

### The rail's drawer (21)
- A labelled drawer opens over the rail (it opened for me after Back from Settings → Voice; how it's meant to open isn't visible: edge swipe?). Labels make the recents readable, truncated at ~18 chars.
- 12 recents mix three Research chats, Stack Exchange index pages, a read-aloud item, an album, an e-book and a pack's "Home" page ("Home · FOSS cooking" — a page literally named Home, next to the Home tool).

### Wiki reader (22)
- Chrome stacks: mini player (~250 px), toolbar (Back, Home-of-pack, "Search Bes…" truncated, star, overflow), and the rail takes ~12% of the width the whole time you read. No full-screen/immersive reading.
- Page text is the pack's own Roboto on the paper ground: fine, but nothing of Spectral reaches the reading surface.
- The pack Home button (house icon) sits beside the rail's Home tool: two different "Home"s, same icon.
- Read aloud isn't on the toolbar (overflow).
- Opening a recent moves it to the top of the rail; the rail reorders under your thumb.
- **Bug (repeatable):** tapping the e-book's cover tile in the rail (A Vindication…, the only tile with real cover art) opens the drawer instead of the book. Letter and icon tiles open their item.

### E-book reader (24)
- Reads well in Spectral on paper. Top: Back, title, Contents (≡), Search. Bottom: Contents (≡ again), Aa, headphones (read aloud), star, plus the chapter name and a progress bar.
- The two readers disagree: the wiki reader has Read aloud in overflow and no bottom bar; the e-book reader has a headphones button and a bottom bar. Research's spoken answer also uses headphones; the rail's read-aloud item uses a speaker.
- Same chrome cost as the wiki reader: mini player + rail + two bars.

## Fresh install (Granger Fresh)

### Welcome (fresh 01)
- First run follows the phone into Night. The brand lands well: owl, Spectral wordmark, tagline, one-line pitch, Get started / Skip.
- The full rail shows during onboarding (Home, Search, Research, Library, Player, a lone History icon, Settings). Every tool is tappable before anything is installed.

### This phone (fresh 02)
- "A library and research phone, with about 7 GB of memory." Readiness on the same phone says "8.0 GB of memory".
- "Research, the AI librarian, is coming in a later release; this phone can run it." The Research tool sits in the rail beside it (debug build). F1 has to settle this line for the Preview.
- Storage: "18 GB free of 118 GB". Fine.

### Pick a start (fresh 03–04)
- **First thing a new user meets on this step: "Refresh the catalog to see the choices." + a Refresh button.** The catalog isn't fetched on first run; it took ~2 s once tapped.
- Then: Efficient start · 12 GB (preselected), Comprehensive library · 91 GB, Neither. Below, a flat checklist of every pack in the preset (WikiMed (no pictures), based.cooking LukeSmith, Water Treatment Library, Food for Preppers, Knots, iFixit 3.8 GB, Physics, Chemistry, Mathematics, Computer…): no domain grouping, raw ZIM names again.
- Efficient preselected on a phone with 18 GB free leaves ~6 GB; no warning shown here.

### Your situation (fresh 07–08)
- Opens already scrolled so the "Country or area" field is cut off at the top.
- Region: a ~200-country radio list from Afghanistan down, then "Larger areas" (Africa 8.1 GB … World 78 GB). No guess from the phone's locale (US), so a US user scrolls past a long list or uses the field.
- "What you'll use it for" (Off-grid & emergencies, Travel, Study, Build & repair, Medical work, Software, Faith & philosophy; each "Adds N GB · N items", See) sits under the whole country list. Many users won't reach it.
- Two questions on one screen with one Next.

### Review and start → Home (fresh 09–12)
- Review still says "Efficient start" after I unchecked everything big (318 MB of the 12 GB). The heading should follow the picks.
- Start gave no feedback for ~4 s (Review stayed on screen), then Home.
- Home fills fast and looks good: status line "> 149 MB here, ready offline.", the dots, the next suggestion ("Nothing yet for Medicine & first aid. Get WikiMed (no pictures), 1.2 GB."), a progress bar "Setting up · 76 of 351 · 161 MB of 244 MB", then feeds from Knots and Granger Classics within seconds.
- "351 items" counts each Classics book; "244 MB" disagrees with Review's 318 MB.
- "On this day" fed from the Knots pack ("October 28": Borromean rings, Knot, Cornrows): the heading promises a date link the pack can't give.
- The suggestion line asks for 1.2 GB right after the user chose not to get it.
- The rail's lone History icon sits where recents will be; nothing says what it is.

### Fresh Research and Library (13–14)
- Research: "Research needs the Gemma 4 E2B model. Get it in Settings → Models." + Get models. Onboarding said "this phone can run it" but never offered the model; the core "ask it" mission is a Settings trip away after setup.
- Library: Media (Granger Classics 123 books and climbing, Kiwix 4 packs), then a "No music or audiobooks yet" block promoting LibriVox, Archive.org audiobooks, Archive.org music, Settings — taller than the content above it. Then Archive sources again (Granger, Kiwix, Archive.org, LibriVox…).
- So a new user's Library leads with audiobooks/music sources, which U2 wants passive.
