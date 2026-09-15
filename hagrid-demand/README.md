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
