"""Palette options rendered as the app's real screens: patch GrangerTheme.kt per option, run ThemeShots, collect, restore.
Usage: variants.py <worktree> <outdir>"""
import json, re, shutil, subprocess, sys, os
W, OUT = sys.argv[1], sys.argv[2]
P = f"{W}/app/src/main/kotlin/app/granger/theme/GrangerTheme.kt"
ORIG = open(P).read()

V = {
 "A-owl": {
  "Field": dict(background="F3EEE4", surface="F3EEE4", surfaceVariant="E1D9C9", onSurfaceVariant="4A453F",
    surfaceContainerLowest="FAF7F1", surfaceContainerLow="F0EADF", surfaceContainer="E8E1D3", surfaceContainerHigh="E1D9C9",
    surfaceContainerHighest="D9D0BE", surfaceDim="D9D0BE", surfaceBright="FAF7F1", outline="77706A", outlineVariant="CFC6B4",
    primaryContainer="D3E2DE", secondary="3A4243", secondaryContainer="E2E4E1", onSecondaryContainer="1A1F20",
    tertiary="9A5B22", tertiaryContainer="F6DEC6", onTertiaryContainer="3A1F08"),
  "FieldExtras": dict(play="F0A35E"),
  "Night": dict(background="141A19", surface="141A19", surfaceVariant="263231", onSurfaceVariant="B5BBB4",
    surfaceContainerLowest="0F1413", surfaceContainerLow="19201F", surfaceContainer="1F2927", surfaceContainerHigh="263231",
    surfaceContainerHighest="2E3B39", surfaceDim="0F1413", surfaceBright="34423F", outline="7D8A87", outlineVariant="34413F",
    primary="8DBDB2", onPrimary="0E2420", primaryContainer="2E4A45", onPrimaryContainer="D6EDE7",
    secondary="F0A35E", onSecondary="1F1813", secondaryContainer="5A3A1C", onSecondaryContainer="FFDDB8",
    tertiary="D8CBB4", surfaceTint="8DBDB2"),
  "NightExtras": dict(play="F0A35E"),
 },
 "B-instrument": {
  "Field": dict(background="EDEBE6", surface="EDEBE6", surfaceVariant="E1DED7", onSurfaceVariant="45474A",
    surfaceContainerLowest="F7F6F3", surfaceContainerLow="E8E6E0", surfaceContainer="E1DED7", surfaceContainerHigh="D9D6CE",
    surfaceContainerHighest="D1CDC4", surfaceDim="D1CDC4", surfaceBright="F7F6F3", outline="75777A", outlineVariant="CAC7C0",
    onBackground="1C1E1F", onSurface="1C1E1F", primaryContainer="D3E2DE",
    secondary="2B2F31", secondaryContainer="DEE0E0", onSecondaryContainer="15181A",
    tertiary="9A5B22", tertiaryContainer="F6DEC6", onTertiaryContainer="3A1F08"),
  "FieldExtras": dict(play="F0A35E"),
  "Night": dict(background="151718", surface="151718", surfaceVariant="2A2E30", onSurfaceVariant="B4B6B5",
    onBackground="ECEAE4", onSurface="ECEAE4",
    surfaceContainerLowest="101213", surfaceContainerLow="1B1E1F", surfaceContainer="212526", surfaceContainerHigh="2A2E30",
    surfaceContainerHighest="33383A", surfaceDim="101213", surfaceBright="3A3F41", outline="808585", outlineVariant="3A3F41",
    primary="9EC3BA", onPrimary="0E2420", primaryContainer="2E4A45", onPrimaryContainer="D6EDE7",
    secondary="F0A35E", onSecondary="1F1813", secondaryContainer="5A3A1C", onSecondaryContainer="FFDDB8",
    tertiary="C9C6BE", surfaceTint="9EC3BA"),
  "NightExtras": dict(play="F0A35E"),
 },
 "C-enamel-night": {
  "Field": dict(background="F6F3EC", surface="F6F3EC", surfaceVariant="E6E0D3", onSurfaceVariant="474239",
    surfaceContainerLowest="FCFAF6", surfaceContainerLow="F0ECE3", surfaceContainer="EAE4D8", surfaceContainerHigh="E6E0D3",
    surfaceContainerHighest="DED7C8", surfaceDim="DED7C8", surfaceBright="FCFAF6", outline="77706A", outlineVariant="D3CCBD",
    secondary="3A4243", secondaryContainer="E2E4E1", onSecondaryContainer="1A1F20",
    tertiary="9A5B22", tertiaryContainer="F6DEC6", onTertiaryContainer="3A1F08"),
  "FieldExtras": dict(play="F0A35E"),
  "Night": dict(background="12211E", surface="12211E", surfaceVariant="233B36", onSurfaceVariant="B8C4BF",
    surfaceContainerLowest="0D1917", surfaceContainerLow="172A26", surfaceContainer="1C322E", surfaceContainerHigh="233B36",
    surfaceContainerHighest="2B4540", surfaceDim="0D1917", surfaceBright="34504A", outline="7F948F", outlineVariant="2F4A44",
    secondaryContainer="2B4540"),
  "NightExtras": dict(),
 },
}

def block(src, start, end):
    i = src.index(start); j = src.index(end, i)
    return i, j

def patch(src, v):
    for name, start, end in (("Field", "val FieldColors", "\n)\n"), ("Night", "val NightColors", "\n)\n")):
        i, j = block(src, start, end)
        b = src[i:j]
        for k, hexv in v.get(name, {}).items():
            b, n = re.subn(rf"(\n    {k} = )[^,\n]+,", rf"\g<1>Color(0xFF{hexv}),", b)
            if n == 0:  # a slot the scheme leaves to Material's default
                b = b + f"\n    {k} = Color(0xFF{hexv}),"
        src = src[:i] + b + src[j:]
    for name, start in (("FieldExtras", "val FieldExtras"), ("NightExtras", "val NightExtras")):
        i = src.index(start); j = src.index("\n", src.index("markPupils", i))
        b = src[i:j]
        for k, hexv in v.get(name, {}).items():
            b = re.sub(rf"{k} = [^,)]+", f"{k} = Color(0xFF{hexv})", b)
        src = src[:i] + b + src[j:]
    return src

env = dict(os.environ, JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home",
           ANDROID_HOME="/opt/homebrew/share/android-commandlinetools")
try:
    for name, v in [("0-current", {})] + list(V.items()):
        open(P, "w").write(patch(ORIG, v))
        shots = f"{W}/app/build/theme-shots"
        shutil.rmtree(shots, ignore_errors=True)
        r = subprocess.run(["./gradlew", "-q", ":app:testDebugUnitTest", "--tests", "*ThemeShots*", "-PthemeShots", "--rerun-tasks"],
                           cwd=W, env=env, capture_output=True, text=True)
        if r.returncode: print(name, r.stdout[-2000:], r.stderr[-2000:]); sys.exit(1)
        shutil.copytree(shots, f"{OUT}/{name}", dirs_exist_ok=True)
        print("done", name)
finally:
    open(P, "w").write(ORIG)
