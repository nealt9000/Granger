"""Descendant-selector rules whose last part matches elements but whose ancestor part never does (wrapper lost in split)."""
import zipfile,re,glob,os,json,warnings,collections
from bs4 import BeautifulSoup, XMLParsedAsHTMLWarning
warnings.filterwarnings('ignore', category=XMLParsedAsHTMLWarning)
D='/Users/s.nealthompson/Developer/Granger/Granger/tools/eval/runs/r0-books/books/granger/'
SEC={w['slug']:w['section'] for w in json.load(open('/Users/s.nealthompson/Developer/Granger/Granger/tools/pack-builder/classics/manifest.json'))['works']}
out={}
for f in sorted(glob.glob(D+'*.epub')):
    slug=os.path.basename(f)[3:-16]
    z=zipfile.ZipFile(f)
    css='\n'.join(z.read('OEBPS/book.css').decode().splitlines()[10:])
    sels=set()
    for sel,body in re.findall(r'([^{}]+)\{([^{}]*)\}',css):
        for s in sel.split(','):
            s=s.strip()
            if ' ' in s.replace(' > ','>') and ':' not in s and 'pagedjs' not in s and 'xhtml_center' not in s: sels.add(s)
    if not sels: continue
    full=collections.Counter(); last=collections.Counter()
    for n in z.namelist():
        if not re.match(r'OEBPS/c\d+\.xhtml$',n): continue
        b=BeautifulSoup(z.read(n),'lxml').body
        for s in sels:
            try:
                full[s]+=len(b.select(s)); last[s]+=len(b.select(re.split(r'\s+|>',s.strip())[-1]))
            except Exception: pass
    dead={s:(full[s],last[s]) for s in sels if last[s]>0 and full[s] < last[s]*0.5 and not re.match(r'^(body|html)\b',s)}
    if dead: out[slug]=dead
for k,v in out.items():
    print(SEC[k][:5],k, {s:v[s] for s in sorted(v,key=lambda s:-v[s][1])[:5]})
json.dump(out,open('deadrules.json','w'),indent=1)
