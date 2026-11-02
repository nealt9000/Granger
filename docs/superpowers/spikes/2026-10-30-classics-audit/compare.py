import json
SEC={w['slug']:w['section'] for w in json.load(open('/Users/s.nealthompson/Developer/Granger/Granger/tools/pack-builder/classics/manifest.json'))['works']}
def L(f): return json.load(open(f))
B={k:L(f'render-{k}.json') for k in ['390-1.0','360-1.0','390-1.25']}
F={k:L(f'render-{k}-fix.json') for k in B}
def stats(r):
    # verse rows: wrapped flush lines (bad), plus pre
    lines=r['lines']+r['pre_src']; flush=r['wrapped']-r['wrapped_hang']
    pre_extra=r['pre_rows']-r['pre_src']  # all pre continuation rows are flush and monospace
    return lines, r['wrapped'], flush+max(0,pre_extra), r.get('align_lost',0), r['mono']
print('%-5s %-36s %6s | %-24s | %-24s | %s'%('sec','slug','lines','390@100 wrap/flush now->fix','360@100','390@125  alignlost now->fix'))
tot={k:[0,0,0,0] for k in B}; T=0
rows=[]
for s in B['390-1.0']:
    if SEC[s] not in ('poetry','drama','libretti','scripture'): continue
    out=[]
    for k in B:
        l,w,fl,al,m=stats(B[k][s]); l2,w2,fl2,al2,m2=stats(F[k][s])
        out.append((l,w,fl,al,w2,fl2,al2,l2))
        tot[k][0]+=fl; tot[k][1]+=fl2; tot[k][2]+=max(l,1) if l else 0; tot[k][3]+=w
    if out[0][0]<30 and out[0][3]==0: continue
    rows.append((s,out))
rows.sort(key=lambda x:-x[1][0][2]/max(x[1][0][0],1))
for s,o in rows:
    print('%-5s %-36s %6d | '%(SEC[s][:5],s[:36],o[0][0]) + ' | '.join(f'{x[2]:5d}->{x[5]:<4d} ({100*x[2]//max(x[0],1):3d}%)' for x in o) + f' | align {o[0][3]}->{o[0][6]}')
for k,v in tot.items(): print(k,'flush continuation rows now',v[0],'fixed',v[1],'source lines',v[2])
