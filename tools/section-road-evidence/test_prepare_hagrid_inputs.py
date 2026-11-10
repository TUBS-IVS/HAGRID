import os
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import geopandas as gpd
from shapely.geometry import LineString, Point

import prepare_hagrid_inputs as producer
from prepare_hagrid_inputs import prepare

FIXTURE_ROOT = Path(os.environ['HAGRID_TEST_FIXTURE_ROOT']) if os.getenv('HAGRID_TEST_FIXTURE_ROOT') else None
SOURCE_ROOT = Path(os.environ['HAGRID_TEST_SOURCE_ROOT']) if os.getenv('HAGRID_TEST_SOURCE_ROOT') else None
FIXTURE_NETWORK = (FIXTURE_ROOT / '.superpowers/hagrid2-auto-evidence-20261110/tests/regional-network.xml.gz'
                   if FIXTURE_ROOT else None)
REAL_FIXTURES_AVAILABLE = bool(
    FIXTURE_ROOT and SOURCE_ROOT
    and FIXTURE_NETWORK.is_file()
    and (FIXTURE_ROOT / '.superpowers/hagrid-only-20261110/geometry/carriers-7.xml').is_file()
    and (FIXTURE_ROOT / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml').is_file()
    and (SOURCE_ROOT / 'reference_stops.parquet').is_file()
    and (SOURCE_ROOT / 'sources/dhl_observations.parquet').is_file()
)


class FixedOohPreparationTest(unittest.TestCase):
    def test_registered_ooh_and_home_share_a_link_without_inventing_poi_halt(self):
        x, y = 500_000.0, 5_800_000.0
        point_id, kind = 'fixture-locker', 'locker'
        poi = Point(x, y)
        with tempfile.TemporaryDirectory(dir=os.getenv('HAGRID_TEST_SCRATCH')) as directory:
            root = Path(directory)
            network = root / 'network.xml'
            network.write_text(f'<network><nodes><node id="a" x="{x-50}" y="{y+10}"/>'
                               f'<node id="b" x="{x+50}" y="{y+10}"/></nodes>'
                               '<links effectivelanewidth="3.75"><link id="road" from="a" to="b" '
                               'length="100" modes="car"/></links></network>')
            ooh_id = 'ooh:' + point_id
            carriers = root / 'carrier.xml'
            carriers.write_text('<carriers><carrier id="mixed"><capabilities><vehicles>'
                                '<vehicle depotLinkId="road"/></vehicles></capabilities><services>'
                                f'<service id="home" to="road"><attributes><attribute name="carrierMode">HOME</attribute>'
                                f'<attribute name="stop_id">stp:home</attribute><attribute name="side">right</attribute>'
                                f'<attribute name="coord">({x-20};{y+10})</attribute>'
                                '</attributes></service>'
                                f'<service id="ooh" to="road"><attributes><attribute name="carrierMode">PARCEL_LOCKER_EXISTING</attribute>'
                                f'<attribute name="stop_id">{ooh_id}</attribute><attribute name="coord">({x};{y})</attribute>'
                                '</attributes></service></services></carrier></carriers>')
            stops = gpd.GeoDataFrame({'stop_id': ['stp:home', ooh_id], 'str_idx': [1, -1],
                                      'part': [0.0, float('nan')], 'side': ['right', None],
                                      'stop_type': ['home', kind],
                                      'point_id': [None, point_id]},
                                     geometry=[Point(x-20, y+10), poi], crs='EPSG:25832')
            stops.to_parquet(root / 'stops.parquet')
            streets = gpd.GeoDataFrame({'source_row': [1], 'street': ['test']},
                                       geometry=[LineString(((x-50,y+10),(x+50,y+10)))], crs='EPSG:25832')
            streets.to_parquet(root / 'streets.parquet')
            report, catalogs = prepare(network, [carriers], root / 'stops.parquet', root / 'streets.parquet')
            original = producer.read_carriers

            def missing_ooh_identity(*paths):
                parsed = original(*paths)
                parsed[0]['jobs'][1]['stopId'] = None
                return parsed

            with patch.object(producer, 'read_carriers', side_effect=missing_ooh_identity):
                rejected, unpublished = prepare(network, [carriers], root / 'stops.parquet', root / 'streets.parquet',
                                                allow_legacy_coordinate_recovery=True)
            def legacy_locker_mode(*paths):
                parsed = original(*paths)
                parsed[0]['jobs'][1]['mode'] = 'PARCEL_LOCKER'
                return parsed

            with patch.object(producer, 'read_carriers', side_effect=legacy_locker_mode):
                legacy_report, legacy_catalogs = prepare(network, [carriers], root / 'stops.parquet', root / 'streets.parquet')
        self.assertEqual(2, report['supportedJobs'])
        jobs = {job['jobId']: job for job in catalogs['mixed']['jobs']}
        self.assertEqual([{'linkId': 'road', 'fraction': 1.0}], jobs['ooh']['approaches'])
        self.assertTrue(jobs['ooh']['fixedOriginalLink'])
        self.assertEqual([x, y], jobs['ooh']['sourceXY'])
        self.assertNotIn('fixedOriginalLink', jobs['home'])
        self.assertEqual({}, unpublished)
        self.assertIn('OOH_STOP_ID_REQUIRED', rejected['carriers'][0]['jobs'][1]['reasons'])
        self.assertEqual(2, legacy_report['supportedJobs'])
        self.assertEqual('PARCEL_LOCKER', legacy_catalogs['mixed']['jobs'][1]['serviceMode'])


class ReverseLineageTest(unittest.TestCase):
    def test_hyphenated_raw_does_not_prove_reverse_and_shared_way_does(self):
        x, y = 500_000.0, 5_800_000.0
        with tempfile.TemporaryDirectory(dir=os.getenv('HAGRID_TEST_SCRATCH')) as directory:
            root = Path(directory)
            stops = gpd.GeoDataFrame({'stop_id': ['stp:1'], 'str_idx': [1], 'part': [0.0], 'side': ['right']},
                                     geometry=[Point(x+25,y)], crs='EPSG:25832')
            streets = gpd.GeoDataFrame({'source_row': [1], 'street': ['Example']},
                                       geometry=[LineString(((x,y),(x+100,y)))], crs='EPSG:25832')
            stops.to_parquet(root/'stops.parquet'); streets.to_parquet(root/'streets.parquet')
            carriers = root/'carrier.xml'
            carriers.write_text('<carriers><carrier id="c"><capabilities><vehicles><vehicle depotLinkId="raw-a"/>'
                                '</vehicles></capabilities><services><service id="j" to="raw-a"><attributes>'
                                '<attribute name="carrierMode">HOME</attribute><attribute name="stop_id">stp:1</attribute>'
                                f'<attribute name="side">right</attribute><attribute name="coord">({x+25};{y})</attribute>'
                                '</attributes></service></services></carrier></carriers>')
            network = root/'network.xml'
            def write_network(way):
                attr = '<attributes><attribute name="osm:way:id">w1</attribute></attributes>' if way else ''
                network.write_text(f'<network><nodes><node id="a" x="{x}" y="{y}"/>'
                                   f'<node id="b" x="{x+100}" y="{y}"/></nodes><links effectivelanewidth="3.75">'
                                   f'<link id="raw-a" from="a" to="b" length="100" modes="car">{attr}</link>'
                                   f'<link id="other-b" from="b" to="a" length="100" modes="car">{attr}</link>'
                                   '</links></network>')
            write_network(False)
            report, catalogs = prepare(network, [carriers], root/'stops.parquet', root/'streets.parquet', allow_street_crossing=True)
            self.assertEqual(['raw-a'], [a['linkId'] for a in catalogs['c']['jobs'][0]['approaches']])
            stops['side'] = 'left'; stops.to_parquet(root/'stops.parquet')
            carriers.write_text(carriers.read_text().replace('>right<','>left<'))
            report, catalogs = prepare(network, [carriers], root/'stops.parquet', root/'streets.parquet')
            self.assertEqual({}, catalogs)
            self.assertIn('CUSTOMER_SIDE_REQUIRES_REVERSE_ONLY_UNSUPPORTED', report['carriers'][0]['jobs'][0]['reasons'])
            write_network(True)
            report, catalogs = prepare(network, [carriers], root/'stops.parquet', root/'streets.parquet')
            self.assertEqual(['other-b'], [a['linkId'] for a in catalogs['c']['jobs'][0]['approaches']])


    def test_remapped_composed_candidates_need_shared_lineage(self):
        x, y = 500_000.0, 5_800_000.0
        with tempfile.TemporaryDirectory(dir=os.getenv('HAGRID_TEST_SCRATCH')) as directory:
            root = Path(directory)
            stops = gpd.GeoDataFrame({'stop_id': ['stp:1'], 'str_idx': [1], 'part': [0.0], 'side': ['right']},
                                     geometry=[Point(x+25,y)], crs='EPSG:25832')
            streets = gpd.GeoDataFrame({'source_row': [1, 2], 'street': ['First', 'Second']},
                                       geometry=[LineString(((x,y),(x+50,y))), LineString(((x+50,y),(x+100,y)))], crs='EPSG:25832')
            stops.to_parquet(root/'stops.parquet'); streets.to_parquet(root/'streets.parquet')
            carriers = root/'carrier.xml'
            carriers.write_text('<carriers><carrier id="c"><capabilities><vehicles><vehicle depotLinkId="wrong"/>'
                                '</vehicles></capabilities><services><service id="j" to="wrong"><attributes>'
                                '<attribute name="carrierMode">HOME</attribute><attribute name="stop_id">stp:1</attribute>'
                                f'<attribute name="side">right</attribute><attribute name="coord">({x+25};{y})</attribute>'
                                '</attributes></service></services></carrier></carriers>')
            network = root/'network.xml'
            def write_network(way):
                attr = '<attributes><attribute name="osm:way:id">shared</attribute></attributes>' if way else ''
                network.write_text(f'<network><nodes><node id="a" x="{x}" y="{y}"/><node id="b" x="{x+100}" y="{y}"/>'
                                   f'<node id="c" x="{x}" y="{y+100}"/><node id="d" x="{x+100}" y="{y+100}"/>'
                                   '</nodes><links effectivelanewidth="3.75"><link id="wrong" from="c" to="d" length="100" modes="car"/>'
                                   f'<link id="f-a" from="a" to="b" length="100" modes="car">{attr}</link>'
                                   f'<link id="r-b" from="b" to="a" length="100" modes="car">{attr}</link></links></network>')
            write_network(False)
            report, catalogs = prepare(network, [carriers], root/'stops.parquet', root/'streets.parquet', allow_street_crossing=True)
            self.assertEqual({}, catalogs)
            self.assertIn('UNIQUE_HAGRID_ALTERNATIVE_NOT_FOUND', report['carriers'][0]['jobs'][0]['reasons'])
            write_network(True)
            report, catalogs = prepare(network, [carriers], root/'stops.parquet', root/'streets.parquet', allow_street_crossing=True)
            self.assertEqual({'f-a','r-b'}, {a['linkId'] for a in catalogs['c']['jobs'][0]['approaches']})


@unittest.skipUnless(REAL_FIXTURES_AVAILABLE,
                     'set HAGRID_TEST_FIXTURE_ROOT and HAGRID_TEST_SOURCE_ROOT for local Hannover regression data')
class HagridPreparationTest(unittest.TestCase):
    def test_fixture_reports_every_job_and_keeps_near_straight_source_curve(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        inputs = [root / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml']
        report, catalogs = prepare(FIXTURE_NETWORK, inputs,
                                   source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                                   allow_street_crossing=True, allow_legacy_coordinate_recovery=True)
        self.assertEqual(42, report['totalJobs'])
        # One alternative composed road has two unproven directions: do not publish a partial catalog.
        self.assertEqual(41, report['supportedJobs'])
        self.assertEqual({}, catalogs)
        rows = {j['jobId']: j for c in report['carriers'] for j in c['jobs']}
        self.assertIn('UNIQUE_HAGRID_ALTERNATIVE_NOT_FOUND', rows['service_B2B_ups_30627_88']['reasons'])
        self.assertEqual(2, rows['service_B2B_ups_30627_88']['alternatePhysicalRoadGroups'])
        self.assertEqual([], rows['service_B2B_ups_30627_55']['reasons'])
        self.assertGreater(rows['service_B2B_ups_30627_55']['rawGeometry']['sourceChordHausdorffMeters'], 0.49)
        self.assertEqual([], rows['service_B2C_dhl_30559_1_396']['reasons'])

    def test_customer_side_only_uses_verified_reverse_without_crossing(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        report, catalogs = prepare(FIXTURE_NETWORK,
                                   [root / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml'],
                                   source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                                   allow_street_crossing=False, allow_legacy_coordinate_recovery=True)
        rows = {j['jobId']: j for c in report['carriers'] for j in c['jobs']}
        self.assertEqual([], rows['service_B2C_dhl_30559_1_193']['reasons'])
        self.assertEqual([{'linkId': '1626480', 'fraction': rows['service_B2C_dhl_30559_1_193']['approaches'][0]['fraction']}],
                         rows['service_B2C_dhl_30559_1_193']['approaches'])
        self.assertEqual(38, report['supportedJobs'])
        self.assertEqual({}, catalogs)
        unsupported = {j['jobId']: j for j in rows.values() if j['status'] != 'supported'}
        reverse_only = {
            'service_B2C_ups_30627_7': '1323019-218250',
            'service_B2C_gls_30539_52': '882515-882519-952259',
            'service_B2B_dhl_30559_1_313': '1118402-3630556-1382516-3630560-594524',
        }
        self.assertEqual(set(reverse_only) | {'service_B2B_ups_30627_88'}, set(unsupported))
        _, raw_links, reverse = producer.read_network(FIXTURE_NETWORK, set(reverse_only.values()))
        for job_id, link_id in reverse_only.items():
            row = unsupported[job_id]
            self.assertIn('CUSTOMER_SIDE_REQUIRES_REVERSE_ONLY_UNSUPPORTED', row['reasons'])
            raw = raw_links[link_id]
            self.assertFalse(raw['attributes'].get('osm:way:id'))
            candidates = reverse[(raw['from'], raw['to'])]
            self.assertTrue(any(abs(float(c['length'])-float(raw['length'])) < 0.01 and
                                not c['attributes'].get('osm:way:id') for c in candidates))
        self.assertIn('UNIQUE_HAGRID_ALTERNATIVE_NOT_FOUND', unsupported['service_B2B_ups_30627_88']['reasons'])

    def test_exported_side_mismatch_blocks_publication(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        existing = root / '.superpowers/hagrid-only-20261110/geometry/carriers-7.xml'
        original = producer.read_carriers

        def with_wrong_side(*args):
            carriers = original(*args)
            for carrier in carriers:
                for job in carrier['jobs']:
                    if job['id'] == 'service_B2C_ups_30627_75':
                        job['attributes']['side'] = 'left'
            return carriers

        with patch.object(producer, 'read_carriers', side_effect=with_wrong_side):
            report, catalogs = prepare(FIXTURE_NETWORK, [existing],
                                       source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                                       allow_street_crossing=True, allow_legacy_coordinate_recovery=True)
        rows = {j['jobId']: j for c in report['carriers'] for j in c['jobs']}
        self.assertIn('EXPORTED_SIDE_MISMATCH', rows['service_B2C_ups_30627_75']['reasons'])
        self.assertEqual({}, catalogs)

    def test_missing_reverse_keeps_one_certified_raw_approach(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        existing = root / '.superpowers/hagrid-only-20261110/geometry/carriers-7.xml'
        original = producer.read_network

        def without_reverse(*args):
            nodes, links, reverse = original(*args)
            reverse[('369566772', '2018189867')] = []  # UPS 75 raw link 224967
            return nodes, links, reverse

        with patch.object(producer, 'read_network', side_effect=without_reverse):
            report, catalogs = prepare(FIXTURE_NETWORK, [existing],
                                       source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                                       allow_street_crossing=True, allow_legacy_coordinate_recovery=True)
        rows = {j['jobId']: j for c in report['carriers'] for j in c['jobs']}
        self.assertEqual([], rows['service_B2C_ups_30627_75']['reasons'])
        self.assertFalse(rows['service_B2C_ups_30627_75']['reverseAvailable'])
        self.assertEqual([{'linkId': '224967', 'fraction': rows['service_B2C_ups_30627_75']['approaches'][0]['fraction']}],
                         rows['service_B2C_ups_30627_75']['approaches'])
        self.assertEqual(7, report['supportedJobs'])
        self.assertEqual(3, len(catalogs))

    def test_hagrid_source_curve_keeps_model_length_and_vertices(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        report, _ = prepare(FIXTURE_NETWORK,
                            [root / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml'],
                            source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                            allow_street_crossing=True, allow_legacy_coordinate_recovery=True)
        rows = {j['jobId']: j for c in report['carriers'] for j in c['jobs']}
        self.assertEqual([], rows['service_B2B_ups_30627_100']['reasons'])
        self.assertGreater(rows['service_B2B_ups_30627_100']['rawGeometry']['networkChordResidualMeters'], 50)
        self.assertEqual('H03_BRANCH_JUNCTION_TO_ORIGINAL_CAR_LINK', rows['service_B2B_ups_30627_71']['anchorEvidence'])

    def test_connected_hagrid_parts_reconstruct_single_model_link(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        report, _ = prepare(FIXTURE_NETWORK,
                            [root / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml'],
                            source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                            allow_street_crossing=True, allow_legacy_coordinate_recovery=True)
        rows = {j['jobId']: j for c in report['carriers'] for j in c['jobs']}
        self.assertEqual([], rows['service_B2C_ups_30627_9']['reasons'])
        self.assertEqual([], rows['service_B2C_gls_30539_52']['reasons'])
        self.assertEqual('H03_BRANCH_JUNCTION_TO_ORIGINAL_CAR_LINK', rows['service_B2C_ups_30627_49']['anchorEvidence'])

    def test_exact_hagrid_alternative_preserves_original_assignment(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        select = {'ups_30627': {'service_B2C_ups_30627_54'},
                  'gls_30539': {'service_B2C_gls_30539_145', 'service_B2C_gls_30539_169'},
                  'dhl_30559_1': {'service_B2C_dhl_30559_1_396'}}
        report, catalogs = prepare(FIXTURE_NETWORK,
                                   [root / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml'],
                                   source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                                   allow_street_crossing=True, allow_legacy_coordinate_recovery=True,
                                   selected_jobs=select)
        self.assertEqual(4, report['supportedJobs'])
        jobs = {j['jobId']: j for j in catalogs['gls_30539']['jobs']}
        remap = jobs['service_B2C_gls_30539_145']
        self.assertEqual('2637566', remap['rawLinkId'])
        self.assertEqual('3640365', remap['anchorLinkId'])
        self.assertTrue(remap['sourceStopId'].startswith('stp:'))
        self.assertEqual(2, len(remap['sourceXY']))

    def test_network_lane_corridor_registers_unique_same_road_offsets(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        report, _ = prepare(FIXTURE_NETWORK,
                            [root / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml'],
                            source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                            allow_street_crossing=True, allow_legacy_coordinate_recovery=True)
        rows = {j['jobId']: j for c in report['carriers'] for j in c['jobs']}
        for job_id in ('service_B2C_ups_30627_65', 'service_B2C_gls_30539_122'):
            self.assertEqual([], rows[job_id]['reasons'])
            self.assertEqual('MODEL_LANE_CORRIDOR_H03_REGISTERED', rows[job_id]['anchorEvidence'])
            self.assertLessEqual(rows[job_id]['anchorGeometry']['endpointResidualMeters'], 1.875)
        self.assertEqual('H03_BRANCH_JUNCTION_TO_ORIGINAL_CAR_LINK', rows['service_B2B_ups_30627_71']['anchorEvidence'])

    def test_two_hagrid_streets_reconstruct_composed_link_without_chord_guess(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        report, _ = prepare(FIXTURE_NETWORK,
                            [root / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml'],
                            source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                            allow_street_crossing=True, allow_legacy_coordinate_recovery=True)
        rows = {j['jobId']: j for c in report['carriers'] for j in c['jobs']}
        for job_id in ('service_B2C_ups_30627_7', 'service_B2B_ups_30627_96'):
            self.assertEqual([], rows[job_id]['reasons'])
            self.assertTrue(rows[job_id]['sourceGeometryMethod'] == 'TWO_CONNECTED_H03_STREETS' or
                            rows[job_id].get('anchorEvidence') == 'TWO_CONNECTED_H03_STREETS')
        self.assertIn('UNIQUE_HAGRID_ALTERNATIVE_NOT_FOUND', rows['service_B2B_ups_30627_88']['reasons'])

    def test_branch_junction_keeps_customer_coordinate_and_original_service_time(self):
        root = FIXTURE_ROOT
        source = SOURCE_ROOT
        report, catalogs = prepare(FIXTURE_NETWORK,
                                   [root / '.superpowers/hagrid-only-20261110/geometry/carriers-42.xml'],
                                   source / 'reference_stops.parquet', source / 'sources/dhl_observations.parquet',
                                   allow_street_crossing=True, allow_legacy_coordinate_recovery=True,
                                   selected_jobs={'ups_30627': {'service_B2C_ups_30627_49', 'service_B2B_ups_30627_71'},
                                                  'gls_30539': set(), 'dhl_30559_1': set()})
        self.assertEqual(2, report['supportedJobs'])
        jobs = {j['jobId']: j for j in catalogs['ups_30627']['jobs']}
        for job_id in ('service_B2C_ups_30627_49', 'service_B2B_ups_30627_71'):
            job = jobs[job_id]
            self.assertNotEqual(job['sourceXY'], job['anchorXY'])
            self.assertGreater(len(job['sourceStreetXY']), 2)
            self.assertEqual(job['rawLinkId'], job['anchorLinkId'])


if __name__ == '__main__':
    unittest.main()
