"""Build a single offline entry point for logistics checks and reference reconstruction."""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
from html import escape

import geopandas as gpd
import numpy as np
import pandas as pd

from ..data import write_json
from .logistics_osm import audit_map, parse, street_distances
from ..pipeline import digest
from .provenance import code_hashes as package_code_hashes


def _code_hashes(package_root=None):
    root = Path(package_root).resolve() if package_root is not None else Path(__file__).resolve().parents[1]
    return package_code_hashes(root, "experimental/continuation_report.py", "experimental/logistics_osm.py", "data.py", "pipeline.py")


def number(value, digits=1):
    return f'{value:,.{digits}f}'.replace(',', '_').replace('.', ',').replace('_', '.')


# The evaluation keys stay German because they are the keys of summary.json["wmape"]; only the display is English.
EVALUATION_LABELS = {'Räumliche Gruppen': 'Spatial groups', 'Zufällige Aufteilungen': 'Random splits'}


def main():
    p = argparse.ArgumentParser()
    for name in ['osm-run','search-run','street-run','foundation','output']:
        p.add_argument('--'+name,required=True)
    p.add_argument('--historical-probe')
    args = p.parse_args()
    osm, search, street, source, output = (Path(getattr(args,k)) for k in ['osm_run','search_run','street_run','foundation','output'])
    output.mkdir(parents=True,exist_ok=True)
    def read(path): return json.loads(path.read_text(encoding='utf-8'))
    summary, reconstruction, protocol = read(osm/'summary.json'),read(street/'result.json'),read(search/'protocol.json')
    current = summary['current']
    scores = pd.read_csv(search/'metrics.csv')
    scores['evaluation'] = np.where(scores.layout.eq('spatial'),'Räumliche Gruppen','Zufällige Aufteilungen')
    table = scores.groupby(['model','evaluation']).wMAPE.mean().unstack()
    predictions = pd.read_csv(search/'predictions.csv',dtype={'plz':str})
    focus = predictions.loc[predictions.layout.eq('spatial') & predictions.plz.eq('30855'),['model','observed','predicted']]
    postal = pd.read_csv(street/'postal_checks.csv',dtype={'plz':str})
    local = postal.loc[postal.plz.eq('30855')].iloc[0]
    share = reconstruction['assigned_to_sites']/reconstruction['observed_DHL']
    near = current['volume_street_within_100m_with_source_context']/current['street_volume_total']
    mid = current['volume_midpoint_within_100m_with_source_context']/current['street_volume_total']
    names = {'baseline':'Previous baseline model','person_spatial_100':'Persons + space',
        'logistics_residual_10':'Logistics, weaker regularization',
        'logistics_residual_100':'Logistics, stronger regularization',
        'logistics_spatial_100':'Logistics + persons + space',
        'logistics_additive_10':'Additive logistics correction','nested_selection':'Selection within the training data'}
    def link(path): return escape(os.path.relpath(path,output).replace('\\','/'),quote=True)
    nav = ' · '.join(f'<a href="{link(path)}">{title}</a>' for title,path in [
        ('OSM detail check',osm/'dashboard.html'),('Model comparison',search/'dashboard.html'),
        ('Street reconstruction',street/'dashboard.html'),('Methods report',output/'report.md')])
    page = '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HAGRID – Langenhagen and street anchors</title>'
    page += '<style>body{font:16px/1.5 system-ui;max-width:1120px;margin:35px auto;padding:0 22px;color:#213449;background:#fafbfc}h1,h2{color:#123b60}a{color:#125b98}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:15px}.card{background:#fff;border:1px solid #d4e0e9;border-radius:10px;padding:18px}.big{font-size:30px;font-weight:700;display:block}.note{padding:16px;border-left:4px solid #bd7820;background:#fff4df}table{border-collapse:collapse;width:100%;font-size:14px;background:#fff}td,th{padding:9px;text-align:right;border-bottom:1px solid #d8e0e9}th:first-child,td:first-child{text-align:left}nav{margin:20px 0}details{margin:20px 0}svg{border:1px solid #d4e0e9;border-radius:8px}code{word-break:break-word}</style>'
    page += '<h1>Checking Langenhagen. Anchoring DHL 2021 to streets.</h1><nav>'+nav+'</nav>'
    page += '<p>Analysis dated '+datetime.now(timezone.utc).strftime('%d.%m.%Y')+': spatially match logistics sites, test additional features and run the outstanding street reconstruction.</p><div class="cards">'
    for value,label in [(number(reconstruction['observed_DHL'],0),'DHL volume within the defined CEP scope'),
        (number(share*100,2)+' %','assigned to sites with model weights'),
        (number(reconstruction['unallocated_at_streets'],0),'volume units remain at streets')]:
        page += '<div class="card"><span class="big">'+value+'</span>'+label+'</div>'
    page += '</div><h2>What the logistics check shows</h2>'
    page += f'<p>PLZ 30855 contains {current["explicit_objects"]} OSM objects with explicit warehouse/logistics tags, including {current["warehouse_building_objects"]} warehouse building objects. These are map objects, not a unique count of companies. The available firm file contains {current["firms"]} firms and {number(current["employees"],0)} employees; {current["transport_storage_firms"]} firms belong to the transport/storage branch.</p>'
    page += '<p>The <a href="https://group.dhl.com/en/media-relations/press-releases/2019/dhl-freight-opens-new-freight-hub-in-hanover-langenhagen.html">DHL Freight center opened in 2019</a>. The <a href="https://hermesworld.com/int/about-us/history/current-decade/">Hermes hub is also documented before 2021</a>. Transshipment volumes of such centers must not additionally be counted as parcel deliveries to local recipients. The sources do not establish any specific DHL volume in our street observations.</p>'
    page += f'<p>Streets that come within 100 meters of mapped warehouse/logistics objects carry <b>{number(near*100)} %</b> of the DHL volume in 30855. Measured at 100 meters from the street midpoint, it is <b>{number(mid*100)} %</b>. The DHL Freight and Hermes areas additionally classified from company sources do not change these values here. Proximity is not a delivery assignment; the differing values show the sensitivity of the measure. A blanket logistics surcharge is therefore not justified.</p>'
    boundary = gpd.read_parquet(source/'postal_support.parquet')
    boundary = boundary.loc[boundary.plz.eq('30855')].geometry.union_all()
    streets = gpd.read_parquet(source/'dhl_observations.parquet').to_crs(25832)
    streets = streets.loc[streets.plz.eq('30855')]
    facilities = gpd.read_parquet(osm/'facilities_current.parquet')
    page += '<p>Blue: DHL streets. Orange: explicit warehouse/logistics tags. Gray: other candidates. Hovering shows names and volumes. The geometries come from the current server data.</p>'
    page += audit_map(boundary,streets,facilities)
    dates=', '.join(sorted(set(t[:10] for t in current['server_data_timestamps'])))
    page += '<p><small>Geodata: <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors, ODbL</a>. Server data versions used, including the supplementary name query: '+dates+'. OSM footprint area is neither usable floor area nor parcel volume.</small></p>'
    historical = None
    if args.historical_probe:
        historical_osm = parse(read(Path(args.historical_probe)))
        historical_osm = historical_osm.loc[historical_osm.geometry.intersects(boundary)]
        matches = street_distances(streets,historical_osm)
        historical = {'warehouse_ways_in_30855':len(historical_osm),
            'street_volume_within_100m':float(matches.loc[matches.distance_m.le(100),'value'].sum()),
            'scope':'Partial local query: ways with building=warehouse, snapshot 2021-12-31; not complete regional logistics coverage'}
    page += '<div class="note"><b>Historical data access remains open.</b> The complete 2021 queries failed with timeouts at several public Overpass endpoints; the tested ohsome endpoint responded with HTTP 403. Successful partial queries and errors are stored.'
    if historical:
        page += f' An earlier, narrowly limited warehouse-building query returned {historical["warehouse_ways_in_30855"]} matching objects within 30855. This is not a complete historical logistics inventory and no proof that the remaining halls were built only later.'
    page += '</div><h2>Do logistics features improve the estimate?</h2>'
    page += '<p>Tested were regional warehouse footprints, industrial building footprints and the number of explicitly tagged logistics objects. The additional models correct the previous base demand model together with person features; variants use regularization, a spatial correction or an additive correction. There is no freely set Langenhagen constant.</p>'
    page += '<p class="note"><b>Retrospective test with current auxiliary data:</b> The following errors refer to DHL 2021, but the additional OSM features refer to 2026. This comparison shows spatial relationships under this assumption. It is not a historically valid prediction test for 2021 and no approval for the main model.</p>'
    page += table.rename(index=names,columns=EVALUATION_LABELS).to_html(float_format=lambda x:number(x*100,2)+' %')
    page += '<p>wMAPE is the sum of absolute errors divided by the observed total volume. The same three random 5-fold splits and five spatial groups as before are compared. The selection variant chooses the model exclusively within inner training splits. The entire experiment remains exploratory because of repeatedly examined data and later OSM features.</p>'
    page += '<details><summary>What is predicted for Langenhagen without its DHL test values?</summary>' + focus.replace({'model':names}).rename(columns={'model':'Model','observed':'Observed','predicted':'Predicted'}).to_html(index=False,float_format=number)+'</details>'
    page += '<h2>The new reference for 2021</h2>'
    page += f'<p>The new reconstruction preserves all <b>{reconstruction["observations"]}</b> included street observations in {reconstruction["postal_areas"]} PLZ. Site volume plus open street volume matches each observation. In 30855 that is {number(local.observed_DHL,0)}: {number(local.assigned_to_sites,0)} are distributed with site weights and {number(local.unallocated_at_streets,0)} remain at streets.</p>'
    page += '<p>Within a street, the previous person/firm model weights the unique site candidates. Ambiguous assignments are discarded before observations are excluded. Without a matching site, the volume stays in a separate file with the original street geometry. A site without an assigned volume is therefore not empirically shown to be free of demand.</p>'
    page += '<p><b>The exact totals are a data constraint, not an error measure for predictions.</b> Other carriers keep their estimated volumes. B2B/B2C shares, within-town site weights and volumes of other LSPs are therefore not independently confirmed. The new street reference is a separately executed step; it is not yet connected as the starting point of the daily/future simulation.</p>'
    page += '<h2>Methodological consequence</h2><p>For the known 2021 state, use the street observations as volume anchors. Check logistics sites as a separate site role: freight transshipment, parcel sorting, fulfillment and the demand of a recipient firm have different meanings. For missing observations, the previous persons/space candidate remains the benchmark. These tests do not support a new general carrier or logistics surcharge.</p>'
    page += '<p>To project forward from this street reference, volume changes must be applied jointly to the site part and the open street part. Calendar profiles and spatial fluctuations can then be used. This connection and a complete historical building inventory remain further work; no future volumes were recalibrated in this run.</p><nav>'+nav+'</nav></html>'
    (output/'dashboard.html').write_text(page,encoding='utf-8')
    result = {'reconstruction':reconstruction,'langenhagen_current':current,'historical_partial':historical,
        'model_search_protocol':protocol,'wmape':table.to_dict(),
        'production_model_promoted':False,'street_reference_connected_to_forecast':False}
    write_json(output/'summary.json',result)
    rows=['| Model | Spatial groups | Random splits |','|---|---:|---:|']
    for name,row in table.iterrows():
        rows.append('| '+names.get(name,name)+' | '+number(row['Räumliche Gruppen']*100,2)+' % | '+number(row['Zufällige Aufteilungen']*100,2)+' % |')
    report = '# Langenhagen, OSM features and street reference\n\n'
    report += 'The street reconstruction is implemented and has been run. The current regional OSM matching and an additional model comparison are complete. A complete historical OSM inventory could not be retrieved despite several data endpoints. The production model was not replaced on the basis of the additional experiment.\n\n'
    report += '## Volume anchor 2021\n\n'
    report += f'{reconstruction["observations"]} street observations in {reconstruction["postal_areas"]} PLZ yield {number(reconstruction["observed_DHL"],0)} volume units. Of these, {number(reconstruction["assigned_to_sites"],0)} ({number(share*100,2)} %) are assigned to sites with model weights. {number(reconstruction["unallocated_at_streets"],0)} remain at {reconstruction["streets_with_positive_unallocated_demand"]} streets with a positive remainder. In 30855, {number(local.assigned_to_sites,0)} of {number(local.observed_DHL,0)} are distributed; {number(local.unallocated_at_streets,0)} remain open.\n\n'
    report += 'Site volume plus open volume preserves every observation. This is an imposed data constraint, not independently measured predictive accuracy. The individual site weights, recipient segments and other carriers remain estimated. An unassigned site is not shown to be actually free of demand. The exclusion rule remains strictly >1000 on complete raw observations; in 30855 it removes nothing. The raw files remain unchanged. The daily-mean definition and the additivity of repeated street keys are still unconfirmed.\n\n'
    report += '## What the logistics data establish\n\n'
    report += f'In 30855, OSM currently contains {current["explicit_objects"]} objects with explicit warehouse/logistics tags and {current["warehouse_building_objects"]} warehouse building objects. These are not unique firms. The regional site file contains {current["firms"]} firms there with {number(current["employees"],0)} employees, including {current["transport_storage_firms"]} firms in branch H. Streets within 100 meters of the mapped logistics objects contain {number(near*100)} % of the DHL volume; based on street midpoints it is {number(mid*100)} %. There is no confirmed delivery assignment.\n\n'
    report += 'The [DHL Freight center has been documented since 2019](https://group.dhl.com/en/media-relations/press-releases/2019/dhl-freight-opens-new-freight-hub-in-hanover-langenhagen.html); the [Hermes hub already appears in the company history before 2021](https://hermesworld.com/int/about-us/history/current-decade/). The matching named OSM areas are additionally checked in the local context. This manual classification does not enter the regional fit as a special feature. Transshipment at the hub is not additional recipient demand and must not be double-counted. The sources do not establish any specific DHL delivery volume for these firms.\n\n'
    report += 'The regional OSM features date from 14.09.2026, the supplementary local name query from a server state of 31.05.2026. The complete historical 2021 query failed via Overpass with timeouts at several public endpoints; the tested ohsome endpoint responded HTTP 403. A smaller early query found seven `building=warehouse` ways within 30855. This is not a complete 2021 logistics inventory. The difference from current tags may also reflect mapping and reclassification. It was not interpreted as new construction.\n\n'
    report += '## Additional comparison\n\nCurrent OSM footprints and logistics object counts serve here as retrospective auxiliary features for DHL 2021. This explicitly retrospective experiment is not a historically valid test of a 2021 model. There is no manually set PLZ constant.\n\n'+'\n'.join(rows)+'\n\n'
    report += 'The candidates are regularized corrections of the base model, with/without spatial smoothing, plus an additive alternative. The previous three random 5-fold splits and five spatial groups were retained. Model selection takes place in inner folds. The selection variant slightly improves the spatial metric, but the fixed logistics/space variant does not beat the previous persons/space candidate. Because of later features and repeatedly examined target values, even the more favorable selection metric does not justify model approval. The new predictors are tested for DHL only and do not identify a new split of other LSPs.\n\n'
    report += '## Further integration\n\nThe street reference can be run as a separate step and is not yet connected to the daily/future simulation. For this connection, site volumes and open street volumes must be projected forward jointly. The existing calendar and fluctuation models do not replace a verified historical development of the building stock. In addition, a historical building/address inventory and better functional features are needed before logistics is approved as a demand predictor.\n\n'
    report += '## Reproduction and artifacts\n\nSee the commands in the project README (`street-reference`, `logistics-audit`, `model_search --logistics-snapshot current`). Each step produces a dashboard, data tables and provenance hashes. The individual queries are stored with content hashes in the OSM cache; download errors must not be interpreted as a zero inventory. A default call without `--logistics-snapshot current` still requires historical features.\n\n'
    report += '- [OSM detail check]('+link(osm/'dashboard.html')+')\n- [Model comparison]('+link(search/'dashboard.html')+')\n- [Street reference]('+link(street/'dashboard.html')+')\n\n'
    report += 'Geodata: [OpenStreetMap contributors, ODbL](https://www.openstreetmap.org/copyright). Query methods: [Overpass API and public instances](https://wiki.openstreetmap.org/wiki/Overpass_API), [historical map states](https://wiki.openstreetmap.org/wiki/Attic_Data).\n'
    (output/'report.md').write_text(report,encoding='utf-8')
    files=[osm/'summary.json',search/'metrics.csv',search/'protocol.json',search/'predictions.csv',
        street/'result.json',street/'postal_checks.csv',osm/'facilities_current.parquet',
        source/'postal_support.parquet',source/'dhl_observations.parquet']
    if args.historical_probe: files.append(Path(args.historical_probe))
    write_json(output/'provenance.json',{'inputs':{str(f.resolve()):digest(f) for f in files},'code':_code_hashes()})
    print(output/'dashboard.html')


if __name__=='__main__': main()
