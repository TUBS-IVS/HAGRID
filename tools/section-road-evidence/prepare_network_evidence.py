"""Prepare a section catalog from every candidate in a chosen network extent."""
import argparse,csv,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from network_transitions import transitions,FIELDS
from prepare_evidence import prepare

def run(network,catalog,sources,bounds,output,proof,witness):
 network,catalog,output,proof,witness=map(Path,(network,catalog,output,proof,witness))
 outputs=[output.resolve(),proof.resolve(),witness.resolve()]; inputs={network.resolve(),catalog.resolve(),*(Path(s).resolve() for s in sources)}
 if len(set(outputs))!=3 or inputs.intersection(outputs): raise ValueError('All inputs and outputs must be distinct')
 if any(x.exists() for x in (output,proof,witness)):raise FileExistsError('Output already exists')
 rows=transitions(network,bounds)
 if not rows: raise ValueError('No reverse-endpoint candidates in selected scope')
 with witness.open('w',encoding='utf8',newline='') as f:
  writer=csv.DictWriter(f,fieldnames=FIELDS,delimiter='\t',lineterminator='\n');writer.writeheader();writer.writerows(rows)
 prepare(catalog,[witness],sources,output,proof,network=network,bounds=bounds)
 return len(rows)
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('--network',type=Path,required=True);p.add_argument('--catalog',type=Path,required=True);p.add_argument('--source',type=Path,nargs='+',required=True);p.add_argument('--bounds',type=float,nargs=4,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--proof',type=Path,required=True);p.add_argument('--witness',type=Path,required=True);a=p.parse_args()
 print('Processed directional candidate pairs:',run(a.network,a.catalog,a.source,a.bounds,a.output,a.proof,a.witness))
