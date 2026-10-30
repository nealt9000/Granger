import json,sys
R1=json.load(open('render-390-1.0.json')); R2=json.load(open('render-390-1.25.json')); R3=json.load(open('render-360-1.0.json'))
A={b['slug']:b for b in json.load(open('audit.json'))}
rows=[]
for s,r in R1.items():
    r2=R2[s]; r3=R3[s]; a=A[s]
    L=r['lines']; P=r['pre_src']
    if L+P<50: continue
    w=r['wrapped']/L*100 if L else 0; w2=r2['wrapped']/L*100 if L else 0; w3=r3['wrapped']/L*100 if L else 0
    pe=(r['pre_rows']/P) if P else 0; pe2=(r2['pre_rows']/P) if P else 0
    rows.append((a['section'][:5],s[:36],L,round(w),round(w3),round(w2),r['wrapped_hang'],r['narrow'],r['offscreen'],(r['leftpct'] or {}).get('med'),(r['avail'] or {}).get('med'),P,round(pe,2),round(pe2,2),r['mono'],r['docW']))
rows.sort(key=lambda x:(-max(x[3],(x[12]-1)*100)))
print('sec   slug                                 lines wr390 wr360 wr390@125 hang narrow offscr left%med availmed | pre_src pre_rows/src@100 @125 mono docW')
for x in rows: print('%-5s %-36s %6d %5d %5d %5d %5d %6d %5d %5s %5s | %6d %5.2f %5.2f %5d %4d'%x)
