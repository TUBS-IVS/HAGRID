# Landnutzungsdynamik 2025–2035: Personen und Firmen je Gebiet

Stand: 2026-09-29. Ansatz A aus der Voruntersuchung (Bezirksfaktoren + Kohorteneffekt + Branchenraten, dazu
Neubau- und Gewerbegebiete als neue Standorte, zwei Varianten), vom Nutzer gewählt.

## 1. Ziel

Die Mehrjahresprojektion (`docs/demand/specs/2026-09-28-decade-projection-design.md`) hält die räumliche Struktur
auf dem Stand der Referenz fest: jeder Standort behält seinen Anteil (`historical_share`), nur das Niveau wächst.
Künftig soll sich über 2025–2035 ändern,

1. **wo Personen wohnen** — nach der amtlichen Bevölkerungsprognose je Prognosebezirk, mit den großen
   Neubaugebieten als neuen Wohnstandorten,
2. **wie viel sie bestellen** — über die Altersstruktur (Alterung) mit einstellbarem Kohorteneffekt,
3. **wo Firmen sitzen und wachsen** — nach Branchenraten, mit einem Teil des Wachstums als neue Betriebe in
   Gewerbe- und Industrieflächen,

alles über einen Konfigurationsblock `land_use` einstellbar, mit belegten Defaults und zwei Varianten
(Innenentwicklung, Suburbanisierung). Die Wirkung läuft durch alle bestehenden Auswertungen (PLZ-Karte, Hotspots,
Abholnetz-Wachstum, MATSim-Export) und bekommt im Dekaden-Dashboard einen Abschnitt „Strukturwandel“.

Erfolg: 2025 bleibt bitidentisch zum abgenommenen Lauf; die Personenentwicklung je Prognosebezirk trifft die
Prognose 2024→2034 (±0,1 Prozentpunkte, fortgeschrieben bis 2035); Segment- und Jahressummen bleiben exakt beim
Volumenszenario; jede Annahme steht mit Quelle in `data/land_use.json` und im Methodenteil des Dashboards.

## 2. Ausgangslage

- `reference_sites.parquet`: 220.841 Standorte (191.748 privat = OSM-Gebäude mit `population`, 29.093 gewerblich
  mit `employees`, `branch` = WZ-2008-Abschnitt), `historical_share` je Segment aus dem Straßenanker.
- `projection.py::project_annual` verteilt die Segmentmenge eines Jahres mit `share = historical_share / Summe`
  (`memory.fixed = 1`) auf die Standorte; das Volumenszenario bestimmt nur das Niveau.
- Die Tagesstufe hängt Abholpunkte bereits als zusätzliche Stopps an (`point_stops`, stabile `stop_index` nach den
  Referenzstopps) und kennt Pseudostandorte (`ooh:`); `export_day` baut das Stop-Register aus
  `reference_stops.parquet` plus `out_of_home_points.parquet` wieder auf.
- Lokale Eingaben: synthetische Bevölkerung (`raw/persons_total.csv`, 1.150.862 Personen mit Alter und Gebäude),
  Firmen (`raw/companies_Total_reduced.shp`: Beschäftigte, WZ-Abschnitt, Größenklasse), OSM-Landnutzung
  (`raw/osm_landuse_region_hannover.csv`: residential 1.538, commercial 756, industrial 465, retail 226 Polygone;
  u. a. „Kronsberg-Süd“), Geofabrik-Auszug `osm/niedersachsen-210101.osm.pbf` (Verwaltungsgrenzen).

## 3. Datengrundlagen

| Baustein | Quelle | Verwendung |
|---|---|---|
| Bevölkerung je Gebiet | Landeshauptstadt und Region Hannover: *Bevölkerungsprognose 2025 bis 2035* (Feb. 2026), Tabellen 7 und 8: Bevölkerung 31.12.2024 und 31.12.2034 in 50 Prognosebezirken (30 Bezirke der LHH, 20 Umlandkommunen) | Faktor je Bezirk und Jahr |
| Neubaugebiete | dieselbe Prognose, Kapitel Ergebnisse: Kronsberg (Bemerode +3.675), Wasserstadt Limmer (+1.384), Langenhagen-Mitte/Godshorn/Kaltenweide, Berenbostel/Garbsen-Mitte, Seelze-Süd | Standorte und Einwohnerannahmen (Annahme, da die Prognose keine Einwohner je Gebiet nennt) |
| Alter | synthetische Bevölkerung (Altershistogramm je Bezirk); Prognose Tabelle 5 (65+: +13 %, 18–64: LHH −1,1 %, Umland −6 %) zur Plausibilisierung | Alterung je Bezirk |
| Online-Neigung | Destatis, IKT-Erhebung 2025, Online-Einkauf in den letzten 12 Monaten: 16–24: 84 %, 25–44: 91 %, 45–64: 80 %, 65–74: 61 %; 75+ nicht erhoben (Annahme 40 %) | Neigungskurve, Kohorteneffekt |
| Beschäftigung | Region Hannover, *Trends und Fakten* 2025: SV-Beschäftigte 2014–2024 +1,5 %/a, 2023–2024 +0,5 %; Gesundheitswirtschaft überdurchschnittlich, Automotive ohne Wachstum | Branchenraten (Annahme auf dieser Basis) |
| Gebiete | OSM `boundary=administrative`: `admin_level=8` (Städte und Gemeinden), `admin_level=10` (Stadtteile LHH) aus dem PBF 2021 | Zuordnung Standort → Prognosebezirk |

Die Tabellenwerte werden mit Seitenangabe in `data/land_use.json` übernommen (50 Zeilen: `id`, `name`, `kind`
`city|umland`, `pop_2024`, `pop_2034`), ebenso die Zuordnung der 51 Stadtteile zu den 30 städtischen
Prognosebezirken (aus dem Tabellenanhang der Prognose; Stadtteile, die dort nicht einzeln stehen, per Namensgleichheit).

## 4. Entscheidungen

| # | Entscheidung | Begründung | Kosten bei Irrtum |
|---|---|---|---|
| L1 | Bezugsjahr 2025: alle Faktoren sind 2025 = 1, Neubau- und Gewerbestandorte öffnen frühestens 2026. | 2025 bleibt der abgenommene Lauf; die Prognose startet Ende 2024. | Bestand an Neubau 2021–2025 fehlt im Bezugsjahr; über `base_year` verschiebbar. |
| L2 | Nur Umverteilung: Regions- und Segmentsummen bleiben beim Volumenszenario; die Landnutzung verschiebt Anteile innerhalb der Region. | Das Volumenszenario enthält die nationale Demografie schon; eine Doppelzählung wird vermieden. Die Region wächst laut Prognose nur um 0,5 %. | Regionsanteil um höchstens ±0,5 % ungenau. |
| L3 | Bezirksfaktor linear zwischen 2024 und 2034, 2035 mit der mittleren Jahresrate fortgeschrieben, dann auf 2025 = 1 normiert. | Die Prognose liefert nur Anfangs- und Endstand. | Zwischenjahre glatt statt stufig. |
| L4 | Neubaugebiete bekommen absolute Einwohnerannahmen mit Start und Hochlauf; der Rest der Bezirksveränderung wirkt auf den Bestand. | So stimmt die Bezirkssumme mit der Prognose, und der Zuwachs landet dort, wo gebaut wird. | Annahmen je Gebiet (konfigurierbar). |
| L5 | Neue Standorte liegen auf einem 50-m-Raster im Gebietspolygon, jeder mit eigenem Stopp. | Die OSM-Gebäude von 2021 kennen die Neubauten noch nicht. | Straßenzuordnung grob; MATSim nimmt den nächsten Link. |
| L6 | Neigung: Destatis-Kurve je Altersgruppe; `cohort_shift` ∈ [0, 1] mischt zwischen Alterseffekt (0: die Kurve bleibt) und Kohorteneffekt (1: jede Person behält ihre Neigung beim Altern). Standard 0,7. | Ein Parameter, belegt durch den Anstieg der 55- bis 74-Jährigen von 66 % (2021) auf 73 % (2024). | Wirkung nur relativ zwischen Bezirken. |
| L7 | Alterung je Bezirk ohne Mikrosimulation: das Histogramm von 2025 altert jährlich um ein Jahr, junge Jahrgänge werden mit der Altersverteilung von 2025 aufgefüllt, dann wird auf die Bezirksbevölkerung des Jahres skaliert. | Die amtliche Prognose rechnet Geburten, Sterbefälle und Wanderung schon; wir brauchen nur die Altersmischung. | Leicht überschätzte Alterung in Zuzugsgebieten. |
| L8 | Firmen: Wachstum je WZ-Abschnitt (Standard Q +1,5 %/a, J/M/N +1,0 %/a, H +1,0 %/a, G 0,0 %/a, C −0,5 %/a, sonst +0,5 %/a); `new_firm_share` 0,3 des Beschäftigtenzuwachses entsteht als neue Betriebe in OSM-Gewerbe- und Industrieflächen. | Belegt durch die Regionsentwicklung (+0,5 bis +1,5 %/a) und die Branchentrends. | Annahmen konfigurierbar. |
| L9 | Varianten als Jahresraten-Offsets: `innenentwicklung` +0,1 Prozentpunkte/a für städtische Bezirke, −0,1 für das Umland (nach 10 Jahren ±1 Prozentpunkt), `suburbanisierung` umgekehrt; danach auf die Regionssumme der Prognose normiert. | Dieselbe Mechanik, fast ohne Mehraufwand. | Offsets sind Konfiguration. |
| L10 | Kein Java, keine Haushaltsbildung, kein Einkommen, kein Wohnungsmodell. | Schlank halten. | — |

## 5. Architektur

### 5.1 Gebiete (`osm.py`, CLI)

- Neuer Befehl `python -m hagrid_demand baseline osm-boundaries --pbf <pbf> --plz <plz.csv> --out <parquet>`:
  liest `boundary=administrative` mit `admin_level` 8 und 10 (Layer `multipolygons`, wie `osm-transit`), schneidet
  auf die Region, schreibt `name`, `admin_level`, `geometry`. Ziel: `input/hannover/osm/osm_boundaries_region_hannover_2021.parquet`,
  Config-Schlüssel `osm_boundaries`.
- `land_use.districts(boundaries, crosswalk) -> GeoDataFrame[district_id, name, kind, geometry]`: 20 Umlandkommunen
  aus `admin_level=8` (ohne die LHH), 30 Stadtbezirke als Vereinigung ihrer Stadtteile aus `admin_level=10`.
- `land_use.assign_districts(site_xy, districts) -> np.ndarray[district_id]`: räumliche Zuordnung über die
  Stopp-Koordinate des Standorts; Standorte außerhalb aller Polygone erhalten den nächsten Bezirk.

### 5.2 Faktoren (`land_use.py`)

Reine Funktionen, jede einzeln getestet:

- `district_population(table, years, base_year, variant, offsets) -> DataFrame[year, district_id, population_index]`
  nach L3/L9 (Index = Bevölkerung(y) / Bevölkerung(base_year)).
- `aged_histograms(persons_by_district: DataFrame[district_id, age, persons], years, base_year) -> DataFrame[year, district_id, age, persons]`
  nach L7 (Altersgrenze 100, auf den Bezirksindex skaliert).
- `propensity(ages, year, base_year, curve, cohort_shift) -> np.ndarray`: `p_y(a) = p(a − s(y))`,
  `s(y) = cohort_shift · (y − base_year)`, Kurve stückweise konstant nach Altersgruppen, unter 16 = 0.
- `propensity_index(histograms, curve, cohort_shift, base_year) -> DataFrame[year, district_id, propensity_index]`:
  (Σ N·p / Σ N)(y) geteilt durch den Wert im Bezugsjahr.
- `firm_factors(sites_business, years, base_year, rates) -> DataFrame[year, site_id, factor]` (Zinseszins je
  Branche) und `new_firm_employees(...)` für den extensiven Anteil.
- `site_factors(sites, district_of_site, population_index, propensity_index, firm_factors, development) -> DataFrame[year, site_id, factor]`:
  privat = Bestandsfaktor × Neigungsindex des Bezirks, mit Bestandsfaktor
  `E_d(y) = max(0, P_d(base) · population_index_d(y) − R_d(y)) / P_d(base)`, `P_d(base)` = Summe `population` der
  Bestandsstandorte des Bezirks, `R_d(y)` = Einwohner der in `y` bezogenen Neubaugebiete des Bezirks (5.3; ein
  Wert unter 0 wird auf 0 gesetzt und im Status gemeldet); gewerblich = Bestandsanteil des Branchenwachstums
  `1 + (1 − new_firm_share) · ((1 + r_branch)^(y − base) − 1)`, bei schrumpfender Branche (`r_branch < 0`) der volle
  Rückgang `(1 + r_branch)^(y − base)` (Nachtrag 2026-09-30, siehe 9). Faktor im Bezugsjahr exakt 1.

### 5.3 Neue Standorte (`land_use.py`, Anbindung in `workflow.py`)

- Neubaugebiete (Defaults in `data/land_use.json`, überschreibbar): `name`, `district_id`, `residents`,
  `start_year`, `ramp_years`, Geometrie per `osm_landuse_name` (Polygon aus der OSM-Landnutzung) oder
  `center` + `radius_m`. Standardliste mit Einwohnerannahmen: Kronsberg-Süd 3.000, Wasserstadt Limmer 1.200,
  Seelze-Süd 700, Langenhagen (Mitte, Godshorn, Kaltenweide) zusammen 1.200, Garbsen (Berenbostel, Mitte) 800;
  Start 2026, Hochlauf 4 Jahre (linear).
- `development_sites(areas, years, grid_m, reference_share_per_person) -> GeoDataFrame`: Rasterpunkte je Gebiet
  (50 m, mindestens 5 Punkte), Einwohner gleich verteilt; `site_id = "lu:res:<gebiet>:<i>"`, Segment `private`,
  `year_opened`, `population` je Jahr (Hochlauf), `historical_share` = Einwohner × mittlerer Anteil je Person der
  Bestandsstandorte des Bezirks (Summe `historical_share` / Summe `population`); Faktor je Jahr = Hochlaufanteil ×
  Neigungsindex des Bezirks.
- Neue Betriebe: je Jahr `new_firm_share` des Beschäftigtenzuwachses je Branche als Betriebe mit der mittleren
  Betriebsgröße der Branche, Ort per Ziehung ohne Zurücklegen aus OSM-Gewerbe-/Industriepolygonen
  (Gewicht ∝ Fläche), zufälliger Punkt im Polygon; `site_id = "lu:biz:<branch>:<jahr>:<i>"`, Segment `business`,
  `historical_share` = Beschäftigte × mittlerer Anteil je Beschäftigtem der Branche. Zufall über
  `named_rng(seed, year=..., channel="land-use-firms")`.
- Stopps: jeder neue Standort bekommt einen Stopp (`stop_type = "home"`), `stop_index` fortlaufend nach den
  Referenzstopps und **vor** den Abholpunkten; Register `land_use_stops.parquet` (stop_id, stop_index, plz,
  geometry, year_opened) neben `out_of_home_points.parquet`; `export_day` hängt beide an.
- Vor dem Eröffnungsjahr haben neue Standorte `annual_expected = 0`.

### 5.4 Projektion (`projection.py`)

- `project_annual(..., site_factors: DataFrame | None = None, extra_sites: DataFrame | None = None)`: ohne beide
  Argumente unverändert (bitidentisch). Mit Faktoren: `share_y(site) = historical_share · F(site, y) / Σ_Segment(historical_share · F)`;
  zusätzliche Standorte gehen mit ihrem `historical_share` und Faktor (0 vor Eröffnung) in dieselbe Normierung ein.
  Segment- und Gesamtbilanzen werden wie bisher mit `assert_balance` geprüft.
- `postal_projection` enthält danach die verschobenen PLZ-Mengen.

### 5.5 Konfiguration und Ausgaben

```json
"land_use": {"enabled": true, "base_year": 2025, "variant": "prognose", "cohort_shift": 0.7,
             "new_firm_share": 0.3, "grid_m": 50,
             "developments": "standard", "firm_rates": "standard"}
```

- Defaults und Quellen in `data/land_use.json`; Varianten `prognose | innenentwicklung | suburbanisierung`,
  `enabled: false` = heutiges Verhalten.
- Ausgaben: `land_use_districts.parquet` (year, district_id, name, kind, population_index, propensity_index,
  persons_model, employees_model), `land_use_factors.parquet` (year, site_id, factor), `land_use_sites.parquet`
  (neue Standorte mit Geometrie, Art, Eröffnungsjahr, Einwohner/Beschäftigte je Jahr), Statusblock `land_use` in
  `daily_status.json`.
- Stage-Hash: `land_use`-Block, `data/land_use.json`, `osm_boundaries` und `land_use.py` gehen in den Daily-Hash ein.
- Neue Szenario-Configs sind nicht nötig: die drei `decade-*.json` bekommen den Block (Variante `prognose`);
  Varianten als eigene Configs `decade-trend-innen.json`, `decade-trend-suburban.json` (nur Trendvolumen).

### 5.6 Dekaden-Dashboard

Neuer Abschnitt `#structure` („Strukturwandel“) zwischen `#hotspots` und `#network`:

- Karte der 50 Prognosebezirke mit Jahresregler: Personenindex, Neigungsindex, Beschäftigtenindex (umschaltbar);
  neue Wohn- und Gewerbestandorte als Punkte ab ihrem Eröffnungsjahr.
- Balken: Bezirke nach Personenveränderung 2025→2035 (Modell gegen Prognose, zur Kontrolle).
- Kurve der Online-Neigung nach Alter 2025 und 2035 (mit Kohorteneffekt) und Altersaufbau Region 2025/2035.
- Tabelle der Neubaugebiete: Einwohner je Jahr, Sendungen je Zustelltag, neue Abholpunkte in der Nähe.
- Methodentexte aus `D.meta.land_use` (Quellen, Parameter, Variante).

## 6. Datenfluss

Referenz 2021 → Bezirke (OSM) und Zuordnung der Standorte → Faktoren je Jahr (Bevölkerung, Alter/Neigung, Firmen)
und neue Standorte → `project_annual` mit Faktoren → Kalender, Versand, Abholnetz-Vorpass (nutzt die verschobene
Nachfrage) → Tagesschleife → Jahresspeicher, Register (`land_use_*`) → Jahres- und Dekaden-Dashboard.

## 7. Tests

- `district_population`: trifft 2024 und 2034 exakt, 2025 = 1, Fortschreibung 2035, Varianten verschieben
  Stadt/Umland und halten die Regionssumme.
- `aged_histograms` / `propensity` / `propensity_index`: Altersverschiebung um ein Jahr je Jahr; `cohort_shift` 0
  und 1 als Grenzfälle (bei 1 behält eine Kohorte ihre Neigung); Index 2025 = 1.
- `firm_factors`: Zinseszins je Branche, unbekannte Branche mit Standardrate.
- `development_sites` / neue Betriebe: Raster im Polygon, Hochlauf, Summen, Determinismus, Stop-Indizes vor den
  Abholpunkten.
- `project_annual` mit Faktoren: Bilanzen exakt; ohne Faktoren bitidentisch; Faktor 1 überall = unverändert.
- Workflow (Testregion, zwei Jahre): `land_use_*`-Dateien, 2025 identisch zum Lauf ohne `land_use`, neue Standorte
  ab Eröffnungsjahr im Jahresspeicher und im `export_day`.
- Dekaden-Dashboard: Abschnitt `#structure` vorhanden, Payload ohne NaN, fehlende `land_use`-Dateien = Abschnitt leer.

## 8. Nicht-Ziele

Mikrosimulation, Haushalte, Einkommen, Wohnungsbestand, Pendeln, Kalibrierung an Zensus 2022, Java-Änderungen.

## 9. Nachtrag 2026-09-30 (Befunde aus dem Abschluss-Review)

- Schrumpfende Branchen tragen den vollen Rückgang im Bestand. Neue Betriebe entstehen nur bei Wachstum, und kein
  Betrieb schließt. Mit dem Faktor aus 5.2 ginge sonst ein Teil des Rückgangs verloren, und die Branche schrumpfte
  langsamer als ihre Rate.
- Neue Betriebe werden je Jahr ohne Zurücklegen auf die Flächen gezogen, wie in 5.3 vorgesehen. Sind alle Flächen
  belegt, beginnt eine neue Runde.
- Beim Laden wird geprüft: `start_year` jedes Neubaugebiets liegt nach `base_year`, kein simuliertes Jahr liegt vor
  `base_year`, Gebietsnamen und ihre Kürzel sind eindeutig.
- Abholpunkte und Landnutzungsstopps behalten getrennte Indexbereiche. Berühren sie sich, bricht der Lauf ab.
- Die neuen Standorte stehen auch in `reference["geometry"]`, damit die korrelierte räumliche Verteilung sie findet.
- Der Cache-Schlüssel der Tagesstufe enthält alle Landnutzungseingaben, auch `sources/sites.parquet` und
  `buildings/site_buildings.parquet`. Ein leerer `land_use`-Block zählt als eingeschaltet.
- Das Jahres-Dashboard zählt die bis zu seinem Jahr eröffneten Landnutzungsstopps mit.
- Das Dekaden-Dashboard speichert gleiche Bezirksformen und Standortlisten nur einmal (`structure_pool`) und liest
  die Standortprojektion Lauf für Lauf.
