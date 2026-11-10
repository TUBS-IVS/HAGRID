import importlib.util,json,sys,tempfile,unittest
from pathlib import Path
P=Path(__file__).parent;sys.path.insert(0,str(P))
spec=importlib.util.spec_from_file_location('wrapper',P/'run_with_evidence.py');w=importlib.util.module_from_spec(spec);spec.loader.exec_module(w)
class WrapperTest(unittest.TestCase):
 def test_unconfigured_passes_command_unchanged(self):
  calls=[];rc=w.launch(None,['fake-java','x'],lambda args:calls.append(args) or 0);self.assertEqual(rc,0);self.assertEqual(calls,[['fake-java','x']])
 def test_cache_reuses_verified_input_and_rebuilds_changed_network(self):
  with tempfile.TemporaryDirectory(dir=P) as d:
   p=Path(d);n=p/'network';c=p/'catalog';s=p/'source';n.write_text('one');c.write_text('{}');s.write_text('pbf');cfg={'network':str(n),'catalog':str(c),'sources':[{'path':str(s),'sha256':w.digest(s)}],'bounds':[0,0,1,1],'cacheDir':str(p/'cache')};calls=[]
   def build(config,folder):
    calls.append(1)
    for x in ('catalog.json','proof.json','witness.tsv'): (folder/x).write_text(x)
   a=w.prepare_cached(cfg,build);b=w.prepare_cached(cfg,build);self.assertEqual(a,b);self.assertEqual(len(calls),1)
   commands=[];w.launch(cfg,['java','routing.haltCatalogFile={halt_catalog}'],lambda x:commands.append(x) or 0,build);self.assertIn(str(a),commands[0][1]);self.assertEqual(len(calls),1)
   n.write_text('two');self.assertNotEqual(w.prepare_cached(cfg,build),a);self.assertEqual(len(calls),2)
   n.write_text('one');(a.parent/'catalog.json').write_text('tampered');self.assertRaises(ValueError,w.prepare_cached,cfg,build)
if __name__=='__main__':unittest.main()

