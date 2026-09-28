"""Target-independent OSM land-use areas; source vintage must be documented."""
from pathlib import Path
import pandas as pd
import geopandas as gpd
from shapely import from_wkt


def areas(path,source,groups):
    raw=pd.read_csv(path)
    land=gpd.GeoDataFrame(raw[['fclass']],geometry=from_wkt(raw.geometry),crs=25832)
    land=land.loc[land.fclass.isin(['residential','industrial','commercial','retail'])].copy()
    land.geometry=land.geometry.make_valid()
    # Union within each class prevents duplicate polygons from counting twice.
    land=land.dissolve(by='fclass').reset_index()
    postal=gpd.read_parquet(Path(source)/'postal_support.parquet')[['plz','geometry']].to_crs(25832)
    cut=gpd.overlay(postal,land,how='intersection',keep_geom_type=False)
    cut['area_km2']=cut.geometry.area/1e6
    result=cut.pivot_table(index='plz',columns='fclass',values='area_km2',aggfunc='sum',fill_value=0)
    return result.reindex(index=groups,columns=['residential','industrial','commercial','retail']).fillna(0)
