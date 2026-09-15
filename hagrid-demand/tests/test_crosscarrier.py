import numpy as np
import pytest
from hagrid_demand.model_search import predict_candidate


@pytest.mark.parametrize('kind',['hermes_ratio','hermes_blend','hermes_regression'])
def test_crosscarrier_uses_test_hermes_but_never_test_dhl(kind):
    rng=np.random.default_rng(42);x=rng.uniform(1,5,(15,3));train=np.arange(15)<10
    priors={'private':np.ones(7)/7,'business':np.ones(7)/7,'market':np.ones(7)/7,'b2b':.3}
    data={'population':x[:,0],'business':x[:,1:],'branches':['C','G'],'dhl':50*x[:,0],'hermes':x[:,2]}
    cfg={'hermes_shape_weight':.2,'market_share_sd':.05,'b2b_share_sd':.07,'max_fit_evaluations':500}
    a=predict_candidate(kind,x,data,priors,cfg,train)
    changed={**data,'dhl':data['dhl'].copy()};changed['dhl'][~train]*=100
    np.testing.assert_array_equal(a,predict_candidate(kind,x,changed,priors,cfg,train))
    changed={**data,'hermes':data['hermes'].copy()};changed['hermes'][~train]*=2
    b=predict_candidate(kind,x,changed,priors,cfg,train)
    assert not np.allclose(a[~train],b[~train])
