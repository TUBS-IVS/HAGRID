import importlib.util, json, tempfile, unittest, sys
from pathlib import Path
P = Path(__file__).parent
sys.path.insert(0,str(P))
spec = importlib.util.spec_from_file_location('wrapper', P/'run_with_evidence.py')
w = importlib.util.module_from_spec(spec); spec.loader.exec_module(w); w.HERE=P
class AutomaticPipelineTest(unittest.TestCase):
 def test_manifest_all_catalogs_and_verified_cache(self):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as d:
   p=Path(d); n=p/'network.xml'; n.write_text('<network><nodes><node id="a" x="0" y="0"/><node id="b" x="3" y="4"/></nodes><links/></network>'); s=p/'source.pbf';s.write_bytes(b'source')
   for i in ('a','b'): (p/(i+'.json')).write_text(json.dumps({'carrierId':i}))
   manifest=p/'manifest.json'; manifest.write_text(json.dumps({'catalogs':{'a':'a.json','b':'b.json'}})); cfg={'sources':[{'path':str(s),'sha256':w.digest(s)}],'cacheDir':str(p/'cache'),'importMinimumLinkLengthMeters':0}; calls=[]
   def builder(config,folder):
    calls.append(config)
    for name in ('manifest.json','proof.json','witness.tsv'): (folder/name).write_text(name)
    for name in ('a.json','b.json'): (folder/name).write_text(name)
   a=w.prepare_pipeline(cfg,n,manifest,builder); self.assertEqual(a,w.prepare_pipeline(cfg,n,manifest,builder)); self.assertEqual(len(calls),1); self.assertEqual(calls[0]['bounds'],[0.,0.,3.,4.]);self.assertEqual(len(calls[0]['catalogs']),2)
   (p/'b.json').write_text('{"carrierId":"b","changed":true}'); self.assertNotEqual(a,w.prepare_pipeline(cfg,n,manifest,builder)); self.assertEqual(len(calls),2)
 def test_missing_source_fails_before_builder(self):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as d:
   p=Path(d);n=p/'network';n.write_text('<network><nodes><node x="0" y="0"/></nodes></network>');c=p/'c.json';c.write_text('{}')
   with self.assertRaisesRegex(ValueError,'source'): w.prepare_pipeline({'sources':[],'cacheDir':str(p/'cache')},n,c,lambda *a:self.fail('builder called'))
 def test_failed_build_can_retry_without_manual_cache_cleanup(self):
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as d:
   p=Path(d);n=p/'network';n.write_text('<network><nodes><node id="a" x="0" y="0"/></nodes></network>');c=p/'c';c.write_text('{"carrierId":"a"}');source=p/'pbf';source.write_text('pbf')
   cfg={'sources':[{'path':str(source),'sha256':w.digest(source)}],'cacheDir':str(p/'cache'),'importMinimumLinkLengthMeters':0}
   def bad(config,folder):raise ValueError('test failure')
   with self.assertRaisesRegex(ValueError,'test failure'):w.prepare_pipeline(cfg,n,c,bad)
   with self.assertRaisesRegex(ValueError,'test failure'):w.prepare_pipeline(cfg,n,c,bad)
class EvidenceMergeTest(unittest.TestCase):
 def test_equivalent_proofs_do_not_conflict(self):
  x={'fromLinkId':'1','toLinkId':'2','kind':'TURN','sourceSha256':'a','fromNodeId':'a','toNodeId':'b','fromLengthMeters':1.0,'toLengthMeters':1.0}
  y=dict(x,sourceSha256='b')
  self.assertEqual(w.merge_known([x,y])[('1','2')]['kind'],'TURN')
 def test_conflicting_kinds_fail(self):
  x={'fromLinkId':'1','toLinkId':'2','kind':'TURN','sourceSha256':'a'}
  with self.assertRaisesRegex(ValueError,'Conflicting'):w.merge_known([x,dict(x,kind='NOT_TURN')])
class RealPreparationTest(unittest.TestCase):
 def test_real_two_catalogs_coverage_and_network_cache_reuse(self):
  from pyproj import Transformer
  with tempfile.TemporaryDirectory(dir=Path(__file__).parent) as d:
   p=Path(d);net=p/'net.xml';net.write_text('<network><nodes><node id="a" x="550000" y="5800000"/><node id="c" x="550020" y="5800000"/></nodes><links><link id="in" from="a" to="c" length="20"/><link id="out" from="c" to="a" length="20"/></links></network>')
   inv=Transformer.from_crs(25832,4326,always_xy=True); bounds=[549900,5799900,550100,5800100]
   source=p/'source.json';source.write_text(json.dumps({'EPSG25832Bounds':bounds,'source':{'snapshot':'2022-08-01'},'nodes':{k:inv.transform(x,5800000) for k,x in [('a',550000),('b',550010),('c',550020)]},'ways':{'1':{'nodes':['a','b','c'],'tags':{'highway':'residential'}}}}))
   for name in ('a','b'):(p/(name+'.json')).write_text(json.dumps({'carrierId':name,'links':{},'turnEvidence':[]}))
   manifest=p/'manifest.json';manifest.write_text(json.dumps({'catalogs':{'a':'a.json','b':'b.json'}}))
   cfg={'sources':[{'path':str(source),'sha256':w.digest(source),'format':'RoadSourceJSON','snapshot':'2022-08-01','bounds':bounds}],'cacheDir':str(p/'cache'),'importMinimumLinkLengthMeters':0}
   out=w.prepare_pipeline(cfg,net,manifest); result=json.loads(out.read_text()); self.assertEqual(set(result['catalogs']),{'a','b'})
   for filename in result['catalogs'].values(): self.assertEqual({e['kind'] for e in json.loads((out.parent/filename).read_text())['turnEvidence']},{'TURN'})
   self.assertEqual(out,w.prepare_pipeline(cfg,net,manifest)); source_dirs=list((p/'cache'/'network').iterdir());self.assertEqual(len(source_dirs),1)
   (p/'a.json').write_text(json.dumps({'carrierId':'a','links':{},'turnEvidence':[],'changed':True}));self.assertNotEqual(out,w.prepare_pipeline(cfg,net,manifest));self.assertEqual(source_dirs,list((p/'cache'/'network').iterdir()))
   cfg['sources'][0]['bounds']=[0,0,1,1]
   with self.assertRaisesRegex(ValueError,'coverage'):w.prepare_pipeline(cfg,net,manifest)
if __name__=='__main__': unittest.main()
