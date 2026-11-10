"""Optional pre-Java section evidence preparation with a verified content cache."""
import argparse,csv,hashlib,importlib.metadata,json,math,subprocess,sys
from pathlib import Path

HERE=Path(__file__).resolve().parent
TOOL_FILES=('network_transitions.py','extract_witness_sources.py','prove_witness_turns.py','prepare_evidence.py','run_with_evidence.py')
OUTPUTS=('catalog.json','proof.json','witness.tsv')
def digest(path):
 with Path(path).open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
def version(name):
 try:return importlib.metadata.version(name)
 except importlib.metadata.PackageNotFoundError:return None

def inputs(config):
 bounds=config['bounds'];sources=config['sources']
 if len(bounds)!=4 or not all(isinstance(x,(int,float)) and math.isfinite(x) for x in bounds) or bounds[0]>bounds[2] or bounds[1]>bounds[3]:raise ValueError('Invalid EPSG:25832 bounds')
 if not sources:raise ValueError('At least one PBF source is required')
 source_records=[]
 for s in sources:
  path=Path(s['path']).resolve();actual=digest(path)
  if actual!=s['sha256'].lower():raise ValueError(f'PBF source hash mismatch: {path}')
  source_records.append({'path':str(path),'sha256':actual})
 return {'network':{'path':str(Path(config['network']).resolve()),'sha256':digest(config['network'])},'catalog':{'path':str(Path(config['catalog']).resolve()),'sha256':digest(config['catalog'])},'sources':source_records,'bounds':bounds,'tools':{name:digest(HERE/name) for name in TOOL_FILES},'dependencies':{x:version(x) for x in ('pyproj','osmium','lxml')}}

def build(config,folder):
 from network_transitions import transitions,FIELDS
 from extract_witness_sources import extract,required_bounds,witness_rows
 from prepare_evidence import prepare
 rows=transitions(config['network'],config['bounds'])
 if not rows:raise ValueError('No reverse-endpoint candidates in selected area')
 witness=folder/'witness.tsv'
 with witness.open('x',encoding='utf8',newline='') as f:
  writer=csv.DictWriter(f,fieldnames=FIELDS,delimiter='\t',lineterminator='\n');writer.writeheader();writer.writerows(rows)
 bounds=required_bounds(witness_rows([witness]));extracts=[]
 for i,source in enumerate(config['sources']):
  data=extract(source,bounds);dest=folder/f'source-{i}.json'
  with dest.open('x',encoding='utf8') as f:json.dump(data,f,separators=(',',':'))
  extracts.append(dest)
 prepare(Path(config['catalog']),[witness],extracts,folder/'catalog.json',folder/'proof.json',network=Path(config['network']),bounds=config['bounds'])

def prepare_cached(config,builder=build):
 metadata=inputs(config);fingerprint=hashlib.sha256(json.dumps(metadata,sort_keys=True,separators=(',',':')).encode()).hexdigest();folder=Path(config['cacheDir']).resolve()/fingerprint;marker=folder/'complete.json'
 if folder.exists():
  if not marker.is_file():raise ValueError(f'Incomplete or in-progress evidence cache: {folder}')
  state=json.loads(marker.read_text(encoding='utf8'))
  if state['inputs']!=metadata or any(not (folder/n).is_file() or digest(folder/n)!=h for n,h in state['outputs'].items()) or not set(OUTPUTS).issubset(state['outputs']):raise ValueError(f'Evidence cache integrity failure: {folder}')
 else:
  folder.mkdir(parents=True,exist_ok=False);builder(config,folder)
  if inputs(config)!=metadata:raise ValueError('Inputs changed during evidence preparation')
  names=(*OUTPUTS,*(x.name for x in sorted(folder.glob('source-*.json'))))
  state={'inputs':metadata,'outputs':{name:digest(folder/name) for name in names}}
  marker.write_text(json.dumps(state,indent=2)+'\n',encoding='utf8')
 return folder/'catalog.json'

def launch(config,command,runner,builder=build):
 if not command:raise ValueError('Missing Java command')
 if config is None:return runner(command)
 if sum(x.count('{halt_catalog}') for x in command)!=1:raise ValueError('Configured command must contain exactly one {halt_catalog} placeholder')
 catalog=prepare_cached(config,builder)
 return runner([x.replace('{halt_catalog}',str(catalog)) for x in command])

def main():
 p=argparse.ArgumentParser();p.add_argument('--evidence-config',type=Path);p.add_argument('command',nargs=argparse.REMAINDER);a=p.parse_args();command=a.command[1:] if a.command and a.command[0]=='--' else a.command
 config=None
 if a.evidence_config:
  path=a.evidence_config.resolve();config=json.loads(path.read_text(encoding='utf8'))
  for key in ('network','catalog','cacheDir'):config[key]=str((path.parent/config[key]).resolve())
  for source in config['sources']:source['path']=str((path.parent/source['path']).resolve())
 return launch(config,command,lambda cmd:subprocess.run(cmd,check=False).returncode)


# Pipeline adapter: one shared network/source extraction for every catalog.
def network_bounds(path):
 import gzip
 from lxml import etree
 bounds=[math.inf,math.inf,-math.inf,-math.inf]
 with (gzip.open if str(path).endswith('.gz') else open)(path,'rb') as stream:
  for _,el in etree.iterparse(stream,events=('end',),tag=('node','link')):
   if el.tag=='node':
    x=float(el.get('x'));y=float(el.get('y'))
    if not math.isfinite(x) or not math.isfinite(y):raise ValueError('Non-finite network coordinate')
    bounds=[min(bounds[0],x),min(bounds[1],y),max(bounds[2],x),max(bounds[3],y)]
   el.clear()
   while el.getprevious() is not None:del el.getparent()[0]
 if bounds[0]==math.inf:raise ValueError('Network has no nodes')
 return bounds

def merge_known(evidence):
 known={}
 for ev in evidence:
  pair=(ev['fromLinkId'],ev['toLinkId']);old=known.get(pair)
  if old is not None:
   if {k:v for k,v in old.items() if k!='sourceSha256'}!={k:v for k,v in ev.items() if k!='sourceSha256'}:raise ValueError(f'Conflicting existing evidence: {pair}')
  else:known[pair]=ev
 return known

def catalog_inputs(path):
 path=Path(path).resolve();root=json.loads(path.read_text(encoding='utf8'))
 if 'catalogs' not in root:return [(None,path)]
 entries=root['catalogs']
 if not isinstance(entries,dict) or not entries:raise ValueError('Section manifest requires a nonempty catalogs map')
 result=[]
 for key,value in sorted(entries.items()):
  if not isinstance(value,str) or not value.strip():raise ValueError('Invalid catalog path for '+key)
  child=(path.parent/value).resolve();data=json.loads(child.read_text(encoding='utf8'))
  if data.get('carrierId')!=key:raise ValueError('Catalog carrier mismatch: '+key)
  result.append((key,child))
 return result

def prepare_sources(config,bounds):
 from extract_witness_sources import extract
 import shutil
 contract=[]
 for source in config['sources']:
  area=source.get('bounds');fmt=source.get('format');snapshot=source.get('snapshot')
  if fmt not in ('PBF','RoadSourceJSON') or not isinstance(snapshot,str) or not snapshot.strip():raise ValueError('Every source requires format PBF or RoadSourceJSON and snapshot')
  if not isinstance(area,list) or len(area)!=4 or not all(isinstance(x,(int,float)) and math.isfinite(x) for x in area):raise ValueError('Invalid source coverage bounds')
  if area[0]>bounds[0] or area[1]>bounds[1] or area[2]<bounds[2] or area[3]<bounds[3]:raise ValueError('Source coverage does not cover actual network audit extent')
  if digest(source['path'])!=source['sha256'].lower():raise ValueError('Source hash mismatch: '+source['path'])
  contract.append(source)
 identity={'networkSha256':digest(config['network']),'bounds':bounds,'sources':contract,'tools':{n:digest(HERE/n) for n in TOOL_FILES},'dependencies':{x:version(x) for x in ('pyproj','osmium','lxml')}}
 key=hashlib.sha256(json.dumps(identity,sort_keys=True,separators=(',',':')).encode()).hexdigest();folder=Path(config['cacheDir']).resolve()/'network'/key;marker=folder/'complete.json'
 if folder.exists():
  if not marker.is_file():raise ValueError(f'Incomplete network evidence cache: {folder}')
  state=json.loads(marker.read_text(encoding='utf8'))
  if state['inputs']!=identity or any(not (folder/n).is_file() or digest(folder/n)!=h for n,h in state['outputs'].items()):raise ValueError(f'Network evidence cache integrity failure: {folder}')
 else:
  folder.mkdir(parents=True,exist_ok=False)
  try:
   outputs={}
   for i,source in enumerate(contract):
    dest=folder/f'source-{i}.json'
    if source['format']=='RoadSourceJSON':
     data=json.loads(Path(source['path']).read_text(encoding='utf8'));actual=data.get('EPSG25832Bounds')
     if actual!=source['bounds']:raise ValueError('RoadSourceJSON coverage differs from declared bounds')
     if data.get('source',{}).get('snapshot')!=source['snapshot']:raise ValueError('RoadSourceJSON snapshot differs from declaration')
     shutil.copyfile(source['path'],dest)
    else:
     with dest.open('x',encoding='utf8') as f:json.dump(extract(source,bounds),f,separators=(',',':'))
    outputs[dest.name]=digest(dest)
   if any(digest(src['path'])!=src['sha256'].lower() for src in contract):raise ValueError('Source changed during evidence preparation')
   marker.write_text(json.dumps({'inputs':identity,'outputs':outputs},indent=2)+'\n',encoding='utf8')
  except BaseException:
   shutil.rmtree(folder)
   raise
 return [folder/f'source-{i}.json' for i in range(len(contract))]

def build_pipeline(config,folder):
 from collections import Counter
 from network_transitions import transitions,FIELDS
 from extract_witness_sources import extract,required_bounds,witness_rows
 from prepare_evidence import prepare
 all_rows=transitions(config['network'],config['bounds'])
 links={}; conflicts=set(); known={}; originals=[]; provenance={}; all_known=[]
 for key,path in config['catalogs']:
  original=json.loads(Path(path).read_text(encoding='utf8'));originals.append((key,original))
  for link,road in original.get('links',{}).items():
   if link in links and links[link]!=road:conflicts.add(link)
   else:links[link]=road
  for evidence in original.get('turnEvidence',[]):
   pair=(evidence['fromLinkId'],evidence['toLinkId']);provenance.setdefault(pair,set()).add(evidence.get('sourceSha256',''))
   all_known.append(evidence)
 known=merge_known(all_known)
 for link in conflicts:links.pop(link,None)
 unique={(r['fromNode'],r['toNode'],r['fromWay'],r['fromRaw']) for r in all_rows if r['fromWay']}
 multiplicity=Counter((a,b,way) for a,b,way,_ in unique)
 rows=[]
 for row in all_rows:
  if (row['fromRaw'],row['toRaw']) in known:continue
  a,b,w1,w2=(row[k] for k in ('fromNode','toNode','fromWay','toWay'))
  if w1 and w2 and (w1!=w2 or multiplicity[a,b,w1]==multiplicity[b,a,w1]==1):continue
  rows.append(row)
 print(f'Section evidence: {len(all_rows)} directional candidates, {len(rows)} need source audit',flush=True)
 witness=folder/'witness.tsv'
 with witness.open('x',encoding='utf8',newline='') as f:
  writer=csv.DictWriter(f,fieldnames=FIELDS,delimiter='\t',lineterminator='\n');writer.writeheader();writer.writerows(rows)
 (folder/'prior-provenance.json').write_text(json.dumps([{'fromLinkId':a,'toLinkId':b,'sourceSha256':sorted(v)} for (a,b),v in sorted(provenance.items())]),encoding='utf8')
 base=folder/'common-input.json';base.write_text(json.dumps({'links':links,'turnEvidence':list(known.values())}),encoding='utf8')
 proof=folder/'proof.json';common=folder/'common-evidence.json'
 if rows:
  bounds=required_bounds(all_rows)
  extracts=prepare_sources(config,bounds)
  bundle=prepare(base,[witness],extracts,common,proof,network=Path(config['network']),bounds=config['bounds'],minimum_link_length=config['importMinimumLinkLengthMeters'])
  print('Section evidence statuses:',dict(Counter(r['status'] for r in bundle['results'])),flush=True)
  evidence=json.loads(common.read_text(encoding='utf8'))['turnEvidence']
 else:
  proof.write_text(json.dumps({'results':[]}),encoding='utf8');evidence=list(known.values())
 out={}
 for i,(key,original) in enumerate(originals):
  name=f'catalog-{i}.json';original['turnEvidence']=evidence
  original['turnEvidenceAudit']={'proof':str(proof.resolve()),'proofSha256':digest(proof)}
  (folder/name).write_text(json.dumps(original,indent=2)+'\n',encoding='utf8')
  out[key if key is not None else original['carrierId']]=name
 (folder/'manifest.json').write_text(json.dumps({'catalogs':out},indent=2)+'\n',encoding='utf8')

def prepare_pipeline(config,network,catalog,builder=build_pipeline):
 sources=config.get('sources')
 if not isinstance(sources,list) or not sources:raise ValueError('At least one configured PBF source is required')
 network=Path(network).resolve();catalog=Path(catalog).resolve();catalogs=catalog_inputs(catalog)
 cfg=dict(config,network=str(network),bounds=network_bounds(network),catalogs=[(k,str(p)) for k,p in catalogs])
 base=inputs(dict(cfg,catalog=str(catalog)))
 base['network']['path']='<actual-scenario-network>'
 minimum=config.get('importMinimumLinkLengthMeters')
 if not isinstance(minimum,(int,float)) or not math.isfinite(minimum) or minimum<0:raise ValueError('importMinimumLinkLengthMeters is required and must be nonnegative')
 base['sourceContract']=config['sources'];base['minimumLinkLength']=minimum
 base['catalogs']=[{'carrier':k,'path':str(p),'sha256':digest(p)} for k,p in catalogs]
 fingerprint=hashlib.sha256(json.dumps(base,sort_keys=True,separators=(',',':')).encode()).hexdigest()
 folder=Path(config['cacheDir']).resolve()/'catalogs'/fingerprint;marker=folder/'complete.json'
 if folder.exists():
  if not marker.is_file():raise ValueError(f'Incomplete or in-progress evidence cache: {folder}')
  state=json.loads(marker.read_text(encoding='utf8'))
  if state['inputs']!=base or not state['outputs'] or 'manifest.json' not in state['outputs'] or any(not (folder/n).is_file() or digest(folder/n)!=h for n,h in state['outputs'].items()):raise ValueError(f'Evidence cache integrity failure: {folder}')
 else:
  folder.mkdir(parents=True,exist_ok=False)
  try:
   import shutil
   shutil.copyfile(network,folder/'network.xml');cfg['network']=str(folder/'network.xml');builder(cfg,folder)
   after=inputs(dict(cfg,catalog=str(catalog)));after['network']['path']='<actual-scenario-network>';after['sourceContract']=config['sources'];after['minimumLinkLength']=minimum
   if after!= {k:v for k,v in base.items() if k!='catalogs'} or any(digest(p)!=record['sha256'] for (_,p),record in zip(catalogs,base['catalogs'])):raise ValueError('Inputs changed during evidence preparation')
   outputs={p.name:digest(p) for p in folder.iterdir() if p.is_file()}
   if 'manifest.json' not in outputs:raise ValueError('Preparation did not produce a manifest')
   marker.write_text(json.dumps({'inputs':base,'outputs':outputs},indent=2)+'\n',encoding='utf8')
  except BaseException:
   import shutil
   shutil.rmtree(folder)
   raise
 return folder/'manifest.json'

def pipeline_main(args):
 p=argparse.ArgumentParser();p.add_argument('--pipeline-config',type=Path,required=True);p.add_argument('--network',type=Path,required=True);p.add_argument('--catalog',type=Path,required=True);p.add_argument('--result',type=Path,required=True);p.add_argument('--minimum-link-length',type=float,required=True);a=p.parse_args(args)
 path=a.pipeline_config.resolve();config=json.loads(path.read_text(encoding='utf8'))
 if config.get('importMinimumLinkLengthMeters')!=a.minimum_link_length:raise ValueError('Configured importMinimumLinkLengthMeters differs from the active network import setting')
 config['cacheDir']=str((path.parent/config['cacheDir']).resolve())
 for source in config.get('sources',[]):source['path']=str((path.parent/source['path']).resolve())
 result=prepare_pipeline(config,a.network,a.catalog);a.result.write_text(str(result),encoding='utf8')

if __name__=='__main__':
 if '--pipeline-config' in sys.argv: pipeline_main(sys.argv[1:])
 else: sys.exit(main())
