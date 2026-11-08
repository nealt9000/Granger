import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import push  # noqa: E402


def test_the_zim_name_drops_the_date_and_flavour():
    assert push.zim_name("wikipedia_en_top_maxi_2026-09-14") == "wikipedia_en_top"
    assert push.zim_name("wikipedia_en_medicine_nopic_2026-10-10") == "wikipedia_en_medicine"
    assert push.zim_name("wikipedia_en_geography_maxi_2026-10") == "wikipedia_en_geography"
