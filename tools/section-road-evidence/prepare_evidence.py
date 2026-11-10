"""Prepare source-certified turn evidence for arbitrary witnessed transitions."""
import argparse, json, sys
from collections import defaultdict
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from extract_witness_sources import read, required_bounds, sha256, witness_rows
from prove_witness_turns import RoadSource, classify

FIELDS=('fromRaw','toRaw','fromNode','toNode','fromLength','toLength','fromWay','toWay','fromX','fromY','toX','toY')
def prepare(catalog,witnesses,sources,output,proof,network=None,bounds=None,minimum_link_length=0.0):
 catalog,output,proof=map(Path,(catalog,output,proof))
 if output.resolve()==catalog.resolve() or proof.resolve() in (catalog.resolve(),output.resolve()): raise ValueError('Input and outputs must be distinct')
 rows=list(witness_rows(witnesses)); bounds=required_bounds(rows)
 roads=[RoadSource(Path(s),bounds,minimum_link_length=minimum_link_length) for s in sources]; original=read(catalog); groups=defaultdict(list)
 for row in rows: groups[row['fromRaw'],row['toRaw']].append(row)
 results=[]
 for pair,group in groups.items():
  raw={k:group[0][k] for k in FIELDS}
  if any(any(r[k]!=v for k,v in raw.items()) for r in group): raise ValueError(f'Conflicting witness records: {pair}')
  result=classify(raw,roads,[original.get('links',{})]);result['raw']=raw;results.append(result)
 bundle={'catalog':{'path':str(catalog.resolve()),'sha256':sha256(catalog)},'witnessFiles':[{'path':str(Path(p).resolve()),'sha256':sha256(Path(p))} for p in witnesses], 'sourceFiles':[r.provenance for r in roads], 'results':results}
 if network is not None: bundle['network']={'path':str(Path(network).resolve()),'sha256':sha256(Path(network)),'bounds':bounds}
 proof.write_text(json.dumps(bundle,indent=2)+'\n',encoding='utf8'); digest=sha256(proof)
 existing={(e['fromLinkId'],e['toLinkId']):e for e in original.get('turnEvidence',[])}
 for result in results:
  if not result['kind']: continue
  row=result['raw'];pair=row['fromRaw'],row['toRaw']; prev=existing.get(pair)
  if prev and prev['kind']!=result['kind']: raise ValueError(f'Conflicting existing evidence: {pair}')
  if not prev: existing[pair]=dict(fromLinkId=pair[0],toLinkId=pair[1],kind=result['kind'],sourceSha256=digest,fromNodeId=row['fromNode'],toNodeId=row['toNode'],fromLengthMeters=float(row['fromLength']),toLengthMeters=float(row['toLength']))
 original['turnEvidence']=[existing[k] for k in sorted(existing)];original['turnEvidenceAudit']={'baseCatalog':str(catalog.resolve()),'baseCatalogSha256':sha256(catalog),'proof':str(proof.resolve()),'proofSha256':digest}
 output.write_text(json.dumps(original,indent=2)+'\n',encoding='utf8');return bundle
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--catalog',type=Path,required=True);p.add_argument('--witness',type=Path,nargs='+',required=True);p.add_argument('--source',type=Path,nargs='+',required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--proof',type=Path,required=True);p.add_argument('--minimum-link-length',type=float,default=0.0);a=p.parse_args()
 result=prepare(a.catalog,a.witness,a.source,a.output,a.proof,minimum_link_length=a.minimum_link_length);print({k:sum(r['kind']==k for r in result['results']) for k in ('TURN','NOT_TURN',None)})
