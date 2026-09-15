import numpy as np
import pytest
import pandas as pd
from hagrid_demand.carriers import balance,stable_preferences
from hagrid_demand.carriers import build_profiles


def test_preferences_stable_under_reorder_and_additions():
    a=stable_preferences(['a','b'],42)
    b=stable_preferences(['c','b','a'],42)
    np.testing.assert_array_equal(a,b[[2,1]])
    assert not np.array_equal(a,stable_preferences(['a','b'],43))


def test_balancing_preserves_site_totals_and_requested_margins():
    p=np.array([[.4,.1,.1,.1,.1,.1,.1],[.1,.4,.1,.1,.1,.1,.1]])
    w=np.array([10.,20.]);target=np.array([12.,3.,3.,3.,3.,3.,3.])
    q=balance(p,w,target)
    np.testing.assert_allclose(q.sum(axis=1),1)
    np.testing.assert_allclose(w@q,target,atol=1e-6)
    assert (q>=0).all()
    assert not np.allclose(q[0],q[1])
    with pytest.raises(ValueError): balance(p,w,target*2)


def test_local_evidence_is_conditional_and_infeasible_targets_are_flagged(tmp_path):
    source=tmp_path/'source';source.mkdir();output=tmp_path/'out';output.mkdir()
    pd.DataFrame({'plz':['1'],'year':[2021],'value':[1000.]}).to_parquet(source/'dhl_observations.parquet')
    pd.DataFrame({'plz':['1'],'year':[2021],'value':[1.]}).to_parquet(source/'hermes_observations.parquet')
    sites=pd.DataFrame({'site_id':['a','b'],'plz':['1','1'],'recipient_type':['business','business'],'branch':['C','G']})
    shares=[np.ones(7)/7,np.ones(7)/7]
    cfg={'seed':42,'reference_year':2021,'foundation_run':str(source),
         'carrier_allocation':{'enabled':True,'max_local_multiplier':1000,'preference_log_sd':{'business':.5},'local_strength':{'DHL':1}}}
    q=build_profiles(sites,np.array([10.,20.]),shares,cfg,output)
    np.testing.assert_allclose(q.sum(axis=1),1)
    assert np.isclose(np.array([10.,20.])@q[:,0],29.4,atol=1e-6)
    report=pd.read_csv(output/'carrier_local_diagnostics.csv')
    assert report.target_capped.all()
