"""Street-anchored reference demand with an explicit unallocated observation ledger."""
import argparse
import json
from pathlib import Path

import geopandas as gpd
import numpy as np
import pandas as pd

from ..data import write_json
from .model import PROVIDERS
from ..pipeline import digest
from ..scope import filter_dhl


def constrain_streets(frame, observations, links):
    """Allocate only over unique candidate links; proximity remains an assumption.

    Ambiguous links are rejected BEFORE filtering observations, so excluding one
    candidate cannot turn an equidistant match into an apparently certain match.
    Unassignable demand stays at its source observation, never at a fake address.
    """
    if frame.site_id.duplicated().any() or observations.observation_id.duplicated().any():
        raise ValueError('Site and observation IDs must be unique')
    if not np.isfinite(observations.value).all() or (observations.value < 0).any():
        raise ValueError('Invalid observed demand')
    if not np.isfinite(frame.DHL).all() or (frame.DHL < 0).any():
        raise ValueError('Invalid prior DHL weights')
    result = frame.copy()
    result['prior_DHL'] = result.DHL
    covered = result.plz.isin(observations.plz)
    result['dhl_assignment_status'] = np.where(covered, 'no_unique_eligible_street', 'outside_observed_postal_coverage')
    result.loc[covered, 'DHL'] = 0.
    result['dhl_observation_id'] = pd.Series(pd.NA, index=result.index, dtype='string')
    candidates = links.drop_duplicates(['site_id', 'observation_id']).copy()
    counts = candidates.groupby('site_id').observation_id.transform('size')
    eligible = candidates.loc[counts.eq(1) & candidates.candidate_count.eq(1)
        & candidates.link_status.eq('nearest_within_postal_unverified')
        & ~candidates.repeated_street_key].copy()
    obs = observations[['observation_id', 'plz', 'value', 'repeated_street_key']].rename(columns={'plz': 'observed_plz'})
    mapping = eligible[['site_id', 'observation_id']].merge(obs, on='observation_id', validate='many_to_one')
    mapping = mapping.merge(frame[['site_id', 'plz', 'DHL']], on='site_id', validate='one_to_one')
    mapping = mapping.loc[mapping.plz.eq(mapping.observed_plz) & ~mapping.repeated_street_key & mapping.DHL.gt(0)].copy()
    denom = mapping.groupby('observation_id').DHL.transform('sum')
    mapping['allocated_DHL'] = mapping.value * mapping.DHL / denom
    by_site = mapping.set_index('site_id')
    assigned = result.site_id.isin(by_site.index)
    result.loc[assigned, 'DHL'] = result.loc[assigned, 'site_id'].map(by_site.allocated_DHL)
    result.loc[assigned, 'dhl_observation_id'] = result.loc[assigned, 'site_id'].map(by_site.observation_id)
    result.loc[assigned, 'dhl_assignment_status'] = 'street_anchored_model_weight_unverified_location'
    ledger = observations.copy()
    allocated = mapping.groupby('observation_id').allocated_DHL.sum()
    support = mapping.groupby('observation_id').site_id.size()
    ledger['allocated_DHL'] = ledger.observation_id.map(allocated).fillna(0.)
    ledger['eligible_site_count'] = ledger.observation_id.map(support).fillna(0).astype(int)
    ledger['unallocated_DHL'] = np.maximum(ledger.value - ledger.allocated_DHL, 0.)
    ledger['difference'] = ledger.allocated_DHL + ledger.unallocated_DHL - ledger.value
    ledger['allocation_status'] = np.where(ledger.eligible_site_count.gt(0), 'allocated_with_unverified_model_weights', 'no_positive_unique_site_support')
    ledger.loc[ledger.repeated_street_key, 'allocation_status'] = 'repeated_street_definition_unresolved'
    np.testing.assert_allclose(ledger['difference'], 0, atol=1e-8)
    np.testing.assert_allclose(result.loc[covered, 'DHL'].sum(), ledger.allocated_DHL.sum(), atol=1e-8)
    return result, ledger


def main(argv=None):
    parser = argparse.ArgumentParser()
    parser.add_argument('--model-run', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args(argv)
    source, output = Path(args.model_run), Path(args.output)
    cfg = json.loads((source/'config.resolved.json').read_text(encoding='utf-8'))
    if cfg['reference_year'] != 2021:
        raise ValueError('2021 reference model required')
    output.mkdir(parents=True, exist_ok=False)
    foundation = Path(cfg['foundation_run'])
    foundation_cfg = json.loads((foundation/'config.resolved.json').read_text(encoding='utf-8'))
    base = gpd.read_parquet(source/'baseline_sites.parquet')
    shares = pd.read_parquet(source/'carrier_site_profiles.parquet')
    data = base.merge(shares.drop(columns=['plz', 'recipient_type']), on='site_id', validate='one_to_one')
    frame = data[['site_id', 'plz', 'recipient_type', 'geometry']].copy()
    for provider in PROVIDERS:
        frame[provider] = data.mean_reference_operating_day * data['share_'+provider]
    observations = gpd.read_parquet(foundation/'dhl_observations.parquet')
    if set(observations.year.unique()) != {2021}:
        raise ValueError('Mixed observation years')
    observations = filter_dhl(observations, cfg.get('dhl_exclude_above'), output)
    links = pd.read_parquet(foundation/'dhl_candidate_links.parquet')
    result, ledger = constrain_streets(frame, observations, links)
    np.testing.assert_array_equal(result[PROVIDERS[1:]], frame[PROVIDERS[1:]])
    result['total_assigned'] = result[PROVIDERS].sum(axis=1)
    result.to_parquet(output/'reference_day_sites.parquet', index=False)
    ledger.to_parquet(output/'street_ledger.parquet', index=False)
    ledger.drop(columns='geometry').to_csv(output/'street_checks.csv', index=False)
    ledger.loc[ledger.unallocated_DHL.gt(1e-8)].to_parquet(output/'unallocated_street_demand.parquet', index=False)
    observed = ledger.groupby('plz').value.sum()
    placed = result.groupby('plz').DHL.sum().reindex(observed.index, fill_value=0)
    rest = ledger.groupby('plz').unallocated_DHL.sum()
    check = pd.DataFrame({'observed_DHL': observed, 'assigned_to_sites': placed, 'unallocated_at_streets': rest})
    check['difference'] = check.assigned_to_sites + check.unallocated_at_streets - check.observed_DHL
    np.testing.assert_allclose(check.difference, 0, atol=1e-8)
    check.to_csv(output/'postal_checks.csv')
    carriers = result.groupby('plz')[PROVIDERS].sum()
    carriers['DHL'] = carriers.DHL.add(rest, fill_value=0)
    carriers.to_csv(output/'carrier_postal_reference.csv')
    note = {'status': 'observation_constrained_street_reconstruction_not_validation',
        'reference_year': 2021, 'observations': len(ledger), 'postal_areas': len(check),
        'observed_DHL': float(ledger.value.sum()), 'assigned_to_sites': float(ledger.allocated_DHL.sum()),
        'unallocated_at_streets': float(ledger.unallocated_DHL.sum()),
        'streets_with_site_allocation': int(ledger.eligible_site_count.gt(0).sum()),
        'streets_with_positive_unallocated_demand': int(ledger.unallocated_DHL.gt(1e-8).sum()),
        'max_absolute_street_difference': float(ledger['difference'].abs().max()),
        'max_absolute_postal_difference': float(check.difference.abs().max()),
        'excluded_above': cfg.get('dhl_exclude_above'),
        'candidate_max_distance_m': foundation_cfg['max_street_distance_m'],
        'limitations': [
            'Exact street totals include the unallocated street ledger. This is data conditioning, not out-of-sample accuracy.',
            'Nearest-site candidate links at the recorded foundation threshold are unverified. Within-street weights and recipient segments remain modeled.',
            'Ambiguous or repeated-street links and missing/zero prior support stay unassigned. They are not snapped to another building.',
            'A zero DHL assignment at an unsupported site is not evidence of zero real demand. Consult dhl_assignment_status and the street ledger.',
            'Known source exclusions apply to entire observations before reconstruction; raw files are unchanged.',
            'Other carriers retain their model estimates. DHL calibration does not validate their amounts or shares.',
            'Outside observed postal coverage, prior estimates remain and are excluded from the exact-fit claim.',
            'Source daily-mean denominator and additivity of repeated rows remain unconfirmed. No annualization or future growth applied.']}
    write_json(output/'result.json', note)
    paths = [source/n for n in ['baseline_sites.parquet', 'carrier_site_profiles.parquet', 'config.resolved.json']]
    paths += [foundation/n for n in ['dhl_candidate_links.parquet', 'dhl_observations.parquet', 'config.resolved.json']]
    write_json(output/'provenance.json', {'inputs': {str(p.resolve()): digest(p) for p in paths},
        'code': {p.name: digest(p) for p in [Path(__file__), Path(__file__).parents[1] / 'scope.py']}})
    page = '<!doctype html><meta charset="utf-8"><title>DHL Straßenrekonstruktion 2021</title><style>body{font:16px system-ui;max-width:1150px;margin:35px auto;padding:0 20px;color:#243246}td,th{padding:7px}table{border-collapse:collapse;font-size:14px}</style><h1>DHL 2021: Straßen als Mengenanker</h1>'
    def number(v): return f'{v:,.1f}'.replace(',', '_').replace('.', ',').replace('_', '.')
    page += f'<p><b>{number(note["observed_DHL"])}</b> beobachtete Mengeneinheiten; <b>{number(note["assigned_to_sites"])}</b> an Standorten modelliert; <b>{number(note["unallocated_at_streets"])}</b> verbleiben an den beobachteten Straßen.</p>'
    page += '<p>Jede Straßenmenge bleibt erhalten. Die exakte Übereinstimmung wird durch die Beobachtung vorgegeben und ist kein Vorhersagetest. Standortnähe, B2B/B2C-Aufteilung und Mengen anderer Anbieter bleiben Modellannahmen. Unzugeordnete Mengen stehen in einer eigenen Datei mit ihrer ursprünglichen Straßengeometrie.</p>'
    table = check.rename(columns={'observed_DHL':'DHL beobachtet','assigned_to_sites':'Standorten zugeordnet',
        'unallocated_at_streets':'Offen an Straßen','difference':'Bilanzabweichung'})
    page += '<h2>Kontrolle nach PLZ</h2><p><label>PLZ filtern: <input id="postal-filter" placeholder="z. B. 30855" inputmode="numeric"></label></p>' + table.to_html(float_format=number,table_id='postal-table')
    page += '<script>document.getElementById("postal-filter").addEventListener("input",e=>{for(const row of document.querySelectorAll("#postal-table tbody tr"))row.hidden=!row.querySelector("th").textContent.includes(e.target.value.trim());});</script>'
    page += '<h2>Größte noch nicht auf Standorte verteilte Mengen</h2>' + ledger.loc[ledger.unallocated_DHL.gt(1e-8), ['plz','street','value','unallocated_DHL','allocation_status']].sort_values('unallocated_DHL',ascending=False).head(30).to_html(index=False)
    (output/'dashboard.html').write_text(page, encoding='utf-8')
    print(json.dumps(note, indent=2), flush=True)


if __name__ == '__main__':
    main()
