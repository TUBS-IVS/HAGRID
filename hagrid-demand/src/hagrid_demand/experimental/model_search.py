"""Exploratory nested model search with random and spatially blocked evaluation."""
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
import geopandas as gpd
from scipy.optimize import least_squares
from scipy.special import softmax
from sklearn.cluster import KMeans
from sklearn.linear_model import Ridge,PoissonRegressor
from sklearn.ensemble import RandomForestRegressor,HistGradientBoostingRegressor
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler,SplineTransformer
from threadpoolctl import threadpool_limits
from .model import prepare_training,load_legacy,fit_candidate,predict_groups,metrics
from .workflow import config
from .diagnostics import special_offsets
from ..pipeline import digest
from ..data import write_json


CANDIDATES=['baseline','joint','group_logit','positive_ridge','log_ridge_10','log_ridge_100',
            'poisson_10','poisson_100','splines','forest','boosting','spatial_5','spatial_15']


def features(source,sites,data):
    groups=data['plz'];biz=sites.loc[sites.recipient_type.eq('business')].copy()
    biz['sqrt_employees']=np.sqrt(biz.employees)
    biz['log_employees']=np.log1p(biz.employees)
    biz['large']=biz.employees.ge(50).astype(int)
    x=pd.DataFrame(index=groups)
    x['population']=data['population']*1000
    x['residential_sites']=sites.loc[sites.recipient_type.eq('private')].groupby('plz').size().reindex(groups).fillna(0)
    for col in ['employees','sqrt_employees','log_employees','large']:
        x[col]=biz.groupby('plz')[col].sum().reindex(groups).fillna(0)
    x['business_sites']=biz.groupby('plz').size().reindex(groups).fillna(0)
    for j,branch in enumerate(data['branches']): x['branch_'+branch]=data['business'][:,j]*1000
    postal=gpd.read_parquet(Path(source)/'postal_support.parquet').set_index('plz').reindex(groups)
    x['area_km2']=postal.geometry.area/1e6
    x['population_density']=x.population/np.maximum(x.area_km2,.001)
    x['business_density']=x.business_sites/np.maximum(x.area_km2,.001)
    if not np.isfinite(x.to_numpy()).all(): raise ValueError('Invalid model features')
    coords=np.c_[postal.geometry.centroid.x,postal.geometry.centroid.y]
    return x,coords


def grouped_logit(data,priors,cfg,train):
    groups=[0 if b in {'C','H'} else 1 if b=='G' else 2 if b in {'J','K','L','M','N'} else 3 for b in data['branches']]
    base=fit_candidate('pooled',data,priors,cfg,train)
    def prediction(t):
        shift=np.zeros((len(groups),7));shift[:,:2]=t[2:].reshape(4,2)[groups]
        logits=np.full(7,-np.inf);active=priors['business']>0;logits[active]=np.log(priors['business'][active])
        shares=softmax(logits+shift,axis=1)
        C=data['population'][:,None]*np.exp(t[0])*priors['private']
        B=np.exp(t[1])*data['business']@shares
        return C,B
    hm=train & np.isfinite(data['hermes']) & (data['hermes']>=0)
    scale=max(data['dhl'][train].mean(),1.)
    def residual(t):
        C,B=prediction(t);p=C+B;total=max(p[train].sum(),1e-12)
        r=[(p[train,0]-data['dhl'][train])/scale/np.sqrt(train.sum()),
           .2*(p[train].sum(axis=0)/total-priors['market'])/cfg['market_share_sd']/np.sqrt(7),
           np.array([.2*(B[train].sum()/total-priors['b2b'])/cfg['b2b_share_sd']]),
           .1*t[2:]/.5/np.sqrt(8)]
        if hm.sum()>1 and data['hermes'][hm].sum()>0:
            hp=p[hm,1];ht=data['hermes'][hm]
            r.append(cfg['hermes_shape_weight']*(hp/hp.sum()-ht/ht.sum())*np.sqrt(hm.sum()))
        return np.concatenate(r)
    fit=least_squares(residual,np.r_[base['theta'],np.zeros(8)],bounds=([-12,-12]+[-1.5]*8,[12,12]+[1.5]*8),max_nfev=500)
    if not fit.success: raise ValueError('Grouped logit did not converge')
    C,B=prediction(fit.x)
    return (C+B)[:,0]


def predict_candidate(kind,x,data,priors,cfg,train):
    if kind.startswith('logistics_'):
        base=predict_candidate('baseline',x,data,priors,cfg,train)
        design=data['logistics_demographics']
        estimator=make_pipeline(StandardScaler(),Ridge(alpha=float(kind.split('_')[-1])))
        additive='additive' in kind
        residual=(data['dhl'][train]-base[train]) if additive else np.log(np.maximum(data['dhl'][train],1)/np.maximum(base[train],1))
        estimator.fit(design[train],residual)
        correction=estimator.predict(design)
        predicted=np.maximum(base+correction,0) if additive else base*np.exp(np.clip(correction,-.7,.7))
        if 'spatial' not in kind: return predicted
        delta=data['coords'][:,None,:]-data['coords'][train][None,:,:]
        kernel=np.exp(-np.sum(delta*delta,axis=2)/(2*5000.**2))
        residual=np.log(np.maximum(data['dhl'][train],1)/np.maximum(predicted[train],1))
        return predicted*np.exp(.5*(kernel@residual)/(kernel.sum(axis=1)+3))
    if kind.startswith('hermes_'):
        base=predict_candidate('baseline',x,data,priors,cfg,train)
        h=data['hermes'];valid=np.isfinite(h)&(h>=0);used=train&valid
        if used.sum()<3 or h[used].sum()<=0: raise ValueError('Insufficient Hermes covariates')
        ratio=data['dhl'][used].sum()/h[used].sum()
        transfer=np.where(valid,h*ratio,base)
        if kind=='hermes_ratio': return transfer
        if kind=='hermes_blend': return .5*base+.5*transfer
        design=np.c_[data['population'],data['business'].sum(axis=1),np.where(valid,h,0)]
        estimator=make_pipeline(StandardScaler(with_mean=False),Ridge(alpha=10,positive=True,fit_intercept=False))
        estimator.fit(design[used],data['dhl'][used])
        return np.where(valid,np.maximum(estimator.predict(design),0),base)
    if kind.startswith('person_') or kind.startswith('landuse_'):
        base=predict_candidate('baseline',x,data,priors,cfg,train)
        alpha=100 if kind.endswith('100') else 10
        estimator=make_pipeline(StandardScaler(),Ridge(alpha=alpha))
        residual=np.log(np.maximum(data['dhl'][train],1)/np.maximum(base[train],1))
        covariates=data['landuse_demographics'] if kind.startswith('landuse_') else data['demographics']
        estimator.fit(covariates[train],residual)
        demographic=base*np.exp(np.clip(estimator.predict(covariates),-.7,.7))
        if 'spatial' not in kind: return demographic
        delta=data['coords'][:,None,:]-data['coords'][train][None,:,:]
        kernel=np.exp(-np.sum(delta*delta,axis=2)/(2*5000.**2))
        residual=np.log(np.maximum(data['dhl'][train],1)/np.maximum(demographic[train],1))
        return demographic*np.exp(.5*(kernel@residual)/(kernel.sum(axis=1)+3))
    if kind.startswith('hybrid_'):
        base=predict_candidate('baseline',x,data,priors,cfg,train)
        bandwidth=15000.
        delta=data['coords'][:,None,:]-data['coords'][train][None,:,:]
        kernel=np.exp(-np.sum(delta*delta,axis=2)/(2*bandwidth**2))
        # Exclude own residual when calibrating on training rows.
        kernel[np.flatnonzero(train),np.arange(train.sum())]=0
        if kind=='hybrid_additive':
            residual=data['dhl'][train]-base[train]
            local=np.maximum(base+.5*(kernel@residual)/(kernel.sum(axis=1)+3),1e-8)
        else:
            residual=np.log(np.maximum(data['dhl'][train],1)/np.maximum(base[train],1))
            local=base*np.exp(.5*(kernel@residual)/(kernel.sum(axis=1)+3))
        local*=data['dhl'][train].sum()/max(local[train].sum(),1e-12)
        if kind=='hybrid_blend':
            calibrated_base=base*data['dhl'][train].sum()/max(base[train].sum(),1e-12)
            local=.5*local+.5*calibrated_base
        return local
    if kind.startswith('spatial_'):
        base=predict_candidate('baseline',x,data,priors,cfg,train)
        bandwidth=float(kind.split('_')[1])*1000
        delta=data['coords'][:,None,:]-data['coords'][train][None,:,:]
        kernel=np.exp(-np.sum(delta*delta,axis=2)/(2*bandwidth**2))
        # Mean-zero prior with weight 3 prevents strong corrections without nearby support.
        residual=np.log(np.maximum(data['dhl'][train],1)/np.maximum(base[train],1))
        correction=(kernel@residual)/(kernel.sum(axis=1)+3)
        return base*np.exp(.5*correction)
    if kind in ['baseline','joint']:
        fit=fit_candidate('pooled' if kind=='baseline' else 'joint',data,priors,cfg,train)
        C,B=predict_groups(np.array(fit['theta']),fit['kind'],data,priors)
        return (C+B)[:,0]
    if kind=='group_logit': return grouped_logit(data,priors,cfg,train)
    y=data['dhl'];z=np.log1p(x)
    if kind=='positive_ridge':
        model=make_pipeline(StandardScaler(with_mean=False),Ridge(alpha=10,positive=True,fit_intercept=False))
        z=x
    elif kind.startswith('log_ridge'):
        model=make_pipeline(StandardScaler(),Ridge(alpha=float(kind.split('_')[-1])))
    elif kind.startswith('poisson'):
        model=make_pipeline(StandardScaler(),PoissonRegressor(alpha=float(kind.split('_')[-1]),max_iter=2000))
    elif kind=='splines':
        model=make_pipeline(SplineTransformer(n_knots=3,degree=2,include_bias=False),StandardScaler(),Ridge(alpha=100))
    elif kind=='forest':
        model=RandomForestRegressor(n_estimators=80,min_samples_leaf=4,max_features=.7,random_state=42,n_jobs=1)
    elif kind=='boosting':
        model=HistGradientBoostingRegressor(loss='poisson',max_iter=80,max_leaf_nodes=4,min_samples_leaf=6,l2_regularization=10,early_stopping=False,random_state=42)
    else: raise ValueError(kind)
    log_target=kind.startswith('log_ridge') or kind=='splines'
    model.fit(z[train],np.log1p(y[train]) if log_target else y[train])
    prediction=model.predict(z)
    if log_target:
        smear=np.exp(np.log1p(y[train])-model.predict(z[train])).mean()
        prediction=np.exp(prediction)*smear-1
    if not np.isfinite(prediction).all(): raise ValueError('Nonfinite prediction')
    return np.maximum(prediction,0)


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True)
    parser.add_argument('--special-customers');parser.add_argument('--output',required=True)
    parser.add_argument('--hybrid-only',action='store_true')
    parser.add_argument('--persons')
    parser.add_argument('--landuse')
    parser.add_argument('--person-hybrid-only',action='store_true')
    parser.add_argument('--crosscarrier-only',action='store_true')
    parser.add_argument('--logistics-run',help='Completed regional OSM audit; uses historical 2021 features only')
    parser.add_argument('--logistics-snapshot',choices=['2021','current'],default='2021',help='Current is a retrospective proxy experiment, never historical validation')
    args=parser.parse_args();cfg=config(args.config);source=Path(cfg['foundation_run'])
    if args.hybrid_only:
        CANDIDATES[:]=['baseline','spatial_15','hybrid_log','hybrid_additive','hybrid_blend']
    if args.person_hybrid_only:
        if not args.persons: raise ValueError('Person hybrid requires --persons')
        CANDIDATES[:]=['baseline','spatial_5','person_residual_10','person_residual_100','person_spatial_10','person_spatial_100']
    if args.crosscarrier_only:
        if not args.persons: raise ValueError('Cross-carrier comparison requires --persons')
        CANDIDATES[:]=['baseline','person_spatial_100','hermes_ratio','hermes_blend','hermes_regression']
    output=Path(args.output);output.mkdir(parents=True,exist_ok=False)
    sites,data=prepare_training(source,.5,cfg.get('dhl_exclude_above'));priors=load_legacy(cfg['legacy_output'],2021)
    h=data.pop('hermes_table').query('year == 2021');data['hermes']=h.set_index('plz').value.reindex(data['plz']).to_numpy(float)
    x,coords=features(source,sites,data);data['coords']=coords
    if args.persons:
        from .person_features import aggregate_persons
        person,quality=aggregate_persons(args.persons,sites,data['plz'])
        write_json(output/'person_feature_quality.json',quality)
        denom=np.maximum(data['population']*1000,1)
        data['demographics']=person[['age25_44','age65plus','employed']].to_numpy()/denom[:,None]
        x=x.join(person.add_prefix('person_'))
    if args.landuse:
        if not args.persons: raise ValueError('Landuse comparison requires person features')
        from .landuse import areas
        land=areas(args.landuse,source,data['plz'])
        land.to_csv(output/'landuse_features.csv')
        x=x.join(land.add_prefix('landuse_'))
        data['landuse_demographics']=np.c_[data['demographics'],np.log1p(land.to_numpy())]
        CANDIDATES[:]=['baseline','person_spatial_100','landuse_residual_100','landuse_spatial_100','log_ridge_10','log_ridge_100','forest']
    if args.logistics_run:
        if not args.persons or args.landuse: raise ValueError('Logistics comparison requires persons and no landuse comparison')
        import json
        logistics_run=Path(args.logistics_run)
        meta=json.loads((logistics_run/'provenance.json').read_text(encoding='utf-8'))
        snapshot_meta=json.loads((logistics_run/f'osm_{args.logistics_snapshot}.json').read_text(encoding='utf-8'))
        if not meta.get('regional') or snapshot_meta.get('status')!='complete': raise ValueError('Complete regional OSM snapshot required')
        if meta['inputs']['postal_support.parquet']!=digest(source/'postal_support.parquet'):
            raise ValueError('OSM and model postal boundaries differ')
        logistics=pd.read_csv(logistics_run/f'postal_features_{args.logistics_snapshot}.csv',dtype={'plz':str}).set_index('plz').reindex(data['plz'])
        if logistics.index.duplicated().any() or not np.isfinite(logistics.to_numpy()).all() or (logistics.to_numpy()<0).any():
            raise ValueError('Historical OSM features must cover every PLZ; missing is not zero')
        logistics.to_csv(output/f'logistics_features_{args.logistics_snapshot}.csv')
        x=x.join(logistics.add_prefix(f'osm_{args.logistics_snapshot}_'))
        data['logistics_demographics']=np.c_[data['demographics'],np.log1p(logistics.to_numpy())]
        CANDIDATES[:]=['baseline','person_spatial_100','logistics_residual_10','logistics_residual_100','logistics_spatial_100','logistics_additive_10']
    x.to_csv(output/'features.csv',index_label='plz');x=x.to_numpy()
    from ..scope import filter_dhl
    filter_dhl(pd.read_parquet(source/'dhl_observations.parquet'),cfg.get('dhl_exclude_above'),output)
    if cfg.get('dhl_exclude_above') is not None:
        offset=np.zeros(len(data['plz']))
    else:
        if not args.special_customers: raise ValueError('Supply special customers or an explicit observation scope')
        offset=special_offsets(pd.read_parquet(source/'dhl_observations.parquet'),pd.read_csv(args.special_customers,dtype={'plz':str}),data['plz'])
    protocol={'candidates':CANDIDATES,'random_seeds':[42,73,101],'outer_folds':5,'spatial':'5 KMeans clusters of postal polygon centroids, seed 42; no buffer',
              'selection':'3-fold inner CV for random outer splits; leave-one-remaining-spatial-cluster-out for spatial outer splits. Pooled absolute error determines selection.',
              'limits':'53 PLZ; exploratory reused data. Direct regression candidates predict DHL only, not identified total market or other carriers. Conditional core uses previously known special amounts; not a blind special-customer test. No post-fit local corrections.'}
    write_json(output/'protocol.json',protocol)
    if args.logistics_run:
        protocol['osm_snapshot']=args.logistics_snapshot
        protocol['osm_information']=('Uniform regional OSM snapshot at 2021-12-31. ' if args.logistics_snapshot=='2021' else 'RETROSPECTIVE PROXY EXPERIMENT: current OSM server data explain 2021 targets. This includes later mapping/buildings, is not historically valid 2021 prediction, and cannot justify model promotion. ')
        protocol['osm_information']+='Footprints are unioned and clipped. Mapping completeness remains uncertain. No hand-coded Langenhagen effect or target-based exclusions.'
        write_json(output/'protocol.json',protocol)
    if args.crosscarrier_only:
        protocol['additional_test_information']='Known Hermes 2021 value at test PLZ is an input. Test DHL remains excluded. This is cross-carrier estimation, not forecasting without Hermes data.'
        write_json(output/'protocol.json',protocol)
    spatial=KMeans(n_clusters=5,random_state=42,n_init=10).fit_predict(coords)
    layouts={f'random_{seed}':np.array_split(np.random.default_rng(seed).permutation(len(x)),5) for seed in [42,73,101]}
    layouts['spatial']=[np.flatnonzero(spatial==k) for k in range(5)]
    rows=[];choices=[];failures=[]
    for mode in (['kep_scope'] if cfg.get('dhl_exclude_above') is not None else ['raw','conditional_core']):
        work={**data,'dhl':data['dhl']-(offset if mode=='conditional_core' else 0)}
        for layout,folds in layouts.items():
            for fold,held in enumerate(folds):
                train=np.ones(len(x),dtype=bool);train[held]=False
                if layout=='spatial':
                    inner=[v for j,v in enumerate(folds) if j!=fold]
                else: inner=np.array_split(np.random.default_rng(900+fold).permutation(np.flatnonzero(train)),3)
                errors={};outer={}
                for kind in CANDIDATES:
                    try:
                        error=0.
                        for validation in inner:
                            fit_mask=train.copy();fit_mask[validation]=False
                            p=predict_candidate(kind,x,work,priors,cfg,fit_mask)
                            error+=np.abs(p[validation]-work['dhl'][validation]).sum()
                        outer[kind]=predict_candidate(kind,x,work,priors,cfg,train)[held]
                        errors[kind]=error
                    except (ValueError,RuntimeError) as exc:
                        failures.append(dict(mode=mode,layout=layout,fold=fold,model=kind,error=str(exc)))
                if 'baseline' not in outer: raise ValueError('Baseline failed')
                winner=min(errors,key=errors.get)
                choices.append(dict(mode=mode,layout=layout,fold=fold,selected=winner,held_plz=[data['plz'][i] for i in held],inner_absolute_errors=errors))
                outer['nested_selection']=outer[winner]
                for kind,p in outer.items():
                    for i,value in zip(held,p): rows.append(dict(mode=mode,layout=layout,fold=fold,model=kind,plz=data['plz'][i],observed=work['dhl'][i],predicted=float(value)))
                print(f'{mode} {layout} fold {fold}: selected {winner}',flush=True)
                pd.DataFrame(rows).to_csv(output/'predictions.csv',index=False)
    frame=pd.DataFrame(rows);scores=[]
    for (mode,layout,kind),part in frame.groupby(['mode','layout','model']):
        if len(part)!=len(x): continue
        scores.append(dict(mode=mode,layout=layout,model=kind,**metrics(part.observed,part.predicted)))
    score=pd.DataFrame(scores);score.to_csv(output/'metrics.csv',index=False)
    write_json(output/'selection.json',choices);write_json(output/'failures.json',failures)
    summary=score.assign(protocol=np.where(score.layout.eq('spatial'),'spatial','random_mean')).groupby(['mode','protocol','model'])[['wMAPE','bias']].mean()
    print(summary.to_string())
    page='<!doctype html><meta charset="utf-8"><title>Modellsuche</title><style>body{font:16px system-ui;max-width:1200px;margin:40px auto}td,th{padding:8px}</style><h1>Breiter Modellvergleich</h1><p>'+protocol['limits']+'</p><p>nested_selection bewertet die gesamte Auswahlprozedur. Einzelne Kandidaten dienen der explorativen Diagnose; die beste Tabellenzeile ist kein unabhaengiger Auswahltest.</p>'
    if args.logistics_run: page+='<p><strong>'+protocol['osm_information']+'</strong></p>'
    (output/'dashboard.html').write_text(page+summary.to_html(float_format=lambda x:f'{x:.2%}'),encoding='utf-8')
    import sklearn
    paths=[Path(args.config),*([Path(args.landuse)] if args.landuse else []),*([Path(args.persons)] if args.persons else []),*([Path(args.special_customers)] if args.special_customers else []),*[source/n for n in ['sites.parquet','site_postal_candidates.parquet','postal_support.parquet','dhl_observations.parquet','hermes_observations.parquet']],*Path(cfg['legacy_output']).glob('0[0125]_*.csv')]
    if args.logistics_run: paths.extend([Path(args.logistics_run)/n for n in [f'postal_features_{args.logistics_snapshot}.csv','provenance.json',f'osm_{args.logistics_snapshot}.json']])
    write_json(output/'provenance.json',{'sklearn':sklearn.__version__,'inputs':{str(p.resolve()):digest(p) for p in paths},'code':{p.relative_to(Path(__file__).parents[1]).as_posix():digest(p) for p in [*Path(__file__).parent.glob('*.py'),*Path(__file__).parents[1].glob('*.py')]}})


if __name__=='__main__':
    with threadpool_limits(limits=1): main()
