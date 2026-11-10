import importlib.util,json,tempfile,unittest
from pathlib import Path
from pyproj import Transformer
P=Path(__file__).parent
class IntegratedTest(unittest.TestCase):
 def test_prepares_all_network_candidates_once(self):
  spec=importlib.util.spec_from_file_location('pipeline',P/'prepare_network_evidence.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
  with tempfile.TemporaryDirectory(dir=P) as td:
   t=Path(td);(t/'n.xml').write_text('<network><nodes><node id="a" x="550000" y="5800000"/><node id="c" x="550020" y="5800000"/></nodes><links><link id="in" from="a" to="c" length="20"/><link id="out" from="c" to="a" length="20"/></links></network>')
   inv=Transformer.from_crs(25832,4326,always_xy=True);src={'EPSG25832Bounds':[549900,5799900,550100,5800100],'source':{},'nodes':{k:inv.transform(x,5800000) for k,x in [('a',550000),('b',550010),('c',550020)]},'ways':{'1':{'nodes':['a','b','c'],'tags':{'highway':'residential'}}}};(t/'source.json').write_text(json.dumps(src));(t/'catalog.json').write_text(json.dumps({'turnEvidence':[]}))
   count=m.run(t/'n.xml',t/'catalog.json',[t/'source.json'],(549999,5799999,550021,5800001),t/'out.json',t/'proof.json',t/'w.tsv')
   self.assertEqual(count,2)
   result=json.loads((t/'out.json').read_text());self.assertEqual({(x['fromLinkId'],x['toLinkId'],x['kind']) for x in result['turnEvidence']},{('in','out','TURN'),('out','in','TURN')})
if __name__=='__main__':unittest.main()

