from datetime import date
import numpy as np
import pandas as pd
import pytest
from hagrid_demand.temporal import factors, slow_shock


def test_week_53_leap_year_and_no_double_seasonality(tmp_path):
    path=tmp_path/'profile.csv'
    pd.DataFrame({'Year':[2021]*52,'Date':pd.date_range('2021-01-04',periods=52,freq='7D'),
                  'WeeklyMean':np.arange(1,53)}).to_csv(path,index=False)
    cfg={'weekly_profile_file':str(path),'monthly_weights':[1]*12,'holiday_dates':[],
         'holiday_factor':0,'weekday_weights':{'private':[1]*7}}
    for year in [2020,2021,2026]:
        f=factors(year,'private',cfg)
        assert np.isclose(sum(f.values()),1)
        assert len(f)==(366 if year==2020 else 365)
    f=factors(2021,'private',cfg)
    assert np.isclose(f['2021-01-01']/f['2021-01-04'],26.5)
    cfg['monthly_weights'][0]=2
    with pytest.raises(ValueError,match='double counting'): factors(2021,'private',cfg)


def test_correlated_shocks_are_date_stable_and_shared_within_week():
    cfg={'reference_year':2021,'seed':42,'week_log_sd':.2,'week_rho':.7,'year_log_sd':.1}
    a=slow_shock(date(2021,12,20),0,cfg)
    assert a==slow_shock(date(2021,12,26),0,cfg)
    assert a!=slow_shock(date(2021,12,20),1,cfg)
    slow_shock(date(2026,1,1),0,cfg)
    assert a==slow_shock(date(2021,12,20),0,cfg)
    assert slow_shock(date(2021,1,1),0,{'reference_year':2021,'seed':42})==1
