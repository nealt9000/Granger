"""Writes the iPhone app icon from the brand mark (Plan I2 builder's call 7: traced, not redrawn).

    python3 -I tools/ios/make_appicon.py OUT_DIR

Uses tools/brand/granger-mark.svg's three layers with the Android launcher's colours (make_drawables.py): the owl and
the G in field paper, the eyes in lantern, the pupils in night, on the launcher's enamel background (#2E4A45). The
mark's rim circle takes the share of the icon it takes of the Android launcher's visible 72 dp (58 dp). Writes
OUT_DIR/AppIcon.svg and, through macOS's QuickLook renderer, OUT_DIR/AppIcon-1024.png.
"""
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
ENAMEL, PAPER, LANTERN, NIGHT = "#2E4A45", "#EFE4D0", "#F0A35E", "#181C1D"
SIDE = 1024
SHARE = 58 / 72  # the rim circle's diameter over the launcher's visible area


def main(out: Path) -> None:
    svg = (ROOT / "tools/brand/granger-mark.svg").read_text()
    view = float(re.search(r'viewBox="0 0 ([\d.]+) ', svg).group(1))
    mark = re.findall(r'<path fill-rule="evenodd" d="([^"]+)"/>', svg)[0]
    eyes = re.search(r'<path class="eyes" fill-rule="evenodd" d="([^"]+)"/>', svg).group(1)
    pupils = re.search(r'<path class="pupils" fill-rule="evenodd" d="([^"]+)"/>', svg).group(1)
    scale = SIDE * SHARE / view
    offset = (SIDE - view * scale) / 2
    icon = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{SIDE}" height="{SIDE}" viewBox="0 0 {SIDE} {SIDE}">'
        f'<rect width="{SIDE}" height="{SIDE}" fill="{ENAMEL}"/>'
        f'<g transform="translate({offset:.2f} {offset:.2f}) scale({scale:.5f})">'
        f'<path fill="{LANTERN}" fill-rule="evenodd" d="{eyes}"/>'
        f'<path fill="{NIGHT}" fill-rule="evenodd" d="{pupils}"/>'
        f'<path fill="{PAPER}" fill-rule="evenodd" d="{mark}"/>'
        "</g></svg>"
    )
    out.mkdir(parents=True, exist_ok=True)
    (out / "AppIcon.svg").write_text(icon)
    subprocess.run(["qlmanage", "-t", "-s", str(SIDE), "-o", str(out), str(out / "AppIcon.svg")], check=True, capture_output=True)
    png = out / "AppIcon-1024.png"
    (out / "AppIcon.svg.png").rename(png)
    # The App Store refuses an icon with an alpha channel: flatten it through JPEG and back (lossless enough at 100%).
    jpg = out / "AppIcon-1024.jpg"
    subprocess.run(["sips", "-s", "format", "jpeg", "-s", "formatOptions", "100", str(png), "--out", str(jpg)], check=True, capture_output=True)
    subprocess.run(["sips", "-s", "format", "png", str(jpg), "--out", str(png)], check=True, capture_output=True)
    jpg.unlink()
    print(out / "AppIcon-1024.png")


if __name__ == "__main__":
    main(Path(sys.argv[1]))
