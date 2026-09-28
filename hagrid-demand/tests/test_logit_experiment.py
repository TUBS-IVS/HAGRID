import numpy as np
from hagrid_demand.logit_experiment import predict,fit
from hagrid_demand.model import predict_groups


def example():
    rng=np.random.default_rng(42)
    priors={'private':np.array([.5,.2,.1,.05,.05,.05,.05]),'business':np.array([.2,.1,.3,.1,.1,.1,.1]),
            'market':np.array([.4,.15,.15,.075,.075,.075,.075]),'b2b':.3}
    data={'population':rng.uniform(1,10,12),'business':rng.uniform(1,4,(12,2)),
          'branches':['C','G'],'dhl':rng.uniform(100,200,12),'hermes':rng.uniform(1,2,12)}
    cfg={'hermes_shape_weight':.2,'market_share_sd':.05,'b2b_share_sd':.07,'max_fit_evaluations':500}
    return data,priors,cfg


def test_zero_effect_exactly_reproduces_baseline_and_preserves_totals():
    data,priors,_=example()
    a,b=predict([1.,2.,0.],data,priors)
    c,d=predict_groups(np.array([1.,2.]),'pooled',data,priors)
    np.testing.assert_allclose(a,c);np.testing.assert_allclose(b,d)
    e,f=predict([1.,2.,.5],data,priors)
    np.testing.assert_allclose((a+b).sum(axis=1),(e+f).sum(axis=1))
    assert (f[:,0]>b[:,0]).all()


def test_heldout_observations_cannot_affect_logit_fit():
    data,priors,cfg=example();train=np.arange(12)<9
    a=fit(data,priors,cfg,train)
    changed={**data,'dhl':data['dhl'].copy(),'hermes':data['hermes'].copy()}
    changed['dhl'][~train]*=1000;changed['hermes'][~train]*=1000
    np.testing.assert_array_equal(a,fit(changed,priors,cfg,train))
