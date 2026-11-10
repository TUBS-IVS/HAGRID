import unittest
from extract_historical_source import convert_response

class HistoricalSourceTest(unittest.TestCase):
    def response(self):
        return {'elements':[
            {'type':'node','id':1,'lon':9.,'lat':52.,'timestamp':'2022-01-01T00:00:00Z'},
            {'type':'node','id':2,'lon':9.001,'lat':52.,'timestamp':'2022-01-01T00:00:00Z'},
            {'type':'way','id':3,'nodes':[1,2],'tags':{'highway':'residential'},'timestamp':'2022-01-01T00:00:00Z'}]}

    def test_complete_snapshot_preserves_topology_and_provenance(self):
        data=convert_response(self.response(),'2022-07-01T00:00:00Z',[0,0,1,1],{'sha256':'abc'})
        self.assertEqual(data['ways']['3']['nodes'],[1,2])
        self.assertEqual(data['source']['snapshot'],'2022-07-01T00:00:00Z')
        self.assertEqual(data['source']['sha256'],'abc')

    def test_incomplete_and_later_versions_are_rejected(self):
        for mutate in (lambda d:d.update(remark='runtime error: timeout'),
                       lambda d:d['elements'].pop(0),
                       lambda d:d['elements'][0].update(timestamp='2023-01-01T00:00:00Z')):
            with self.subTest(mutate=mutate):
                data=self.response();mutate(data)
                with self.assertRaises(ValueError):convert_response(data,'2022-07-01T00:00:00Z',[0,0,1,1],{})

if __name__=='__main__':unittest.main()
