"""Observed synthetic-person covariates, without invented shopping labels."""
import pandas as pd
import numpy as np


def aggregate_persons(path,sites,groups):
    mapping=sites.loc[sites.recipient_type.eq('private'),['source_key','plz']]
    if mapping.source_key.duplicated().any(): raise ValueError('Ambiguous building mapping')
    mapping=mapping.set_index('source_key').plz
    pieces=[];unmatched=0;total=0
    for frame in pd.read_csv(path,usecols=['Building','age','employed'],dtype={'Building':'string'},chunksize=150000):
        frame['plz']=('building:'+frame.Building.str.strip()).map(mapping)
        age=pd.to_numeric(frame.age,errors='coerce');valid=age.between(0,120)
        work=frame.employed.astype(str).str.lower()
        out=pd.DataFrame({'plz':frame.plz})
        for name,lo,hi in [('under18',0,17),('age18_24',18,24),('age25_44',25,44),('age45_64',45,64),('age65plus',65,120)]:
            out[name]=(valid & age.between(lo,hi)).astype(int)
        out['age_missing']=(~valid).astype(int)
        out['employed']=work.eq('true').astype(int)
        out['employment_missing']=(~work.isin(['true','false'])).astype(int)
        total+=len(frame);unmatched+=int(frame.plz.isna().sum())
        pieces.append(out.groupby('plz').sum())
    result=pd.concat(pieces).groupby(level=0).sum().reindex(groups).fillna(0)
    return result,{'persons':total,'persons_without_postal_link':unmatched,
                   'interpretation':'Synthetic population attributes; no observed personal shopping outcomes. Age and employment are covariates, not identified causal effects.'}
