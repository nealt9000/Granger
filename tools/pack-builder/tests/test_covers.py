import io

from PIL import Image

import covers


def open_cover(data: bytes) -> Image.Image:
    return Image.open(io.BytesIO(data))


def test_cover_is_a_600x900_jpeg():
    image = open_cover(covers.cover_jpeg("Pride and Prejudice", "Jane Austen"))
    assert image.format == "JPEG" and image.size == (600, 900)


def test_long_title_fits():
    image = open_cover(covers.cover_jpeg("A Very Long Title " * 12, "An Author With A Rather Long Name Indeed"))
    assert image.size == (600, 900)


def test_cover_has_a_dark_ground_and_the_flame_mark_in_the_upper_third():
    image = open_cover(covers.cover_jpeg("Emma", "")).convert("RGB")
    assert sum(image.getpixel((5, 5))) < 60
    upper = image.crop((0, 0, 600, 300))
    raw = upper.tobytes()
    assert any(raw[i] > 180 and raw[i + 2] < 90 for i in range(0, len(raw), 3))


def test_anonymous_work_still_renders():
    assert open_cover(covers.cover_jpeg("Beowulf", "")).size == (600, 900)
