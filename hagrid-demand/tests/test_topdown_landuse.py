import numpy as np
import pandas as pd
import geopandas as gpd
import pytest
from shapely.geometry import box
from hagrid_demand.topdown import normalize_to_total
from hagrid_demand.landuse import areas


def test_topdown_changes_total_not_relative_shape():
    a=np.array([1.,2.,3.]);b=normalize_to_total(a,60)
    np.testing.assert_array_equal(b,[10.,20.,30.])
    np.testing.assert_array_equal(a,[1.,2.,3.])
    with pytest.raises(ValueError): normalize_to_total([0,0],10)


def test_landuse_union_does_not_double_count_duplicates(tmp_path):
    gpd.GeoDataFrame({'plz':['1']},geometry=[box(0,0,1000,1000)],crs=25832).to_parquet(tmp_path/'postal_support.parquet')
    path=tmp_path/'land.csv'
    pd.DataFrame({'fclass':['industrial']*2,'geometry':[box(0,0,1000,1000).wkt]*2}).to_csv(path,index=False)
    result=areas(path,tmp_path,['1'])
    assert result.loc['1','industrial']==1
