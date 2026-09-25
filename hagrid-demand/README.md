# HAGRID Demand: Daten, Kalibrierung und Prognose

Ausführbare Umsetzung der Kernstages des [Gesamtentwurfs](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/MASTERPLAN.md).
Neben dem Datenfundament existiert die gemeinsame Nachfrage-/Anbieterschätzung mit Modellvergleich,
eingefrorener Anwendung, Kalender, räumlich-zeitlichen Schwankungen, Zukunftspfaden und Liefer-/GIS-Exporten.
**Die Kalibrierung ist vorläufig und hängt von dokumentierten Annahmen ab.**

Vollständiger Modelllauf aus dem Repository-Stamm nach Installation:

```powershell
python -m hagrid_demand run --config hagrid-demand/configs/model.json
```

[Modellbeschreibung, Ein-/Ausgaben und Prüfgrenzen](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/MODEL_WORKFLOW.md).
Der weitere Abschnitt beschreibt den weiterhin separat verfügbaren Datenfundament-Befehl.

## Straßenanker und historischer OSM-Abgleich

Die zusätzlichen Befehle arbeiten ohne MATSim und schreiben jeweils ein eigenes Dashboard:

```powershell
python -m hagrid_demand street-reference --model-run hagrid-demand/runs/kep-reference-20260910 --output hagrid-demand/runs/mein-strassenbezug
python -m hagrid_demand logistics-audit --foundation hagrid-demand/runs/hannover-foundation-dashboard-20260909 --output hagrid-demand/runs/mein-osm-abgleich --regional
```

Die Straßenrekonstruktion erhält die bereinigten DHL-Beobachtungen von 2021 einzeln. Eindeutige,
aber räumlich unbestätigte Kandidaten liefern Modellgewichte. Nicht zuordenbare Mengen stehen separat
in `unallocated_street_demand.parquet` mit der ursprünglichen Straßengeometrie. `street_checks.csv`
und `postal_checks.csv` prüfen jeweils Standortmenge plus offene Menge. Exakte Summen sind hier
eine Datenbindung, keine unabhängige Vorhersagegüte. Andere Anbieter behalten ihre Modellwerte.

Der OSM-Abgleich erfasst den Kartenstand am 31.12.2021 und den aktuellen Serverstand getrennt.
Erfolgreiche Teilabfragen werden mit Abfrage- und Inhaltshashes zwischengespeichert. Bei abgebrochenem
Download denselben Befehl mit demselben Ausgabeordner wiederholen. Größere fehlgeschlagene Abfragen
werden räumlich geteilt. Diese Abfragekacheln sind keine Nachfragezellen. Historische OSM-Lücken sind
keine nachgewiesene Abwesenheit von Gebäuden; Objekte und Betriebe dürfen nicht gleichgesetzt werden.

Ein eigener explorativer Vergleich prüft historische OSM-Merkmale unter den bisherigen äußeren und inneren Folds:

```powershell
python -m hagrid_demand.model_search --config hagrid-demand/configs/local-carriers.json --persons parcel-demand-estimation/input/persons_total.csv --logistics-run hagrid-demand/runs/mein-osm-abgleich --output hagrid-demand/runs/mein-logistikvergleich
```

Dieser Vergleich benötigt einen vollständigen regionalen OSM-Abgleich. Fehlende PLZ werden nicht mit
Nullmerkmalen aufgefüllt. Es gibt keine manuelle Langenhagen-Konstante und keine neuen Zielwertausschlüsse.

Mit `--logistics-snapshot current` lässt sich ausdrücklich ein nachträglicher Vergleich mit aktuellen
Kartenmerkmalen durchführen. Das Dashboard kennzeichnet ihn als zeitlich unpassenden Proxy-Versuch für
DHL 2021; daraus wird keine Modellfreigabe abgeleitet. Der Standard bleibt `2021`.
Beim OSM-Befehl begrenzt `--snapshots current` beziehungsweise `--snapshots 2021` die Wiederaufnahme
auf den gewählten Datenstand. Eine vollständige Antwort des Servers bedeutet keine vollständige
Erfassung des realen Gebäudebestands.

Die Fortsetzung vom 14.09.2026 wurde ausgeführt: Straßenanker mit **85.113** zugeordneten und **826**
offenen Mengeneinheiten; aktueller regionaler OSM-Abgleich; vier zusätzliche Logistik-Kandidaten.
Die vollständige historische Abfrage blieb durch Serverfehler blockiert. Ein gemeinsamer Einstieg liegt unter
`runs/continuation-20260914/dashboard.html`, der Methodenbericht daneben unter `report.md`.
Die neue Straßenreferenz ist noch nicht an die Tages-/Zukunftssimulation angeschlossen.

[Verifizierter Lauf und Ergebnisse](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/VALIDATION.md): sieben Tests bestanden; vollständiger Lauf mit 280.572 Standorten und erhaltener Populationsbilanz.

## Start

Aus diesem Projektverzeichnis mit Python 3.11 oder neuer:

```powershell
python -m pip install -e ".[test]"
hagrid-demand foundation --config configs/hannover.json
```

Alternativ nach Installation: `python -m hagrid_demand foundation --config configs/hannover.json`.
Die relativen Input-/Outputpfade werden relativ zur Konfigurationsdatei aufgelöst, nicht zum aktuellen Arbeitsverzeichnis.
Ein optionales `--run-id mein-lauf` benennt den Run. Existierende Runs werden nicht überschrieben.

## Deterministische Referenz

Der Baseline-Befehl erzeugt ausschließlich die dokumentierte Referenz für 2021. Er liest lokale Rohquellen,
schreibt geprüfte Zwischenartefakte in einen inhaltsadressierten Cache und kopiert die verwendeten Artefakte in
den Run. Die Konfiguration benötigt `source_mode: "raw"`, die Foundation-kompatiblen Quellen H01/H02/H03/H04/H06,
`weekly_source`, CRS-Angaben, `reference_year`, `reference_operating_days` und `output_scope: "reference"`.

```powershell
python -m hagrid_demand baseline run --config configs/baseline-reference.json --run-id reference-2021
python -m hagrid_demand baseline report --run-dir runs/reference-2021
```

Alle Referenzläufe aktualisieren denselben Offline-Einstieg unter `<output_dir>/dashboard/index.html`; die
run-spezifischen Mengen, Scope-Bilanzen und B2B-Anpassungen bleiben in `<run>/report_data.json`. Ein Lauf mit
demselben Namen benötigt `--resume`; geänderte Konfigurationen oder Eingabequellen werden dabei abgelehnt.
Mit `output_scope: "daily"` entsteht zusätzlich der Tageslauf (siehe nächster Abschnitt).

Die früheren Modell-, OSM- und räumlichen Versuche liegen unter `hagrid_demand.experimental`. Die bisherigen
Importpfade bleiben als Weiterleitungen erhalten, damit vorhandene Befehle und Skripte weiterhin funktionieren.

## Tageslauf und MATSim-Export

```powershell
python -m hagrid_demand baseline run --config configs/baseline-daily.json --run-id daily-2025-05
```

Die Beispielkonfiguration rechnet dieselben acht Tage wie `ParcelDemandScenarioGenerator` (09./10.05. und 12.–17.05.2025).
Die Jahresmenge wird einmal über das ganze Jahr verteilt; `dates` wählt nur aus, welche Tage ausgegeben werden.

- **Jahr:** Gesamtmenge × nationale Volumenreihe V(y)/V(2021), B2B-Ziel und Marktanteile je Jahr (Notebooks 00–02).
- **Woche:** Wochenprofil aus `Parcels19_20_21_inter.xlsx` (Notebook 03), `calendar.weekly_profile: "source"`.
- **Wochentag:** Standard ist der Versandtag-/Laufzeitmechanismus (Abschnitt „Versandtage, Laufzeit und Jahresspeicher“).
  Ohne `temporal` gilt direkt die Notebook-Verteilung Mo .16, Di .17, Mi .19, Do .18, Fr .15, Sa .115, So 0 (`calendar.weekday_weights`).
- **Feiertage:** gesetzliche Feiertage Niedersachsen (`calendar.holiday_region`, `holiday_dates`); ohne `temporal` mit `holiday_factor` 0.
- **Betriebstage:** `reference_operating_days: "calendar"` zählt die Liefertage des Referenzjahres mit demselben Kalender (2021: 306).
- **B2B je Anbieter:** Grenzen, Startwerte und Skalierung wie Notebook 05 (`data/provider_priors.json`); das nationale B2B-Ziel wird jedes Jahr exakt getroffen, ohne die Grenzen zu verlassen.
- **Gewerbegewicht:** 1 je Firma; im DHL-Straßencheck erklärt die Beschäftigtenzahl nichts. `business_potential.model: company_plus_employees` (1 + 0,1 × Beschäftigte wie Notebook 06) bleibt optional.
- **Räumliche Tagesstreuung:** Dirichlet zwischen PLZ (`between`, Notebook 50.000) und innerhalb der PLZ (`within_per_site` × Standortzahl).

Mit `matsim_export: true` (Standard im Tageslauf) liegt unter `<run>/matsim/` je Liefertag
`hagrid_parcel_demand_<Datum>_(<Wochentag>).shp` im Format, das `DemandProcessor` der MATSim-Pipeline liest:
Punkte je Nachfragestandort (EPSG:25832), `postal_cod`, `<anbieter>_tag` = B2C, `<anbieter>_type` = B2B
(DBF-Name max. 10 Zeichen, z. B. `amazon_typ`), `total`/`wl_tag` = Summe, dazu die Notebook-Aliase `dhl_b2c`, `ups_b2b` usw.
Tage ohne Lieferung (Sonntag, Feiertag) erzeugen keine Datei. `matsim_export.json` enthält die Tagesbilanz.
Für MATSim den Ordner nach `hagrid-input/demand/<runId>/` kopieren.

### Straßen-Anker, OSM-Gebäude und Stopps

Mit `osm_buildings`/`osm_points` rechnet die Referenz im Straßenmodus (`anchor.mode: street`,
Spezifikation `docs/superpowers/specs/2026-09-24-hagrid-street-anchor-buildings-design.md`):

1. **Gebäude** (`<run>/buildings/`): Personen-Gebäudepunkte gehen an OSM-Gebäude (Stand 01.01.2021), Firmen
   innerhalb ihrer Zensus-100-m-Zelle an passende Gebäude (Fläche × Branchenpassung). Jedes Gebäude bekommt
   seine DHL-Straße (Straßenname + PLZ, sonst nächste Straße bis 100 m, dann bis 250 m), 50-m-Abschnitt und Straßenseite.
2. **Anker** (`reference_anchor.json`, `reference_streets.parquet`): Pegelkorrektur je PLZ über Wohnstraßen
   (nur PLZ mit extremem DHL-Pegel, 2021: 30855), DHL-Raten je Einwohner und Firma, Aufteilung jeder DHL-Straße in
   B2C/B2B, DHL-B2B-Anteil aus diesen Daten, Hochrechnung auf alle Anbieter. Straßen mit DHL-Menge ohne Gebäude
   bekommen synthetische Punkte, Gebäude ohne Straße und DHL-Lücken das Strukturmodell.
3. **Stopps** (`reference_stops.parquet`): Gebäude derselben Straßenseite innerhalb von 2 × 40 m bilden einen Stopp,
   Großempfänger (≥ 15 Pakete/Tag) einen eigenen. Der MATSim-Export schreibt je Stopp eine Zeile (`id`, `stop_id`,
   `str_idx`, `section_id`) und teilt Zeilen über 400 Paketen. Mit `notebook_output_dir` vergleicht der Lauf jeden
   Tag mit der gleichnamigen Datei des Notebook-Generators (Menge, B2B, PLZ-Korrelation; `matsim_export.json` und `report.md`).

Die OSM-Dateien entstehen einmalig aus dem Geofabrik-Auszug (© OpenStreetMap contributors, ODbL):

```powershell
python -m hagrid_demand baseline osm-clip --pbf ../parcel-demand-estimation/input/osm/niedersachsen-210101.osm.pbf --plz ../parcel-demand-estimation/input/plz_region_hannover.csv --out ../parcel-demand-estimation/input/osm
```

Abnahmelauf 24.09.2026 (8 Tage wie der Notebook-Generator, 6 min): q_DHL 0,254; Regionalmenge 2021 60,3 Mio.
Pakete bei 306 Liefertagen; 99,5 % der Personen in Gebäuden; 98 % der Menge direkt aus DHL-Straßen, 0,6 % Strukturrückfall;
Median 51 Pakete je Einwohner und Jahr; Tagesmengen 4–5 % unter dem Notebook (Pegelkorrektur 30855),
PLZ-Korrelation ohne 30855 0,97–0,98; 44–51 Tsd. Stopps je Tag mit im Median 2–3 Paketen.

### Versandtage, Laufzeit und Jahresspeicher

Mit `temporal.mode: shipping_transit` (Standard in `configs/baseline-daily.json`, Spezifikation
`docs/superpowers/specs/2026-09-25-hagrid-shipping-week-annual-design.md`) entsteht der Tagesverlauf aus
Versandtag und Laufzeit statt aus einem festen Zustellprofil:

1. **Versandtag:** Saisonfaktor der Kalenderwoche × Versandprofil je Wochentag (`data/temporal_inputs.json`).
   Gewerbe: LogIKTram-Abholungen Mo .23, Di .21, Mi .18, Do .16, Fr .17, Sa .05. Privat: aus dem Notebook-Zustellprofil
   zurückgerechnet (`derive_shipping_profile`, Sa:So = 2:1) Mo .178, Di .200, Mi .185, Do .150, Fr .113, Sa .116, So .058.
   An Feiertagen wird nichts versendet; diese Menge geht am nächsten Versandtag raus.
2. **Laufzeit:** E+1/E+2/E+3 = 0,85/0,13/0,02 Liefertage (Mo–Sa ohne Feiertage, nie Sonntag), je Anbieter über `transit_days`.
3. **Samstag:** alle Anbieter stellen samstags zu (`saturday_delivery: 1.0`, zu Unterschieden fehlen Daten). Nur 20 % der
   Firmen nehmen samstags an (`business_saturday_open`), der Rest kommt am nächsten Werktag.
4. **Stochastik je Paket:** Wochenfaktor je Segment (AR(1), log-SD 0,016, ρ 0,5), Wochenfaktor je Anbieter (log-SD 0,02),
   täglicher Marktanteils-Schock je Anbieter (`carrier_day_log_sd` 0,03, je Tag über die Anbieter normiert, damit die
   Tagesmenge im Mittel gleich bleibt), Dirichlet-Aufteilung auf die Wochentage (κ 1000), Laufzeit und Samstagsannahme
   je Paket. So weichen die Anbieter von Tag zu Tag und von Woche zu Woche voneinander ab, im Erwartungswert bleibt der
   Verlauf gleich. Die Jahresmenge je Anbieter bleibt erhalten.
5. **Raum:** Jeder Anbieter hat eigene PLZ-Hochburgen (`spatial.carrier_plz_log_sd` 0,15: fester Jahresfaktor je Anbieter
   und PLZ, per IPF so skaliert, dass PLZ- und Anbietermengen gleich bleiben). Vielbesteller (`spatial.site_frailty_cv`
   0,5: Gamma-Faktor je Standort und Jahr, je Straße normiert, damit der DHL-Straßenanker hält) sind an vielen Tagen groß.
   Beides ist eine Annahme ohne Daten und gilt nur im Versandmodus.

Mit `annual_store: true` rechnet der Lauf jeden Tag des Jahres und legt statt 365 Shapefiles einen Jahresspeicher an
(`<run>/annual/`): `stop_daily.parquet` (Datum, Stopp, 14 Zählspalten `<anbieter>_b2c`/`_b2b`), `plz_daily.parquet`,
`days.parquet` (Tagessummen, Stoppkennzahlen) und `annual_summary.json` (Wochen, Monate, Wochentagsprofile).
`dates` legt weiter fest, welche Tage direkt als MATSim-Datei entstehen. Jeden anderen Tag schreibt `export-day`
aus dem Speicher, im selben Format und bei konfigurierten Tagen identisch zum direkten Export:

```powershell
python -m hagrid_demand baseline export-day --run runs/<run-id> --date 2025-06-03
python -m hagrid_demand baseline annual-dashboard --run runs/<run-id> --out runs/<run-id>/year.html
```

`annual-dashboard` baut „Hannover Parcel Year“ (eine HTML-Datei, hell/dunkel, mobil): Kalender mit Tages- und
Wochenauswahl, Kennzahlen, Zeitreihe je Anbieter, Wochentagsprofil, PLZ-Karte und -Tabelle.
`--artifact` lässt Doctype und Head weg, wenn der Host sie selbst setzt.

Abnahmelauf 25.09.2026 (2025, alle 365 Tage, die 8 Notebook-Tage direkt als MATSim-Datei): 16,5 min ohne Cache
(Quellen, Gebäude und Referenz 6 min, Jahres-Tagesstage 7,4 min, Export mit Notebook-Vergleich 2,6 min),
Jahresspeicher 97,6 MB. 58,32 Mio. Pakete an 303 Liefertagen, Jahresmenge je Segment und Anbieter exakt erhalten.
Privates Zustellprofil in den 37 Wochen ohne Feiertag (auch in der Vorwoche) Mo 16,45, Di 17,63, Mi 19,72, Do 18,69,
Fr 15,57, Sa 11,94 % (Notebook 16,58/17,62/19,69/18,65/15,54/11,92); Gewerbe samstags 3,39 %. Nachholtage: Dienstag nach
Ostermontag +57 %, Freitag nach dem 1. Mai +17 %, Samstag nach dem 3. Oktober +29 % gegenüber demselben Wochentag.
Nach Pfingstmontag kommt die Nachholmenge erst am Mittwoch (+78 %); der Dienstag bekommt nur die Wochenendsendungen (−6 %).
KW 20: 1,12 Mio. Pakete; gegenüber dem Notebook-Generator werktags −5 bis −15 %, samstags −24 % (Firmen nehmen samstags
kaum an: B2B-Anteil 6–7 % statt 22 %). `export-day` ist für die direkt exportierten Tage identisch.
Mit Tages-Marktanteilen, PLZ-Hochburgen und Vielbestellern (gleicher Tag, gleiche Konfiguration plus Punkt 4/5):
Tagesanteil DHL privat ± 0,86 pp statt ± 0,61 pp (gewerblich ± 0,97 statt ± 0,59), PLZ-Verteilung der Anbieter log-SD
0,10–0,19 statt ≈ 0, Jahresmenge je Stopp gegenüber dem Erwartungswert CV 28 % statt 4 %; Jahresmengen, Wochentagsprofile,
Speicher (96 MB) und Notebook-Vergleich bleiben praktisch gleich.

## Stages und Outputs

1. `ingest`: SHA-256 der konsumierten Dateien einschließlich SHP-Komponenten; Inventar weiterer lokaler Dateien.
2. `build_sites`: private Gebäudeeinheiten und einzelne Betriebsstätten; Erhalt des Personenbestands.
3. `audit_observations`: DHL-Straßen und Hermes-PLZ/Jahr-Werte in ursprünglicher Semantik, Qualitätsflags.
4. `link_candidates`: eindeutige PLZ-Mitgliedschaft und nächste DHL-Linie innerhalb einer konfigurierbaren Entfernung.
5. `report`: Mengenbilanzen, Zuordnungsstatus und offene Voraussetzungen für die Kalibrierung.
6. `dashboard`: eigenständiges HTML-Dashboard mit PLZ-Karte, Filtern, Tabellen und automatisch berechneten Befunden.

Ergebnisse liegen unter `runs/<run-id>/`. `dashboard.html`, `report.md` und `summary.json` sind die Einstiege.
Standort- und Beobachtungstabellen werden als Parquet/GeoParquet geschrieben. Sie enthalten lokale Quellkennungen;
das gesamte Run-Verzeichnis ist vom Git-Tracking ausgeschlossen. Rohdaten werden ausschließlich gelesen.

## Dashboard

Jeder erfolgreiche Foundation-Lauf erzeugt automatisch `dashboard.html` und `dashboard_data.json`.
Die HTML-Datei lässt sich direkt im Browser öffnen, benötigt keinen Server und lädt keine externen Dienste.
Sie zeigt aggregierte Daten, keine individuellen Standort- oder Empfängerkennungen.

- Filter: alle Standorte, private Gebäude, Betriebsstätten.
- PLZ-Karte: offene Anzahl/Quote sowie betroffene Einwohner oder Beschäftigte; Klick filtert die Tabelle.
- Sortierbare PLZ-Tabelle und Branchenübersicht.
- Entfernungsverteilung mit genau einem Wert je Standort, auch bei mehreren Kandidaten.
- Quellenübersicht, Haushaltslücken, wiederholte Straßenschlüssel und Interpretationsgrenzen.

Ein vorhandener Run kann ohne erneuten Datenimport ausgewertet werden:

```powershell
python -m hagrid_demand dashboard --run-dir runs/<run-id>
```

Dieser Befehl aktualisiert nur die beiden Dashboard-Artefakte. Der ursprüngliche Run-/Fit-Status bleibt erhalten.
Erzeugungszeit und Renderer-Hashes werden separat in `dashboard_data.json` gespeichert. Ältere Foundation-Runs
können damit mit einem neueren Dashboard ausgewertet werden, ohne ihre ursprüngliche Codeversion umzuschreiben.

Die [Auswertung des ersten Dashboard-Laufs](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/DASHBOARD_ANALYSIS.md)
beschreibt die auffälligsten Unterschiede und die daraus abgeleiteten nächsten Prüfungen.

Der Fit darf die Kandidaten nicht als bestätigte Zuordnung verwenden. Bei fehlenden Adressen sind Nähe und PLZ
nur Indizien. Gleich weit entfernte Linien, Grenzpunkte, fehlende Geometrien und widersprüchliche Gebäude-IDs
bleiben sichtbar. Wiederholte DHL-Straßenschlüssel werden nicht automatisch aufsummiert oder dedupliziert.
Es werden keine Paketmengen auf Standorte verteilt und keine Netzanschlüsse erfunden.

Ein Run gilt als technisch vollständig, kann aber `calibration_ready: false` haben. Diese Version setzt dies
immer auf false: Die Definition von `tagesschni`, Hermes-Einheiten, zeitliche Herkunft und tatsächliche Zuordnung
müssen vor einer Kalibrierung bestätigt werden. Der Distanzwert 100 m und die Gebäudetoleranz 5 m sind
konfigurierbare Arbeitsannahmen, keine empirisch optimierten Parameter.

Geometrie- und Identitätsfehler mit unklarer Bedeutung werden gemeldet; keine stillen Dublettenlöschungen.
Mehrere Firmen am selben Punkt bleiben getrennte Einheiten. Gemeinsame physische Gebäude-/Eingangs-IDs
werden erst mit einer belastbaren Gebäude-/Adressreferenz ergänzt.

## Räumlich-zeitlicher Methodenvergleich

Der zusätzliche Befehl `spatial-demo` vergleicht eine feste mit einer räumlich-zeitlich schwankenden Verteilung
bei **identischen Tagesgesamtmengen je Segment**. Er arbeitet direkt auf den Standortkoordinaten; PLZ werden
nur zur Auswertung verwendet. Er benötigt einen vorhandenen Foundation-Run.

Aus dem Repository-Stamm:

```powershell
python -m hagrid_demand spatial-demo --config hagrid-demand/configs/spatial-demo.json
```

Die Beispielkonfiguration verwendet sieben Tage, Seed 42 und illustrative Mengen von 180.000 privaten sowie
40.000 gewerblichen Paketen pro Tag. Das sind **keine geschätzten Hannover-Mengen**. Private Basisgewichte
sind Bevölkerung, gewerbliche Beschäftigte; die Gewichte sind noch keine kalibrierten Intensitäten.
Parameter und Inputpfade werden relativ zur Konfigurationsdatei aufgelöst.

Pro Datum/Segment entstehen Standort-Parquet-Dateien mit festen und veränderten Erwartungswerten sowie
ganzzahligen Tagesmengen. `comparison.json` enthält PLZ-Aggregate und Diagnostik. `dashboard.html` wird
automatisch erzeugt: Datum, Empfängersegment und Erwartung/Tagesziehung sind umschaltbar.

Das räumliche Feld verwendet eine endliche Fourier-Approximation eines glatten Gauß-Kovarianzkerns.
Die Koeffizienten folgen einem stationär initialisierten AR(1)-Prozess. `length_scale_m` steuert die
räumliche Reichweite, `log_sigma` die Stärke und `temporal_rho` die zeitliche Persistenz. Die Faktoren werden
mit den Basisgewichten multipliziert und je Segment normalisiert. Dadurch verändern sich räumliche Anteile,
aber nicht die vorgegebene Gesamtmenge. Der räumliche Kern ist ein Kandidat; Straßenbarrieren und
branchenbezogene gemeinsame Schocks sind damit noch nicht abgebildet.

Kalender, Gesamtmengenschwankung, LSP-Zuordnung und Zukunftswachstum sind aus diesem kontrollierten Vergleich
ausgeklammert. Das Beispiel belegt Mengenbilanzen und Reproduzierbarkeit, keine empirische Nachfragegüte.
Die Datumsbindung der Zufallsströme erlaubt verlängerte oder verschobene Zeitfenster mit identischen
überlappenden Tagen, solange Standortbestand, Parameter, Seed und Zeitanker gleich bleiben.

[Erster Vergleichslauf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/runs/spatial-comparison-20260909/dashboard.html):
13 Tests bestanden; alle 14 Tages-/Segmentdateien erhalten die vorgegebenen ganzzahligen Mengen.
Die räumlich umverteilte Erwartung beträgt in diesem Beispiel 8,33–11,37 % für private und 12,06–24,66 %
für gewerbliche Nachfrage. Diese Werte hängen unmittelbar von den angenommenen Parametern ab.

## Tests ausführen

```powershell
python -m pytest -q
```

Die Tests prüfen Bestandsbilanzen, widersprüchliche Gebäude, fehlende IDs, räumliche Mehrdeutigkeit,
PLZ-Grenzen und Null-/Fehlwerte. Reale Daten werden über den vollständigen Foundation-Lauf geprüft.
Bei einem Fehler bleibt `run.json` mit Stage-Status erhalten. Wiederaufnahme einzelner Stages und
inkrementelle Caches sind in dieser ersten Lieferung noch nicht implementiert; ein neuer Run startet vollständig.
