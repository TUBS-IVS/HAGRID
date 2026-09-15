"""Distinguish volume forecasting from allocation conditional on known totals."""
import argparse
from pathlib import Path
import pandas as pd
import numpy as np
from .model import metrics
from .data import write_json
from .pipeline import digest


def normalize_to_total(values,total):
    values=np.asarray(values,float)
    if not np.isfinite(values).all() or (values<0).any() or total<0: raise ValueError('Invalid allocation')
    if values.sum()<=0 and total>0: raise ValueError('No allocation support')
    return values*total/values.sum() if values.sum()>0 else values.copy()


def main():
    p=argparse.ArgumentParser();p.add_argument('--source',required=True);p.add_argument('--output',required=True);args=p.parse_args()
    source=Path(args.source);output=Path(args.output);output.mkdir(parents=True,exist_ok=False)
    frame=pd.read_csv(source/'predictions.csv',dtype={'plz':str});rows=[];scores=[]
    for (mode,layout,model),part in frame.groupby(['mode','layout','model']):
        for condition in ['no_known_total','known_regional_total','known_test_block_total']:
            part=part.copy()
            values=part.predicted.copy()
            if condition=='known_regional_total': values=normalize_to_total(values,part.observed.sum())
            if condition=='known_test_block_total':
                for _,block in part.groupby('fold'):
                    values.loc[block.index]=normalize_to_total(block.predicted,block.observed.sum())
            scores.append(dict(mode=mode,layout=layout,model=model,condition=condition,**metrics(part.observed,np.asarray(values))))
            rows.extend(dict(mode=mode,layout=layout,model=model,condition=condition,plz=z,observed=float(y),predicted=float(v)) for z,y,v in zip(part.plz,part.observed,values))
    table=pd.DataFrame(scores);table.to_csv(output/'metrics.csv',index=False)
    pd.DataFrame(rows).to_csv(output/'predictions.csv',index=False)
    summary=table.assign(protocol=np.where(table.layout.eq('spatial'),'spatial','random_mean')).groupby(['protocol','model','condition'])[['wMAPE','bias']].mean()
    page='<!doctype html><meta charset="utf-8"><title>Top-down-Test</title><style>body{font:16px system-ui;margin:40px}td,th{padding:8px}</style><h1>Vorhersage und Top-down-Verteilung</h1><p>Bekannte Summen stammen hier aus den Beobachtungen: Die entsprechenden Zeilen bewerten ausschliesslich die bedingte Verteilung, keine unabhaengige Mengenvorhersage. Bei bekannten Testblock-Summen steht zusaetzliche Information zur Verfuegung. Die Quellmodelle sind ausserhalb der jeweiligen Testgebiete angepasst.</p>'
    (output/'dashboard.html').write_text(page+summary.to_html(float_format=lambda x:f'{x:.2%}'),encoding='utf-8')
    write_json(output/'provenance.json',{'source_sha256':digest(source/'predictions.csv'),'code_sha256':digest(Path(__file__))})
    print(summary.to_string())


if __name__=='__main__': main()
