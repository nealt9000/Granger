import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from zim_articles import is_namespace_page  # noqa: E402


def test_namespace_pages_are_left_out_and_real_titles_kept():
    for p in ["Category:1854_ballets", "Portal:Opera/Selected_article/26", "Template:Infobox", "Categoría:Novelas",
              "Plantilla:Ficha"]:
        assert is_namespace_page(p), p
    for p in ["Star_Wars:_Episode_IV_–_A_New_Hope", "Portal:_Still_Alive", "Anexo:Premios_Nobel", "London",
              "Category_theory", "Re:Zero"]:
        assert not is_namespace_page(p), p
