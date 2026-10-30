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

- [x] `make_script.py --dialogue`: passages with at least 40% of their characters inside double quotes, 2–5 sentences, quotes balanced inside the passage; excludes lines already used (`--exclude script script2`).
- [x] 20 passages per voice through `generate.py` (no tail, takes 0–2).
- [x] `cut_quotes.py <raw dir> <script> <out>`: Whisper words with timings, aligned to the script's words, so each quote span gets start and end times; cut in the silence nearest each boundary (search ±150 ms for the lowest-energy 20 ms), 30 ms fades; each piece gets its slot and its own text, quotes stripped.
- [x] QC every piece with `qc.py`'s checks (WER on the piece's own text, length, clipping, clean ending).
- [x] **Neal listens** to a dozen cut dialogue pieces per voice next to their source passages. The questions: does the voicing survive the cut, and are the edges clean? Stop here if not. *Done 2026-10-29: 104 dialogue / 102 narration pieces from 37 passages × 3 takes; Neal: the pieces hold up alone and together, edges clean. Teacher's dialogue vs narration: Nanny +1.4 semitones and a 40% wider range; Professor about the same pitch, range +10%.*

### Task 2: Generate the dialogue set

- [x] About 320 dialogue passages per voice (`script3`, ~31 min quoted each; 15 Gutenberg books incl. Treasure Island, Peter Pan, The Railway Children, Five Children and It, Alice; Neal's Mary Poppins is in copyright), `v4/round3.sh`. Was: about 400 dialogue passages per voice (aim: 25–30 min of quoted speech after QC), on the Mac overnight, with retakes for failures as in round 2.
- [x] Cut and QC (take 0, then takes 1–2 for lines with a failed piece). Round 2's 140 quoted lines are left out of narration rather than cut: the new pieces already reach the target. *Done 2026-10-30 00:09; most WER failures are names Whisper misspells (Pumblechook, Mavis).*
- [x] Build `dataset/<voice>-2slot/`: all round-2 narration plus the new narration pieces as `narration`, all dialogue pieces as `dialogue`. Record the minutes per slot. *Nanny: narration 1915 clips / 172.5 min, dialogue 484 / 29.6 min (515 MB). Professor: narration 1770 / 151.6 min, dialogue 417 / 25.2 min (451 MB).*

### Task 3: Train

- [ ] Two pods, a fresh volume, upload both sets (~1 GB). `pod_finish.sh` also keeps the best full checkpoint, rsynced before the pod is removed.
- [x] 800 epochs from `lj-med_1000.ckpt`, two speakers. Listen to the e400 snapshot early (both slots, "After the rain" split by hand) before letting it run to the end. *2026-10-30: the volume filled at ~e475/e531 (full 0.9 GB snapshots every 25 epochs, two runs on one 50 GB volume); slimmed, resumed into version_1, a pod-side slimmer now slims each snapshot. e400 (`v4/listen-e400/`, `say.py --dialogue`): the dialogue slot learned a pitch level (Nanny +1.1 st, teacher +1.4; Professor +1.3 st, teacher −0.3), not pace, range or inflection. **Neal: deadpan straight through, no progress toward the goal.** Runs left to finish (~$3) for the full checkpoint; pods removed by the watchers.*

### Task 4: The app reads quotes in the dialogue slot (`v4-voice-settings`)

- [x] `QuoteSpans`: splits one sentence into `(text, inQuote)` pieces, carrying whether a quote is open from one sentence to the next within a paragraph (`"I'll go. You stay." He left.`). Unit tests: quote at the start, in the middle, at the end, across sentences, unbalanced quotes (treated as narration from the stray mark on), curly and straight quotes.
- [x] `SherpaVoice.dialogueSpeaker(voice)` from the `granger` block; `speakerNames` leaves that slot out of the picker.
- [x] `SherpaSpeech`: when the voice has a dialogue slot, synthesize each piece with its slot and join the audio with no added pause (the cut's own silence is the gap). Highlighting and resume still go by sentence; nothing changes for other voices. *`v4-voice-settings` 6ba0b60 (rebased on main 9479962).*
- [ ] Tests green; build on top of whatever is on the phone (see "The phone"); Neal listens through the reader on a novel.

### Task 5: Numbers and decision

- [ ] RTF on the Pixel 6 screen off through the real player (two calls per quoted sentence cost a little), and first-audio time.
- [ ] Neal's verdict against the round-2 voices and the references. If yes: model card (both slots, the Qwen3 teacher, Gutenberg/LibriTTS sources), new voice ids, catalog entry later with V2's Task 3.

## Result (2026-10-30)

**The dialogue slot doesn't buy delivery; the round-2 voices ship as they are.** The slot learned a pitch level for quoted speech (Nanny +1.1 semitones against the teacher's +1.4; Professor +1.3 where the teacher went −0.3) but not the pace, range or inflection that make a storyteller, and Neal heard the e400 voices as deadpan straight through. The cause is the model, not the data: Piper's VITS medium learns one average reading for a text, and the teacher's many readings average to flat; the slot only gives quotes a second average. KittenTTS (the StyleTTS2 family, which predicts a style per sentence) reads just as flat to Neal and slightly behind our round-2 voices, so a small StyleTTS2 student isn't the way out either. On-device delivery waits for a model that predicts it from the text and still runs at RTF ≤ 0.5 on the Pixel; the Qwen3 teacher pipeline is ready to retry when one appears.

**What ships:** `vits-piper-en_GB-granger_nanny-medium` (e735, val_mel 0.3003) and `…_professor-medium` (e789, 0.3172), round 2, single speaker, setting e (noise 0.75, noise_w 1.0) in their `inference` block, which the app reads (`v4-voice-settings`). Re-exported with V4's model card (`export_voice --teacher`, aac394f3); the ONNX models are byte-identical to the ones Neal used on the phone. Zips (67 MB each) in `v4/ship-zips/`; `curated.json` entries on `v4-voice-settings`.

**What stays, dormant:** the app's dialogue-slot code (a voice without a `granger.dialogue_speaker` reads as before), `cut_quotes.py`, `build_slots.py`, `say.py --dialogue`, and the two-slot runs' checkpoints in `runs-pod/<voice>-2slot/`.

**Lessons for the next pod run:** one network volume for two runs fills with full 25-epoch snapshots (0.9 GB each): slim them on the pod as they land (`/root/vt/slimmer.sh`). After the first few epochs a 4090 does ~17–19 s an epoch on ~200 min, not 35–39. The pod's own `RUNPOD_API_KEY` can't remove pods; Neal's key (`runpodctl config --apiKey`) can.

## The phone

Shared with the U3c walkthrough. On 2026-10-29 the installed build was `v4-phone-test` (u3c-reading 54c03a7 + the voice settings change), with the app's data backed up to `backups/phone-2026-10-29-v4/`. Before installing, check what is on it (`dumpsys package app.granger` install time against the worktrees' APKs), build on top of that branch, and back up first. One app only, `app.granger`. Pushing a voice folder needs no install.

## Not this plan

- **KittenTTS** (StyleTTS2 family, Apache-2.0): checked 2026-10-29 for its delivery. Pixel 6 RTF screen on, 2 threads: nano 0.20, micro 0.39, mini 0.82, against our Piper's 0.10; only nano fits screen off. No training code is public (custom voices are KittenML's paid service), so it can't take our voices; it's evidence for what the StyleTTS2 family's delivery buys, if Neal's ear says it is better. *Neal 2026-10-30: flat straight through on all voices, slightly behind our round-2 voices on the phone; so a small StyleTTS2 student is not expected to fix delivery either.*
- Piper high: 8× medium's cost on the Pixel (RTF 0.99 on, 1.38 off), out.
