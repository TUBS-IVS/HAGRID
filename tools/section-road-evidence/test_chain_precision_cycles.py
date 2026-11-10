import json,math,tempfile,unittest
from pathlib import Path
from pyproj import Transformer
from prove_witness_turns import RoadSource,classify

class ChainPrecisionTest(unittest.TestCase):
    def source(self,folder,coords,ways):
        t=Transformer.from_crs(25832,4326,always_xy=True);p=Path(folder)/'s.json'
        p.write_text(json.dumps({'source':{},'EPSG25832Bounds':[0,0,1e7,1e7],
            'nodes':{k:t.transform(550000+x,5800000+y) for k,(x,y) in coords.items()},
            'ways':{k:{'nodes':nodes,'tags':{'highway':'residential',**tags}} for k,(nodes,tags) in ways.items()}}))
        return RoadSource(p,[0,0,1,1])

    def test_millimetre_different_curves_are_not_equal_length(self):
        with tempfile.TemporaryDirectory() as td:
            s=self.source(td,{'a':(0,0),'b':(20,0),'c':(10,10),'d':(10,-10.007)},
                {'1':(['a','c','b'],{}),'2':(['a','d','b'],{})})
            bound=s.bind('a','b',(550000,5800000),(550020,5800000),math.sqrt(800),'',True,True)
            self.assertEqual([x['nodes'] for x in bound['chains']],[('a','c','b')])

    def test_equal_length_distinct_curves_remain_competing(self):
        with tempfile.TemporaryDirectory() as td:
            s=self.source(td,{'a':(0,0),'b':(20,0),'c':(10,10),'d':(10,-10)},
                {'1':(['a','c','b'],{}),'2':(['a','d','b'],{})})
            row=dict(fromRaw='i',toRaw='o',fromNode='a',toNode='b',fromLength=str(math.sqrt(800)),toLength=str(math.sqrt(800)),fromWay='',toWay='',fromX='550000',fromY='5800000',toX='550020',toY='5800000')
            self.assertIsNone(classify(row,[s],[])['kind'])

    def test_directed_loop_after_first_target_arrival_is_retained(self):
        with tempfile.TemporaryDirectory() as td:
            s=self.source(td,{'a':(0,0),'b':(10,0),'c':(20,0),'d':(20,10)},
                {'1':(['a','b'],{}),'2':(['b','c','d','b'],{'oneway':'yes'})})
            length=30+math.sqrt(200)
            bound=s.bind('a','b',(550000,5800000),(550010,5800000),length,'',True,True)
            self.assertEqual(bound['status'],'bound')
            self.assertEqual([x['nodes'] for x in bound['chains']],[('a','b','c','d','b')])

if __name__=='__main__':unittest.main()
