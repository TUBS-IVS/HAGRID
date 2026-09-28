"""Calendar-correct legacy seasonality and reproducible correlated time shocks."""
from datetime import date, timedelta
from functools import lru_cache
import numpy as np
import pandas as pd


@lru_cache(maxsize=8)
def weekly_profile(path):
    frame = pd.read_csv(path)
    # The legacy export's 2021 Mondays map exactly to ISO weeks 1..52.
    # Other export years contain ambiguous week-53 handling; do not inherit it.
    frame = frame.loc[frame.Year.eq(2021)].copy()
    frame['week'] = pd.to_datetime(frame.Date).dt.isocalendar().week.astype(int)
    if len(frame) != 52 or set(frame.week) != set(range(1,53)):
        raise ValueError('Legacy seasonal profile needs unique weeks 1..52 in 2021')
    values = frame.set_index('week').WeeklyMean.sort_index().to_numpy(float)
    if not np.isfinite(values).all() or (values <= 0).any():
        raise ValueError('Invalid weekly profile')
    return values


def factors(year, segment, cfg):
    days = [date(year,1,1)+timedelta(days=i) for i in range((date(year+1,1,1)-date(year,1,1)).days)]
    if cfg.get('weekly_profile_file'):
        if not np.allclose(cfg['monthly_weights'],1):
            raise ValueError('Weekly seasonality requires neutral monthly weights to prevent double counting')
        profile = weekly_profile(cfg['weekly_profile_file'])
        # Explicit cyclic interpolation for week 53, rather than copying a pandemic-year decline.
        seasonal = np.array([profile[d.isocalendar().week-1] if d.isocalendar().week<=52
                             else (profile[-1]+profile[0])/2 for d in days])
        strength = cfg.get('seasonality_strength',{}).get(segment,1.)
        if not np.isfinite(strength) or not 0<=strength<=2:
            raise ValueError('Seasonality strength must be 0..2')
        seasonal = seasonal**strength
    else:
        seasonal = np.array([cfg['monthly_weights'][d.month-1] for d in days])
    holidays = set(cfg['holiday_dates'])
    weights = seasonal*np.array([cfg['weekday_weights'][segment][d.weekday()]
        *(cfg['holiday_factor'] if str(d) in holidays else 1) for d in days])
    if not np.isfinite(weights).all() or (weights<0).any() or weights.sum()<=0:
        raise ValueError('Invalid calendar weights')
    return dict(zip(map(str,days),weights/weights.sum()))


@lru_cache(maxsize=4096)
def ar_value(seed, realization, channel, index, rho):
    if index < 0:
        raise ValueError('Temporal date precedes reference anchor')
    rng = np.random.default_rng(np.random.SeedSequence([seed,realization,channel]))
    noise = rng.normal(size=index+1)
    value = noise[0]
    for innovation in noise[1:]:
        value = rho*value+np.sqrt(1-rho*rho)*innovation
    return value


def slow_shock(stamp, realization, cfg):
    anchor = date(cfg['reference_year'],1,1)
    monday = lambda d: d-timedelta(days=d.weekday())
    week = (monday(stamp)-monday(anchor)).days//7
    year = stamp.year-anchor.year
    value = 0.; variance = 0.
    for label,index,channel in [('week',week,810),('year',year,811)]:
        sd = cfg.get(label+'_log_sd',0.)
        rho = cfg.get(label+'_rho',.7)
        if not np.isfinite(sd) or not 0<=sd<=2 or not 0<=rho<1:
            raise ValueError('Invalid correlated time variation')
        if sd:
            value += sd*ar_value(cfg['seed'],realization,channel,index,rho)
            variance += sd*sd
    return float(np.exp(value-.5*variance))


def write_time_report(output, summaries):
    data = pd.DataFrame(summaries)
    dates = pd.to_datetime(data.date)
    iso = dates.dt.isocalendar()
    data['week'] = iso.year.astype(str)+'-W'+iso.week.astype(str).str.zfill(2)
    data['month'] = dates.dt.strftime('%Y-%m')
    measures=['baseline_expected','conditional_expected','realized']
    tables=[]
    for period in ['date','week','month']:
        totals=data.groupby([period,'realization'])[measures].sum().reset_index()
        totals.to_csv(output/f'temporal_{period}.csv',index=False)
        summary=totals.groupby(period)[measures].quantile([.1,.5,.9]).unstack()
        summary.to_csv(output/f'temporal_{period}_quantiles.csv')
        tables.append('<h2>'+period+'</h2>'+summary.to_html(float_format=lambda x:f'{x:,.0f}'))
    page='<!doctype html><meta charset="utf-8"><title>Demand over time</title><style>body{font:16px system-ui;margin:40px}td,th{padding:8px}table{border-collapse:collapse}</style><h1>Demand over time</h1><p>10/50/90 % quantiles of the simulated paths; not calibrated prediction intervals. Weekly and monthly values cover only the requested days. Parameter and carrier uncertainty are not included.</p>'
    (output/'temporal.html').write_text(page+''.join(tables),encoding='utf-8')
