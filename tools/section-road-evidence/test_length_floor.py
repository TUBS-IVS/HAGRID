import json,tempfile,unittest
from pathlib import Path
from pyproj import Transformer
from prove_witness_turns import RoadSource

class LengthFloorTest(unittest.TestCase):
    def test_declared_floor_binds_short_geometry_without_inflating_tolerance(self):
        inverse=Transformer.from_crs(25832,4326,always_xy=True)
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'source.json'
            p.write_text(json.dumps({'source':{},'EPSG25832Bounds':[0,0,1e7,1e7],
                'nodes':{'a':inverse.transform(550000,5800000),'b':inverse.transform(550003,5800000)},
                'ways':{'1':{'nodes':['a','b'],'tags':{'highway':'residential'}}}}))
            args=('a','b',(550000,5800000),(550003,5800000),5.,'1',True,True)
            source=RoadSource(p,[0,0,1,1])
            self.assertEqual(source.bind(*args)['status'],'no-exact-directional-chain')
            floored=RoadSource(p,[0,0,1,1],minimum_link_length=5.)
            bound=floored.bind(*args)
            self.assertEqual(bound['status'],'bound')
            self.assertAlmostEqual(bound['chains'][0]['lengthMeters'],3.,places=6)
            self.assertEqual(bound['chains'][0]['modeledLengthMeters'],5.)
            self.assertEqual(floored.bind(*(*args[:4],6.,*args[5:]))['status'],'no-exact-directional-chain')

    def test_floor_applies_to_search_without_way_id(self):
        inverse=Transformer.from_crs(25832,4326,always_xy=True)
        with tempfile.TemporaryDirectory() as td:
            p=Path(td)/'source.json'
            p.write_text(json.dumps({'source':{},'EPSG25832Bounds':[0,0,1e7,1e7],
                'nodes':{'a':inverse.transform(550000,5800000),'b':inverse.transform(550003,5800000)},
                'ways':{'1':{'nodes':['a','b'],'tags':{'highway':'residential'}}}}))
            source=RoadSource(p,[0,0,1,1],minimum_link_length=5.)
            self.assertEqual(source.bind('a','b',(550000,5800000),(550003,5800000),5.,'',True,True)['status'],'bound')

if __name__=='__main__':unittest.main()
