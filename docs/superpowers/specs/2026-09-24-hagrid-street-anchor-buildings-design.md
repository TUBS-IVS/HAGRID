# HAGRID Teilprojekt A: Straßen-Anker, Gebäude und Stopps

Stand: 24. September 2026. Status: Design im Gespräch abgenommen (Abschnitte 1–3), Spezifikation zur Prüfung.
Baut auf der bestehenden Baseline im Worktree `.worktrees/hagrid-baseline` (Branch `codex/hagrid-baseline`) auf.
Teilprojekt B (Tagesprozess) und C (Unsicherheit/Monte Carlo) folgen mit eigenen Specs.

## 1. Ziel und Erfolgskriterien

Ein Paketnachfragemodell für die Region Hannover, das die DHL-Daten 2021 als räumliche Hauptbeobachtung nutzt,
Anbieter über den lokalen B2B/B2C-Mix unterscheidet und die Nachfrage auf OSM-Gebäuden zu realistischen Stopps bündelt.

Erfolgskriterien (vom Nutzer gewählt):

- **A – DHL-Straßen-Holdout:** Das Strukturmodell (Rückfall und Zukunftskomponente) wird auf DHL-Straßen mit räumlichem Holdout nach PLZ bewertet.
- **C – Plausibilität:** Pakete je Person und Jahr, B2B-Anteil je PLZ, Pakete je Stopp, Stopps je Tag und Anteil belieferter Gebäude sind plausibel und dokumentiert.

Hermes-Daten werden nicht verwendet (Einheit unbekannt).

## 2. Befunde, auf denen das Design beruht

| Befund | Zahl | Folge |
|---|---|---|
| DHL-Pegel in PLZ 30855 gleichmäßig überhöht | Wohnstraßen 161 statt Median 62 Pakete je 1.000 Einwohner und Tag; Faktor 3,7 über alle Straßentypen; Nachbar-PLZ normal; kein DHL-Paketdepot, Packstationsdichte normal | Pegelkorrektur D (Abschnitt 5.5) |
| DHL-B2B-Anteil aus eigenen Straßendaten | 24,2 % (Radius 50–150 m: 23–26 %; Bootstrap 90 %: 20,6–27,9 %; Stadt und Umland gleich) | q_DHL aus Daten statt 11,3 % (alte Baseline) bzw. 16,6 % (Notebook 05) |
| Beschäftigtenzahl erklärt DHL-Menge nicht | NNLS-Koeffizient 0; PLZ-Holdout-Fehler „1 je Firma“ 12,9 %, „1 + 0,1 × Beschäftigte“ 15,7 % | Standard wieder „1 je Firma“ |
| Firmendaten synthetisch | keine zwei Firmen am selben Punkt; IDs mit Zonennamen; Zensus-100-m-Zelle und MATSim-Link je Firma; nur 25 % liegen in einem OSM-Gebäude | Firmen auf Gebäude umlegen (5.3) |
| Personen auf Gebäudegrundrissen | 94 % der Personen liegen in OSM-Gebäuden 2021 | B2C direkt an Gebäude (5.2) |
| OSM 2021 Region | 253.918 Gebäude, 71 % der relevanten mit Adresse | Adressbasierte Straßenzuordnung (5.4) |

## 3. Entscheidungen

| Thema | Entscheidung |
|---|---|
| Räumlicher Anker | DHL-Straße (12.340 Straßen, Schwelle > 1.000 bleibt) statt PLZ-Summe |
| Ausreißer-PLZ | Variante D: Pegel über Wohnstraßen korrigieren, Muster innerhalb der PLZ bleibt |
| DHL-B2B | aus der Straßenzerlegung; über die Zeit proportional zum nationalen B2B-Anteil |
| Andere Anbieter | innerhalb der Notebook-05-Grenzen auf das nationale Ziel abgestimmt, DHL fest |
| Nachfrageorte | OSM-Gebäude Stand 01.01.2021 (Geofabrik) |
| Stopps | S3: adaptive Stopps je Straße und Straßenseite, Laufradius einstellbar |
| Firmengewicht | 1 je Firma; „1 + 0,1 × Beschäftigte“ bleibt Option |
| Zeitliche Fortschreibung | unverändert (nationale Volumen- und B2B-Reihe, Muster 2021 fest bis Task 9) |

## 4. Datenquellen

| Quelle | Pfad | Nutzung |
|---|---|---|
| DHL-Straßen 2021 | `parcel-demand-estimation/input/dhl2streets_2021.shp` | Anker, Zerlegung |
| Personen | `persons_total.csv` | B2C-Gewicht je Gebäude |
| Firmen | `companies_Total_reduced.shp` | B2B-Gewicht, Zensus-Zelle, Branche |
| PLZ-Flächen | `plz_region_hannover.csv` | Scope, Pegelkorrektur |
| OSM 2021 | `parcel-demand-estimation/input/osm/osm_buildings_region_hannover_2021.parquet`, `osm_address_poi_region_hannover_2021.parquet`, Manifest | Gebäude, Adressen, POI |

Die OSM-Dateien entstehen aus `niedersachsen-210101.osm.pbf` (Geofabrik, 351.802.303 Byte, MD5 `9773d7511fb93945565ef625a8b7657e`),
zugeschnitten auf die 53 PLZ plus 250 m. Lizenz ODbL, Nennung „© OpenStreetMap contributors“ im Bericht und Dashboard.
Der Zuschnitt wird als Quellstage in das Paket übernommen (bisher Scratch-Skript).

## 5. Verfahren

### 5.1 Gebäudebasis

Relevante Gebäude sind alle OSM-Gebäude außer einer einstellbaren Ausschlussliste
(Standard: `garage, garages, roof, hut, shed, carport, greenhouse, barn, farm_auxiliary, parking, construction, ruins, bunker, toilets, transformer_tower`).
Jedes Gebäude erhält `building_id` (OSM-ID), Grundfläche, Typ, Adresse (5.4) und die Zensus-100-m-Zelle seines Schwerpunkts.

### 5.2 B2C an Gebäude

Jeder Personen-Gebäudepunkt geht an das relevante Gebäude, das ihn enthält; sonst an das nächste relevante Gebäude bis 65 m;
sonst bleibt er als Gebäude ohne Umriss (`footprint=false`) mit seinem Punkt. Einwohner je Gebäude sind die Summe.
Bilanz: Summe der Einwohner vor und nach der Zuordnung ist identisch.

### 5.3 B2B an Gebäude

Für jede Firma sind Kandidaten die relevanten Gebäude, die ihre Zensus-100-m-Zelle schneiden und höchstens 100 m entfernt sind.
Gewicht = Grundfläche × Branchenpassung:

| Branche (NACE) | passende Gebäudetypen (Faktor 1,0) | Wohngebäude | sonstige |
|---|---|---|---|
| G Handel, I Gastronomie | retail, commercial, supermarket, kiosk, hotel | 0,5 | 0,3 |
| C Industrie, F Bau, H Verkehr/Lager | industrial, warehouse, manufacture, hangar, transportation | 0,1 | 0,3 |
| J–N Dienstleistung | office, commercial | 0,5 | 0,3 |
| O–Q Öffentlich, Bildung, Gesundheit | public, school, university, hospital, civic, government, kindergarten | 0,3 | 0,3 |
| übrige | commercial, office, industrial | 0,5 | 0,3 |

Typ `yes` gilt je nach Kontext: mit Einwohnern als Wohngebäude, ohne Einwohner als „sonstige“. POI-Punkte mit `shop`/`office`/`amenity`/`craft`
im Gebäude heben die Passung auf 1,0. Die Auswahl ist gewichtet zufällig mit `named_rng(seed, firm_id, channel="firm-building")`
und damit reproduzierbar. Mehrere Firmen je Gebäude sind erlaubt. Ohne Kandidat: nächstes relevantes Gebäude bis 250 m,
sonst bleibt der Firmenpunkt (`footprint=false`). Alle Fälle stehen im Zuordnungsbericht.

### 5.4 Adresse und DHL-Straße

Adresse eines Gebäudes: `addr:street`/`addr:housenumber` am Gebäude, sonst ein Adresspunkt innerhalb des Gebäudes.
Straßennamen werden normalisiert (Kleinschreibung, `str.`/`strasse` → `straße`, `ss`/`ß` vereinheitlicht, Leerzeichen/Bindestriche).
Zuordnung zur DHL-Straße in dieser Reihenfolge:

1. normalisierter Straßenname und PLZ des Gebäudes gleich einer DHL-Straße (bei mehreren Teilstücken: nächstes Teilstück);
2. nächste DHL-Straße bis 100 m;
3. keine DHL-Straße → Rückfall (5.9).

Der Anteil je Stufe wird berichtet.

### 5.5 Pegelkorrektur D

Wohnstraßen einer PLZ sind DHL-Straßen mit mindestens 30 zugeordneten Einwohnern und ohne Firma.
Rate R_p = ΣDHL / ΣEinwohner über die Wohnstraßen der PLZ; M = Median der R_p aller PLZ.
Für PLZ mit mindestens 20 Wohnstraßen und f_p = R_p / M ≥ 2 oder ≤ 0,5 gilt DHL′_s = DHL_s / f_p für alle Straßen der PLZ.
Schwellen sind einstellbar. Erwartetes Ergebnis mit den Daten 2021: nur PLZ 30855, f ≈ 2,6. Korrigierte PLZ, Faktoren und
Mengen vor/nach stehen im Bericht.

### 5.6 DHL-Raten und Zerlegung je Straße

Auf den korrigierten Straßenwerten wird regional ohne Achsenabschnitt nichtnegativ geschätzt:
DHL′_s ≈ r_P · P_s + r_C · C_s (P_s Einwohner, C_s Firmen der zugeordneten Gebäude).
Aufteilung jeder Straße im Verhältnis der Erwartung:
DHL_B2C,s = DHL′_s · r_P P_s / (r_P P_s + r_C C_s), DHL_B2B,s = DHL′_s − DHL_B2C,s.
Straßen mit positiver DHL-Menge ohne zugeordnete Struktur werden mit dem regionalen Verhältnis aufgeteilt und
auf synthetische Punkte entlang der Straße gelegt (je 50-m-Abschnitt einer).

### 5.7 DHL-B2B-Anteil und Anbieterprofile

q_DHL(2021) = ΣDHL_B2B / ΣDHL′; q_DHL(y) = q_DHL(2021) · b(y) / b(2021) mit der nationalen B2B-Reihe b(y).
Die übrigen Anbieter werden je Jahr mit `reconcile_carriers` auf b(y) abgestimmt: DHL mit Unter- = Obergrenze q_DHL(y),
übrige Grenzen, Startwerte und Skalen wie Notebook 05 (`provider_priors.json`). Unzulässig → harter Fehler.
Daraus P(c | B2C) = m_c (1 − q_c) / (1 − b) und P(c | B2B) = m_c q_c / b.

### 5.8 Hochrechnung auf alle Anbieter

B2C_s = DHL_B2C,s / P(DHL | B2C), B2B_s = DHL_B2B,s / P(DHL | B2B) (Tagesmittel 2021), Jahresmenge = Tagesmittel × Betriebstage
(`reference_operating_days`, Standard `calendar`). Prüfidentitäten: Σ(B2C_s + B2B_s) = ΣDHL′ / m_DHL und Regional-B2B = b(2021) bis auf 1e-9.

### 5.9 Rückfall auf das Strukturmodell

Gilt für Gebäude ohne DHL-Straße und für Straßen mit DHL = 0, deren Strukturerwartung r_P P_s + r_C C_s ≥ 5 DHL-Pakete je Tag ist
(Datenlücke). Nachfrage je Segment = Strukturerwartung des Segments / P(DHL | Segment). Diese Mengen kommen zur beobachtungsbasierten
Menge hinzu und stehen im Bericht getrennt (`anchor_status = observed | structural_gap | structural_no_street`). Die Identitäten aus 5.8
gelten für den beobachtungsbasierten Teil; Gesamtmenge und Regional-B2B inklusive Rückfall werden zusätzlich berichtet.

### 5.10 Verteilung auf Gebäude

B2C einer Straße nach Einwohnern auf ihre Gebäude, B2B zu gleichen Teilen je Firma und dann je Gebäude summiert.
Ergebnis: `annual_expected` je Gebäude × Segment; dieselbe Tabelle speist Jahresprojektion und Tagesgenerator wie bisher die Standorte.

### 5.11 Abschnitte und Straßenseite

DHL-Straßen werden wie in Notebook 06 je LineString-Teil in 50-m-Abschnitte geteilt (Rest als eigenes Stück).
Jedes Gebäude wird auf die Achse seiner DHL-Straße projiziert: Abschnitt = enthaltendes Stück, Seite = Vorzeichen des Kreuzprodukts
(links/rechts in Digitalisierrichtung), Position = Distanz entlang der Straße.

### 5.12 Stopps (S3)

Innerhalb von (Straße, Seite) werden Gebäude nach Position sortiert und gierig gruppiert: Ein Stopp umfasst Gebäude, deren Positionen
höchstens 2 × `walking_radius_m` auseinanderliegen (Standard 40 m). Gebäude mit ≥ `own_stop_parcels_per_day` (Standard 15) erwarteten
Paketen je Tag bilden einen eigenen Stopp. Stopppunkt = paketgewichteter Mittelpunkt der Positionen, auf der Achse.
`stop_id` ist stabil (Hash aus Straßen-ID, Seite und erster Gebäude-ID) und über alle Tage gleich.
Der Export teilt einen Stopp in mehrere Zeilen am selben Punkt, sobald eine Anbieter-Segment-Zahl an einem Tag `max_parcels_per_row`
(Standard 400) überschreitet; damit greift die Java-Grenze 450 nicht.

### 5.13 Zeit und Tageslauf

Jahresprojektion, Kalender (Wochenprofil, Wochentage, Feiertage NI), Tagesgenerator und Anbieterziehung bleiben wie in der Baseline.
Übergangsweise arbeitet der Dirichlet-Tagesgenerator auf Gebäuden und die Tagesmengen werden je Stopp summiert;
die methodische Neugestaltung der Tagesstreuung ist Teilprojekt B.

### 5.14 Export

Der MATSim-Export schreibt je Liefertag eine Zeile je Stopp (bzw. Teilzeile nach 5.12) im bestehenden Vertrag
(`<anbieter>_tag` B2C, `<anbieter>_type`/`_typ` B2B, 14 Notebook-Aliase `{ama,…}_{b2b,b2c}`, `total = total_sim = wl_tag`, `postal_cod`, `date`)
und ergänzt `id` (int64, stabil je Stopp/Teilzeile), `stop_id`, `str_idx` (DHL-Straßenindex), `section_id`. Keine weiteren Spalten mit
Endung `_b2b`/`_b2c`. Tage 09.–17.05.2025 (Mo–Sa) sind in `configs/baseline-daily.json` enthalten.

## 6. Artefakte

`buildings.parquet` (Gebäude, Einwohner, Firmen, Adresse, Straße, Abschnitt, Seite, Status), `site_buildings.parquet` (Personen-Gebäudepunkt bzw. Firma → Gebäude, Stufe),
`street_decomposition.parquet` (DHL, DHL′, Korrektur, B2C/B2B, Status), `stops.parquet` (Stopp, Punkt, Gebäude, Erwartung je Segment),
`anchor_report.json` (Raten, q_DHL, Korrekturen, Zuordnungsquoten, Identitäten), dazu die bestehenden Referenz-, Tages- und MATSim-Artefakte.

## 7. Validierung und Tests

- **Unit-Tests** je Schritt mit kleinen Fixtures: Zuordnung Personen/Firmen, Namensnormalisierung, Pegelkorrektur, Zerlegung, Identitäten 5.8, Stoppbildung, Teilzeilen, Exportvertrag.
- **Echter Lauf (Pflicht, Abnahme):** Referenz 2021 und die acht Tage 09.–17.05.2025. Kein Teilprojekt gilt ohne diesen Lauf als fertig.
- **Kriterium A:** räumlicher 5-fach-Holdout nach PLZ für das Strukturmodell (M0 nur Einwohner, M1 Einwohner + Firmen) mit Straßen- und PLZ-wMAPE auf Gebäudebasis; Vergleich mit dem Punktergebnis vom 24.09. (M1: PLZ 12,9 %). M5 (Firmen je Branche) entfällt, weil es auf Punktbasis keinen Gewinn brachte.
- **Kriterium C:** Tabelle im Bericht mit Pakete je Person und Jahr je PLZ, B2B-Anteil je PLZ, Pakete je Stopp, Stopps je Tag, Anteil belieferter Gebäude und Firmen, Vergleich der Tagesmengen und PLZ-Verteilung mit den Notebook-Dateien.
- **Bilanzen:** Summe Gebäude = Summe Straßen + Rückfall; ganzzahlige Tagesmengen je Stopp = Tagesmengen je Gebäude.

## 8. Abgrenzung

Nicht Teil von A: neue Tagesstreuung (B), Unsicherheit und Monte Carlo (C), Task 9 (Mischung historisch/strukturell),
Hermes, Änderungen an Java/MATSim oder am BatchDelivery-Notebook, amtliche LGLN-Gebäude.

## 9. Umsetzungsregeln

Bestehende Datenverträge und Tests bleiben gültig. Reviews prüfen fachliche Richtigkeit und Tests; höchstens zwei Review-Runden je
Aufgabe; keine zusätzliche Infrastruktur-Härtung (Locks, Caches, Überlauf-Sonderfälle) ohne konkreten Fehler. Der bisher nicht
committete Stand (Kalender, Notebook-05-Grenzen, Amazon-Normierung, MATSim-Export, Tagesconfig) wird zuerst verifiziert und committet;
der Standard des Firmengewichts geht dabei auf „1 je Firma“ zurück.

## 10. Offene Punkte

- Ursache des DHL-Pegels in 30855: Rückfrage beim Datenlieferanten der DHL-Straßendaten.
- Werte für Laufradius, eigene Stopps und Branchenfaktoren sind begründete Annahmen und werden in C per Sensitivität geprüft.
