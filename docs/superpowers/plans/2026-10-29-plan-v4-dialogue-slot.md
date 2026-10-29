# Plan V4b: The dialogue slot — each British voice learns how its teacher reads quoted speech

> **For agentic workers:** continues V4 on branch `v4-teacher-voice` (worktree `.claude/worktrees/v4`); the app half goes on `v4-voice-settings` (worktree `.claude/worktrees/v4-settings`, off `main`). Work dir `voice-train-2026-10-22/v4/`. Steps use checkbox (`- [ ]`) syntax. Licence rules as V4: Qwen3-TTS (Apache-2.0) is the only teacher, public-domain Gutenberg and LibriTTS (CC BY 4.0) the only texts, nothing non-commercial in training.

**Why.** Neal on the round-2 voices (2026-10-29, on the phone): the sound is acceptable; the delivery falls far short of the references. The clearest loss: the Nanny's reference reads `"Can you hear that?" she asked` like a storyteller, the quote higher and quicker with its question lift, and the Professor's too; the trained voices read it flat. Faking it in the app (a quicker, pitched-up quote) was rejected: chipmunk, and it misses the question's inflection.

**Why the model can't learn it now.** It never sees the quotes. Piper (training) and sherpa-onnx (phone) both phonemize with espeak, which drops `"` `“ ”` `« »` `( [` `—` and spells out `* ~ #` (tested 2026-10-29), so `"Can you hear that?" she asked` reaches the model as the same phonemes with or without quotes. No in-band marker survives without patching sherpa's vendored AAR, and later the iPhone build.

**The idea.** Each voice becomes the same person in two speaker slots: **0 narration, 1 dialogue.** Training clips are cut at the quote marks, so slot 1 learns from the teacher's real dialogue readings (pitch, pace, inflection) and slot 0 from the narration. On the phone the app splits each sentence at the quotes and asks sherpa for slot 1 inside them. sherpa takes a speaker id on every call, so no engine change is needed. The pilot's accent bleed came from two different people sharing a model; here both slots are one voice, so bleed is harmless.

**Goal.** `vits-piper-en_GB-granger_nanny-medium` and `…_professor-medium`, each with two slots, where Neal hears the quoted lines in "After the rain" and in a dialogue-heavy passage from a novel as clearly voiced, without a jolt at the joins, at RTF ≤ 0.5 with the screen off on the Pixel 6. They replace the round-2 voices under a new voice id.

## Facts to start from

- **Round-2 data has too little dialogue:** 140 Nanny lines and 142 Professor lines with quotes, about 6 min of quoted speech each out of ~170 min. The aim is 25–30 min of dialogue per voice.
- **Teacher:** audio.cpp v0.9.2 on the Mac, `qwen3-clone`, library voices `NorthLondonNanny` / `SouthLondonProfessor`, RTF about 1.15. `generate.py` with `--take` and no `--tail` (the Nanny runs the tail into passages; round-2 lesson).
- **Word timings:** mlx-whisper turbo in `~/.venvs/granger-asr` with `word_timestamps=True`. The quote boundaries are punctuation, which Whisper's words carry (`"Can`, `that?"`), so cut points come from aligning the script's words to Whisper's.
- **Sources:** `v4/gutenberg/` (Persuasion, The Wind in the Willows, Three Men in a Boat, Cranford, North and South), plus more PD novels heavy in dialogue if needed. Some use single quotes for speech; `make_script.py`'s `good_passage()` rejects single-quoted speech today.
- **Training:** piper1-gpl, multi-speaker from `lj-med_1000.ckpt` (the pilot's recipe), GRANGER_ESPEAK=en-gb-x-rp, 4090 pods, about 34 s an epoch for ~3 h of data. **Keep one full checkpoint** this time (the volume was deleted 2026-10-29 and only slim ones survived).
- **App:** `SherpaSpeech` calls `tts.synthesize(spoken(text), speaker = …)` once per sentence (`maxNumSentences = 1`). Speaker names come from `speaker_id_map` (`SherpaVoice.speakerNames`), which the voice picker lists, so the dialogue slot must be hidden from it. Per-voice sampling already comes from the `.onnx.json` `inference` block (`v4-voice-settings` e5d8e96).

## Decisions (defaults; Neal can change them)

1. **Two slots per voice, one model per voice**, not one model with four slots: the round-2 split is what fixed the bleed.
2. **The slot is named in the voice's config, not by convention:** `"speaker_id_map": {"narration": 0, "dialogue": 1}` and `"granger": {"dialogue_speaker": 1}`. The picker hides voices' dialogue slots, and voices without the key read as now.
3. **What counts as dialogue:** text between double quotes (`"…"`, `“…”`) for now; single quotes later, once telling them apart from apostrophes is solved and tested (`'tis`, `the Smiths'`).
4. **Short narration fragments stay:** `she asked.` between two quotes is a slot-0 clip if it is at least 0.6 s, and is dropped otherwise.
5. **The same sampling for both slots** (setting e, 0.75 / 1.0), until listening says otherwise.

## Tasks

### Task 1: Prove the cut on a handful (Mac only, about an hour)

- [ ] `make_script.py --dialogue`: passages with at least 40% of their characters inside double quotes, 2–5 sentences, quotes balanced inside the passage; excludes lines already used (`--exclude script script2`).
- [ ] 20 passages per voice through `generate.py` (no tail, takes 0–2).
- [ ] `cut_quotes.py <raw dir> <script> <out>`: Whisper words with timings, aligned to the script's words, so each quote span gets start and end times; cut in the silence nearest each boundary (search ±150 ms for the lowest-energy 20 ms), 30 ms fades; each piece gets its slot and its own text, quotes stripped.
- [ ] QC every piece with `qc.py`'s checks (WER on the piece's own text, length, clipping, clean ending).
- [ ] **Neal listens** to a dozen cut dialogue pieces per voice next to their source passages. The questions: does the voicing survive the cut, and are the edges clean? Stop here if not.

### Task 2: Generate the dialogue set

- [ ] About 400 dialogue passages per voice (aim: 25–30 min of quoted speech after QC), on the Mac overnight, with retakes for failures as in round 2.
- [ ] Cut, QC, and fold in round 2's 140 quoted lines, cut the same way.
- [ ] Build `dataset/<voice>-2slot/`: all round-2 narration plus the new narration pieces as `narration`, all dialogue pieces as `dialogue`. Record the minutes per slot.

### Task 3: Train

- [ ] Two pods, a fresh volume, upload both sets (~1 GB). `pod_finish.sh` also keeps the best full checkpoint, rsynced before the pod is removed.
- [ ] 800 epochs from `lj-med_1000.ckpt`, two speakers. Listen to the e400 snapshot early (both slots, "After the rain" split by hand) before letting it run to the end.

### Task 4: The app reads quotes in the dialogue slot (`v4-voice-settings`)

- [ ] `QuoteSpans`: splits one sentence into `(text, inQuote)` pieces, carrying whether a quote is open from one sentence to the next within a paragraph (`"I'll go. You stay." He left.`). Unit tests: quote at the start, in the middle, at the end, across sentences, unbalanced quotes (treated as narration from the stray mark on), curly and straight quotes.
- [ ] `SherpaVoice.dialogueSpeaker(voice)` from the `granger` block; `speakerNames` leaves that slot out of the picker.
- [ ] `SherpaSpeech`: when the voice has a dialogue slot, synthesize each piece with its slot and join the audio with no added pause (the cut's own silence is the gap). Highlighting and resume still go by sentence; nothing changes for other voices.
- [ ] Tests green; build on top of whatever is on the phone (see "The phone"); Neal listens through the reader on a novel.

### Task 5: Numbers and decision

- [ ] RTF on the Pixel 6 screen off through the real player (two calls per quoted sentence cost a little), and first-audio time.
- [ ] Neal's verdict against the round-2 voices and the references. If yes: model card (both slots, the Qwen3 teacher, Gutenberg/LibriTTS sources), new voice ids, catalog entry later with V2's Task 3.

## The phone

Shared with the U3c walkthrough. On 2026-10-29 the installed build was `v4-phone-test` (u3c-reading 54c03a7 + the voice settings change), with the app's data backed up to `backups/phone-2026-10-29-v4/`. Before installing, check what is on it (`dumpsys package app.granger` install time against the worktrees' APKs), build on top of that branch, and back up first. One app only, `app.granger`. Pushing a voice folder needs no install.

## Not this plan

- **KittenTTS** (StyleTTS2 family, Apache-2.0): checked 2026-10-29 for its delivery. Pixel 6 RTF screen on, 2 threads: nano 0.20, micro 0.39, mini 0.82, against our Piper's 0.10; only nano fits screen off. No training code is public (custom voices are KittenML's paid service), so it can't take our voices; it's evidence for what the StyleTTS2 family's delivery buys, if Neal's ear says it is better.
- Piper high: 8× medium's cost on the Pixel (RTF 0.99 on, 1.38 off), out.
