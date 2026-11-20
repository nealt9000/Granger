#!/usr/bin/env python3
"""Plan I3 Task 4: the Android ids for the string keys shared models use.

Shared code (core/screens) names resources by key: UiText.Res("key", ...), UiText.Plural("key", ...). Android needs
R ids, and resource shrinking would drop strings looked up by name at run time, so this writes an explicit map:
app/src/main/kotlin/app/granger/ScreenTextIds.kt. ScreenTextIdsTest fails when a key in the shared code isn't mapped.

It also writes the iPhone's table of the same strings (ios/Granger/Sources/App/ScreenText.swift): every key above, plus
any other literal in the shared code that names an Android string (Subject.labelKey, Era.spanKey...), with its English
words. The String Catalog for Spanish comes with the languages plan (I7).

    python3 tools/ios/make_text_ids.py          # write
    python3 tools/ios/make_text_ids.py --check  # exit 1 if out of date
"""
import html
import xml.etree.ElementTree as ET
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHARED = ROOT / "core/screens/src/commonMain/kotlin"
OUT = ROOT / "app/src/main/kotlin/app/granger/ScreenTextIds.kt"
SWIFT = ROOT / "ios/Granger/Sources/App/ScreenText.swift"
VALUES = ROOT / "app/src/main/res/values"
LITERAL = re.compile(r'"([a-z][a-z0-9]*_[a-z0-9_]+)"')
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


def english():
    """Android's English strings and plurals by name, unescaped."""
    strings, plurals = {}, {}
    def text(e):
        t = "".join(e.itertext())
        if t.startswith('"') and t.endswith('"'):
            t = t[1:-1]
        return t.replace("\\'", "'").replace('\\"', '"').replace("\\n", "\n")
    for f in sorted(VALUES.glob("*.xml")):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for e in root.findall("string"):
            strings[e.get("name")] = text(e)
        for e in root.findall("plurals"):
            plurals[e.get("name")] = {i.get("quantity"): text(i) for i in e.findall("item")}
    return strings, plurals


def swift_keys(strings, plurals, en_strings, en_plurals):
    found = set(strings)
    for f in sorted(SHARED.rglob("*.kt")):
        found |= {k for k in LITERAL.findall(f.read_text()) if k in en_strings}
    return sorted(found), sorted(plurals)


def q(t):
    return '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def render_swift(strings, plurals, en_strings, en_plurals):
    missing = [k for k in strings if k not in en_strings] + [k for k in plurals if k not in en_plurals]
    if missing:
        sys.exit(f"no English string for {missing}")
    s = "".join(f"        {q(k)}: {q(en_strings[k])},\n" for k in strings)
    p = "".join(
        f"        {q(k)}: [" + ", ".join(f"{q(n)}: {q(v)}" for n, v in sorted(en_plurals[k].items())) + "],\n" for k in plurals
    )
    return f"""import Foundation

/// The English words for the string keys the shared models use (Subject and Era labels, UiText keys): Android's
/// strings, written by tools/ios/make_text_ids.py. Templates keep Android's %1$s / %1$d placeholders; `ScreenText.text`
/// fills them.
enum ScreenText {{
    static let strings: [String: String] = [
{s}    ]

    static let plurals: [String: [String: String]] = [
{p or "        :\n"}    ]
}}
"""


if __name__ == "__main__":
    strings, plurals = keys()
    text = render(strings, plurals)
    en_strings, en_plurals = english()
    swift = render_swift(*swift_keys(strings, plurals, en_strings, en_plurals), en_strings, en_plurals)
    if "--check" in sys.argv:
        sys.exit(0 if OUT.exists() and OUT.read_text() == text and SWIFT.exists() and SWIFT.read_text() == swift else 1)
    OUT.write_text(text)
    SWIFT.write_text(swift)
    print(f"wrote {OUT.relative_to(ROOT)} and {SWIFT.relative_to(ROOT)}")
