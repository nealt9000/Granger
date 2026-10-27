# Granger brand and vision

**Status:** Direction set by Neal, 2026-10-23, and revised the same day from his notes (your own media in the vision, the renaissance-generalist voice with a punk streak), then again: the Grange leads the name with Fahrenheit 451 beneath it, and the field-recorder image sets a new field-kit palette. Ownership leads, preparedness is the proof; field-kit visuals replace the dark flame; four revenue paths. **2026-10-26:** a first mark (the cassette on books) shipped in H3 Task 5. **2026-10-27:** Neal replaced it with the owl in the G (§8, The mark), which ships with Plan T1. The same day he chose the tagline (§3) and moved the wordmark from the stencil to Spectral capitals (§8, Type); the final palette is still open.
**Reference art:** `docs/superpowers/brand/` (dated folders; the current reference is `2026-10-23-field-recorder-concept/`).
**Unblocks:** H3 Task 5 (the Welcome brand), Plan 10 (billing, now on the fair deal below), Plan 11 (the store listing), and the UI pass that follows the roadmap's features.

## 1. Vision

**The world's knowledge. Yours to keep.**

Granger puts the world's reference knowledge, literature and a local librarian on a device you own, right beside your own music, audiobooks and e-books. The librarian knows both: it answers from the encyclopedia and from your shelf, cites either, and plays the recording it cites. Nothing is rented, nothing needs an account, and nothing can be switched off by whoever made it. Because it is yours, it works anywhere: on a plane, in a tent, in a classroom with no signal, after a storm takes the network down.

## 2. The name

A *grange* is a granary: the store a farm lays up against lean seasons, so a bad winter doesn't decide who eats. Granger is that store for knowledge, filled while times are good and there when they aren't.

The Granger movement gave the name its spine. In the 1860s–70s, American farmers organised as the Grange to take on the railroad and grain-elevator monopolies, which set their own rates and could cut a farmer off from market on a whim. The "Granger Laws" they won were among the first limits on that kind of power. A cooperative of ordinary people refusing to depend on a provider that could shut them out: that is Granger's quarrel with rented media and tethered knowledge, 150 years early.

The mark carries the first story. Farmers left owl holes in their barn gables so barn owls would nest inside and keep the rats off the grain: the owl guards the store. It also sees in the dark, which is the point of Granger when the network goes down. Lead with the owl hole when the mark needs explaining. Granger plus an owl invites a jump to Hermione Granger and Hogwarts owls; it is not a legal problem, but the granary story should always come first, and Granger never uses wizarding imagery.

There is a quieter layer too. In *Fahrenheit 451*, Granger leads the book people, exiles who each carry a book in memory so no one can take it, and who walk back to rebuild when the burning stops. Tell that story in our own words: the novel is in copyright, so never quote it at length, use its covers, or suggest the estate endorses Granger.

## 3. Positioning: ownership first, preparedness as the proof

| | Leads with | Says |
|---|---|---|
| **Headline** | Ownership | It's yours: downloaded, offline, no account, no subscription, nobody can take it back. |
| **Proof** | Preparedness | So it works when nothing else does: no signal, no network, no service. |
| **Delight** | Your collection, back in your hands | Your music, audiobooks and e-books in one player with every feature and none of the tethers: no lapsed subscription empties your library, no algorithm decides what you hear next. |
| **Depth** | A librarian with context | It knows the encyclopedia and your shelf, connects them, and cites both. |

Streaming services traded the best possible experience for the best possible shareholder returns. Granger offers all the features with none of the strings attached to you or your wallet; the product never gets worse to make someone else money.

**Retired:** "Civilization, backed up." It frames Granger as an archive for after the end and puts fear first.
**Tagline (chosen 2026-10-27):** *The world's knowledge. Yours to keep.* It says what Granger is in one breath: the scale ("the world's knowledge"), ownership first ("yours") and the granary and the book-keepers ("to keep"). It sits under the lockup, on Welcome (as two lines) and in About.
**Mission line:** *Knowledge is a human right.* It says why Granger exists, not what it is, so it opens About, the manifesto, the non-profit and grants side and Basecamp, never the logo. (It sits close to Wikimedia's and the Internet Archive's slogans, so it stays a belief, not a slogan.)
**Description, where there's room:** name the content, not a rival: *"Encyclopedias, maps, manuals, books and your own music, with a reader and a librarian you can ask: all on your phone, all working with no signal."*
**Never name Google** (or any search engine or streaming service) in the brand. In comparisons and press, "the internet's reference shelf, without the internet" makes the point without tying Granger to a competitor.
**Passed over:** *Knowledge you keep.* (no scale); *Knowledge sovereignty* ("sovereign" carries the sovereign-citizen and crypto crowds, and "data sovereignty" is enterprise jargon and an Indigenous movement's own term; the idea stays in body copy: "your library answers to you"); *Everything you'd ask the internet. No internet.* (overclaims).
**Retired with the stencil:** *Everything worth knowing, yours to keep.*, the H3 Welcome line.

## 4. The fair deal

Granger's promise, published in About, the store listing and the site, and used as the test for every product and pricing decision:

1. **What you download is yours.** It works offline, forever, without an account.
2. **Knowledge is never paywalled.** No cap on what you can download, no paid content, nothing safety-critical behind a purchase.
3. **No subscription for the app.** Pay once if you choose to; the app never stops working.
4. **Nothing phones home** without an opt-in you can inspect (the v1 design's opt-in telemetry, off by default).
5. **Open source, GPL-3.0.** If Granger the project disappears, Granger the app does not.
6. **No dark patterns.** No nags, countdowns, or features shown to you as yours when they aren't (the Park anti-goal, generalised).
7. **Your own collection is first class.** Importing your music, audiobooks and e-books is free, and nothing is converted into a format only Granger can read.

## 5. The product family: field unit and Basecamp

The expedition metaphor from the concept art becomes the product architecture.

| | Granger (the field unit) | Granger Basecamp |
|---|---|---|
| **What** | The Android app: library, readers, players, Research, voice | A home server on your Wi-Fi |
| **Holds** | What fits the phone: the Efficient/Comprehensive tiers, small models | The whole Kiwix catalog, your full media collection, larger models on the host's compute |
| **Serves** | You, anywhere | The app at full quality on home Wi-Fi, and any computer through a browser |
| **Offline** | Always | Always (LAN only; no cloud relay required) |

Rules that keep both true to the fair deal: the app never needs Basecamp; Basecamp never needs the internet after setup; Basecamp software is free to self-host, and what's sold is a ready-made box. Naming stays plain: "Granger" and "Granger Basecamp"; institutional builds are "Granger for <org>" if needed.

## 6. How Granger earns

Charge for convenience, hardware, service and support, never for access to knowledge.

| Path | What's paid | Fit with the fair deal |
|---|---|---|
| **Basecamp** | A preloaded, configured home server (hardware + the full library + setup) | People pay for the box and the labour; the software stays free. The largest lever and the most operations (stock, support, returns). |
| **Institutions** | Custom builds, preloaded fleets, support contracts for schools, rural and prison libraries, ships, NGOs, disaster response | Organisations pay; individuals don't. Where "good for everyone" and income line up. |
| **Supporter (one-time, Play)** | Extras that aren't knowledge: themes, additional voices, early features, a supporter badge | Pay once. F-Droid/direct stays free with a "Support Granger" link. |
| **Grants and donations** | Open-source and digital-commons funders; a donate link | Buys time without strings. |

**What this changes in Plan 10:** the 2026-10-15 spec's Pro (unlimited library + import) no longer fits: the cap is already gone (2026-10-19), and paywalling import of your own books contradicts "what's yours is yours". Plan 10 is rewritten as **Supporter**: one non-consumable, extras only, import free for everyone. The F-Droid flavour is the same app with the purchase swapped for a link.

**Honest expectation:** a one-time purchase in a niche app pays for little on its own. Basecamp and institutions are the income; the app is the reason anyone wants them.

## 7. Voice and tone

**Who it's for:** a modern renaissance generalist. They think critically, solve problems, and value independent access to knowledge because it lets them build, fix, grow, invent, create, teach and learn. Cut off from everything, they're Robinson Crusoe with a library: resourceful, not frightened.

**The attitude:** self-reliant with a punk streak. Granger refuses to outsource what you know or to rent back what should be yours, and says so plainly. The defiance aims at the tether, never at the person: someone on Spotify today is a future user, not a fool.

**The tone:** a capable friend who has read everything. Direct, dry, a little irreverent, generous with what they know.

- **Say:** yours, keep, carry, build, fix, make, learn, works anywhere, no strings, cites its source.
- **Avoid:** collapse, apocalypse, survive the end, backed up civilization; unlock, premium, upgrade to continue; manufactured urgency; sneering at people.
- **Sounds like:** "No account. No subscription. No kidding." · "Works with no signal. First aid and water treatment are in Medicine and Survival." · "Your library. Not a licence to it."
- Every claim is literal. "Offline" means offline; "yours" means it outlives the store.

## 8. Visual direction: field kit

**Source:** the field recorder (`docs/superpowers/brand/2026-10-23-field-recorder-concept/granger-field-recorder-concept.png`): a hand-built player on a workbench at the edge of camp, gunmetal and teal enamel, a mix tape in the deck, a command line glowing on its screen, the world's knowledge spread around it (books from Homer to Achebe, seed packets, a soldering iron, a sextant, a compass, a cuneiform tablet, a West African mask, Hokusai's wave). Crafted, repaired, self-reliant; warm from the lantern, not from fire. It replaces the first personal-player concept (`2026-10-23-personal-player-concept/`), which was too ornamental and too blue. It is a reference, not an asset to ship: it is generated, and it shows other companies' marks (the cassette brand, the lantern maker). Don't use the word "Walkman" (Sony's mark).

The prompt that made it (Neal's revision):

> Product photograph of a rugged, hand-built personal media player/field recorder about the size of a paperback, deep navy enamel with brass hardware, scuffed and repaired: a cassette deck window with a mix tape, a small bank of toggles with a small strip of duct tape blocking one off, a worn leather camera strap, a salvaged circuit board visible through a small window, a few buttons from a vintage graphing calculator, small future-retro commandline output display. Wired over-ear headphones. Built onto and around it, a tactile diorama of human knowledge from every culture and era: a cuneiform tablet, a well-thumbed field guide, a compass, a soldering iron and a spool of solder, seed packets, a sextant, a sketchbook of inventions, paperbacks with cracked spines (one with a singed corner), a West African mask, a Japanese woodblock wave, an astrolabe. It rests on a workbench at the edge of a wilderness camp at dusk, lantern lit. The word GRANGER is stencilled on the body. Warm, crafted, defiant, self-reliant. Not glossy, not post-apocalyptic, no fire, no ruins.

**What carries into the brand:**
- **The command line.** The screen's `> ready.` is Granger's status voice: short lowercase lines in mono, phosphor on dark ("> offline. ready.", "> 3 sources."). For state, never for content.
- **The stencil** (retired from the wordmark 2026-10-27). The owl's wing cuts already carry it; a stencilled word beside them doubled the cuts and read as army surplus. Stencil stays available for kit and merch, never the lockup.
- **Hardware controls.** Labelled toggles and knobs (MUSIC, FIELD, ARCHIVE, OFFLINE) suggest how modes and the rail can look: physical, labelled, few.
- **The mix tape.** Your own collection sits in the deck, at the centre of the device.
- **Every culture and era, and the tools to use them.** Knowledge you act on, not knowledge on display.

**Palette: field kit** (measured from the image, checked for contrast; preview `docs/superpowers/brand/2026-10-23-field-recorder-concept/granger-palette-field-kit.png`):

| Token | Hex | Use | Contrast |
|---|---|---|---|
| Enamel | `#2E4A45` | Brand colour; Field primary | Paper on enamel 7.6:1 |
| Gunmetal | `#24292A` | The device; Night surfaces | Paper on it 11.7:1 |
| Night | `#181C1D` | Night background | Paper on it 13.6:1 |
| Field paper | `#EFE4D0` | Field background | — |
| Ink | `#1F1813` | Body text on paper | 13.9:1 |
| Lantern | `#F0A35E` | Night primary, play | 8.3:1 on Night; ink on lantern 8.4:1 |
| Phosphor | `#91C4BA` | Status lines and labels on dark | 8.8:1 on Night |
| Worn brass | `#A87B52` | Fills, icons, rules; not text on paper | 3.0:1 on paper |
| Leather | `#5B3322` | Secondary accent | 8.6:1 on paper |
| Dusk | `#354059` | Maps, a cool counterweight | 8.2:1 on paper |

**Revised 2026-10-27, after the phone walkthrough** (Neal: the first set read a little "national park sign" and hadn't caught up with the owl; options A, B and C compared on the app's own screens in `docs/superpowers/brand/2026-10-27-palette/`; he chose A's Field with C's Night):

| Token | Hex | Use | Contrast |
| --- | --- | --- | --- |
| Night ground | `#12211E` | Night background: the launcher's enamel, deepened, so app and icon are one family | Paper on it 13.2:1 |
| Night cards | `#172A26` · `#1C322E` · `#233B36` | Night containers, low to high | — |
| Field paper | `#F3EEE4` | Field background: lighter, less yellow | — |
| Field cards | `#F0EADF` · `#E8E1D3` · `#E1D9C9` | Field containers, low to high | — |
| Graphite | `#3A4243` | Field secondary (replaces leather) | — |
| Burnt lantern | `#8A4F1C` | Field tertiary (replaces dusk) | Paper on it 5.2:1 |
| Field play | `#94531E` | Play buttons and progress in Field: lantern deepened to read on paper | Paper on it 4.7:1 |

Lantern `#F0A35E` stays Night's primary and play colour and the owl's eyes everywhere; enamel stays Field's primary and the launcher ground. The tokens above are `FieldColors` and `NightColors` in `app/theme/GrangerTheme.kt`.

Two themes, both first class: **Field** (light: paper and enamel, for daylight) and **Night** (deep enamel, lantern and phosphor). The app's current near-black `#0E0E0E` and flame orange `#E8601C` retire, as does the first concept's navy and parchment.

**Type:** Spectral (OFL) for reading and headings; Courier Prime (OFL) for labels, counters and the command line; the wordmark is Spectral SemiBold capitals, spaced 0.12 em (chosen 2026-10-27 over Big Shoulders, Barlow, Chakra Petch, Archivo and Space Grotesk: the books and the brand share one voice, and the plain owl beside it carries the edge). All bundled, never downloaded.

**The mark (chosen 2026-10-27): the owl in the G.** A stern, flat owl sits inside a capital G whose lower curve is three wing bands, with a square spur. It reads as a guardian of the store that keeps watch at night: the granary story (§2) told in one picture. Neal drew it in ElevenLabs from the cassette mark, then picked one of his sketches (`docs/superpowers/brand/2026-10-27-owl-mark/`). It was traced and cleaned as geometry, not redrawn by hand:
- the rim and the crescent's inner edge are true circles fitted to his lines;
- each cut between the bands follows arcs fitted to its own edges;
- the head, eyes and pupils are mirrored about the beak.

The pipeline is reproducible, in `redraw/`. It replaces the first mark (2026-10-26), a cassette whose right half was a G, resting on two books (`2026-10-26-mark-sketches/`). That mark said what Granger holds; the owl says why it exists. The music side now lives in the product and the copy, not the mark.

Colours:
- **The eyes are the lantern.** Lantern whites with dark pupils, in both themes. The mark has three layers (eyes, pupils, body), so the eyes can glow while the body takes the theme colour.
- **Night:** a paper owl.
- **Field:** an enamel owl.
- **Launcher (option C):** a paper owl with lantern eyes on enamel.
- **Themed icon:** one colour, eyes cut out.

`tools/brand/make_drawables.py` writes all of these, and the wordmark (Spectral SemiBold caps, drawn as paths). Night is the brand's face: lead store art and Welcome with it.

Rules for the owl:
- **A mark, not a mascot.** It doesn't talk, wave, wear things or become the librarian's avatar.
- **Keep it angular, stern, flat and one colour.** Never green, never round, never cute. Duolingo owns the friendly app owl.
- **No star or sparkle beside it.** A four-point sparkle reads as the generic AI icon, and a star in the crescent's mouth echoes the star and crescent.
- **The notification icon is the owl alone** (2026-10-27): the G dropped, eyes cut out with the pupils in them, 20 dp tall in the 24 dp canvas (`media3_notification_small_icon`, which replaces Media3's music note on the playback notification). The full mark holds at 24 px, but in the status bar the owl shrinks to a smudge. Downloads keep Android's own download arrow. The perch sketch stays in reserve.

**The brief it answered:**
- Must read at 48 px as a launcher icon and as Android's monochrome themed icon.
- Says *a thing you own and carry that holds the world's knowledge*. Directions: the field recorder's front reduced to a cassette window and one knob; a stencilled G cut like a toggle slot; a granary silhouette; a book opening its pages like wings.
- No literal flame, no Vitruvian figure in a circle (both earlier drafts are retired: the flame reads as catastrophe, the figure as a peace sign at small sizes).
- Lockup: the mark with GRANGER in the stencil; the tagline in Courier Prime. (Superseded 2026-10-27: GRANGER in Spectral caps; the tagline in Spectral.)

## 9. What the brand asks of the UI

Principles for the UI pass once the roadmap's features land; each traces to the fair deal or the positioning.

1. **Show what's yours.** Library and Readiness read as a shelf you own (counts, sizes, what's covered), not a store you browse.
2. **Honest state.** Never show something as available that isn't on the device; downloads, flavours and models say exactly what's present.
3. **No dead ends offline.** Every screen works, or says plainly what needs a network and why; no spinners waiting for a server that isn't there.
4. **Field-ready.** One hand, bright sunlight (Field theme), low battery, gloves: large targets, high contrast, the voice for eyes-busy use.
5. **Curiosity first on Home.** Feeds open with something worth reading from anywhere in the world, not alerts.
6. **Calm about emergencies.** Medicine and Survival are a tap from Home and Readiness without sirens, red, or countdowns.
7. **No upsell in the path of knowledge.** Supporter appears in Settings and About only.

## 10. What this unblocks, in order

1. **Claude Design:** the mark and lockup (done 2026-10-26, from Neal's sketch); the tagline and wordmark, and the palette (done 2026-10-27).
2. **H3 Task 5 (done 2026-10-26):** `BrandLockup` takes the chosen mark and lockup; the Welcome line is the vision sentence (§1); the launcher icon (and with it the system splash) changes with it.
3. **Theme pass (Plan T1, done 2026-10-27):** `GrangerTheme` gains Field and Night from the palette; the reader and players follow.
4. **Plan 10 rewrite:** Supporter instead of Pro (§6); billing + the telemetry opt-in slot into onboarding.
5. **Plan 11 / store listing:** the fair deal, the Grange story, screenshots in the new theme.
6. **Basecamp:** its own spec when the app's roadmap is done; this brief fixes its name and rules only.

## 11. Open

- The supporter price and exactly which extras it carries (Plan 10).
- Organisation form for income: sole proprietor, LLC, or a public-benefit company with a nonprofit partner for grants (needs advice).
- Basecamp hardware: off-the-shelf mini PC vs a single-board computer; the model it runs; whether institutions get it first.
- Whether to retire `p1-brand` (the Vitruvian draft) now that §8 rules it out.
