# Synthetic long-link export fixture

Four artificial HAGRID demand rows on the horizontal line y=5800000, between
x=550010 and x=554500 in EPSG:25832. No real customer addresses or regional
network are included. The five shapefile sidecars must travel together.

`CarrierGenerationAccessTest.nonFullMergeKeepsPythonExportedPhysicalStopsOnLongLink`
reads this Python-format export and verifies that delivery generation/merging
retains distinct physical stop identities on a 5 km Java network link.
