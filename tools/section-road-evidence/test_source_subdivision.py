import json,math,tempfile,unittest
from pathlib import Path
from pyproj import Transformer
from prove_witness_turns import RoadSource,classify

class SubdivisionTest(unittest.TestCase):
    def classify_sources(self, bend=False):
        inverse=Transformer.from_crs(25832,4326,always_xy=True)
        xy={'a':(550000,5800000),'b':(550020,5800000),'q':(550010,5799990),
            'c':(550010,5800010 if bend else 5800000),'d':(550010,5799990) if bend else (550015,5800000)}
        length=math.sqrt(800) if bend else 20.
        row=dict(fromRaw='in',toRaw='out',fromNode='a',toNode='b',fromLength=str(math.sqrt(800)),toLength=str(length),fromWay='10',toWay='20',fromX='550000',fromY='5800000',toX='550020',toY='5800000')
        with tempfile.TemporaryDirectory() as td:
            sources=[]
            for i in (0,1):
                ways={'20':{'nodes':['b','c','a'] if i==0 else ['b','d','a'] if bend else ['b','d','c','a'],'tags':{'highway':'residential'}}}
                if i==0:ways['10']={'nodes':['a','q','b'],'tags':{'highway':'residential'}}
                p=Path(td)/f'{i}.json';p.write_text(json.dumps({'source':{},'EPSG25832Bounds':[0,0,1e7,1e7],'nodes':{k:inverse.transform(*v) for k,v in xy.items()},'ways':ways}))
                sources.append(RoadSource(p,[0,0,1,1]))
            return classify(row,sources,[])

    def test_collinear_subdivision_does_not_create_a_source_conflict(self):
        self.assertEqual(self.classify_sources()['kind'],'NOT_TURN')

    def test_different_same_length_geometry_remains_unresolved(self):
        self.assertIsNone(self.classify_sources(True)['kind'])

if __name__=='__main__':unittest.main()
