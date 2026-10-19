#!/usr/bin/env bash
# Generates the audio test fixtures in testdata/audio/.
#
# Usage: tools/dev/make-audio-fixtures.sh
#
# Requirements:
#   - ffmpeg (tested with 9.0.2 from Homebrew) built with libmp3lame, libopus,
#     flac, aac and the native (experimental) vorbis encoder. ffmpeg encodes the
#     audio, writes the cover image, the LAME gapless header and the MP4 chapters.
#   - python3 (3.9+). The tags are written with mutagen (pinned below), because
#     ffmpeg can't write multi-value ID3v2.4 frames (null-separated TPE1),
#     embedded covers in Ogg/Opus/FLAC, or ID3 CHAP/CTOC frames. If mutagen
#     isn't importable, the script installs it into a throwaway venv in a temp dir.
#
# The output is deterministic: the encoders run with +bitexact, all tags are
# cleared and rewritten by mutagen, and broken.mp3 comes from a fixed seed.
# Re-running overwrites every fixture. Each fixture stays under 100 KB.
set -euo pipefail

MUTAGEN_VERSION="1.47.0"

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="$ROOT/testdata/audio"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$OUT"

command -v ffmpeg >/dev/null || { echo "ffmpeg is required" >&2; exit 1; }

PY=python3
if ! "$PY" -c "import mutagen" 2>/dev/null; then
  python3 -m venv "$WORK/venv"
  "$WORK/venv/bin/pip" install --quiet --disable-pip-version-check "mutagen==$MUTAGEN_VERSION"
  PY="$WORK/venv/bin/python"
fi

FF=(ffmpeg -hide_banner -loglevel error -y -fflags +bitexact)
BITEXACT=(-map_metadata -1 -fflags +bitexact -flags:a +bitexact)

# Source audio: a 2.5 s 440 Hz mono sine, and a small cover picture.
"${FF[@]}" -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=2.5" -ac 1 "$WORK/sine.wav"
"${FF[@]}" -f lavfi -i "testsrc=size=64x64:rate=1:duration=1" -frames:v 1 -flags:v +bitexact "$WORK/cover.jpg"

# MP3s are written without any ffmpeg ID3 tag; mutagen adds the tags below.
MP3=(-c:a libmp3lame -b:a 64k -id3v2_version 0 -write_id3v1 0)
"${FF[@]}" -i "$WORK/sine.wav" "${BITEXACT[@]}" "${MP3[@]}" "$OUT/tagged.mp3"
"${FF[@]}" -i "$WORK/sine.wav" "${BITEXACT[@]}" "${MP3[@]}" "$OUT/untagged.mp3"
"${FF[@]}" -i "$WORK/sine.wav" "${BITEXACT[@]}" -ar 22050 -c:a flac "$OUT/tagged.flac"
"${FF[@]}" -i "$WORK/sine.wav" "${BITEXACT[@]}" -c:a vorbis -strict experimental -ac 2 -q:a 0 "$OUT/tagged.ogg"
"${FF[@]}" -i "$WORK/sine.wav" "${BITEXACT[@]}" -c:a libopus -b:a 32k "$OUT/tagged.opus"
"${FF[@]}" -i "$WORK/sine.wav" "${BITEXACT[@]}" -c:a aac -b:a 64k "$OUT/tagged.m4a"

# Gapless pair: one continuous 5 s sine split sample-exactly at 2.5 s, each half
# LAME-encoded so the Xing/LAME header carries the encoder delay and padding.
"${FF[@]}" -f lavfi -i "sine=frequency=440:sample_rate=44100:duration=5" -ac 1 "$WORK/long.wav"
"${FF[@]}" -i "$WORK/long.wav" -af "atrim=end_sample=110250" "$WORK/half1.wav"
"${FF[@]}" -i "$WORK/long.wav" -af "atrim=start_sample=110250,asetpts=PTS-STARTPTS" "$WORK/half2.wav"
"${FF[@]}" -i "$WORK/half1.wav" "${BITEXACT[@]}" "${MP3[@]}" "$OUT/gapless-1.mp3"
"${FF[@]}" -i "$WORK/half2.wav" "${BITEXACT[@]}" "${MP3[@]}" "$OUT/gapless-2.mp3"

# Chapters: a 3 s file with chapters One (0 s), Two (1 s), Three (2 s).
"${FF[@]}" -f lavfi -i "sine=frequency=330:sample_rate=22050:duration=3" -ac 1 "$WORK/book.wav"
cat > "$WORK/chapters.txt" <<'EOF'
;FFMETADATA1
title=Chapter Test Book
artist=Test Narrator
album=Chapter Test Book
[CHAPTER]
TIMEBASE=1/1000
START=0
END=1000
title=One
[CHAPTER]
TIMEBASE=1/1000
START=1000
END=2000
title=Two
[CHAPTER]
TIMEBASE=1/1000
START=2000
END=3000
title=Three
EOF
# The mp4 muxer writes both a Nero chpl box and a QuickTime chapter track.
"${FF[@]}" -i "$WORK/book.wav" -f ffmetadata -i "$WORK/chapters.txt" -map 0:a -map_metadata 1 -map_chapters 1 \
  -fflags +bitexact -flags:a +bitexact -c:a aac -b:a 32k -f mp4 "$OUT/chapters.m4b"
"${FF[@]}" -i "$WORK/book.wav" "${BITEXACT[@]}" -map_chapters -1 "${MP3[@]}" -b:a 32k "$OUT/chapters.mp3"

"$PY" - "$OUT" "$WORK/cover.jpg" <<'PYEOF'
import base64, random, sys
from mutagen.id3 import (ID3, APIC, CHAP, CTOC, CTOCFlags, TALB, TCOM, TCON, TDOR, TDRC, TIT2,
                         TPE1, TPE2, TPOS, TRCK, TSO2, TSOA, TSOP, TSOT, TXXX)
from mutagen.flac import FLAC, Picture
from mutagen.oggvorbis import OggVorbis
from mutagen.oggopus import OggOpus
from mutagen.mp4 import MP4, MP4Cover, MP4FreeForm

out, cover_path = sys.argv[1], sys.argv[2]
cover = open(cover_path, "rb").read()

TITLE = "Mars, the Bringer of War"
ALBUM = "The Planets"
ALBUMARTIST = "Gustav Holst"
ARTISTS = ["London Symphony Orchestra", "Gustav Holst"]
COMPOSER = "Gustav Holst"
GENRE = "Classical"
DATE = "1926"
ORIGINALDATE = "1916"
TRACK_GAIN = "-6.50 dB"
TRACK_PEAK = "0.988525"
ALBUM_GAIN = "-7.10 dB"
ALBUM_PEAK = "0.999969"

# tagged.mp3: ID3v2.4 with multi-value TPE1 (null-separated, written by mutagen).
id3 = ID3()
id3.add(TIT2(encoding=3, text=[TITLE]))
id3.add(TALB(encoding=3, text=[ALBUM]))
id3.add(TPE2(encoding=3, text=[ALBUMARTIST]))
id3.add(TPE1(encoding=3, text=ARTISTS))
id3.add(TCOM(encoding=3, text=[COMPOSER]))
id3.add(TCON(encoding=3, text=[GENRE]))
id3.add(TPOS(encoding=3, text=["1/2"]))
id3.add(TRCK(encoding=3, text=["3/10"]))
id3.add(TDRC(encoding=3, text=[DATE]))
id3.add(TDOR(encoding=3, text=[ORIGINALDATE]))
id3.add(TSOT(encoding=3, text=["Mars"]))
id3.add(TSOA(encoding=3, text=["Planets"]))
id3.add(TSOP(encoding=3, text=["London Symphony Orchestra"]))
id3.add(TSO2(encoding=3, text=["Holst, Gustav"]))
id3.add(TXXX(encoding=3, desc="REPLAYGAIN_TRACK_GAIN", text=[TRACK_GAIN]))
id3.add(TXXX(encoding=3, desc="REPLAYGAIN_TRACK_PEAK", text=[TRACK_PEAK]))
id3.add(TXXX(encoding=3, desc="REPLAYGAIN_ALBUM_GAIN", text=[ALBUM_GAIN]))
id3.add(TXXX(encoding=3, desc="REPLAYGAIN_ALBUM_PEAK", text=[ALBUM_PEAK]))
id3.add(APIC(encoding=3, mime="image/jpeg", type=3, desc="Cover", data=cover))
id3.save(f"{out}/tagged.mp3", v2_version=4)

# Vorbis-comment formats.
def vorbis_fields(tags):
    tags["TITLE"] = [TITLE]
    tags["ALBUM"] = [ALBUM]
    tags["ALBUMARTIST"] = [ALBUMARTIST]
    tags["ARTIST"] = ARTISTS
    tags["COMPOSER"] = [COMPOSER]
    tags["GENRE"] = [GENRE]
    tags["DISCNUMBER"] = ["1"]
    tags["DISCTOTAL"] = ["2"]
    tags["TRACKNUMBER"] = ["3"]
    tags["TRACKTOTAL"] = ["10"]
    tags["DATE"] = [DATE]
    tags["ORIGINALDATE"] = [ORIGINALDATE]

def picture():
    p = Picture()
    p.type = 3
    p.mime = "image/jpeg"
    p.desc = "Cover"
    p.width = p.height = 64
    p.depth = 24
    p.data = cover
    return p

def rg_fields(tags):
    tags["REPLAYGAIN_TRACK_GAIN"] = [TRACK_GAIN]
    tags["REPLAYGAIN_TRACK_PEAK"] = [TRACK_PEAK]
    tags["REPLAYGAIN_ALBUM_GAIN"] = [ALBUM_GAIN]
    tags["REPLAYGAIN_ALBUM_PEAK"] = [ALBUM_PEAK]

f = FLAC(f"{out}/tagged.flac")
f.delete()
f.clear_pictures()
vorbis_fields(f)
rg_fields(f)
f.add_picture(picture())
f.save()

for name, cls in (("tagged.ogg", OggVorbis), ("tagged.opus", OggOpus)):
    f = cls(f"{out}/{name}")
    f.delete()
    vorbis_fields(f)
    if cls is OggOpus:
        # Opus uses R128 gain (Q7.8 dB relative to -23 LUFS), not ReplayGain.
        f["R128_TRACK_GAIN"] = ["-1536"]
        f["R128_ALBUM_GAIN"] = ["-1690"]
    else:
        rg_fields(f)
    f["METADATA_BLOCK_PICTURE"] = [base64.b64encode(picture().write()).decode("ascii")]
    f.save()

f = MP4(f"{out}/tagged.m4a")
f.delete()
f["\xa9nam"] = [TITLE]
f["\xa9alb"] = [ALBUM]
f["aART"] = [ALBUMARTIST]
f["\xa9ART"] = ARTISTS
f["\xa9wrt"] = [COMPOSER]
f["\xa9gen"] = [GENRE]
f["disk"] = [(1, 2)]
f["trkn"] = [(3, 10)]
f["\xa9day"] = [DATE]
f["sonm"] = ["Mars"]
f["soal"] = ["Planets"]
f["soar"] = ["London Symphony Orchestra"]
f["soaa"] = ["Holst, Gustav"]
for key, value in (("REPLAYGAIN_TRACK_GAIN", TRACK_GAIN), ("REPLAYGAIN_TRACK_PEAK", TRACK_PEAK),
                   ("REPLAYGAIN_ALBUM_GAIN", ALBUM_GAIN), ("REPLAYGAIN_ALBUM_PEAK", ALBUM_PEAK)):
    f[f"----:com.apple.iTunes:{key}"] = [MP4FreeForm(value.encode("utf-8"))]
f["covr"] = [MP4Cover(cover, imageformat=MP4Cover.FORMAT_JPEG)]
f.save()

# Gapless pair: plain tags so they group as one album.
for i in (1, 2):
    id3 = ID3()
    id3.add(TIT2(encoding=3, text=[f"Gapless {i}"]))
    id3.add(TALB(encoding=3, text=["Gapless Test"]))
    id3.add(TPE1(encoding=3, text=["Test Artist"]))
    id3.add(TRCK(encoding=3, text=[f"{i}/2"]))
    id3.save(f"{out}/gapless-{i}.mp3", v2_version=4)

# chapters.mp3: ID3v2.4 CTOC + 3 CHAP frames (One 0 s, Two 1 s, Three 2 s).
id3 = ID3()
id3.add(TIT2(encoding=3, text=["Chapter Test Book"]))
id3.add(TPE1(encoding=3, text=["Test Narrator"]))
id3.add(TALB(encoding=3, text=["Chapter Test Book"]))
ids = ["chp0", "chp1", "chp2"]
id3.add(CTOC(element_id="toc", flags=CTOCFlags.TOP_LEVEL | CTOCFlags.ORDERED, child_element_ids=ids,
             sub_frames=[TIT2(encoding=3, text=["Contents"])]))
for i, (cid, title) in enumerate(zip(ids, ["One", "Two", "Three"])):
    id3.add(CHAP(element_id=cid, start_time=i * 1000, end_time=(i + 1) * 1000,
                 start_offset=0xFFFFFFFF, end_offset=0xFFFFFFFF,
                 sub_frames=[TIT2(encoding=3, text=[title])]))
id3.save(f"{out}/chapters.mp3", v2_version=4)

# broken.mp3: 4 KiB of seeded random bytes. 0xFF is left out so no byte pair can
# look like an MPEG frame sync, which keeps the file unparseable as any format.
rng = random.Random(20261019)
open(f"{out}/broken.mp3", "wb").write(bytes(rng.randrange(0, 0xFF) for _ in range(4096)))
PYEOF

ls -l "$OUT"
