# Detailaudit der HAGRID-Demand-Notebooks

## 1. Umfang und Belegbasis

Geprüft wurden die neun Notebooks in jedem der Verzeichnisse `parcel-demand-estimation` und `parcel-demand-estimation-batch`: Stages 00–06, `ParcelDemandScenarioGenerator` und `BatchDeliveryStrategiesGenerator`. Die **acht Nachfrage-Notebooks pro Verzeichnis haben identischen Code**; nur die beiden BatchDeliveryStrategies-Varianten unterscheiden sich. Die Dateihashes unterscheiden zusätzlich Notebook-Metadaten und gespeicherte Outputs vom eigentlichen Code.

Die Analyse umfasst Quelltextprüfung der Nachfragekette, gezielte Funktionsausführung, Input-Schemata und aggregierte Profile vorhandener Zwischenprodukte. Das BatchDelivery-Notebook wurde hinsichtlich der Anbindung und wesentlicher Transferfunktionen geprüft, nicht als vollständiges Audit aller nachgelagerten Plots und Szenarien. MATSim-Routing und Ergebnisanalyse sind nicht Gegenstand dieses Audits.

Belege: [maschineller Prüfbericht][verification], [ausführbares Prüfskript][verify] und die unten verlinkten Quelltext-Snapshots. Die Snapshots bewahren `CELL`-Markierungen; Zellnummern zählen alle Notebook-Zellen ab 1. Sie sind **Lesefassungen, keine neue ausführbare Pipeline**.

## 2. Was die Kette heute tatsächlich macht

| Schritt | Inputs und Verfahren | Output / Rolle | Bewertung |
|---|---|---|---|
| [00 Marktanteile][n00] | Eingeschriebene Anbieterwerte, Fortschreibung und Amazon-Sigmoid aus drei Punkten | `00_markedshare_with_amazon.csv`; separate Unsicherheitsvariante | Stark annahmengetrieben; Unsicherheitsvariante wird in 05 nicht verwendet |
| [01 nationale B2B-Anteile][n01] | 13 Werte 2009–2023; exponentiell, sigmoid, Sigmoid mit Untergrenze 20 % | `01_b2b_forecast_complete.csv`, Jahre 2009–2050 | Die langfristige Untergrenze ist vorgegeben, nicht geschätzt |
| [02 nationales Volumen][n02] | Eingeschriebene Reihe 2000–2028, einschließlich Zukunftsschätzungen; drei Kurvenmodelle | Jahreswerte und Modellkurven | Beobachtungen und Prognosen vermischt; CI-Fehler |
| [03 Wochenprofil][n03] | Schweizer Wochenreihe 2019–2021, feste Glättungs-/Weihnachtsparameter, stochastische Pfade | Wochenfaktoren pro Modell und Jahr | Kalenderfehler und fehlende Mengennormierung |
| [04 lokales B2B][n04] | Fertiges 250-m-Raster mit `b2b_ratio`, `total_coun`, `fit`; GA je Jahr | Angepasste B2B-Zellanteile | Vorgelagerte Schätzung wird übernommen; frischer Lauf gebrochen |
| [05 lokale Marktanteile][n05] | Nationale Marktanteile, lokale B2B-Ziele, angenommene B2B-Bandbreiten je Anbieter | Zell-Anbieteranteile und jährliche Anbieter-B2B-Anteile | Anbieterstruktur wird aus B2B-Annahmen konstruiert; Fitness/Export weichen ab |
| [06 Segmentgewichte][n06] | DHL-Straßen, Personen und Firmen, 50-m-Teilsegmente mit 250-m-breiten Puffern | 112.478 gewichtete Sample-Punkte | Geometrische Mehrfachzählung, leere Treffer, DHL-Boost |
| [ScenarioGenerator][ngen] | DHL-Basismenge / lokaler DHL-Marktanteil; relative Zeitfaktoren; PLZ→Zellen→Samples | Tagesnachfrage nach Anbieter und B2B/B2C, GIS-/MATSim-nahe Exporte | Viele nachträgliche Korrekturen; Mengen-/Kalender-/Reproduzierbarkeitsprobleme |
| [BatchDeliveryStrategies][nbatch] | Bereits erzeugte Nachfrage, Preispräferenzen, Anbieter-Liefertage | Verschiebung/Bündelung der Zustellung | Logistikpolitik, fachlich nach der Nachfrageerzeugung anzusiedeln |

Die nationale Jahresmenge wird im Generator **als relativer Wachstumsfaktor** genutzt. Das lokale absolute Niveau entsteht aus DHL-Beobachtungen und angenommenem lokalem DHL-Anteil. Das ist keine direkte nationale Mengenallokation auf Hannover.

## 3. Was die vorhandenen Daten ermöglichen

| Quelle | Geprüfter Bestand | Bedeutung für den Neubau |
|---|---|---|
| Firmen-Shapefile | 52.931 Zeilen und eindeutige IDs, 19 Branchenwerte, 650.267 Beschäftigte; keine fehlenden/negativen/null Beschäftigtenwerte im eingelesenen Feld | Branchen- und größenspezifische B2B-Intensitäten sind möglich. Bedeutung und Jahr der Branchenklassifikation müssen dokumentiert werden; Vollständigkeit ist noch kein Realitätsnachweis |
| Personen-CSV | 1.150.862 Zeilen und eindeutige Personen-IDs; 227.641 Gebäudekennungen; 280.733 bekannte Haushaltskennungen | Gebäudeebene ist verfügbar. 536.496 fehlende Haushaltskennungen, also 46,62 %, erfordern einen Ersatzansatz. Bekannte Haushalte sind nicht die Gesamtzahl der Haushalte |
| DHL-Straßendaten 2021 | DBF: 12.342 Geometrie-Datensätze; Feld `tagesschni`, PLZ und Name | Räumliche Kalibrierungsbeobachtung eines Anbieters; genaue Bezugsperiode des Tagesmittels klären |
| Hermes-CSV | PLZ-Werte für 2019–2021 | Zweite Anbieterinformation; Einheit und Beobachtungsfenster müssen vor Nutzung geklärt werden |
| Altes Raster | 15.917 eindeutige Zellen; fertige Mengen, B2B-Ratio, Landnutzung, Regressions-/Fit-Felder | Legacy-Referenz und ggf. schwacher Prior; nicht als unabhängige B2B-Wahrheit verwenden |
| OSM-Landnutzung / PLZ | Flächeninformationen und Zuordnungsgebiete | Kontextmerkmale, Plausibilitätsprüfung und Aggregation |
| Schweizer Wochenreihe | Excel-Datei 2019–2021 vorhanden | Saisonaler Proxy; keine deutsche regionale Tagesmessung |
| MATSim-Netz | XML, etwa 1,24 GB | Späteres Netz-Mapping; nicht pro Simulationstag neu einlesen |

**Abdeckungsbefund:** 8.531 Rasterzellen haben `total_coun == 0`. Davon enthalten 2.625 Einwohner oder Firmen. Die alten Rasteraggregate summieren dort 35.256 Personen und 7.339 Firmen. Diese Zahlen stammen aus dem Raster und sind nicht als erneute Verifikation der individuellen Geodaten zu verstehen. Der Filter auf positive Altmengen entfernt diese Gebiete aus der Kernkette.

**Übergang zu den Samples:** In beiden vorhandenen Output-Verzeichnissen stehen 7.386 Zellen und 112.478 Samples. Unter derselben EPSG:25832-Zuordnung wie im Generator bleiben 13.277 Samples ohne Zelle. 226 positive Zellen haben keinen Sample-Punkt; sie tragen 1.537 von 196.885 Einheiten des alten Zellgewichts, ungefähr 0,78 %. Das ist ein Abdeckungsrisiko für die finale Allokation, **keine gemessene endgültige Verlustquote aller Tagespakete**. Bei dieser konkreten Zuordnung wurden keine zusätzlichen Join-Zeilen durch Mehrfachtreffer gemessen.

## 4. Priorisierte Fehler und Unstimmigkeiten

Kennzeichnung: **R** = reproduziert durch isolierte Prüfung oder vorhandene Daten; **S** = direkt im Code nachgewiesen, End-to-End-Auswirkung nicht gemessen; **M** = Modellannahme bzw. Validitätsproblem. P1 bezeichnet Blocker oder erhebliche Ergebnisrisiken, P2 begrenztere bzw. bedingte Fehler.

### F01 · P1 · R — Zwei unabhängige Blocker in Notebook 04

In [04, Zelle 5][n04] wird `ga_corrected_b2b_gdf = final_grid_h.copy()` verwendet. `b2b_ratio_norm` wurde zuvor ausschließlich auf `final_grid_h_filter` angelegt. Der tatsächliche Input enthält diese Spalte nicht. Der Zugriff auf `original_ratios` scheitert bei einem frischen Lauf.

Später erwartet dieselbe Zelle `Jahr`, `Ist_B2B`, `Typ` und das Label `Prognose`. [01, Zelle 5/7][n01] exportiert `Year`, `Actual_B2B`, `Type` und `Forecast`; genau dieses Schema liegt auf der Platte. Selbst nach Behebung des ersten Fehlers ist der Vertrag gebrochen. Vorhandene 04-Outputs beweisen nicht, dass sie aus dem aktuellen Code entstanden sind.

**Beheben:** Explizite typisierte Schemas, Prüfung unmittelbar nach dem Einlesen und ein sauberer Run ohne bestehende Zwischenprodukte.

### F02 · P1 · R — Leere räumliche Joins zählen als Person/Firma

[06, Zelle 6, `assign_b2c_b2b_weights_with_total`][n06]: `sjoin(..., how="left")` erhält auch ohne Treffer eine Zeile. `groupby(index).size()` zählt diese Zeile als Treffer. Eine Fläche ohne Person und Firma erhält `person_sum=1`, `company_count=1`; der anschließende positive-Gewichte-Filter greift dadurch nicht wie beabsichtigt.

**Beheben:** Treffer über nichtleere rechte IDs zählen. Für den Neubau vorzugsweise jede Nachfrageeinheit eindeutig einem Zustellort zuordnen.

### F03 · P1 · R/M — Puffer überzählen und DHL-Boost hängt an Segmentzahl

[06, Zellen 5–8][n06]: 50-m-Teilsegmente erhalten 250-m-breite Puffer. An benachbarten Straßen und Kurven können sich diese überschneiden. Im Gegenbeispiel wird dieselbe Person in zwei Puffern zweimal gezählt. Dies ist keine automatisch massenerhaltende räumliche Gewichtung.

Der Straßenwert `dhl_weight` wird auf jedes Teilsegment kopiert. Algebraisch vereinfacht sich der Boost bei positiven Ausgangsgewichten zu `base_weight + dhl_weight/2` je Teilsegment. Damit entsteht für eine Straße mit n Segmenten ein Gesamtzuschlag von `n * dhl_weight/2`. `min_tag=300` wird zwar übergeben, aber nie geprüft. Im Test steigen vier Gewichte von insgesamt 4 auf 24, obwohl alle DHL-Werte nur 10 betragen.

**Beheben:** Eindeutige oder anteilige Zuordnung mit je Person/Firma aufsummiertem Gewicht 1; Straßenbeobachtungen einmalig auf ihre Teilsegmente verteilen und als Beobachtungen kalibrieren.

### F04 · P1 · R/M — Nachfragefreie Altzellen bleiben dauerhaft ausgeschlossen

[04, Zelle 5][n04] und [05, `prepare_data`][n05] filtern `total_coun > 0`. Damit kann eine fehlende Beobachtung in eine dauerhafte strukturelle Null verwandelt werden. Die 2.625 betroffenen bewohnten/betrieblich genutzten Zellen sind datenbasiert bestätigt.

**Beheben:** Räumlichen Support aus allen Einwohner-/Betriebsstandorten aufbauen. Nullbeobachtung, fehlende Beobachtung und tatsächlich unmögliche Nachfrage unterscheiden.

### F05 · P1 · R — ISO-Kalender ist um eine Woche verschoben

[03, Zelle 9][n03] erzeugt Montage mit `date_range(start=f"{year}-01-04", freq="W-MON")`. Das ist häufig der Montag der zweiten ISO-Woche. Für 2025 beginnt die Datei am **6. Januar**, während ISO-Woche 1 am **30. Dezember 2024** beginnt. Die letzte Zeile unter `Year=2025` ist der **29. Dezember 2025**, also bereits ISO-Woche 1 von 2026.

[Generator, Zelle 4][ngen] filtert Kalenderjahr und ISO-Wochennummer ohne konsistentes ISO-Jahr. Eine Anfrage für Woche 1 kann dadurch das Jahresendprofil verwenden; an anderen Jahresgrenzen fehlen Zeilen. Die bloße Prüfung, ob irgendwo Wochennummer 1 vorkommt, übersieht diesen Fehler.

Die gespeicherte Wochenreihe bestätigt **44 Zeilen mit falschem ISO-Jahr und 44 Jahresgruppen ohne ihre eigene ISO-Woche 1**. Der Fehler betrifft damit bereits vorhandene Zwischenprodukte und nicht nur ein konstruiertes Datum.

**Beheben:** Tageskalender als Master, `date.fromisocalendar(iso_year, week, 1)` für ISO-Aggregate; Kalender- und ISO-Jahr getrennt führen.

### F06 · P1 · R — Tagesanteile summieren sich auf 96,5 %

[Generator, `get_relative_package_change_per_day`, Zelle 4][ngen]: `.16 + .17 + .19 + .18 + .15 + .115 = .965`. Die Faktoren werden gegen 1/6 skaliert und nicht normalisiert. Über eine vollständige Sechstagewoche entsteht gegenüber dem angenommenen Durchschnitt ein Minderfaktor von 3,5 %. Sonntage lösen im Default einen Fehler aus; Feiertage werden nicht separat modelliert.

**Beheben:** Kalendergewichte über den tatsächlichen Zeitraum normieren und Nachfrageentstehung von Zustellkalendern trennen.

### F07 · P1 · R/S — Rundung verletzt Mengenbilanzen

[Generator, Zelle 6, `distribute_to_carriers`][ngen] rundet jeden Anbieter unabhängig. Ein Paket mit Anteilen 50/50 ergibt zweimal `round(.5)=0`. Die vorherige summenerhaltende Zellrundung schützt den nächsten Schritt nicht.

Die deterministische Sample-Variante rundet ebenfalls unabhängig. Sie ist im aktuellen Hauptlauf nicht ausgewählt. Die ausgewählte Multinomial-Variante erhält die Menge innerhalb einer repräsentierten Zelle, löst jedoch nicht das Problem von Zellen ohne Samples.

**Beheben:** Erwartungswerte zunächst als Float; Ganzzahligkeit einmalig mit Resteverteilung oder Multinomial-Allokation herstellen. Jede verbindliche Aggregation prüfen.

### F08 · P1 · R/S — B2B-Umschichtung verändert B2B-Gesamtsumme

[Generator, Zelle 7, `pre_adjust_b2b_distribution`][ngen] rundet Geber und Empfänger separat ab. Im Test geben zwei Geber mit je 0,6 Überschuss jeweils 0 ab, während ein Empfänger mit Bedarf 1,2 ein Paket erhält. Die B2B-Summe steigt von 2 auf 3; B2C sinkt entsprechend. Die Gesamtpaketzahl bleibt hierbei erhalten, **der B2B-Randwert nicht**.

Zusätzlich skaliert `apply_scaled_b2b_split` Anbieteranteile mit Alpha ohne Sicherung gegen Werte >1. Ein Anteil 1,08 produziert negative B2C-Mengen. Ob dieser Bereich im konkreten gespeicherten Lauf erreicht wird, wurde nicht nachgewiesen.

**Beheben:** Gemeinsame zulässige Allokation mit ganzzahliger Bilanz statt unabhängiger Reparaturen.

### F09 · P1 · R/S — B2B-Füllschritt fügt neue Pakete hinzu

[Generator, Zelle 7, `final_adjust_b2b_distribution`][ngen] erzeugt für leere Zellen B2B-Mengen aus `total_coun * Zeitfaktor * B2B-Ziel`. Es gibt keine Gegenbuchung zur vorher festgelegten Gesamtmenge. Im Gegenbeispiel steigen 0 Pakete auf 5. Ob und wie häufig die Bedingung im vollständigen Lauf greift, ist offen. `sum_total` wird über mehrere Korrekturen als abgeleitetes Feld geführt; die Aktualität der Maske ist nicht als Invariante abgesichert.

**Beheben:** Fehlende räumliche Allokation im ursprünglichen Verteilungsschritt lösen, keine zusätzliche Nachfrage beim Reparieren erzeugen.

### F10 · P1 · S — Optimiert und exportiert werden verschiedene Modelle

[05, Zellen 5–7][n05]: `fast_vectorized_estimation` verwendet `delta * direction * strength`, wobei `strength=clip(abs(delta), .05, 1)`. `run_ga` berechnet später die exportierte Matrix erneut als `delta * direction`, also **ohne strength**. Der Fitnesswert gilt damit nicht für den exportierten räumlichen Zustand.

Die Fitness setzt außerdem `market_error = mean(b2b_error)` und zählt den Zellfehler ein zweites Mal. `global_error ** global_weight` mit Gewicht 2 quadriert den Fehler, statt ihn mit 2 zu gewichten. Ein kleiner Marktanteilsfehler wird dadurch stark abgeschwächt. Bei der Fitness werden Anbieteranteile an `realistic_bounds` geclippt; beim abschließenden Export nur an 0–1 plus Mindestwert .01. Auch diese Ergebnisse müssen nicht dieselben sein.

**Beheben:** Eine einzige Modellfunktion für Fit, Diagnose und Export; unterschiedliche Residuen explizit benennen und gewichten; gemeinsame Bounds.

### F11 · P1 · R/M — Zukunftsschätzungen werden als Messwerte trainiert/exportiert

[02, Zellen 2–3][n02]: Die Werte 2024–2028 sind im Code als Schätzungen beschrieben. Sie bleiben im Training und erscheinen im Output als `Observed`; ihre Bänder fallen mit dem Wert zusammen. Die Datei bestätigt diese Kennzeichnung für alle fünf Jahre. Für einen heutigen Lauf wird dadurch insbesondere 2027/2028 eine Beobachtung vorgetäuscht.

Ein für den Ausschluss von 2021 angelegter DataFrame wird in den gezeigten Fits nicht verwendet. Die Modelle arbeiten zudem auf unterschiedlichen Zeitfenstern; ihre Trainingsmetriken und AIC-Werte sind kein fairer Prognosevergleich über denselben Testdatensatz.

**Beheben:** `observed`, `imputed`, `external_forecast`, `scenario` und Veröffentlichungsstand getrennt speichern; externe Prognosen als Annahmen behandeln, Backtests nur mit damals verfügbarer Information.

### F12 · P1 · R — Lineare Konfidenzintervalle beruhen auf der falschen Streuung

[02, Zelle 3, `compute_linear_ci`][n02] verwendet die Streuung der Vorhersagen um ihren Mittelwert anstelle der Trainingsresiduen. Stichprobengröße und Hebelwirkung werden aus den übergebenen Prognosepunkten berechnet. Selbst bei einer exakt linearen Trainingsreihe ohne Residuen ergibt die isolierte Prüfung am sechsten Punkt eine Intervall-Halbbreite von **4,753949** statt 0 für das geschätzte Regressionsmittel.

**Beheben:** Unsicherheit aus Trainingsresiduen und Trainingsdesign berechnen. Unsicherheit des Mittelwerts und Vorhersageintervall einer zukünftigen Realisierung getrennt ausgeben.

### F13 · P1/P2 · S — Solvererfolg und Zeilenbestand nicht abgesichert

[Generator, Zelle 7][ngen]: `optimize_b2b_allocation` filtert pro Anbieter alle Zeilen mit Gesamtmenge 0 aus und gibt die reduzierte Tabelle zurück. `smart_reallocation` reicht diese Tabelle an den nächsten Anbieter weiter. Eine Zelle ohne Anbieter A, aber mit B, fällt deshalb aus der weiteren Optimierung. Beim späteren `DataFrame.update` kann die ursprüngliche Zeile erhalten bleiben; es handelt sich deshalb nicht automatisch um einen vollständigen Output-Zeilenverlust, sondern um unvollständige, reihenfolgeabhängige Optimierung.

`problem.solve` ohne Exception wird als Erfolg behandelt, ohne `problem.status` und `x.value` zu prüfen. Anschließendes unabhängiges Runden kann die kontinuierlich erzwungene B2B-Summe wieder verändern.

**Beheben:** Gesamten Index erhalten, nur Variablenmasken optimieren, Solverstatus/Toleranzen prüfen und Ganzzahligkeit bilanztreu herstellen.

### F14 · P2 · R/M — Saisonalisierung hält das Jahresniveau nicht exakt

[03, Zelle 9][n03] skaliert einen gezogenen Wochenpfad mit dem Jahreswert, ohne den Pfad anschließend auf den gewünschten Jahresmittelwert zu normieren. In den vorhandenen Dateien liegt `Mittel(weekly.linear_Prognose) / annual.linear` über die Jahre zwischen **0,9596 und 1,0243**. Die Reihe trägt Jahresniveau-Einheiten mit wöchentlicher Variation; sie ist nicht direkt eine Wochenpaketmenge. Im Generator werden Quotienten verwendet, wodurch sich die Einheit kürzt, aber Referenz- und Zieljahr bleiben vom jeweiligen Zufallspfad abhängig.

Die vermeintlichen Wochen-CIs sind zunächst Mittelwert ±1,96 Standardabweichungen aus drei Jahren. Später werden Intervallgrenzen miteinander multipliziert. Das ist kein allgemein gültiges 95-%-Intervall des Produkts. Ein gezogener Pfad ist auch kein Monte-Carlo-Mittel.

**Beheben:** Viele kohärente Pfade erzeugen, jährlich oder täglich nach gewähltem Bilanzmodell normieren und Quantile aus dem Ensemble berechnen.

### F15 · P2 · S — Seed 42 garantiert keine Reproduzierbarkeit

[04][n04], [05][n05] und [Generator][ngen] verwenden `default_rng(42)`, globale `np.random`-Aufrufe und DEAP-Operatoren mit eigenem Zufallszustand nebeneinander. Ein Generator-Seed kontrolliert die anderen Ströme nicht. Ergebnisse hängen zusätzlich an Gruppen-/Anbieterreihenfolge und zuvor ausgeführten Zellen. Das Batch-Notebook setzt dagegen einen globalen Seed im Simulationsstart; diese partielle Sicherung behebt die vorgelagerte Kette nicht.

**Beheben:** Stabile Zufallsströme pro Ensemble, Stage, Datum und Entität; identische Anfragen unabhängig von Run-Aufteilung und Cachezustand.

### F16 · P2 · S/M — GA-Grenzen und B2B-Vorgaben sind keine empirische Identifikation

[04, Zelle 5][n04]: Mutationen und Blend-Crossover sind nicht an die als Suchraum beschriebenen Alpha-Grenzen gebunden. `mutGaussian(mu=1.0)` verschiebt mutierte Gene im Erwartungswert positiv. Nachträgliches Clipping der Ratios macht diese Alpha-Grenzen nicht verbindlich. Null-Ratios bleiben bei multiplikativer Anpassung immer null.

Tausende Zellfaktoren werden an einen nationalen Randwert und qualitative `fit`-Labels angepasst. Damit lässt sich kein eindeutiges lokales B2B-Muster aus den Daten ableiten. Die Annahme, Hannover müsse denselben B2B-Anteil wie Deutschland haben, benötigt eine eigene Begründung. In [05][n05] werden Anbieter-B2B-Bounds dynamisiert, aber die Standardjahresgrenze 2025 friert diese Dynamisierung danach ein; die tatsächlich geschätzten Anteile können trotzdem weiter variieren.

**Beheben:** Branchen-/Standortintensitäten als Primärmodell; nationale Anteile als unsichere Randinformation. Für eine reine Übergangskalibrierung genügt häufig ein kontrollierter Logit-Intercept statt eines GA mit einem Parameter je Zelle.

### F17 · P2 · S/M — Anbieteranteile, Marktabgrenzung und lokales Niveau hängen zusammen

[00, Zelle 6][n00]: Wird zu bereits auf 100 % normierten Anbietern ein als Gesamtmarktanteil verstandener Amazon-Wert von 20 hinzugefügt und alles erneut normiert, werden daraus 16,67 %. Entweder muss Amazon als Gesamtmarktanteil fixiert und der Rest auf 80 % verteilt werden, oder die ursprüngliche Definition muss anders lauten. Die Quelle/Grundgesamtheit entscheidet; die aktuelle Beschreibung passt nicht zur Arithmetik.

[Generator, Zelle 6][ngen] berechnet `total_tag = dhl_2021 * Wachstum / DHL_share_Zieljahr`. Sinkt der angenommene DHL-Anteil von 50 % auf 40 %, steigt das geschätzte Gesamtvolumen allein dadurch um 25 %, zusätzlich zum allgemeinen Wachstumsfaktor. Das ist eine implizite Annahme, dass DHL selbst proportional zum Gesamtmarkt wächst und gleichzeitig Marktanteil verliert.

**Beheben:** Regionales Gesamtvolumen zuerst mit Basisjahr-Anteilen kalibrieren und unabhängig fortschreiben; aktuelle Anbieteranteile erst danach auf die Gesamtmenge anwenden. KEP/Paket, Inland/Ausland, Eigenzustellung und Sendungsrichtung einheitlich definieren.

### F18 · P2 · S/M — Kalibrierungsdaten werden zugleich als Gütenachweis verwendet

Der Generator bewertet die Straßenverteilung gegen DHL 2021. Dieselben Informationen bestimmen bereits das lokale Niveau, das alte Raster und den Segment-Boost. Ein guter RMSE belegt dann vor allem die Rekonstruktion des Kalibrierungsinputs. Der voreingestellte Hauptlauf simuliert Mai 2025 und vergleicht dennoch mit 2021.

**Beheben:** Räumlich getrennte Testbereiche ohne Nutzung ihrer Zielmengen beim Fit; zeitliche Backtests soweit Beobachtungen vorliegen; gesonderte Baseline-Rekonstruktion für 2021. Keine Aussage zur 2030-Prognosegüte allein aus diesem RMSE ableiten.

### F19 · P2 · S — Implizite Umgebung und verlustreiche Datenschnittstellen

Die Root-Requirements enthalten weder das vom Generator importierte `cvxpy` noch `openpyxl` für Excel. `cvxpy` fehlt in der für dieses Audit verwendeten Python-Umgebung. Eine komplette Neuinstallation der alten Requirements wurde nicht getestet.

Die isolierten Prüfungen liefen mit Python 3.13.5, Pandas 2.3.3, NumPy 2.3.3 und GeoPandas 1.1.1. Diese Versionen weichen von den Root-Pins ab; der Nachweis ist deshalb kein Installationstest genau dieser alten Umgebung. Datenverträge, Kalenderarithmetik und die beschriebenen Bilanzgleichungen sind davon unabhängig nachvollziehbar.

Relative `input/`-/`output/`-Pfade setzen das jeweilige Arbeitsverzeichnis voraus. Die 90-%-Heuristik zur Zahlenerkennung kann Spalten abhängig von ihren Fehlwerten unterschiedlich typisieren. Geometrien und verschachtelte Anbieter-Dictionaries werden als CSV-Strings weitergereicht, CRS teilweise nur gesetzt. [06, Zelle 4][n06] etikettiert Sample-Koordinaten als EPSG:32632, obwohl die Straßen zuvor nach EPSG:25832 projiziert wurden; diese ältere Sample-Variante ist nicht die zentrale Buffer-Variante.

**Beheben:** Geprüfte Adapter, PLZ als String, EPSG:25832 explizit, Long-Format für Jahre/Anbieter, GeoParquet als interne Geometrieschnittstelle, reproduzierbare Paketumgebung.

### F20 · P2 · S/M — Batch-Zustellung braucht ein beständiges Mengenbuch

[Batch, Zelle 7, `remove_at_source_date_and_add_at_current_date`][nbatch] zieht verfügbare Kapazitäten aus `original_df`, subtrahiert aber von `current_df`. Bei mehrfachen Transfers aus derselben Quelle kann ein bereits belegter Bestand erneut ausgewählt werden und negative Mengen auslösen. Quellen außerhalb der Simulationswoche werden aus frischen Kopien erstellt und nicht dauerhaft zurückgeschrieben. Die aktuelle Reichweite dieses Risikos hängt an der Transferliste.

Neue Zielzeilen für B2B und B2C werden separat vorbereitet, bevor der Lookup erweitert wird; identische neue Geometrien können so doppelte Zeilen erhalten. Geometrie-WKT als Schlüssel ist außerdem keine stabile fachliche Empfänger-ID. Die Preispräferenzkurven sind ausdrücklich Szenarioannahmen, kein kalibriertes Nachfragemodell.

**Beheben:** Nachfrage-ID/Empfänger und Mengenbestand durchgängig erhalten; Verschiebungen über eine Queue mit Carry-in/Carry-out buchen. Preis- und Liefertagpolitik als separaten Schritt nach Nachfrageentstehung behandeln.

## 5. Fachliche Konsequenz

Die bestehende Kette besitzt sinnvolle Bausteine: nationale Mengen als Orientierung, lokale Beobachtungen, Person-/Firmendaten, Saisonalität und einen teilweisen Einsatz summenerhaltender Allokation. Das zentrale Problem ist ihre Kopplung. B2B-Zellen und Anbieterprofile werden wechselseitig aufeinander angepasst; danach folgen weitere Reparaturen. Ein kleiner Fehler gegen selbst erzeugte Zielwerte ist dabei kein Nachweis genauerer Nachfrage.

Der 250-m-Raster ist nicht grundsätzlich unzulässig. Problematisch ist seine Rolle als unveränderlicher Nachfrage-Support und vermeintlich exakte B2B-Wahrheit. Ein bloßer Wechsel zu 100-m-Zellen oder Hexagonen würde die Datenabhängigkeit und die Kalibrierungsprobleme beibehalten.

Die vorhandenen Quellen reichen für ein besser strukturiertes, transparent kalibriertes Szenariomodell. Sie reichen **nicht**, um genaue tägliche B2B-Mengen je Betrieb für 2026 unabhängig nachzuweisen. Die Identifikation der B2B-/B2C-Zerlegung bleibt ohne zusätzliche Beobachtung schwach; dieser Unsicherheitsanteil muss im Ergebnis sichtbar bleiben.

## 6. Externe methodische Einordnung

Für zeitliche Modellvergleiche müssen Testwerte zeitlich hinter dem Trainingsstand liegen; mehrere Prognosehorizonte sollten separat ausgewertet werden. Diese Regel wird im Neubau durch rollierende Backtests umgesetzt. [Hyndman & Athanasopoulos: Time series cross-validation](https://otexts.com/fpp3/tscv.html).

Räumliche Aggregationen sollen sich zu denselben Gesamtmengen addieren. Auch simulierte Prognosepfade können konsistent abgestimmt werden; Unsicherheitsintervalle werden danach aus diesen Pfaden gewonnen. Das begründet die vorgeschlagene Bilanzprüfung und Ensembleausgabe, legt aber noch keinen speziellen Optimierer fest. [Forecast reconciliation](https://otexts.com/fpp3/reconciliation.html), [Reconciled distributional forecasts](https://otexts.com/fpp3/rec-prob.html).

Bei der Aktualisierung nationaler Inputs ist der Datenstatus entscheidend: Die aufgerufene Bundesnetzagentur-Seite kennzeichnet ihre Werte für 2025 ausdrücklich als Anbieterprognosen und unterscheidet Inland, Ausland und Gesamt. Aus einer jüngeren Jahreszahl darf also nicht automatisch eine Beobachtung werden. [Bundesnetzagentur: Paket-Sendungsmengen](https://www.bundesnetzagentur.de/DE/Fachthemen/Datenportal/3_Post/_svg_Post/Paket/P_Paketmarkt_Mengen/P_Paketmarkt_Mengen.html). Neue Marktzahlen wurden in diesem Audit nicht in die alten Inputs eingespielt.

[verification]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/verification.json
[verify]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/verify_findings.py
[n00]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__00_EstimateGlobalGermanParcelMarketShares.py
[n01]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__01_EstimateGlobalGermanB2BShares.py
[n02]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__02_EstimateGlobalGermanParcelVolumens.py
[n03]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__03_EstimateWeekyParcelDistribution.py
[n04]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__04_EstimateLocalB2BDistribution.py
[n05]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__05_EstimateLocalMarketShares.py
[n06]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__06_DistributeEstimationWeightsPerSegment.py
[ngen]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation__ParcelDemandScenarioGenerator.py
[nbatch]: C:/Users/bienzeisler/Documents/GitHub/HAGRID/docs/demand-audit/notebook-sources/parcel-demand-estimation-batch__BatchDeliveryStrategiesGenerator.py
