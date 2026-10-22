# Voices

How a read-aloud voice gets into the catalog (Plan V1, Task 8).

1. **Pack it.** In an empty directory (downloaded archives are untrusted input):

   ```
   python3 -I tools/voices/pack_voice.py vits-piper-en_US-ljspeech-medium --out /tmp/voices
   ```

   The script fetches sherpa-onnx's `tts-models` release archive, keeps the model, `tokens.txt`, `espeak-ng-data/`,
   `MODEL_CARD` and `LICENSE`, writes `manifest.json` (every file's size and sha256, which Readiness checks after the
   zip is unpacked and deleted) and produces `<id>.zip` deterministically, so the sha256 is the same on every run. It
   prints the catalog's `sizeBytes`, `sha256` and `unpackedBytes`.

2. **Host it.** Upload the zip as an asset of the `voices-v1` release on `nealt9000/Granger`
   (`https://github.com/nealt9000/Granger/releases/download/voices-v1/<id>.zip`). The asset must not change once a
   catalog names it; a new voice version is a new id.

3. **List it.** Add the entry to `tools/catalog-gen/curated.json` under `voices` (`id, title, engine, language,
   speakers, sampleRate, sizeBytes, unpackedBytes, sha256, url, license, attribution`), then rebuild and sign the
   catalog as `tools/catalog-gen/catalog_gen.py`'s docstring says. Older apps ignore the `voices` array.

4. **Licence first.** Only voices trained from scratch on permissive data ship. `ljspeech` is public domain.
   `libritts_r`, `arctic`, `ryan`, `amy` and `hfc_*` are fine-tuned from `lessac` (Blizzard 2013, non-commercial)
   and do not (`docs/superpowers/spikes/2026-10-voice-spike-results.md`).

On the phone the zip lands in `voices/<id>.zip`, is verified by the download ledger, unpacked into `voices/<id>/`
(`VoiceUnpacker`), and the zip is deleted. Settings → Voice lists it; Readiness checks it against the manifest.

The first packed voice: `vits-piper-en_US-ljspeech-medium.zip`, 67,456,670 bytes,
sha256 `3013e3af7bd4c3d35188a93b8a3ef4ebf26e9946a54292e10f3f961ecc0ac23b`, 81,529,587 bytes unpacked.
