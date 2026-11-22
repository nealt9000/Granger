#!/usr/bin/env python3
"""The words both apps share, from Android's strings.xml (Plan I3 Task 4, Plan I7 Task 3).

Shared code (core/screens) names resources by key: UiText.Res("key", ...), UiText.Plural("key", ...). Android needs
R ids, and resource shrinking would drop strings looked up by name at run time, so this writes an explicit map:
app/src/main/kotlin/app/granger/ScreenTextIds.kt. ScreenTextIdsTest fails when a key in the shared code isn't mapped.

It writes the iPhone's table of the same keys (ios/Granger/Sources/App/ScreenText.swift): every key above, plus any
other literal in the shared code that names an Android string (Subject.labelKey, Era.spanKey...), in English and in
Spanish (values-es), with Android's %1$s placeholders that UiTextWords fills. The app picks the table for the language
iOS runs it in.

And it writes the iPhone's String Catalog (ios/Granger/Resources/Localizable.xcstrings, Plan I7 call 1) for the words
SwiftUI shows: every literal the Swift compiler extracts (Text("…"), Button, Label, Section, navigationTitle,
accessibilityLabel, String(localized:), L("…")…), read from the build's .stringsdata files. A literal whose English is an
Android string (placeholders compared as placeholders, %1$s against %@) takes that string's Spanish, with Android's
arguments turned into Swift's (%1$s -> %1$@, %1$d -> %1$lld, by the type the Swift code interpolates); a literal that
is an Android plural's "other" form takes the plural's forms. The rest are iPhone-only and come from
ios/Granger/Resources/ios-only-strings.json (English and Spanish side by side). Literals without letters aren't
translated.

    python3 tools/ios/make_text_ids.py [--build DIR] [--report]   # write (DIR: Granger.build/<config> intermediates)
    python3 tools/ios/make_text_ids.py --check [--build DIR]      # exit 1 if out of date or a word on screen lacks Spanish

Without --build, the newest DerivedData build of Granger is used; with none, the catalog is left alone (write) or not
checked (check). --check also lints the Swift sources for English literals the compiler can't see (a String the
catalog never meets); tools/ios/not-on-screen.txt lists the ones that are never shown. GrangerTests runs --check on
every test build.
"""
import collections, glob, json, os, pathlib, re, sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[2]
SHARED = ROOT / "core/screens/src/commonMain/kotlin"
# Plan I5: core/archive's shelves and errors name their words by key too.
SHARED_DIRS = [SHARED, ROOT / "core/archive/src/commonMain/kotlin"]
OUT = ROOT / "app/src/main/kotlin/app/granger/ScreenTextIds.kt"
IOS = ROOT / "ios/Granger"
SWIFT = IOS / "Sources/App/ScreenText.swift"
CATALOG = IOS / "Resources/Localizable.xcstrings"
IOS_ONLY = IOS / "Resources/ios-only-strings.json"
INFOPLIST = IOS / "Resources/InfoPlist.xcstrings"
NOT_ON_SCREEN = ROOT / "tools/ios/not-on-screen.txt"
VALUES = ROOT / "app/src/main/res/values"
VALUES_ES = ROOT / "app/src/main/res/values-es"
TARGETS = ["Granger", "GrangerWidgets"]
LITERAL = re.compile(r'"([a-z][a-z0-9]*_[a-z0-9_]+)"')
RES = re.compile(r'UiText\.Res\(\s*"([a-z0-9_]+)"')
PLURAL = re.compile(r'UiText\.Plural\(\s*"([a-z0-9_]+)"')


# ---- the shared keys (ScreenTextIds.kt, ScreenText.swift) ----

def shared_files():
    return [f for d in SHARED_DIRS for f in sorted(d.rglob("*.kt"))]


def keys():
    strings, plurals = set(), set()
    for f in shared_files():
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


def android_text(e):
    """An Android resource's text as the app shows it: quotes, escapes (\\', \\n, \\u00A0) and whitespace as aapt reads
    them; the XML parser has already turned &amp; and the like into characters."""
    raw = "".join(e.itertext())
    out, i, quoted = [], 0, False
    while i < len(raw):
        c = raw[i]
        if c == "\\" and i + 1 < len(raw):
            n = raw[i + 1]
            if n == "u" and re.fullmatch(r"[0-9a-fA-F]{4}", raw[i + 2:i + 6]):
                out.append(chr(int(raw[i + 2:i + 6], 16))); i += 6; continue
            out.append({"n": "\n", "t": "\t"}.get(n, n)); i += 2; continue
        if c == '"':
            quoted = not quoted; i += 1; continue
        if not quoted and c in " \t\n\r":
            if not out or out[-1] != " ":
                out.append(" ")
            i += 1; continue
        out.append(c); i += 1
    t = "".join(out)
    return t if raw.strip().startswith('"') else t.strip()


def resources(folder):
    """Android's strings and plurals by name, and the names marked translatable="false"."""
    strings, plurals, fixed = {}, {}, set()
    for f in sorted(folder.glob("*.xml")):
        try:
            root = ET.parse(f).getroot()
        except ET.ParseError:
            continue
        for e in root.findall("string"):
            strings[e.get("name")] = android_text(e)
            if e.get("translatable") == "false":
                fixed.add(e.get("name"))
        for e in root.findall("plurals"):
            plurals[e.get("name")] = {i.get("quantity"): android_text(i) for i in e.findall("item")}
    return strings, plurals, fixed


def english():
    s, p, _ = resources(VALUES)
    return s, p


SWIFT_USE = re.compile(r'ScreenText\.(?:word\(|strings\[)"([a-z0-9_]+)"')


def swift_keys(strings, plurals, en_strings, en_plurals):
    """The shared keys, plus Android keys the iPhone's Swift names itself (ScreenText.word("home_timeline_bc"))."""
    found = set(strings) - set(en_plurals)
    for f in shared_files():
        found |= {k for k in LITERAL.findall(f.read_text()) if k in en_strings}
    for f in sorted((IOS / "Sources").rglob("*.swift")):
        found |= set(SWIFT_USE.findall(f.read_text()))
    return sorted(found), sorted(plurals)


def q(t):
    return '"' + t.replace("\\", "\\\\").replace('"', '\\"').replace("\n", "\\n") + '"'


def render_swift(strings, plurals, en, es):
    (en_s, en_p, _), (es_s, es_p, _) = en, es
    missing = [k for k in strings if k not in en_s] + [k for k in plurals if k not in en_p]
    if missing:
        sys.exit(f"no English string for {missing}")
    def table(src):
        return "".join(f"        {q(k)}: {q(src.get(k, en_s[k]))},\n" for k in strings)
    def plural_table(src):
        rows = ""
        for k in plurals:
            forms = src.get(k, en_p[k])
            rows += f"        {q(k)}: [" + ", ".join(f"{q(n)}: {q(v)}" for n, v in sorted(forms.items())) + "],\n"
        return rows or "        :\n"
    return f"""import Foundation

/// The words for the string keys the shared models use (Subject and Era labels, UiText keys), in English and Spanish:
/// Android's strings (values, values-es), written by tools/ios/make_text_ids.py. Templates keep Android's %1$s / %1$d
/// placeholders; `UiTextWords.fill` fills them. The table is the one for the language iOS runs the app in (Settings ›
/// Granger › Language), the same choice the String Catalog's words follow.
enum ScreenText {{
    static let spanish = Bundle.main.preferredLocalizations.first?.hasPrefix("es") == true
    static let strings: [String: String] = spanish ? es : en
    static let plurals: [String: [String: String]] = spanish ? esPlurals : enPlurals

    /// [key]'s words in the app's language (the key itself if it isn't here).
    static func word(_ key: String) -> String {{ strings[key] ?? key }}

    static let en: [String: String] = [
{table(en_s)}    ]

    static let es: [String: String] = [
{table(es_s)}    ]

    static let enPlurals: [String: [String: String]] = [
{plural_table(en_p)}    ]

    static let esPlurals: [String: [String: String]] = [
{plural_table(es_p)}    ]
}}
"""


def literal_keys(en_strings, en_plurals):
    """Keys the shared code names as plain literals and passes on (KiwixList's flavour labels, a Folded noun): Plan I4."""
    found_s, found_p = set(), set()
    for f in shared_files():
        for k in LITERAL.findall(f.read_text()):
            if k in en_strings:
                found_s.add(k)
            elif k in en_plurals:
                found_p.add(k)
    return found_s, found_p


# ---- the String Catalog (Localizable.xcstrings) ----

ANDROID_ARG = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([sdfxXc])|%%")
SWIFT_ARG = re.compile(r"%(?:(\d+)\$)?(lld|llu|ld|lu|li|d|i|u|@|lf|f|s|c)|%%")
INT_SPECS = ("lld", "llu", "ld", "lu", "li", "d", "i", "u")


def args(text, pattern):
    """[(start, end, position or None, spec)] for each placeholder, %% left out."""
    return [(m.start(), m.end(), m.group(1), m.group(2)) for m in pattern.finditer(text) if m.group(0) != "%%"]


def skeleton(text, pattern):
    """The words with every placeholder as \\0, %% as %, no-break spaces as spaces: what two keys must share to match."""
    return pattern.sub(lambda m: "%" if m.group(0) == "%%" else "\0", text).replace("\u00a0", " ").replace("\u202f", " ")


def android_order(text):
    """Android's argument numbers in the order they appear in [text] (1-based; a bare %s counts on from the last)."""
    out, nxt = [], 1
    for _, _, pos, _ in args(text, ANDROID_ARG):
        n = int(pos) if pos else nxt
        nxt = n + 1
        out.append(n)
    return out


def to_swift(value, english_android, swift_key):
    """Android's [value] (a translation of [english_android]) with its placeholders as the Swift key's.

    The i-th placeholder of the English Android string is the i-th of the Swift key; Android's argument number n there
    becomes Swift argument i, with the Swift key's type (%@, %lld, %d...). With several, the specifiers are positional
    so the translation may reorder them.
    """
    swift = args(swift_key, SWIFT_ARG)
    order = android_order(english_android)
    if len(order) != len(swift):
        raise ValueError(f"placeholder count differs: {english_android!r} vs {swift_key!r}")
    by_android = {n: (i + 1, swift[i][3]) for i, n in enumerate(order)}
    nxt = [1]
    def sub(m):
        if m.group(0) == "%%":
            return "%%"
        n = int(m.group(1)) if m.group(1) else nxt[0]
        nxt[0] = n + 1
        if n not in by_android:
            raise ValueError(f"argument {n} of {value!r} isn't in {swift_key!r}")
        i, spec = by_android[n]
        return f"%{i}${spec}" if len(swift) > 1 else f"%{spec}"
    out = ANDROID_ARG.sub(sub, value)
    return out if swift else out.replace("%%", "%")


def swift_value(english_key, value):
    """An iPhone-only translation in the key's own placeholders (%@, %lld), made positional when there are several."""
    swift = args(english_key, SWIFT_ARG)
    if len(swift) <= 1 or re.search(r"%\d+\$", value):
        return value
    it = iter(range(1, 100))
    return SWIFT_ARG.sub(lambda m: m.group(0) if m.group(0) == "%%" else f"%{next(it)}${m.group(2)}", value)


def stringsdata(build):
    """{key: [source file, ...]} for every literal the compiler extracted in the app and widget targets."""
    found = collections.defaultdict(list)
    for t in TARGETS:
        for f in sorted(glob.glob(f"{build}/{t}.build/Objects-normal/*/*.stringsdata")):
            j = json.load(open(f))
            src = os.path.realpath(j.get("source", ""))
            if not os.path.exists(src) or not src.startswith(os.path.realpath(IOS)):
                continue
            for e in j.get("tables", {}).get("Localizable", []):
                found[e["key"]].append(os.path.relpath(src, os.path.realpath(IOS)))
    return found


def find_build():
    dd = pathlib.Path.home() / "Library/Developer/Xcode/DerivedData"
    hits = sorted(glob.glob(f"{dd}/Granger-*/Build/Intermediates.noindex/Granger.build/*-iphonesimulator"), key=os.path.getmtime)
    return hits[-1] if hits else None


# When one English has two Spanish words on Android, the key from the Swift file's own area wins.
FOLDER_PREFIX = {
    "Archive": ["archive"], "Audiobooks": ["audiobooks", "librivox", "player"], "Books": ["ebook", "reader", "bookshelf", "library"],
    "Downloads": ["downloads", "sources", "browse"], "Explore": ["explore", "games", "subject", "wander"],
    "Home": ["home", "readiness", "timeline", "feed"], "Library": ["library", "bookshelf"], "Music": ["music", "media"],
    "Voice": ["listen", "voice", "player"], "Reader": ["reader", "app"], "Search": ["search"], "Settings": ["settings"],
    "Topic": ["topic", "explore"], "App": ["shell", "onboarding", "app"], "System": ["media", "widget"], "Widgets": ["widget"],
}


def has_words(key):
    return re.search(r"[A-Za-z]{2}", SWIFT_ARG.sub("", key)) is not None


def unit(value):
    return {"stringUnit": {"state": "translated", "value": value}}


def plural_localization(forms, key):
    """A plural entry. With one placeholder the whole string varies; with more, one substitution of whole sentences
    keyed on the count (the integer argument nearest before where English's one and other forms part)."""
    swift = args(key, SWIFT_ARG)
    if len(swift) <= 1:
        return {"variations": {"plural": {n: unit(v) for n, v in forms.items()}}}
    other = forms["other"]
    one = forms.get("one", other)
    cut = next((i for i, (a, b) in enumerate(zip(one, other)) if a != b), min(len(one), len(other)))
    ints = [m for m in re.finditer(r"%(\d+)\$(" + "|".join(INT_SPECS) + ")", other)]
    before = [m for m in ints if m.start() < cut] or ints
    if not before:
        return {"variations": {"plural": {n: unit(v) for n, v in forms.items()}}}
    count = before[-1]
    variants = {n: unit(v.replace(count.group(0), "%arg")) for n, v in forms.items()}
    return {"stringUnit": {"state": "translated", "value": "%#@count@"},
            "substitutions": {"count": {"argNum": int(count.group(1)), "formatSpecifier": count.group(2),
                                        "variations": {"plural": variants}}}}


def catalog(extracted):
    """(catalog, report) from the extracted keys, Android's words and the iPhone-only file."""
    en_s, en_p, fixed = resources(VALUES)
    es_s, es_p, _ = resources(VALUES_ES)
    only = json.loads(IOS_ONLY.read_text())["strings"] if IOS_ONLY.exists() else {}
    by_skel = collections.defaultdict(list)
    for k, v in en_s.items():
        by_skel[skeleton(v, ANDROID_ARG)].append(k)
    plural_by_skel = collections.defaultdict(list)
    for k, forms in en_p.items():
        if "other" in forms:
            plural_by_skel[skeleton(forms["other"], ANDROID_ARG)].append(k)

    def pick(names, files):
        """Of the Android keys with this English: any, if they share one Spanish; else the Swift file's area's own."""
        spanish = {es_s.get(n) for n in names if n in es_s}
        if len(spanish) <= 1:
            return sorted(names, key=lambda n: (n not in es_s, n))[0], False
        folders = [f.split("/")[1] if f.startswith("Sources/") else f.split("/")[0] for f in files]
        prefixes = [p for f in folders for p in FOLDER_PREFIX.get(f, [])]
        for p in prefixes + ["common"]:
            hit = sorted(n for n in names if n.startswith(p + "_") and n in es_s)
            if hit:
                return hit[0], True
        return sorted(n for n in names if n in es_s)[0], True

    strings, report = {}, collections.defaultdict(list)
    for key in sorted(extracted):
        files = sorted(set(extracted[key]))
        has_args = bool(args(key, SWIFT_ARG))
        if not has_words(key) or (key in only and only[key] is None):
            strings[key] = {"shouldTranslate": False}
            report["fixed"].append(key)
            continue
        if key in only and isinstance(only[key], str) and only[key].startswith("@"):
            # Android has the words, in other English ("this phone" for "this iPhone") or under two keys: "@key" names it.
            name = only[key][1:]
            try:
                strings[key] = {"localizations": {"es": unit(to_swift(es_s[name], en_s[name], key))}}
            except (KeyError, ValueError) as err:
                report["broken"].append((key, f"{only[key]}: {err}"))
                continue
            report["matched"].append((key, name))
            report["chosen"].append(key)
            if has_args:
                report["with_args"].append(key)
            continue
        if key in only:
            entry = only[key]
            if isinstance(entry, dict):
                strings[key] = {"localizations": {
                    "en": plural_localization({n: swift_value(key, v) for n, v in entry["en"].items()}, key),
                    "es": plural_localization({n: swift_value(key, v) for n, v in entry["es"].items()}, key)}}
            else:
                strings[key] = {"localizations": {"es": unit(swift_value(key, entry))}}
            report["ios_only"].append(key)
            if has_args:
                report["with_args"].append(key)
            continue
        skel = skeleton(key, SWIFT_ARG)
        names, plural_names = by_skel.get(skel, []), plural_by_skel.get(skel, [])
        if names:
            name, ambiguous = pick(names, files)
            if ambiguous:
                report["ambiguous"].append((key, name, sorted(names)))
            if name in fixed:
                strings[key] = {"shouldTranslate": False}
                report["fixed"].append(key)
                continue
            if name not in es_s:  # Android has no Spanish for it either: iPhone-only until values-es has it
                report["missing"].append((key, files + [f"Android's {name} has no Spanish"]))
                continue
            try:
                strings[key] = {"localizations": {"es": unit(to_swift(es_s[name], en_s[name], key))}}
            except ValueError as err:
                report["broken"].append((key, str(err)))
                continue
            report["matched"].append((key, name))
        elif plural_names:
            name = sorted(plural_names, key=lambda n: (n not in es_p, n))[0]
            other = en_p[name]["other"]
            try:
                en_forms = {n: to_swift(v, other, key) for n, v in en_p[name].items() if n in ("one", "other")}
                es_forms = {n: to_swift(v, other, key) for n, v in es_p.get(name, {}).items() if n in ("one", "many", "other")}
            except ValueError as err:
                report["broken"].append((key, str(err)))
                continue
            if not es_forms:
                report["missing"].append((key, files))
                continue
            if not any(a[3] in INT_SPECS for a in args(key, SWIFT_ARG)):
                # The count comes formatted (a String, as "1,204 topics"), and the code says "1 topic" itself: this
                # is the plural's other form.
                strings[key] = {"localizations": {"es": unit(es_forms["other"])}}
                report["matched"].append((key, name))
                if has_args:
                    report["with_args"].append(key)
                continue
            strings[key] = {"localizations": {"en": plural_localization(en_forms, key), "es": plural_localization(es_forms, key)}}
            report["plural"].append((key, name))
        else:
            report["missing"].append((key, files))
            continue
        if has_args:
            report["with_args"].append(key)
    report["unused_ios_only"] = sorted(k for k in only if k not in extracted)
    return {"sourceLanguage": "en", "strings": strings, "version": "1.0"}, report


def infoplist_catalog():
    """InfoPlist.xcstrings: Info.plist's words in Spanish (the English stays in project.yml's INFOPLIST_KEY_*)."""
    words = json.loads(IOS_ONLY.read_text()).get("infoPlist", {}) if IOS_ONLY.exists() else {}
    strings = {k: ({"shouldTranslate": False} if v is None else {"localizations": {"es": unit(v)}}) for k, v in words.items()}
    return render_catalog({"sourceLanguage": "en", "strings": strings, "version": "1.0"})


def render_catalog(cat):
    return json.dumps(cat, ensure_ascii=False, indent=2, sort_keys=True, separators=(",", " : ")) + "\n"


# ---- the lint: English literals the compiler can't see ----

SKIP_LINE = re.compile(
    r"NSLog|print\(|os_log|Logger\(|fatalError|precondition|assert|accessibilityIdentifier|forKey|UserDefaults|"
    r"systemImage:|systemName:|Image\(\"|named:|URL\(|metadata\(|\.tag\(|Notification\.Name|appendingPathComponent|"
    r"Text\(verbatim:|debugNotice|#\"|^\s*//")
SKIP_FILES = re.compile(r"(^|/)(Debug[A-Za-z]*|SampleTextPdf|ScreenText|Words)\.swift$")
SWIFT_LIT = re.compile(r'"((?:[^"\\]|\\.)*)"')


def literal_skeleton(text):
    """A Swift literal's words with each \\(…) as a placeholder, as the compiler keys it."""
    out, i = [], 0
    while i < len(text):
        if text.startswith("\\(", i):
            depth, j = 1, i + 2
            while j < len(text) and depth:
                depth += {"(": 1, ")": -1}.get(text[j], 0)
                j += 1
            out.append("\0"); i = j; continue
        if text[i] == "\\" and i + 1 < len(text):
            out.append({"n": "\n", "t": "\t"}.get(text[i + 1], text[i + 1])); i += 2; continue
        out.append(text[i]); i += 1
    return "".join(out).replace("\u00a0", " ")


def looks_like_words(t):
    words = literal_skeleton(t).replace("\0", " ")
    if not re.search(r"[A-Za-z]{2}", words):
        return False
    if re.fullmatch(r"[A-Za-z0-9_.\-/:@+=]+", words) and not re.fullmatch(r"[A-Z][a-z]+[.!?…]?", words):
        return False  # identifiers, keys, paths, camelCase, symbol names
    return bool(re.search(r"[A-Za-z]{2,}[ ,:;.!?…][^\"]*[A-Za-z]", words) or re.fullmatch(r"\s*[A-Z][a-z]+[.!?…]?\s*", words))


def swift_literals(line):
    """The string literals on a line of Swift, interpolations (which may hold literals of their own) taken whole."""
    out, i = [], 0
    while True:
        i = line.find('"', i)
        if i < 0:
            return out
        if line.startswith('"""', i):
            return out
        j, depth, buf = i + 1, 0, []
        while j < len(line):
            c = line[j]
            if depth == 0 and c == "\\" and line.startswith("\\(", j):
                depth, j = 1, j + 2
                buf.append("\\(")
                continue
            if depth:
                depth += {"(": 1, ")": -1}.get(c, 0)
                buf.append(c); j += 1
                continue
            if c == "\\":
                buf.append(line[j:j + 2]); j += 2; continue
            if c == '"':
                break
            buf.append(c); j += 1
        out.append("".join(buf))
        i = j + 1


def lint(extracted):
    """English-looking literals in the Swift sources the compiler didn't extract and that aren't listed as never shown:
    each would stay English in Spanish. A literal counts as extracted when its file's keys hold its words."""
    allowed = set()
    if NOT_ON_SCREEN.exists():
        for line in NOT_ON_SCREEN.read_text().splitlines():
            if line.strip() and not line.startswith("#"):
                allowed.add(line)
    keyed = collections.defaultdict(set)
    for k, files in extracted.items():
        for f in files:
            keyed[f].add(skeleton(k, SWIFT_ARG))
    found = []
    for d in ["Sources", "Widgets"]:
        for f in sorted((IOS / d).rglob("*.swift")):
            rel = str(f.relative_to(IOS))
            if SKIP_FILES.search(rel):
                continue
            in_block = False
            for n, line in enumerate(f.read_text().splitlines(), 1):
                if line.count('"""') % 2 == 1:
                    in_block = not in_block
                    continue
                if in_block or SKIP_LINE.search(line):
                    continue
                for t in swift_literals(line):
                    if not looks_like_words(t) or literal_skeleton(t) in keyed.get(rel, ()) or f"{rel}: {t}" in allowed:
                        continue
                    found.append(f"{rel}:{n}: {t}")
    return found


# ---- main ----

def main():
    check = "--check" in sys.argv
    build = sys.argv[sys.argv.index("--build") + 1] if "--build" in sys.argv else find_build()
    strings, plurals = keys()
    en_strings, en_plurals = english()
    lit_s, lit_p = literal_keys(en_strings, en_plurals)
    strings, plurals = sorted(set(strings) | lit_s), sorted(set(plurals) | lit_p)
    text = render(strings, plurals)
    sk, pk = swift_keys(strings, plurals, en_strings, en_plurals)
    en, es = resources(VALUES), resources(VALUES_ES)
    swift = render_swift(sk, pk, en, es)
    problems = []
    no_spanish = [k for k in sk if k not in es[0] and k not in en[2]] + [k for k in pk if k not in es[1]]
    if no_spanish:
        problems.append(f"shared keys without Spanish in values-es: {no_spanish}")
    cat_text = None
    if build and glob.glob(f"{build}/Granger.build/Objects-normal/*/*.stringsdata"):
        extracted = stringsdata(build)
        cat, report = catalog(extracted)
        cat_text = render_catalog(cat)
        for key, files in report["missing"]:
            problems.append(f"no Spanish for {key!r} ({', '.join(files)}): match an Android string or add it to ios-only-strings.json")
        for key, err in report["broken"]:
            problems.append(f"can't map {key!r}: {err}")
        if "--report" in sys.argv:
            print(f"keys {len(extracted)}: matched {len(report['matched'])}, plurals {len(report['plural'])}, "
                  f"(of them chosen by @key {len(report['chosen'])}), iPhone-only {len(report['ios_only'])}, not translated {len(report['fixed'])}, "
                  f"with arguments {len(set(report['with_args']))}, missing {len(report['missing'])}")
            for key, name, names in report["ambiguous"]:
                print(f"  ambiguous: {key!r} -> {name} of {names}")
            for key in report["unused_ios_only"]:
                print(f"  ios-only not on screen any more: {key!r}")
        if check or "--lint" in sys.argv:
            for hit in lint(extracted):
                problems.append(f"English the catalog can't see (wrap it in L(…) or list it in tools/ios/not-on-screen.txt): {hit}")
    elif check:
        print(f"note: no Granger build with .stringsdata found{' in ' + build if build else ''}; catalog not checked", file=sys.stderr)
    if check:
        stale = [p.name for p, t in [(OUT, text), (SWIFT, swift), (INFOPLIST, infoplist_catalog())] + ([(CATALOG, cat_text)] if cat_text else [])
                 if not p.exists() or p.read_text() != t]
        if stale:
            problems.append(f"out of date (run tools/ios/make_text_ids.py): {stale}")
        for p in problems:
            print(f"error: {p}", file=sys.stderr)
        sys.exit(1 if problems else 0)
    OUT.write_text(text)
    SWIFT.write_text(swift)
    INFOPLIST.write_text(infoplist_catalog())
    wrote = [OUT, SWIFT, INFOPLIST]
    if cat_text:
        CATALOG.write_text(cat_text)
        wrote.append(CATALOG)
    print("wrote " + ", ".join(str(p.relative_to(ROOT)) for p in wrote))
    for p in problems:
        print(p, file=sys.stderr)


if __name__ == "__main__":
    main()
