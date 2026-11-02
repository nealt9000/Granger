import json,sys,collections
A=json.load(open('audit.json'))
secs=sys.argv[1].split(',') if len(sys.argv)>1 else ['poetry','drama','libretti','scripture']
K=['verse_br_lines','collapsed_space_indent_lines','collapsed_internal_space_runs','nbsp_indent_lines','inline_line_numbers','double_br','pre_blocks','pre_lines','pre_lines_over_40col','pre_lines_indent_ge10','line_elements','div_stanza','p_poem','p_drama','linenum_elements','tei_l','div_line','tables_with_number_cells','illustration_text','transcriber_note','empty_p','nbsp_only_blocks','heading_caps_period','contents_heading','inbook_toc_blocks','dropcap','inline_big_margin','inline_width']
for b in A:
    if b['section'] not in secs: continue
    c=b['counts']; ll=b['line_len']
    print(f"{b['section'][:5]} {b['slug'][:34]:34s} ch{b['chapters']:3d}", ' '.join(f"{k.replace('verse_','').replace('_lines','L')[:14]}={c[k]}" for k in K if c.get(k)), '| len', ll.get('median'),ll.get('p90'),'o40',ll.get('over40'),'/',ll.get('n'))
