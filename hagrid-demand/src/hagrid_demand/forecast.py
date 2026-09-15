"""Frozen model application: normalized calendar, spatial fields and carrier counts."""

from datetime import date,timedelta
from functools import lru_cache
import numpy as np
import pandas as pd

from .model import PROVIDERS,unpack,load_legacy
from .spatial import spatial_basis,coefficients,normalize_weights
from .delivery import build_delivery_access,delivery_counts,export_hagrid
from .data import write_json
from .carriers import build_profiles


from .temporal import factors as calendar_factors, slow_shock, write_time_report


def apply_frozen(output,sites,model,priors,cfg,branches):
    c,b,shares=unpack(np.array(model['theta']),model['kind'],len(branches),priors)
    branch_rates=dict(zip(branches,b))
    weights=np.where(sites.recipient_type.eq('private'),sites.population*c/1000,
                     sites.business_exposure*sites.branch.map(branch_rates).fillna(float(np.median(b)))/1000)
    # Fitted weights remain separate from output expectations and realized draws.
    baseline=sites[['site_id','recipient_type','plz','geometry']].copy()
    baseline['mean_reference_operating_day']=weights
    baseline.to_parquet(output/'baseline_sites.parquet',index=False)
    local_profiles=build_profiles(sites,weights,shares,cfg,output)
    access=build_delivery_access(sites,cfg)
    access.to_parquet(output/'delivery_access.parquet',index=False)
    import geopandas as gpd
    postal_support=gpd.read_parquet(__import__('pathlib').Path(cfg['foundation_run'])/'postal_support.parquet')
    export_checks=[]
    years=sorted({date.fromisoformat(d).year for d in cfg['dates']})
    annual={};future_shares={}
    reference=cfg['reference_year'];curve=cfg['volume_curve']
    volumes=priors['volumes']
    for year in years:
        if year not in volumes.index or reference not in volumes.index: raise ValueError('Projection year outside supplied volume series')
        growth=float(volumes.loc[year,curve]/volumes.loc[reference,curve])
        if not np.isfinite(growth) or growth<=0: raise ValueError('Nonpositive volume projection')
        future=load_legacy(cfg['legacy_output'],year)
        for s,segment in enumerate(['private','business']):
            changed=shares[s]*future[segment]/np.maximum(priors[segment],1e-12)
            if changed.sum()<=0: raise ValueError('Empty future carrier support')
            future_shares[(year,segment)]=changed/changed.sum()
            annual[(year,segment)]=float(weights[sites.recipient_type.eq(segment)].sum()*cfg['reference_operating_days']*growth)
    summaries=[];postal=[];yearly=[]
    stock=None
    if cfg.get('stock_updates'):
        stock=pd.read_csv(cfg['stock_updates'],dtype={'site_id':str})
        if not {'site_id','year','multiplier'}<=set(stock) or stock.duplicated(['site_id','year']).any():
            raise ValueError('Stock updates need unique site_id/year and multiplier')
        if not set(stock.site_id)<=set(sites.site_id) or not np.isfinite(stock.multiplier).all() or (stock.multiplier<0).any():
            raise ValueError('Unknown site or invalid stock multiplier')
    dates=sorted(set(cfg['dates']))
    for (year,segment),total in annual.items():
        selection=sites.recipient_type.eq(segment).to_numpy()
        segment_id=0 if segment=='private' else 1
        change=np.divide(future_shares[(year,segment)],shares[segment_id],out=np.ones(7),where=shares[segment_id]>0)
        reference_profile=local_profiles[selection]*change
        reference_profile/=reference_profile.sum(axis=1)[:,None]
        effective=weights[selection]@reference_profile/max(weights[selection].sum(),1e-12)
        yearly.append({'year':year,'segment':segment,'expected_packages':total,
                       'carrier_shares':dict(zip(PROVIDERS,map(float,effective)))})
    for realization in range(cfg['realizations']):
        per_date={d:[] for d in dates}
        for segment_id,segment in enumerate(['private','business']):
            mask=sites.recipient_type.eq(segment).to_numpy();subset=sites.loc[mask]
            raw=weights[mask];spec=cfg['spatial'][segment]
            persistent=np.random.default_rng(np.random.SeedSequence([cfg['seed'],segment_id,500])).normal(size=len(raw))
            persistent=np.exp(cfg.get('persistent_site_log_sd',0)*persistent)
            basis=spatial_basis(np.column_stack([subset.geometry.x,subset.geometry.y]),spec['length_scale_m'],cfg['fourier_features'],cfg['seed'],segment_id)
            realization_seed=int(np.random.SeedSequence([cfg['seed'],realization,777]).generate_state(1)[0])
            calendars={y:calendar_factors(y,segment,cfg) for y in years}
            for day in dates:
                stamp=date.fromisoformat(day);offset=(stamp-date.fromisoformat(cfg['field_anchor_date'])).days
                if offset<0: raise ValueError('Forecast date precedes field anchor')
                field=basis@coefficients(realization_seed,segment_id,cfg['fourier_features'],spec['temporal_rho'],offset)
                adjusted=raw*persistent
                if stock is not None:
                    multipliers=stock.loc[stock.year.eq(stamp.year)].set_index('site_id').multiplier
                    adjusted=adjusted*subset.site_id.map(multipliers).fillna(1).to_numpy(float)
                prob,spatial=normalize_weights(adjusted,field,spec['log_sigma'])
                common=np.random.default_rng(np.random.SeedSequence([cfg['seed'],realization,stamp.toordinal(),10])).normal()
                specific=np.random.default_rng(np.random.SeedSequence([cfg['seed'],realization,stamp.toordinal(),11,segment_id])).normal()
                a,z=cfg['common_day_log_sd'],cfg['segment_day_log_sd']
                shock=np.exp(a*common+z*specific-.5*(a*a+z*z))*slow_shock(stamp,realization,cfg)
                total=annual[(stamp.year,segment)]*calendars[stamp.year][day]
                conditional=total*shock
                rng=np.random.default_rng(np.random.SeedSequence([cfg['seed'],realization,stamp.toordinal(),12,segment_id]))
                n=int(rng.poisson(conditional))
                site_counts=rng.multinomial(n,spatial)
                change=np.divide(future_shares[(stamp.year,segment)],shares[segment_id],out=np.ones(7),where=shares[segment_id]>0)
                share=local_profiles[mask]*change
                share/=share.sum(axis=1)[:,None]
                carrier_counts=rng.multinomial(site_counts,share)
                result=pd.DataFrame({'site_id':subset.site_id.to_numpy(),'segment':segment,
                                     'plz':subset.plz.fillna('unassigned').to_numpy(),
                                     'baseline_expected':prob*total,'conditional_expected':spatial*conditional})
                for k,p in enumerate(PROVIDERS):
                    result['expected_'+p]=result.conditional_expected*share[:,k]
                    result['count_'+p]=carrier_counts[:,k]
                if result[['count_'+p for p in PROVIDERS]].to_numpy().sum()!=n: raise AssertionError('Carrier count balance failed')
                if not np.isclose(result[['expected_'+p for p in PROVIDERS]].to_numpy().sum(),conditional): raise AssertionError('Carrier expected balance failed')
                per_date[day].append(result)
                summaries.append({'date':day,'realization':realization,'segment':segment,'baseline_expected':total,
                                  'conditional_expected':conditional,'realized':n,'shock':shock,
                                  'carrier_counts':dict(zip(PROVIDERS,map(int,carrier_counts.sum(axis=0)))),
                                  'redistribution_pct':float(.5*np.abs(spatial-prob).sum()*100)})
        for day,parts in per_date.items():
            result=pd.concat(parts,ignore_index=True)
            result.to_parquet(output/f'demand_{day}_r{realization}.parquet',index=False)
            grouped=result.groupby(['plz','segment']).sum(numeric_only=True).reset_index()
            grouped['date']=day;grouped['realization']=realization
            postal.extend(__import__('json').loads(grouped.to_json(orient='records')))
            destinations=delivery_counts(result,access,PROVIDERS)
            destinations.to_parquet(output/f'delivery_{day}_r{realization}.parquet',index=False)
            if cfg.get('export_geopackage',True):
                check=export_hagrid(destinations,access,postal_support,output/f'hagrid_{day}_r{realization}.gpkg')
                export_checks.append({'date':day,'realization':realization,**check})
    write_time_report(output,summaries)
    write_json(output/'daily_summary.json',summaries)
    write_json(output/'annual_projection.json',yearly)
    pd.DataFrame(postal).to_parquet(output/'postal_demand.parquet',index=False)
    write_json(output/'export_checks.json',export_checks)
    return summaries,yearly
