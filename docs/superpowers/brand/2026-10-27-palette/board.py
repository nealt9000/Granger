import sys
from PIL import Image, ImageDraw, ImageFont
S = sys.argv[1]; D = S + "/palette"
rows = [("Current", "0-current"), ("A  The owl", "A-owl"), ("B  The instrument", "B-instrument"), ("C  Enamel Night", "C-enamel-night")]
cols = ["home-field", "music-field", "welcome-field", "home-night", "music-night", "welcome-night"]
w = 300
first = Image.open(f"{D}/0-current/home-field.png"); h = int(first.height * w / first.width * 0.72)
lab = ImageFont.truetype(S + "/fonts2/CourierPrime-Regular.ttf.ttf", 30)
img = Image.new("RGB", (40 + len(cols) * (w + 16) + 40, 30 + len(rows) * (h + 70)), (42, 47, 48))
d = ImageDraw.Draw(img)
for r, (name, folder) in enumerate(rows):
    y = 30 + r * (h + 70)
    d.text((40, y + 34), name, font=lab, fill=(239, 228, 208), anchor="ls")
    for c, shot in enumerate(cols):
        im = Image.open(f"{D}/{folder}/{shot}.png").convert("RGB")
        im = im.resize((w, int(im.height * w / im.width)), Image.LANCZOS).crop((0, 0, w, h))
        x = 40 + c * (w + 16) + (30 if c >= 3 else 0)
        img.paste(im, (x, y + 50))
img.save(D + "/palette-board.png")
print(img.size)
