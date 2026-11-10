"""Enumerate directional reverse-endpoint candidates within a network rectangle.

No physical-turn inference is made here. Pass the TSV to the source chain auditor.
"""
import argparse,csv,gzip,math
from collections import defaultdict
from pathlib import Path
from lxml import etree
FIELDS=('carrier','tariff','fromRaw','toRaw','fromNode','toNode','fromLength','toLength','fromWay','toWay','fromX','fromY','toX','toY','witnessOrigin','witnessDestination','rowCount')
def transitions(path,bounds):
 x0,y0,x1,y1=bounds
 if not all(map(math.isfinite,bounds)) or x0>x1 or y0>y1: raise ValueError('Invalid bounds')
 nodes={}; pairs=defaultdict(list)
 with (gzip.open if str(path).endswith('.gz') else open)(path,'rb') as stream:
  for _,el in etree.iterparse(stream,events=('end',),tag=('node','link')):
   if el.tag=='node':
    x,y=float(el.get('x')),float(el.get('y'))
    if x0<=x<=x1 and y0<=y<=y1: nodes[el.get('id')]=(x,y)
   else:
    a,b=el.get('from'),el.get('to')
    if a in nodes and b in nodes and a!=b:
     attrs={v.get('name'):v.text for v in el.iter('attribute')};length=float(el.get('length'))
     if math.isfinite(length) and length>0: pairs[a,b].append((el.get('id'),length,attrs.get('osm:way:id') or ''))
   el.clear()
   while el.getprevious() is not None: del el.getparent()[0]
 rows=[]
 for (a,b),incoming in sorted(pairs.items()):
  for id1,l1,w1 in sorted(incoming):
   for id2,l2,w2 in sorted(pairs.get((b,a),[])):
    x,y=nodes[a];u,v=nodes[b]
    rows.append(dict(zip(FIELDS,('network-scope','0',id1,id2,a,b,l1,l2,w1,w2,x,y,u,v,'network-scope','network-scope',1))))
 return rows
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--network',type=Path,required=True);p.add_argument('--bounds',type=float,nargs=4,required=True,metavar=('XMIN','YMIN','XMAX','YMAX'));p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if a.output.exists():raise FileExistsError(a.output)
 rows=transitions(a.network,a.bounds)
 with a.output.open('w',encoding='utf8',newline='') as f:
  w=csv.DictWriter(f,fieldnames=FIELDS,delimiter='\t',lineterminator='\n');w.writeheader();w.writerows(rows)
 print('directional candidate pairs:',len(rows))
