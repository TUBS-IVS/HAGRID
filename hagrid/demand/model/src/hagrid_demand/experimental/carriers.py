"""Conditional carrier allocation: declared branch priors, stable preferences, local data."""
import hashlib
import html
import numpy as np
import pandas as pd
from scipy.special import ndtri
from .model import PROVIDERS
from ..data import write_json


def stable_preferences(ids, seed):
    # Independent of input order, requested days and Monte Carlo realization.
    values=np.array([np.frombuffer(hashlib.sha512(f'{seed}:{x}'.encode()).digest(),dtype='<u8')[:7]
                     for x in ids],dtype=np.uint64)
    uniform=(values.astype(float)+.5)/2.**64
    return ndtri(np.clip(uniform,1e-12,1-1e-12))


def balance(prob, weights, target, tolerance=1e-8, iterations=500):
    """Preserve each site's total while matching feasible weighted carrier margins."""
    prob=np.asarray(prob,float).copy();weights=np.asarray(weights,float)
    target=np.asarray(target,float)
    if (target<0).any() or not np.isclose(target.sum(),weights.sum()):
        raise ValueError('Infeasible carrier margins')
    if weights.sum()==0: return prob
    for _ in range(iterations):
        actual=weights@prob
        if np.max(np.abs(actual-target))/weights.sum()<tolerance: return prob
        if ((actual==0)&(target>0)).any(): raise ValueError('Carrier target outside prior support')
        prob*=np.divide(target,actual,out=np.zeros(7),where=actual>0)
        norm=prob.sum(axis=1)
        if (norm<=0).any(): raise ValueError('Empty site carrier support')
        prob/=norm[:,None]
    raise ValueError('Local carrier balancing did not converge')


def build_profiles(sites,weights,shares,cfg,output):
    spec=cfg.get('carrier_allocation',{})
    base=np.array([shares[0] if s=='private' else shares[1] for s in sites.recipient_type])
    if not spec.get('enabled',False): return base
    prob=base.copy()
    for branch,multipliers in spec.get('branch_multipliers',{}).items():
        if set(multipliers)-set(PROVIDERS): raise ValueError('Unknown carrier in branch prior')
        factor=np.array([multipliers.get(p,1.) for p in PROVIDERS],float)
        if not np.isfinite(factor).all() or (factor<=0).any(): raise ValueError('Invalid branch multipliers')
        mask=sites.recipient_type.eq('business') & sites.branch.eq(branch)
        prob[mask]*=factor
    noise=stable_preferences(sites.site_id,cfg['seed'])
    sd=np.array([spec.get('preference_log_sd',{}).get(s,0.) for s in sites.recipient_type])
    if not np.isfinite(sd).all() or ((sd<0)|(sd>2)).any(): raise ValueError('Invalid preference variation')
    prob*=np.exp(noise*sd[:,None]);prob/=prob.sum(axis=1)[:,None]
    # Branch/preferences redistribute within each segment while retaining its carrier margins.
    for segment in ['private','business']:
        mask=sites.recipient_type.eq(segment).to_numpy()
        prob[mask]=balance(prob[mask],weights[mask],weights[mask]@base[mask])
    assumed=prob.copy()
    source=__import__('pathlib').Path(cfg['foundation_run'])
    dhl=pd.read_parquet(source/'dhl_observations.parquet')
    if set(dhl.year.unique())!={cfg['reference_year']}: raise ValueError('LSP reference year mismatch')
    from ..scope import filter_dhl
    dhl=filter_dhl(dhl,cfg.get('dhl_exclude_above'))
    dhl=dhl.groupby('plz').value.sum()
    hermes=pd.read_parquet(source/'hermes_observations.parquet')
    hermes=hermes.loc[hermes.year.eq(cfg['reference_year'])]
    if hermes.plz.duplicated().any(): raise ValueError('Duplicate Hermes observations')
    hermes=hermes.set_index('plz').value
    strength=spec.get('local_strength',{})
    limit=spec.get('max_local_multiplier',1.5)
    if not np.isfinite(limit) or limit<1: raise ValueError('Local multiplier bound must be >=1')
    for v in strength.values():
        if not np.isfinite(v) or not 0<=v<=1: raise ValueError('Local correction strength must be 0..1')
    postal=sites.plz.fillna('unassigned').to_numpy()
    covered=np.isin(postal,hermes.index)
    hsum=hermes.reindex(sorted(set(postal)&set(hermes.index))).sum()
    hscale=float(weights[covered]@prob[covered,1]/hsum) if hsum>0 else 0.
    reports=[]
    for plz in sorted(set(postal)):
        mask=postal==plz;w=weights[mask];before=w@prob[mask];total=w.sum()
        target=before.copy();known=[]
        if plz in dhl.index:
            a=strength.get('DHL',0.);target[0]=(1-a)*before[0]+a*dhl.loc[plz]
            if a: known.append(0)
        if plz in hermes.index and hscale>0:
            a=strength.get('Hermes',0.);target[1]=(1-a)*before[1]+a*hermes.loc[plz]*hscale
            if a: known.append(1)
        capped=False
        if known and total>0:
            bounded=np.clip(target[known],before[known]/limit,before[known]*limit)
            capped=bool(not np.allclose(bounded,target[known]))
            target[known]=bounded
            # Inconsistent observed demand must remain visible; never invent negative remainder.
            if target[known].sum()>.98*total:
                target[known]*=.98*total/target[known].sum();capped=True
            other=[k for k in range(7) if k not in known]
            remainder=total-target[known].sum()
            if before[other].sum()<=0: raise ValueError('No support for remaining carriers')
            target[other]=before[other]*remainder/before[other].sum()
            prob[mask]=balance(prob[mask],w,target)
        after=w@prob[mask]
        for k,p in enumerate(PROVIDERS):
            reports.append(dict(plz=plz,carrier=p,total=total,baseline=float(w@base[mask,k]),
                branch_and_preference=before[k],conditional=after[k],
                share_pct=100*after[k]/total if total else 0.,
                within_plz_p10=float(np.quantile(prob[mask,k],.1)),within_plz_p90=float(np.quantile(prob[mask,k],.9)),
                target_capped=capped,dhl_observed=float(dhl.loc[plz]) if plz in dhl.index else None))
    detail=sites[['site_id','plz','recipient_type','branch']].copy()
    for k,p in enumerate(PROVIDERS):
        detail['prior_'+p]=base[:,k];detail['assumed_'+p]=assumed[:,k];detail['share_'+p]=prob[:,k]
    detail.to_parquet(output/'carrier_site_profiles.parquet',index=False)
    private=sites.recipient_type.eq('private').to_numpy()
    C=weights[private]@prob[private];B=weights[~private]@prob[~private]
    write_json(output/'carrier_conditional_reference.json',[
        {'carrier':p,'private':float(C[k]),'business':float(B[k]),
         'own_b2b_pct':float(100*B[k]/(C[k]+B[k])) if C[k]+B[k]>0 else 0.}
        for k,p in enumerate(PROVIDERS)])
    report=pd.DataFrame(reports);report.to_csv(output/'carrier_local_diagnostics.csv',index=False)
    note={'status':'conditional_allocation_not_independent_prediction',
          'local_strength':strength,'max_local_multiplier':limit,'hermes_use':'spatial shape only, scaled to pre-correction Hermes total on covered PLZ',
          'preferences':'fixed by site_id and seed across dates and realizations; unobserved assumptions',
          'branch_priors':'user-adjustable hypotheses, not measured market shares',
          'limits':'Uses reference observations after demand fitting. Original holdout scores do not evaluate this correction. Site totals remain fixed; regional carrier totals may change. Future years inherit local patterns as an assumption.',
          'capped_plz':sorted(report.loc[report.target_capped,'plz'].unique().tolist())}
    write_json(output/'carrier_allocation.json',note)
    page='<!doctype html><meta charset="utf-8"><title>Local carrier profiles</title><style>body{font:16px system-ui;margin:30px}td,th{padding:7px}table{border-collapse:collapse}</style><h1>Local carrier profiles</h1><p>'+html.escape(note['limits'])+'</p><p>Branch profiles and persistent preferences are assumptions. Hermes provides only the spatial shape. P10/P90 describe the unweighted dispersion of the site shares, not uncertainty intervals.</p>'
    page+=report.to_html(index=False,float_format=lambda x:f'{x:.3f}')
    (output/'carriers.html').write_text(page,encoding='utf-8')
    return prob
