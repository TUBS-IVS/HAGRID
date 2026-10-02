"""Small, regularized aggregate logit experiment; no post-fit observation correction."""
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
from scipy.optimize import least_squares
from scipy.special import softmax
from .model import prepare_training,load_legacy,fit_candidate,predict_groups,metrics
from .workflow import config
from .diagnostics import special_offsets
from ..pipeline import digest
from ..data import write_json


def predict(theta,data,priors):
    # One additional coefficient: LSP log odds in manufacturing / transport-storage.
    # All other carriers retain their relative prior odds; no unsupported free effects.
    business_shares=np.tile(priors['business'],(len(data['branches']),1))
    active=priors['business']>0
    for j,branch in enumerate(data['branches']):
        if branch in {'C','H'}:
            logits=np.full(7,-np.inf);logits[active]=np.log(priors['business'][active])
            logits[0]+=theta[2]
            business_shares[j]=softmax(logits)
    C=data['population'][:,None]*np.exp(theta[0])*priors['private']
    B=np.exp(theta[1])*data['business']@business_shares
    return C,B


def fit(data,priors,cfg,train):
    initial=fit_candidate('pooled',data,priors,cfg,train)
    start=np.r_[initial['theta'],0.]
    scale=max(data['dhl'][train].mean(),1.)
    h=data['hermes'];hm=train & np.isfinite(h) & (h>=0)
    def residual(t):
        C,B=predict(t,data,priors);p=C+B
        errors=[(p[train,0]-data['dhl'][train])/scale/np.sqrt(train.sum())]
        if hm.sum()>1 and h[hm].sum()>0:
            hp=p[hm,1]
            errors.append(cfg['hermes_shape_weight']*(hp/hp.sum()-h[hm]/h[hm].sum())*np.sqrt(hm.sum()))
        total=max(p[train].sum(),1e-12)
        errors.append(.2*(p[train].sum(axis=0)/total-priors['market'])/cfg['market_share_sd']/np.sqrt(7))
        errors.append(np.array([.2*(B[train].sum()/total-priors['b2b'])/cfg['b2b_share_sd']]))
        # Fixed in advance: modest effect, log-odds SD .35; no hyperparameter search.
        errors.append(np.array([.1*t[2]/.35]))
        return np.concatenate(errors)
    result=least_squares(residual,start,bounds=([-12,-12,-1],[12,12,1]),max_nfev=500,
                         ftol=1e-9,xtol=1e-9,gtol=1e-9)
    if not result.success: raise ValueError(result.message)
    return result.x


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',required=True);parser.add_argument('--special-customers',required=True)
    parser.add_argument('--output',required=True)
    args=parser.parse_args();cfg=config(args.config)
    if cfg.get('dhl_exclude_above') is not None: raise ValueError('Legacy comparison: use model_search for the configured CEP scope')
    output=Path(args.output);output.mkdir(parents=True,exist_ok=False)
    source=Path(cfg['foundation_run'])
    _,data=prepare_training(source,.5);priors=load_legacy(cfg['legacy_output'],2021)
    h=data.pop('hermes_table').query('year == 2021')
    data['hermes']=h.set_index('plz').value.reindex(data['plz']).to_numpy(float)
    obs=pd.read_parquet(source/'dhl_observations.parquet')
    offset=special_offsets(obs,pd.read_csv(args.special_customers,dtype={'plz':str}),data['plz'])
    protocol={'additional_parameters':1,'feature':'business branches C or H','log_odds_sd':.35,
              'coefficient_bounds':[-1,1],'business_size_power':.5,'seeds':[42,73,101],'folds':5,
              'gate':'Raw mean wMAPE improves by >=0.005 absolute, all three seeds improve, mean Hermes shape wMAPE worsens by <=0.02.',
              'status':'Exploratory reused postal folds, no untouched final test. Conditional core is sensitivity only. No local correction.'}
    write_json(output/'protocol.json',protocol)
    rows=[];scores=[];coefficients=[]
    for mode in ['raw','conditional_core']:
        work={**data,'dhl':data['dhl']-(offset if mode=='conditional_core' else 0)}
        for seed in protocol['seeds']:
            predictions={k:np.zeros((len(data['plz']),7)) for k in ['baseline','logit_one_effect']}
            for fold,held in enumerate(np.array_split(np.random.default_rng(seed).permutation(len(data['plz'])),5)):
                train=np.ones(len(data['plz']),dtype=bool);train[held]=False
                base=fit_candidate('pooled',work,priors,cfg,train)
                C,B=predict_groups(np.array(base['theta']),'pooled',work,priors)
                predictions['baseline'][held]=(C+B)[held]
                theta=fit(work,priors,cfg,train);C,B=predict(theta,work,priors)
                predictions['logit_one_effect'][held]=(C+B)[held]
                coefficients.append(dict(mode=mode,seed=seed,fold=fold,beta=float(theta[2])))
            for kind,p in predictions.items():
                hm=np.isfinite(data['hermes']) & (data['hermes']>=0)
                hp=p[hm,1];ht=data['hermes'][hm]
                hs=metrics(ht,hp/hp.sum()*ht.sum())['wMAPE']
                scores.append(dict(mode=mode,seed=seed,model=kind,hermes_shape_wMAPE=hs,**metrics(work['dhl'],p[:,0])))
                for i,plz in enumerate(data['plz']):
                    rows.append(dict(mode=mode,seed=seed,model=kind,plz=plz,observed=work['dhl'][i],predicted=p[i,0]))
            print(f'Completed {mode}, seed {seed}',flush=True)
    table=pd.DataFrame(scores);table.to_csv(output/'metrics.csv',index=False)
    pd.DataFrame(rows).to_csv(output/'predictions.csv',index=False)
    pd.DataFrame(coefficients).to_csv(output/'coefficients.csv',index=False)
    raw=table.query("mode == 'raw'").pivot(index='seed',columns='model',values='wMAPE')
    improvements=raw.baseline-raw.logit_one_effect
    hs=table.query("mode == 'raw'").groupby('model').hermes_shape_wMAPE.mean()
    passed=bool(improvements.mean()>=.005 and (improvements>0).all() and hs.logit_one_effect-hs.baseline<=.02)
    decision={'gate_passed':passed,'mean_raw_wMAPE_improvement':float(improvements.mean()),
              'next_step':'Independent spatial-block evaluation before more effects' if passed else 'Keep baseline; do not expand logit yet',
              'no_production_model_changed':True}
    write_json(output/'decision.json',decision)
    summary=table.groupby(['mode','model'])[['wMAPE','bias','hermes_shape_wMAPE']].mean()
    print(summary.to_string());print(decision)
    (output/'dashboard.html').write_text('<!doctype html><meta charset="utf-8"><title>Small logit test</title><style>body{font:17px system-ui;max-width:1100px;margin:40px auto}td,th{padding:10px}</style><h1>One additional logit effect</h1><p>'+protocol['status']+'</p><p>'+decision['next_step']+'</p>'+summary.to_html(float_format=lambda x:f'{x:.2%}')+'<p>Expansion threshold fixed before computation: at least 0.5 percentage points lower raw-volume wMAPE, improvement in all three splits and at most 2 percentage points deterioration of the Hermes shape.</p>',encoding='utf-8')
    paths=[Path(args.config),Path(args.special_customers),*[source/n for n in ['sites.parquet','site_postal_candidates.parquet','dhl_observations.parquet','hermes_observations.parquet']],*Path(cfg['legacy_output']).glob('0[0125]_*.csv')]
    write_json(output/'provenance.json',{'inputs':{str(p.resolve()):digest(p) for p in paths},'code':{p.relative_to(Path(__file__).parents[1]).as_posix():digest(p) for p in [*Path(__file__).parent.glob('*.py'),*Path(__file__).parents[1].glob('*.py')]}})


if __name__=='__main__': main()
