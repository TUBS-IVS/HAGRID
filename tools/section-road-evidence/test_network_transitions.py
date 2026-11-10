import importlib.util,tempfile,unittest
from pathlib import Path
P=Path(__file__).parent
class TransitionsTest(unittest.TestCase):
 def test_scans_both_directions_without_inferring_a_turn(self):
  spec=importlib.util.spec_from_file_location('transitions',P/'network_transitions.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
  with tempfile.TemporaryDirectory(dir=P) as d:
   p=Path(d);(p/'network.xml').write_text('<network><nodes><node id="a" x="0" y="0"/><node id="b" x="1" y="0"/><node id="c" x="2" y="0"/></nodes><links><link id="x" from="a" to="b" length="1"/><link id="y" from="b" to="a" length="1"/><link id="z" from="b" to="c" length="1"/></links></network>')
   rows=m.transitions(p/'network.xml',(-1,-1,3,3));self.assertEqual([(x['fromRaw'],x['toRaw']) for x in rows],[('x','y'),('y','x')]);self.assertFalse(any('kind' in row for row in rows))
if __name__=='__main__':unittest.main()
