"""Reference-year diagnosis; no forecast, calendar conversion or simulation."""
import html
import json
from pathlib import Path

import numpy as np
import pandas as pd

from .model import prepare_training, load_legacy, fit_candidate, predict_groups, metrics
from ..pipeline import digest
from ..data import write_json
from .workflow import config


def special_offsets(observations, special, groups):
    """Require exact unique raw street/value matches before subtracting an offset."""
    offset = pd.Series(0., index=groups)
    seen = set()
    for row in special.itertuples():
        key = (str(row.plz), row.name)
        if key in seen:
            raise ValueError('Duplicate special-customer street')
        seen.add(key)
        match = observations.loc[observations.plz.eq(key[0]) & observations.street.eq(key[1])]
        if len(match) != 1 or not np.isclose(match.value.iloc[0], row.vm_tag):
            raise ValueError(f'Special customer does not uniquely match raw data: {key}')
        if not np.isfinite(row.exzess) or not 0 <= row.exzess <= row.vm_tag:
            raise ValueError('Invalid special-customer excess')
        if key[0] not in offset.index:
            raise ValueError('Special customer outside model support')
        offset.loc[key[0]] += row.exzess
    return offset.to_numpy()


def run_diagnostics(config_path, run_id, special_path):
    cfg = config(config_path)
    if cfg.get('dhl_exclude_above') is not None:
        raise ValueError('Legacy raw/core comparison: use model_search for the configured CEP scope')
    if cfg['reference_year'] != 2021:
        raise ValueError('This diagnosis requires the confirmed DHL reference year 2021')
    source = Path(cfg['foundation_run'])
    output = Path(cfg['output_dir']) / run_id
    output.mkdir(parents=True, exist_ok=False)
    _, data = prepare_training(source)
    h = data.pop('hermes_table').query('year == 2021')
    data['hermes'] = h.set_index('plz').value.reindex(data['plz']).to_numpy(float)
    priors = load_legacy(cfg['legacy_output'], 2021)
    obs = pd.read_parquet(source / 'dhl_observations.parquet')
    special = pd.read_csv(special_path, dtype={'plz': str})
    offset = special_offsets(obs, special, data['plz'])
    raw = data['dhl'].copy()
    rows = []
    # All models share folds. This is exploratory comparison, not a new blind test.
    for seed in [42, 73, 101]:
        folds = np.array_split(np.random.default_rng(seed).permutation(len(raw)), 5)
        for mode in ['raw', 'conditional_special_customers']:
            target = raw if mode == 'raw' else raw-offset
            work = {**data, 'dhl': target}
            for kind in ['pooled', 'branches', 'joint']:
                predicted = np.zeros(len(raw))
                for ids in folds:
                    train = np.ones(len(raw), dtype=bool)
                    train[ids] = False
                    fitted = fit_candidate(kind, work, priors, cfg, train)
                    C, B = predict_groups(np.array(fitted['theta']), kind, work, priors)
                    predicted[ids] = (C+B)[ids, 0]
                for i, plz in enumerate(data['plz']):
                    rows.append(dict(seed=seed, mode=mode, model=kind, plz=plz,
                                     raw=raw[i], target=target[i], predicted=predicted[i],
                                     known_offset=0. if mode == 'raw' else offset[i]))
                print(f'{seed} {mode} {kind}: {metrics(target,predicted)["wMAPE"]:.1%}', flush=True)
    frame = pd.DataFrame(rows)
    frame.to_csv(output/'out_of_fold.csv', index=False)
    scores = []
    for (mode, kind, seed), part in frame.groupby(['mode', 'model', 'seed']):
        scores.append(dict(mode=mode, model=kind, seed=int(seed), **metrics(part.target, part.predicted)))
    table = pd.DataFrame(scores)
    table.to_csv(output/'metrics.csv', index=False)
    special.to_csv(output/'special_customer_assumptions.csv', index=False)
    volumes = priors['volumes']
    growth = {str(y): float(volumes.loc[y,cfg['volume_curve']]/volumes.loc[2021,cfg['volume_curve']]-1)
              for y in [2021,2026,2030]}
    result = dict(reference_year=2021, growth_applied=False, noise_applied=False,
                  legacy_growth_not_used=growth, special_offset=float(offset.sum()),
                  raw_total=float(raw.sum()), metrics=scores,
                  limitations=['Exploratory repeated five-fold postal CV after the old test was inspected; no untouched test claim.',
                  'Special-customer offsets come from PANDA and the same DHL observations. Conditional core errors do not validate prediction of these customers.',
                  'PANDA confirmed flag is automatically prefilled by its detector; independent manual confirmation is not established.',
                  'DHL daily-mean denominator and population/company vintages remain unconfirmed.',
                  'No growth, 313-day conversion, calendar weights, random fluctuations or MATSim run in this diagnostic.'])
    write_json(output/'result.json',result)
    paths=[Path(config_path),Path(special_path),*[source/n for n in ['sites.parquet','site_postal_candidates.parquet','dhl_observations.parquet','hermes_observations.parquet']]]
    paths += list(Path(cfg['legacy_output']).glob('0[0125]_*.csv'))
    write_json(output/'provenance.json',{'inputs':{str(p.resolve()):digest(p) for p in paths},
        'code':{p.relative_to(Path(__file__).parents[1]).as_posix():digest(p) for p in [*Path(__file__).parent.glob('*.py'),*Path(__file__).parents[1].glob('*.py')]}})
    summary=table.groupby(['mode','model'])[['wMAPE','bias']].agg(['mean','min','max'])
    content='<h1>DHL 2021: reproduction and error diagnosis</h1><p>No scale-up, no simulated daily fluctuations. Errors on the respective held-out PLZ.</p>'
    content+=summary.to_html(float_format=lambda x:f'{x:.1%}')
    content+='<h2>Validation limits</h2><ul>'+''.join('<li>'+html.escape(x)+'</li>' for x in result['limitations'])+'</ul>'
    content+=f'<p>Raw volume: {raw.sum():,.0f}; separately assumed large-customer volume: {offset.sum():,.1f}.</p>'
    (output/'dashboard.html').write_text('<!doctype html><html lang="en"><meta charset="utf-8"><title>DHL 2021 diagnosis</title><style>body{font:17px system-ui;max-width:1200px;margin:40px auto;padding:20px}td,th{padding:10px;text-align:right}li{margin:12px}</style>'+content+'</html>',encoding='utf-8')
    return output
