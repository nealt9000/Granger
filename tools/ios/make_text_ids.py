#!/usr/bin/env python3
"""Plan I3 Task 4: the Android ids for the string keys shared models use.

Shared code (core/screens) names resources by key: UiText.Res("key", ...), UiText.Plural("key", ...). Android needs
R ids, and resource shrinking would drop strings looked up by name at run time, so this writes an explicit map:
app/src/main/kotlin/app/granger/ScreenTextIds.kt. ScreenTextIdsTest fails when a key in the shared code isn't mapped.

    python3 tools/ios/make_text_ids.py          # write
    python3 tools/ios/make_text_ids.py --check  # exit 1 if out of date
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHARED = ROOT / "core/screens/src/commonMain/kotlin"
OUT = ROOT / "app/src/main/kotlin/app/granger/ScreenTextIds.kt"
RES = re.compile(r'UiText\.Res\(\s*"([a-z0-9_]+)"')
PLURAL = re.compile(r'UiText\.Plural\(\s*"([a-z0-9_]+)"')


def keys():
    strings, plurals = set(), set()
    for f in sorted(SHARED.rglob("*.kt")):
        t = f.read_text()
        strings |= set(RES.findall(t))
        plurals |= set(PLURAL.findall(t))
    return sorted(strings), sorted(plurals)


def render(strings, plurals):
    s = "".join(f'        "{k}" -> R.string.{k}\n' for k in strings)
    p = "".join(f'        "{k}" -> R.plurals.{k}\n' for k in plurals)
    return f'''package app.granger

/**
 * The Android ids for the string keys the shared models use (`UiText.Res("key")`). Written by
 * tools/ios/make_text_ids.py; `ScreenTextIdsTest` fails when a key is missing. An explicit map, not a lookup by
 * name, so resource shrinking keeps every string.
 */
object ScreenTextIds {{
    fun string(key: String): Int = when (key) {{
{s}        else -> error("no Android string for the shared key '$key' (run tools/ios/make_text_ids.py)")
    }}

    fun plural(key: String): Int = when (key) {{
{p}        else -> error("no Android plural for the shared key '$key' (run tools/ios/make_text_ids.py)")
    }}
}}
'''


if __name__ == "__main__":
    text = render(*keys())
    if "--check" in sys.argv:
        sys.exit(0 if OUT.exists() and OUT.read_text() == text else 1)
    OUT.write_text(text)
    print(f"wrote {OUT.relative_to(ROOT)}")
