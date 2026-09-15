"""Paired exploratory CV of establishment-size effects, never automatic promotion."""
import argparse
from pathlib import Path
import numpy as np
import pandas as pd
from .workflow import config
from .model import prepare_training,load_legacy,fit_candidate,predict_groups,metrics
from .diagnostics import special_offsets
from .pipeline import digest
from .data import write_json


def main():
    parser=argparse.ArgumentParser()
    parser.add_argument('--config',required=True)
    parser.add_argument('--special-customers',required=True)
    parser.add_argument('--output',required=True)
    args=parser.parse_args();cfg=config(args.config)
    if cfg.get('dhl_exclude_above') is not None: raise ValueError('Legacy comparison: use model_search for the configured KEP scope')
    output=Path(args.output);output.mkdir(parents=True,exist_ok=False)
    source=Path(cfg['foundation_run'])
    priors=load_legacy(cfg['legacy_output'],2021)
    observations=pd.read_parquet(source/'dhl_observations.parquet')
    special=pd.read_csv(args.special_customers,dtype={'plz':str})
    rows=[];scores=[]
    for power in [1.,.75,.5]:
        _,data=prepare_training(source,power)
        h=data.pop('hermes_table').query('year == 2021')
        data['hermes']=h.set_index('plz').value.reindex(data['plz']).to_numpy(float)
        offset=special_offsets(observations,special,data['plz'])
        for mode in ['raw','conditional_core']:
            work={**data,'dhl':data['dhl']-(offset if mode=='conditional_core' else 0)}
            for seed in [42,73,101]:
                pred=np.zeros(len(data['plz']))
                for held in np.array_split(np.random.default_rng(seed).permutation(len(pred)),5):
                    train=np.ones(len(pred),dtype=bool);train[held]=False
                    fit=fit_candidate('pooled',work,priors,cfg,train)
                    C,B=predict_groups(np.array(fit['theta']),'pooled',work,priors)
                    pred[held]=(C+B)[held,0]
                scores.append(dict(power=power,mode=mode,seed=seed,**metrics(work['dhl'],pred)))
                for plz,y,p in zip(data['plz'],work['dhl'],pred):
                    rows.append(dict(power=power,mode=mode,seed=seed,plz=plz,observed=y,predicted=p))
        print(f'Completed establishment exponent {power}',flush=True)
    pd.DataFrame(rows).to_csv(output/'predictions.csv',index=False)
    table=pd.DataFrame(scores);table.to_csv(output/'metrics.csv',index=False)
    summary=table.groupby(['mode','power'])[['wMAPE','bias']].mean()
    print(summary.to_string())
    (output/'dashboard.html').write_text('<!doctype html><meta charset="utf-8"><h1>Betriebsgroesse: Modellvergleich</h1><p>Explorative wiederholte PLZ-CV. Conditional core setzt bekannte DHL-Grosskundenmengen voraus. Kein unberuehrter Test und keine automatische Modellfreigabe.</p>'+summary.to_html(float_format=lambda x:f'{x:.2%}'),encoding='utf-8')
    paths=[Path(args.config),Path(args.special_customers),*source.glob('*.parquet'),*Path(cfg['legacy_output']).glob('0[0125]_*.csv')]
    write_json(output/'provenance.json',{'inputs':{str(p.resolve()):digest(p) for p in paths},'code':{p.name:digest(p) for p in Path(__file__).parent.glob('*.py')}})


if __name__=='__main__': main()
