# -*- coding: utf-8 -*-
"""One-off script that writes MINI.output_network.xml.gz next to
MINI.output_events.xml.gz. Run manually (not part of the test suite):
    python _make_network_fixture.py

Hand-authored mini MATSim network, no namespace, EPSG:25832 coords near
Hoyerswerda:
  n1=(864000,5705000)  n2=(864500,5705100)  n3=(865000,5705050)
  l1: n1 -> n2
  l2: n2 -> n3          (l1.to == l2.from == n2, so l1+l2 chain into one run)
  l9: n3 -> n1          (unused link -- must be excluded by used_links filter)
"""
import gzip
from pathlib import Path

NETWORK_XML = """<?xml version="1.0" encoding="utf-8"?>
<network name="mini">
<nodes>
<node id="n1" x="864000.0" y="5705000.0"/>
<node id="n2" x="864500.0" y="5705100.0"/>
<node id="n3" x="865000.0" y="5705050.0"/>
</nodes>
<links>
<link id="l1" from="n1" to="n2" length="502.0" freespeed="16.6" capacity="1000" permlanes="1" oneway="1" modes="car"/>
<link id="l2" from="n2" to="n3" length="509.0" freespeed="16.6" capacity="1000" permlanes="1" oneway="1" modes="car"/>
<link id="l9" from="n3" to="n1" length="1010.0" freespeed="16.6" capacity="1000" permlanes="1" oneway="1" modes="car"/>
</links>
</network>
"""

OUT = Path(__file__).parent / "MINI.output_network.xml.gz"

if __name__ == "__main__":
    with gzip.open(OUT, "wt", encoding="utf-8") as f:
        f.write(NETWORK_XML)
    print(f"wrote {OUT}")
