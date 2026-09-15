"""End-to-end calibrated provisional workflow and frozen-model application."""



from datetime import datetime,timezone,date,timedelta

import json

from pathlib import Path

import re

import html

import numpy as np

import pandas as pd

import geopandas as gpd



from ..data import write_json,valid_points

from .model import prepare_training,load_legacy,fit_compare,PROVIDERS,predict_groups

from .forecast import apply_frozen

from ..pipeline import digest





def config(path):

    path=Path(path).resolve();cfg=json.loads(path.read_text(encoding='utf-8'))

    for key in ['foundation_run','output_dir','legacy_output','delivery_mapping','network_file','stock_updates','weekly_profile_file']:

        if cfg.get(key): cfg[key]=str((path.parent/cfg[key]).resolve())

    if not 0<cfg['holdout_fraction']<.5 or not 0<cfg['validation_fraction']<.5: raise ValueError('Invalid split fractions')

    if not isinstance(cfg['realizations'],int) or not 1<=cfg['realizations']<=100: raise ValueError('realizations must be 1..100')

    if not isinstance(cfg['seed'],int) or cfg['seed']<0: raise ValueError('Nonnegative integer seed required')

    if cfg['reference_operating_days']<=0: raise ValueError('Operating-day conversion must be positive')

    if cfg.get('date_start') or cfg.get('date_end'):

        start=date.fromisoformat(cfg['date_start']);end=date.fromisoformat(cfg['date_end'])

        if end<start or (end-start).days>3660: raise ValueError('Date range must be 0..3660 days')

        cfg['dates']=[str(start+timedelta(days=i)) for i in range((end-start).days+1)]

    if not cfg['dates']: raise ValueError('At least one projection date required')

    for d in cfg['dates']: date.fromisoformat(d)

    if cfg['volume_curve'] not in ['linear','logistic','exponential']: raise ValueError('Unknown legacy projection curve')

    for segment in ['private','business']:

        if len(cfg['weekday_weights'][segment])!=7: raise ValueError('Seven weekday weights required')

        spec=cfg['spatial'][segment]

        if not 0<=spec['log_sigma']<=3 or spec['length_scale_m']<=0 or not 0<=spec['temporal_rho']<1: raise ValueError('Invalid spatial parameters')

    if len(cfg['monthly_weights'])!=12: raise ValueError('Twelve monthly weights required')

    if not 8<=cfg['fourier_features']<=512: raise ValueError('Invalid number of Fourier features')

    for key in ['branch_log_sd','carrier_log_sd','market_share_sd','b2b_share_sd']:

        if cfg[key]<=0: raise ValueError(f'{key} must be positive')

    for key in ['common_day_log_sd','segment_day_log_sd']:

        if not 0<=cfg[key]<=2: raise ValueError(f'Invalid {key}')

    if not 0<=cfg.get('persistent_site_log_sd',0)<=2: raise ValueError('Invalid persistent site variation')

    return cfg





def run_model(path,run_id,frozen_run=None):

    cfg=config(path)

    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,100}',run_id): raise ValueError('Invalid run_id')

    source=Path(cfg['foundation_run']);output=Path(cfg['output_dir'])/run_id

    if output.resolve().is_relative_to(source): raise ValueError('Run output must not overwrite source')

    output.mkdir(parents=True,exist_ok=False)

    state={'run_id':run_id,'status':'running','started_at':datetime.now(timezone.utc).isoformat(),'stages':[]}

    def stage(name):

        state['stages'].append(name);write_json(output/'run.json',state);print('Completed: '+name,flush=True)

    write_json(output/'run.json',state)

    try:

        write_json(output/'config.resolved.json',cfg)

        consumed=[source/n for n in ['sites.parquet','site_postal_candidates.parquet','dhl_observations.parquet','hermes_observations.parquet','summary.json']]

        consumed += [Path(cfg['legacy_output'])/n for n in ['00_markedshare_with_amazon.csv','05_optimized_b2b_shares_by_year.csv','01_b2b_forecast_complete.csv','02_parcel_volumen_estimation_complete.csv']]

        consumed += [Path(cfg[k]) for k in ['delivery_mapping','network_file','stock_updates','weekly_profile_file'] if cfg.get(k)]

        provenance={'inputs':{str(p):digest(p) for p in consumed},'config_sha256':digest(path),

                    'code':{p.relative_to(Path(__file__).parents[1]).as_posix():digest(p) for p in [*Path(__file__).parent.iterdir(), *Path(__file__).parents[1].iterdir()] if p.suffix in {'.py','.html'}},

                    'mode':'frozen_apply' if frozen_run else 'fit_select_apply'}

        write_json(output/'provenance.json',provenance)

        from ..scope import filter_dhl
        filter_dhl(pd.read_parquet(source/'dhl_observations.parquet'),cfg.get('dhl_exclude_above'),output)
        sites,data=prepare_training(source,cfg.get('business_size_power',1.),cfg.get('dhl_exclude_above'))

        # Use GeoParquet CRS rather than inferring it from coordinates or configuration.

        spatial=gpd.read_parquet(source/'sites.parquet')[['site_id','geometry']]

        sites=sites.drop(columns='geometry').merge(spatial,on='site_id',validate='one_to_one')

        sites=gpd.GeoDataFrame(sites,geometry='geometry',crs=spatial.crs)

        if not valid_points(sites.geometry).all(): raise ValueError('Resolve invalid site points before spatial application')

        priors=load_legacy(cfg['legacy_output'],cfg['reference_year'])

        h=data.pop('hermes_table');h=h.loc[h.year.eq(cfg['reference_year'])]

        if h.plz.duplicated().any(): raise ValueError('Duplicate Hermes reference-year postal rows')

        data['hermes']=h.set_index('plz').value.reindex(data['plz']).to_numpy(float)

        stage('prepare_observations_and_priors')

        if frozen_run:

            stored=json.loads((Path(frozen_run)/'model.json').read_text(encoding='utf-8'))

            if stored['sites_sha256']!=digest(source/'sites.parquet'): raise ValueError('Frozen model requires its original site feature snapshot')

            if stored['reference_year']!=cfg['reference_year']: raise ValueError('Cannot change frozen model reference year')

            if stored.get('business_size_power',1.)!=cfg.get('business_size_power',1.): raise ValueError('Frozen model requires original business size exponent')

            if stored.get('dhl_exclude_above')!=cfg.get('dhl_exclude_above'): raise ValueError('Observation scope changed: refit model required')
            model=stored['model'];data['branches']=stored['branches']

            for key in ['market','q','private','business']: priors[key]=np.array(stored['priors'][key])

            priors['b2b']=stored['priors']['b2b']

            evaluation={'selected':model['kind'],'mode':'frozen_apply_no_refit','original_model':str(Path(frozen_run).resolve()),

                        'limitations':['Evaluation metrics belong to the original model run. This run does not refit.']}

        else:

            model,evaluation,predictions=fit_compare(data,priors,cfg)

            predictions.to_csv(output/'spatial_evaluation.csv',index=False)

        frozen={'dhl_exclude_above':cfg.get('dhl_exclude_above'),'business_size_power':cfg.get('business_size_power',1.),'reference_year':cfg['reference_year'],'model':model,'branches':data['branches'],

                'sites_sha256':digest(source/'sites.parquet'),

                'priors':{key:priors[key].tolist() for key in ['market','q','private','business']},

                'status':'provisional_calibration_under_declared_assumptions'}

        frozen['priors']['b2b']=priors['b2b']

        write_json(output/'model.json',frozen);write_json(output/'evaluation.json',evaluation)

        stage('fit_select_freeze' if not frozen_run else 'load_frozen_model')

        daily,annual=apply_frozen(output,sites,model,priors,cfg,data['branches'])

        stage('calendar_spatial_future_and_carrier_sampling')

        stage('delivery_export')

        C,B=predict_groups(np.array(model['theta']),model['kind'],data,priors)

        carrier=[{'carrier':p,'private':float(C[:,i].sum()),'business':float(B[:,i].sum()),

                  'own_b2b_pct':float(B[:,i].sum()/max((C+B)[:,i].sum(),1e-12)*100)} for i,p in enumerate(PROVIDERS)]

        if (output/'carrier_conditional_reference.json').exists():
            carrier=json.loads((output/'carrier_conditional_reference.json').read_text(encoding='utf-8'))
        result={'run_id':run_id,'status':'provisional_not_validated_current_demand','selected':model['kind'],

                'evaluation':evaluation,'carrier_reference':carrier,'daily':daily,'annual':annual,

                'assumptions':cfg['assumptions'],'unimplemented_or_unavailable_evidence':[

                  'Exact PANDA OSM/Zensus reproduction needs missing raw files; these are alternative HAGRID-feature candidates.',

                  'No independent B2B or non-DHL absolute accuracy established; Hermes is shape-only.',

                  'Spatial/day-noise and future paths are assumptions, not calibrated confidence intervals.',

                  'No verified entrances or routed tours without a supplied mapping/network. Service events are proxies.',

                  'No new future addresses are synthesized. Optional stock multipliers redistribute existing site demand while retaining the annual segment totals.']}

        write_json(output/'result.json',result)

        render(output,result)

        stage('dashboard')

        state.update(status='complete_provisional',finished_at=datetime.now(timezone.utc).isoformat());write_json(output/'run.json',state)

        return output

    except Exception as exc:

        state.update(status='failed',error=f'{type(exc).__name__}: {exc}');write_json(output/'run.json',state);raise





def render(output,result):

    cfg=json.loads((output/'config.resolved.json').read_text(encoding='utf-8'))

    polygons=gpd.read_parquet(Path(cfg['foundation_run'])/'postal_support.parquet')

    shapes=[]

    for row in polygons.itertuples():

        geom=row.geometry.simplify(40,preserve_topology=True)

        parts=[geom] if geom.geom_type=='Polygon' else list(geom.geoms)

        path=' '.join('M'+' L'.join(f'{x:.0f},{-y:.0f}' for x,y in ring.coords)+' Z'

                      for part in parts for ring in [part.exterior,*part.interiors])

        shapes.append({'plz':row.plz,'path':path})

    bounds=polygons.total_bounds

    result={**result,'postal':json.loads(pd.read_parquet(output/'postal_demand.parquet').to_json(orient='records')),

            'shapes':shapes,'map_box':[float(bounds[0]),float(-bounds[3]),float(bounds[2]-bounds[0]),float(bounds[3]-bounds[1])]}

    data=json.dumps(result,ensure_ascii=False,allow_nan=False).replace('<','\\u003c').replace('&','\\u0026')

    template=Path(__file__).with_name('model.html').read_text(encoding='utf-8')

    (output/'dashboard.html').write_text(template.replace('</body>','<p><a href="temporal.html">Zeitverlauf: Tage, Wochen, Monate und Simulationsbänder</a></p></body>').replace('__RUN__',html.escape(result['run_id'])).replace('__DATA__',data),encoding='utf-8')

    if (output/'carriers.html').exists():
        dashboard=output/'dashboard.html'
        dashboard.write_text(dashboard.read_text(encoding='utf-8').replace('</body>','<p><a href="carriers.html">Lokale Anbieterprofile und Annahmen</a></p></body>'),encoding='utf-8')
    e=result['evaluation'];test=e.get('test_dhl')

    report=f"# Gemeinsames Nachfragemodell: {result['run_id']}\n\nStatus: vorlÃ¤ufige Kalibrierung unter dokumentierten Annahmen.\n\nGewÃ¤hlt: {result['selected']}\n\n"

    if test: report+=f"DHL-Test-wMAPE: {test['wMAPE']:.2%}; Bias: {test['bias']:.2%}; {test['groups']} Test-PLZ.\n\n"

    report+='## Annahmen\n\n'+'\n'.join('- '+x for x in result['assumptions'])

    report+='\n\n## PrÃ¼fgrenzen\n\n'+'\n'.join('- '+x for x in result['unimplemented_or_unavailable_evidence'])

    report+='\n\nDashboard: [dashboard.html](dashboard.html). Modell, Datenherkunft und Evaluation liegen als JSON vor.\n'

    (output/'report.md').write_text(report,encoding='utf-8')
