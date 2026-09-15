"""Audit household linkage candidates without treating donor IDs as household IDs."""
import argparse
from pathlib import Path
import pandas as pd
from ..data import write_json
from ..pipeline import digest


def audit(frame):
    frame=frame.copy()
    for col in ['Building','Household','h_id']:
        frame[col]=frame[col].astype('string').str.strip().replace('',pd.NA)
    known=frame.dropna(subset=['Household','Building','h_id'])
    donor_counts=known.groupby('h_id').Household.nunique()
    local=known.groupby(['Building','h_id']).Household.agg(['nunique','first'])
    missing=frame.loc[frame.Household.isna(),['id','Building','h_id']].copy()
    candidates=missing.merge(local.reset_index(),on=['Building','h_id'],how='left',validate='many_to_one')
    candidates['candidate_household']=candidates['first'].where(candidates['nunique'].eq(1))
    candidates['status']=candidates.candidate_household.notna().map({True:'candidate_requires_semantic_validation',False:'unresolved'})
    candidates=candidates.drop(columns=['first','nunique'])
    result={'persons':len(frame),'missing_household_persons':len(missing),
            'known_households':int(known.Household.nunique()),
            'h_ids_spanning_multiple_households':int(donor_counts.gt(1).sum()),
            'h_ids_with_known_households':len(donor_counts),
            'within_building_candidates':int(candidates.candidate_household.notna().sum()),
            'unresolved':int(candidates.candidate_household.isna().sum()),
            'automatic_assignments':0,
            'limitations':['h_id is not a unique synthetic household identifier; never group globally by it.',
                          'Same-building/h_id matches are candidates only: donor reuse can occur even within buildings.',
                          'Do not create one-person households for missing values. Validate identifier semantics or use constrained household synthesis.']}
    return candidates,result


def main():
    p=argparse.ArgumentParser();p.add_argument('--persons',required=True);p.add_argument('--output',required=True);args=p.parse_args()
    output=Path(args.output);output.mkdir(parents=True,exist_ok=False)
    frame=pd.read_csv(args.persons,usecols=['id','Building','Household','h_id'],dtype='string')
    candidates,result=audit(frame)
    candidates.to_parquet(output/'household_candidates.parquet',index=False)
    write_json(output/'summary.json',result)
    write_json(output/'provenance.json',{'persons_sha256':digest(args.persons),'code_sha256':digest(Path(__file__))})
    (output/'report.md').write_text('# Haushaltszuordnung\n\n'+str(result)+'\n',encoding='utf-8')
    print(result)


if __name__=='__main__': main()
