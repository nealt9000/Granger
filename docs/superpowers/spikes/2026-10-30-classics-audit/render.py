"""Renders each book's verse in headless Chrome under Readium CSS and measures line wrapping.

Usage: .venv/bin/python -I render.py WIDTH FONTSCALE [slug ...]  -> render-<W>-<scale>.json
Harness: ReadiumCSS-before + the book's book.css + ReadiumCSS-after, root style as the Kotlin toolkit writes it
with publisherStyles=false (readium-advanced-on), textAlign start, Georgia standing in for Android's Noto Serif,
scroll mode so the measuring isn't confused by columns.
"""
import concurrent.futures
import warnings
from bs4 import XMLParsedAsHTMLWarning
warnings.filterwarnings('ignore', category=XMLParsedAsHTMLWarning)
import glob
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile

from bs4 import BeautifulSoup, NavigableString, Tag

HERE = os.path.dirname(os.path.abspath(__file__))
D = '/Users/s.nealthompson/Developer/Granger/Granger/tools/eval/runs/r0-books/books/granger/'
MAN = '/Users/s.nealthompson/Developer/Granger/Granger/tools/pack-builder/classics/manifest.json'
RCSS = os.path.join(HERE, 'aar/assets/readium/readium-css/')
CHROME = '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'
SEC = {w['slug']: w['section'] for w in json.load(open(MAN))['works']}
BLOCKS = {'p', 'div', 'table', 'ul', 'ol', 'dl', 'blockquote', 'pre', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6', 'hr'}


def root_style(scale, extra='', width=390):
    return (''
            '--USER__view: readium-scroll-on; --USER__advancedSettings: readium-advanced-on; '
            '--USER__fontOverride: readium-font-on; --USER__fontFamily: GNotoSerif, serif; --USER__textAlign: start; '
            f'--USER__lineHeight: 1.5; --USER__pageMargins: 1; --USER__fontSize: {int(scale * 100)}%;' + extra)


def head(book_css, scale, extra_css=''):
    before = open(RCSS + 'ReadiumCSS-before.css').read()
    after = open(RCSS + 'ReadiumCSS-after.css').read()
    return (f'<head><meta charset="utf-8"/><meta name="viewport" content="width=device-width, initial-scale=1"/>'
            f'<link rel="stylesheet" href="notoserif.css"/><style>{before}</style><style>{book_css}\n{extra_css}</style><style>{after}</style></head>')


def is_verse_br_block(p):
    brs = p.find_all('br', recursive=False)
    if len(brs) < 2 or p.find(BLOCKS - {'p'}) is not None:
        return False
    segs, cur = [], []
    for c in p.children:
        if isinstance(c, Tag) and c.name == 'br':
            segs.append(''.join(cur)); cur = []
        else:
            cur.append(c.get_text() if isinstance(c, Tag) else str(c))
    segs.append(''.join(cur))
    vis = [' '.join(s.split()) for s in segs if s.strip()]
    return bool(vis) and sum(map(len, vis)) / len(vis) <= 90


def mark(soup):
    n = 0
    for sp in soup.select('span.gv-l'):
        sp['data-vl'] = '1'
        sp.parent['data-vb'] = 'gv'
        n += 1
    for p in soup.find_all(['p', 'div', 'td', 'blockquote', 'span']):
        if not is_verse_br_block(p):
            continue
        p['data-vb'] = 'br'
        groups, cur = [], []
        for c in list(p.children):
            if isinstance(c, Tag) and c.name == 'br':
                groups.append((cur, c)); cur = []
            else:
                cur.append(c)
        groups.append((cur, None))
        for nodes, br in groups:
            if not any((n_.get_text() if isinstance(n_, Tag) else str(n_)).strip() for n_ in nodes):
                continue
            span = soup.new_tag('span', attrs={'data-vl': '1'})
            nodes[0].insert_before(span)
            for n_ in nodes:
                span.append(n_.extract())
            n += 1
    sel = ('div.stanza > p, div.stanza > div, div.stanza > span, div.poem > p, div.poem > div, div.poetry > p, '
           'div.poetry > div, div.verse > p, div.verse > div, div.tei-l, div.line, p.line, p.verse')
    for t in soup.select(sel):
        if t.find(BLOCKS) is not None or t.has_attr('data-vb') or t.find(attrs={'data-vl': True}):
            continue
        tx = ' '.join(t.get_text().split())
        if not tx or len(tx) > 120:
            continue
        span = soup.new_tag('span', attrs={'data-vl': '1'})
        for c in list(t.children):
            span.append(c.extract())
        t.append(span)
        t['data-vb'] = 'el'
        n += 1
    # span-per-line verse (DP style: div.stanza > span.i2 + br) is handled by the br rule when the stanza holds brs
    for pre in soup.find_all('pre'):
        pre['data-vb'] = 'pre'
        pre['data-n'] = str(sum(1 for l in pre.get_text().split('\n') if l.strip()))
    return n


JS = r"""
<script>
window.addEventListener('load',()=>document.fonts.ready.then(function(){
  const W = document.documentElement.getBoundingClientRect().width;
  const body = document.body.getBoundingClientRect();
  const bs = getComputedStyle(document.body);
  const colL = body.left + parseFloat(bs.paddingLeft), colR = body.right - parseFloat(bs.paddingRight);
  const col = colR - colL;
  const res = {W, col, lines:0, wrapped:0, wrapped_hang:0, wrapped_flush:0, rows:0, narrow:0, offscreen:0,
               leftpct:[], avail:[], pre_blocks:0, pre_src:0, pre_rows:0, pre_offscreen:0, justify:0, mono:0,
               examples:[]};
  function rowsOf(rects){
    const tops=[]; for(const r of rects){ if(r.width<1||r.height<1) continue;
      if(!tops.some(t=>Math.abs(t.top-r.top)<r.height*0.5)) tops.push({top:r.top,left:r.left,right:r.right});
      else { const t=tops.find(t=>Math.abs(t.top-r.top)<r.height*0.5); t.left=Math.min(t.left,r.left); t.right=Math.max(t.right,r.right);} }
    tops.sort((a,b)=>a.top-b.top); return tops;
  }
  document.querySelectorAll('[data-vl]').forEach(s=>{
    const rg=document.createRange(); rg.selectNodeContents(s); const rows = rowsOf([...rg.getClientRects()].filter(r=>r.width<W));
    if(!rows.length) return;
    res.lines++; res.rows += rows.length;
    const blk = s.closest('[data-vb]') || s.parentElement;
    const cs = getComputedStyle(blk);
    const br = blk.getBoundingClientRect();
    const avail = br.width - parseFloat(cs.paddingLeft) - parseFloat(cs.paddingRight);
    res.avail.push(Math.round(avail));
    const lp = (rows[0].left - colL)/col; res.leftpct.push(Math.round(lp*100));
    if (avail < col*0.7) res.narrow++;
    if (rows.some(r=>r.right > W+1)) res.offscreen++;
    if (cs.textAlign==='justify') res.justify++;
    if (/mono/i.test(cs.fontFamily)) res.mono++;
    if(rows.length>1){
      res.wrapped++;
      if (rows[1].left > rows[0].left + 8 || rows[1].left > (br.left+parseFloat(cs.paddingLeft)) + 8) res.wrapped_hang++; else res.wrapped_flush++;
      if(res.examples.length<3) res.examples.push(s.textContent.trim().replace(/\s+/g,' ').slice(0,90)+' | avail='+Math.round(avail)+'px rows='+rows.length);
    }
  });
  document.querySelectorAll('pre[data-vb]').forEach(p=>{
    const range=document.createRange(); range.selectNodeContents(p);
    const rows=rowsOf(range.getClientRects());
    res.pre_blocks++; res.pre_src += +p.dataset.n; res.pre_rows += rows.length;
    if (rows.some(r=>r.right > W+1)) res.pre_offscreen++;
    if (/mono/i.test(getComputedStyle(p).fontFamily)) res.mono++;
  });
  const q = a => { if(!a.length) return null; const b=[...a].sort((x,y)=>x-y); return {min:b[0], p10:b[Math.floor(b.length*.1)], med:b[Math.floor(b.length/2)]}; };
  res.avail = q(res.avail); res.leftpct = q(res.leftpct);
  { const ps=[...document.querySelectorAll('p')]; const after=document.styleSheets[document.styleSheets.length-1];
    const now=ps.map(p=>getComputedStyle(p).textAlign); after.disabled=true;
    const want=ps.map(p=>getComputedStyle(p).textAlign); after.disabled=false;
    res.align_lost=0; res.align_lost_cls={};
    ps.forEach((p,i)=>{ if(/center|right|end/.test(want[i]) && !/center|right|end/.test(now[i])) { res.align_lost++;
      const k='p.'+[...p.classList].join('.')+':'+want[i]+(p.getAttribute('style')&&/text-align/.test(p.getAttribute('style'))?' (inline)':''); res.align_lost_cls[k]=(res.align_lost_cls[k]||0)+1; } }); }
  res.docW = document.documentElement.scrollWidth;
  res.overflow = {};
  if (res.docW > W + 1) { document.querySelectorAll('body *').forEach(e=>{ const r=e.getBoundingClientRect();
    if (r.right > W + 1 && r.width > 0 && ![...e.children].some(c=>c.getBoundingClientRect().right > W+1)) {
      const cs=getComputedStyle(e); const k=e.tagName.toLowerCase()+'.'+[...e.classList].join('.')+' pos='+cs.position+' w='+Math.round(r.width)+' '+(e.getAttribute('style')||'');
      res.overflow[k]=(res.overflow[k]||0)+1; } }); }
  const o=parent.document.createElement('script'); o.type='application/json'; o.id='audit-out'; o.textContent=JSON.stringify(res);
  parent.document.body.appendChild(o);
}));
</script>"""


def build(slug, width, scale, extra_css='', root_extra=''):
    z = zipfile.ZipFile(f'{D}gc-{slug}_2026-10-17.epub')
    css = z.read('OEBPS/book.css').decode()
    if FIX:
        lines = css.split('\n')
        css = '\n'.join(lines[:10]) + '\n' + fixproto.scrub_extra('\n'.join(lines[10:])) + fixproto.PRE_CSS + fixproto.VERSE_CSS
    chapters = sorted(n for n in z.namelist() if re.match(r'OEBPS/c\d+\.xhtml$', n))
    parts = []
    for ch in chapters:
        soup = BeautifulSoup(z.read(ch), 'lxml')
        if FIX:
            fixproto.verse(soup)
        mark(soup)
        parts.append(soup.body.decode_contents())
    html = (f'<!DOCTYPE html><html lang="en" style="{root_style(scale, root_extra, width)}">{head(css, scale, extra_css)}'
            f'<body>{"".join(parts)}{JS}</body></html>')
    return html


WWW = os.path.join(HERE, 'www')
FIX = os.environ.get('FIX') == '1'
sys.path.insert(0, HERE)
import fixproto
PORT = 8765


def run(slug, width, scale, extra_css='', root_extra='', tag=''):
    html = build(slug, width, scale, extra_css, root_extra)
    name = f'{slug}-{width}-{scale}{tag}'
    open(os.path.join(WWW, name + '.html'), 'w').write(html)
    open(os.path.join(WWW, name + '-outer.html'), 'w').write(
        f'<!DOCTYPE html><html><body style="margin:0"><iframe src="{name}.html" style="border:0;width:{width}px;height:900px"></iframe></body></html>')
    with tempfile.TemporaryDirectory() as tmp:
        out = subprocess.run([CHROME, '--headless=new', '--disable-gpu', '--hide-scrollbars', f'--user-data-dir={tmp}/u',
                              '--window-size=500,900', '--virtual-time-budget=120000', '--dump-dom',
                              f'http://127.0.0.1:{PORT}/{name}-outer.html'],
                             capture_output=True, text=True, timeout=1200)
    m = re.search(r'<script type="application/json" id="audit-out">(.*?)</script>', out.stdout, re.S)
    if not m:
        return slug, {'error': out.stderr[-400:]}
    import html as h
    return slug, json.loads(h.unescape(m.group(1)))


def main():
    width, scale = int(sys.argv[1]), float(sys.argv[2])
    extra_css = ''
    args = sys.argv[3:]
    tag = ''
    if args and args[0].startswith('--fix='):
        extra_css = open(args[0][6:]).read(); tag = '-fixed'; args = args[1:]
    tag += '-subset' if args else ''
    tag += '-fix' if FIX else ''
    slugs = args or [os.path.basename(f)[3:-16] for f in sorted(glob.glob(D + '*.epub'))
                     if SEC[os.path.basename(f)[3:-16]] != 'novels']
    res = {}
    with concurrent.futures.ThreadPoolExecutor(6) as ex:
        for slug, r in ex.map(lambda s: run(s, width, scale, extra_css, tag=tag), slugs):
            res[slug] = r
            print(slug, {k: r.get(k) for k in ('lines', 'wrapped', 'wrapped_hang', 'narrow', 'offscreen', 'pre_src', 'pre_rows', 'docW', 'error')}, file=sys.stderr)
    json.dump(res, open(os.path.join(HERE, f'render-{width}-{scale}{tag}.json'), 'w'), indent=1)


if __name__ == '__main__':
    main()
