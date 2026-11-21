import json,collections,sys
G="/Volumes/X10 Pro/Developer/Granger/Granger/"
cat=json.load(open(G+"catalog/catalog.json")); byid={b['id']:b for b in cat['books']}
def w(b): return b['sizeBytes']+(b.get('vectors') or {}).get('sizeBytes',0)+sum(s['sizeBytes'] for s in b.get('sidecars',[]))
CHILD={"winnie-the-pooh","aesop-s-fables","black-beauty","adventures-of-pinocchio","bungalow-mystery","hidden-staircase","mystery-at-lilac-inn","secret-of-the-old-clock","secret-garden","fairy-tales","peter-pan","grimms-fairy-tales","heidi","wind-in-the-willows","wonderful-wizard-of-oz","anne-of-green-gables","alice-s-adventures-in-wonderland","through-the-looking-glass","little-women","adventures-of-tom-sawyer","prince-and-the-pauper","jungle-book","just-so-stories","treasure-island","arabian-nights-entertainments","christmas-carol"}
GENRE_AUTH={"Agatha Christie","Arthur Conan Doyle","Dashiell Hammett","Dorothy L. Sayers","G. K. Chesterton","John Buchan","Wilkie Collins","Edgar Allan Poe","Bram Stoker","Mary Wollstonecraft Shelley","H. G. Wells","Jules Verne","H. Rider Haggard","Anthony Hope","Baroness Orczy","Alexandre Dumas","Jack London"}
GENRE_SLUG={"strange-case-of-dr-jekyll-and-mr-hyde","turn-of-the-screw","kidnapped","legend-of-sleepy-hollow","herland","last-of-the-mohicans","ivanhoe","rob-roy"}
US={"Charlotte Perkins Gilman","Edith Wharton","Ernest Hemingway","F. Scott Fitzgerald","Harriet Beecher Stowe","Henry James","Herman Melville","Kate Chopin","Mark Twain","Nathaniel Hawthorne","Sinclair Lewis","Stephen Crane","Theodore Dreiser","Thornton Wilder","Upton Sinclair","Willa Cather","William Faulkner","Washington Irving","James Fenimore Cooper","Louisa May Alcott"}
BRIT={"Anne Brontë","Anthony Trollope","Charles Dickens","Charlotte Brontë","D. H. Lawrence","Daniel Defoe","E. M. Forster","Elizabeth Cleghorn Gaskell","Emily Brontë","Evelyn Waugh","George Eliot","Henry Fielding","J. B. Priestley","James Joyce","Jane Austen","Jerome K. Jerome","Jonathan Swift","Joseph Conrad","Laurence Sterne","Oscar Wilde","Robert Louis Stevenson","Rudyard Kipling","Samuel Butler","Thomas Hardy","Thomas Malory","Virginia Woolf","W. Somerset Maugham","Walter Scott","William Makepeace Thackeray"}
EN={"scripture":"Scripture & religion","philosophy":"Philosophy","poetry":"Epic & poetry","drama":"Drama & opera","libretti":"Drama & opera"}
ES={"lecturas":"Lecturas fáciles e infantiles","infantil":"Lecturas fáciles e infantiles","cuentos":"Cuentos y novelas cortas","novelas":"Novelas","poesia":"Poesía","teatro":"Teatro","universales":"Clásicos universales en español","ensayo":"Ensayo, pensamiento y religión","escritura":"Ensayo, pensamiento y religión","historia":"Historia y crónicas de Indias"}
def vol_en(x):
    if x['section']!='novels': return EN[x['section']]
    if x['slug'] in CHILD: return "Children's classics"
    if x['author'] in GENRE_AUTH or x['slug'] in GENRE_SLUG: return "Mystery, adventure & the fantastic"
    if x['author'] in US: return "Novels: America"
    if x['author'] in BRIT: return "Novels: Britain & Ireland"
    return "Novels: Europe & the world"
out={}
for f,pre,fn in [("manifest.json","gc-",vol_en),("manifest_es.json","gc-es-",lambda x:ES[x['section']])]:
    m=json.load(open(G+"tools/pack-builder/classics/"+f)); agg=collections.OrderedDict(); members=collections.defaultdict(list)
    for x in m['works']:
        v=fn(x); bid=[k for k in (pre+x['slug'],) if k in byid]
        b=byid[bid[0]] if bid else None
        a=agg.setdefault(v,[0,0]); a[0]+=1; a[1]+=w(b) if b else 0
        members[v].append(f"{x['author'] or '—'} · {x['title']}")
    print("==",m['title'])
    for v,(n,s) in agg.items(): print(f"  {v:40} {n:4} books {s/1e6:6.1f} MB")
    out[m['title']]={v:members[v] for v in agg}
json.dump(out,open("volumes-draft.json","w"),ensure_ascii=False,indent=1)
for v in ["Novels: Europe & the world","Children's classics"]: print(v, out['Granger Classics'][v])
