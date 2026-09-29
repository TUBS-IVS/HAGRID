# Mehrjahresprojektion 2025–2035: Wachstumsszenarien, wachsendes Abholnetz, Dekaden-Dashboard

Stand: 2026-09-28 (Nachtlauf). Der Nutzer hat den gesamten Pfad (Spec → Plan → Umsetzung → Läufe → Dashboard)
vorab freigegeben; Rückfragen waren nicht möglich. Entscheidungen, die sonst abgestimmt worden wären, stehen
unter „Entscheidungen (Rulings)“ und sind einzeln umkehrbar.

## 1. Ziel

Das tägliche Nachfragemodell (`hagrid/demand/model`) rechnet bisher ein Jahr (2025). Es soll

1. **jeden Tag der Jahre 2025–2035** simulieren, in **drei Volumenszenarien** mit unterschiedlichen Anstiegen,
   abgeleitet aus den Reihen der alten Notebooks (Volumen-Logistik/Linear/Exponential, Marktanteile, B2B-Anteil),
2. das **Abholnetz (Packstationen, geteilte Boxen, Amazon-Counter) mitwachsen** lassen: neue synthetische Standorte
   an Supermärkten, Tankstellen, Kiosken usw., dort wo Nachfrage hoch und Abdeckung dünn ist, Kapazitäten nachziehen,
3. ein **Dekaden-Dashboard** liefern (Verlauf über die Jahre: wo und was besonders zunimmt, Kanalverschiebung,
   Netzwachstum, Auslastung, Spitzentage, Szenariovergleich), ohne das bestehende Jahres-Dashboard zu überladen.

Erfolg: `years: [2025, …, 2035]` läuft in einem Lauf je Szenario durch; 2025 des Basisszenarios bleibt bitidentisch zum
bisherigen Jahreslauf; die Jahresziele (Marktanteile, B2B-Anteil, OOH-Anteile je Jahr) werden exakt eingehalten; das
Dashboard erklärt jede Zahl regelbasiert aus den Annahmen.

## 2. Ausgangslage (was schon da ist)

- `years` ist bereits eine Liste; Kalender, Versandtage, Zustelltage und OOH-Pläne werden je Jahr aufgebaut
  (`workflow.py`: `_holidays`, `delivery_calendar`, `simulate_deliveries`, `plans[year] = build_plan(...)`).
- Nationale Reihen aus den Notebooks 00–02 (`series.py`): Volumen (beobachtet 2000–2023, Notebook-Schätzung
  2024–2028, danach Fit), Marktanteile je Jahr (Amazon-Sigmoid, sechs KEP-Dienstleister), B2B-Anteil je Jahr.
  `_volume` berechnet je Jahr drei Kandidaten (`linear`, `logistic`, `exponential`); genutzt wird heute nur `linear`.
- Regionale Jahresmenge = Referenz 2021 × V(Jahr)/V(2021) (`projection.py::_national_total`).
- OOH-Anteil je Anbieter folgt einem Sigmoid-Trend (`out_of_home.json::trend`, DHL 10 % 2025 → 20 % 2030 → 29 % 2035).
- Synthetische Punkte an Einzelhandels-POIs gibt es schon (`synthesize_shops`, gewichtet nach Bevölkerung im Umkreis).
- Das Stop-Register (Hausstopps + Abholpunkte) wird **vor** der Tagesschleife gebaut und dem `AnnualStoreWriter`
  übergeben; `export_day` hängt alle Abholpunkte als Stops mit `stop_type` an.
- Jahres-Dashboard (`annual_dashboard.py` + Template, eigenständig ohne CDN) rendert **ein** Jahr eines Laufs.
- Laufzeit: ein Jahr ≈ 8 Minuten, ≈ 0,5 GB Tagesdetails + ≈ 45 MB je exportiertem MATSim-Tag. 221 GB frei.

## 3. Entscheidungen (Rulings)

| # | Entscheidung | Begründung | Kosten bei Irrtum |
|---|---|---|---|
| R1 | Jahre **2025–2035** (nicht rückwärts bis 2021). | OOH-Trend und „unterschiedliche Anstiege“ zielen auf die Zukunft; Vergangenheitsjahre bräuchten ein rückwärts schrumpfendes Netz. | Ein Lauf mit `years` ab 2021 ist jederzeit möglich (Netzwachstum ist ab `reference_year` definiert). |
| R2 | Drei Szenarien, alle **am Niveau 2025 des Basislaufs verkettet**; sie unterscheiden sich nur in der Steigung ab 2025. | Vergleichbarkeit; 2025 bleibt identisch zum abgenommenen Jahreslauf. | Absolutniveau 2025 der Notebook-Schätzung (4,30 Mrd.) weicht 1,5 % vom Fit (4,365 Mrd.) ab — dokumentiert, nicht genutzt. |
| R3 | Szenariokurven = die Notebook-Kandidaten: **Trend** = linearer Fit (heutiger Standard), **Sättigung** = logistischer Fit unter `legacy_assumptions`, **Boom** = exponentieller Fit unter `legacy_assumptions`. | Nutzerwunsch: Reihen der alten Notebooks nehmen und verfeinern; die drei Fits existieren bereits. | Wachstumsraten sind Annahmen, keine Prognose; leicht durch eigene Kurven/Raten ersetzbar. |
| R4 | Netzwachstum **nachfragegetrieben mit Elastizität 0,6**: Punktzahl je Netzgruppe wächst mit (OOH-Nachfrage Jahr / Referenz)^0,6, der Rest über größere Stationen. | Ein Parameter, konsistent mit dem OOH-Trend; keine Literaturrecherche nötig (Nutzerrestriktion). | Elastizität ist Konfiguration (`network_growth.elasticity`). |
| R5 | Neue Standorte nur an **Einzelhandels-/Tankstellen-POIs** (OSM), gewichtet mit B2C-Nachfrage im Umkreis × Abdeckungslücke, mit Kind-Präferenzen (Packstation: Supermarkt/Tankstelle; Counter: Kiosk/Convenience; geteilte Box: Supermarkt/Tankstelle). | Entspricht der Standortpraxis der Anbieter; POI-Daten liegen lokal vor. | Präferenzen/Radien sind Konfiguration. |
| R6 | Bestehende, nicht OSM-getaggte Stationen werden **jährlich nach Nachfrage nachdimensioniert, nie verkleinert**, Obergrenze bleibt 390 Fächer. | Anbieter erweitern Module; Kapazität ist ohnehin nachfragebasiert. | Ein Schalter `resize_existing`. |
| R7 | MATSim-Export: Basisszenario alle Jahre mit den **acht Vergleichstagen** (Fr/Sa ISO-Woche 19, Mo–Sa ISO-Woche 20); Varianten nur 2030 und 2035. | Platz/Zeit; Java-Pipeline braucht vor allem den Basispfad. | Weitere Tage per `export-day` nachziehbar. |
| R8 | Dekaden-Dashboard als **eigene Seite** (`decade_dashboard.html`), Jahres-Dashboard bleibt; im Mehrjahreslauf wird zusätzlich je Jahr `annual_dashboard_<jahr>.html` geschrieben (Unterseiten). | Nutzerwunsch: nicht überfrachten, Unterseiten erlaubt. | — |
| R9 | Läufe sequentiell (Trend → Sättigung → Boom), nicht parallel. | Frühere MemoryErrors bei parallelen Läufen. | Gesamtdauer ≈ 4,5 h. |
| R10 | Keine Java-Änderungen, keine Shops (Fehlzustellungsmodell folgt später), keine neue Literaturrecherche. | Nutzervorgaben. | — |

## 4. Architektur

### 4.1 Volumenszenarien (`series.py`, Config)

Neuer optionaler Config-Block (Schlüssel wird in `config.py::_KEYS` aufgenommen):

```json
"volume_scenario": {"name": "saettigung", "policy": "legacy_assumptions", "curve": "logistic", "chain_year": 2025}
```

- `policy` ∈ {`observed_only`, `legacy_assumptions`}, `curve` ∈ {`linear`, `logistic`, `exponential`},
  `chain_year` ganzzahlig ≥ 2021 und in `years` ∪ {`reference_year`}; `name` ein nichtleerer String.
- Neue reine Funktion `apply_volume_scenario(basis: DataFrame, policy_frame: DataFrame, scenario: dict) -> DataFrame`:
  `basis` ist der bisherige Volumenrahmen (Standardpfad, unverändert), `policy_frame` derselbe Rahmen unter
  `scenario.policy`. Für Jahr `y > chain_year`: `value(y) = basis.value(chain_year) × C(y) / C(chain_year)` mit
  `C` = Spalte `scenario.curve` aus `policy_frame`; für `y ≤ chain_year`: `basis.value(y)`. Verkettete Jahre erhalten
  `status = "scenario_projection"`, `curve = "<policy>/<curve>"`; alle Zeilen erhalten `scenario = name`.
  Fehler (ValueError): unbekannte policy/curve, `C` nicht endlich oder ≤ 0 in `chain_year` oder einem späteren Jahr,
  `chain_year` nicht in den Jahren.
- `build_series(..., volume_scenario: dict | None = None)`: ohne Block bleibt alles bitidentisch (Spalte `scenario`
  = `"trend"` wird nur ergänzt, wenn ein Block gegeben ist — die bestehenden Tests dürfen nicht brechen).
- `workflow.py` reicht `config.get("volume_scenario")` an `build_series` durch; `_daily_code`/Stage-Hash enthält den Block.
- Erwartete nationale Werte 2035 (Mrd. Sendungen; 2025 = 4,365 in allen Szenarien): Trend 5,565 (+27 %, ≈ 2,5 %/a),
  Sättigung 5,258 (+20 %, ≈ 1,9 %/a), Boom 6,446 (+48 %, ≈ 4,0 %/a). Regionale Wachstumsfaktoren gegenüber 2021
  (4,51): 1,234 / 1,166 / 1,429.

Drei Lauf-Configs in `hagrid/demand/model/configs/`: `decade-trend.json` (kein Szenarioblock), `decade-saettigung.json`,
`decade-boom.json`; alle mit `years: [2025..2035]`, `output_dir: "../../runs"`, `dates` nach R7 (explizit, per
`date.fromisocalendar` erzeugt), sonst identisch zu `baseline-daily.json`.

### 4.2 Mehrjahreslauf und Exporte (`workflow.py`, `annual.py`)

- Keine Änderung der Jahresschleifen; Prüfpunkte im Plan: Speicher (Listen `prepared`, `rows` sind klein),
  Seeds je Jahr (`named_rng(..., year=year)`), `daily_status.json` führt alle Jahre.
- Am Laufende: `annual_dashboard.html` (letztes Jahr, wie heute) und bei mehr als einem Jahr zusätzlich
  `annual_dashboard_<jahr>.html` je Jahr.
- `export_day` hängt nur Abholpunkte mit `year_opened ≤ Jahr des Tages` an (Spalte kommt aus dem Punktregister, s. 4.3).

### 4.3 Wachsendes Abholnetz (neues Modul `network_growth.py`, Anbindung in `workflow.py`)

Konfiguration (Defaults in `out_of_home.json`, überschreibbar im Lauf-Config-Block `out_of_home.network_growth`):

```json
"network_growth": {
  "enabled": true, "reference_year": 2025, "elasticity": 0.6, "resize_existing": true,
  "demand_radius_m": 500, "gap_scale_m": 600, "min_spacing_m": 150, "off_preference_weight": 0.25,
  "candidate_types": {"shop": ["supermarket", "convenience", "kiosk", "chemist", "newsagent", "tobacco", "variety_store", "bakery"], "amenity": ["fuel"]},
  "kind_preferences": {
    "locker": {"shop": ["supermarket", "convenience"], "amenity": ["fuel"]},
    "shared_locker": {"shop": ["supermarket"], "amenity": ["fuel"]},
    "counter": {"shop": ["kiosk", "convenience", "newsagent", "tobacco", "chemist", "variety_store", "bakery"]}
  }
}
```

Ablauf (Vorpass, **vor** der Tagesschleife, damit das Stop-Register vollständig ist):

1. **Netzgruppen** g = (kind, carriers-String) aus dem Referenznetz (z. B. `("locker","DHL")`, `("locker","Amazon")`,
   `("shared_locker","Hermes|DPD|GLS|UPS|FedEx/TNT")`, `("counter","Amazon")`). Referenzzahl N_g(ref).
2. **OOH-Nachfrage je Anbieter und Jahr** D_c(y) = Σ_sites B2C-Jahreserwartung(y) × share_private(c, y) × ooh_share(y, c)
   (Projektion, Profile und `out_of_home_share` liegen vor der Tagesschleife vor). D_g(y) = Σ_{c∈g} D_c(y).
3. **Ziel** N_g(y) = max(N_g(y−1), round(N_g(ref) × (D_g(y)/D_g(ref))^elasticity)) für y > ref; Zusätze Δ_g(y) = N_g(y) − N_g(y−1).
4. **Kandidaten**: POIs aus `candidate_types` innerhalb der Region, die keinen Punkt derselben Gruppe innerhalb
   `min_spacing_m` haben; Gewicht w_i = B2C-Nachfrage(y) im Umkreis `demand_radius_m` × (1 − exp(−d_i / gap_scale_m))
   × (1 falls POI-Typ in `kind_preferences[kind]`, sonst `off_preference_weight`); d_i = Abstand zum nächsten Punkt
   derselben Gruppe (Stand Jahr y−1 inkl. der in y bereits gezogenen Zusätze — Ziehung ohne Zurücklegen ∝ w, wie
   `synthesize_shops`; Abdeckungslücke wird je Gruppe und Jahr einmal berechnet, nicht nach jeder Ziehung).
5. **Neue Punkte**: `point_id = "syn:<kind>:<gruppe>:<jahr>:<i>"`, `kind`, `carriers`, `brand = "synthetic"`,
   `synthetic = True`, `context = "retail"`, `year_opened = y`, `poi_type = "<shop|amenity>=<wert>"`, `compartments = NaN`
   (Dimensionierung nach Nachfrage im Eröffnungsjahr, `compartments_by_demand`). Referenzpunkte: `year_opened = ref`,
   `poi_type = None`. Die Ziehung nutzt `named_rng(seed, year=y, channel="ooh-network-growth")`.
6. `build_plan(year)` erhält nur aktive Punkte (`year_opened ≤ year`); das Stop-Register enthält alle Punkte mit
   stabilen `stop_index` (Referenzpunkte zuerst, dann Zusätze in Jahresreihenfolge).
7. **Kapazität**: für Punkte ohne OSM-Kapazitätstag gilt `compartments(y) = max(compartments(y−1), sized(y))`
   (nie kleiner, Obergrenze `compartments_by_demand.max`); OSM-getaggte Punkte bleiben fix. Bei
   `resize_existing = false` behalten Referenzpunkte ihre Erstdimensionierung.
8. **Ausgaben**: `out_of_home_points.parquet` (alle Punkte, + `year_opened`, `poi_type`) wie bisher;
   neu `out_of_home_network.parquet` mit einer Zeile je Punkt und aktivem Jahr
   (`year, stop_index, point_id, kind, carriers, brand, context, synthetic, year_opened, poi_type, plz, compartments, lon, lat`);
   `locker_occupancy.parquet` unverändert (Fächer je Datum stehen dort bereits). Statusblock `network_growth` in
   `daily_status.json`: je Jahr und Gruppe Ziel, Zusätze, verfügbare Kandidaten, Fächer gesamt.
- Ist `network_growth.enabled = false` oder gibt es nur ein Jahr, entsteht kein Zusatzpunkt; `year_opened` und
  `poi_type` werden trotzdem geschrieben.
- Fehlerfälle: keine Kandidaten für eine Gruppe → Warnung im Status (`shortfall`), Lauf geht weiter; fehlende
  `osm_points` bei `enabled = true` → ValueError beim Start.

### 4.4 Dekaden-Dashboard (`decade_dashboard.py`, `templates/decade_dashboard.html`, CLI)

- `build_decade_dashboard_data(runs: dict[str, Path]) -> dict` (Szenarioname → Laufordner; der erste ist das
  Basisszenario) und `write_decade_dashboard(runs, out_html) -> Path`; CLI
  `python -m hagrid_demand baseline decade-dashboard --run trend=<dir> --run saettigung=<dir> --run boom=<dir> --out <html>`.
  Fehlt ein Lauf ein Jahr, wird es in der Payload als fehlend markiert (kein Abbruch, solange das Basisszenario
  vollständig ist).
- Payload (Variable `D` im Template, JSON ohne NaN):
  - `meta`: Erzeugungszeit, Szenarien (Name, Label, Lauf-ID, Jahre, nationale Werte, CAGR 2025–2035), Region
    (Einwohner, Anzahl PLZ), Annahmen (Szenariodefinitionen, Elastizität, Radien, OOH-Trendparameter).
  - `national`: beobachtete Anker 2000–2023 und je Szenario die Jahreswerte 2021–2035 (Mrd.).
  - `annual[szenario][jahr]`: Sendungen gesamt/B2C/B2B, je Einwohner, Zustelltage, Mittel je Zustelltag,
    Spitzentag (Datum, Sendungen, Grund wie im Jahres-Dashboard), Spitze/Mittel, Stopps je Tag, je Anbieter,
    Kanäle (Haus, Packstation, geteilte Box, Counter), OOH-Anteil je Anbieter, Netz (Anzahl je Kind, Fächer gesamt,
    neue Standorte je POI-Typ), Auslastung (mittlerer Füllgrad, Anteil voller Punkt-Tage, abgewiesene Sendungen),
    Wochentagsprofil.
  - `plz`: Geometrie (vereinfacht, aus dem Jahres-Dashboard übernommen), Einwohner; je Szenario und Jahr je PLZ
    Hauszustellungen B2C/B2B und OOH-Sendungen.
  - `calendar[szenario][jahr]`: Tagesmengen (Kalenderteppich), Feiertage/Events-Markierungen.
  - `network[szenario]`: Punkte (id, kind, carriers, lon, lat, year_opened, poi_type, synthetic) mit Fächern und
    mittlerem Füllgrad je Jahr.
- Seiten-Abschnitte: `#hero` (Szenarioschalter, Jahres-Scrubber mit Abspielen, KPI-Leiste), `#growth`
  (Volumenfächer national + regional, CAGR), `#mix` (Anbieter-Stapelfläche, B2C/B2B, je Einwohner), `#channels`
  (Kanalverschiebung, OOH-Sigmoide je Anbieter), `#map` (PLZ-Choroplethe mit Scrubber: Sendungen/Tag, Wachstum vs.
  2025, OOH-Anteil; Abholpunkte erscheinen im Eröffnungsjahr), `#hotspots` (PLZ-Ranking absolutes/relatives Wachstum
  2025→2035, B2C/B2B), `#network` (Anzahl je Kind, Fächer, Auslastung, neue Standorte nach POI-Typ), `#calendar`
  (Kalenderteppich Jahr × Tag, Spitzentage je Jahr), `#method` (regelbasierte Erklärtexte aus `meta`).
- Technik wie das Jahres-Dashboard: eigenständige HTML-Datei ohne CDN, Inline-SVG/Canvas, CSS-Tokens, hell/dunkel,
  handybreit ohne horizontales Scrollen; Payload < 2 MB.

### 4.5 Orchestrierung

`runs/hannover/run_demand_decade.bat [szenarien…]` (Standard: `trend saettigung boom`): je Szenario
`python -m hagrid_demand baseline run --config hagrid\demand\model\configs\decade-<szenario>.json --run-id decade-<szenario>`,
danach das Dekaden-Dashboard nach `hagrid\demand\runs\decade_dashboard.html`. Kein xcopy nach `hagrid/simulation`
(bei Bedarf `run_demand_year.bat`-Logik nutzen). Läufe sequentiell (R9).

## 5. Datenfluss

Referenz 2021 → Projektion je Jahr (Volumenszenario, Marktanteile, B2B) → Kalender/Versand je Jahr →
Netz-Vorpass (Referenznetz + Zusätze je Jahr, Stop-Register) → Tagesschleife (Zustellungen, OOH-Umlenkung mit aktiven
Punkten, Fächerwarteschlange) → Jahresspeicher (`daily_aggregates`, Details, Belegung, Netzregister) →
Jahres-Dashboards je Jahr + Dekaden-Dashboard über Läufe.

## 6. Tests

- `series`: Szenarioverkettung (Werte, Status, Spalten), Validierungsfehler, Basis bleibt ohne Block bitidentisch.
- `network_growth`: Ziele (Elastizität, Monotonie, Rundung), Kandidatenfilter (Abstand, Typen), Gewichte
  (Nachfrage × Lücke × Präferenz), Ziehung ohne Zurücklegen, Punktattribute, stabile Stop-Indizes, Kapazität nie
  kleiner, Statusblock, `enabled=false`.
- `annual`: `export_day` hängt nur bis zum Tagesjahr eröffnete Punkte an.
- `workflow`: Mehrjahreslauf auf der Testregion (zwei Jahre) schreibt `out_of_home_network.parquet`, Netzstatus und
  Jahres-Dashboards je Jahr.
- `decade_dashboard`: Payload auf einem Zwei-Jahres-Testlauf (Schlüssel, JSON ohne NaN, HTML enthält `D`), fehlendes
  Jahr in einem Nebenszenario wird markiert.
- Configs: alle drei `decade-*.json` laden fehlerfrei; `dates` folgt der ISO-Wochenregel.

## 7. Nicht-Ziele

Keine Vergangenheitsjahre, keine Shops/Fehlzustellungen, keine Java-Änderungen, keine Kalibrierung der
Wachstumsraten an neue Quellen, keine parallelen Läufe.
