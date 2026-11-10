import geopandas as gpd
import pandas as pd
import pytest
from shapely.geometry import Point, LineString
from hagrid_demand.baseline.stops import build_stops
from hagrid_demand.compatibility.matsim_export import write_matsim_day

def test_stop_position_and_side_survive_demand_shapefile(tmp_path):
    streets = gpd.GeoDataFrame({'sid':[7]}, geometry=[LineString([(0,0),(100,0)])], crs='EPSG:25832')
    units = gpd.GeoDataFrame({'building_key':['a','b','off'],'plz':['30159']*3,'sid':[7,7,-1], 'part':[0,0,None], 'side':['left','left',None], 'position_m':[10.,30.,None]}, geometry=[Point(10,2),Point(30,2),Point(90,90)],crs=streets.crs)
    stops, mapping = build_stops(units, pd.Series({'a':1.,'b':3.,'off':1.}), streets)
    on = stops.loc[stops.str_idx.eq(7)].iloc[0]
    assert on.position_m == pytest.approx(25.)
    chunk = pd.DataFrame({'date':pd.Timestamp('2025-05-13'),'site_id':['a','off'],'plz':['30159']*2,'segment':['private']*2,'carrier':['DHL']*2,'count':[5,1]})
    sites = units.rename(columns={'building_key':'site_id'})[['site_id','geometry']]
    ledger = write_matsim_day(chunk, sites, tmp_path, stops={'stops':stops,'site_stops':mapping.rename(columns={'building_key':'site_id'})}, max_parcels_per_row=2)
    frame = gpd.read_file(tmp_path / ledger['file'])
    rows = frame.loc[frame.str_idx.eq(7)]
    assert len(rows) == 3
    assert rows.side.eq('left').all() and rows.part.eq(0).all()
    assert rows.position_m.eq(25.).all() and rows.geometry.eq(Point(25,0)).all()
    off = frame.loc[frame.str_idx.eq(-1)].iloc[0]
    assert pd.isna(off.side) and pd.isna(off.part) and pd.isna(off.position_m)
