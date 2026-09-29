import geopandas as gpd
import numpy as np
import pytest
from shapely.geometry import box, LineString
from hagrid_demand.logistics_osm import parse, postal_features, fetch_part, query, street_distances


def test_snapshot_cache_separates_queries_and_rejects_server_errors(tmp_path):
    calls=[]
    def fetch(endpoint,q):
        calls.append(q)
        return {'elements': []}
    fetch_part(tmp_path,'2021',(52,9,53,10),'warehouse',fetch)
    fetch_part(tmp_path,'2021',(52,9,53,10),'warehouse',fetch)
    fetch_part(tmp_path,'current',(52,9,53,10),'warehouse',fetch)
    fetch_part(tmp_path,'2021',(52,9,53,11),'warehouse',fetch)
    assert len(calls)==3
    assert '[date:"2021-12-31T23:59:59Z"]' in calls[0]
    assert '[date:' not in calls[1]
    with pytest.raises(ValueError,match='timed out'):
        fetch_part(tmp_path,'2021',(52,9,53,10),'industrial',lambda e,q:{'elements':[], 'remark':'timed out'})
    assert not list(tmp_path.glob('*industrial*.json')) or all('fetch' in p.name for p in tmp_path.glob('*industrial*.json'))
    with pytest.raises(ValueError): query('2025')


def test_osm_empty_duplicates_and_building_use():
    assert parse({'elements':[]}).empty
    item={'type':'way','id':1,'tags':{'building':'industrial','building:use':'warehouse'},
          'geometry':[{'lat':52.,'lon':9.},{'lat':52.,'lon':9.001},{'lat':52.001,'lon':9.001},{'lat':52.,'lon':9.}]}
    frame=parse({'elements':[item,item]})
    assert len(frame)==1 and frame.iloc[0].warehouse_building
    assert frame.iloc[0].area_m2>0
    relation={'type':'relation','id':2,'tags':{'building':'warehouse'},'center':{'lat':52.,'lon':9.}}
    out=parse({'elements':[relation]})
    assert out.iloc[0].geometry_kind=='relation_center' and out.iloc[0].area_m2==0


def test_union_features_and_empty_nearest_do_not_invent_coverage():
    osm=gpd.GeoDataFrame({'warehouse_building':[True,True], 'building':['warehouse','warehouse'],
        'geometry_kind':['polygon','polygon'],'candidate_type':['explicit_warehouse_or_logistics']*2},geometry=[box(0,0,10,10)]*2,crs=25832)
    postal=gpd.GeoDataFrame({'plz':['1','2']},geometry=[box(0,0,5,10),box(5,0,15,10)],crs=25832)
    out=postal_features(osm,postal)
    np.testing.assert_allclose(out.warehouse_m2,[50,50])
    streets=gpd.GeoDataFrame({'observation_id':['a'],'street':['a'],'value':[10.]},geometry=[LineString([(0,0),(1000,0)])],crs=25832)
    missing=street_distances(streets,parse({'elements':[]}))
    assert np.isnan(missing.distance_m.iloc[0])
    osm['osm_key']=['way/1','way/2']
    distances=street_distances(streets,osm)
    assert len(distances)==1 and distances.distance_m.iloc[0]==0
    assert distances.midpoint_distance_m.iloc[0]==490
