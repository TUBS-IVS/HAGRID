"""Extract hash-pinned PBF road geometry for a network witness file."""
import argparse,json,sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parent))
from extract_witness_sources import extract,required_bounds,witness_rows,sha256
p=argparse.ArgumentParser();p.add_argument('--witness',nargs='+',type=Path,required=True);p.add_argument('--pbf',type=Path,required=True);p.add_argument('--sha256',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
if a.output.exists():raise FileExistsError(a.output)
source={'path':str(a.pbf.resolve()),'sha256':a.sha256};data=extract(source,required_bounds(witness_rows(a.witness)))
with a.output.open('x',encoding='utf8') as f:json.dump(data,f,separators=(',',':'))
print('Extracted',len(data['nodes']),'nodes',len(data['ways']),'ways; SHA-256',sha256(a.output))
