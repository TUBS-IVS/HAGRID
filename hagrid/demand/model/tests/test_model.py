import numpy as np
import pandas as pd
import geopandas as gpd
import pytest
from shapely.geometry import Point
from hagrid_demand.forecast import calendar_factors
from hagrid_demand.model import unpack,predict_groups,PROVIDERS,fit_candidate
from hagrid_demand.delivery import build_delivery_access,delivery_counts,export_hagrid
from hagrid_demand.model import business_exposure


def test_business_size_transform_precedes_aggregation():
    exposure=business_exposure([4,9,0],.5)
    np.testing.assert_array_equal(exposure,[2,3,0])
    assert exposure.sum()!=np.sqrt(13)
    with pytest.raises(ValueError): business_exposure([1],0)


def test_calendar_normalized_leap_year_sundays_and_holiday():
    cfg={'weekday_weights':{'private':[1,1,1,1,1,1,0]},'monthly_weights':[1]*12,'holiday_dates':['2024-01-01'],'holiday_factor':0}
    factors=calendar_factors(2024,'private',cfg)
    assert len(factors)==366
    assert np.isclose(sum(factors.values()),1)
    assert factors['2024-01-01']==0 and factors['2024-01-07']==0


def test_joint_positive_conserved_and_local_carrier_profiles_differ():
    priors={'private':np.array([.7,.3,0,0,0,0,0]),'business':np.array([.2,.8,0,0,0,0,0])}
    data={'branches':['A'],'population':np.array([10,0]),'business':np.array([[0],[10]])}
    theta=np.zeros(2+1+12)
    C,B=predict_groups(theta,'joint',data,priors)
    assert np.all(C>=0) and np.all(B>=0)
    np.testing.assert_allclose((C+B).sum(axis=1),[10,10])
    assert C[0,0]>B[1,0]
    assert np.all((C+B)[:,2:]==0)


def test_delivery_mapping_preserves_private_and_business_counts(tmp_path):
    sites=gpd.GeoDataFrame({'site_id':['a','b']},geometry=[Point(0,0),Point(1,1)],crs=25832)
    mapping=tmp_path/'mapping.csv'
    mapping.write_text('site_id,carrier,delivery_point_id,x,y\na,DHL,locker,20,20\nb,DHL,locker,20,20\n')
    access=build_delivery_access(sites,{'delivery_mapping':str(mapping),'network_file':None})
    counts=pd.DataFrame({'site_id':['a','b'],**{'count_'+p:[2,3] if p=='DHL' else [0,0] for p in PROVIDERS}})
    result=delivery_counts(counts,access,PROVIDERS)
    assert result.packages.sum()==5 and len(result)==1
    assert result.delivery_point_id.item()=='locker'


def test_optional_network_modes_and_nearest_link(tmp_path):
    net=tmp_path/'net.xml'
    net.write_text('<network><nodes><node id="a" x="0" y="0"/><node id="b" x="100" y="0"/></nodes><links><link id="road" from="a" to="b" modes="car"/></links></network>')
    sites=gpd.GeoDataFrame({'site_id':['a']},geometry=[Point(10,2)],crs=25832)
    access=build_delivery_access(sites,{'network_file':str(net),'network_modes':['car'],'network_max_distance_m':20})
    assert access.network_link.item()=='road'


def test_training_does_not_use_held_out_target_values():
    prior={'private':np.ones(7)/7,'business':np.ones(7)/7,'market':np.ones(7)/7,'b2b':.3}
    data={'branches':['A'],'population':np.arange(1,11,dtype=float),'business':np.arange(10,0,-1,dtype=float)[:,None],
          'dhl':np.arange(1,11,dtype=float)*100,'hermes':np.arange(1,11,dtype=float)}
    cfg={'max_fit_evaluations':500,'hermes_shape_weight':.2,'market_share_sd':.05,'b2b_share_sd':.07,'branch_log_sd':.6,'carrier_log_sd':.5}
    train=np.arange(10)<7
    original=fit_candidate('joint',data,prior,cfg,train)
    data['dhl'][~train]*=1000;data['hermes'][~train]*=1000
    modified=fit_candidate('joint',data,prior,cfg,train)
    np.testing.assert_array_equal(original['theta'],modified['theta'])


def test_export_current_java_tag_semantics_and_mixed_destination(tmp_path):
    from shapely.geometry import box
    sites=gpd.GeoDataFrame({'site_id':['a','b']},geometry=[Point(0,0),Point(1,1)],crs=25832)
    mapping=tmp_path/'map.csv'
    mapping.write_text('site_id,carrier,delivery_point_id,x,y\na,DHL,locker,2,2\nb,DHL,locker,2,2\n')
    access=build_delivery_access(sites,{'delivery_mapping':str(mapping)})
    counts=pd.DataFrame({'site_id':['a','b'],'segment':['private','business'],**{'count_'+p:[2,3] if p=='DHL' else [0,0] for p in PROVIDERS}})
    result=delivery_counts(counts,access,PROVIDERS)
    assert result.service_event_proxy.sum()==1
    postal=gpd.GeoDataFrame({'plz':['12345']},geometry=[box(-5,-5,5,5)],crs=25832)
    target=tmp_path/'test.gpkg';check=export_hagrid(result,access,postal,target)
    exported=gpd.read_file(target)
    assert exported.dhl_tag.item()==2 and exported.dhl_type.item()==3
    assert exported.total.item()==5 and exported.postal_cod.item()=='12345'
    assert check['packages']==5
