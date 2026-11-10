import importlib.util,json,tempfile,unittest
from pathlib import Path
from pyproj import Transformer
P=Path(__file__).parent
class EvidenceTest(unittest.TestCase):
 def test_reversed_chain_is_proven_and_unknown_is_not_defaulted(self):
  spec=importlib.util.spec_from_file_location('evidence',P/'prepare_evidence.py'); m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
  with tempfile.TemporaryDirectory(dir=P) as td:
   t=Path(td); inv=Transformer.from_crs(25832,4326,always_xy=True); coords={'a':(550000,5800000),'b':(550010,5800000),'c':(550020,5800000)}
   src={'EPSG25832Bounds':[549900,5799900,550100,5800100],'source':{},'nodes':{k:inv.transform(*v) for k,v in coords.items()},'ways':{'1':{'nodes':['a','b','c'],'tags':{'highway':'residential'}}}}
   (t/'source.json').write_text(json.dumps(src)); (t/'catalog.json').write_text(json.dumps({'turnEvidence':[]}))
   hdr='carrier\ttariff\tfromRaw\ttoRaw\tfromNode\ttoNode\tfromLength\ttoLength\tfromWay\ttoWay\tfromX\tfromY\ttoX\ttoY\twitnessOrigin\twitnessDestination\trowCount\n'
   row='x\t0.1\tin\tout\ta\tc\t20\t20\t1\t1\t550000\t5800000\t550020\t5800000\ta\tc\t1\n'
   bad='x\t0.1\tin\tmissing\ta\tc\t20\t20\t1\t999\t550000\t5800000\t550020\t5800000\ta\tc\t1\n'
   (t/'w.tsv').write_text(hdr+row+bad)
   result=m.prepare(t/'catalog.json',[t/'w.tsv'],[t/'source.json'],t/'out.json',t/'proof.json')
   self.assertEqual([x['kind'] for x in result['results']],['TURN',None]); out=json.loads((t/'out.json').read_text());self.assertEqual([(x['fromLinkId'],x['toLinkId'],x['kind']) for x in out['turnEvidence']],[('in','out','TURN')])
if __name__=='__main__':unittest.main()
