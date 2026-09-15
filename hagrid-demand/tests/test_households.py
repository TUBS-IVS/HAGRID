import pandas as pd
from hagrid_demand.households import audit


def test_reused_donor_ids_are_not_merged_into_households():
    data=pd.DataFrame({'id':['1','2','3','4'],'Building':['a','b','a','c'],
                       'Household':['ha','hb',None,None],'h_id':['donor']*4})
    candidates,result=audit(data)
    assert result['h_ids_spanning_multiple_households']==1
    assert result['automatic_assignments']==0
    assert candidates.loc[candidates.id.eq('3'),'candidate_household'].iloc[0]=='ha'
    assert candidates.loc[candidates.id.eq('4'),'candidate_household'].isna().all()
    assert data.Household.isna().sum()==2
