import numpy as np
import pandas as pd
import pytest
import geopandas as gpd
from shapely.geometry import LineString
from hagrid_demand.street_reconstruct import constrain_streets


def test_anchors_conserve_streets_and_keep_ambiguity_after_scope_filter():
    frame=pd.DataFrame({'site_id':['a','b','c','d','e'], 'plz':['1']*4+['2'],
                        'DHL':[2.,3.,4.,0.,7.], 'UPS':[1.,2.,3.,4.,5.]})
    obs=pd.DataFrame({'observation_id':['s1','s2','s3'], 'plz':['1']*3,'value':[100.,80.,25.],
                      'repeated_street_key':[False,False,True]})
    obs=gpd.GeoDataFrame(obs,geometry=[LineString([(0,0),(1,1)])]*3,crs=25832)
    links=pd.DataFrame({'site_id':['a','b','c','c','d'], 'observation_id':['s1','s1','s2','excluded','s2'],
        'candidate_count':[1,1,2,2,1], 'repeated_street_key':[False]*5,
        'link_status':['nearest_within_postal_unverified']*2+['equidistant_candidates']*2+['nearest_within_postal_unverified']})
    result,ledger=constrain_streets(frame,obs,links)
    np.testing.assert_allclose(result.DHL,[40,60,0,0,7])
    np.testing.assert_allclose(ledger.unallocated_DHL,[0,80,25])
    np.testing.assert_array_equal(result.UPS,frame.UPS)
    assert result.dhl_assignment_status.iloc[2]=='no_unique_eligible_street'
    assert result.dhl_assignment_status.iloc[-1]=='outside_observed_postal_coverage'
    assert ledger.allocation_status.iloc[-1]=='repeated_street_definition_unresolved'
    changed=obs.copy();changed.loc[0,'value']=0
    zero,_=constrain_streets(frame,changed,links)
    assert zero.DHL.iloc[:2].sum()==0
    with pytest.raises(ValueError): constrain_streets(frame,pd.concat([obs,obs]),links)


def test_cross_postal_link_is_not_used():
    frame=pd.DataFrame({'site_id':['a'],'plz':['2'],'DHL':[10.]})
    obs=pd.DataFrame({'observation_id':['s1'],'plz':['1'],'value':[100.],'repeated_street_key':[False]})
    links=pd.DataFrame({'site_id':['a'],'observation_id':['s1'],'candidate_count':[1],
                        'repeated_street_key':[False],'link_status':['nearest_within_postal_unverified']})
    result,ledger=constrain_streets(frame,obs,links)
    assert result.DHL.iloc[0]==10 and ledger.unallocated_DHL.iloc[0]==100
