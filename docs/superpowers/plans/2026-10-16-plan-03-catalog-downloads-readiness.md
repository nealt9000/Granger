# Granger Plan 3: Catalog, Downloads, Readiness — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Get packs and models onto the phone from inside the app: a signed catalog we host on GitHub, resumable verified downloads to internal storage or an SD card, a Downloads screen with a storage meter and the Starter preset, and a Readiness screen that checks every installed file.

**Architecture:** `tools/catalog-gen` (Python) turns a curated list into `catalog/catalog.json` using Kiwix's OPDS feed and metalink files, and signs it with Ed25519; the repo serves it from `raw.githubusercontent.com`. `:core:catalog` fetches, verifies (BouncyCastle), caches and parses it, and falls back to the last good copy. `:core:downloads` holds a Room ledger of downloads (`DownloadRecord`, keyed by item id + sha256), a pure `FileDownloader` over OkHttp (HTTP Range resume, mirror fallback, streaming sha256, free-space checks), a `DownloadRunner` that installs or fails a record, and a thin WorkManager `DownloadWorker` with a foreground notification. `:core:library` learns each pack's ZIM `Name` so a new snapshot inherits the old one's bookmarks and reading positions. `:app` adds the Downloads and Readiness screens, whose ViewModels depend only on interfaces (`CatalogSource`, `Downloads`, `VolumeSource`, `DownloadPrefs`, `Library`) and are tested with fakes.

**Tech Stack:** Existing: Kotlin 2.4.21, AGP 9.4.1, Compose BOM 2026.09.00, Room 2.8.5 + KSP 2.3.12, kotlinx-serialization 1.11.0, Robolectric 4.17, JUnit 4.13.2, Truth 1.4.5, coroutines 1.11.0. New: OkHttp 5.5.0, WorkManager 2.12.0 (`work-runtime-ktx`), BouncyCastle `bcprov-jdk18on` 1.86, `room-testing` 2.8.5. Tooling: Python 3.12, `cryptography` 50.0.2, `pytest` 8.4.2.

**Spec:** `docs/superpowers/specs/2026-10-15-granger-v1-design.md` (build-order milestone 3; sections "Catalog", "Downloads", "Readiness", "Storage cap", "Error handling", "Tests"). Roadmap, including the two items carried from Plan 2's review: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`.

## Global Constraints

- Android 10+ → `minSdk = 29`; `compileSdk = 37`; `targetSdk = 36`. Source in `src/<set>/kotlin/`. Package root `app.granger`.
- Kotlin + Jetpack Compose. GPLv3. No new dependency with a GPL-incompatible license (OkHttp, WorkManager, BouncyCastle: Apache-2.0 / MIT-style).
- **We host only a signed catalog JSON; packs come from Kiwix mirrors, models from Hugging Face** (spec "Hosting").
- Catalog: **a single JSON file at a fixed URL with a detached Ed25519 signature; the public key is compiled into the app. Invalid signature or fetch failure → use the last good cached catalog.** URL: `https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json` (signature at the same URL + `.sig`).
- Pack entry fields: `id, title, category, language, sizeBytes, sha256, urls[], snapshotDate, license, hasFulltextIndex, topics[]` (plus `description`, `zimUuid`, `zimName`, added by this plan). Model entry fields: `id, role (small|large|embedder), sizeBytes, minRamGb, sha256, url, license, evalPassed` (plus `title`, `fileName`). **Release builds list only models with `evalPassed = true`.**
- Content: public domain or commercially redistributable licenses only. iFixit (CC BY-NC-SA) is therefore left out of the catalog.
- Downloads: **WorkManager with a foreground notification; HTTP Range resume; Wi-Fi-only by default (user can override).** **Write to `*.part`, compute sha256 while streaming, verify, atomic rename, then register in the library. Unverified files are never registered.**
- **Checksum mismatch → retry once, then mark failed with a Retry button; never registered. Insufficient space → checked before queueing and before each chunk write. Download interrupted → auto-resume.**
- Volumes from `Context.getExternalFilesDirs()` (internal + SD card, no storage permissions). **The UI notes that uninstalling Granger removes downloaded files.** Storage meter per volume: free space, Granger usage, queued size.
- Storage cap: **Free: installed packs + queued pack downloads ≤ 10 GB; models are excluded; checked when queueing; never deletes anything to enforce the cap.** Billing is Plan 8, so this plan implements the arithmetic with the cap supplied as `capBytes = Long.MAX_VALUE` (uncapped) until Plan 8 wires `:core:billing`.
- Readiness: **per pack and model: verified / incomplete / corrupted (size check always; full sha256 re-hash on request, with progress), plus snapshot date. Also free space per volume and the device tier. No battery estimate.**
- **The library always works.** No failure in catalog or downloads may crash the app or make installed packs unreadable.
- The reader must never touch the network: before adding `INTERNET`, set `blockNetworkLoads = true` and disable Safe Browsing on `ReaderWebView` (roadmap carry-over).
- English only; user-visible strings are plain Kotlin string literals in the screen and ViewModel files (i18n extraction is Plan 8).
- Never commit ZIM or model files, or the catalog **private** key. The private key lives at `~/.config/granger/catalog-signing.key` (mode 600). The RFC 8032 test key used for fixtures is public knowledge and may be committed.
- Every commit message ends with the line `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- Gradle commands use `./gradlew`; the dev machine has ~6 GB RAM (heap already lowered in `gradle.properties`). Python tools run in a venv under `/tmp` (as Plan 2 did for `tools/test-zim`).

## Review Focus

1. **A download interrupted at any point** (connection drop, app killed, reboot, Wi-Fi lost) resumes from its `.part` file and still verifies, because the bytes already on disk are re-hashed before appending. A mirror that ignores `Range` and answers `200` must restart the file, never append a second copy → `FileDownloaderTest.resumesFromPartFile`, `serverIgnoringRangeRestartsTheFile`, `connectionDropContinuesOnNextMirror`, `failedAttemptKeepsBytesForNextRun` (Task 6); `DownloadRunnerTest.stoppedWorkerGoesBackToQueued` (Task 8).
2. **A pack update** keeps bookmarks, reading positions and `addedAt`, and deletes the old file only after the new one verified → `RoomLibraryTest.updatedSnapshotKeepsBookmarksPositionsAndAddedAt`, `snapshotReplacedAtTheSamePathKeepsBookmarks`, `bothSnapshotsPresentStayApart` (Task 4); `DownloadRunnerTest.updateDeletesTheOldFileOnlyAfterVerifying`, `failedUpdateKeepsTheOldVersion` (Task 8).
3. **A bad or stale catalog** (tampered bytes, truncated JSON, missing `.sig`, an older signed catalog replayed, a future `formatVersion`, hostile ids like `../../x`, no network) never replaces the last good one and never reaches the file system → `CatalogParserTest` (Task 2), `CatalogRepositoryTest` (Task 3).
4. **Running out of space** before or during a download (SD card full, several queued downloads on one volume) fails that download with a clear reason instead of crashing or filling the disk to zero → `FileDownloaderTest.stopsBeforeTheDiskFills` (Task 6); `QueuePolicyTest`, `DownloadManagerTest.queuedDownloadsOnTheSameVolumeCount` (Task 7).
5. **Repeated or overlapping taps** (Get twice, Starter preset over items already installed or queued, Cancel while the worker is mid-write, WorkManager re-running a finished job) leave exactly one record and one file → `DownloadManagerTest.enqueueTwiceIsOneDownload`, `cancelDeletesPartAndRecord` (Task 7); `DownloadRunnerTest.rerunAfterInstallDoesNothing`, `cancelledRecordIsNotResurrected` (Task 8); `DownloadsViewModelTest.presetSkipsInstalledAndActiveItems` (Task 11).

---

## File Structure

```
Granger/
├── gradle/libs.versions.toml                 + okhttp, work, bouncycastle, room-testing
├── settings.gradle.kts                       + :core:catalog (Task 2), :core:downloads (Task 6)
├── catalog/                                   Published catalog (Task 10)
│   ├── catalog.json, catalog.json.sig
│   └── public-key.txt                         Base64 Ed25519 public key; compiled into the app
├── testdata/catalog/                          Cross-language fixture signed with the RFC 8032 test key (Task 1)
│   ├── test-catalog.json, test-catalog.json.sig
├── tools/catalog-gen/
│   ├── catalog_gen.py                         keygen | build | sign | verify
│   ├── curated.json                           The hand-picked packs, models and presets
│   ├── requirements.txt
│   └── test_catalog_gen.py
├── core/catalog/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/app/granger/core/catalog/
│       │   ├── Catalog.kt                     Catalog, PackEntry, ModelEntry, ModelRole, Preset, CatalogItem
│       │   ├── CatalogSignature.kt            Ed25519 verify (BouncyCastle)
│       │   ├── CatalogParser.kt               verify + parse + validate → ParseResult
│       │   └── CatalogRepository.kt           CatalogSource, CatalogState, fetch/cache/fallback
│       └── test/kotlin/app/granger/core/catalog/  TestCatalogs, FakeHttp, CatalogParserTest, CatalogRepositoryTest
├── core/library/                              (modified, Task 4)
│   ├── schemas/…/2.json                       generated
│   └── src/main/kotlin/app/granger/core/library/  Entities (Pack.name), Daos (move/delete per pack),
│                                                  LibraryDatabase (v2 auto-migration), Library (+remove), RoomLibrary
├── core/downloads/
│   ├── build.gradle.kts
│   ├── schemas/…/1.json                       generated
│   └── src/
│       ├── main/AndroidManifest.xml
│       ├── main/kotlin/app/granger/core/downloads/
│       │   ├── Sha256.kt                      hashFile, toHex
│       │   ├── FileDownloader.kt              DownloadRequest, DownloadResult, FileDownloader
│       │   ├── DownloadRecord.kt              ItemKind, DownloadState, DownloadRecord, file/partFile
│       │   ├── DownloadDatabase.kt            DownloadDao, Converters, DownloadDatabase
│       │   ├── Volumes.kt                     Volume, VolumeSource, AndroidVolumes
│       │   ├── QueuePolicy.kt                 EnqueueResult, QueuePolicy
│       │   ├── Downloads.kt                   Downloads, DownloadScheduler, DownloadManager
│       │   ├── DownloadRunner.kt              RunOutcome, DownloadRunner
│       │   ├── DownloadWorker.kt              DownloadsHost, DownloadWorker, WorkManagerScheduler, notifications
│       │   └── IntegrityChecker.kt            Integrity, IntegrityChecker
│       └── test/kotlin/app/granger/core/downloads/  FakeServer, FileDownloaderTest, TestDb, QueuePolicyTest,
│                                                    DownloadManagerTest, DownloadRunnerTest, IntegrityCheckerTest
└── app/
    ├── build.gradle.kts                       + modules, buildConfig fields (catalog URL + key)
    └── src/
        ├── main/AndroidManifest.xml           + INTERNET, network state, foreground service, notifications,
        │                                        Safe Browsing off, WorkManager service type
        ├── main/kotlin/app/granger/
        │   ├── GrangerApp.kt                  + catalog, downloads, volumes, device tier; implements DownloadsHost
        │   ├── GrangerNavHost.kt              + DownloadsRoute, ReadinessRoute
        │   ├── Bytes.kt                       formatBytes()
        │   ├── library/LibraryScreen.kt       + Downloads and Readiness actions, "Get packs" empty state
        │   ├── reader/ReaderWebView.kt        blockNetworkLoads, Safe Browsing off
        │   ├── downloads/DownloadPrefs.kt, ItemStatus.kt, DownloadsViewModel.kt, DownloadsScreen.kt
        │   └── readiness/ReadinessViewModel.kt, ReadinessScreen.kt
        ├── test/kotlin/app/granger/           DownloadFakes, ItemStatusTest, DownloadsViewModelTest, ReadinessViewModelTest
        └── androidTest/kotlin/app/granger/reader/ReaderWebViewTest.kt  + network block test
```

---

### Task 1: `tools/catalog-gen` and the cross-language test fixture

**Files:**
- Create: `tools/catalog-gen/catalog_gen.py`, `tools/catalog-gen/requirements.txt`, `tools/catalog-gen/test_catalog_gen.py`, `tools/catalog-gen/curated.json`, `testdata/catalog/test-catalog.json`, `testdata/catalog/test-catalog.json.sig` (generated)

**Interfaces:**
- Consumes: Kiwix OPDS v2 (`https://library.kiwix.org/catalog/v2/entries?name=<name>&count=50`; entries carry `<id>urn:uuid:…</id>`, `<name>`, `<flavour>`, `<updated>`, `<tags>` with `_ftindex:yes`, and an acquisition link to a `.meta4` with `length`) and metalink files (`<file><size>`, `<hash type="sha-256">`, `<url priority="n">`). Both formats were checked against the live service on 2026-10-16. Verified: a ZIM's `Name` metadata equals the OPDS `<name>` and excludes the flavour (`wikipedia_en_ray-charles` + Flavour `maxi`).
- Produces: the catalog JSON format every later task parses (format version 1):

```json
{
  "formatVersion": 1,
  "generatedAt": "2026-10-16T12:00:00Z",
  "packs": [{"id": "wikipedia_en_medicine_nopic", "title": "…", "description": "…", "category": "Medical",
             "language": "eng", "sizeBytes": 1047708672, "sha256": "<64 lowercase hex>",
             "urls": ["https://download.kiwix.org/zim/wikipedia/….zim", "…mirror…"],
             "snapshotDate": "2026-10-10", "license": "CC BY-SA 4.0", "hasFulltextIndex": true,
             "topics": [], "zimUuid": "1f8cf2fe-…", "zimName": "wikipedia_en_medicine"}],
  "models": [{"id": "gemma-4-e2b-gpu", "title": "…", "role": "small", "fileName": "gemma-4-E2B-it-gpu.litertlm",
              "sizeBytes": 2008432640, "minRamGb": 6, "sha256": "…", "url": "https://huggingface.co/…",
              "license": "Apache-2.0", "evalPassed": false}],
  "presets": [{"id": "starter", "title": "Starter", "items": ["gemma-4-e2b-gpu", "…"]}]
}
```

Signature files (`<catalog>.sig`) hold the base64 of the 64-byte Ed25519 signature over the exact bytes of the JSON file, plus a newline. `public-key.txt` holds the base64 of the 32-byte raw public key plus a newline. CLI: `catalog_gen.py keygen --out KEYFILE`, `build --curated FILE --out FILE`, `sign (--key KEYFILE | --key-hex HEX) FILE`, `verify --public-key FILE FILE`. Key files hold the 32-byte private seed as hex.

The fixture is signed with the RFC 8032 §7.1 TEST 1 key: seed `9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60`, public key `d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a` (base64 `11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=`).

- [ ] **Step 1: Write the failing tests**

`tools/catalog-gen/requirements.txt`:

```text
cryptography==50.0.2
pytest==8.4.2
```

`tools/catalog-gen/test_catalog_gen.py`:

```python
import base64
import datetime
import json

import pytest

import catalog_gen as cg

RFC_SEED = bytes.fromhex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
RFC_PUBLIC = bytes.fromhex("d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a")
RFC_EMPTY_SIG = bytes.fromhex(
    "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155"
    "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"
)

OPDS = """<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
  <entry>
    <id>urn:uuid:1f8cf2fe-e076-e018-2e09-004387350cba</id>
    <title>WikiMed Medical Encyclopedia</title>
    <updated>2026-10-10T00:00:00Z</updated>
    <summary>The largest medical encyclopedia, from Wikipedia</summary>
    <language>eng</language>
    <name>wikipedia_en_medicine</name>
    <flavour>nopic</flavour>
    <tags>medical;wikipedia;_pictures:no;_ftindex:yes</tags>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim"
          href="https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim.meta4" length="1047708672" />
  </entry>
  <entry>
    <id>urn:uuid:f0902c7c-81ad-0490-6f91-fecc14810992</id>
    <title>WikiMed Medical Encyclopedia</title>
    <updated>2026-10-10T00:00:00Z</updated>
    <summary>The largest medical encyclopedia, from Wikipedia</summary>
    <language>eng</language>
    <name>wikipedia_en_medicine</name>
    <flavour>maxi</flavour>
    <tags>medical;wikipedia;_pictures:yes</tags>
    <link rel="http://opds-spec.org/acquisition/open-access" type="application/x-zim"
          href="https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_maxi_2026-10.zim.meta4" length="1864210432" />
  </entry>
</feed>"""

META4 = """<?xml version="1.0" encoding="UTF-8"?>
<metalink xmlns="urn:ietf:params:xml:ns:metalink">
  <file name="wikipedia_en_medicine_nopic_2026-10.zim">
    <size>1047708672</size>
    <hash type="md5">5c2bd306a75683d1c2bc5ff4858ed170</hash>
    <hash type="sha-256">74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976</hash>
    <url location="us" priority="2">https://ftpmirror.your.org/pub/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim</url>
    <url location="us" priority="1">https://ny.mirror.driftle.ss/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim</url>
    <url location="us" priority="5">https://dumps.wikimedia.org/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim</url>
    <url location="de" priority="6">http://insecure.example/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim</url>
  </file>
</metalink>"""

MODEL = {
    "id": "gemma-4-e2b-gpu", "title": "Gemma 4 E2B", "role": "small", "fileName": "gemma-4-E2B-it-gpu.litertlm",
    "sizeBytes": 2008432640, "minRamGb": 6,
    "sha256": "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
    "url": "https://huggingface.co/x/resolve/abc/gemma-4-E2B-it-gpu.litertlm", "license": "Apache-2.0",
    "evalPassed": False,
}


def fake_fetch(routes):
    def fetch(url):
        for prefix, body in routes.items():
            if url.startswith(prefix):
                return body.encode()
        raise AssertionError(f"unexpected fetch {url}")
    return fetch


ROUTES = {
    "https://library.kiwix.org/catalog/v2/entries?name=wikipedia_en_medicine": OPDS,
    "https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim.meta4": META4,
}
CURATED_MED = {"name": "wikipedia_en_medicine", "flavour": "nopic", "category": "Medical", "license": "CC BY-SA 4.0"}


def test_rfc8032_vector():
    assert cg.public_key(RFC_SEED) == RFC_PUBLIC
    assert cg.sign_bytes(RFC_SEED, b"") == RFC_EMPTY_SIG


def test_opds_entries():
    entries = cg.opds_entries(OPDS.encode())
    assert [(e["name"], e["flavour"]) for e in entries] == [
        ("wikipedia_en_medicine", "nopic"), ("wikipedia_en_medicine", "maxi")]
    assert entries[0]["uuid"] == "1f8cf2fe-e076-e018-2e09-004387350cba"
    assert entries[0]["length"] == 1047708672
    assert "_ftindex:yes" in entries[0]["tags"]


def test_metalink_sorts_mirrors_by_priority():
    m = cg.metalink(META4.encode())
    assert m["size"] == 1047708672
    assert m["sha256"] == "74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976"
    assert m["mirrors"][0].startswith("https://ny.mirror.driftle.ss/")


def test_pack_entry_picks_the_flavour_and_builds_urls():
    p = cg.pack_entry(CURATED_MED, fake_fetch(ROUTES))
    assert p["id"] == "wikipedia_en_medicine_nopic"
    assert p["zimName"] == "wikipedia_en_medicine"
    assert p["zimUuid"] == "1f8cf2fe-e076-e018-2e09-004387350cba"
    assert p["snapshotDate"] == "2026-10-10"
    assert p["hasFulltextIndex"] is True
    assert p["category"] == "Medical" and p["license"] == "CC BY-SA 4.0"
    # The canonical Kiwix URL first (it redirects to a nearby mirror), then stable https mirrors only.
    assert p["urls"] == [
        "https://download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim",
        "https://ftpmirror.your.org/pub/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim",
        "https://dumps.wikimedia.org/kiwix/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim",
    ]


def test_maxi_without_ftindex_tag():
    meta4_maxi = META4.replace("nopic", "maxi").replace("1047708672", "1864210432")
    routes = {**ROUTES, "https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_maxi": meta4_maxi}
    p = cg.pack_entry({**CURATED_MED, "flavour": "maxi"}, fake_fetch(routes))
    assert p["id"] == "wikipedia_en_medicine_maxi"
    assert p["hasFulltextIndex"] is False


def test_missing_flavour_fails():
    with pytest.raises(SystemExit):
        cg.pack_entry({**CURATED_MED, "flavour": "mini"}, fake_fetch(ROUTES))


def test_metalink_size_is_exact():
    # Seen live: OPDS 1047708672 (rounded to KiB) vs metalink 1047708415.
    routes = {**ROUTES, ROUTES_META4_KEY: META4.replace("<size>1047708672", "<size>1047708415")}
    assert cg.pack_entry(CURATED_MED, fake_fetch(routes))["sizeBytes"] == 1047708415


def test_size_disagreement_fails():
    routes = {**ROUTES, ROUTES_META4_KEY: META4.replace("<size>1047708672", "<size>5")}
    with pytest.raises(SystemExit):
        cg.pack_entry(CURATED_MED, fake_fetch(routes))


ROUTES_META4_KEY = "https://lb.download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim.meta4"


def test_build_checks_presets_and_ids():
    curated = {"packs": [CURATED_MED], "models": [MODEL],
               "presets": [{"id": "starter", "title": "Starter", "items": ["gemma-4-e2b-gpu", "wikipedia_en_medicine_nopic"]}]}
    now = datetime.datetime(2026, 10, 16, 12, 0, tzinfo=datetime.timezone.utc)
    catalog = cg.build(curated, fake_fetch(ROUTES), now)
    assert catalog["formatVersion"] == 1
    assert catalog["generatedAt"] == "2026-10-16T12:00:00Z"
    assert [p["id"] for p in catalog["packs"]] == ["wikipedia_en_medicine_nopic"]
    assert catalog["models"] == [MODEL]

    bad = {**curated, "presets": [{"id": "starter", "title": "Starter", "items": ["nope"]}]}
    with pytest.raises(SystemExit):
        cg.build(bad, fake_fetch(ROUTES), now)


def test_build_rejects_unsafe_ids():
    curated = {"packs": [], "models": [{**MODEL, "fileName": "../evil.litertlm"}], "presets": []}
    with pytest.raises(SystemExit):
        cg.build(curated, fake_fetch(ROUTES), datetime.datetime.now(datetime.timezone.utc))


def test_sign_and_verify_round_trip(tmp_path):
    catalog = tmp_path / "catalog.json"
    catalog.write_text('{"formatVersion": 1}\n')
    cg.sign_file(RFC_SEED, catalog)
    sig = (tmp_path / "catalog.json.sig").read_text()
    assert len(base64.b64decode(sig.strip())) == 64
    assert cg.verify_file(RFC_PUBLIC, catalog)

    catalog.write_text('{"formatVersion": 2}\n')
    assert not cg.verify_file(RFC_PUBLIC, catalog)


def test_keygen_writes_private_seed(tmp_path):
    key = tmp_path / "k.key"
    public_b64 = cg.keygen(key)
    seed = bytes.fromhex(key.read_text().strip())
    assert len(seed) == 32
    assert base64.b64decode(public_b64) == cg.public_key(seed)
    assert oct(key.stat().st_mode & 0o777) == "0o600"
```

- [ ] **Step 2: Run the tests to verify they fail**

```bash
python3 -m venv /tmp/catgenv && /tmp/catgenv/bin/pip install -q -r tools/catalog-gen/requirements.txt
cd tools/catalog-gen && /tmp/catgenv/bin/python -m pytest -q; cd -
```

Expected: collection error, `ModuleNotFoundError: No module named 'catalog_gen'`.

- [ ] **Step 3: Write `catalog_gen.py`**

`tools/catalog-gen/catalog_gen.py`:

```python
#!/usr/bin/env python3
"""Builds, signs and verifies Granger's catalog of packs and models.

Packs come from Kiwix's OPDS catalog plus the hand-picked list in curated.json; each pack is pinned to
one snapshot by its sha256 from Kiwix's metalink file. Models are listed in curated.json as-is.

  catalog_gen.py keygen --out ~/.config/granger/catalog-signing.key   # once; prints the public key
  catalog_gen.py build  --curated tools/catalog-gen/curated.json --out catalog/catalog.json
  catalog_gen.py sign   --key ~/.config/granger/catalog-signing.key catalog/catalog.json
  catalog_gen.py verify --public-key catalog/public-key.txt catalog/catalog.json

The app trusts only catalogs signed by the key whose public half is in catalog/public-key.txt.
"""
import argparse
import base64
import datetime
import json
import os
import pathlib
import re
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey, Ed25519PublicKey
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

FORMAT_VERSION = 1
OPDS_ENTRIES = "https://library.kiwix.org/catalog/v2/entries"
ACQUISITION = "http://opds-spec.org/acquisition/open-access"
ATOM = "{http://www.w3.org/2005/Atom}"
METALINK = "{urn:ietf:params:xml:ns:metalink}"
# Mirrors that have been around for years; the canonical download.kiwix.org URL already picks a nearby one.
STABLE_MIRRORS = ("ftpmirror.your.org", "dumps.wikimedia.org")
MAX_URLS = 3
SAFE_NAME = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")


def http_get(url):
    request = urllib.request.Request(url, headers={"User-Agent": "granger-catalog-gen"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return response.read()


def opds_entries(xml_bytes):
    entries = []
    for entry in ET.fromstring(xml_bytes).findall(f"{ATOM}entry"):
        def text(tag):
            return (entry.findtext(f"{ATOM}{tag}") or "").strip()
        link = next((l for l in entry.findall(f"{ATOM}link") if l.get("rel") == ACQUISITION), None)
        if link is None:
            continue
        entries.append({
            "uuid": text("id").removeprefix("urn:uuid:"),
            "title": text("title"),
            "summary": text("summary"),
            "language": text("language"),
            "name": text("name"),
            "flavour": text("flavour"),
            "updated": text("updated"),
            "tags": text("tags").split(";"),
            "meta4": link.get("href"),
            "length": int(link.get("length")),
        })
    return entries


def metalink(xml_bytes):
    file = ET.fromstring(xml_bytes).find(f"{METALINK}file")
    sha256 = next(h.text.strip() for h in file.findall(f"{METALINK}hash") if h.get("type") == "sha-256")
    urls = sorted(file.findall(f"{METALINK}url"), key=lambda u: int(u.get("priority", "999")))
    return {
        "name": file.get("name"),
        "size": int(file.findtext(f"{METALINK}size")),
        "sha256": sha256.lower(),
        "mirrors": [u.text.strip() for u in urls],
    }


def pack_entry(curated, fetch=http_get):
    name, flavour = curated["name"], curated.get("flavour", "")
    query = urllib.parse.urlencode({"name": name, "count": 50})
    matches = [e for e in opds_entries(fetch(f"{OPDS_ENTRIES}?{query}"))
               if e["name"] == name and e["flavour"] == flavour]
    if not matches:
        raise SystemExit(f"{name} ({flavour or 'no flavour'}): not in the Kiwix catalog")
    entry = max(matches, key=lambda e: e["updated"])
    meta4_url = urllib.parse.urljoin(OPDS_ENTRIES, entry["meta4"])
    meta = metalink(fetch(meta4_url))
    # OPDS rounds the length to whole KiB; the metalink size is exact. Only a big gap means a mix-up.
    if abs(meta["size"] - entry["length"]) > entry["length"] // 100:
        raise SystemExit(f"{name}: OPDS says {entry['length']} bytes, metalink says {meta['size']}")
    canonical = meta4_url.removesuffix(".meta4").replace("://lb.download.kiwix.org/", "://download.kiwix.org/")
    stable = [u for u in meta["mirrors"]
              if u.startswith("https://") and urllib.parse.urlparse(u).hostname in STABLE_MIRRORS]
    return {
        "id": f"{name}_{flavour}" if flavour else name,
        "title": curated.get("title", entry["title"]),
        "description": entry["summary"],
        "category": curated["category"],
        "language": entry["language"],
        "sizeBytes": meta["size"],
        "sha256": meta["sha256"],
        "urls": ([canonical] + stable)[:MAX_URLS],
        "snapshotDate": entry["updated"][:10],
        "license": curated["license"],
        "hasFulltextIndex": "_ftindex:yes" in entry["tags"],
        "topics": [],
        "zimUuid": entry["uuid"],
        "zimName": entry["name"],
    }


def build(curated, fetch=http_get, now=None):
    now = now or datetime.datetime.now(datetime.timezone.utc)
    packs = [pack_entry(c, fetch) for c in curated["packs"]]
    models = curated["models"]
    for item in packs + models:
        for key in ("id", "fileName"):
            if key in item and not SAFE_NAME.match(item[key]):
                raise SystemExit(f"unsafe {key}: {item[key]!r}")
    ids = [item["id"] for item in packs + models]
    if len(ids) != len(set(ids)):
        raise SystemExit(f"duplicate ids in {ids}")
    for preset in curated["presets"]:
        unknown = set(preset["items"]) - set(ids)
        if unknown:
            raise SystemExit(f"preset {preset['id']} lists unknown items {sorted(unknown)}")
    return {
        "formatVersion": FORMAT_VERSION,
        "generatedAt": now.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "packs": packs,
        "models": models,
        "presets": curated["presets"],
    }


def public_key(seed):
    return Ed25519PrivateKey.from_private_bytes(seed).public_key().public_bytes(Encoding.Raw, PublicFormat.Raw)


def sign_bytes(seed, data):
    return Ed25519PrivateKey.from_private_bytes(seed).sign(data)


def sign_file(seed, path):
    path = pathlib.Path(path)
    signature = sign_bytes(seed, path.read_bytes())
    pathlib.Path(f"{path}.sig").write_text(base64.b64encode(signature).decode() + "\n")


def verify_file(public, path):
    path = pathlib.Path(path)
    signature = base64.b64decode(pathlib.Path(f"{path}.sig").read_text().strip())
    try:
        Ed25519PublicKey.from_public_bytes(public).verify(signature, path.read_bytes())
        return True
    except InvalidSignature:
        return False


def keygen(out):
    out = pathlib.Path(out).expanduser()
    if out.exists():
        raise SystemExit(f"{out} exists; refusing to overwrite a signing key")
    out.parent.mkdir(parents=True, exist_ok=True)
    seed = os.urandom(32)
    fd = os.open(out, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as f:
        f.write(seed.hex() + "\n")
    return base64.b64encode(public_key(seed)).decode()


def main(argv):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)
    p = sub.add_parser("keygen"); p.add_argument("--out", required=True)
    p = sub.add_parser("build"); p.add_argument("--curated", required=True); p.add_argument("--out", required=True)
    p = sub.add_parser("sign"); p.add_argument("file")
    group = p.add_mutually_exclusive_group(required=True)
    group.add_argument("--key"); group.add_argument("--key-hex")
    p = sub.add_parser("verify"); p.add_argument("--public-key", required=True); p.add_argument("file")
    args = parser.parse_args(argv)

    if args.command == "keygen":
        print(keygen(args.out))
    elif args.command == "build":
        catalog = build(json.loads(pathlib.Path(args.curated).read_text()))
        pathlib.Path(args.out).write_text(json.dumps(catalog, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        print(f"{args.out}: {len(catalog['packs'])} packs, {len(catalog['models'])} models")
    elif args.command == "sign":
        hex_seed = args.key_hex or pathlib.Path(args.key).expanduser().read_text().strip()
        sign_file(bytes.fromhex(hex_seed), args.file)
        print(f"wrote {args.file}.sig")
    elif args.command == "verify":
        public = base64.b64decode(pathlib.Path(args.public_key).read_text().strip())
        if not verify_file(public, args.file):
            raise SystemExit(f"{args.file}: BAD signature")
        print(f"{args.file}: signature OK")


if __name__ == "__main__":
    main(sys.argv[1:])
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd tools/catalog-gen && /tmp/catgenv/bin/python -m pytest -q; cd -`
Expected: `12 passed`.

- [ ] **Step 5: Write the curated list**

`tools/catalog-gen/curated.json` (sizes as of 2026-10-16; Science gets OpenStax and Religion & Philosophy gets Granger Classics in Plan 4; Repair stays empty because iFixit is CC BY-NC-SA):

```json
{
  "packs": [
    {"name": "wikipedia_en_medicine", "flavour": "nopic", "category": "Medical", "license": "CC BY-SA 4.0",
     "title": "WikiMed (no pictures)"},
    {"name": "wikipedia_en_medicine", "flavour": "maxi", "category": "Medical", "license": "CC BY-SA 4.0",
     "title": "WikiMed"},
    {"name": "wikem_en_all", "flavour": "maxi", "category": "Medical", "license": "CC BY-SA 3.0",
     "title": "WikEM emergency medicine"},
    {"name": "wikipedia_en_top", "flavour": "nopic", "category": "Reference", "license": "CC BY-SA 4.0",
     "title": "Wikipedia: best articles (no pictures)"},
    {"name": "wikipedia_en_top", "flavour": "maxi", "category": "Reference", "license": "CC BY-SA 4.0",
     "title": "Wikipedia: best articles"},
    {"name": "wikipedia_en_100", "category": "Reference", "license": "CC BY-SA 4.0",
     "title": "Wikipedia: 100 essential articles"},
    {"name": "wikipedia_en_ray-charles", "flavour": "maxi", "category": "Reference", "license": "CC BY-SA 4.0",
     "title": "Wikipedia sample: Ray Charles (2 MB)"},
    {"name": "gutenberg_en_lcc-b", "category": "Religion & Philosophy", "license": "Public domain (US)",
     "title": "Project Gutenberg: Philosophy & Religion"},
    {"name": "wikisource_en_all", "flavour": "nopic", "category": "Literature & Drama", "license": "CC BY-SA 4.0",
     "title": "Wikisource (no pictures)"},
    {"name": "gutenberg_en_lcc-pr", "category": "Literature & Drama", "license": "Public domain (US)",
     "title": "Project Gutenberg: English literature"},
    {"name": "wikivoyage_en_all", "flavour": "nopic", "category": "Regional", "license": "CC BY-SA 4.0",
     "title": "Wikivoyage travel guide (no pictures)"}
  ],
  "models": [
    {"id": "gemma-4-e2b-gpu", "title": "Gemma 4 E2B (answers questions)", "role": "small",
     "fileName": "gemma-4-E2B-it-gpu.litertlm", "sizeBytes": 2008432640, "minRamGb": 6,
     "sha256": "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
     "url": "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it-gpu.litertlm",
     "license": "Apache-2.0", "evalPassed": false},
    {"id": "gemma-4-e4b-gpu", "title": "Gemma 4 E4B (Think deeper)", "role": "large",
     "fileName": "gemma-4-E4B-it-gpu.litertlm", "sizeBytes": 2969059328, "minRamGb": 8,
     "sha256": "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff",
     "url": "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/2eee7ac325f20eb8c9ac1d0e972f7c84663062da/gemma-4-E4B-it-gpu.litertlm",
     "license": "Apache-2.0", "evalPassed": false},
    {"id": "embeddinggemma-2-text-270m", "title": "EmbeddingGemma 2 (ranks passages)", "role": "embedder",
     "fileName": "embeddinggemma-2-text-270m.litertlm", "sizeBytes": 164626432, "minRamGb": 6,
     "sha256": "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
     "url": "https://huggingface.co/litert-community/embeddinggemma-2-text-270m-litert-lm/resolve/9be6e8b90982095dc05c2bd162e4b954ee4dbac7/embeddinggemma-2-text-270m.litertlm",
     "license": "Apache-2.0", "evalPassed": false}
  ],
  "presets": [
    {"id": "starter", "title": "Starter",
     "items": ["gemma-4-e2b-gpu", "embeddinggemma-2-text-270m", "wikipedia_en_medicine_nopic", "wikipedia_en_top_nopic"]}
  ]
}
```

Model sizes and hashes match `tools/spike/fetch-models.sh` and Hugging Face's `x-linked-size` / `x-linked-etag` headers; URLs pin the repo commit so the file behind a URL can't change. `evalPassed` stays `false` until Plan 7, so release builds list no models yet; debug builds list them all.

- [ ] **Step 6: Write and sign the test fixture**

`testdata/catalog/test-catalog.json` — the contract for the Kotlin parser tests (Task 2). It deliberately contains one invalid pack (`bad-sha`) and one hostile id (`../escape`), which the parser must drop:

```json
{
  "formatVersion": 1,
  "generatedAt": "2026-10-16T00:00:00Z",
  "futureField": {"ignored": true},
  "packs": [
    {"id": "wikipedia_en_medicine_nopic", "title": "WikiMed (no pictures)", "description": "Medical encyclopedia",
     "category": "Medical", "language": "eng", "sizeBytes": 1047708672,
     "sha256": "74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976",
     "urls": ["https://download.kiwix.org/zim/wikipedia/wikipedia_en_medicine_nopic_2026-10.zim"],
     "snapshotDate": "2026-10-10", "license": "CC BY-SA 4.0", "hasFulltextIndex": true, "topics": [],
     "zimUuid": "1f8cf2fe-e076-e018-2e09-004387350cba", "zimName": "wikipedia_en_medicine"},
    {"id": "bad-sha", "title": "Bad", "category": "Reference", "language": "eng", "sizeBytes": 10,
     "sha256": "nothex", "urls": ["https://example.com/a.zim"], "snapshotDate": "2026-10-10",
     "license": "CC0", "hasFulltextIndex": false, "zimUuid": "u", "zimName": "n"},
    {"id": "../escape", "title": "Escape", "category": "Reference", "language": "eng", "sizeBytes": 10,
     "sha256": "74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976",
     "urls": ["https://example.com/a.zim"], "snapshotDate": "2026-10-10",
     "license": "CC0", "hasFulltextIndex": false, "zimUuid": "u2", "zimName": "n2"}
  ],
  "models": [
    {"id": "gemma-4-e2b-gpu", "title": "Gemma 4 E2B", "role": "small", "fileName": "gemma-4-E2B-it-gpu.litertlm",
     "sizeBytes": 2008432640, "minRamGb": 6,
     "sha256": "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5",
     "url": "https://huggingface.co/x/resolve/abc/gemma-4-E2B-it-gpu.litertlm", "license": "Apache-2.0",
     "evalPassed": true},
    {"id": "gemma-4-e4b-gpu", "title": "Gemma 4 E4B", "role": "large", "fileName": "gemma-4-E4B-it-gpu.litertlm",
     "sizeBytes": 2969059328, "minRamGb": 8,
     "sha256": "4912bb5a9c30993c51a7711f763212077458529312175df0573a78323a2bb7ff",
     "url": "https://huggingface.co/x/resolve/abc/gemma-4-E4B-it-gpu.litertlm", "license": "Apache-2.0",
     "evalPassed": false}
  ],
  "presets": [
    {"id": "starter", "title": "Starter", "items": ["gemma-4-e2b-gpu", "wikipedia_en_medicine_nopic", "bad-sha"]}
  ]
}
```

Sign it with the RFC 8032 test key and check:

```bash
/tmp/catgenv/bin/python tools/catalog-gen/catalog_gen.py sign \
  --key-hex 9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60 testdata/catalog/test-catalog.json
echo 11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo= > /tmp/rfc-pub.txt
/tmp/catgenv/bin/python tools/catalog-gen/catalog_gen.py verify --public-key /tmp/rfc-pub.txt testdata/catalog/test-catalog.json
```

Expected: `wrote testdata/catalog/test-catalog.json.sig`, then `signature OK`. Never edit `test-catalog.json` without re-signing it.

- [ ] **Step 7: Commit**

```bash
git add tools/catalog-gen testdata/catalog
git commit -m "Add catalog-gen: build and sign the pack and model catalog

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 2: `:core:catalog` — model, signature, parser

**Files:**
- Create: `core/catalog/build.gradle.kts`, `core/catalog/src/main/AndroidManifest.xml`, `core/catalog/src/main/kotlin/app/granger/core/catalog/Catalog.kt`, `.../CatalogSignature.kt`, `.../CatalogParser.kt`, `core/catalog/src/test/kotlin/app/granger/core/catalog/TestCatalogs.kt`, `.../CatalogParserTest.kt`
- Modify: `gradle/libs.versions.toml`, `settings.gradle.kts`

**Interfaces:**
- Consumes: the catalog format and `testdata/catalog/test-catalog.json{,.sig}` from Task 1.
- Produces (package `app.granger.core.catalog`):
  - `@Serializable data class Catalog(formatVersion: Int, generatedAt: String, packs: List<PackEntry>, models: List<ModelEntry>, presets: List<Preset>)` with `val items: List<CatalogItem>` (models then packs), `fun item(id: String): CatalogItem?`, `fun forBuild(includeUnevaluatedModels: Boolean): Catalog`.
  - `sealed interface CatalogItem { id; title; sizeBytes: Long; sha256: String; urls: List<String>; license }`.
  - `@Serializable data class PackEntry(id, title, description = "", category, language, sizeBytes, sha256, urls, snapshotDate, license, hasFulltextIndex, topics = emptyList(), zimUuid, zimName) : CatalogItem`.
  - `@Serializable data class ModelEntry(id, title, role: ModelRole, fileName, sizeBytes, minRamGb: Int, sha256, url, license, evalPassed) : CatalogItem` (`urls = listOf(url)`).
  - `enum class ModelRole { SMALL, LARGE, EMBEDDER }` (JSON `small|large|embedder`); `@Serializable data class Preset(id, title, items: List<String>)`.
  - `enum class CatalogError { NETWORK, BAD_SIGNATURE, UNSUPPORTED_FORMAT, MALFORMED, OLDER_THAN_CACHED }`.
  - `sealed interface ParseResult { data class Ok(catalog: Catalog); data class Invalid(error: CatalogError) }`.
  - `object CatalogParser { const val FORMAT_VERSION = 1; fun parse(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray): ParseResult }` — drops invalid entries (bad sha256, size ≤ 0, no URLs, non-https URL, unsafe id or model `fileName`, pack `snapshotDate` not `YYYY-MM-DD`, duplicate id) and preset items that don't exist.
  - `object CatalogSignature { fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean }` — `signature` is the raw `.sig` file content (base64 text).
  - Version-catalog aliases used later: `libs.okhttp`, `libs.androidx.work.runtime`, `libs.bouncycastle`, `libs.androidx.room.testing`.

Ids, pack snapshot dates and model file names become file names in Task 7, which is why the parser only accepts `^[A-Za-z0-9][A-Za-z0-9._-]*$` for names and `YYYY-MM-DD` for dates.

- [ ] **Step 1: Add the module and dependencies**

In `gradle/libs.versions.toml`, add under `[versions]`:

```toml
okhttp = "5.5.0"
work = "2.12.0"
bouncycastle = "1.86"
```

and under `[libraries]`:

```toml
okhttp = { module = "com.squareup.okhttp3:okhttp", version.ref = "okhttp" }
androidx-work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
bouncycastle = { module = "org.bouncycastle:bcprov-jdk18on", version.ref = "bouncycastle" }
androidx-room-testing = { module = "androidx.room:room-testing", version.ref = "room" }
```

In `settings.gradle.kts`, change the include line to:

```kotlin
include(":app", ":core:ai", ":core:zim", ":core:library", ":core:catalog")
```

`core/catalog/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "app.granger.core.catalog"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(libs.okhttp)
    implementation(libs.bouncycastle)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
}
```

`core/catalog/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 2: Write the failing tests**

`core/catalog/src/test/kotlin/app/granger/core/catalog/TestCatalogs.kt`:

```kotlin
package app.granger.core.catalog

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.io.File
import java.util.Base64

/** The RFC 8032 §7.1 TEST 1 key pair. Public knowledge; for tests only. */
object TestKeys {
    val SEED: ByteArray = hex("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60")
    val PUBLIC: ByteArray = Base64.getDecoder().decode("11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo=")

    /** The content of a `.sig` file for [data]. */
    fun sign(data: ByteArray, seed: ByteArray = SEED): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(data, 0, data.size)
        return (Base64.getEncoder().encodeToString(signer.generateSignature()) + "\n").toByteArray()
    }

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

const val SHA_A = "74f9d80ad6183ede02e5c6f4a023676444c0c212889ed0dd9496f3738f72b976"
const val SHA_B = "a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5"

/** A small valid catalog: one pack, one evaluated and one unevaluated model, one preset. */
fun catalogJson(
    generatedAt: String = "2026-10-16T00:00:00Z",
    formatVersion: Int = 1,
    packTitle: String = "WikiMed",
    modelFileName: String = "e2b.litertlm",
    packUrl: String = "https://download.kiwix.org/zim/wikipedia/med.zim",
    snapshotDate: String = "2026-10-10",
): ByteArray = """
    {"formatVersion": $formatVersion, "generatedAt": "$generatedAt",
     "packs": [{"id": "med", "title": "$packTitle", "category": "Medical", "language": "eng", "sizeBytes": 1000,
                "sha256": "$SHA_A", "urls": ["$packUrl"], "snapshotDate": "$snapshotDate", "license": "CC BY-SA 4.0",
                "hasFulltextIndex": true, "zimUuid": "u-med", "zimName": "wikipedia_en_medicine"}],
     "models": [{"id": "e2b", "title": "E2B", "role": "small", "fileName": "$modelFileName", "sizeBytes": 2000,
                 "minRamGb": 6, "sha256": "$SHA_B", "url": "https://huggingface.co/e2b", "license": "Apache-2.0",
                 "evalPassed": true},
                {"id": "e4b", "title": "E4B", "role": "large", "fileName": "e4b.litertlm", "sizeBytes": 3000,
                 "minRamGb": 8, "sha256": "$SHA_B", "url": "https://huggingface.co/e4b", "license": "Apache-2.0",
                 "evalPassed": false}],
     "presets": [{"id": "starter", "title": "Starter", "items": ["e2b", "med"]}]}
""".trimIndent().toByteArray()

/** A file under the repo's `testdata/` folder, found by walking up from the module directory. */
fun testdata(name: String): File {
    var dir: File? = File("").absoluteFile
    while (dir != null && !File(dir, "testdata").isDirectory) dir = dir.parentFile
    return File(dir ?: error("testdata/ not found"), "testdata/$name")
}
```

`core/catalog/src/test/kotlin/app/granger/core/catalog/CatalogParserTest.kt`:

```kotlin
package app.granger.core.catalog

import com.google.common.truth.Truth.assertThat
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class CatalogParserTest {
    private fun parse(bytes: ByteArray, sig: ByteArray = TestKeys.sign(bytes), key: ByteArray = TestKeys.PUBLIC) =
        CatalogParser.parse(bytes, sig, key)

    private fun ok(result: ParseResult) = (result as ParseResult.Ok).catalog

    @Test fun rfc8032VectorVerifies() {
        val sig = TestKeys.hex(
            "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e06522490155" +
                "5fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b",
        )
        val sigFile = Base64.getEncoder().encodeToString(sig).toByteArray()
        assertThat(CatalogSignature.verify(ByteArray(0), sigFile, TestKeys.PUBLIC)).isTrue()
    }

    @Test fun verifiesTheCatalogSignedByCatalogGen() {
        val bytes = testdata("catalog/test-catalog.json").readBytes()
        val sig = testdata("catalog/test-catalog.json.sig").readBytes()
        val catalog = ok(CatalogParser.parse(bytes, sig, TestKeys.PUBLIC))
        // "bad-sha" and "../escape" are dropped; so is the dangling preset item.
        assertThat(catalog.packs.map { it.id }).containsExactly("wikipedia_en_medicine_nopic")
        assertThat(catalog.models.map { it.id }).containsExactly("gemma-4-e2b-gpu", "gemma-4-e4b-gpu").inOrder()
        assertThat(catalog.presets.single().items).containsExactly("gemma-4-e2b-gpu", "wikipedia_en_medicine_nopic")

        val med = catalog.packs.single()
        assertThat(med.sizeBytes).isEqualTo(1047708672L)
        assertThat(med.zimUuid).isEqualTo("1f8cf2fe-e076-e018-2e09-004387350cba")
        assertThat(med.zimName).isEqualTo("wikipedia_en_medicine")
        assertThat(med.snapshotDate).isEqualTo("2026-10-10")
        assertThat(med.hasFulltextIndex).isTrue()
        assertThat(med.topics).isEmpty()
        assertThat(catalog.models.first().role).isEqualTo(ModelRole.SMALL)
        assertThat(catalog.models.first().urls).containsExactly("https://huggingface.co/x/resolve/abc/gemma-4-E2B-it-gpu.litertlm")
        assertThat(catalog.item("wikipedia_en_medicine_nopic")).isSameInstanceAs(med)
    }

    @Test fun tamperedBytesFailTheSignature() {
        val bytes = catalogJson()
        val sig = TestKeys.sign(bytes)
        val tampered = catalogJson(packTitle = "WikiMeD")
        assertThat(parse(tampered, sig)).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
    }

    @Test fun otherKeysAndGarbageSignaturesFail() {
        val gen = Ed25519KeyPairGenerator().apply { init(Ed25519KeyGenerationParameters(SecureRandom())) }
        val otherPublic = (gen.generateKeyPair().public as Ed25519PublicKeyParameters).encoded
        val bytes = catalogJson()
        assertThat(parse(bytes, key = otherPublic)).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
        assertThat(parse(bytes, sig = "not base64!!".toByteArray())).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
        assertThat(parse(bytes, sig = ByteArray(0))).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
        assertThat(parse(bytes, key = ByteArray(5))).isEqualTo(ParseResult.Invalid(CatalogError.BAD_SIGNATURE))
    }

    @Test fun futureFormatIsUnsupported() {
        assertThat(parse(catalogJson(formatVersion = 2))).isEqualTo(ParseResult.Invalid(CatalogError.UNSUPPORTED_FORMAT))
    }

    @Test fun signedGarbageIsMalformed() {
        assertThat(parse("{not json".toByteArray())).isEqualTo(ParseResult.Invalid(CatalogError.MALFORMED))
        assertThat(parse("[]".toByteArray())).isEqualTo(ParseResult.Invalid(CatalogError.MALFORMED))
        assertThat(parse(catalogJson(generatedAt = "yesterday"))).isEqualTo(ParseResult.Invalid(CatalogError.MALFORMED))
    }

    @Test fun unsafeFileNamesAndPlainHttpAreDropped() {
        val catalog = ok(parse(catalogJson(modelFileName = "../../databases/library.db", packUrl = "http://example.com/med.zim")))
        assertThat(catalog.models.map { it.id }).containsExactly("e4b")
        assertThat(catalog.packs).isEmpty()
        assertThat(catalog.presets.single().items).isEmpty()

        val traversal = ok(parse(catalogJson(snapshotDate = "../../../x")))
        assertThat(traversal.packs).isEmpty()
    }

    @Test fun releaseBuildsListOnlyEvaluatedModels() {
        val catalog = ok(parse(catalogJson()))
        assertThat(catalog.forBuild(includeUnevaluatedModels = true).models.map { it.id }).containsExactly("e2b", "e4b")
        assertThat(catalog.forBuild(includeUnevaluatedModels = false).models.map { it.id }).containsExactly("e2b")
        assertThat(catalog.items.map { it.id }).containsExactly("e2b", "e4b", "med").inOrder()
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:catalog:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference: CatalogParser`).

- [ ] **Step 4: Write the model, signature check and parser**

`core/catalog/src/main/kotlin/app/granger/core/catalog/Catalog.kt`:

```kotlin
package app.granger.core.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The signed list of packs and models the app can download. */
@Serializable
data class Catalog(
    val formatVersion: Int,
    val generatedAt: String,
    val packs: List<PackEntry> = emptyList(),
    val models: List<ModelEntry> = emptyList(),
    val presets: List<Preset> = emptyList(),
) {
    val items: List<CatalogItem> get() = models + packs

    fun item(id: String): CatalogItem? = items.firstOrNull { it.id == id }

    /** Release builds list only models that passed the eval (spec §3 "Catalog"). */
    fun forBuild(includeUnevaluatedModels: Boolean): Catalog =
        if (includeUnevaluatedModels) this else copy(models = models.filter { it.evalPassed })
}

/** Anything the downloader can fetch and verify. */
sealed interface CatalogItem {
    val id: String
    val title: String
    val sizeBytes: Long
    val sha256: String
    val urls: List<String>
    val license: String
}

@Serializable
data class PackEntry(
    override val id: String,
    override val title: String,
    val description: String = "",
    val category: String,
    val language: String,
    override val sizeBytes: Long,
    override val sha256: String,
    override val urls: List<String>,
    val snapshotDate: String,
    override val license: String,
    val hasFulltextIndex: Boolean,
    /** Reserved for v2 curation. */
    val topics: List<String> = emptyList(),
    /** The ZIM's UUID; equals the installed pack's id once downloaded. */
    val zimUuid: String,
    /** The ZIM's `Name` metadata, shared by every snapshot (and flavour) of the same content. */
    val zimName: String,
) : CatalogItem

@Serializable
enum class ModelRole {
    @SerialName("small") SMALL,
    @SerialName("large") LARGE,
    @SerialName("embedder") EMBEDDER,
}

@Serializable
data class ModelEntry(
    override val id: String,
    override val title: String,
    val role: ModelRole,
    val fileName: String,
    override val sizeBytes: Long,
    val minRamGb: Int,
    override val sha256: String,
    val url: String,
    override val license: String,
    val evalPassed: Boolean,
) : CatalogItem {
    override val urls: List<String> get() = listOf(url)
}

@Serializable
data class Preset(val id: String, val title: String, val items: List<String>)
```

`core/catalog/src/main/kotlin/app/granger/core/catalog/CatalogSignature.kt`:

```kotlin
package app.granger.core.catalog

import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.util.Base64

/** Ed25519 via BouncyCastle: the JCA only has Ed25519 from API 33, and minSdk is 29. */
object CatalogSignature {
    /** [signature] is a `.sig` file: the base64 of a 64-byte signature, surrounding whitespace allowed. */
    fun verify(data: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        if (publicKey.size != 32) return false
        return try {
            val sig = Base64.getDecoder().decode(signature.decodeToString().trim())
            if (sig.size != 64) return false
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(data, 0, data.size)
            verifier.verifySignature(sig)
        } catch (e: Exception) {
            false
        }
    }
}
```

`core/catalog/src/main/kotlin/app/granger/core/catalog/CatalogParser.kt`:

```kotlin
package app.granger.core.catalog

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import java.time.Instant

enum class CatalogError { NETWORK, BAD_SIGNATURE, UNSUPPORTED_FORMAT, MALFORMED, OLDER_THAN_CACHED }

sealed interface ParseResult {
    data class Ok(val catalog: Catalog) : ParseResult
    data class Invalid(val error: CatalogError) : ParseResult
}

object CatalogParser {
    const val FORMAT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true }
    private val SHA256 = Regex("^[0-9a-f]{64}$")
    /** Ids and model file names become file names on the phone. */
    private val SAFE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    /** Pack snapshot dates are part of the file name too. */
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    /** Checks the signature first, then parses. Entries that don't validate are dropped, not fatal. */
    fun parse(bytes: ByteArray, signature: ByteArray, publicKey: ByteArray): ParseResult {
        if (!CatalogSignature.verify(bytes, signature, publicKey)) return ParseResult.Invalid(CatalogError.BAD_SIGNATURE)
        val root = try {
            json.parseToJsonElement(bytes.decodeToString()) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: return ParseResult.Invalid(CatalogError.MALFORMED)
        val version = (root["formatVersion"] as? JsonPrimitive)?.intOrNull
            ?: return ParseResult.Invalid(CatalogError.MALFORMED)
        if (version != FORMAT_VERSION) return ParseResult.Invalid(CatalogError.UNSUPPORTED_FORMAT)
        val generatedAt = (root["generatedAt"] as? JsonPrimitive)?.contentOrNull
        if (generatedAt == null || runCatching { Instant.parse(generatedAt) }.isFailure) {
            return ParseResult.Invalid(CatalogError.MALFORMED)
        }
        val packs = entries<PackEntry>(root, "packs")
            .filter { valid(it) && DATE.matches(it.snapshotDate) }
            .distinctBy { it.id }
        val models = entries<ModelEntry>(root, "models")
            .filter { valid(it) && SAFE_NAME.matches(it.fileName) }
            .distinctBy { it.id }
        val ids = (packs.map { it.id } + models.map { it.id }).toSet()
        val presets = entries<Preset>(root, "presets").map { preset -> preset.copy(items = preset.items.filter { it in ids }) }
        return ParseResult.Ok(Catalog(version, generatedAt, packs, models, presets))
    }

    private inline fun <reified T> entries(root: JsonObject, key: String): List<T> =
        (root[key] as? JsonArray).orEmpty().mapNotNull { runCatching { json.decodeFromJsonElement<T>(it) }.getOrNull() }

    private fun valid(item: CatalogItem): Boolean =
        SAFE_NAME.matches(item.id) &&
            SHA256.matches(item.sha256) &&
            item.sizeBytes > 0 &&
            item.urls.isNotEmpty() &&
            item.urls.all { it.startsWith("https://") }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:catalog:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 8 tests passed.

- [ ] **Step 6: Commit**

```bash
git add gradle/libs.versions.toml settings.gradle.kts core/catalog
git commit -m "Add :core:catalog with Ed25519-verified catalog parsing

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: `CatalogRepository` — fetch, cache, fall back

**Files:**
- Create: `core/catalog/src/main/kotlin/app/granger/core/catalog/CatalogRepository.kt`, `core/catalog/src/test/kotlin/app/granger/core/catalog/FakeHttp.kt`, `.../CatalogRepositoryTest.kt`

**Interfaces:**
- Consumes: `CatalogParser`, `Catalog.forBuild` (Task 2).
- Produces:
  - `data class CatalogState(catalog: Catalog? = null, fetchedAt: Long? = null, refreshing: Boolean = false, error: CatalogError? = null)` — `catalog` is already filtered by `forBuild`.
  - `interface CatalogSource { val state: StateFlow<CatalogState>; suspend fun load(); suspend fun refresh() }` — `load()` reads only the cache; `refresh()` downloads `url` and `"$url.sig"`, and on any failure keeps the shown catalog and sets `error`.
  - `class CatalogRepository(client: OkHttpClient, url: String, publicKey: ByteArray, cacheDir: File, includeUnevaluatedModels: Boolean, io: CoroutineDispatcher = Dispatchers.IO, now: () -> Long = System::currentTimeMillis) : CatalogSource`. Cache files: `cacheDir/catalog.json`, `cacheDir/catalog.json.sig`.

- [ ] **Step 1: Write the failing tests**

`core/catalog/src/test/kotlin/app/granger/core/catalog/FakeHttp.kt`:

```kotlin
package app.granger.core.catalog

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException

/** Answers requests from [routes] without touching the network; unknown URLs get a 404. */
class FakeHttp : Interceptor {
    val routes = mutableMapOf<String, ByteArray>()
    var offline = false
    val requested = mutableListOf<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requested += request.url.toString()
        if (offline) throw IOException("offline")
        val body = routes[request.url.toString()]
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(if (body != null) 200 else 404)
            .message("")
            .body((body ?: ByteArray(0)).toResponseBody())
            .build()
    }

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()
}
```

`core/catalog/src/test/kotlin/app/granger/core/catalog/CatalogRepositoryTest.kt`:

```kotlin
package app.granger.core.catalog

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CatalogRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val url = "https://example.test/catalog.json"
    private val http = FakeHttp()
    private val dispatcher = StandardTestDispatcher()
    private val cacheDir by lazy { File(tmp.root, "catalog") }

    private fun repo(includeUnevaluated: Boolean = true) =
        CatalogRepository(http.client(), url, TestKeys.PUBLIC, cacheDir, includeUnevaluated, dispatcher, now = { 42L })

    private fun serve(bytes: ByteArray, sig: ByteArray = TestKeys.sign(bytes)) {
        http.routes[url] = bytes
        http.routes["$url.sig"] = sig
    }

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    @Test fun refreshVerifiesCachesAndPublishes() = test {
        serve(catalogJson())
        val repo = repo()
        repo.refresh()
        val state = repo.state.value
        assertThat(state.catalog!!.packs.single().title).isEqualTo("WikiMed")
        assertThat(state.fetchedAt).isEqualTo(42L)
        assertThat(state.error).isNull()
        assertThat(state.refreshing).isFalse()
        assertThat(File(cacheDir, "catalog.json").readBytes()).isEqualTo(catalogJson())
        assertThat(File(cacheDir, "catalog.json.sig").exists()).isTrue()
    }

    @Test fun loadUsesTheCacheWithoutTheNetwork() = test {
        serve(catalogJson())
        repo().refresh()
        http.requested.clear()
        http.offline = true

        val fresh = repo()
        fresh.load()
        assertThat(fresh.state.value.catalog!!.packs.single().id).isEqualTo("med")
        assertThat(http.requested).isEmpty()
    }

    @Test fun badSignatureKeepsTheLastGoodCatalog() = test {
        serve(catalogJson())
        val repo = repo()
        repo.refresh()
        val goodBytes = File(cacheDir, "catalog.json").readBytes()

        serve(catalogJson(packTitle = "Evil"), sig = TestKeys.sign(catalogJson()))
        repo.refresh()
        assertThat(repo.state.value.catalog!!.packs.single().title).isEqualTo("WikiMed")
        assertThat(repo.state.value.error).isEqualTo(CatalogError.BAD_SIGNATURE)
        assertThat(File(cacheDir, "catalog.json").readBytes()).isEqualTo(goodBytes)
    }

    @Test fun offlineOrMissingSignatureKeepsTheLastGoodCatalog() = test {
        serve(catalogJson())
        val repo = repo()
        repo.refresh()

        http.offline = true
        repo.refresh()
        assertThat(repo.state.value.catalog).isNotNull()
        assertThat(repo.state.value.error).isEqualTo(CatalogError.NETWORK)
        assertThat(repo.state.value.refreshing).isFalse()

        http.offline = false
        http.routes.remove("$url.sig")
        repo.refresh()
        assertThat(repo.state.value.catalog).isNotNull()
        assertThat(repo.state.value.error).isEqualTo(CatalogError.NETWORK)
    }

    @Test fun firstRunOfflineHasNoCatalog() = test {
        http.offline = true
        val repo = repo()
        repo.refresh()
        assertThat(repo.state.value.catalog).isNull()
        assertThat(repo.state.value.error).isEqualTo(CatalogError.NETWORK)
    }

    @Test fun anOlderSignedCatalogIsNotAcceptedOverANewerOne() = test {
        serve(catalogJson(generatedAt = "2026-10-16T00:00:00Z", packTitle = "New"))
        val repo = repo()
        repo.refresh()

        serve(catalogJson(generatedAt = "2026-09-01T00:00:00Z", packTitle = "Old"))
        repo.refresh()
        assertThat(repo.state.value.catalog!!.packs.single().title).isEqualTo("New")
        assertThat(repo.state.value.error).isEqualTo(CatalogError.OLDER_THAN_CACHED)

        // Also across restarts: the cache is the reference.
        val restarted = repo()
        restarted.refresh()
        assertThat(restarted.state.value.catalog!!.packs.single().title).isEqualTo("New")
    }

    @Test fun aNewerCatalogReplacesTheCache() = test {
        serve(catalogJson(generatedAt = "2026-10-16T00:00:00Z", packTitle = "One"))
        val repo = repo()
        repo.refresh()
        serve(catalogJson(generatedAt = "2026-10-17T00:00:00Z", packTitle = "Two"))
        repo.refresh()
        assertThat(repo.state.value.catalog!!.packs.single().title).isEqualTo("Two")

        val restarted = repo()
        restarted.load()
        assertThat(restarted.state.value.catalog!!.packs.single().title).isEqualTo("Two")
    }

    @Test fun aTamperedCacheIsIgnored() = test {
        cacheDir.mkdirs()
        File(cacheDir, "catalog.json").writeBytes(catalogJson(packTitle = "Edited"))
        File(cacheDir, "catalog.json.sig").writeBytes(TestKeys.sign(catalogJson()))
        val repo = repo()
        repo.load()
        assertThat(repo.state.value.catalog).isNull()
    }

    @Test fun futureFormatKeepsTheLastGoodCatalog() = test {
        serve(catalogJson())
        val repo = repo()
        repo.refresh()
        serve(catalogJson(formatVersion = 2, generatedAt = "2026-12-01T00:00:00Z"))
        repo.refresh()
        assertThat(repo.state.value.catalog!!.formatVersion).isEqualTo(1)
        assertThat(repo.state.value.error).isEqualTo(CatalogError.UNSUPPORTED_FORMAT)
    }

    @Test fun releaseBuildsHideUnevaluatedModels() = test {
        serve(catalogJson())
        val repo = repo(includeUnevaluated = false)
        repo.refresh()
        assertThat(repo.state.value.catalog!!.models.map { it.id }).containsExactly("e2b")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:catalog:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference: CatalogRepository`).

- [ ] **Step 3: Write the repository**

`core/catalog/src/main/kotlin/app/granger/core/catalog/CatalogRepository.kt`:

```kotlin
package app.granger.core.catalog

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.time.Instant

data class CatalogState(
    /** The catalog to show, already filtered for this build; null until one has been verified. */
    val catalog: Catalog? = null,
    /** When [catalog] was downloaded (epoch ms). */
    val fetchedAt: Long? = null,
    val refreshing: Boolean = false,
    /** Why the last refresh kept the old catalog; null after a successful refresh. */
    val error: CatalogError? = null,
)

interface CatalogSource {
    val state: StateFlow<CatalogState>

    /** Shows the last good catalog from the cache. No network. */
    suspend fun load()

    /** Downloads, verifies and caches the catalog. On any failure the shown catalog stays and [CatalogState.error] says why. */
    suspend fun refresh()
}

class CatalogRepository(
    private val client: OkHttpClient,
    private val url: String,
    private val publicKey: ByteArray,
    private val cacheDir: File,
    private val includeUnevaluatedModels: Boolean,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : CatalogSource {
    private val _state = MutableStateFlow(CatalogState())
    override val state: StateFlow<CatalogState> = _state.asStateFlow()

    private val mutex = Mutex()
    private val jsonFile = File(cacheDir, "catalog.json")
    private val sigFile = File(cacheDir, "catalog.json.sig")

    /** The verified catalog before [Catalog.forBuild]; the reference for rejecting older ones. */
    private var current: Catalog? = null

    override suspend fun load() = mutex.withLock { loadCacheOnce() }

    override suspend fun refresh() = mutex.withLock {
        loadCacheOnce()
        _state.update { it.copy(refreshing = true) }
        val error = withContext(io) { fetchAndCache() }
        _state.update { it.copy(refreshing = false, error = error) }
    }

    private suspend fun loadCacheOnce() {
        if (current != null) return
        withContext(io) {
            if (!jsonFile.isFile || !sigFile.isFile) return@withContext
            val parsed = CatalogParser.parse(jsonFile.readBytes(), sigFile.readBytes(), publicKey)
            if (parsed is ParseResult.Ok) show(parsed.catalog, jsonFile.lastModified())
        }
    }

    /** Null on success; otherwise why the shown catalog was kept. */
    private fun fetchAndCache(): CatalogError? {
        val bytes = get(url) ?: return CatalogError.NETWORK
        val signature = get("$url.sig") ?: return CatalogError.NETWORK
        val catalog = when (val parsed = CatalogParser.parse(bytes, signature, publicKey)) {
            is ParseResult.Invalid -> return parsed.error
            is ParseResult.Ok -> parsed.catalog
        }
        // A replayed old catalog is validly signed; never step backwards (it could re-list a pulled pack).
        val shown = current
        if (shown != null && Instant.parse(catalog.generatedAt) < Instant.parse(shown.generatedAt)) {
            return CatalogError.OLDER_THAN_CACHED
        }
        writeCache(bytes, signature)
        show(catalog, now())
        return null
    }

    private fun show(catalog: Catalog, fetchedAt: Long) {
        current = catalog
        _state.update { it.copy(catalog = catalog.forBuild(includeUnevaluatedModels), fetchedAt = fetchedAt) }
    }

    private fun get(target: String): ByteArray? = try {
        client.newCall(Request.Builder().url(target).build()).execute().use { response ->
            val body = response.body
            if (!response.isSuccessful || body.contentLength() > MAX_BYTES) null
            else body.bytes().takeIf { it.size <= MAX_BYTES }
        }
    } catch (e: Exception) {
        null
    }

    private fun writeCache(bytes: ByteArray, signature: ByteArray) {
        cacheDir.mkdirs()
        val tmpJson = File(cacheDir, "catalog.json.tmp").apply { writeBytes(bytes) }
        val tmpSig = File(cacheDir, "catalog.json.sig.tmp").apply { writeBytes(signature) }
        // A crash between the two renames leaves a mismatched pair, which fails verification and is
        // treated as no cache: safe, and fixed by the next refresh.
        tmpJson.renameTo(jsonFile)
        tmpSig.renameTo(sigFile)
    }

    private companion object {
        const val MAX_BYTES = 4L * 1024 * 1024
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:catalog:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 18 tests passed (8 parser + 10 repository).

- [ ] **Step 5: Commit**

```bash
git add core/catalog
git commit -m "Add CatalogRepository: fetch, verify, cache, keep the last good catalog

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Library — keep bookmarks across pack updates; remove packs

**Files:**
- Modify: `core/library/build.gradle.kts`, `core/library/src/main/kotlin/app/granger/core/library/Entities.kt`, `.../Daos.kt`, `.../LibraryDatabase.kt`, `.../Library.kt`, `.../RoomLibrary.kt`, `core/library/src/test/kotlin/app/granger/core/library/Fakes.kt`, `.../RoomLibraryTest.kt`, `app/src/test/kotlin/app/granger/Fakes.kt`
- Create: `core/library/src/test/kotlin/app/granger/core/library/MigrationTest.kt`, `core/library/schemas/app.granger.core.library.LibraryDatabase/2.json` (generated by the build)

**Interfaces:**
- Consumes: `ZimMetadata.name` (already read by `KiwixZimArchive` from the ZIM's `Name` metadata).
- Produces:
  - `Pack.name: String` (last constructor parameter, default `""`; DB column `name TEXT NOT NULL DEFAULT ''`), filled from `ZimMetadata.name`.
  - `LibraryDatabase` version 2 with `AutoMigration(from = 1, to = 2)`.
  - `Library.remove(packId: String): Boolean` — closes the archive, deletes the file, the row, its bookmarks and reading positions. Returns false only if the file exists and could not be deleted. Unknown ids return true.
  - `RoomLibrary.refresh()` re-keys: when a pack id appears that wasn't known before, and a known pack with the same non-blank `name` is no longer on disk (its file was deleted or replaced by the new snapshot), the new pack takes over the old one's bookmarks, reading positions and `addedAt`, and the old row is deleted. Both snapshots present on disk → both listed, nothing moves.
  - DAO additions: `BookmarkDao.moveToPack(from, to)`, `BookmarkDao.deleteForPack(packId)`, `ReadingPositionDao.moveToPack(from, to)`, `ReadingPositionDao.deleteForPack(packId)`.

Plan 3's downloader writes each snapshot to its own file (`<id>_<snapshotDate>.zim`) and deletes the old file only after the new one is verified, then calls `refresh()`. That is the "old file gone, new id with the same Name" case.

- [ ] **Step 1: Write the failing tests**

In `core/library/src/test/kotlin/app/granger/core/library/Fakes.kt`, give `FakeZimArchive` a `name` (the ZIM `Name` metadata) — add it as the last constructor parameter and use it in `metadata`:

```kotlin
class FakeZimArchive(
    override val uuid: String,
    title: String = uuid,
    private val paths: Set<String> = setOf("index"),
    private val redirects: Map<String, String> = emptyMap(),
    override val mainPath: String? = "index",
    override val hasFulltextIndex: Boolean = true,
    var suggestions: List<ZimHit> = emptyList(),
    var results: List<ZimHit> = emptyList(),
    var failSearch: Boolean = false,
    name: String = uuid,
) : ZimArchive {
    override val metadata = ZimMetadata(title, "About $title", "eng", "2026-10-15", name)
```

(the rest of the class is unchanged).

In `core/library/src/test/kotlin/app/granger/core/library/RoomLibraryTest.kt`, replace the `zim` helper with:

```kotlin
    private fun zim(
        dir: File,
        fileName: String,
        uuid: String,
        title: String = uuid,
        paths: Set<String> = setOf("index"),
        name: String = uuid,
    ): File {
        opener.archives[fileName] = { FakeZimArchive(uuid, title, paths, name = name) }
        return File(dir, fileName).apply { writeText("zim $uuid") }
    }
```

and add these tests at the end of the class:

```kotlin
    @Test fun recordsTheZimName() = test {
        zim(internal, "a.zim", "u-a", name = "wikipedia_en_top")
        library.refresh()
        assertThat(packs().single().name).isEqualTo("wikipedia_en_top")
    }

    @Test fun updatedSnapshotKeepsBookmarksPositionsAndAddedAt() = test {
        val old = zim(internal, "top_2026-08.zim", "u-old", name = "wikipedia_en_top", paths = setOf("index", "C++"))
        library.refresh()
        library.setBookmarked("u-old", "C++", "C++", true)
        library.savePosition("u-old", "C++", 0.5f)
        clock = 9_000L

        zim(internal, "top_2026-09.zim", "u-new", name = "wikipedia_en_top", paths = setOf("index", "C++"))
        old.delete()
        library.refresh()

        assertThat(packs().map { it.id }).containsExactly("u-new")
        assertThat(packs().single().status).isEqualTo(PackStatus.OK)
        assertThat(packs().single().addedAt).isEqualTo(1_000L)
        assertThat(library.bookmarks.first().map { it.packId to it.path }).containsExactly("u-new" to "C++")
        assertThat(library.position("u-new", "C++")).isEqualTo(0.5f)
        assertThat(library.position("u-old", "C++")).isNull()
        assertThat(library.startPath("u-new")).isEqualTo("C++")
    }

    @Test fun snapshotReplacedAtTheSamePathKeepsBookmarks() = test {
        zim(internal, "top.zim", "u-old", name = "wikipedia_en_top")
        library.refresh()
        library.setBookmarked("u-old", "index", "Home", true)

        zim(internal, "top.zim", "u-new", name = "wikipedia_en_top")
        library.refresh()

        assertThat(packs().map { it.id }).containsExactly("u-new")
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-new")
    }

    @Test fun bothSnapshotsPresentStayApart() = test {
        zim(internal, "top_2026-08.zim", "u-old", name = "wikipedia_en_top")
        library.refresh()
        library.setBookmarked("u-old", "index", "Home", true)

        zim(sdCard, "top_2026-09.zim", "u-new", name = "wikipedia_en_top")
        library.refresh()

        assertThat(packs().map { it.id }).containsExactly("u-old", "u-new")
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-old")
    }

    @Test fun differentNamesAreNotMerged() = test {
        val old = zim(internal, "med.zim", "u-med", name = "wikipedia_en_medicine")
        library.refresh()
        library.setBookmarked("u-med", "index", "Home", true)
        old.delete()
        zim(internal, "top.zim", "u-top", name = "wikipedia_en_top")
        library.refresh()

        assertThat(packs().first { it.id == "u-med" }.status).isEqualTo(PackStatus.MISSING)
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-med")
    }

    @Test fun blankNamesAreNeverMerged() = test {
        val old = zim(internal, "a.zim", "u-a", name = "")
        library.refresh()
        old.delete()
        zim(internal, "b.zim", "u-b", name = "")
        library.refresh()
        assertThat(packs().map { it.id to it.status })
            .containsExactly("u-a" to PackStatus.MISSING, "u-b" to PackStatus.OK)
    }

    @Test fun movingKeepsOneBookmarkWhenBothSnapshotsHadIt() = test {
        val old = zim(internal, "old.zim", "u-old", name = "n")
        library.refresh()
        library.setBookmarked("u-old", "index", "Old title", true)
        // A bookmark already saved against the new id (e.g. the user opened it before the old one vanished).
        library.setBookmarked("u-new", "index", "New title", true)
        old.delete()
        zim(internal, "new.zim", "u-new", name = "n")
        library.refresh()
        assertThat(library.bookmarks.first().map { it.packId to it.title }).containsExactly("u-new" to "New title")
    }

    @Test fun removeDeletesFileRowBookmarksAndPositions() = test {
        val file = zim(internal, "a.zim", "u-a")
        zim(internal, "b.zim", "u-b")
        library.refresh()
        val opened = library.archive("u-a") as FakeZimArchive
        library.setBookmarked("u-a", "index", "Home", true)
        library.setBookmarked("u-b", "index", "Home", true)
        library.savePosition("u-a", "index", 0.3f)

        assertThat(library.remove("u-a")).isTrue()

        assertThat(file.exists()).isFalse()
        assertThat(opened.closed).isTrue()
        assertThat(packs().map { it.id }).containsExactly("u-b")
        assertThat(library.bookmarks.first().map { it.packId }).containsExactly("u-b")
        assertThat(library.position("u-a", "index")).isNull()
        library.refresh()
        assertThat(packs().map { it.id }).containsExactly("u-b")
    }

    @Test fun removeWorksForMissingAndUnknownPacks() = test {
        val file = zim(sdCard, "a.zim", "u-a")
        library.refresh()
        file.delete()
        library.refresh()
        assertThat(library.remove("u-a")).isTrue()
        assertThat(packs()).isEmpty()
        assertThat(library.remove("nope")).isTrue()
    }
```

`core/library/src/test/kotlin/app/granger/core/library/MigrationTest.kt`:

```kotlin
package app.granger.core.library

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class MigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LibraryDatabase::class.java)

    @Test fun version1PacksGetABlankNameAndKeepTheirRows() {
        helper.createDatabase("migrate.db", 1).apply {
            execSQL(
                "INSERT INTO packs (id, filePath, title, description, language, date, sizeBytes, hasFulltextIndex, status, addedAt) " +
                    "VALUES ('u-a', '/p/a.zim', 'A', '', 'eng', '2026-10-15', 10, 1, 'OK', 5)",
            )
            execSQL("INSERT INTO bookmarks (packId, path, title, createdAt) VALUES ('u-a', 'index', 'Home', 1)")
            close()
        }
        val db = helper.runMigrationsAndValidate("migrate.db", 2, true)
        db.query("SELECT id, name FROM packs").use {
            assertThat(it.moveToFirst()).isTrue()
            assertThat(it.getString(0)).isEqualTo("u-a")
            assertThat(it.getString(1)).isEqualTo("")
        }
        db.query("SELECT COUNT(*) FROM bookmarks").use {
            it.moveToFirst()
            assertThat(it.getInt(0)).isEqualTo(1)
        }
    }
}
```

In `core/library/build.gradle.kts`, add the schemas as test assets (inside `android { … }`) and the testing artifact:

```kotlin
    sourceSets {
        getByName("test") { assets.directories.add("$projectDir/schemas") }
    }
```

```kotlin
    testImplementation(libs.androidx.room.testing)
```

In `app/src/test/kotlin/app/granger/Fakes.kt`, implement the new `Library.remove` in `FakeLibrary` so the app's tests keep compiling:

```kotlin
    val removed = mutableListOf<String>()

    override suspend fun remove(packId: String): Boolean {
        removed += packId
        packList.update { list -> list.filterNot { it.id == packId } }
        bookmarkList.update { list -> list.filterNot { it.packId == packId } }
        return true
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:library:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference: name` on `Pack`, `remove`).

- [ ] **Step 3: Implement**

In `Entities.kt`, add the `name` column as the last property of `Pack`, fill it in `from`, and leave `unreadable` with the default:

```kotlin
import androidx.room.ColumnInfo
```

```kotlin
    val addedAt: Long,
    /** The ZIM's `Name` metadata, shared by every snapshot of the same content; blank if unknown. */
    @ColumnInfo(defaultValue = "") val name: String = "",
) {
    companion object {
        fun from(archive: ZimArchive, file: File, addedAt: Long) = Pack(
            id = archive.uuid,
            filePath = file.absolutePath,
            title = archive.metadata.title,
            description = archive.metadata.description,
            language = archive.metadata.language,
            date = archive.metadata.date,
            sizeBytes = file.length(),
            hasFulltextIndex = archive.hasFulltextIndex,
            status = PackStatus.OK,
            addedAt = addedAt,
            name = archive.metadata.name,
        )
```

In `Daos.kt`, add to `BookmarkDao`:

```kotlin
    /** Moves bookmarks to another pack; one that already exists there wins. */
    @Query("UPDATE OR IGNORE bookmarks SET packId = :to WHERE packId = :from")
    suspend fun moveToPack(from: String, to: String)

    @Query("DELETE FROM bookmarks WHERE packId = :packId")
    suspend fun deleteForPack(packId: String)
```

and to `ReadingPositionDao`:

```kotlin
    @Query("UPDATE OR IGNORE reading_positions SET packId = :to WHERE packId = :from")
    suspend fun moveToPack(from: String, to: String)

    @Query("DELETE FROM reading_positions WHERE packId = :packId")
    suspend fun deleteForPack(packId: String)
```

`LibraryDatabase.kt`:

```kotlin
package app.granger.core.library

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Pack::class, Bookmark::class, ReadingPosition::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class LibraryDatabase : RoomDatabase() {
    abstract fun packDao(): PackDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun readingPositionDao(): ReadingPositionDao

    companion object {
        fun create(context: Context): LibraryDatabase =
            Room.databaseBuilder(context, LibraryDatabase::class.java, "library.db").build()
    }
}
```

In `Library.kt`, add to the interface:

```kotlin
    /**
     * Deletes the pack's file, its entry, bookmarks and reading positions. False only when the file
     * exists and can't be deleted; an unknown id is already gone, so it returns true.
     */
    suspend fun remove(packId: String): Boolean
```

Replace `RoomLibrary.kt` with:

```kotlin
package app.granger.core.library

import androidx.room.withTransaction
import app.granger.core.zim.ZimArchive
import app.granger.core.zim.ZimOpenResult
import app.granger.core.zim.ZimOpener
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [Library] backed by Room. Packs are the `*.zim` files in [packDirs] (one `packs` folder per
 * external-files volume); [refresh] reconciles the table with what is on disk.
 */
class RoomLibrary(
    private val db: LibraryDatabase,
    private val opener: ZimOpener,
    private val packDirs: () -> List<File>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : Library {
    private val packDao = db.packDao()
    private val bookmarkDao = db.bookmarkDao()
    private val positionDao = db.readingPositionDao()
    private val refreshMutex = Mutex()
    private val openArchives = HashMap<String, ZimArchive>()

    override val packs: Flow<List<Pack>> = packDao.observeAll()
    override val bookmarks: Flow<List<Bookmark>> = bookmarkDao.observeAll()

    override suspend fun refresh() = refreshMutex.withLock {
        withContext(io) {
            val known = packDao.getAll()
            val knownIds = known.mapTo(HashSet()) { it.id }
            val files = packDirs()
                .flatMap { dir -> dir.listFiles()?.toList().orEmpty() }
                .filter { it.isFile && it.name.endsWith(".zim", ignoreCase = true) }
                .sortedBy { it.absolutePath }
            val seenPaths = HashSet<String>()
            val seenIds = HashSet<String>()
            val added = ArrayList<Pack>()
            for (file in files) {
                val path = file.absolutePath
                seenPaths += path
                val previous = known.firstOrNull { it.filePath == path }
                val pack = when (val result = opener.open(file)) {
                    is ZimOpenResult.Opened -> result.archive.use { archive ->
                        // A pack moved to another folder or volume keeps its addedAt.
                        val sameZim = previous ?: known.firstOrNull { it.id == archive.uuid }
                        Pack.from(archive, file, sameZim?.addedAt ?: now())
                    }
                    is ZimOpenResult.Failed ->
                        previous?.copy(status = PackStatus.CORRUPT, sizeBytes = file.length())
                            ?: Pack.unreadable(file, now())
                }
                if (!seenIds.add(pack.id)) continue // the same ZIM in two folders: keep the first
                if (previous != null && previous.id != pack.id) {
                    // A different ZIM now sits at this path (e.g. a newer snapshot).
                    packDao.delete(previous.id)
                    closeArchive(previous.id)
                }
                if (pack != previous) {
                    packDao.upsert(pack)
                    closeArchive(pack.id)
                }
                if (pack.id !in knownIds && pack.status == PackStatus.OK) added += pack
            }
            // A new snapshot whose predecessor is gone from disk takes over its bookmarks and positions.
            val superseded = HashSet<String>()
            for (pack in added) {
                if (pack.name.isBlank()) continue
                val old = known.firstOrNull {
                    it.name == pack.name && it.id !in seenIds && it.id !in superseded
                } ?: continue
                inherit(old, pack)
                superseded += old.id
            }
            for (pack in known) {
                if (pack.id in superseded) continue
                if (pack.id !in seenIds && pack.filePath !in seenPaths && pack.status != PackStatus.MISSING) {
                    packDao.upsert(pack.copy(status = PackStatus.MISSING))
                    closeArchive(pack.id)
                }
            }
        }
    }

    private suspend fun inherit(old: Pack, new: Pack) {
        db.withTransaction {
            bookmarkDao.moveToPack(old.id, new.id)
            bookmarkDao.deleteForPack(old.id)
            positionDao.moveToPack(old.id, new.id)
            positionDao.deleteForPack(old.id)
            packDao.delete(old.id)
            packDao.upsert(new.copy(addedAt = old.addedAt))
        }
        closeArchive(old.id)
    }

    override suspend fun remove(packId: String): Boolean = refreshMutex.withLock {
        withContext(io) {
            val pack = packDao.get(packId) ?: return@withContext true
            closeArchive(packId)
            val file = File(pack.filePath)
            if (file.exists() && !file.delete()) return@withContext false
            db.withTransaction {
                bookmarkDao.deleteForPack(packId)
                positionDao.deleteForPack(packId)
                packDao.delete(packId)
            }
            true
        }
    }

    override fun archive(packId: String): ZimArchive? {
        synchronized(openArchives) { openArchives[packId]?.let { return it } }
        val pack = packDao.get(packId)?.takeIf { it.status == PackStatus.OK } ?: return null
        val opened = (opener.open(File(pack.filePath)) as? ZimOpenResult.Opened)?.archive ?: return null
        if (opened.uuid != packId) {
            // The file was replaced (e.g. a new snapshot) since the last refresh; it isn't this pack.
            opened.close()
            return null
        }
        synchronized(openArchives) {
            openArchives[packId]?.let { existing ->
                opened.close() // another thread won the race
                return existing
            }
            openArchives[packId] = opened
            return opened
        }
    }

    override fun cachedArchive(packId: String): ZimArchive? = synchronized(openArchives) { openArchives[packId] }

    private fun closeArchive(packId: String) {
        synchronized(openArchives) { openArchives.remove(packId) }?.close()
    }

    override suspend fun startPath(packId: String): String? = withContext(io) {
        val archive = archive(packId) ?: return@withContext null
        positionDao.lastRead(packId)?.path?.takeIf { archive.resolvePath(it) != null } ?: archive.mainPath
    }

    override fun isBookmarked(packId: String, path: String): Flow<Boolean> =
        bookmarkDao.observeExists(packId, path)

    override suspend fun setBookmarked(packId: String, path: String, title: String, bookmarked: Boolean) {
        if (bookmarked) {
            bookmarkDao.upsert(Bookmark(packId, path, title, now()))
        } else {
            bookmarkDao.delete(packId, path)
        }
    }

    override suspend fun savePosition(packId: String, path: String, scrollFraction: Float) {
        val fraction = if (scrollFraction.isNaN()) 0f else scrollFraction.coerceIn(0f, 1f)
        positionDao.upsert(ReadingPosition(packId, path, fraction, now()))
    }

    override suspend fun position(packId: String, path: String): Float? =
        positionDao.get(packId, path)?.scrollFraction
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:library:testDebugUnitTest :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; the 9 new `RoomLibraryTest` tests and `MigrationTest` pass alongside the existing ones. The build writes `core/library/schemas/app.granger.core.library.LibraryDatabase/2.json`; check it contains `` `name` TEXT NOT NULL DEFAULT '' ``.

If `MigrationTestHelper` can't find the schema under Robolectric (`Cannot find the schema file in the assets folder`), check that `2.json` exists (it is generated by the `kspDebugKotlin` step of the same build) and rerun once.

- [ ] **Step 5: Commit**

```bash
git add core/library app/src/test/kotlin/app/granger/Fakes.kt
git commit -m "Keep bookmarks and positions across pack snapshots; add Library.remove

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Block the reader's network, then add `INTERNET`

**Files:**
- Modify: `app/src/main/kotlin/app/granger/reader/ReaderWebView.kt`, `app/src/main/AndroidManifest.xml`, `app/src/androidTest/kotlin/app/granger/reader/ReaderWebViewTest.kt`

**Interfaces:**
- Consumes: Plan 2's `ReaderWebView` and `ZimWebViewClient` (which already answers every non-ZIM request with 403).
- Produces: the app holds `INTERNET` and `ACCESS_NETWORK_STATE`; `ReaderWebView` has `blockNetworkLoads = true` and Safe Browsing off (per view and app-wide via manifest meta-data, so WebView never phones home to check URLs).

- [ ] **Step 1: Write the failing device test**

Add to `ReaderWebViewTest`:

```kotlin
    @Test fun webViewCannotReachTheNetwork() {
        instrumentation.runOnMainSync {
            assertThat(webView.settings.blockNetworkLoads).isTrue()
            assertThat(webView.settings.safeBrowsingEnabled).isFalse()
        }
        // ZIM content still loads through request interception.
        load("Photosynthesis")
        assertThat(js("document.querySelector('img').naturalWidth")).isEqualTo("1")
    }
```

- [ ] **Step 2: Run it on the Pixel 6 to verify it fails**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.granger.reader.ReaderWebViewTest`
Expected: `webViewCannotReachTheNetwork` FAILS on `blockNetworkLoads` (`expected: true but was: false`).

- [ ] **Step 3: Implement**

In `ReaderWebView.kt`, add to the end of `init` (before `webViewClient = client`):

```kotlin
        // Belt and braces: ZimWebViewClient already refuses every non-ZIM request, and the app now
        // holds INTERNET for downloads. Intercepted ZIM responses are unaffected by this setting.
        settings.blockNetworkLoads = true
        settings.safeBrowsingEnabled = false
```

In `app/src/main/AndroidManifest.xml`, add above `<application`:

```xml
    <!-- Catalog and downloads only. The reader blocks all network loads (ReaderWebView). -->
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

and inside `<application>`:

```xml
        <meta-data android:name="android.webkit.WebView.EnableSafeBrowsing" android:value="false" />
```

- [ ] **Step 4: Run all reader device tests**

Run: `./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.granger.reader.ReaderWebViewTest`
Expected: all `ReaderWebViewTest` tests pass, including the existing rendering, link and scroll tests.

If the existing rendering tests fail with `blockNetworkLoads = true` (pages blank, `net::ERR_ACCESS_DENIED`), the WebView build applies the block before interception: remove the `blockNetworkLoads` line, change the new test's first assertion to `assertThat(webView.settings.safeBrowsingEnabled).isFalse()` only, and add a test that loads a ZIM page containing `<img src="https://example.com/x.png">` (the test ZIM's `index` links to `https://example.com/`) and asserts `ZimResolver` returned `Blocked` for it. Record which path was taken in the commit message.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/app/granger/reader/ReaderWebView.kt app/src/main/AndroidManifest.xml app/src/androidTest
git commit -m "Block reader network loads and Safe Browsing; add INTERNET for downloads

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 6: `:core:downloads` — `FileDownloader`

**Files:**
- Create: `core/downloads/build.gradle.kts`, `core/downloads/src/main/AndroidManifest.xml`, `core/downloads/src/main/kotlin/app/granger/core/downloads/Sha256.kt`, `.../FileDownloader.kt`, `core/downloads/src/test/kotlin/app/granger/core/downloads/FakeServer.kt`, `.../FileDownloaderTest.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `libs.okhttp`, `libs.androidx.work.runtime`, Room aliases, `:core:catalog` (Task 2).
- Produces (package `app.granger.core.downloads`):
  - `data class DownloadRequest(urls: List<String>, sizeBytes: Long, sha256: String)`.
  - `sealed interface DownloadResult { data object Done; data object HashMismatch; data class NoSpace(neededBytes: Long); data class Failed(reason: String) }` — `HashMismatch` has already deleted the part file; `Failed` keeps the bytes so the next run resumes.
  - `class FileDownloader(client: OkHttpClient, freeBytes: (File) -> Long = { it.usableSpace }, reserveBytes: Long = DEFAULT_RESERVE_BYTES, io: CoroutineDispatcher = Dispatchers.IO)` with `suspend fun download(request: DownloadRequest, part: File, onProgress: (Long) -> Unit = {}): DownloadResult`. `onProgress` gets total bytes on disk, about every 256 KB and at the end, on the download thread.
  - `internal suspend fun hashFile(file: File, digest: MessageDigest, onBytes: (Long) -> Unit = {}): Long`, `internal fun ByteArray.toHex(): String`, `internal const val BUFFER_SIZE`.
  - Test helper `FakeServer` (an OkHttp interceptor; later tasks' tests reuse it).

How a download goes: re-hash whatever is already in `part` (so a resume still checks every byte), then try each URL in order from the current offset with `Range: bytes=<offset>-`. `206` with a matching `Content-Range` appends; `200` means the server ignored the range, so the file restarts from zero; anything else, a size that doesn't match, or a dropped connection moves on to the next URL from wherever the bytes got to. Before every chunk is written, free space minus the chunk must stay above `reserveBytes`. When all bytes are there, compare the sha256.

The spec lists an instrumented download test against a local test server. This plan runs those scenarios on the JVM through `FakeServer`, an OkHttp interceptor (deterministic, no device, no flaky sockets), and does the real-network run on the Pixel 6 in Task 14.

- [ ] **Step 1: Create the module**

In `settings.gradle.kts`:

```kotlin
include(":app", ":core:ai", ":core:zim", ":core:library", ":core:catalog", ":core:downloads")
```

`core/downloads/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "app.granger.core.downloads"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric reads FileDescriptor internals by reflection, which JDK 17+ blocks by default.
        unitTests.all { it.jvmArgs("--add-opens=java.base/java.io=ALL-UNNAMED") }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    api(project(":core:catalog"))
    api(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}
```

`core/downloads/src/main/AndroidManifest.xml` (Task 8 fills it in):

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest />
```

- [ ] **Step 2: Write the failing tests**

`core/downloads/src/test/kotlin/app/granger/core/downloads/FakeServer.kt`:

```kotlin
package app.granger.core.downloads

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import java.io.IOException
import java.security.MessageDigest

/**
 * A fake HTTP server as an OkHttp interceptor: serves [files] by URL with Range support, and can
 * ignore ranges, drop the connection after N bytes, or answer with a fixed status.
 */
class FakeServer : Interceptor {
    val files = mutableMapOf<String, ByteArray>()
    /** Served once for that URL, then [files] takes over. */
    val once = mutableMapOf<String, ByteArray>()
    val failAfter = mutableMapOf<String, Int>()
    val ignoreRange = mutableSetOf<String>()
    val status = mutableMapOf<String, Int>()
    val requests = mutableListOf<Request>()

    fun client(): OkHttpClient = OkHttpClient.Builder().addInterceptor(this).build()

    fun rangesRequested(url: String): List<String?> =
        requests.filter { it.url.toString() == url }.map { it.header("Range") }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        requests += request
        val url = request.url.toString()
        val response = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).message("")
        status[url]?.let { return response.code(it).body(ByteArray(0).toResponseBody()).build() }
        val content = once.remove(url) ?: files[url]
            ?: return response.code(404).body(ByteArray(0).toResponseBody()).build()
        val start = request.header("Range")?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()
        val partial = start != null && url !in ignoreRange
        val bytes = if (partial) content.copyOfRange(start!!, content.size) else content
        if (partial) response.header("Content-Range", "bytes $start-${content.size - 1}/${content.size}")
        return response
            .code(if (partial) 206 else 200)
            .body(DroppingBody(bytes, failAfter[url]))
            .build()
    }

    private class DroppingBody(private val bytes: ByteArray, private val failAfter: Int?) : ResponseBody() {
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = bytes.size.toLong()
        override fun source(): BufferedSource = object : Source {
            private var pos = 0
            override fun read(sink: Buffer, byteCount: Long): Long {
                if (failAfter != null && pos >= failAfter) throw IOException("connection reset")
                if (pos >= bytes.size) return -1
                val end = minOf(bytes.size.toLong(), pos + byteCount, (failAfter ?: Int.MAX_VALUE).toLong()).toInt()
                sink.write(bytes, pos, end - pos)
                val read = end - pos
                pos = end
                return read.toLong()
            }
            override fun timeout() = Timeout.NONE
            override fun close() {}
        }.buffer()
    }
}

fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
```

`core/downloads/src/test/kotlin/app/granger/core/downloads/FileDownloaderTest.kt`:

```kotlin
package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class FileDownloaderTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = FakeServer()
    private val content = Random(7).nextBytes(100_000)
    private val a = "https://a.test/f.zim"
    private val b = "https://b.test/f.zim"
    private var freeBytes: (File) -> Long = { Long.MAX_VALUE / 2 }
    private val downloader by lazy { FileDownloader(server.client(), freeBytes = { freeBytes(it) }, reserveBytes = 1_000) }
    private val part by lazy { File(tmp.root, "packs/f.zim.part") }

    private fun request(vararg urls: String) = DownloadRequest(urls.toList(), content.size.toLong(), sha256(content))

    private fun partWith(bytes: ByteArray) {
        part.parentFile!!.mkdirs()
        part.writeBytes(bytes)
    }

    @Test fun downloadsAndVerifies() = runTest {
        server.files[a] = content
        val progress = mutableListOf<Long>()
        val result = downloader.download(request(a), part) { progress += it }
        assertThat(result).isEqualTo(DownloadResult.Done)
        assertThat(part.readBytes()).isEqualTo(content)
        assertThat(progress.first()).isEqualTo(0L)
        assertThat(progress.last()).isEqualTo(content.size.toLong())
        assertThat(server.rangesRequested(a)).containsExactly(null)
    }

    @Test fun resumesFromPartFile() = runTest {
        server.files[a] = content
        partWith(content.copyOf(40_000))
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(a)).containsExactly("bytes=40000-")
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun serverIgnoringRangeRestartsTheFile() = runTest {
        server.files[a] = content
        server.ignoreRange += a
        partWith(content.copyOf(40_000))
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(part.length()).isEqualTo(content.size.toLong())
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun partLargerThanTheFileStartsOver() = runTest {
        server.files[a] = content
        partWith(Random(9).nextBytes(150_000))
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(a)).containsExactly(null)
    }

    @Test fun completePartNeedsNoNetwork() = runTest {
        partWith(content)
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.requests).isEmpty()
    }

    @Test fun corruptCompletePartIsAMismatch() = runTest {
        partWith(Random(3).nextBytes(content.size))
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.HashMismatch)
        assertThat(part.exists()).isFalse()
    }

    @Test fun connectionDropContinuesOnNextMirror() = runTest {
        server.files[a] = content
        server.files[b] = content
        server.failAfter[a] = 30_000
        assertThat(downloader.download(request(a, b), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(b)).containsExactly("bytes=30000-")
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun failedAttemptKeepsBytesForNextRun() = runTest {
        server.files[a] = content
        server.failAfter[a] = 30_000
        val first = downloader.download(request(a), part)
        assertThat(first).isInstanceOf(DownloadResult.Failed::class.java)
        assertThat(part.length()).isEqualTo(30_000L)

        server.failAfter.remove(a)
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.Done)
        assertThat(server.rangesRequested(a).last()).isEqualTo("bytes=30000-")
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun notFoundTriesTheNextMirror() = runTest {
        server.files[b] = content
        assertThat(downloader.download(request(a, b), part)).isEqualTo(DownloadResult.Done)
    }

    @Test fun mirrorWithADifferentFileIsSkipped() = runTest {
        server.files[a] = Random(5).nextBytes(5_000)
        server.files[b] = content
        assertThat(downloader.download(request(a, b), part)).isEqualTo(DownloadResult.Done)
        assertThat(part.readBytes()).isEqualTo(content)
    }

    @Test fun wrongContentIsAMismatchAndDeleted() = runTest {
        server.files[a] = Random(8).nextBytes(content.size)
        assertThat(downloader.download(request(a), part)).isEqualTo(DownloadResult.HashMismatch)
        assertThat(part.exists()).isFalse()
    }

    @Test fun stopsBeforeTheDiskFills() = runTest {
        server.files[a] = content
        freeBytes = { 21_000 - part.length() }
        val result = downloader.download(request(a), part)
        assertThat(result).isInstanceOf(DownloadResult.NoSpace::class.java)
        assertThat((result as DownloadResult.NoSpace).neededBytes).isEqualTo(content.size - part.length())
        assertThat(part.length()).isAtMost(20_000L)
    }

    @Test fun everyMirrorFailingReportsTheLastError() = runTest {
        server.status[a] = 503
        val result = downloader.download(request(a), part)
        assertThat((result as DownloadResult.Failed).reason).contains("503")
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :core:downloads:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference: FileDownloader`).

- [ ] **Step 4: Implement**

`core/downloads/src/main/kotlin/app/granger/core/downloads/Sha256.kt`:

```kotlin
package app.granger.core.downloads

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

internal const val BUFFER_SIZE = 64 * 1024

/** Feeds [file] into [digest] and returns how many bytes it read (0 when there is no file). */
internal suspend fun hashFile(file: File, digest: MessageDigest, onBytes: (Long) -> Unit = {}): Long {
    if (!file.isFile) return 0
    var total = 0L
    FileInputStream(file).use { input ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            total += n
            onBytes(total)
        }
    }
    return total
}

internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
```

`core/downloads/src/main/kotlin/app/granger/core/downloads/FileDownloader.kt`:

```kotlin
package app.granger.core.downloads

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest

data class DownloadRequest(val urls: List<String>, val sizeBytes: Long, val sha256: String)

sealed interface DownloadResult {
    data object Done : DownloadResult

    /** The bytes didn't match the expected sha256. The part file has been deleted. */
    data object HashMismatch : DownloadResult

    /** Writing on would leave less than the reserve free. The bytes so far are kept. */
    data class NoSpace(val neededBytes: Long) : DownloadResult

    /** Every URL failed; [reason] is the last error. The bytes so far are kept for a resume. */
    data class Failed(val reason: String) : DownloadResult
}

/**
 * Downloads one file into a `.part` file with HTTP Range resume, mirror fallback, a streaming
 * sha256 and a free-space check before every write. It never renames or registers anything.
 */
class FileDownloader(
    private val client: OkHttpClient,
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun download(request: DownloadRequest, part: File, onProgress: (Long) -> Unit = {}): DownloadResult =
        withContext(io) {
            val dir = part.absoluteFile.parentFile
            if (dir == null || (!dir.isDirectory && !dir.mkdirs())) {
                return@withContext DownloadResult.Failed("Can't create the download folder")
            }
            if (part.length() > request.sizeBytes) part.delete()
            // Hash what an earlier run left, so the final check covers every byte of the file.
            val digest = MessageDigest.getInstance("SHA-256")
            var offset = hashFile(part, digest)
            onProgress(offset)
            var lastError = "No download address"
            for (url in request.urls) {
                if (offset >= request.sizeBytes) break
                val attempt = fetch(url, request, part, dir, digest, offset, onProgress)
                ensureActive()
                offset = attempt.offset
                // A failed write can leave bytes the digest never saw; cut the file back to what it covers.
                if (part.length() != offset) RandomAccessFile(part, "rw").use { it.setLength(offset) }
                if (attempt.noSpace) return@withContext DownloadResult.NoSpace(request.sizeBytes - offset)
                attempt.error?.let { lastError = it }
            }
            if (offset < request.sizeBytes) return@withContext DownloadResult.Failed(lastError)
            if (digest.digest().toHex() != request.sha256.lowercase()) {
                part.delete()
                return@withContext DownloadResult.HashMismatch
            }
            DownloadResult.Done
        }

    private class Attempt(val offset: Long, val error: String? = null, val noSpace: Boolean = false)

    private suspend fun fetch(
        url: String,
        request: DownloadRequest,
        part: File,
        dir: File,
        digest: MessageDigest,
        start: Long,
        onProgress: (Long) -> Unit,
    ): Attempt {
        val builder = try {
            Request.Builder().url(url)
        } catch (e: IllegalArgumentException) {
            return Attempt(start, "Bad address: $url")
        }
        if (start > 0) builder.header("Range", "bytes=$start-")
        val call = client.newCall(builder.build())
        // Cancelling the coroutine aborts a read that is blocked on the network.
        val cancelHook = currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        var offset = start
        try {
            call.execute().use { response ->
                val host = response.request.url.host
                val restart = when (response.code) {
                    206 -> {
                        if (start == 0L || !response.header("Content-Range").orEmpty().startsWith("bytes $start-")) {
                            return Attempt(offset, "$host sent the wrong part of the file")
                        }
                        false
                    }
                    200 -> true // no range support: start the file again
                    else -> return Attempt(offset, "$host answered ${response.code}")
                }
                val body = response.body
                val expected = if (restart) request.sizeBytes else request.sizeBytes - start
                if (body.contentLength() != -1L && body.contentLength() != expected) {
                    return Attempt(offset, "$host has a different file")
                }
                if (restart && start > 0) {
                    digest.reset()
                    offset = 0
                }
                FileOutputStream(part, !restart).use { out ->
                    try {
                        val input = body.byteStream()
                        val buffer = ByteArray(BUFFER_SIZE)
                        var reported = offset
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buffer)
                            if (n < 0) break
                            if (offset + n > request.sizeBytes) return Attempt(offset, "$host sent too many bytes")
                            if (freeBytes(dir) - n < reserveBytes) return Attempt(offset, noSpace = true)
                            out.write(buffer, 0, n)
                            digest.update(buffer, 0, n)
                            offset += n
                            if (offset - reported >= PROGRESS_STEP || offset == request.sizeBytes) {
                                onProgress(offset)
                                reported = offset
                            }
                        }
                    } finally {
                        out.flush()
                        out.fd.sync()
                    }
                }
                return if (offset < request.sizeBytes) Attempt(offset, "$host closed the connection early") else Attempt(offset)
            }
        } catch (e: IOException) {
            return Attempt(offset, e.message ?: "Network error")
        } finally {
            cancelHook.dispose()
        }
    }

    companion object {
        /** Never fill a volume past this, whatever the queue check allowed. */
        const val DEFAULT_RESERVE_BYTES = 64L * 1024 * 1024
        private const val PROGRESS_STEP = 256L * 1024
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :core:downloads:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, 13 tests passed.

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts core/downloads
git commit -m "Add :core:downloads FileDownloader: Range resume, mirrors, streaming sha256, space checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Download ledger, volumes, queue policy, `DownloadManager`

**Files:**
- Create: `core/downloads/src/main/kotlin/app/granger/core/downloads/DownloadRecord.kt`, `.../DownloadDatabase.kt`, `.../Volumes.kt`, `.../QueuePolicy.kt`, `.../Downloads.kt`, `core/downloads/src/test/kotlin/app/granger/core/downloads/TestDb.kt`, `.../QueuePolicyTest.kt`, `.../DownloadManagerTest.kt`, `core/downloads/schemas/app.granger.core.downloads.DownloadDatabase/1.json` (generated)

**Interfaces:**
- Consumes: `CatalogItem`, `PackEntry`, `ModelEntry` (Task 2).
- Produces:
  - `enum class ItemKind { PACK, MODEL }`; `enum class DownloadState { QUEUED, RUNNING, FAILED, INSTALLED }`.
  - `@Entity(tableName = "downloads", primaryKeys = ["itemId", "sha256"]) data class DownloadRecord(itemId: String, sha256: String, kind: ItemKind, title: String, urls: List<String>, sizeBytes: Long, filePath: String, snapshotDate: String, wifiOnly: Boolean, state: DownloadState, bytesDone: Long = 0, error: String? = null, hashFailures: Int = 0, verifiedAt: Long? = null, createdAt: Long, updatedAt: Long)`; extensions `DownloadRecord.file: File`, `DownloadRecord.partFile: File` (`"$filePath.part"`), `DownloadRecord.isActive: Boolean` (`state != INSTALLED`). One row per version of an item: an update has its new row next to the installed old one until it installs.
  - `DownloadDao` (methods listed in Step 3), `DownloadDatabase.create(context)`.
  - `data class Volume(root: File, label: String, removable: Boolean, freeBytes: Long, totalBytes: Long, grangerBytes: Long)`; `fun interface VolumeSource { fun list(): List<Volume> }` (blocking; mounted volumes, internal first); `class AndroidVolumes(context) : VolumeSource`. A volume's `root` is the app's external-files dir on it; packs go in `root/packs`, models in `root/models`.
  - `sealed interface EnqueueResult { Queued; AlreadyActive; AlreadyInstalled; NoSpace(neededBytes); OverCap(capBytes, wouldUseBytes) }`; `object QueuePolicy { RESERVE_BYTES; fun check(...) }`.
  - `interface Downloads { val records: Flow<List<DownloadRecord>>; suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult; suspend fun retry(itemId: String); suspend fun cancel(itemId: String); suspend fun remove(itemId: String); suspend fun repair(itemId: String); suspend fun recordCheck(itemId: String, sha256: String, verified: Boolean); suspend fun setWifiOnly(wifiOnly: Boolean) }`.
  - `interface DownloadScheduler { fun schedule(record: DownloadRecord, replace: Boolean = false); fun cancel(itemId: String) }`.
  - `class DownloadManager(dao: DownloadDao, scheduler: DownloadScheduler, installedPackBytes: suspend () -> Long, capBytes: () -> Long = { Long.MAX_VALUE }, freeBytes: (File) -> Long = { it.usableSpace }, io: CoroutineDispatcher = Dispatchers.IO, now: () -> Long = System::currentTimeMillis) : Downloads`.
  - Target files: pack → `<volumeRoot>/packs/<id>_<snapshotDate>.zim`; model → `<volumeRoot>/models/<fileName>`.

- [ ] **Step 1: Write the failing tests**

`core/downloads/src/test/kotlin/app/granger/core/downloads/TestDb.kt`:

```kotlin
package app.granger.core.downloads

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.PackEntry

fun inMemoryDownloads(): DownloadDatabase =
    Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), DownloadDatabase::class.java)
        .allowMainThreadQueries()
        .build()

val SHA_1 = "1".repeat(64)
val SHA_2 = "2".repeat(64)

fun packEntry(id: String = "med", sha256: String = SHA_1, size: Long = 1_000, date: String = "2026-10-10", urls: List<String> = listOf("https://a.test/$id.zim")) =
    PackEntry(
        id = id, title = "Pack $id", category = "Medical", language = "eng", sizeBytes = size, sha256 = sha256,
        urls = urls, snapshotDate = date, license = "CC BY-SA 4.0", hasFulltextIndex = true,
        zimUuid = "u-$id", zimName = "n-$id",
    )

fun modelEntry(id: String = "e2b", sha256: String = SHA_2, size: Long = 2_000, url: String = "https://hf.test/$id") =
    ModelEntry(
        id = id, title = "Model $id", role = ModelRole.SMALL, fileName = "$id.litertlm", sizeBytes = size,
        minRamGb = 6, sha256 = sha256, url = url, license = "Apache-2.0", evalPassed = true,
    )

class FakeScheduler : DownloadScheduler {
    val scheduled = mutableListOf<Pair<DownloadRecord, Boolean>>()
    val cancelled = mutableListOf<String>()
    override fun schedule(record: DownloadRecord, replace: Boolean) { scheduled += record to replace }
    override fun cancel(itemId: String) { cancelled += itemId }
}
```

`core/downloads/src/test/kotlin/app/granger/core/downloads/QueuePolicyTest.kt`:

```kotlin
package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class QueuePolicyTest {
    private val gb = 1_000_000_000L
    private val roomy = 1_000 * gb

    private fun check(
        itemBytes: Long = gb, isPack: Boolean = true, installed: Long = 0, queued: Long = 0, replaced: Long = 0,
        cap: Long = 10 * gb, free: Long = roomy, queuedOnVolume: Long = 0,
    ) = QueuePolicy.check(itemBytes, isPack, installed, queued, replaced, cap, free, queuedOnVolume)

    @Test fun underTheCapQueues() {
        assertThat(check(installed = 8 * gb, queued = gb)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun installedPlusQueuedPacksOverTheCapAreRefused() {
        assertThat(check(itemBytes = gb, installed = 9 * gb, queued = gb / 2))
            .isEqualTo(EnqueueResult.OverCap(10 * gb, 10 * gb + gb / 2))
    }

    @Test fun modelsIgnoreTheCap() {
        assertThat(check(isPack = false, installed = 10 * gb)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun anUpdateDoesNotCountTheOldSnapshotTwice() {
        assertThat(check(itemBytes = 2 * gb, installed = 9 * gb, replaced = 2 * gb)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun spaceCountsOtherQueuedDownloadsAndTheReserve() {
        val free = gb + QueuePolicy.RESERVE_BYTES
        assertThat(check(itemBytes = gb, free = free)).isEqualTo(EnqueueResult.Queued)
        assertThat(check(itemBytes = gb, free = free, queuedOnVolume = 300)).isEqualTo(EnqueueResult.NoSpace(300))
        assertThat(check(itemBytes = gb, free = free - 1)).isEqualTo(EnqueueResult.NoSpace(1))
    }

    @Test fun capIsCheckedBeforeSpace() {
        assertThat(check(itemBytes = 11 * gb, free = 0)).isInstanceOf(EnqueueResult.OverCap::class.java)
    }
}
```

`core/downloads/src/test/kotlin/app/granger/core/downloads/DownloadManagerTest.kt`:

```kotlin
package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadManagerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDownloads()
    private val dao = db.downloadDao()
    private val scheduler = FakeScheduler()
    private val dispatcher = StandardTestDispatcher()
    private var installedPackBytes = 0L
    private var cap = Long.MAX_VALUE
    private val free = mutableMapOf<String, Long>()
    private val internal by lazy { tmp.newFolder("internal") }
    private val sd by lazy { tmp.newFolder("sd") }
    private val manager = DownloadManager(
        dao, scheduler,
        installedPackBytes = { installedPackBytes },
        capBytes = { cap },
        freeBytes = { free[it.path] ?: Long.MAX_VALUE / 2 },
        io = dispatcher,
        now = { 7L },
    )

    @After fun close() = db.close()

    private fun test(block: suspend TestScope.() -> Unit) = runTest(dispatcher) { block() }

    private suspend fun records() = manager.records.first()

    private suspend fun installed(entry: app.granger.core.catalog.CatalogItem, filePath: String) {
        dao.upsert(
            DownloadRecord(
                itemId = entry.id, sha256 = entry.sha256, kind = ItemKind.PACK, title = entry.title, urls = entry.urls,
                sizeBytes = entry.sizeBytes, filePath = filePath, snapshotDate = "2026-09-01", wifiOnly = true,
                state = DownloadState.INSTALLED, bytesDone = entry.sizeBytes, createdAt = 1, updatedAt = 1,
            ),
        )
    }

    @Test fun enqueueWritesARecordAndSchedulesIt() = test {
        assertThat(manager.enqueue(packEntry(), internal, wifiOnly = true)).isEqualTo(EnqueueResult.Queued)
        val record = records().single()
        assertThat(record.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.kind).isEqualTo(ItemKind.PACK)
        assertThat(record.filePath).isEqualTo(File(internal, "packs/med_2026-10-10.zim").path)
        assertThat(record.partFile.path).isEqualTo(File(internal, "packs/med_2026-10-10.zim.part").path)
        assertThat(record.urls).containsExactly("https://a.test/med.zim")
        assertThat(record.wifiOnly).isTrue()
        assertThat(record.createdAt).isEqualTo(7L)
        assertThat(scheduler.scheduled.single()).isEqualTo(record to false)
    }

    @Test fun modelsGoToTheModelsFolder() = test {
        manager.enqueue(modelEntry(), sd, wifiOnly = false)
        val record = records().single()
        assertThat(record.kind).isEqualTo(ItemKind.MODEL)
        assertThat(record.filePath).isEqualTo(File(sd, "models/e2b.litertlm").path)
        assertThat(record.wifiOnly).isFalse()
    }

    @Test fun enqueueTwiceIsOneDownload() = test {
        manager.enqueue(packEntry(), internal, true)
        assertThat(manager.enqueue(packEntry(), internal, true)).isEqualTo(EnqueueResult.AlreadyActive)
        assertThat(records()).hasSize(1)
        assertThat(scheduler.scheduled).hasSize(1)
    }

    @Test fun anInstalledVersionIsNotDownloadedAgain() = test {
        installed(packEntry(), "/x/med.zim")
        assertThat(manager.enqueue(packEntry(), internal, true)).isEqualTo(EnqueueResult.AlreadyInstalled)
        assertThat(scheduler.scheduled).isEmpty()
    }

    @Test fun aNewerVersionQueuesNextToTheInstalledOne() = test {
        installed(packEntry(sha256 = SHA_1), "/x/med_2026-09-01.zim")
        assertThat(manager.enqueue(packEntry(sha256 = SHA_2), internal, true)).isEqualTo(EnqueueResult.Queued)
        assertThat(records().map { it.sha256 to it.state })
            .containsExactly(SHA_1 to DownloadState.INSTALLED, SHA_2 to DownloadState.QUEUED)
    }

    @Test fun capRefusesPacksButNotModels() = test {
        cap = 1_500
        installedPackBytes = 1_000
        assertThat(manager.enqueue(packEntry(size = 1_000), internal, true)).isEqualTo(EnqueueResult.OverCap(1_500, 2_000))
        assertThat(manager.enqueue(modelEntry(size = 5_000), internal, true)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun queuedPacksCountTowardsTheCap() = test {
        cap = 2_500
        manager.enqueue(packEntry(id = "a", size = 1_000), internal, true)
        manager.enqueue(packEntry(id = "b", size = 1_000), internal, true)
        assertThat(manager.enqueue(packEntry(id = "c", size = 1_000), internal, true))
            .isEqualTo(EnqueueResult.OverCap(2_500, 3_000))
    }

    @Test fun queuedDownloadsOnTheSameVolumeCount() = test {
        free[internal.path] = QueuePolicy.RESERVE_BYTES + 1_500
        free[sd.path] = QueuePolicy.RESERVE_BYTES + 1_500
        assertThat(manager.enqueue(packEntry(id = "a"), internal, true)).isEqualTo(EnqueueResult.Queued)
        assertThat(manager.enqueue(packEntry(id = "b"), internal, true)).isEqualTo(EnqueueResult.NoSpace(500))
        assertThat(manager.enqueue(packEntry(id = "b"), sd, true)).isEqualTo(EnqueueResult.Queued)
    }

    @Test fun cancelDeletesPartAndRecord() = test {
        manager.enqueue(packEntry(), internal, true)
        val record = records().single()
        record.partFile.parentFile!!.mkdirs()
        record.partFile.writeText("half")
        manager.cancel("med")
        assertThat(records()).isEmpty()
        assertThat(record.partFile.exists()).isFalse()
        assertThat(scheduler.cancelled).containsExactly("med")
    }

    @Test fun cancelKeepsTheInstalledVersion() = test {
        installed(packEntry(sha256 = SHA_1), "/x/med_2026-09-01.zim")
        manager.enqueue(packEntry(sha256 = SHA_2), internal, true)
        manager.cancel("med")
        assertThat(records().map { it.sha256 }).containsExactly(SHA_1)
    }

    @Test fun retryRequeuesAFailedDownload() = test {
        manager.enqueue(packEntry(), internal, true)
        dao.setState("med", SHA_1, DownloadState.FAILED, "Checksum mismatch", 0, 2, 9)
        manager.retry("med")
        val record = records().single()
        assertThat(record.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.error).isNull()
        assertThat(record.hashFailures).isEqualTo(0)
        assertThat(scheduler.scheduled.last().second).isTrue()
    }

    @Test fun removeDeletesEveryVersionAndFile() = test {
        val old = File(internal, "packs/med_2026-09-01.zim").apply { parentFile!!.mkdirs(); writeText("old") }
        installed(packEntry(sha256 = SHA_1), old.path)
        manager.enqueue(packEntry(sha256 = SHA_2), internal, true)
        val part = records().first { it.sha256 == SHA_2 }.partFile.apply { writeText("new") }
        manager.remove("med")
        assertThat(records()).isEmpty()
        assertThat(old.exists()).isFalse()
        assertThat(part.exists()).isFalse()
        assertThat(scheduler.cancelled).contains("med")
    }

    @Test fun repairRequeuesAnInstalledItem() = test {
        installed(packEntry(), File(internal, "packs/med.zim").path)
        dao.setVerifiedAt("med", SHA_1, 5)
        manager.repair("med")
        val record = records().single()
        assertThat(record.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.bytesDone).isEqualTo(0)
        assertThat(record.verifiedAt).isNull()
        assertThat(scheduler.scheduled.single().second).isTrue()
    }

    @Test fun setWifiOnlyReschedulesActiveDownloads() = test {
        installed(packEntry(id = "old"), "/x/old.zim")
        manager.enqueue(packEntry(id = "a"), internal, wifiOnly = true)
        manager.setWifiOnly(false)
        assertThat(records().first { it.itemId == "a" }.wifiOnly).isFalse()
        assertThat(records().first { it.itemId == "old" }.wifiOnly).isTrue()
        assertThat(scheduler.scheduled.last().let { it.first.itemId to it.second }).isEqualTo("a" to true)
        assertThat(scheduler.scheduled.last().first.wifiOnly).isFalse()
    }

    @Test fun recordCheckSetsAndClearsVerifiedAt() = test {
        installed(packEntry(), "/x/med.zim")
        manager.recordCheck("med", SHA_1, verified = true)
        assertThat(records().single().verifiedAt).isEqualTo(7L)
        manager.recordCheck("med", SHA_1, verified = false)
        assertThat(records().single().verifiedAt).isNull()
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:downloads:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference: DownloadManager`).

- [ ] **Step 3: Implement**

`core/downloads/src/main/kotlin/app/granger/core/downloads/DownloadRecord.kt`:

```kotlin
package app.granger.core.downloads

import androidx.room.Entity
import java.io.File

enum class ItemKind { PACK, MODEL }

enum class DownloadState { QUEUED, RUNNING, FAILED, INSTALLED }

/**
 * One version of one catalog item: waiting, downloading, failed, or installed. Installed records are
 * the ledger Readiness checks files against, so they outlive the catalog entry that created them.
 */
@Entity(tableName = "downloads", primaryKeys = ["itemId", "sha256"])
data class DownloadRecord(
    val itemId: String,
    val sha256: String,
    val kind: ItemKind,
    val title: String,
    val urls: List<String>,
    val sizeBytes: Long,
    /** Where the verified file ends up; it is written to `<filePath>.part` first. */
    val filePath: String,
    /** Packs only; blank for models. */
    val snapshotDate: String,
    val wifiOnly: Boolean,
    val state: DownloadState,
    val bytesDone: Long = 0,
    val error: String? = null,
    val hashFailures: Int = 0,
    /** When the file last passed a full sha256 check (at install, or from Readiness). */
    val verifiedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
)

val DownloadRecord.file: File get() = File(filePath)
val DownloadRecord.partFile: File get() = File("$filePath.part")
val DownloadRecord.isActive: Boolean get() = state != DownloadState.INSTALLED
```

`core/downloads/src/main/kotlin/app/granger/core/downloads/DownloadDatabase.kt`:

```kotlin
package app.granger.core.downloads

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY createdAt")
    fun observeAll(): Flow<List<DownloadRecord>>

    @Query("SELECT * FROM downloads")
    suspend fun getAll(): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId")
    suspend fun forItem(itemId: String): List<DownloadRecord>

    @Query("SELECT * FROM downloads WHERE itemId = :itemId AND sha256 = :sha256")
    suspend fun get(itemId: String, sha256: String): DownloadRecord?

    @Upsert
    suspend fun upsert(record: DownloadRecord)

    @Query("DELETE FROM downloads WHERE itemId = :itemId AND sha256 = :sha256")
    suspend fun delete(itemId: String, sha256: String)

    /** Updates in place and returns the rows changed; 0 means the record was cancelled or removed. */
    @Query(
        "UPDATE downloads SET state = :state, error = :error, bytesDone = :bytesDone, hashFailures = :hashFailures, " +
            "updatedAt = :now WHERE itemId = :itemId AND sha256 = :sha256",
    )
    suspend fun setState(
        itemId: String, sha256: String, state: DownloadState, error: String?, bytesDone: Long, hashFailures: Int, now: Long,
    ): Int

    @Query(
        "UPDATE downloads SET state = 'INSTALLED', bytesDone = sizeBytes, error = NULL, hashFailures = 0, " +
            "verifiedAt = :now, updatedAt = :now WHERE itemId = :itemId AND sha256 = :sha256",
    )
    suspend fun markInstalled(itemId: String, sha256: String, now: Long): Int

    /** Blocking: called from the download thread's progress callback. */
    @Query(
        "UPDATE downloads SET bytesDone = :bytesDone, updatedAt = :now " +
            "WHERE itemId = :itemId AND sha256 = :sha256 AND state = 'RUNNING'",
    )
    fun setProgress(itemId: String, sha256: String, bytesDone: Long, now: Long)

    @Query(
        "UPDATE downloads SET state = 'QUEUED', updatedAt = :now " +
            "WHERE itemId = :itemId AND sha256 = :sha256 AND state = 'RUNNING'",
    )
    suspend fun requeueIfRunning(itemId: String, sha256: String, now: Long)

    @Query("UPDATE downloads SET verifiedAt = :verifiedAt WHERE itemId = :itemId AND sha256 = :sha256")
    suspend fun setVerifiedAt(itemId: String, sha256: String, verifiedAt: Long?)

    @Query("UPDATE downloads SET wifiOnly = :wifiOnly WHERE state != 'INSTALLED'")
    suspend fun setWifiOnlyForActive(wifiOnly: Boolean)
}

class Converters {
    @TypeConverter fun fromUrls(urls: List<String>): String = urls.joinToString("\n")
    @TypeConverter fun toUrls(value: String): List<String> = if (value.isEmpty()) emptyList() else value.split("\n")
}

@Database(entities = [DownloadRecord::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class DownloadDatabase : RoomDatabase() {
    abstract fun downloadDao(): DownloadDao

    companion object {
        fun create(context: Context): DownloadDatabase =
            Room.databaseBuilder(context, DownloadDatabase::class.java, "downloads.db").build()
    }
}
```

`core/downloads/src/main/kotlin/app/granger/core/downloads/Volumes.kt`:

```kotlin
package app.granger.core.downloads

import android.content.Context
import android.os.Environment
import android.os.storage.StorageManager
import java.io.File

/** A storage volume Granger can write to. [root] is the app's external-files folder on it. */
data class Volume(
    val root: File,
    val label: String,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
    /** Bytes in Granger's `packs` and `models` folders on this volume. */
    val grangerBytes: Long,
) {
    val packsDir: File get() = File(root, "packs")
    val modelsDir: File get() = File(root, "models")
}

fun interface VolumeSource {
    /** Mounted volumes, internal storage first. Blocking: touches the disk. */
    fun list(): List<Volume>
}

/** Internal storage plus any SD card, via `getExternalFilesDirs` (no storage permission needed). */
class AndroidVolumes(private val context: Context) : VolumeSource {
    override fun list(): List<Volume> {
        val storage = context.getSystemService(StorageManager::class.java)
        return context.getExternalFilesDirs(null).filterNotNull()
            .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
            .map { root ->
                val removable = Environment.isExternalStorageRemovable(root)
                Volume(
                    root = root,
                    label = storage.getStorageVolume(root)?.getDescription(context)
                        ?: if (removable) "SD card" else "Internal storage",
                    removable = removable,
                    freeBytes = root.usableSpace,
                    totalBytes = root.totalSpace,
                    grangerBytes = sizeOf(File(root, "packs")) + sizeOf(File(root, "models")),
                )
            }
    }

    private fun sizeOf(dir: File): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L
}
```

`core/downloads/src/main/kotlin/app/granger/core/downloads/QueuePolicy.kt`:

```kotlin
package app.granger.core.downloads

sealed interface EnqueueResult {
    data object Queued : EnqueueResult
    data object AlreadyActive : EnqueueResult
    data object AlreadyInstalled : EnqueueResult

    /** The volume is [neededBytes] short, counting other queued downloads and the reserve. */
    data class NoSpace(val neededBytes: Long) : EnqueueResult

    /** Packs (installed + queued + this one) would total [wouldUseBytes], over [capBytes]. */
    data class OverCap(val capBytes: Long, val wouldUseBytes: Long) : EnqueueResult
}

object QueuePolicy {
    /** Left free on every volume so the phone keeps working. */
    const val RESERVE_BYTES = 256L * 1024 * 1024

    /**
     * The checks made when queueing (spec "Storage cap", "Insufficient space"). The cap covers packs
     * only; models are excluded. An update's old snapshot ([replacedPackBytes]) is not counted twice.
     */
    fun check(
        itemBytes: Long,
        isPack: Boolean,
        installedPackBytes: Long,
        queuedPackBytes: Long,
        replacedPackBytes: Long,
        capBytes: Long,
        freeBytes: Long,
        queuedOnVolumeBytes: Long,
    ): EnqueueResult {
        if (isPack) {
            val wouldUse = installedPackBytes - replacedPackBytes + queuedPackBytes + itemBytes
            if (wouldUse > capBytes) return EnqueueResult.OverCap(capBytes, wouldUse)
        }
        val needed = itemBytes + queuedOnVolumeBytes + RESERVE_BYTES - freeBytes
        if (needed > 0) return EnqueueResult.NoSpace(needed)
        return EnqueueResult.Queued
    }
}
```

`core/downloads/src/main/kotlin/app/granger/core/downloads/Downloads.kt`:

```kotlin
package app.granger.core.downloads

import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** What the app's screens use to download, cancel and check items. */
interface Downloads {
    /** Every version of every item, oldest first. */
    val records: Flow<List<DownloadRecord>>

    /** Checks the cap and free space on [volumeRoot], then queues [item]. */
    suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult

    /** Queues a failed download again, with a fresh checksum retry. */
    suspend fun retry(itemId: String)

    /** Stops an unfinished download and deletes its partial file. An installed version stays. */
    suspend fun cancel(itemId: String)

    /** Stops any download and deletes every file and record of [itemId]. */
    suspend fun remove(itemId: String)

    /** Downloads an installed item again, e.g. after Readiness found it damaged. */
    suspend fun repair(itemId: String)

    /** Stores the result of a full sha256 check from Readiness. */
    suspend fun recordCheck(itemId: String, sha256: String, verified: Boolean)

    /** Applies the Wi-Fi-only setting to every unfinished download. */
    suspend fun setWifiOnly(wifiOnly: Boolean)
}

/** Runs downloads in the background; the WorkManager implementation is in DownloadWorker.kt. */
interface DownloadScheduler {
    /** Starts [record]'s download; with [replace], restarts it (it resumes from its part file). */
    fun schedule(record: DownloadRecord, replace: Boolean = false)

    fun cancel(itemId: String)
}

class DownloadManager(
    private val dao: DownloadDao,
    private val scheduler: DownloadScheduler,
    private val installedPackBytes: suspend () -> Long,
    private val capBytes: () -> Long = { Long.MAX_VALUE },
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : Downloads {
    private val mutex = Mutex()

    override val records: Flow<List<DownloadRecord>> = dao.observeAll()

    override suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult = mutex.withLock {
        val versions = dao.forItem(item.id)
        if (versions.any { it.isActive }) return@withLock EnqueueResult.AlreadyActive
        if (versions.any { it.sha256 == item.sha256 }) return@withLock EnqueueResult.AlreadyInstalled
        val root = volumeRoot.absoluteFile
        val active = dao.getAll().filter { it.isActive }
        val isPack = item is PackEntry
        val verdict = QueuePolicy.check(
            itemBytes = item.sizeBytes,
            isPack = isPack,
            installedPackBytes = installedPackBytes(),
            queuedPackBytes = active.filter { it.kind == ItemKind.PACK }.sumOf { it.sizeBytes },
            replacedPackBytes = if (isPack) versions.sumOf { it.sizeBytes } else 0L,
            capBytes = capBytes(),
            freeBytes = withContext(io) { freeBytes(root) },
            queuedOnVolumeBytes = active
                .filter { it.filePath.startsWith(root.path + File.separator) }
                .sumOf { it.sizeBytes - it.bytesDone },
        )
        if (verdict != EnqueueResult.Queued) return@withLock verdict
        val t = now()
        val record = DownloadRecord(
            itemId = item.id,
            sha256 = item.sha256,
            kind = if (isPack) ItemKind.PACK else ItemKind.MODEL,
            title = item.title,
            urls = item.urls,
            sizeBytes = item.sizeBytes,
            filePath = targetFile(item, root).path,
            snapshotDate = (item as? PackEntry)?.snapshotDate.orEmpty(),
            wifiOnly = wifiOnly,
            state = DownloadState.QUEUED,
            createdAt = t,
            updatedAt = t,
        )
        dao.upsert(record)
        scheduler.schedule(record)
        EnqueueResult.Queued
    }

    /** Each pack snapshot gets its own file, so an update never overwrites the pack being read. */
    private fun targetFile(item: CatalogItem, root: File): File = when (item) {
        is PackEntry -> File(root, "packs/${item.id}_${item.snapshotDate}.zim")
        is ModelEntry -> File(root, "models/${item.fileName}")
    }

    override suspend fun retry(itemId: String) = mutex.withLock {
        val failed = dao.forItem(itemId).firstOrNull { it.state == DownloadState.FAILED } ?: return@withLock
        val queued = failed.copy(state = DownloadState.QUEUED, error = null, hashFailures = 0, updatedAt = now())
        dao.upsert(queued)
        scheduler.schedule(queued, replace = true)
    }

    override suspend fun cancel(itemId: String) = mutex.withLock {
        scheduler.cancel(itemId)
        for (record in dao.forItem(itemId).filter { it.isActive }) {
            dao.delete(record.itemId, record.sha256)
            withContext(io) { record.partFile.delete() }
        }
    }

    override suspend fun remove(itemId: String) = mutex.withLock {
        scheduler.cancel(itemId)
        for (record in dao.forItem(itemId)) {
            dao.delete(record.itemId, record.sha256)
            withContext(io) {
                record.partFile.delete()
                record.file.delete()
            }
        }
    }

    override suspend fun repair(itemId: String) = mutex.withLock {
        val installed = dao.forItem(itemId).firstOrNull { it.state == DownloadState.INSTALLED } ?: return@withLock
        val queued = installed.copy(state = DownloadState.QUEUED, bytesDone = 0, error = null, hashFailures = 0, verifiedAt = null, updatedAt = now())
        dao.upsert(queued)
        scheduler.schedule(queued, replace = true)
    }

    override suspend fun recordCheck(itemId: String, sha256: String, verified: Boolean) {
        dao.setVerifiedAt(itemId, sha256, if (verified) now() else null)
    }

    override suspend fun setWifiOnly(wifiOnly: Boolean) = mutex.withLock {
        dao.setWifiOnlyForActive(wifiOnly)
        for (record in dao.getAll().filter { it.isActive }) scheduler.schedule(record, replace = true)
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:downloads:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `QueuePolicyTest` (6) and `DownloadManagerTest` (15) pass with `FileDownloaderTest`. The build writes `core/downloads/schemas/app.granger.core.downloads.DownloadDatabase/1.json`.

- [ ] **Step 5: Commit**

```bash
git add core/downloads
git commit -m "Add download ledger, volumes, cap and space checks, DownloadManager

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: `DownloadRunner` and the WorkManager worker

**Files:**
- Create: `core/downloads/src/main/kotlin/app/granger/core/downloads/DownloadRunner.kt`, `.../DownloadWorker.kt`, `core/downloads/src/test/kotlin/app/granger/core/downloads/DownloadRunnerTest.kt`, `.../WorkResultTest.kt`
- Modify: `core/downloads/src/main/AndroidManifest.xml`

**Interfaces:**
- Consumes: `FileDownloader` (Task 6), `DownloadDao`, `DownloadRecord`, `DownloadScheduler` (Task 7).
- Produces:
  - `enum class RunOutcome { INSTALLED, RETRY, FAILED, GONE }`.
  - `class DownloadRunner(dao: DownloadDao, downloader: FileDownloader, onInstalled: suspend (DownloadRecord) -> Unit, io: CoroutineDispatcher = Dispatchers.IO, now: () -> Long = System::currentTimeMillis, progressEveryMillis: Long = 1_000)` with `suspend fun run(itemId: String, sha256: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): RunOutcome`. On success it renames `.part` → final, marks the record `INSTALLED` (with `verifiedAt`), deletes older installed versions of the item (and their files when the path differs), then calls `onInstalled` — the app passes `library.refresh()` for packs, which registers the pack and re-keys its bookmarks (Task 4).
  - `interface DownloadsHost { val downloadRunner: DownloadRunner }` — the `Application` implements it.
  - `class DownloadWorker : CoroutineWorker`, input keys `DownloadWorker.KEY_ITEM_ID`, `KEY_SHA256`, `KEY_TITLE`; `fun RunOutcome.toWorkResult(): ListenableWorker.Result`.
  - `class WorkManagerScheduler(context: Context) : DownloadScheduler` — unique work `download-<itemId>`, `KEEP` (or `REPLACE` when asked), network `UNMETERED` when Wi-Fi only else `CONNECTED`, exponential backoff from 30 s.

Outcomes: `Failed` (network) → record back to `QUEUED` with the error, worker returns `retry()` so WorkManager resumes later. First `HashMismatch` → download again at once; second → `FAILED` ("retry once, then mark failed"). `NoSpace` → `FAILED`. If the worker is stopped (Wi-Fi lost, cancelled, system limit), a `RUNNING` record goes back to `QUEUED`. All state writes are `UPDATE`s, so a record that `cancel()` deleted mid-download is never brought back.

- [ ] **Step 1: Write the failing tests**

`core/downloads/src/test/kotlin/app/granger/core/downloads/DownloadRunnerTest.kt`:

```kotlin
package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.random.Random

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DownloadRunnerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val db = inMemoryDownloads()
    private val dao = db.downloadDao()
    private val server = FakeServer()
    private var free = Long.MAX_VALUE / 2
    private var clock = 100L
    private val installed = mutableListOf<DownloadRecord>()
    private val runner = DownloadRunner(
        dao,
        FileDownloader(server.client(), freeBytes = { free }, reserveBytes = 0),
        onInstalled = { installed += it },
        now = { clock },
        progressEveryMillis = 0,
    )
    private val content = Random(11).nextBytes(50_000)
    private val url = "https://a.test/med.zim"
    private val root by lazy { tmp.newFolder("vol") }

    @After fun close() = db.close()

    private suspend fun queued(
        sha256: String = sha256(content),
        fileName: String = "packs/med_2026-10-10.zim",
        kind: ItemKind = ItemKind.PACK,
        state: DownloadState = DownloadState.QUEUED,
        size: Long = content.size.toLong(),
    ): DownloadRecord {
        val record = DownloadRecord(
            itemId = "med", sha256 = sha256, kind = kind, title = "WikiMed", urls = listOf(url), sizeBytes = size,
            filePath = File(root, fileName).path, snapshotDate = "2026-10-10", wifiOnly = true, state = state,
            createdAt = 1, updatedAt = 1,
        )
        dao.upsert(record)
        return record
    }

    private suspend fun record(sha256: String = sha256(content)) = dao.get("med", sha256)

    @Test fun installsAVerifiedFile() = runTest {
        server.files[url] = content
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(record.file.readBytes()).isEqualTo(content)
        assertThat(record.partFile.exists()).isFalse()
        val stored = record()!!
        assertThat(stored.state).isEqualTo(DownloadState.INSTALLED)
        assertThat(stored.bytesDone).isEqualTo(content.size.toLong())
        assertThat(stored.verifiedAt).isEqualTo(100L)
        assertThat(installed.map { it.itemId }).containsExactly("med")
    }

    @Test fun anUnverifiedFileIsNeverInstalled() = runTest {
        server.files[url] = Random(1).nextBytes(content.size)
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.FAILED)
        assertThat(record.file.exists()).isFalse()
        assertThat(record.partFile.exists()).isFalse()
        val stored = record()!!
        assertThat(stored.state).isEqualTo(DownloadState.FAILED)
        assertThat(stored.hashFailures).isEqualTo(2)
        assertThat(stored.error).contains("Checksum")
        assertThat(installed).isEmpty()
        assertThat(server.requests).hasSize(2) // retried once
    }

    @Test fun oneMismatchThenAGoodDownloadInstalls() = runTest {
        server.once[url] = Random(1).nextBytes(content.size)
        server.files[url] = content
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(record.file.readBytes()).isEqualTo(content)
    }

    @Test fun networkFailureAsksForARetryAndKeepsTheBytes() = runTest {
        server.files[url] = content
        server.failAfter[url] = 20_000
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.RETRY)
        val stored = record()!!
        assertThat(stored.state).isEqualTo(DownloadState.QUEUED)
        assertThat(stored.error).isNotNull()
        assertThat(stored.bytesDone).isEqualTo(20_000L)
        assertThat(record.partFile.length()).isEqualTo(20_000L)

        server.failAfter.remove(url)
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(server.rangesRequested(url).last()).isEqualTo("bytes=20000-")
    }

    @Test fun noSpaceFails() = runTest {
        server.files[url] = content
        free = 0
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.FAILED)
        assertThat(record()!!.error).contains("space")
    }

    @Test fun rerunAfterInstallDoesNothing() = runTest {
        server.files[url] = content
        val record = queued()
        runner.run("med", record.sha256)
        val requests = server.requests.size
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(server.requests.size).isEqualTo(requests)
        assertThat(installed).hasSize(1)
    }

    @Test fun aMissingRecordIsGone() = runTest {
        assertThat(runner.run("nope", sha256(content))).isEqualTo(RunOutcome.GONE)
    }

    @Test fun updateDeletesTheOldFileOnlyAfterVerifying() = runTest {
        val oldFile = File(root, "packs/med_2026-09-01.zim").apply { parentFile!!.mkdirs(); writeText("old snapshot") }
        dao.upsert(
            DownloadRecord("med", SHA_1, ItemKind.PACK, "WikiMed", listOf(url), 12, oldFile.path, "2026-09-01", true,
                DownloadState.INSTALLED, 12, createdAt = 1, updatedAt = 1),
        )
        server.files[url] = content
        val record = queued()
        var oldExistedDuringDownload = false
        runner.run("med", record.sha256) { _, _ -> oldExistedDuringDownload = oldFile.exists() }
        assertThat(oldExistedDuringDownload).isTrue()
        assertThat(oldFile.exists()).isFalse()
        assertThat(dao.forItem("med").map { it.sha256 }).containsExactly(record.sha256)
    }

    @Test fun failedUpdateKeepsTheOldVersion() = runTest {
        val oldFile = File(root, "packs/med_2026-09-01.zim").apply { parentFile!!.mkdirs(); writeText("old snapshot") }
        dao.upsert(
            DownloadRecord("med", SHA_1, ItemKind.PACK, "WikiMed", listOf(url), 12, oldFile.path, "2026-09-01", true,
                DownloadState.INSTALLED, 12, createdAt = 1, updatedAt = 1),
        )
        server.files[url] = Random(2).nextBytes(content.size)
        val record = queued()
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.FAILED)
        assertThat(oldFile.readText()).isEqualTo("old snapshot")
        assertThat(dao.get("med", SHA_1)!!.state).isEqualTo(DownloadState.INSTALLED)
    }

    @Test fun modelUpdateAtTheSamePathKeepsTheNewFile() = runTest {
        val path = "models/e2b.litertlm"
        val file = File(root, path).apply { parentFile!!.mkdirs(); writeText("old model") }
        dao.upsert(
            DownloadRecord("med", SHA_1, ItemKind.MODEL, "E2B", listOf(url), 9, file.path, "", true,
                DownloadState.INSTALLED, 9, createdAt = 1, updatedAt = 1),
        )
        server.files[url] = content
        val record = queued(fileName = path, kind = ItemKind.MODEL)
        assertThat(runner.run("med", record.sha256)).isEqualTo(RunOutcome.INSTALLED)
        assertThat(file.readBytes()).isEqualTo(content)
        assertThat(dao.forItem("med").map { it.sha256 }).containsExactly(record.sha256)
    }

    @Test fun stoppedWorkerGoesBackToQueued() = runTest {
        server.files[url] = content
        server.failAfter[url] = Int.MAX_VALUE // never drops; the job is cancelled instead
        val record = queued(size = content.size.toLong())
        lateinit var job: Job
        job = launch { runner.run("med", record.sha256) { done, _ -> if (done > 0) job.cancel() } }
        job.join()
        assertThat(record()!!.state).isEqualTo(DownloadState.QUEUED)
        assertThat(record.file.exists()).isFalse()
    }

    @Test fun cancelledRecordIsNotResurrected() = runTest {
        server.files[url] = content
        val record = queued()
        val outcome = runner.run("med", record.sha256) { done, total ->
            if (done == total) runBlocking { dao.delete("med", record.sha256) }
        }
        assertThat(outcome).isEqualTo(RunOutcome.GONE)
        assertThat(dao.forItem("med")).isEmpty()
        assertThat(record.file.exists()).isFalse()
        assertThat(installed).isEmpty()
    }
}
```

`core/downloads/src/test/kotlin/app/granger/core/downloads/WorkResultTest.kt`:

```kotlin
package app.granger.core.downloads

import androidx.work.ListenableWorker
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WorkResultTest {
    @Test fun outcomesMapToWorkResults() {
        assertThat(RunOutcome.INSTALLED.toWorkResult()).isEqualTo(ListenableWorker.Result.success())
        assertThat(RunOutcome.GONE.toWorkResult()).isEqualTo(ListenableWorker.Result.success())
        assertThat(RunOutcome.RETRY.toWorkResult()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(RunOutcome.FAILED.toWorkResult()).isEqualTo(ListenableWorker.Result.failure())
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:downloads:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference: DownloadRunner`).

- [ ] **Step 3: Implement**

`core/downloads/src/main/kotlin/app/granger/core/downloads/DownloadRunner.kt`:

```kotlin
package app.granger.core.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

enum class RunOutcome { INSTALLED, RETRY, FAILED, GONE }

/**
 * Takes one queued record to installed or failed. Only a file whose sha256 matched is ever moved to
 * its final name; [onInstalled] (the library refresh) runs after that.
 */
class DownloadRunner(
    private val dao: DownloadDao,
    private val downloader: FileDownloader,
    private val onInstalled: suspend (DownloadRecord) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
    private val progressEveryMillis: Long = 1_000,
) {
    suspend fun run(itemId: String, sha256: String, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): RunOutcome {
        val record = dao.get(itemId, sha256) ?: return RunOutcome.GONE
        if (record.state == DownloadState.INSTALLED) return RunOutcome.INSTALLED
        var hashFailures = record.hashFailures
        try {
            while (true) {
                val part = record.partFile
                val started = dao.setState(itemId, sha256, DownloadState.RUNNING, null, part.length(), hashFailures, now())
                if (started == 0) return RunOutcome.GONE
                var lastWrite = 0L
                val request = DownloadRequest(record.urls, record.sizeBytes, sha256)
                val result = downloader.download(request, part) { done ->
                    onProgress(done, record.sizeBytes)
                    val t = now()
                    if (t - lastWrite >= progressEveryMillis) {
                        lastWrite = t
                        dao.setProgress(itemId, sha256, done, t)
                    }
                }
                when (result) {
                    DownloadResult.Done -> return install(record)
                    DownloadResult.HashMismatch -> {
                        hashFailures++
                        if (hashFailures >= MAX_HASH_FAILURES) {
                            return fail(record, "Checksum mismatch: the download didn't match the catalog.", hashFailures)
                        }
                    }
                    is DownloadResult.NoSpace -> {
                        val mb = (result.neededBytes + 999_999) / 1_000_000
                        return fail(record, "Not enough free space: about $mb MB more is needed.", hashFailures)
                    }
                    is DownloadResult.Failed -> {
                        dao.setState(itemId, sha256, DownloadState.QUEUED, result.reason, part.length(), hashFailures, now())
                        return RunOutcome.RETRY
                    }
                }
            }
        } catch (e: CancellationException) {
            // Stopped by WorkManager (Wi-Fi lost, cancelled, time limit): the part file stays for a resume.
            withContext(NonCancellable) { dao.requeueIfRunning(itemId, sha256, now()) }
            throw e
        }
    }

    private suspend fun install(record: DownloadRecord): RunOutcome {
        if (!withContext(io) { record.partFile.renameTo(record.file) }) {
            return fail(record, "Couldn't save the finished file.", 0)
        }
        if (dao.markInstalled(record.itemId, record.sha256, now()) == 0) {
            // Cancelled or removed while finishing: don't leave a file nothing points to.
            if (dao.forItem(record.itemId).none { it.filePath == record.filePath }) withContext(io) { record.file.delete() }
            return RunOutcome.GONE
        }
        for (old in dao.forItem(record.itemId)) {
            if (old.sha256 == record.sha256 || old.isActive) continue
            if (old.filePath != record.filePath) withContext(io) { old.file.delete() }
            dao.delete(old.itemId, old.sha256)
        }
        onInstalled(dao.get(record.itemId, record.sha256) ?: record)
        return RunOutcome.INSTALLED
    }

    private suspend fun fail(record: DownloadRecord, reason: String, hashFailures: Int): RunOutcome {
        dao.setState(record.itemId, record.sha256, DownloadState.FAILED, reason, record.partFile.length(), hashFailures, now())
        return RunOutcome.FAILED
    }

    private companion object {
        /** "Checksum mismatch → retry once, then mark failed" (spec §4). */
        const val MAX_HASH_FAILURES = 2
    }
}
```

`core/downloads/src/main/kotlin/app/granger/core/downloads/DownloadWorker.kt`:

```kotlin
package app.granger.core.downloads

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/** Implemented by the Application so the worker can reach the one [DownloadRunner] of the process. */
interface DownloadsHost {
    val downloadRunner: DownloadRunner
}

class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = DownloadNotifications(context)
    private val title: String get() = inputData.getString(KEY_TITLE).orEmpty()

    override suspend fun getForegroundInfo(): ForegroundInfo =
        notifications.foregroundInfo(inputData.getString(KEY_ITEM_ID).orEmpty(), title, 0, 0)

    override suspend fun doWork(): Result {
        val itemId = inputData.getString(KEY_ITEM_ID) ?: return Result.failure()
        val sha256 = inputData.getString(KEY_SHA256) ?: return Result.failure()
        val runner = (applicationContext as DownloadsHost).downloadRunner
        try {
            setForeground(notifications.foregroundInfo(itemId, title, 0, 0))
        } catch (e: IllegalStateException) {
            // Android 12+ refuses a foreground service started from the background; download anyway.
        }
        var shownAt = 0L
        val outcome = runner.run(itemId, sha256) { done, total ->
            val t = SystemClock.elapsedRealtime()
            if (t - shownAt >= NOTIFY_EVERY_MS) {
                shownAt = t
                notifications.update(itemId, title, done, total)
            }
        }
        return outcome.toWorkResult()
    }

    companion object {
        const val KEY_ITEM_ID = "itemId"
        const val KEY_SHA256 = "sha256"
        const val KEY_TITLE = "title"
        private const val NOTIFY_EVERY_MS = 2_000L
    }
}

fun RunOutcome.toWorkResult(): ListenableWorker.Result = when (this) {
    RunOutcome.INSTALLED, RunOutcome.GONE -> ListenableWorker.Result.success()
    RunOutcome.RETRY -> ListenableWorker.Result.retry()
    RunOutcome.FAILED -> ListenableWorker.Result.failure()
}

class WorkManagerScheduler(context: Context) : DownloadScheduler {
    private val work = WorkManager.getInstance(context)

    override fun schedule(record: DownloadRecord, replace: Boolean) {
        val network = if (record.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_ITEM_ID to record.itemId,
                    DownloadWorker.KEY_SHA256 to record.sha256,
                    DownloadWorker.KEY_TITLE to record.title,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        work.enqueueUniqueWork(name(record.itemId), policy, request)
    }

    override fun cancel(itemId: String) {
        work.cancelUniqueWork(name(itemId))
    }

    private fun name(itemId: String) = "download-$itemId"
}

internal class DownloadNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    fun foregroundInfo(itemId: String, title: String, done: Long, total: Long) =
        ForegroundInfo(itemId.hashCode(), build(title, done, total), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    /** Silently dropped when the user declined notifications. */
    fun update(itemId: String, title: String, done: Long, total: Long) {
        manager.notify(itemId.hashCode(), build(title, done, total))
    }

    private fun build(title: String, done: Long, total: Long): Notification {
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE)
        }
        return Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title.ifBlank { "Downloading" })
            .setContentText(if (total > 0) "$percent%" else "Starting…")
            .setProgress(100, percent, total <= 0)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val CHANNEL = "downloads"
    }
}
```

Replace `core/downloads/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <application>
        <!-- WorkManager's foreground service must declare the type the worker uses (Android 14+). -->
        <service
            android:name="androidx.work.impl.foreground.SystemForegroundService"
            android:foregroundServiceType="dataSync"
            tools:node="merge" />
    </application>
</manifest>
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:downloads:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `DownloadRunnerTest` (12) and `WorkResultTest` (1) pass with the earlier tests.

- [ ] **Step 5: Commit**

```bash
git add core/downloads
git commit -m "Add DownloadRunner and WorkManager DownloadWorker with foreground notification

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: `IntegrityChecker`

**Files:**
- Create: `core/downloads/src/main/kotlin/app/granger/core/downloads/IntegrityChecker.kt`, `core/downloads/src/test/kotlin/app/granger/core/downloads/IntegrityCheckerTest.kt`

**Interfaces:**
- Consumes: `DownloadRecord`, `hashFile`, `toHex`.
- Produces: `enum class Integrity { VERIFIED, SIZE_OK, INCOMPLETE, CORRUPTED, MISSING }`; `object IntegrityChecker { fun quick(record: DownloadRecord): Integrity; suspend fun full(record: DownloadRecord, io: CoroutineDispatcher = Dispatchers.IO, onProgress: (Float) -> Unit = {}): Integrity }`. `quick` is the size check Readiness always runs (VERIFIED when the size matches and a full check passed before); `full` re-hashes the file, reporting 0..1.

- [ ] **Step 1: Write the failing tests**

`core/downloads/src/test/kotlin/app/granger/core/downloads/IntegrityCheckerTest.kt`:

```kotlin
package app.granger.core.downloads

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class IntegrityCheckerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val content = Random(4).nextBytes(300_000)

    private fun record(file: File, verifiedAt: Long? = null) = DownloadRecord(
        itemId = "med", sha256 = sha256(content), kind = ItemKind.PACK, title = "WikiMed", urls = emptyList(),
        sizeBytes = content.size.toLong(), filePath = file.path, snapshotDate = "2026-10-10", wifiOnly = true,
        state = DownloadState.INSTALLED, verifiedAt = verifiedAt, createdAt = 1, updatedAt = 1,
    )

    @Test fun quickChecksPresenceAndSize() {
        val file = File(tmp.root, "med.zim")
        assertThat(IntegrityChecker.quick(record(file))).isEqualTo(Integrity.MISSING)
        file.writeBytes(content.copyOf(1_000))
        assertThat(IntegrityChecker.quick(record(file))).isEqualTo(Integrity.INCOMPLETE)
        file.writeBytes(content)
        assertThat(IntegrityChecker.quick(record(file))).isEqualTo(Integrity.SIZE_OK)
        assertThat(IntegrityChecker.quick(record(file, verifiedAt = 5))).isEqualTo(Integrity.VERIFIED)
    }

    @Test fun fullCheckVerifiesWithProgress() = runTest {
        val file = File(tmp.root, "med.zim").apply { writeBytes(content) }
        val progress = mutableListOf<Float>()
        assertThat(IntegrityChecker.full(record(file)) { progress += it }).isEqualTo(Integrity.VERIFIED)
        assertThat(progress.last()).isEqualTo(1f)
        assertThat(progress).isInOrder()
    }

    @Test fun fullCheckFindsCorruption() = runTest {
        val damaged = content.copyOf().also { it[150_000] = (it[150_000] + 1).toByte() }
        val file = File(tmp.root, "med.zim").apply { writeBytes(damaged) }
        assertThat(IntegrityChecker.full(record(file, verifiedAt = 5))).isEqualTo(Integrity.CORRUPTED)
    }

    @Test fun fullCheckOfMissingOrShortFiles() = runTest {
        val file = File(tmp.root, "med.zim")
        assertThat(IntegrityChecker.full(record(file))).isEqualTo(Integrity.MISSING)
        file.writeBytes(content.copyOf(10))
        assertThat(IntegrityChecker.full(record(file))).isEqualTo(Integrity.INCOMPLETE)
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :core:downloads:testDebugUnitTest --tests '*IntegrityCheckerTest*'`
Expected: compilation fails (`Unresolved reference: IntegrityChecker`).

- [ ] **Step 3: Implement**

`core/downloads/src/main/kotlin/app/granger/core/downloads/IntegrityChecker.kt`:

```kotlin
package app.granger.core.downloads

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

enum class Integrity { VERIFIED, SIZE_OK, INCOMPLETE, CORRUPTED, MISSING }

/** Readiness checks: a size check always, a full sha256 re-hash on request (spec "Readiness"). */
object IntegrityChecker {
    fun quick(record: DownloadRecord): Integrity {
        val file = record.file
        return when {
            !file.isFile -> Integrity.MISSING
            file.length() != record.sizeBytes -> Integrity.INCOMPLETE
            record.verifiedAt != null -> Integrity.VERIFIED
            else -> Integrity.SIZE_OK
        }
    }

    suspend fun full(
        record: DownloadRecord,
        io: CoroutineDispatcher = Dispatchers.IO,
        onProgress: (Float) -> Unit = {},
    ): Integrity = withContext(io) {
        val file = record.file
        if (!file.isFile) return@withContext Integrity.MISSING
        if (file.length() != record.sizeBytes) return@withContext Integrity.INCOMPLETE
        val digest = MessageDigest.getInstance("SHA-256")
        hashFile(file, digest) { onProgress(it.toFloat() / record.sizeBytes) }
        if (digest.digest().toHex() == record.sha256) Integrity.VERIFIED else Integrity.CORRUPTED
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :core:downloads:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, all `:core:downloads` tests pass (4 new).

- [ ] **Step 5: Commit**

```bash
git add core/downloads
git commit -m "Add IntegrityChecker for Readiness size and sha256 checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---
### Task 10: Publish the signed catalog to GitHub

**Files:**
- Create: `catalog/public-key.txt`, `catalog/catalog.json`, `catalog/catalog.json.sig`, `core/catalog/src/test/kotlin/app/granger/core/catalog/PublishedCatalogTest.kt`
- Outside the repo: `~/.config/granger/catalog-signing.key` (private, mode 600)

**Interfaces:**
- Consumes: `catalog_gen.py`, `curated.json` (Task 1); `CatalogParser` (Task 2).
- Produces: the live catalog at `https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json` (+ `.sig`), and `catalog/public-key.txt`, which Task 12 compiles into the app. The local branch becomes `main`, tracking `origin/main`.

This task generates a key that cannot be recovered and pushes the repository's whole history to a public GitHub repo. Steps 1 and 6 need Neal's go-ahead.

- [ ] **Step 1: Generate the signing key (ask Neal first)**

```bash
mkdir -p catalog
/tmp/catgenv/bin/python tools/catalog-gen/catalog_gen.py keygen --out ~/.config/granger/catalog-signing.key > catalog/public-key.txt
cat catalog/public-key.txt
ls -l ~/.config/granger/catalog-signing.key
```

Expected: a 44-character base64 line; the key file is `-rw-------`. Tell Neal to back the key file up somewhere safe (a password manager): without it no catalog can be published for apps built with this public key, and replacing it means shipping an app update.

- [ ] **Step 2: Build, sign and verify the catalog**

```bash
/tmp/catgenv/bin/python tools/catalog-gen/catalog_gen.py build --curated tools/catalog-gen/curated.json --out catalog/catalog.json
/tmp/catgenv/bin/python tools/catalog-gen/catalog_gen.py sign --key ~/.config/granger/catalog-signing.key catalog/catalog.json
/tmp/catgenv/bin/python tools/catalog-gen/catalog_gen.py verify --public-key catalog/public-key.txt catalog/catalog.json
/tmp/catgenv/bin/python -c "
import json; c = json.load(open('catalog/catalog.json'))
for p in c['packs']: print(f\"{p['id']:40} {p['sizeBytes']/1e9:6.2f} GB  {p['snapshotDate']}  ft={p['hasFulltextIndex']}  urls={len(p['urls'])}\")
for m in c['models']: print(f\"{m['id']:40} {m['sizeBytes']/1e9:6.2f} GB  evalPassed={m['evalPassed']}\")"
```

Expected: `catalog/catalog.json: 11 packs, 3 models`, `wrote catalog/catalog.json.sig`, `signature OK`, and a table where every pack has 1–3 URLs and plausible sizes (WikiMed nopic ≈ 1.05 GB, Wikipedia top nopic ≈ 2.7 GB, Ray Charles ≈ 0.003 GB). If a curated pack is no longer in the Kiwix catalog the build exits naming it; remove or rename that entry in `curated.json` and rebuild.

- [ ] **Step 3: Check that the app's parser accepts every entry**

`core/catalog/src/test/kotlin/app/granger/core/catalog/PublishedCatalogTest.kt`:

```kotlin
package app.granger.core.catalog

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File
import java.util.Base64

/** The catalog in catalog/ must verify with the committed key and lose no entries to validation. */
class PublishedCatalogTest {
    private fun repoFile(path: String): File {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "catalog/catalog.json").isFile) dir = dir.parentFile
        return File(dir ?: error("catalog/catalog.json not found"), path)
    }

    @Test fun publishedCatalogVerifiesAndParsesCompletely() {
        val bytes = repoFile("catalog/catalog.json").readBytes()
        val key = Base64.getDecoder().decode(repoFile("catalog/public-key.txt").readText().trim())
        val result = CatalogParser.parse(bytes, repoFile("catalog/catalog.json.sig").readBytes(), key)
        val catalog = (result as ParseResult.Ok).catalog

        val raw = kotlinx.serialization.json.Json.parseToJsonElement(bytes.decodeToString()) as kotlinx.serialization.json.JsonObject
        assertThat(catalog.packs.size).isEqualTo((raw["packs"] as kotlinx.serialization.json.JsonArray).size)
        assertThat(catalog.models.size).isEqualTo((raw["models"] as kotlinx.serialization.json.JsonArray).size)
        assertThat(catalog.presets.single { it.id == "starter" }.items).hasSize(4)
    }
}
```

Run: `./gradlew :core:catalog:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`, `PublishedCatalogTest` passes. Every future catalog rebuild re-runs this test before it is pushed.

- [ ] **Step 4: Commit**

```bash
git add catalog core/catalog/src/test/kotlin/app/granger/core/catalog/PublishedCatalogTest.kt
git commit -m "Publish the first signed catalog and its public key

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

- [ ] **Step 5: Check the history before it goes public**

```bash
git log --all -p | grep -inE 'BEGIN (RSA|OPENSSH|EC|PRIVATE)|api[_-]?key|secret|password|token=' | head -20
git log --all --name-only --format= | sort -u | grep -iE '\.(key|pem|keystore|jks|zim|litertlm)$|local\.properties'
```

Expected: no output from either command except `testdata/granger-test.zim` from the second. Anything else: stop and show Neal.

- [ ] **Step 6: Push to GitHub (ask Neal first)**

```bash
git remote add origin https://github.com/nealt9000/Granger.git
git branch -m master main
git push -u origin main
```

Expected: the push succeeds (Neal may need to authenticate: suggest he runs `! git push -u origin main` himself if credentials are needed). Only `main` is pushed; the old `plan-01-foundation` branch stays local.

- [ ] **Step 7: Verify the live URL**

```bash
curl -sfL https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json | sha256sum
sha256sum catalog/catalog.json
curl -sfL https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json.sig | diff - catalog/catalog.json.sig && echo sig matches
```

Expected: identical hashes and `sig matches`. (raw.githubusercontent.com caches for up to ~5 minutes after a push.)

**Updating the catalog later:** rerun Step 2, run `PublishedCatalogTest`, commit `catalog/`, push. Never re-sign an edited `catalog.json` without rebuilding it: `generatedAt` must increase or the app rejects it as older.

---

### Task 11: Downloads logic in the app (`ItemStatus`, `DownloadsViewModel`)

**Files:**
- Create: `app/src/main/kotlin/app/granger/Bytes.kt`, `app/src/main/kotlin/app/granger/downloads/DownloadPrefs.kt`, `.../downloads/ItemStatus.kt`, `.../downloads/DownloadsViewModel.kt`, `app/src/test/kotlin/app/granger/DownloadFakes.kt`, `app/src/test/kotlin/app/granger/BytesTest.kt`, `app/src/test/kotlin/app/granger/downloads/ItemStatusTest.kt`, `.../downloads/DownloadsViewModelTest.kt`
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: `CatalogSource`, `CatalogState`, `CatalogError`, `Catalog`, `CatalogItem`, `PackEntry`, `ModelEntry`, `ModelRole` (Tasks 2–3); `Downloads`, `DownloadRecord`, `DownloadState`, `ItemKind`, `EnqueueResult`, `Volume`, `VolumeSource`, `isActive` (Task 7); `Library`, `Pack`, `PackStatus` (Task 4); `DeviceTier` (`:core:ai`).
- Produces:
  - `fun formatBytes(bytes: Long): String` (decimal units: `"2.0 GB"`, `"350 MB"`).
  - `interface DownloadPrefs { var wifiOnly: Boolean; var volumePath: String? }`, `class SharedDownloadPrefs(context) : DownloadPrefs` (Wi-Fi only defaults to true).
  - `sealed interface ItemStatus { NotInstalled; Queued(wifiOnly); Downloading(fraction: Float); Failed(reason); Installed; UpdateAvailable; NeedsMoreMemory }`, `fun statusOf(item: CatalogItem, records: List<DownloadRecord>, packs: List<Pack>, tier: DeviceTier): ItemStatus`, `fun installedPackId(entry: PackEntry, records: List<DownloadRecord>, packs: List<Pack>): String?`, `fun DeviceTier.canRun(role: ModelRole): Boolean`.
  - `class DownloadsViewModel(catalog: CatalogSource, downloads: Downloads, library: Library, volumes: VolumeSource, prefs: DownloadPrefs, tier: DeviceTier, appScope: CoroutineScope, io: CoroutineDispatcher = Dispatchers.IO)` with `val ui: StateFlow<DownloadsUi>` and actions `refreshVolumes()`, `refreshCatalog()`, `get(itemId)`, `getPreset(presetId)`, `cancel(itemId)`, `retry(itemId)`, `remove(itemId)`, `setWifiOnly(Boolean)`, `selectVolume(path)`, `dismissNotice()`.
  - UI types: `DownloadsUi`, `VolumeRow(path, label, removable, freeBytes, totalBytes, grangerBytes, queuedBytes)`, `Section(title, rows)`, `ItemRow(id, title, details, sizeBytes, status, packId)`, `PresetRow(id, title, itemCount, remainingCount, remainingBytes)`. Task 13 reuses `VolumeRow`.

Status rules: an unfinished record decides the status (queued / downloading / failed). Otherwise a pack is Installed when the library has a pack whose id is the entry's `zimUuid` (or the record of this exact version is installed), and UpdateAvailable when only an older version's record is installed. A model is Installed when its exact version's record is installed. A model the phone can't run (small/embedder need the ASK tier, large needs THINK_DEEPER) shows NeedsMoreMemory instead of Get.

- [ ] **Step 1: Add the module dependencies**

In `app/build.gradle.kts`, add to `dependencies`:

```kotlin
    implementation(project(":core:catalog"))
    implementation(project(":core:downloads"))
```

- [ ] **Step 2: Write the failing tests**

`app/src/test/kotlin/app/granger/DownloadFakes.kt`:

```kotlin
package app.granger

import app.granger.core.catalog.Catalog
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.PackEntry
import app.granger.core.catalog.Preset
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.downloads.DownloadPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File

val SHA_A = "a".repeat(64)
val SHA_B = "b".repeat(64)

fun packEntry(id: String, category: String = "Medical", size: Long = 1_000, sha256: String = SHA_A) = PackEntry(
    id = id, title = "Pack $id", category = category, language = "eng", sizeBytes = size, sha256 = sha256,
    urls = listOf("https://a.test/$id.zim"), snapshotDate = "2026-10-10", license = "CC BY-SA 4.0",
    hasFulltextIndex = true, zimUuid = "u-$id", zimName = "n-$id",
)

fun modelEntry(id: String, role: ModelRole = ModelRole.SMALL, size: Long = 2_000, sha256: String = SHA_B) = ModelEntry(
    id = id, title = "Model $id", role = role, fileName = "$id.litertlm", sizeBytes = size, minRamGb = 6,
    sha256 = sha256, url = "https://hf.test/$id", license = "Apache-2.0", evalPassed = true,
)

fun catalogOf(packs: List<PackEntry> = emptyList(), models: List<ModelEntry> = emptyList(), presets: List<Preset> = emptyList()) =
    Catalog(1, "2026-10-16T00:00:00Z", packs, models, presets)

fun record(
    itemId: String,
    state: DownloadState,
    sha256: String = SHA_A,
    kind: ItemKind = ItemKind.PACK,
    filePath: String = "/internal/packs/$itemId.zim",
    sizeBytes: Long = 1_000,
    bytesDone: Long = 0,
    error: String? = null,
    wifiOnly: Boolean = true,
    verifiedAt: Long? = null,
) = DownloadRecord(
    itemId = itemId, sha256 = sha256, kind = kind, title = "Title $itemId", urls = listOf("https://a.test/$itemId"),
    sizeBytes = sizeBytes, filePath = filePath, snapshotDate = "2026-10-10", wifiOnly = wifiOnly, state = state,
    bytesDone = bytesDone, error = error, verifiedAt = verifiedAt, createdAt = 1, updatedAt = 1,
)

fun volume(path: String, label: String, free: Long = 50_000_000_000, total: Long = 128_000_000_000, granger: Long = 0) =
    Volume(File(path), label, removable = label == "SD card", freeBytes = free, totalBytes = total, grangerBytes = granger)

class FakeCatalogSource(catalog: Catalog? = null) : CatalogSource {
    val mutable = MutableStateFlow(CatalogState(catalog = catalog))
    override val state: StateFlow<CatalogState> = mutable
    var loads = 0
    var refreshes = 0
    override suspend fun load() { loads++ }
    override suspend fun refresh() { refreshes++ }
}

class FakeDownloads : Downloads {
    val list = MutableStateFlow<List<DownloadRecord>>(emptyList())
    override val records = list
    var nextResult: EnqueueResult = EnqueueResult.Queued
    val enqueued = mutableListOf<Pair<String, File>>()
    val calls = mutableListOf<String>()

    override suspend fun enqueue(item: CatalogItem, volumeRoot: File, wifiOnly: Boolean): EnqueueResult {
        enqueued += item.id to volumeRoot
        val result = nextResult
        if (result == EnqueueResult.Queued) {
            list.update { it + record(item.id, DownloadState.QUEUED, sha256 = item.sha256, sizeBytes = item.sizeBytes, wifiOnly = wifiOnly) }
        }
        return result
    }

    override suspend fun retry(itemId: String) { calls += "retry:$itemId" }
    override suspend fun cancel(itemId: String) { calls += "cancel:$itemId" }
    override suspend fun remove(itemId: String) { calls += "remove:$itemId" }
    override suspend fun repair(itemId: String) { calls += "repair:$itemId" }
    override suspend fun recordCheck(itemId: String, sha256: String, verified: Boolean) { calls += "check:$itemId:$verified" }
    override suspend fun setWifiOnly(wifiOnly: Boolean) { calls += "wifi:$wifiOnly" }
}

class FakeVolumes(var volumes: List<Volume>) : VolumeSource {
    override fun list() = volumes
}

class FakePrefs(override var wifiOnly: Boolean = true, override var volumePath: String? = null) : DownloadPrefs
```

`app/src/test/kotlin/app/granger/BytesTest.kt`:

```kotlin
package app.granger

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BytesTest {
    @Test fun formatsDecimalUnits() {
        assertThat(formatBytes(512)).isEqualTo("512 B")
        assertThat(formatBytes(2_000_000_000)).isEqualTo("2.0 GB")
        assertThat(formatBytes(1_047_708_672)).isEqualTo("1.0 GB")
        assertThat(formatBytes(350_000_000)).isEqualTo("350 MB")
        assertThat(formatBytes(12_400)).isEqualTo("12 KB")
        assertThat(formatBytes(0)).isEqualTo("0 B")
    }
}
```

`app/src/test/kotlin/app/granger/downloads/ItemStatusTest.kt`:

```kotlin
package app.granger.downloads

import app.granger.SHA_A
import app.granger.SHA_B
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.ModelRole
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.ItemKind
import app.granger.core.library.PackStatus
import app.granger.modelEntry
import app.granger.pack
import app.granger.packEntry
import app.granger.record
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ItemStatusTest {
    private val med = packEntry("med")
    private val e2b = modelEntry("e2b")
    private val tier = DeviceTier.THINK_DEEPER

    @Test fun nothingYet() {
        assertThat(statusOf(med, emptyList(), emptyList(), tier)).isEqualTo(ItemStatus.NotInstalled)
    }

    @Test fun unfinishedRecordsDecide() {
        assertThat(statusOf(med, listOf(record("med", DownloadState.QUEUED, wifiOnly = true)), emptyList(), tier))
            .isEqualTo(ItemStatus.Queued(wifiOnly = true))
        assertThat(statusOf(med, listOf(record("med", DownloadState.RUNNING, bytesDone = 250)), emptyList(), tier))
            .isEqualTo(ItemStatus.Downloading(0.25f))
        assertThat(statusOf(med, listOf(record("med", DownloadState.FAILED, error = "Checksum mismatch")), emptyList(), tier))
            .isEqualTo(ItemStatus.Failed("Checksum mismatch"))
    }

    @Test fun anUnfinishedUpdateShowsOverTheInstalledVersion() {
        val records = listOf(record("med", DownloadState.INSTALLED, sha256 = SHA_B), record("med", DownloadState.RUNNING, bytesDone = 500))
        assertThat(statusOf(med, records, emptyList(), tier)).isEqualTo(ItemStatus.Downloading(0.5f))
    }

    @Test fun packIsInstalledWhenTheLibraryHasItsZim() {
        assertThat(statusOf(med, emptyList(), listOf(pack("u-med")), tier)).isEqualTo(ItemStatus.Installed)
        assertThat(statusOf(med, emptyList(), listOf(pack("u-med", status = PackStatus.CORRUPT)), tier)).isEqualTo(ItemStatus.Installed)
    }

    @Test fun packIsInstalledFromItsRecordBeforeTheLibraryRefreshes() {
        assertThat(statusOf(med, listOf(record("med", DownloadState.INSTALLED)), emptyList(), tier)).isEqualTo(ItemStatus.Installed)
    }

    @Test fun olderVersionMeansUpdateAvailable() {
        val records = listOf(record("med", DownloadState.INSTALLED, sha256 = SHA_B))
        assertThat(statusOf(med, records, listOf(pack("u-old-snapshot")), tier)).isEqualTo(ItemStatus.UpdateAvailable)
    }

    @Test fun aMissingPackIsNotInstalled() {
        assertThat(statusOf(med, emptyList(), listOf(pack("u-med", status = PackStatus.MISSING)), tier)).isEqualTo(ItemStatus.NotInstalled)
    }

    @Test fun modelsMatchOnTheirExactVersion() {
        assertThat(statusOf(e2b, listOf(record("e2b", DownloadState.INSTALLED, sha256 = SHA_B, kind = ItemKind.MODEL)), emptyList(), tier))
            .isEqualTo(ItemStatus.Installed)
        assertThat(statusOf(e2b, listOf(record("e2b", DownloadState.INSTALLED, sha256 = SHA_A, kind = ItemKind.MODEL)), emptyList(), tier))
            .isEqualTo(ItemStatus.UpdateAvailable)
    }

    @Test fun modelsTooBigForThePhone() {
        val e4b = modelEntry("e4b", role = ModelRole.LARGE)
        assertThat(statusOf(e4b, emptyList(), emptyList(), DeviceTier.ASK)).isEqualTo(ItemStatus.NeedsMoreMemory)
        assertThat(statusOf(e4b, emptyList(), emptyList(), DeviceTier.THINK_DEEPER)).isEqualTo(ItemStatus.NotInstalled)
        assertThat(statusOf(e2b, emptyList(), emptyList(), DeviceTier.LIBRARY_ONLY)).isEqualTo(ItemStatus.NeedsMoreMemory)
        assertThat(statusOf(e2b, emptyList(), emptyList(), DeviceTier.ASK)).isEqualTo(ItemStatus.NotInstalled)
    }

    @Test fun installedPackIdFollowsTheZimOrTheFile() {
        assertThat(installedPackId(med, emptyList(), listOf(pack("u-med")))).isEqualTo("u-med")
        val older = pack("u-old").copy(filePath = "/internal/packs/med.zim")
        assertThat(installedPackId(med, listOf(record("med", DownloadState.INSTALLED, sha256 = SHA_B)), listOf(older))).isEqualTo("u-old")
        assertThat(installedPackId(med, emptyList(), listOf(older))).isNull()
    }
}
```

`app/src/test/kotlin/app/granger/downloads/DownloadsViewModelTest.kt`:

```kotlin
package app.granger.downloads

import app.granger.FakeCatalogSource
import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakePrefs
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.catalogOf
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogError
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.Preset
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.EnqueueResult
import app.granger.modelEntry
import app.granger.pack
import app.granger.packEntry
import app.granger.record
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import java.io.File

class DownloadsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val med = packEntry("med", category = "Medical")
    private val top = packEntry("top", category = "Reference")
    private val voyage = packEntry("voyage", category = "Regional")
    private val e2b = modelEntry("e2b")
    private val catalog = FakeCatalogSource(
        catalogOf(
            packs = listOf(voyage, top, med),
            models = listOf(e2b),
            presets = listOf(Preset("starter", "Starter", listOf("e2b", "med", "top", "voyage"))),
        ),
    )
    private val downloads = FakeDownloads()
    private val library = FakeLibrary()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage"), volume("/sd", "SD card")))
    private val prefs = FakePrefs()

    private fun TestScope.viewModel(tier: DeviceTier = DeviceTier.THINK_DEEPER): DownloadsViewModel {
        val vm = DownloadsViewModel(catalog, downloads, library, volumes, prefs, tier, appScope = this, io = main.dispatcher)
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun loadsTheCachedCatalogThenRefreshes() = runTest(main.dispatcher) {
        viewModel()
        assertThat(catalog.loads).isEqualTo(1)
        assertThat(catalog.refreshes).isEqualTo(1)
    }

    @Test fun modelsFirstThenCategoriesInTheirOrder() = runTest(main.dispatcher) {
        val ui = viewModel().ui.value
        assertThat(ui.sections.map { it.title }).containsExactly("AI models", "Medical", "Reference", "Regional").inOrder()
        assertThat(ui.loading).isFalse()
        assertThat(ui.sections[1].rows.single().details).isEqualTo("eng · Updated 2026-10-10 · CC BY-SA 4.0")
    }

    @Test fun getQueuesOnTheChosenVolumeWithTheWifiSetting() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.selectVolume("/sd")
        vm.setWifiOnly(false)
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued.single()).isEqualTo("med" to File("/sd"))
        assertThat(prefs.volumePath).isEqualTo("/sd")
        assertThat(prefs.wifiOnly).isFalse()
        assertThat(downloads.calls).contains("wifi:false")
        assertThat(vm.ui.value.sections.first { it.title == "Medical" }.rows.single().status)
            .isEqualTo(ItemStatus.Queued(wifiOnly = false))
    }

    @Test fun aVolumeThatIsGoneFallsBackToTheFirst() = runTest(main.dispatcher) {
        prefs.volumePath = "/sd-that-was-removed"
        val vm = viewModel()
        assertThat(vm.ui.value.selectedVolume).isEqualTo("/internal")
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued.single().second).isEqualTo(File("/internal"))
    }

    @Test fun noSpaceExplainsWhere() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.NoSpace(2_000_000_000)
        val vm = viewModel()
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.notice).contains("Not enough space on Internal storage")
        assertThat(vm.ui.value.notice).contains("2.0 GB")
        vm.dismissNotice()
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.notice).isNull()
    }

    @Test fun overTheCapExplains() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.OverCap(10_000_000_000, 11_000_000_000)
        val vm = viewModel()
        vm.get("med")
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.notice).contains("10 GB")
    }

    @Test fun presetSkipsInstalledAndActiveItems() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"))
        downloads.list.value = listOf(record("top", DownloadState.RUNNING))
        val vm = viewModel()
        assertThat(vm.ui.value.presets.single().remainingCount).isEqualTo(2)
        assertThat(vm.ui.value.presets.single().remainingBytes).isEqualTo(3_000)
        vm.getPreset("starter")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued.map { it.first }).containsExactly("e2b", "voyage").inOrder()
        assertThat(vm.ui.value.notice).isEqualTo("Queued 2 downloads.")
    }

    @Test fun presetWithNothingLeftSaysSo() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"), pack("u-top"), pack("u-voyage"))
        downloads.list.value = listOf(record("e2b", DownloadState.INSTALLED, sha256 = e2b.sha256))
        val vm = viewModel()
        vm.getPreset("starter")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued).isEmpty()
        assertThat(vm.ui.value.notice).contains("already")
    }

    @Test fun presetStopsAtTheFirstSpaceProblem() = runTest(main.dispatcher) {
        downloads.nextResult = EnqueueResult.NoSpace(500)
        val vm = viewModel()
        vm.getPreset("starter")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.enqueued).hasSize(1)
        assertThat(vm.ui.value.notice).contains("Not enough space")
    }

    @Test fun removeDeletesThePackThenItsRecords() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-med"))
        val vm = viewModel()
        vm.remove("med")
        testScheduler.advanceUntilIdle()
        assertThat(library.removed).containsExactly("u-med")
        assertThat(downloads.calls).contains("remove:med")
    }

    @Test fun removingAModelOnlyTouchesDownloads() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.remove("e2b")
        testScheduler.advanceUntilIdle()
        assertThat(library.removed).isEmpty()
        assertThat(downloads.calls).contains("remove:e2b")
    }

    @Test fun cancelAndRetryPassThrough() = runTest(main.dispatcher) {
        val vm = viewModel()
        vm.cancel("med")
        vm.retry("top")
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).containsAtLeast("cancel:med", "retry:top")
    }

    @Test fun firstRunOfflineExplains() = runTest(main.dispatcher) {
        catalog.mutable.value = CatalogState(catalog = null, error = CatalogError.NETWORK)
        val ui = viewModel().ui.value
        assertThat(ui.loading).isFalse()
        assertThat(ui.catalogProblem).contains("Connect to the internet")
        assertThat(ui.sections).isEmpty()
    }

    @Test fun aRejectedCatalogUpdateIsExplainedButTheOldOneShows() = runTest(main.dispatcher) {
        catalog.mutable.value = catalog.mutable.value.copy(error = CatalogError.BAD_SIGNATURE)
        val ui = viewModel().ui.value
        assertThat(ui.catalogProblem).contains("security check")
        assertThat(ui.sections).isNotEmpty()
    }

    @Test fun volumeMeterCountsWhatIsStillToDownload() = runTest(main.dispatcher) {
        downloads.list.value = listOf(record("med", DownloadState.RUNNING, filePath = "/sd/packs/med.zim", sizeBytes = 1_000, bytesDone = 400))
        val ui = viewModel().ui.value
        assertThat(ui.volumes.first { it.path == "/sd" }.queuedBytes).isEqualTo(600)
        assertThat(ui.volumes.first { it.path == "/internal" }.queuedBytes).isEqualTo(0)
    }

    @Test fun weakPhonesCannotGetModels() = runTest(main.dispatcher) {
        val ui = viewModel(tier = DeviceTier.LIBRARY_ONLY).ui.value
        assertThat(ui.sections.first().rows.single().status).isEqualTo(ItemStatus.NeedsMoreMemory)
        assertThat(ui.presets.single().remainingCount).isEqualTo(3)
    }
}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference: DownloadsViewModel`, `formatBytes`, …).

- [ ] **Step 4: Implement**

`app/src/main/kotlin/app/granger/Bytes.kt`:

```kotlin
package app.granger

import java.util.Locale

/** "2.0 GB", "350 MB", "512 B": decimal units, like Android's Formatter, but usable in JVM tests. */
fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1000 && unit < units.lastIndex) {
        value /= 1000
        unit++
    }
    return when {
        unit == 0 -> "$bytes B"
        value < 10 -> String.format(Locale.US, "%.1f %s", value, units[unit])
        else -> String.format(Locale.US, "%.0f %s", value, units[unit])
    }
}
```

`app/src/main/kotlin/app/granger/downloads/DownloadPrefs.kt`:

```kotlin
package app.granger.downloads

import android.content.Context

interface DownloadPrefs {
    /** Spec: Wi-Fi only by default; the user can turn it off. */
    var wifiOnly: Boolean

    /** The chosen volume's root path; null means the first (internal) volume. */
    var volumePath: String?
}

class SharedDownloadPrefs(context: Context) : DownloadPrefs {
    private val prefs = context.getSharedPreferences("downloads", Context.MODE_PRIVATE)

    override var wifiOnly: Boolean
        get() = prefs.getBoolean("wifiOnly", true)
        set(value) = prefs.edit().putBoolean("wifiOnly", value).apply()

    override var volumePath: String?
        get() = prefs.getString("volumePath", null)
        set(value) = prefs.edit().putString("volumePath", value).apply()
}
```

`app/src/main/kotlin/app/granger/downloads/ItemStatus.kt`:

```kotlin
package app.granger.downloads

import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.ModelRole
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.isActive
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus

sealed interface ItemStatus {
    data object NotInstalled : ItemStatus
    data class Queued(val wifiOnly: Boolean) : ItemStatus
    data class Downloading(val fraction: Float) : ItemStatus
    data class Failed(val reason: String) : ItemStatus
    data object Installed : ItemStatus
    data object UpdateAvailable : ItemStatus

    /** A model this phone doesn't have the memory to run (spec "Device tiers"). */
    data object NeedsMoreMemory : ItemStatus
}

fun statusOf(item: CatalogItem, records: List<DownloadRecord>, packs: List<Pack>, tier: DeviceTier): ItemStatus {
    val versions = records.filter { it.itemId == item.id }
    versions.firstOrNull { it.isActive }?.let { active ->
        return when (active.state) {
            DownloadState.QUEUED -> ItemStatus.Queued(active.wifiOnly)
            DownloadState.RUNNING ->
                ItemStatus.Downloading(if (active.sizeBytes > 0) active.bytesDone.toFloat() / active.sizeBytes else 0f)
            else -> ItemStatus.Failed(active.error ?: "Download failed")
        }
    }
    val currentInstalled = when (item) {
        is PackEntry -> versions.any { it.sha256 == item.sha256 } ||
            packs.any { it.id == item.zimUuid && it.status != PackStatus.MISSING }
        is ModelEntry -> versions.any { it.sha256 == item.sha256 }
    }
    return when {
        currentInstalled -> ItemStatus.Installed
        versions.isNotEmpty() -> ItemStatus.UpdateAvailable
        item is ModelEntry && !tier.canRun(item.role) -> ItemStatus.NeedsMoreMemory
        else -> ItemStatus.NotInstalled
    }
}

/** The library pack an entry is installed as: its exact ZIM, or the file an older version was saved to. */
fun installedPackId(entry: PackEntry, records: List<DownloadRecord>, packs: List<Pack>): String? =
    packs.firstOrNull { it.id == entry.zimUuid }?.id
        ?: records.filter { it.itemId == entry.id && it.state == DownloadState.INSTALLED }
            .firstNotNullOfOrNull { record -> packs.firstOrNull { it.filePath == record.filePath }?.id }

fun DeviceTier.canRun(role: ModelRole): Boolean = when (role) {
    ModelRole.SMALL, ModelRole.EMBEDDER -> this >= DeviceTier.ASK
    ModelRole.LARGE -> this >= DeviceTier.THINK_DEEPER
}
```

`app/src/main/kotlin/app/granger/downloads/DownloadsViewModel.kt`:

```kotlin
package app.granger.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.ai.DeviceTier
import app.granger.core.catalog.CatalogError
import app.granger.core.catalog.CatalogItem
import app.granger.core.catalog.CatalogSource
import app.granger.core.catalog.CatalogState
import app.granger.core.catalog.ModelEntry
import app.granger.core.catalog.PackEntry
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.EnqueueResult
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.isActive
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.formatBytes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class VolumeRow(
    val path: String,
    val label: String,
    val removable: Boolean,
    val freeBytes: Long,
    val totalBytes: Long,
    val grangerBytes: Long,
    /** Bytes still to download onto this volume. */
    val queuedBytes: Long,
)

data class ItemRow(
    val id: String,
    val title: String,
    val details: String,
    val sizeBytes: Long,
    val status: ItemStatus,
    /** The library pack this entry is installed as, if any. */
    val packId: String?,
)

data class Section(val title: String, val rows: List<ItemRow>)

data class PresetRow(val id: String, val title: String, val itemCount: Int, val remainingCount: Int, val remainingBytes: Long)

data class DownloadsUi(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val catalogProblem: String? = null,
    val volumes: List<VolumeRow> = emptyList(),
    val selectedVolume: String? = null,
    val wifiOnly: Boolean = true,
    val presets: List<PresetRow> = emptyList(),
    val sections: List<Section> = emptyList(),
    val notice: String? = null,
)

class DownloadsViewModel(
    private val catalog: CatalogSource,
    private val downloads: Downloads,
    private val library: Library,
    private val volumes: VolumeSource,
    private val prefs: DownloadPrefs,
    private val tier: DeviceTier,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private data class Settings(val volumes: List<Volume>, val selectedPath: String?, val wifiOnly: Boolean, val notice: String?)

    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val selectedPath = MutableStateFlow(prefs.volumePath)
    private val wifiOnly = MutableStateFlow(prefs.wifiOnly)
    private val notice = MutableStateFlow<String?>(null)
    private val settings = combine(volumeList, selectedPath, wifiOnly, notice, ::Settings)

    val ui: StateFlow<DownloadsUi> =
        combine(catalog.state, downloads.records, library.packs, settings, ::buildUi)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DownloadsUi())

    init {
        viewModelScope.launch {
            catalog.load()
            catalog.refresh()
        }
        refreshVolumes()
    }

    /** Free space changes outside the app; called on resume and after every action. */
    fun refreshVolumes() {
        viewModelScope.launch { volumeList.value = withContext(io) { volumes.list() } }
    }

    fun refreshCatalog() {
        viewModelScope.launch { catalog.refresh() }
    }

    fun get(itemId: String) {
        val item = catalog.state.value.catalog?.item(itemId) ?: return
        appScope.launch {
            notice.value = enqueue(listOf(item))
            refreshVolumes()
        }
    }

    fun getPreset(presetId: String) {
        val current = catalog.state.value.catalog ?: return
        val preset = current.presets.firstOrNull { it.id == presetId } ?: return
        appScope.launch {
            val records = downloads.records.first()
            val packs = library.packs.first()
            val todo = preset.items.mapNotNull { current.item(it) }.filter { wanted(statusOf(it, records, packs, tier)) }
            notice.value = if (todo.isEmpty()) {
                "Everything in ${preset.title} is already here or on its way."
            } else {
                enqueue(todo)
            }
            refreshVolumes()
        }
    }

    fun cancel(itemId: String) {
        appScope.launch {
            downloads.cancel(itemId)
            refreshVolumes()
        }
    }

    fun retry(itemId: String) {
        appScope.launch { downloads.retry(itemId) }
    }

    /** Deletes an installed item. For packs the library goes first, so its bookmarks go with it. */
    fun remove(itemId: String) {
        appScope.launch {
            val entry = catalog.state.value.catalog?.item(itemId) as? PackEntry
            val packId = entry?.let { installedPackId(it, downloads.records.first(), library.packs.first()) }
            if (packId != null && !library.remove(packId)) {
                notice.value = "Couldn't delete ${entry.title}."
                return@launch
            }
            downloads.remove(itemId)
            refreshVolumes()
        }
    }

    fun setWifiOnly(value: Boolean) {
        wifiOnly.value = value
        prefs.wifiOnly = value
        appScope.launch { downloads.setWifiOnly(value) }
    }

    fun selectVolume(path: String) {
        selectedPath.value = path
        prefs.volumePath = path
    }

    fun dismissNotice() {
        notice.value = null
    }

    private fun wanted(status: ItemStatus) = status == ItemStatus.NotInstalled || status == ItemStatus.UpdateAvailable

    /** Queues [items] in order and stops at the first one that doesn't fit. Returns the notice to show. */
    private suspend fun enqueue(items: List<CatalogItem>): String? {
        val all = volumeList.value.ifEmpty { withContext(io) { volumes.list() } }
        val volume = all.firstOrNull { it.root.path == selectedPath.value } ?: all.firstOrNull()
            ?: return "No storage is available right now."
        var queued = 0
        for (item in items) {
            val problem = when (val result = downloads.enqueue(item, volume.root, wifiOnly.value)) {
                EnqueueResult.Queued -> null.also { queued++ }
                EnqueueResult.AlreadyActive, EnqueueResult.AlreadyInstalled -> null
                is EnqueueResult.NoSpace ->
                    "Not enough space on ${volume.label} for ${item.title}: free up ${formatBytes(result.neededBytes)}."
                is EnqueueResult.OverCap ->
                    "${item.title} would bring your packs to ${formatBytes(result.wouldUseBytes)}, " +
                        "over the ${formatBytes(result.capBytes)} limit."
            }
            if (problem != null) return if (queued > 0) "$problem Queued $queued before that." else problem
        }
        return if (items.size > 1 && queued > 0) "Queued $queued downloads." else null
    }

    private fun buildUi(state: CatalogState, records: List<DownloadRecord>, packs: List<Pack>, s: Settings): DownloadsUi {
        val current = state.catalog
        val active = records.filter { it.isActive }
        val volumeRows = s.volumes.map { v ->
            VolumeRow(
                path = v.root.path,
                label = v.label,
                removable = v.removable,
                freeBytes = v.freeBytes,
                totalBytes = v.totalBytes,
                grangerBytes = v.grangerBytes,
                queuedBytes = active.filter { it.filePath.startsWith(v.root.path + File.separator) }
                    .sumOf { it.sizeBytes - it.bytesDone },
            )
        }
        fun row(item: CatalogItem): ItemRow {
            val details = when (item) {
                is PackEntry -> listOf(item.language, "Updated ${item.snapshotDate}", item.license)
                is ModelEntry -> listOf(item.license)
            }.filter { it.isNotBlank() }.joinToString(" · ")
            val packId = (item as? PackEntry)?.let { installedPackId(it, records, packs) }
            return ItemRow(item.id, item.title, details, item.sizeBytes, statusOf(item, records, packs, tier), packId)
        }
        val sections = if (current == null) emptyList() else buildList {
            if (current.models.isNotEmpty()) add(Section("AI models", current.models.map(::row)))
            current.packs.groupBy { it.category }
                .toList()
                .sortedWith(compareBy({ categoryRank(it.first) }, { it.first }))
                .forEach { (category, entries) -> add(Section(category, entries.map(::row))) }
        }
        val presets = current?.presets.orEmpty().filter { it.items.isNotEmpty() }.map { preset ->
            val items = preset.items.mapNotNull { current?.item(it) }
            val todo = items.filter { wanted(statusOf(it, records, packs, tier)) }
            PresetRow(preset.id, preset.title, items.size, todo.size, todo.sumOf { it.sizeBytes })
        }
        return DownloadsUi(
            loading = current == null && state.error == null,
            refreshing = state.refreshing,
            catalogProblem = catalogProblem(state),
            volumes = volumeRows,
            selectedVolume = volumeRows.firstOrNull { it.path == s.selectedPath }?.path ?: volumeRows.firstOrNull()?.path,
            wifiOnly = s.wifiOnly,
            presets = presets,
            sections = sections,
            notice = s.notice,
        )
    }

    private fun catalogProblem(state: CatalogState): String? {
        if (state.catalog == null) {
            return if (state.error != null) "Couldn't load the catalog. Connect to the internet and try again." else null
        }
        return when (state.error) {
            CatalogError.NETWORK -> "Offline: showing the catalog from your last connection."
            CatalogError.BAD_SIGNATURE -> "The latest catalog failed its security check. Showing the last good copy."
            CatalogError.UNSUPPORTED_FORMAT -> "A newer catalog needs an app update. Showing the last copy that works."
            CatalogError.MALFORMED -> "Couldn't read the latest catalog. Showing the last good copy."
            CatalogError.OLDER_THAN_CACHED, null -> null
        }
    }

    private companion object {
        val CATEGORY_ORDER = listOf(
            "Medical", "Reference", "Religion & Philosophy", "Literature & Drama", "Science", "Repair", "Regional",
        )

        fun categoryRank(category: String) = CATEGORY_ORDER.indexOf(category).let { if (it < 0) Int.MAX_VALUE else it }
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `BytesTest` (1), `ItemStatusTest` (10), `DownloadsViewModelTest` (16) pass with the existing app tests.

- [ ] **Step 6: Commit**

```bash
git add app/build.gradle.kts app/src/main/kotlin/app/granger/Bytes.kt app/src/main/kotlin/app/granger/downloads app/src/test
git commit -m "Add Downloads view model: catalog sections, statuses, presets, cap and space notices

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 12: Wire it up — `AppContainer`, Downloads screen, navigation

**Files:**
- Create: `app/src/main/kotlin/app/granger/downloads/DownloadsScreen.kt`
- Modify: `app/build.gradle.kts`, `app/src/main/kotlin/app/granger/GrangerApp.kt`, `app/src/main/kotlin/app/granger/GrangerNavHost.kt`, `app/src/main/kotlin/app/granger/library/LibraryScreen.kt`

**Interfaces:**
- Consumes: everything above; `catalog/public-key.txt` (Task 10).
- Produces: `BuildConfig.CATALOG_URL`, `BuildConfig.CATALOG_PUBLIC_KEY`; `AppContainer.catalog: CatalogSource`, `.downloads: Downloads`, `.downloadRunner: DownloadRunner`, `.volumes: VolumeSource`, `.downloadPrefs: DownloadPrefs`, `.deviceTier: DeviceTier`, `.totalRamBytes: Long`; `GrangerApp : DownloadsHost`; routes `DownloadsRoute`, `ReadinessRoute` (Task 13 adds its screen); `LibraryScreen(…, onDownloads, onReadiness)`.

This task is UI wiring with no new logic; it is verified by building, by the existing unit tests, and on the phone in Step 6.

- [ ] **Step 1: Build config**

In `app/build.gradle.kts`, inside `defaultConfig { … }` add:

```kotlin
        buildConfigField(
            "String", "CATALOG_URL",
            "\"https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json\"",
        )
        buildConfigField(
            "String", "CATALOG_PUBLIC_KEY",
            "\"${rootProject.file("catalog/public-key.txt").readText().trim()}\"",
        )
```

and change `buildFeatures { compose = true }` to:

```kotlin
    buildFeatures {
        compose = true
        buildConfig = true
    }
```

- [ ] **Step 2: `AppContainer` and `GrangerApp`**

Replace `app/src/main/kotlin/app/granger/GrangerApp.kt` with:

```kotlin
package app.granger

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import app.granger.core.ai.DeviceTier
import app.granger.core.ai.DeviceTiers
import app.granger.core.catalog.CatalogRepository
import app.granger.core.catalog.CatalogSource
import app.granger.core.downloads.AndroidVolumes
import app.granger.core.downloads.DownloadDatabase
import app.granger.core.downloads.DownloadManager
import app.granger.core.downloads.DownloadRunner
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.DownloadsHost
import app.granger.core.downloads.FileDownloader
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.VolumeSource
import app.granger.core.downloads.WorkManagerScheduler
import app.granger.core.library.Library
import app.granger.core.library.LibraryDatabase
import app.granger.core.library.LibrarySearch
import app.granger.core.library.RoomLibrary
import app.granger.core.library.ZimLibrarySearch
import app.granger.core.zim.KiwixZimOpener
import app.granger.core.zim.ZimResolver
import app.granger.downloads.DownloadPrefs
import app.granger.downloads.SharedDownloadPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.io.File
import java.util.Base64
import java.util.concurrent.TimeUnit

class GrangerApp : Application(), DownloadsHost {
    val container: AppContainer by lazy { AppContainer(this) }

    override val downloadRunner: DownloadRunner get() = container.downloadRunner
}

/** Hand-wired dependencies; one instance per process. */
class AppContainer(context: Context) {
    /** For writes that must finish even if the screen that started them is gone. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** `<volume>/Android/data/app.granger/files/packs` on internal storage and each SD card. */
    val packDirs: () -> List<File> = {
        context.getExternalFilesDirs(null).filterNotNull().map { File(it, "packs").apply { mkdirs() } }
    }

    val library: Library = RoomLibrary(LibraryDatabase.create(context), KiwixZimOpener(context), packDirs)
    val search: LibrarySearch = ZimLibrarySearch(library)
    val resolver = ZimResolver(library::archive, library::cachedArchive)

    val totalRamBytes: Long = ActivityManager.MemoryInfo()
        .also { context.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
        .totalMem
    val deviceTier: DeviceTier = DeviceTiers.forTotalRam(totalRamBytes)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val catalog: CatalogSource = CatalogRepository(
        client = http,
        url = BuildConfig.CATALOG_URL,
        publicKey = Base64.getDecoder().decode(BuildConfig.CATALOG_PUBLIC_KEY),
        cacheDir = File(context.filesDir, "catalog"),
        includeUnevaluatedModels = BuildConfig.DEBUG,
    )

    val volumes: VolumeSource = AndroidVolumes(context)
    val downloadPrefs: DownloadPrefs = SharedDownloadPrefs(context)

    private val downloadDao = DownloadDatabase.create(context).downloadDao()

    val downloads: Downloads = DownloadManager(
        dao = downloadDao,
        scheduler = WorkManagerScheduler(context),
        // Every installed pack counts towards the cap, SD card included (spec "Storage cap").
        installedPackBytes = { library.packs.first().sumOf { it.sizeBytes } },
        // Uncapped until Plan 8 wires :core:billing.
        capBytes = { Long.MAX_VALUE },
    )

    val downloadRunner = DownloadRunner(
        dao = downloadDao,
        downloader = FileDownloader(http),
        onInstalled = { record -> if (record.kind == ItemKind.PACK) library.refresh() },
    )
}
```

- [ ] **Step 3: The Downloads screen**

`app/src/main/kotlin/app/granger/downloads/DownloadsScreen.kt`:

```kotlin
package app.granger.downloads

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.formatBytes

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadsScreen(viewModel: DownloadsViewModel, onBack: () -> Unit) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.notice) {
        val notice = ui.notice ?: return@LaunchedEffect
        snackbar.showSnackbar(notice)
        viewModel.dismissNotice()
    }

    // Ask once for notifications so download progress can show; downloads work either way.
    val context = LocalContext.current
    var askedForNotifications by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun beforeDownload() {
        if (askedForNotifications || Build.VERSION.SDK_INT < 33) return
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        askedForNotifications = true
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    var confirmRemove by remember { mutableStateOf<ItemRow?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Downloads") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
                actions = {
                    IconButton(onClick = viewModel::refreshCatalog, enabled = !ui.refreshing) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Check for updates")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "storage") { StorageSection(ui, viewModel::selectVolume) }
            item(key = "wifi") {
                ListItem(
                    headlineContent = { Text("Download on Wi-Fi only") },
                    supportingContent = { Text("Turn off to use mobile data") },
                    trailingContent = { Switch(checked = ui.wifiOnly, onCheckedChange = viewModel::setWifiOnly) },
                )
                HorizontalDivider()
            }
            ui.catalogProblem?.let { problem ->
                item(key = "problem") {
                    Text(problem, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
                }
            }
            if (ui.loading) {
                item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(32.dp), Alignment.Center) { CircularProgressIndicator() }
                }
            }
            items(ui.presets, key = { "preset-${it.id}" }) { preset ->
                PresetItem(preset) {
                    beforeDownload()
                    viewModel.getPreset(preset.id)
                }
                HorizontalDivider()
            }
            for (section in ui.sections) {
                item(key = "section-${section.title}") {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
                    )
                }
                items(section.rows, key = { it.id }) { row ->
                    CatalogItemRow(
                        row,
                        onGet = {
                            beforeDownload()
                            viewModel.get(row.id)
                        },
                        onCancel = { viewModel.cancel(row.id) },
                        onRetry = { viewModel.retry(row.id) },
                        onRemove = { confirmRemove = row },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    confirmRemove?.let { row ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove ${row.title}?") },
            text = {
                Text(
                    if (row.packId != null) "This deletes the file, and the bookmarks and reading places you saved in it."
                    else "This deletes the file.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.remove(row.id)
                    confirmRemove = null
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text("Keep") } },
        )
    }
}

@Composable
private fun StorageSection(ui: DownloadsUi, onSelect: (String) -> Unit) {
    val choosable = ui.volumes.size > 1
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Storage", style = MaterialTheme.typography.titleSmall)
        for (volume in ui.volumes) {
            val selected = volume.path == ui.selectedVolume
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().let {
                    if (choosable) it.selectable(selected = selected, onClick = { onSelect(volume.path) }) else it
                },
            ) {
                if (choosable) RadioButton(selected = selected, onClick = null)
                Column(Modifier.weight(1f).padding(start = if (choosable) 8.dp else 0.dp)) {
                    Text(volume.label)
                    val used = if (volume.totalBytes > 0) 1f - volume.freeBytes.toFloat() / volume.totalBytes else 0f
                    LinearProgressIndicator(
                        progress = { used.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Text(
                        buildString {
                            append("${formatBytes(volume.freeBytes)} free · Granger uses ${formatBytes(volume.grangerBytes)}")
                            if (volume.queuedBytes > 0) append(" · ${formatBytes(volume.queuedBytes)} to download")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        if (choosable) Text("New downloads go to the selected storage.", style = MaterialTheme.typography.bodySmall)
        Text(
            "Uninstalling Granger deletes everything it downloaded.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetItem(preset: PresetRow, onGet: () -> Unit) {
    ListItem(
        headlineContent = { Text("${preset.title} set") },
        supportingContent = {
            Text(
                if (preset.remainingCount == 0) "All ${preset.itemCount} items are here or on their way."
                else "${preset.remainingCount} of ${preset.itemCount} items to get · ${formatBytes(preset.remainingBytes)}",
            )
        },
        trailingContent = { if (preset.remainingCount > 0) Button(onClick = onGet) { Text("Get all") } },
    )
}

@Composable
private fun CatalogItemRow(row: ItemRow, onGet: () -> Unit, onCancel: () -> Unit, onRetry: () -> Unit, onRemove: () -> Unit) {
    val status = row.status
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                Text("${formatBytes(row.sizeBytes)} · ${row.details}", style = MaterialTheme.typography.bodySmall)
                when (status) {
                    is ItemStatus.Queued -> Text(if (status.wifiOnly) "Waiting for Wi-Fi" else "Queued")
                    is ItemStatus.Downloading -> {
                        LinearProgressIndicator(
                            progress = { status.fraction },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Text("${(status.fraction * 100).toInt()}%")
                    }
                    is ItemStatus.Failed -> Text(status.reason, color = MaterialTheme.colorScheme.error)
                    ItemStatus.Installed -> Text("Installed", color = MaterialTheme.colorScheme.primary)
                    ItemStatus.UpdateAvailable -> Text("A newer version is available")
                    ItemStatus.NeedsMoreMemory -> Text("Needs a phone with more memory")
                    ItemStatus.NotInstalled -> Unit
                }
            }
        },
        trailingContent = {
            when (status) {
                ItemStatus.NotInstalled -> TextButton(onClick = onGet) { Text("Get") }
                ItemStatus.UpdateAvailable -> Row {
                    TextButton(onClick = onRemove) { Text("Remove") }
                    TextButton(onClick = onGet) { Text("Update") }
                }
                is ItemStatus.Queued, is ItemStatus.Downloading -> TextButton(onClick = onCancel) { Text("Cancel") }
                is ItemStatus.Failed -> Row {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
                ItemStatus.Installed -> TextButton(onClick = onRemove) { Text("Remove") }
                ItemStatus.NeedsMoreMemory -> Unit
            }
        },
    )
}
```

- [ ] **Step 4: Navigation and the Library screen**

In `GrangerNavHost.kt`, add the routes next to the others:

```kotlin
@Serializable object DownloadsRoute
@Serializable object ReadinessRoute
```

pass the new callbacks to `LibraryScreen`:

```kotlin
            LibraryScreen(
                viewModel = viewModel { LibraryViewModel(container.library) },
                packsFolder = container.packDirs().firstOrNull()?.path.orEmpty(),
                onOpenPack = { nav.navigate(ReaderRoute(it)) },
                onSearch = { nav.navigate(SearchRoute) },
                onBookmarks = { nav.navigate(BookmarksRoute) },
                onDownloads = { nav.navigate(DownloadsRoute) },
                onReadiness = { nav.navigate(ReadinessRoute) },
            )
```

and add the destination (Readiness's destination is added in Task 13):

```kotlin
        composable<DownloadsRoute> {
            DownloadsScreen(
                viewModel = viewModel {
                    DownloadsViewModel(
                        container.catalog, container.downloads, container.library, container.volumes,
                        container.downloadPrefs, container.deviceTier, container.appScope,
                    )
                },
                onBack = { nav.popBackStack() },
            )
        }
```

with imports `app.granger.downloads.DownloadsScreen` and `app.granger.downloads.DownloadsViewModel`.

In `LibraryScreen.kt`: add parameters `onDownloads: () -> Unit` and `onReadiness: () -> Unit` after `onBookmarks`; add two actions to the top bar after Bookmarks:

```kotlin
                    IconButton(onClick = onDownloads) { Icon(Icons.Filled.Add, contentDescription = "Downloads") }
                    IconButton(onClick = onReadiness) { Icon(Icons.Filled.CheckCircle, contentDescription = "Readiness") }
```

(imports `androidx.compose.material.icons.filled.Add`, `androidx.compose.material.icons.filled.CheckCircle`), and replace the empty-state `Column` with:

```kotlin
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No packs installed yet.", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = onDownloads) { Text("Get packs") }
                    Text("Or copy .zim files into $packsFolder", style = MaterialTheme.typography.bodySmall)
                }
```

(import `androidx.compose.material3.Button`).

Until Task 13, add a placeholder so `ReadinessRoute` resolves:

```kotlin
        composable<ReadinessRoute> { Text("Readiness") }
```

(import `androidx.compose.material3.Text`).

- [ ] **Step 5: Build and run the unit tests**

Run: `./gradlew :app:assembleDebug :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`. Check the merged manifest has the permissions and service type:

```bash
grep -E 'INTERNET|FOREGROUND_SERVICE_DATA_SYNC|POST_NOTIFICATIONS|foregroundServiceType|EnableSafeBrowsing' \
  app/build/intermediates/merged_manifest/debug/processDebugMainManifest/AndroidManifest.xml
```

Expected: all five appear (the path may differ by AGP version; `find app/build -name AndroidManifest.xml -path '*merged*'` locates it).

- [ ] **Step 6: Smoke-test on the Pixel 6**

```bash
./gradlew :app:installDebug && adb shell am start -n app.granger/.MainActivity
```

On the phone: Library → Downloads (+). Expected: the storage meter for internal storage; the Wi-Fi switch on; within a few seconds the catalog with sections "AI models", "Medical", "Reference", "Religion & Philosophy", "Literature & Drama", "Regional"; the "Starter set" row. Tap Get on "Wikipedia sample: Ray Charles (2 MB)" (on Wi-Fi): it shows a progress bar, then Installed; Back → Library lists "Ray Charles" and it opens. Allow the notification prompt when asked.

- [ ] **Step 7: Commit**

```bash
git add app
git commit -m "Wire catalog and downloads into the app; add the Downloads screen

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 13: Readiness

**Files:**
- Create: `app/src/main/kotlin/app/granger/readiness/ReadinessViewModel.kt`, `.../readiness/ReadinessScreen.kt`, `app/src/test/kotlin/app/granger/readiness/ReadinessViewModelTest.kt`
- Modify: `app/src/main/kotlin/app/granger/GrangerNavHost.kt`

**Interfaces:**
- Consumes: `Downloads`, `DownloadRecord`, `Integrity`, `IntegrityChecker` (Tasks 7, 9); `Library`, `Pack`, `PackStatus`; `VolumeSource`; `VolumeRow` (Task 11); `DeviceTier`.
- Produces:
  - `enum class CheckStatus { VERIFIED, SIZE_OK, INCOMPLETE, CORRUPTED, MISSING, DAMAGED, NOT_FROM_CATALOG }`.
  - `data class CheckRow(key, title, isModel, snapshotDate, sizeBytes, status, verifiedAt: Long?, progress: Float?, canVerify, canRepair)`; key = `"<itemId>@<sha256>"` for downloaded files, `"pack:<id>"` for packs added by hand.
  - `data class ReadinessUi(loading, tier, totalRamBytes, volumes: List<VolumeRow>, models: List<CheckRow>, packs: List<CheckRow>)`.
  - `class ReadinessViewModel(downloads, library, volumes, tier, totalRamBytes, appScope, io = Dispatchers.IO, quick: (DownloadRecord) -> Integrity = IntegrityChecker::quick, full: suspend (DownloadRecord, (Float) -> Unit) -> Integrity = { r, p -> IntegrityChecker.full(r, io, p) })` with `ui`, `refreshVolumes()`, `verify(key)`, `repair(key)`.

Rows: every installed download record (models, then packs), plus library packs with no record (pushed by hand: "can't be verified"). A library pack that won't open shows DAMAGED; a full check's CORRUPTED overrides the size check. Incomplete, corrupted, damaged and missing rows offer "Download again" (`Downloads.repair`).

- [ ] **Step 1: Write the failing tests**

`app/src/test/kotlin/app/granger/readiness/ReadinessViewModelTest.kt`:

```kotlin
package app.granger.readiness

import app.granger.FakeDownloads
import app.granger.FakeLibrary
import app.granger.FakeVolumes
import app.granger.MainDispatcherRule
import app.granger.core.ai.DeviceTier
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Integrity
import app.granger.core.downloads.ItemKind
import app.granger.core.library.PackStatus
import app.granger.pack
import app.granger.record
import app.granger.volume
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test

class ReadinessViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val downloads = FakeDownloads()
    private val library = FakeLibrary()
    private val volumes = FakeVolumes(listOf(volume("/internal", "Internal storage", free = 9_000, total = 64_000)))
    private val quick = mutableMapOf<String, Integrity>()
    private var fullResult = CompletableDeferred<Integrity>()

    private val med = record("med", DownloadState.INSTALLED, filePath = "/internal/packs/med.zim")
    private val e2b = record("e2b", DownloadState.INSTALLED, kind = ItemKind.MODEL, filePath = "/internal/models/e2b.litertlm")
    private fun key(r: DownloadRecord) = "${r.itemId}@${r.sha256}"

    private fun TestScope.viewModel(): ReadinessViewModel {
        val vm = ReadinessViewModel(
            downloads, library, volumes, DeviceTier.THINK_DEEPER, totalRamBytes = 7_974_752_256, appScope = this,
            io = main.dispatcher,
            quick = { quick[it.itemId] ?: Integrity.SIZE_OK },
            full = { _, progress ->
                progress(0.5f)
                fullResult.await()
            },
        )
        backgroundScope.launch { vm.ui.collect {} }
        testScheduler.advanceUntilIdle()
        return vm
    }

    @Test fun showsTierRamAndVolumes() = runTest(main.dispatcher) {
        val ui = viewModel().ui.value
        assertThat(ui.loading).isFalse()
        assertThat(ui.tier).isEqualTo(DeviceTier.THINK_DEEPER)
        assertThat(ui.totalRamBytes).isEqualTo(7_974_752_256)
        assertThat(ui.volumes.single().freeBytes).isEqualTo(9_000)
    }

    @Test fun installedFilesGetTheQuickCheck() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med, e2b, record("other", DownloadState.RUNNING))
        quick["e2b"] = Integrity.VERIFIED
        quick["med"] = Integrity.INCOMPLETE
        val ui = viewModel().ui.value
        assertThat(ui.models.single().status).isEqualTo(CheckStatus.VERIFIED)
        val row = ui.packs.single()
        assertThat(row.status).isEqualTo(CheckStatus.INCOMPLETE)
        assertThat(row.canRepair).isTrue()
        assertThat(row.snapshotDate).isEqualTo("2026-10-10")
    }

    @Test fun aPackTheLibraryCannotOpenIsDamaged() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        library.packList.value = listOf(pack("u-med", status = PackStatus.CORRUPT).copy(filePath = med.filePath, title = "WikiMed"))
        val row = viewModel().ui.value.packs.single()
        assertThat(row.status).isEqualTo(CheckStatus.DAMAGED)
        assertThat(row.title).isEqualTo("WikiMed")
        assertThat(row.canRepair).isTrue()
    }

    @Test fun packsAddedByHandAreListedButNotVerifiable() = runTest(main.dispatcher) {
        library.packList.value = listOf(pack("u-hand", title = "Pushed"), pack("u-gone", status = PackStatus.MISSING))
        val rows = viewModel().ui.value.packs
        assertThat(rows.map { it.title to it.status })
            .containsExactly("Pushed" to CheckStatus.NOT_FROM_CATALOG, "u-gone" to CheckStatus.MISSING)
        assertThat(rows.none { it.canVerify || it.canRepair }).isTrue()
    }

    @Test fun verifyShowsProgressThenTheResult() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.packs.single().progress).isEqualTo(0.5f)
        assertThat(vm.ui.value.packs.single().canVerify).isFalse()

        fullResult.complete(Integrity.CORRUPTED)
        testScheduler.advanceUntilIdle()
        val row = vm.ui.value.packs.single()
        assertThat(row.progress).isNull()
        assertThat(row.status).isEqualTo(CheckStatus.CORRUPTED)
        assertThat(row.canRepair).isTrue()
        assertThat(downloads.calls).contains("check:med:false")
    }

    @Test fun aPassingCheckIsRecorded() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        fullResult.complete(Integrity.VERIFIED)
        val vm = viewModel()
        vm.verify(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(vm.ui.value.packs.single().status).isEqualTo(CheckStatus.VERIFIED)
        assertThat(downloads.calls).contains("check:med:true")
    }

    @Test fun repairDownloadsAgain() = runTest(main.dispatcher) {
        downloads.list.value = listOf(med)
        val vm = viewModel()
        vm.repair(key(med))
        testScheduler.advanceUntilIdle()
        assertThat(downloads.calls).contains("repair:med")
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew :app:testDebugUnitTest`
Expected: compilation fails (`Unresolved reference: ReadinessViewModel`).

- [ ] **Step 3: Implement the view model**

`app/src/main/kotlin/app/granger/readiness/ReadinessViewModel.kt`:

```kotlin
package app.granger.readiness

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.granger.core.ai.DeviceTier
import app.granger.core.downloads.DownloadRecord
import app.granger.core.downloads.DownloadState
import app.granger.core.downloads.Downloads
import app.granger.core.downloads.Integrity
import app.granger.core.downloads.IntegrityChecker
import app.granger.core.downloads.ItemKind
import app.granger.core.downloads.Volume
import app.granger.core.downloads.VolumeSource
import app.granger.core.library.Library
import app.granger.core.library.Pack
import app.granger.core.library.PackStatus
import app.granger.downloads.VolumeRow
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class CheckStatus { VERIFIED, SIZE_OK, INCOMPLETE, CORRUPTED, MISSING, DAMAGED, NOT_FROM_CATALOG }

data class CheckRow(
    val key: String,
    val title: String,
    val isModel: Boolean,
    val snapshotDate: String,
    val sizeBytes: Long,
    val status: CheckStatus,
    val verifiedAt: Long?,
    /** 0..1 while a full check runs. */
    val progress: Float?,
    val canVerify: Boolean,
    val canRepair: Boolean,
)

data class ReadinessUi(
    val loading: Boolean = true,
    val tier: DeviceTier = DeviceTier.LIBRARY_ONLY,
    val totalRamBytes: Long = 0,
    val volumes: List<VolumeRow> = emptyList(),
    val models: List<CheckRow> = emptyList(),
    val packs: List<CheckRow> = emptyList(),
)

class ReadinessViewModel(
    private val downloads: Downloads,
    private val library: Library,
    private val volumes: VolumeSource,
    private val tier: DeviceTier,
    private val totalRamBytes: Long,
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val quick: (DownloadRecord) -> Integrity = IntegrityChecker::quick,
    private val full: suspend (DownloadRecord, (Float) -> Unit) -> Integrity = { record, progress ->
        IntegrityChecker.full(record, io, progress)
    },
) : ViewModel() {
    private val volumeList = MutableStateFlow<List<Volume>>(emptyList())
    private val progress = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val results = MutableStateFlow<Map<String, Integrity>>(emptyMap())
    private val checks = mutableMapOf<String, Job>()

    val ui: StateFlow<ReadinessUi> =
        combine(downloads.records, library.packs, volumeList, progress, results) { records, packs, vols, prog, res ->
            // The quick check reads file sizes.
            withContext(io) { build(records, packs, vols, prog, res) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReadinessUi(tier = tier, totalRamBytes = totalRamBytes))

    init {
        refreshVolumes()
    }

    fun refreshVolumes() {
        viewModelScope.launch { volumeList.value = withContext(io) { volumes.list() } }
    }

    /** Re-hashes the whole file. Runs while the screen is open; leaving it stops the check. */
    fun verify(key: String) {
        if (checks[key]?.isActive == true) return
        checks[key] = viewModelScope.launch {
            val record = downloads.records.first().firstOrNull { keyOf(it) == key } ?: return@launch
            progress.update { it + (key to 0f) }
            try {
                var shown = 0f
                val result = full(record) { p ->
                    if (p - shown >= 0.01f || p >= 1f) {
                        shown = p
                        progress.update { it + (key to p) }
                    }
                }
                results.update { it + (key to result) }
                downloads.recordCheck(record.itemId, record.sha256, result == Integrity.VERIFIED)
            } finally {
                progress.update { it - key }
            }
        }
    }

    fun repair(key: String) {
        appScope.launch {
            val record = downloads.records.first().firstOrNull { keyOf(it) == key } ?: return@launch
            results.update { it - key }
            downloads.repair(record.itemId)
        }
    }

    private fun build(
        records: List<DownloadRecord>,
        packs: List<Pack>,
        vols: List<Volume>,
        prog: Map<String, Float>,
        res: Map<String, Integrity>,
    ): ReadinessUi {
        val installed = records.filter { it.state == DownloadState.INSTALLED }
        val packByPath = packs.associateBy { it.filePath }
        val rows = installed.map { record ->
            val key = keyOf(record)
            val pack = packByPath[record.filePath]
            val status = when {
                res[key] == Integrity.CORRUPTED -> CheckStatus.CORRUPTED
                pack?.status == PackStatus.CORRUPT -> CheckStatus.DAMAGED
                else -> (res[key] ?: quick(record)).toStatus()
            }
            CheckRow(
                key = key,
                title = pack?.title ?: record.title,
                isModel = record.kind == ItemKind.MODEL,
                snapshotDate = record.snapshotDate,
                sizeBytes = record.sizeBytes,
                status = status,
                verifiedAt = record.verifiedAt,
                progress = prog[key],
                canVerify = status != CheckStatus.MISSING && key !in prog,
                canRepair = status in REPAIRABLE,
            )
        }
        val recordedPaths = installed.mapTo(HashSet()) { it.filePath }
        val byHand = packs.filter { it.filePath !in recordedPaths }.map { pack ->
            CheckRow(
                key = "pack:${pack.id}",
                title = pack.title,
                isModel = false,
                snapshotDate = pack.date,
                sizeBytes = pack.sizeBytes,
                status = when (pack.status) {
                    PackStatus.OK -> CheckStatus.NOT_FROM_CATALOG
                    PackStatus.MISSING -> CheckStatus.MISSING
                    PackStatus.CORRUPT -> CheckStatus.DAMAGED
                },
                verifiedAt = null,
                progress = null,
                canVerify = false,
                canRepair = false,
            )
        }
        return ReadinessUi(
            loading = false,
            tier = tier,
            totalRamBytes = totalRamBytes,
            volumes = vols.map { VolumeRow(it.root.path, it.label, it.removable, it.freeBytes, it.totalBytes, it.grangerBytes, 0) },
            models = rows.filter { it.isModel },
            packs = rows.filterNot { it.isModel } + byHand,
        )
    }

    private fun Integrity.toStatus(): CheckStatus = when (this) {
        Integrity.VERIFIED -> CheckStatus.VERIFIED
        Integrity.SIZE_OK -> CheckStatus.SIZE_OK
        Integrity.INCOMPLETE -> CheckStatus.INCOMPLETE
        Integrity.CORRUPTED -> CheckStatus.CORRUPTED
        Integrity.MISSING -> CheckStatus.MISSING
    }

    private companion object {
        val REPAIRABLE = setOf(CheckStatus.INCOMPLETE, CheckStatus.CORRUPTED, CheckStatus.DAMAGED, CheckStatus.MISSING)

        fun keyOf(record: DownloadRecord) = "${record.itemId}@${record.sha256}"
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew :app:testDebugUnitTest`
Expected: `BUILD SUCCESSFUL`; `ReadinessViewModelTest` (7) passes with the rest.

- [ ] **Step 5: The screen and its route**

`app/src/main/kotlin/app/granger/readiness/ReadinessScreen.kt`:

```kotlin
package app.granger.readiness

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.granger.core.ai.DeviceTier
import app.granger.formatBytes
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReadinessScreen(viewModel: ReadinessViewModel, onBack: () -> Unit) {
    LifecycleResumeEffect(Unit) {
        viewModel.refreshVolumes()
        onPauseOrDispose {}
    }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Readiness") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "device") {
                Heading("This phone")
                ListItem(
                    headlineContent = { Text(tierText(ui.tier)) },
                    supportingContent = { Text("${formatBytes(ui.totalRamBytes)} of memory") },
                )
            }
            item(key = "storage") { Heading("Storage") }
            items(ui.volumes, key = { "volume-${it.path}" }) { volume ->
                ListItem(
                    headlineContent = { Text(volume.label) },
                    supportingContent = {
                        Text("${formatBytes(volume.freeBytes)} free of ${formatBytes(volume.totalBytes)} · Granger uses ${formatBytes(volume.grangerBytes)}")
                    },
                )
            }
            item(key = "models") { Heading("AI models") }
            if (ui.models.isEmpty()) item(key = "no-models") { Empty("No models downloaded.") }
            items(ui.models, key = { it.key }) { row ->
                Check(row, viewModel::verify, viewModel::repair)
                HorizontalDivider()
            }
            item(key = "packs") { Heading("Packs") }
            if (ui.packs.isEmpty()) item(key = "no-packs") { Empty("No packs installed.") }
            items(ui.packs, key = { it.key }) { row ->
                Check(row, viewModel::verify, viewModel::repair)
                HorizontalDivider()
            }
        }
    }
}

private fun tierText(tier: DeviceTier) = when (tier) {
    DeviceTier.LIBRARY_ONLY -> "Library only: Ask needs a phone with 6 GB of memory"
    DeviceTier.ASK -> "Library and Ask"
    DeviceTier.THINK_DEEPER -> "Library, Ask and Think deeper"
}

@Composable
private fun Heading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun Empty(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun Check(row: CheckRow, onVerify: (String) -> Unit, onRepair: (String) -> Unit) {
    val (text, good) = when (row.status) {
        CheckStatus.VERIFIED -> {
            val date = row.verifiedAt?.let { " on " + DateFormat.getDateInstance().format(Date(it)) }.orEmpty()
            "Verified$date" to true
        }
        CheckStatus.SIZE_OK -> "Size is right; not fully checked yet" to true
        CheckStatus.INCOMPLETE -> "Incomplete: the file is the wrong size" to false
        CheckStatus.CORRUPTED -> "Corrupted: the contents don't match" to false
        CheckStatus.MISSING -> "Missing: the file isn't there" to false
        CheckStatus.DAMAGED -> "Damaged: it can't be opened" to false
        CheckStatus.NOT_FROM_CATALOG -> "Added by hand; can't be verified" to true
    }
    ListItem(
        headlineContent = { Text(row.title) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(row.snapshotDate.ifBlank { null }, formatBytes(row.sizeBytes)).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(text, color = if (good) Color.Unspecified else MaterialTheme.colorScheme.error)
                row.progress?.let { p ->
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                    Text("Checking… ${(p * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        trailingContent = {
            Row {
                if (row.canVerify) TextButton(onClick = { onVerify(row.key) }) { Text("Check") }
                if (row.canRepair) TextButton(onClick = { onRepair(row.key) }) { Text("Download again") }
            }
        },
    )
}
```

In `GrangerNavHost.kt`, replace the placeholder `composable<ReadinessRoute> { Text("Readiness") }` (and its `Text` import) with:

```kotlin
        composable<ReadinessRoute> {
            ReadinessScreen(
                viewModel = viewModel {
                    ReadinessViewModel(
                        container.downloads, container.library, container.volumes,
                        container.deviceTier, container.totalRamBytes, container.appScope,
                    )
                },
                onBack = { nav.popBackStack() },
            )
        }
```

with imports `app.granger.readiness.ReadinessScreen` and `app.granger.readiness.ReadinessViewModel`.

- [ ] **Step 6: Build and check on the phone**

Run: `./gradlew :app:testDebugUnitTest :app:installDebug`
Expected: `BUILD SUCCESSFUL`. On the phone: Library → Readiness (✓). Expected: "Library, Ask and Think deeper", ~8.0 GB of memory; internal storage line; "Wikipedia sample: Ray Charles" under Packs as "Verified on <today>"; `granger-test` (pushed in Plan 2) as "Added by hand; can't be verified". Tap Check on Ray Charles: a progress bar, then Verified.

- [ ] **Step 7: Commit**

```bash
git add app
git commit -m "Add Readiness: device tier, storage, size checks and on-request sha256 checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 14: Pixel 6 verification and roadmap update

**Files:**
- Modify: `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`

**Interfaces:**
- Consumes: the whole plan on the device.
- Produces: verified behavior on the Pixel 6 and the roadmap row for Plan 3 marked done, with anything learned carried forward.

The Pixel 6 has no SD card slot, so multi-volume behavior is covered by JVM tests only; say so in the roadmap.

- [ ] **Step 1: Full test suite**

Run: `./gradlew testDebugUnitTest && (cd tools/catalog-gen && /tmp/catgenv/bin/python -m pytest -q)`
Expected: every module's unit tests pass, then `12 passed` for catalog-gen.

Run (phone connected): `./gradlew connectedDebugAndroidTest`
Expected: all device tests pass (`:core:zim` and `ReaderWebViewTest`).

- [ ] **Step 2: Resume across a network drop and an app kill**

On the phone, Downloads → turn Wi-Fi only off if testing on mobile data → Get "Gemma 4 E2B (answers questions)" (2.0 GB; debug builds list it). While it downloads:

```bash
adb shell ls -l /sdcard/Android/data/app.granger/files/models/
```

Expected: `gemma-4-E2B-it-gpu.litertlm.part` growing. Then:
1. Turn on airplane mode for ~20 s, then off. Expected: the row goes back to "Waiting…"/"Queued" and then continues; the `.part` size never drops.
2. `adb shell am force-stop app.granger`, then open the app again. Expected: the download resumes from the same size (WorkManager reschedules on next launch).
3. Let it finish. Expected: `gemma-4-E2B-it-gpu.litertlm` (2008432640 bytes) and no `.part`; Readiness shows it Verified.

```bash
adb shell sha256sum /sdcard/Android/data/app.granger/files/models/gemma-4-E2B-it-gpu.litertlm
```

Expected: `a53a59001894c58e6bdb5b9b227709f91a2e3e556baa7d85acf9c55402ba5cf5`.

- [ ] **Step 3: Damage detection**

```bash
adb shell 'f=$(ls /sdcard/Android/data/app.granger/files/packs/wikipedia_en_ray-charles_maxi_*.zim); printf X | dd of="$f" bs=1 seek=100000 conv=notrunc'
```

Readiness → Check on the Ray Charles pack. Expected: "Corrupted: the contents don't match" and "Download again". Tap it; expected: it downloads again and returns to Verified, and the pack still opens in the Library.

- [ ] **Step 4: Reader stays offline**

Open any pack article with Wi-Fi on, then:

```bash
adb logcat -d | grep -iE 'safebrowsing|net::ERR' | tail -5
```

Expected: no Safe Browsing lookups; external links still open in the system browser only when tapped.

- [ ] **Step 5: Update the roadmap**

In `docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md`: set row 3's status to `**Done:** \`2026-10-16-plan-03-catalog-downloads-readiness.md\``, set row 4 to `Next`, and replace the "Carried forward from Plan 2's review" section with:

```markdown
## Carried forward from Plan 3

- The catalog lives at `https://raw.githubusercontent.com/nealt9000/Granger/main/catalog/catalog.json`, signed with the key at `~/.config/granger/catalog-signing.key` (not in the repo; backed up by Neal). Plan 4 adds Granger Classics and OpenStax to `tools/catalog-gen/curated.json`; their packs need a host (a GitHub release asset is the default) since Kiwix doesn't carry them.
- Models are `evalPassed: false`, so release builds list none until Plan 7 flips them.
- Kiwix's Gutenberg packs (`gutenberg_en_lcc-b`, `lcc-pr`) have no full-text index (`hasFulltextIndex: false`, checked 2026-10-16), so Ask can't search them; Granger Classics (Plan 4, built with the index on) covers that ground.
- Plan 6's `ModelManager` finds installed models through the `downloads` ledger: an `INSTALLED` record of kind `MODEL`, file at `filePath`.
- Plan 8 replaces `capBytes = { Long.MAX_VALUE }` in `AppContainer` with `:core:billing`'s cap, and shows the Pro upsell for `EnqueueResult.OverCap`.
- SD cards were tested only in JVM tests (the Pixel 6 has no slot). Try a phone with one before the beta.
- Android 15 limits `dataSync` foreground services to 6 hours a day; a very slow multi-GB download pauses at the limit and resumes when WorkManager next runs it.

Deferred, low priority (from Plan 2):
- Opening the reader after the OS killed the app shows the article it was first opened on, not the last page.
- Large media entries are read whole into memory.
- No device test yet for a ZIM truncated or ejected (SD card) while it is open.
```

Add any surprises found in Steps 2–4 to that list.

- [ ] **Step 6: Commit and push**

```bash
git add docs/superpowers/plans/2026-10-15-granger-v1-roadmap.md
git commit -m "Mark Plan 3 done; carry catalog, model and cap notes forward

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
git push
```
