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

from .data import write_json
from .logistics_osm import audit_map, parse, street_distances
from .pipeline import digest


def number(value, digits=1):
    return f'{value:,.{digits}f}'.replace(',', '_').replace('.', ',').replace('_', '.')


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
    names = {'baseline':'Bisheriges Basismodell','person_spatial_100':'Personen + Raum',
        'logistics_residual_10':'Logistik, schwächere Regularisierung',
        'logistics_residual_100':'Logistik, stärkere Regularisierung',
        'logistics_spatial_100':'Logistik + Personen + Raum',
        'logistics_additive_10':'Additive Logistikkorrektur','nested_selection':'Auswahl innerhalb der Trainingsdaten'}
    def link(path): return escape(os.path.relpath(path,output).replace('\\','/'),quote=True)
    nav = ' · '.join(f'<a href="{link(path)}">{title}</a>' for title,path in [
        ('OSM-Detailprüfung',osm/'dashboard.html'),('Modellvergleich',search/'dashboard.html'),
        ('Straßenrekonstruktion',street/'dashboard.html'),('Methodenbericht',output/'report.md')])
    page = '<!doctype html><html lang="de"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>HAGRID – Langenhagen und Straßenanker</title>'
    page += '<style>body{font:16px/1.5 system-ui;max-width:1120px;margin:35px auto;padding:0 22px;color:#213449;background:#fafbfc}h1,h2{color:#123b60}a{color:#125b98}.cards{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:15px}.card{background:#fff;border:1px solid #d4e0e9;border-radius:10px;padding:18px}.big{font-size:30px;font-weight:700;display:block}.note{padding:16px;border-left:4px solid #bd7820;background:#fff4df}table{border-collapse:collapse;width:100%;font-size:14px;background:#fff}td,th{padding:9px;text-align:right;border-bottom:1px solid #d8e0e9}th:first-child,td:first-child{text-align:left}nav{margin:20px 0}details{margin:20px 0}svg{border:1px solid #d4e0e9;border-radius:8px}code{word-break:break-word}</style>'
    page += '<h1>Langenhagen prüfen. DHL 2021 an Straßen verankern.</h1><nav>'+nav+'</nav>'
    page += '<p>Auswertung vom '+datetime.now(timezone.utc).strftime('%d.%m.%Y')+': Logistikstandorte räumlich abgleichen, zusätzliche Merkmale testen und die offene Straßenrekonstruktion ausführen.</p><div class="cards">'
    for value,label in [(number(reconstruction['observed_DHL'],0),'DHL-Menge im definierten KEP-Umfang'),
        (number(share*100,2)+' %','mit Modellgewichten Standorten zugeordnet'),
        (number(reconstruction['unallocated_at_streets'],0),'Mengeneinheiten verbleiben an Straßen')]:
        page += '<div class="card"><span class="big">'+value+'</span>'+label+'</div>'
    page += '</div><h2>Was die Logistikprüfung zeigt</h2>'
    page += f'<p>In PLZ 30855 liegen {current["explicit_objects"]} OSM-Objekte mit expliziten Lager-/Logistik-Tags, darunter {current["warehouse_building_objects"]} Lagergebäude-Objekte. Das sind Kartenobjekte, keine eindeutige Zahl von Unternehmen. Die vorhandene Firmendatei enthält {current["firms"]} Betriebe und {number(current["employees"],0)} Beschäftigte; {current["transport_storage_firms"]} Betriebe gehören zur Branche Verkehr/Lagerei.</p>'
    page += '<p>Das <a href="https://group.dhl.com/en/media-relations/press-releases/2019/dhl-freight-opens-new-freight-hub-in-hanover-langenhagen.html">DHL-Freight-Zentrum wurde 2019 eröffnet</a>. Auch der <a href="https://hermesworld.com/int/about-us/history/current-decade/">Hermes-Hub ist vor 2021 dokumentiert</a>. Umschlagmengen solcher Zentren dürfen nicht zusätzlich als Paketzustellungen an lokale Empfänger gezählt werden. Die Quellen belegen keine konkrete DHL-Menge in unseren Straßenbeobachtungen.</p>'
    page += f'<p>Straßen, die bis auf 100 Meter an erfasste Lager-/Logistikobjekte heranreichen, tragen <b>{number(near*100)} %</b> der DHL-Menge in 30855. Bei 100 Metern Abstand des Straßenmittelpunkts sind es <b>{number(mid*100)} %</b>. Die zusätzlich anhand von Unternehmensquellen eingeordneten Flächen von DHL Freight und Hermes ändern diese Werte hier nicht. Nähe ist keine Lieferzuordnung; die unterschiedlichen Werte zeigen die Empfindlichkeit des Maßes. Ein pauschaler Logistikaufschlag ist damit nicht begründet.</p>'
    boundary = gpd.read_parquet(source/'postal_support.parquet')
    boundary = boundary.loc[boundary.plz.eq('30855')].geometry.union_all()
    streets = gpd.read_parquet(source/'dhl_observations.parquet').to_crs(25832)
    streets = streets.loc[streets.plz.eq('30855')]
    facilities = gpd.read_parquet(osm/'facilities_current.parquet')
    page += '<p>Blau: DHL-Straßen. Orange: explizite Lager-/Logistik-Tags. Grau: weitere Kandidaten. Beim Überfahren erscheinen Namen und Mengen. Die Geometrien stammen aus dem aktuellen Serverbestand.</p>'
    page += audit_map(boundary,streets,facilities)
    dates=', '.join(sorted(set(t[:10] for t in current['server_data_timestamps'])))
    page += '<p><small>Geodaten: <a href="https://www.openstreetmap.org/copyright">© OpenStreetMap contributors, ODbL</a>. Verwendete Serverstände einschließlich ergänzender Namensabfrage: '+dates+'. OSM-Grundfläche ist keine Nutzfläche und kein Paketvolumen.</small></p>'
    historical = None
    if args.historical_probe:
        historical_osm = parse(read(Path(args.historical_probe)))
        historical_osm = historical_osm.loc[historical_osm.geometry.intersects(boundary)]
        matches = street_distances(streets,historical_osm)
        historical = {'warehouse_ways_in_30855':len(historical_osm),
            'street_volume_within_100m':float(matches.loc[matches.distance_m.le(100),'value'].sum()),
            'scope':'Partial local query: ways with building=warehouse, snapshot 2021-12-31; not complete regional logistics coverage'}
    page += '<div class="note"><b>Historischer Datenzugang bleibt offen.</b> Die vollständigen 2021-Abfragen scheiterten bei mehreren öffentlichen Overpass-Zugängen an Zeitüberschreitungen; der geprüfte ohsome-Zugang antwortete mit HTTP 403. Erfolgreiche Teilabfragen und Fehler sind gespeichert.'
    if historical:
        page += f' Eine frühere eng begrenzte Lagergebäude-Abfrage lieferte {historical["warehouse_ways_in_30855"]} passende Objekte innerhalb 30855. Das ist kein vollständiger historischer Logistikbestand und kein Nachweis, dass die übrigen Hallen erst später gebaut wurden.'
    page += '</div><h2>Verbessern Logistikmerkmale die Schätzung?</h2>'
    page += '<p>Getestet wurden regionale Lagergrundflächen, Industriegebäude-Grundflächen und die Zahl explizit markierter Logistikobjekte. Die Zusatzmodelle korrigieren das bisherige Nachfragegrundmodell gemeinsam mit Personenmerkmalen; Varianten nutzen Regularisierung, eine räumliche Korrektur oder eine additive Korrektur. Es gibt keine frei gesetzte Langenhagen-Konstante.</p>'
    page += '<p class="note"><b>Nachträglicher Test mit aktuellen Hilfsdaten:</b> Die folgenden Fehler beziehen sich auf DHL 2021, die zusätzlichen OSM-Merkmale aber auf 2026. Dieser Vergleich zeigt räumliche Zusammenhänge unter dieser Annahme. Er ist keine historisch valide Vorhersageprüfung für 2021 und keine Freigabe für das Hauptmodell.</p>'
    page += table.rename(index=names).to_html(float_format=lambda x:number(x*100,2)+' %')
    page += '<p>wMAPE ist die Summe der absoluten Fehler geteilt durch die beobachtete Gesamtmenge. Verglichen werden dieselben drei zufälligen 5-Fold-Aufteilungen und fünf räumlichen Gruppen wie zuvor. Die Auswahlvariante wählt das Modell ausschließlich in inneren Trainingsaufteilungen. Der gesamte Versuch bleibt wegen wiederholt untersuchter Daten und späterer OSM-Merkmale explorativ.</p>'
    page += '<details><summary>Was wird für Langenhagen ohne seine DHL-Testwerte vorhergesagt?</summary>' + focus.replace({'model':names}).rename(columns={'model':'Modell','observed':'Beobachtet','predicted':'Vorhergesagt'}).to_html(index=False,float_format=number)+'</details>'
    page += '<h2>Die neue Referenz für 2021</h2>'
    page += f'<p>Die neue Rekonstruktion erhält alle <b>{reconstruction["observations"]}</b> berücksichtigten Straßenbeobachtungen in {reconstruction["postal_areas"]} PLZ. Standortmenge plus offene Straßenmenge stimmt jeweils mit der Beobachtung überein. In 30855 sind das {number(local.observed_DHL,0)}: {number(local.assigned_to_sites,0)} werden mit Standortgewichten verteilt und {number(local.unallocated_at_streets,0)} bleiben an Straßen.</p>'
    page += '<p>Innerhalb einer Straße gewichtet das bisherige Personen-/Betriebsmodell die eindeutigen Standortkandidaten. Mehrdeutige Zuordnungen werden verworfen, bevor Beobachtungen ausgeschlossen werden. Ohne passenden Standort bleibt die Menge in einer eigenen Datei mit der ursprünglichen Straßengeometrie. Ein Standort ohne zugewiesene Menge ist dadurch nicht empirisch als nachfragefrei belegt.</p>'
    page += '<p><b>Die exakten Summen sind eine Datenbindung, kein Fehlermaß für Vorhersagen.</b> Andere Anbieter behalten ihre geschätzten Mengen. B2B/B2C-Anteile, innerörtliche Standortgewichte und Mengen anderer LSP werden dadurch nicht unabhängig bestätigt. Die neue Straßenreferenz ist ein eigener ausgeführter Schritt; sie ist noch nicht als Ausgangspunkt in die Tages-/Zukunftssimulation eingebunden.</p>'
    page += '<h2>Methodische Konsequenz</h2><p>Für den bekannten Zustand 2021 die Straßenbeobachtungen als Mengenanker verwenden. Logistikstandorte als eigene Standortrolle prüfen: Frachtumschlag, Paketsortierung, Fulfilment und Nachfrage eines Empfängerbetriebs haben unterschiedliche Bedeutungen. Für fehlende Beobachtungen bleibt der bisherige Personen-/Raum-Kandidat der Vergleichsmaßstab. Einen neuen allgemeinen Anbieter- oder Logistikaufschlag stützen diese Tests nicht.</p>'
    page += '<p>Für eine Fortschreibung ab dieser Straßenreferenz müssen Mengenänderungen auf Standort- und offenen Straßenanteil gemeinsam angewandt werden. Danach lassen sich Kalenderprofile und räumliche Schwankungen nutzen. Dieser Anschluss und ein vollständiger historischer Gebäudebestand bleiben weitere Arbeit; in diesem Lauf wurden keine zukünftigen Mengen neu kalibriert.</p><nav>'+nav+'</nav></html>'
    (output/'dashboard.html').write_text(page,encoding='utf-8')
    result = {'reconstruction':reconstruction,'langenhagen_current':current,'historical_partial':historical,
        'model_search_protocol':protocol,'wmape':table.to_dict(),
        'production_model_promoted':False,'street_reference_connected_to_forecast':False}
    write_json(output/'summary.json',result)
    rows=['| Modell | Räumliche Gruppen | Zufällige Aufteilungen |','|---|---:|---:|']
    for name,row in table.iterrows():
        rows.append('| '+names.get(name,name)+' | '+number(row['Räumliche Gruppen']*100,2)+' % | '+number(row['Zufällige Aufteilungen']*100,2)+' % |')
    report = '# Langenhagen, OSM-Merkmale und Straßenreferenz\n\n'
    report += 'Die Straßenrekonstruktion ist implementiert und ausgeführt. Der aktuelle regionale OSM-Abgleich und ein zusätzlicher Modellvergleich sind abgeschlossen. Ein vollständiger historischer OSM-Bestand konnte trotz mehrerer Datenzugänge nicht abgerufen werden. Das Produktionsmodell wurde nicht aufgrund des Zusatzversuchs ausgetauscht.\n\n'
    report += '## Mengenanker 2021\n\n'
    report += f'{reconstruction["observations"]} Straßenbeobachtungen in {reconstruction["postal_areas"]} PLZ ergeben {number(reconstruction["observed_DHL"],0)} Mengeneinheiten. Davon sind {number(reconstruction["assigned_to_sites"],0)} ({number(share*100,2)} %) mit Modellgewichten an Standorten zugeordnet. {number(reconstruction["unallocated_at_streets"],0)} bleiben an {reconstruction["streets_with_positive_unallocated_demand"]} Straßen mit positiver Restmenge. In 30855 werden {number(local.assigned_to_sites,0)} von {number(local.observed_DHL,0)} verteilt; {number(local.unallocated_at_streets,0)} bleiben offen.\n\n'
    report += 'Standortmenge plus offene Menge erhält jede Beobachtung. Das ist eine auferlegte Datenbindung, keine unabhängig gemessene Vorhersagegüte. Die individuellen Standortgewichte, Empfängersegmente und anderen Anbieter bleiben geschätzt. Ein unzugeordneter Standort ist nicht als tatsächlich nachfragefrei nachgewiesen. Die Ausschlussregel bleibt strikt >1000 auf vollständigen Rohbeobachtungen; in 30855 entfernt sie nichts. Die Rohdateien bleiben unverändert. Die Tagesmittel-Definition und die Additivität wiederholter Straßenschlüssel sind weiterhin unbestätigt.\n\n'
    report += '## Was die Logistikdaten belegen\n\n'
    report += f'OSM enthält in 30855 aktuell {current["explicit_objects"]} Objekte mit expliziten Lager-/Logistik-Tags und {current["warehouse_building_objects"]} Lagergebäude-Objekte. Das sind keine eindeutigen Betriebe. Die regionale Standortdatei enthält dort {current["firms"]} Betriebe mit {number(current["employees"],0)} Beschäftigten, darunter {current["transport_storage_firms"]} Betriebe der Branche H. Straßen in bis zu 100 Metern Abstand zu den erfassten Logistikobjekten enthalten {number(near*100)} % der DHL-Menge; bei Bezug auf Straßenmittelpunkte sind es {number(mid*100)} %. Es besteht keine bestätigte Lieferzuordnung.\n\n'
    report += 'Das [DHL-Freight-Zentrum ist seit 2019 dokumentiert](https://group.dhl.com/en/media-relations/press-releases/2019/dhl-freight-opens-new-freight-hub-in-hanover-langenhagen.html); der [Hermes-Hub erscheint bereits in der Unternehmenschronik vor 2021](https://hermesworld.com/int/about-us/history/current-decade/). Die passenden benannten OSM-Flächen werden im lokalen Kontext zusätzlich geprüft. Diese manuelle Einordnung fließt nicht als Sondermerkmal in den regionalen Fit ein. Umschlag am Hub ist keine zusätzliche Empfängernachfrage und darf nicht doppelt gezählt werden. Die Quellen belegen keine konkrete DHL-Zustellmenge für diese Betriebe.\n\n'
    report += 'Die regionalen OSM-Merkmale stammen vom 14.09.2026, die ergänzende lokale Namensabfrage von einem Serverstand vom 31.05.2026. Die vollständige historische 2021-Abfrage scheiterte über Overpass bei mehreren öffentlichen Zugängen an Zeitüberschreitungen; der geprüfte ohsome-Zugang antwortete HTTP 403. Eine kleinere frühe Abfrage fand sieben `building=warehouse`-Wege innerhalb 30855. Das ist kein vollständiger Logistikbestand von 2021. Der Unterschied zu aktuellen Tags kann auch Kartierung und Umklassifizierung widerspiegeln. Er wurde nicht als Neubauentwicklung interpretiert.\n\n'
    report += '## Zusatzvergleich\n\nAktuelle OSM-Grundflächen und Logistikobjektzahlen dienen hier als nachträgliche Hilfsmerkmale für DHL 2021. Dieser ausdrücklich retrospektive Versuch ist keine historisch valide Prüfung eines 2021-Modells. Es gibt keine manuell gesetzte PLZ-Konstante.\n\n'+'\n'.join(rows)+'\n\n'
    report += 'Die Kandidaten sind regularisierte Korrekturen des Grundmodells, mit/ohne räumliche Glättung sowie eine additive Alternative. Die bisherigen drei zufälligen 5-Fold-Aufteilungen und fünf räumlichen Gruppen wurden beibehalten. Modellwahl erfolgt in inneren Folds. Die Auswahlvariante verbessert die räumliche Kennzahl leicht, die festgelegte Logistik-/Raumvariante schlägt den bisherigen Personen-/Raum-Kandidaten aber nicht. Wegen späterer Merkmale und wiederholt untersuchter Zielwerte rechtfertigt auch die günstigere Auswahlkennzahl keine Modellfreigabe. Die neuen Prädiktoren sind nur für DHL geprüft und identifizieren keine neue Aufteilung anderer LSP.\n\n'
    report += '## Weiterer Anschluss\n\nDie Straßenreferenz ist als eigener Schritt ausführbar und noch nicht in die Tages-/Zukunftssimulation eingebunden. Für diese Verbindung müssen Standortmengen und offene Straßenmengen gemeinsam fortgeschrieben werden. Die vorhandenen Kalender- und Schwankungsmodelle ersetzen keine verifizierte historische Bestandsentwicklung. Zusätzlich braucht es historischen Gebäude-/Adressbestand und bessere Funktionsmerkmale, bevor Logistik als Nachfrageprädiktor freigegeben wird.\n\n'
    report += '## Reproduktion und Artefakte\n\nSiehe die Befehle im Projekt-README (`street-reference`, `logistics-audit`, `model_search --logistics-snapshot current`). Jeder Schritt erzeugt ein Dashboard, Datentabellen und Herkunftshashes. Die Einzelabfragen stehen mit Inhaltshashes im OSM-Cache; Downloadfehler dürfen nicht als Nullbestand interpretiert werden. Ein Standardaufruf ohne `--logistics-snapshot current` verlangt weiterhin historische Merkmale.\n\n'
    report += '- [OSM-Detailprüfung]('+link(osm/'dashboard.html')+')\n- [Modellvergleich]('+link(search/'dashboard.html')+')\n- [Straßenreferenz]('+link(street/'dashboard.html')+')\n\n'
    report += 'Geodaten: [OpenStreetMap contributors, ODbL](https://www.openstreetmap.org/copyright). Methoden der Abfrage: [Overpass API und öffentliche Instanzen](https://wiki.openstreetmap.org/wiki/Overpass_API), [historische Kartenstände](https://wiki.openstreetmap.org/wiki/Attic_Data).\n'
    (output/'report.md').write_text(report,encoding='utf-8')
    files=[osm/'summary.json',search/'metrics.csv',search/'protocol.json',search/'predictions.csv',
        street/'result.json',street/'postal_checks.csv',osm/'facilities_current.parquet',
        source/'postal_support.parquet',source/'dhl_observations.parquet']
    if args.historical_probe: files.append(Path(args.historical_probe))
    write_json(output/'provenance.json',{'inputs':{str(f.resolve()):digest(f) for f in files},'code_sha256':digest(Path(__file__))})
    print(output/'dashboard.html')


if __name__=='__main__': main()
