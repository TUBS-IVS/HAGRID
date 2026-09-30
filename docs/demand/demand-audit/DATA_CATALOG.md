# Datenkatalog für das gemeinsame Nachfragemodell

Stand: 9. September 2026. Zugehöriger [Gesamtentwurf](C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/MASTERPLAN.md). Dies ist das fachliche Quellenverzeichnis. Das implementierte Python-Datenfundament inventarisiert inzwischen das lokale Input-Verzeichnis und hasht die konsumierten Dateien H01/H02/H03/H04/H06 einschließlich benötigter SHP-Komponenten. Weitere Dateien werden ausdrücklich als nicht verarbeitete Inventareinträge geführt. Die Bestandszahlen unten stammen aus dem Audit; zusätzliche Prüfungen des Datenlaufs stehen im [Validierungsbericht](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/VALIDATION.md).

Status: **profiliert** = ausgewählte Inhalte/Schemata geprüft; **vorhanden** = lokale Datei bekannt, fachlich noch nicht vollständig geprüft; **dokumentiert** = im PANDA-Code/Dokumentation genannt, Rohdaten in der Review-Kopie nicht enthalten; **Ergänzung** = mögliche externe Quelle, noch nicht integriert.

## Lokale HAGRID-Quellen

Die Pfade beziehen sich auf [input](C:/Users/bienzeisler/Documents/GitHub/HAGRID/parcel-demand-estimation/input), soweit nicht anders angegeben. Gleichnamige Batch-Kopien sind vor Zusammenführung per Hash und Inhalt abzugleichen.

| ID / Quelle | Status und Bestand | Rolle | Vor Verwendung klären |
|---|---|---|---|
| H01 persons_total.csv/.shp | Profiliert: 1.150.862 Personen, 227.641 Building-IDs; 536.496 fehlende Household-Werte; Punkte EPSG:25832 | Private Standortstruktur, Bestandsprüfung | Erzeugungsverfahren und Referenzjahr; synthetisch vs. beobachtet; Konsistenz Gebäude-ID/Lage; CSV und SHP nicht doppelt zählen |
| H02 companies_Total_reduced.shp | Profiliert: 52.931 eindeutige IDs, 19 Branchenwerte, 650.267 Beschäftigte; Punkte EPSG:25832 | Gewerbliche Intensitätsmerkmale und Standortstruktur | Branchenklassifikation, Jahr, Betriebsstätte vs. Firmensitz, Geocodierung, Bestandsabdeckung, Netzlink-Version |
| H03 lsp2streets_2021.shp | Schema/Bestand geprüft: 12.342 Linien; name, plz, tagesschni; EPSG:4326 | LSP-Kalibrierung und zurückgehaltene Validierung | Paketdefinition, Tagesmittelnenner, Erfassungsfenster, Null/fehlend, Doppelung bei Linienfragmenten, Paketstationen und Großkunden |
| H04 Hermes_PLZ-Menge_2019-2021.csv | Vorhanden; PANDA besitzt ebenfalls einen Hermes-Adapter | Zweiter Anbieteranker | Einheit/Periode je Spalte, PLZ-Abdeckung, Quellidentität mit PANDA, Formvergleich vs. absolute Validierung |
| H05 final_grid_250_region_results_update.csv | Profiliert: 15.917 Zellen, fertige Mengen/B2B-/Fit-Felder | Historische Referenz; Herkunft bisheriger Annahmen | Wie Mengen und B2B-Ratio entstanden; nicht als unabhängiges Trainingsziel verwenden |
| H06 plz_region_hannover.csv | Vorhanden | Beobachtungsabdeckung und Berichtsaggregation | Geometrie, CRS, Gebietsstand, PLZ-Änderungen |
| H07 osm_landuse_region_hannover.csv | Vorhanden | Nutzungsmerkmale und räumliche Plausibilität | OSM-Zeitstand, Klassifikation, Überschneidungen und Verhältnis zu PANDA-Features |
| H08 multimodalNetwork.xml | Vorhanden | Netzzugang und nachgelagerte Logistik | Netzversion, Modi, Geometrie, gültige bestehende Firmenlinks; keine pauschale Nachfrageableitung aus Netzdichte |
| H09 Parcels19_20_21_inter.xlsx | Vorhanden | Mögliche Zeit-/Mengeninformation nach Prüfung | Blätter, Einheiten, Geographie, beobachtete vs. interpolierte Werte; Dateiname ist kein Datenvertrag |
| H10 Notebook 00–02 und ihre Exporte | Code geprüft; eingeschriebene Markt-/B2B-/Volumenreihen mit Fortschreibungen | Externe Ausgangswerte und Modellvergleich | Originalquellen pro Jahr, Paketdefinition, Prognosekennzeichnung; Zukunftswerte nicht als Beobachtung trainieren |
| H11 Notebook 03 / Wochenprofil | Code geprüft; Schweizer Reihe 2019–2021 laut Audit | Kandidat für zeitliches Profil | Originalquelle, Übertragbarkeit auf Hannover, Sonderjahre, Kalender und Normierung |
| H12 bestehende Demand-Outputs und Segment-Samples | Ausgewählte Profile geprüft | Vergleich und Exportkompatibilität | Run-Konfiguration, Herkunft, abgeleitete Mengen; keine unabhängige Nachfragewahrheit |

## PANDA-Quellen und Methoden

Quellstand: [PANDA DATA.md](https://github.com/HBimmermann/PANDA/blob/1e683d026cec3483214523280877ef44e012d6d5/DATA.md). Rohdaten und Caches sind dort als nicht versioniert dokumentiert. Der Repository-Zugang ersetzt nicht die Verfügbarkeit dieser Daten.

| ID / Quelle | Status | Rolle | Voraussetzung |
|---|---|---|---|
| P01 Zensus2022_Bevoelkerungszahl.zip | Dokumentiert, 100-m-Input | PANDA-Reproduktion; Abgleich H01 | Datei bereitstellen, Gebiets-/Zeitschnitt und Bevölkerungsdefinition abgleichen; kein Rasterzwang für Modelloutput |
| P02 Alter_in_5_Altersklassen.zip | Dokumentiert | Optionaler Merkmalsvergleich | Nur nach zusätzlichem Validierungsnutzen übernehmen |
| P03 Zensus2022_Durchschn_Nettokaltmiete.zip | Dokumentiert | Optionaler Kontextindikator | Räumliche Abdeckung, fehlende Werte, keine individuelle Einkommensinterpretation |
| P04 OSMData/HAN_*.geojson | Dokumentiert | Gebäude, Geschossflächen, Nutzung und POIs | Originalstand für exakte Reproduktion; Proxydefinitionen prüfen; Betriebe mit H02 abgleichen |
| P05 PLZ_Gebiete_*.zip | Dokumentiert | PANDA-Beobachtungseinheiten | Mit H06 vergleichen; Grenzen und IDs harmonisieren |
| P06 KEP/versandmengen-realdaten | Dokumentiert; vermutlich überlappende LSP/Hermes-Quellen | Beobachtungen | Identität/Inhalte vor Verwendung abgleichen; Überlappung nicht doppelt gewichten |
| P07 KEP/Standorte und Kandidaten für Paketstationen | Dokumentiert | Sonderstandorte und Lieferzielwahl | Existierender Standort vs. Kandidat; Anbieter, Gültigkeit, Kapazität und enthaltene Sendungen |
| P08 fitted_params / carrier_split / Studien | Code und ausgewählte Tests geprüft | Referenzmodell, Priors und Evaluationsmethoden | Fit auf LSP-Niveau beachten; Herkunft der Segmentanteile; dokumentierte CV noch nicht reproduziert |
| P09 räumliche Caches / vm-hochrechnung | Dokumentiert, abgeleitet | Reproduktion und Exportvergleich | Keine unabhängigen Messungen; mit Daten-/Codeversion verbinden |

## Externe Ergänzungen nach erwarteter Wirkung

Die verlinkten Quellen wurden im bisherigen Review recherchiert; ihre konkrete Lieferung, Nutzbarkeit und Integration sind offen. Aggregierte Daten werden nicht als adressgenaue Beobachtungen ausgegeben.

| Priorität | Quelle / Information | Zu schließende Lücke |
|---|---|---|
| A | Neuere LSP-Mengen und vollständige Messdefinition der vorhandenen Quelle | Zeitliche Aktualität, absolute Kalibrierung und echte Tagesstreuung |
| A | Hermes bzw. weitere unabhängige Anbieteraggregate | Übertragbarkeit und Fremdanbieterniveau |
| A | Kleine nach Branche und Betriebsgröße geschichtete Empfangsstichprobe | Paketintensitäten und B2B-/Anbieterprofile direkt prüfen; keine Befragung wurde veranlasst |
| B | [Zensus 2022](https://www.destatis.de/DE/Themen/Gesellschaft-Umwelt/Bevoelkerung/Zensus2022/_inhalt.html) | Wohnbevölkerung/-struktur prüfen, Lücken in synthetischem Bestand erkennen |
| B | [LGLN Hausumringe](https://lgln-geodaten.niedersachsen.de/startseite/geodaten_und_karten/liegenschaftsinformationen_aus_alkis/hausumringe/hausumringe-232059.html) | Physische Gebäude als Standortreferenz; kein automatischer Nachweis eines Eingangs |
| B | [LSN Niederlassungen](https://www.statistik.niedersachsen.de/startseite/themen/unternehmen_gewerbeanzeigen_insolvenzen/unternehmen_in_niedersachsen/unternehmen-in-niedersachsen-tabellen-zu-den-niederlassungen-181627.html) | Branchen-/Größenbestand auf Aggregatebene prüfen; keine öffentliche Adressliste voraussetzen |
| B | [BA Beschäftigte](https://statistik.arbeitsagentur.de/DE/Navigation/Statistiken/Fachstatistiken/Beschaeftigung/Beschaeftigte/Beschaeftigte-Nav.html) | Beschäftigtenstruktur und Zeitentwicklung; statistische Abgrenzung gegenüber H02 prüfen |
| B | [Hannover Bevölkerungsprognose 2025–2035](https://www.hannover.de/content/download/1059363/file/Bev%C3%B6lkerungsprognose_Region_Hannover_2025_2035.pdf) | Regionale Zukunftspfade; keine exakt vorhergesagten neuen Gebäude |
| B | [Bundesnetzagentur Paketmengen](https://www.bundesnetzagentur.de/DE/Fachthemen/Datenportal/3_Post/_svg_Post/Paket/P_Paketmarkt_Mengen/P_Paketmarkt_Mengen.html) | Aktuelle nationale Volumenanker; Prognosen von Beobachtungen trennen |
| C | Weitere Kauf-/Haushaltsmerkmale | Nur bei zusätzlichem Nutzen; Onlinekauf-Teilnahme ist keine Paketanzahl |

## Pflichtfelder des künftigen Quellenmanifests

`source_id`, `path_or_uri`, `sha256`, `publisher`, `retrieved_at`, `reference_start`, `reference_end`, `geography`, `crs`, `unit`, `measurement_definition`, `value_kind`, `derived_from`, `coverage`, `missing_value_semantics`, `usage_conditions`, `quality_flags`, `allowed_roles`.

Unbekannte Felder bleiben explizit offen. Für einen Fit benötigte Definitionen müssen geklärt sein; der Datenimport und das Profiling können vorher stattfinden. Besonders H03-Einheit und H01/H02-Referenzjahre sind früh zu prüfen. Dateihashes erkennen Byteidentität; umbenannte oder umformatierte Kopien erfordern zusätzlich fachlichen Abgleich.
