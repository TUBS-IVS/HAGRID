import numpy as np
import pytest
from threadpoolctl import threadpool_limits
from hagrid_demand.model_search import predict_candidate


@pytest.mark.parametrize('kind',['positive_ridge','log_ridge_10','poisson_10','splines','forest','boosting','group_logit','spatial_5','spatial_15','hybrid_log','hybrid_additive','hybrid_blend','person_residual_10','person_spatial_10','person_spatial_100','landuse_residual_100','landuse_spatial_100','logistics_residual_10','logistics_residual_100','logistics_spatial_100','logistics_additive_10'])
def test_search_candidates_do_not_fit_heldout_targets(kind):
    rng=np.random.default_rng(18);x=rng.uniform(0,10,(24,5));train=np.arange(24)<18
    priors={'private':np.array([.5,.2,.1,.05,.05,.05,.05]),'business':np.array([.2,.1,.3,.1,.1,.1,.1]),
            'market':np.array([.4,.15,.15,.075,.075,.075,.075]),'b2b':.3}
    data={'population':x[:,0],'business':x[:,1:3],'branches':['C','G'],'coords':x[:,:2]*1000,'demographics':x[:,:3]/10,'landuse_demographics':x/10,
          'logistics_demographics':x/10,'dhl':100+x[:,0]*10,'hermes':1+x[:,1]}
    cfg={'hermes_shape_weight':.2,'market_share_sd':.05,'b2b_share_sd':.07,'max_fit_evaluations':500}
    changed={**data,'dhl':data['dhl'].copy(),'hermes':data['hermes'].copy()}
    changed['dhl'][~train]*=100;changed['hermes'][~train]*=100
    with threadpool_limits(limits=1):
        a=predict_candidate(kind,x,data,priors,cfg,train)
        b=predict_candidate(kind,x,changed,priors,cfg,train)
    np.testing.assert_array_equal(a,b)
    assert np.isfinite(a).all() and (a>=0).all()
