"""Screenshots of one chapter at 390x844 under Readium CSS, before and after the prototype fixes."""
import os, re, sys, subprocess, tempfile, zipfile, json, warnings
from bs4 import BeautifulSoup, XMLParsedAsHTMLWarning
warnings.filterwarnings('ignore', category=XMLParsedAsHTMLWarning)
HERE = os.path.dirname(os.path.abspath(__file__)); sys.path.insert(0, HERE)
import render, fixproto
from PIL import Image
OUT = os.path.join(HERE, 'shots'); os.makedirs(OUT, exist_ok=True)

def page(slug, ch, find, fix, scale=1.0, W=390, H=844):
    z = zipfile.ZipFile(f'{render.D}gc-{slug}_2026-10-17.epub')
    css = z.read('OEBPS/book.css').decode()
    if fix:
        l = css.split('\n'); css = '\n'.join(l[:10]) + '\n' + fixproto.scrub_extra('\n'.join(l[10:])) + fixproto.PRE_CSS + fixproto.VERSE_CSS
    soup = BeautifulSoup(z.read(f'OEBPS/{ch}'), 'lxml')
    if fix: fixproto.verse(soup)
    js = ("<script>window.addEventListener('load',()=>document.fonts.ready.then(()=>{const w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT);"
          f"let n;while(n=w.nextNode()){{if(n.textContent.includes({json.dumps(find)})){{const r=document.createRange();r.selectNode(n);"
          "window.scrollTo(0, r.getBoundingClientRect().top+window.scrollY-60);break;}}}}}));</script>")
    html = (f'<!DOCTYPE html><html lang="en" style="{render.root_style(scale)}">{render.head(css, scale)}'
            f'<body>{soup.body.decode_contents()}{js}</body></html>')
    name = f'{slug}-{ch[:-6]}-{"fixed" if fix else "now"}' + ('' if scale == 1.0 else f'-{int(scale*100)}')
    open(os.path.join(render.WWW, name + '.html'), 'w').write(html)
    open(os.path.join(render.WWW, name + '-o.html'), 'w').write(
        f'<!DOCTYPE html><html><body style="margin:0;background:#888"><iframe src="{name}.html" style="border:0;width:{W}px;height:{H}px;background:#fff"></iframe></body></html>')
    png = os.path.join(OUT, name + '.png')
    with tempfile.TemporaryDirectory() as tmp:
        subprocess.run([render.CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', f'--user-data-dir={tmp}/u',
                        f'--window-size=500,{H}', '--virtual-time-budget=20000', f'--screenshot={tmp}/s.png',
                        f'http://127.0.0.1:{render.PORT}/{name}-o.html'], capture_output=True, timeout=300)
        Image.open(f'{tmp}/s.png').crop((0, 0, W, H)).save(png)
    return png

if __name__ == '__main__':
    for spec in sys.argv[1:]:
        slug, ch, find = spec.split('|')[:3]
        scale = float(spec.split('|')[3]) if spec.count('|') >= 3 else 1.0
        for fix in (False, True):
            print(page(slug, ch, find, fix, scale))
