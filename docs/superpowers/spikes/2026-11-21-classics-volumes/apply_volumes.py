"""One-off (2026-11-21): writes the approved volumes into the Classics manifests, keeping their one-work-per-line layout.

  python3 apply_volumes.py tools/pack-builder/classics
"""
import json
import pathlib
import re
import sys

# The reviewed assignment (draft_volumes.py, 2026-11-21).
CHILD={"winnie-the-pooh","aesop-s-fables","black-beauty","adventures-of-pinocchio","bungalow-mystery","hidden-staircase","mystery-at-lilac-inn","secret-of-the-old-clock","secret-garden","fairy-tales","peter-pan","grimms-fairy-tales","heidi","wind-in-the-willows","wonderful-wizard-of-oz","anne-of-green-gables","alice-s-adventures-in-wonderland","through-the-looking-glass","little-women","adventures-of-tom-sawyer","prince-and-the-pauper","jungle-book","just-so-stories","treasure-island","arabian-nights-entertainments","christmas-carol"}
GENRE_AUTH={"Agatha Christie","Arthur Conan Doyle","Dashiell Hammett","Dorothy L. Sayers","G. K. Chesterton","John Buchan","Wilkie Collins","Edgar Allan Poe","Bram Stoker","Mary Wollstonecraft Shelley","H. G. Wells","Jules Verne","H. Rider Haggard","Anthony Hope","Baroness Orczy","Alexandre Dumas","Jack London"}
GENRE_SLUG={"strange-case-of-dr-jekyll-and-mr-hyde","turn-of-the-screw","kidnapped","legend-of-sleepy-hollow","herland","last-of-the-mohicans","ivanhoe","rob-roy"}
US={"Charlotte Perkins Gilman","Edith Wharton","Ernest Hemingway","F. Scott Fitzgerald","Harriet Beecher Stowe","Henry James","Herman Melville","Kate Chopin","Mark Twain","Nathaniel Hawthorne","Sinclair Lewis","Stephen Crane","Theodore Dreiser","Thornton Wilder","Upton Sinclair","Willa Cather","William Faulkner","Washington Irving","James Fenimore Cooper","Louisa May Alcott"}
BRIT={"Anne Brontë","Anthony Trollope","Charles Dickens","Charlotte Brontë","D. H. Lawrence","Daniel Defoe","E. M. Forster","Elizabeth Cleghorn Gaskell","Emily Brontë","Evelyn Waugh","George Eliot","Henry Fielding","J. B. Priestley","James Joyce","Jane Austen","Jerome K. Jerome","Jonathan Swift","Joseph Conrad","Laurence Sterne","Oscar Wilde","Robert Louis Stevenson","Rudyard Kipling","Samuel Butler","Thomas Hardy","Thomas Malory","Virginia Woolf","W. Somerset Maugham","Walter Scott","William Makepeace Thackeray"}

EN_VOLUMES = [
    ("scripture", "Scripture & religion", "Escritura y religión"),
    ("philosophy", "Philosophy", "Filosofía"),
    ("poetry", "Epic & poetry", "Épica y poesía"),
    ("drama", "Drama", "Teatro"),
    ("libretti", "Opera libretti", "Libretos de ópera"),
    ("children", "Children's classics", "Clásicos infantiles"),
    ("mystery-adventure", "Mystery, adventure & the fantastic", "Misterio, aventura y fantasía"),
    ("novels-britain", "Novels: Britain & Ireland", "Novelas: Gran Bretaña e Irlanda"),
    ("novels-america", "Novels: America", "Novelas: Estados Unidos"),
    ("novels-world", "Novels: Europe & the world", "Novelas: Europa y el mundo"),
]
ES_VOLUMES = [
    ("lecturas", "Lecturas fáciles e infantiles", "Easy and children's reading"),
    ("cuentos", "Cuentos y novelas cortas", "Short stories and novellas"),
    ("novelas", "Novelas", "Novels"),
    ("poesia", "Poesía", "Poetry"),
    ("teatro", "Teatro", "Drama"),
    ("universales", "Clásicos universales en español", "World classics in Spanish"),
    ("ensayo", "Ensayo, pensamiento y religión", "Essays, thought and religion"),
    ("historia", "Historia y crónicas", "History and chronicles"),
]
ES_MOVES = {"infantil": "lecturas", "escritura": "ensayo"}


def en_volume(w):
    if w["section"] != "novels":
        return None
    if w["slug"] in CHILD:
        return "children"
    if w["author"] in GENRE_AUTH or w["slug"] in GENRE_SLUG:
        return "mystery-adventure"
    if w["author"] in US:
        return "novels-america"
    if w["author"] in BRIT:
        return "novels-britain"
    return "novels-world"


def apply(path, volumes, other, volume_of):
    lines = path.read_text(encoding="utf-8").split("\n")
    out, in_works = [], False
    for line in lines:
        if line.startswith('  "works": ['):
            block = ",\n".join(f'    {{"id": "{vid}", "title": {json.dumps(t, ensure_ascii=False)}, '
                               f'"i18n": {{"{other}": {{"title": {json.dumps(o, ensure_ascii=False)}}}}}}}'
                               for vid, t, o in volumes)
            out += ['  "volumes": [', block, "  ],"]
            in_works = True
        elif in_works and line.startswith("    {"):
            w = json.loads(line.rstrip(","))
            vid = volume_of(w)
            if vid:
                line, n = re.subn(r'("section": "[a-z]+", )', rf'\1"volume": "{vid}", ', line, count=1)
                assert n == 1, w["slug"]
        out.append(line)
    path.write_text("\n".join(out), encoding="utf-8")


root = pathlib.Path(sys.argv[1])
apply(root / "manifest.json", EN_VOLUMES, "es", en_volume)
apply(root / "manifest_es.json", ES_VOLUMES, "en", lambda w: ES_MOVES.get(w["section"]))
