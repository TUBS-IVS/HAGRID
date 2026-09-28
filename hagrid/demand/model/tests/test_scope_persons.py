import pandas as pd
import numpy as np
from hagrid_demand.scope import filter_dhl
from hagrid_demand.person_features import aggregate_persons


def test_scope_strict_threshold_before_aggregation_keeps_raw(tmp_path):
    frame=pd.DataFrame({'plz':['a','a','b'],'value':[1000.,1001.,2.]})
    kept=filter_dhl(frame,1000,tmp_path)
    assert kept.value.sum()==1002
    assert frame.value.sum()==2003
    assert pd.read_csv(tmp_path/'excluded_dhl_observations.csv').value.tolist()==[1001.]


def test_person_counts_preserve_age_unknowns_and_mapping(tmp_path):
    p=tmp_path/'persons.csv'
    pd.DataFrame({'Building':['a','a','b'],'age':[20,np.nan,70],'employed':[True,False,False]}).to_csv(p,index=False)
    sites=pd.DataFrame({'source_key':['building:a'],'plz':['1'],'recipient_type':['private']})
    result,quality=aggregate_persons(p,sites,['1'])
    assert result.loc['1','age18_24']==1 and result.loc['1','age_missing']==1
    assert result.loc['1','employed']==1
    assert quality['persons_without_postal_link']==1
