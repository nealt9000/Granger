import sys,re,json,html
sys.path.insert(0,"/Users/s.nealthompson/Developer/Granger/Granger/tools/catalog-gen")
import zim_probe as zp
for name in sys.argv[1:]:
    rec=[json.loads(l) for l in open('cands.jsonl') if json.loads(l)['name']==name][0]
    docs={d['path'] for d in json.load(open(f"docs/{name}__x.json"))['docs']}
    r=zp.Reader(rec['url'],budget=128<<20); z=zp.Zim(r)
    titles=json.load(open('/dev/null')) if False else {}
    arts=zp.titles(rec['url'])['articles'][:200]
    for a in arts:
        e=z.find(a)
        if not e or 'cluster' not in e: continue
        s=z.content(e).decode('utf-8','replace')
        base=a.rsplit('/',1)[0]
        for href,t in re.findall(r'<a[^>]+href="([^"#]+\.pdf)"[^>]*>(.*?)</a>',s,re.S|re.I):
            t=html.unescape(re.sub(r'\s+',' ',re.sub(r'<[^>]+>',' ',t))).strip()
            if t and len(t)>3: titles.setdefault(href.split('/')[-1],t)
    hit=sum(1 for d in docs if d.split('/')[-1] in titles)
    print(f"{name}: {len(docs)} docs, link text for {hit}; e.g.", list(titles.items())[:6])
    r.close()
