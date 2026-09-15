"""Observation-constrained 2021 reconstruction, explicitly separate from prediction."""
import argparse
from pathlib import Path
import json
import numpy as np
import pandas as pd
from .model import PROVIDERS
from ..pipeline import digest
from ..data import write_json
from .provenance import code_hashes as package_code_hashes


def _code_hashes(package_root=None):
    root = Path(package_root).resolve() if package_root is not None else Path(__file__).resolve().parents[1]
    return package_code_hashes(root, "experimental/reconstruct.py", "experimental/model.py", "data.py", "pipeline.py", "scope.py")


def constrain(frame,targets):
    result=frame.copy();before=result.groupby('plz').DHL.sum()
    for plz,target in targets.items():
        if not np.isfinite(target) or target<0: raise ValueError('Invalid DHL target')
        mask=result.plz.eq(plz)
        if not mask.any() or (before.get(plz,0)<=0 and target>0):
            raise ValueError(f'No positive allocation support for observed PLZ {plz}')
        result.loc[mask,'DHL']*=target/before[plz] if before[plz]>0 else 0.
    return result


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--model-run',required=True)
    parser.add_argument('--output',required=True);args=parser.parse_args()
    source=Path(args.model_run);cfg=json.loads((source/'config.resolved.json').read_text(encoding='utf-8'))
    if cfg['reference_year']!=2021: raise ValueError('2021 model required')
    output=Path(args.output);output.mkdir(parents=True,exist_ok=False)
    base=pd.read_parquet(source/'baseline_sites.parquet')
    shares=pd.read_parquet(source/'carrier_site_profiles.parquet')
    data=base.merge(shares.drop(columns=['plz','recipient_type']),on='site_id',validate='one_to_one')
    frame=data[['site_id','plz','recipient_type','geometry']].copy()
    for provider in PROVIDERS: frame[provider]=data.mean_reference_operating_day*data['share_'+provider]
    dhl_path=Path(cfg['foundation_run'])/'dhl_observations.parquet'
    observations=pd.read_parquet(dhl_path)
    if set(observations.year.unique())!={2021}: raise ValueError('Mixed observation years')
    from ..scope import filter_dhl
    observations=filter_dhl(observations,cfg.get('dhl_exclude_above'),output)
    targets=observations.groupby('plz').value.sum()
    result=constrain(frame,targets)
    result['observation_constrained']=result.plz.isin(targets.index)
    result['total']=result[PROVIDERS].sum(axis=1)
    for provider in PROVIDERS:
        result['share_'+provider]=np.divide(result[provider],result.total,out=np.zeros(len(result)),where=result.total>0)
    result.to_parquet(output/'reference_day_sites.parquet',index=False)
    prior=frame.groupby('plz')[PROVIDERS].sum();after=result.groupby('plz')[PROVIDERS].sum()
    check=pd.DataFrame({'observed_DHL':targets,'before_DHL':prior.DHL.reindex(targets.index),'reconstructed_DHL':after.DHL.reindex(targets.index)})
    check['factor']=check.reconstructed_DHL/check.before_DHL
    check['difference']=check.reconstructed_DHL-check.observed_DHL
    if not np.allclose(check.difference,0,atol=1e-7): raise AssertionError('DHL reconstruction mismatch')
    np.testing.assert_allclose(result[PROVIDERS[1:]].to_numpy(),frame[PROVIDERS[1:]].to_numpy())
    check.to_csv(output/'postal_checks.csv',index_label='plz')
    after.to_csv(output/'carrier_postal_reference.csv',index_label='plz')
    note={'status':'observation_constrained_reconstruction_not_validation','reference_year':2021,
          'observed_DHL_total':float(targets.sum()),'reconstructed_DHL_total_on_observed_PLZ':float(check.reconstructed_DHL.sum()),
          'max_absolute_PLZ_difference':float(check.difference.abs().max()),'source_model':str(source.resolve()),
          'limitations':['Exact fit is imposed from the same observed DHL PLZ totals, not a prediction score.',
          'Within-PLZ allocation and B2B/B2C split remain modeled. Raw street observations are not individually reproduced.',
          'Configured observation exclusions apply before aggregation; remaining within-PLZ allocation is unverified.',
          'Other-carrier amounts remain prior model estimates, not observations; regional total and shares consequently change.',
          'Source tagesschni denominator and reference population/company vintages remain uncertain. No 313-day annual conversion or future growth applied.',
          'Sites outside observed postal areas retain unconstrained estimates and are excluded from the exact-fit claim.']}
    write_json(output/'result.json',note)
    paths=[source/'baseline_sites.parquet',source/'carrier_site_profiles.parquet',source/'config.resolved.json',dhl_path]
    write_json(output/'provenance.json',{'inputs':{str(p.resolve()):digest(p) for p in paths},'code':_code_hashes()})
    page='<!doctype html><meta charset="utf-8"><title>Rekonstruktion 2021</title><style>body{font:17px system-ui;max-width:1100px;margin:40px auto}td,th{padding:8px}</style><h1>DHL 2021: beobachtungsgebundene Rekonstruktion</h1><p>Alle beobachteten PLZ-Mengen werden exakt getroffen. Dies ist eine auferlegte Datenbindung, keine unabhaengige Vorhersageguete.</p><ul>'+''.join('<li>'+s+'</li>' for s in note['limitations'])+'</ul>'
    (output/'dashboard.html').write_text(page+check.to_html(float_format=lambda x:f'{x:,.3f}'),encoding='utf-8')
    print(json.dumps(note,indent=2))


if __name__=='__main__': main()
