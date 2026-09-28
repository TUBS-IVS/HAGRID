# Gemeinsames Modell: ausführbare Version

Stand: 9. September 2026. Die neue Version verbindet Kalibrierung, Anbieteraufteilung, Kalender, räumlich-zeitliche Schwankungen, Zukunftsfortschreibung, Lieferpunkte und Exporte. **Die Kalibrierung ist vorläufig und ausdrücklich an Annahmen gebunden.** Technische Vollständigkeit eines Laufs ist keine Bestätigung der heutigen realen Nachfrage.

## Start

Nach Installation des Pakets aus dem Repository-Stamm:

```powershell
python -m hagrid_demand run --config hagrid-demand/configs/model.json
```

Der Befehl verwendet den vorhandenen Foundation-Run aus der Konfiguration. Ein neuer Quellenstand wird zuvor mit `foundation` aufbereitet; Rohdaten werden nicht verändert. Die Beispieleingänge sind auf die vorhandenen lokalen Dateien eingestellt. Jeder Lauf schreibt in ein neues Run-Verzeichnis und überschreibt keine früheren Runs.

Eine eingefrorene Modellversion ohne erneutes Training anwenden:

```powershell
python -m hagrid_demand predict --config hagrid-demand/configs/model.json --model-run hagrid-demand/runs/joint-demand-20260909-v2
```

Datum, Realisierungen, Zukunfts-/Kalenderannahmen und Zustellmapping können angepasst werden. Standort-Snapshot und Referenzjahr müssen zum eingefrorenen Modell passen. Ein Transfer auf neue Regionen oder neue Standort-IDs benötigt eine eigene Datenaufbereitung und ist mit diesem Frozen-Adapter noch nicht freigegeben.

## 1. Quellen und Beobachtungsebene

Standorte entstehen aus vorhandenen Personen-/Gebäude-IDs und Firmenpunkten. Die Kalibrierung nutzt PLZ-Summen der DHL-Zeilen und Hermes-PLZ-Werte des Referenzjahrs. Dadurch werden ungeklärte nächste Straßen nicht als bestätigte Gebäudezuordnungen benutzt.

Die Additivität der DHL-Zeilen ist eine explizite, aus dem bestehenden PANDA-Ablauf übernommene Arbeitsannahme. Wiederholte Straßenkennungen bleiben als Datenproblem dokumentiert. Hermes wird wegen ungeklärter absoluter Einheit ausschließlich für die räumliche Form innerhalb der jeweiligen Trainingsmenge verwendet. Kein Hermes-Testwert wird zur Trainingsnormierung verwendet.

Die existierenden HAGRID-CSV-Reihen liefern nationale Marktanteile, Anbieter-B2B-Profile, Gesamt-B2B und Volumenpfade. Sie sind **übernommene Priors/Projektionen**, keine zusätzlichen unabhängigen Messungen. Eingeschriebene Zukunftswerte werden weder als Beobachtungen trainiert noch ihre alten Konfidenzbänder übernommen.

## 2. Gemeinsame Schätzung

Private Nachfrage wird zunächst aus Bevölkerung erklärt. Gewerbliche Nachfrage verwendet Beschäftigte und optional unterschiedliche Intensitäten für die vorhandenen Branchencodes. Intensitäten sind positiv parametrisiert. Die Anbieteranteile je Segment summieren sich zu 1.

Aus globalem Marktanteil m und globaler eigener B2B-Quote q werden zunächst konsistente Segmentpriors erzeugt: B2B proportional m*q, B2C proportional m*(1-q), jeweils normiert. Diese Ausgangswerte können von der nationalen B2B-Zielquote abweichen; die gemeinsamen weichen Bedingungen stimmen sie ab, statt eine unvereinbare harte Vorgabe zu erzwingen.

Die Vorhersage je PLZ und Anbieter lautet:

`private Menge * privater Anbieteranteil + gewerbliche Menge * gewerblicher Anbieteranteil`.

Das gemeinsame Kriterium berücksichtigt DHL-Abweichungen, Hermes-Form, Marktanteile, B2B-Gesamtanteil und Abweichungen von Ausgangsprofilen. Regularisierung begrenzt unnötig große Branchen-/Anbieteränderungen. Kein frei geschätzter Anteil je Standort wird vorgetäuscht. Die Gewichtungen des Kriteriums stehen in der Konfiguration und sind noch keine empirisch optimierten Unsicherheiten.

## 3. Modellvergleich und Einfrieren

Verglichen werden:

1. `pooled`: eine private und eine gewerbliche Intensität, feste Segment-Anbieterpriors;
2. `branches`: zusätzliche gewerbliche Branchenintensitäten;
3. `joint`: Branchenintensitäten und gemeinsam angepasste Anbieterprofile.

Alle Kandidaten nutzen identische, seedabhängig festgelegte Trainings- und Validierungs-PLZ. Die Auswahl richtet sich nach DHL-wMAPE auf der Validierung. Anschließend wird die gewählte Modellfamilie auf Training plus Validierung angepasst und einmal auf den getrennten Test-PLZ bewertet. Danach erfolgt ein Fit auf allen Beobachtungen für die eingefrorene Anwendungsversion.

Die Testkennzahlen gehören deshalb zum gespeicherten Evaluationsmodell, nicht zum anschließend auf allen Daten geschätzten Anwendungsmodell. Ein Test prüft explizit, dass geänderte zurückgehaltene Zielwerte die Trainingsparameter nicht beeinflussen.

Die aktuelle Implementierung verwendet einen disjunkten Split und keine verschachtelte Kreuzvalidierung. Die ursprüngliche PANDA-Fünfparameter-Referenz benötigt fehlende OSM-/Zensus-Dateien; die implementierten Varianten sind HAGRID-Merkmalsmodelle und werden nicht als exakte PANDA-Reproduktion ausgegeben. Alte HAGRID-Outputs bleiben Vergleichsmaterial, keine neuen Testmessungen.

## 4. Kalender und Zukunft

Das kalibrierte mittlere DHL-Betriebstagsniveau wird über die angenommene Anzahl der Referenz-Betriebstage in Jahresmengen übersetzt. Die Beispielkonfiguration setzt 313 Tage; diese Umrechnung ist vor einer belastbaren absoluten Jahresprognose zu bestätigen.

Für jedes Zieljahr werden die jährlichen Segmentmengen mit dem gewählten relativen HAGRID-Volumenpfad fortgeschrieben. Anbieterprofile verändern sich anhand der relativen Entwicklung der übernommenen Segmentpriors. Modellierte Abweichungen am Referenzjahr bleiben dabei erhalten. Dies ist eine nachvollziehbare Fortschreibung, keine neu geschätzte langfristige Prognose aus zukünftigen Messungen.

Wochentags- und Monatsgewichte werden auf den tatsächlichen Kalender normiert. Die Summe aller Tagesfaktoren je Jahr ist exakt 1, einschließlich Schaltjahr. Explizite Feiertage können über `holiday_dates` und `holiday_factor` berücksichtigt werden. Die leere Beispielliste bedeutet, dass bislang kein amtlicher Feiertagskalender hinterlegt ist.

Optionales `stock_updates`-CSV: `site_id,year,multiplier`. Es verändert die räumliche Verteilung an bestehenden Standorten, während die jährlichen Segmentgesamtmengen erhalten bleiben. Unbekannte Standort-IDs, doppelte Schlüssel und negative Faktoren werden zurückgewiesen. Neue Gebäude oder Firmen werden nicht ohne neue Bestandsdaten erfunden.

## 5. Schwankungen und Reproduzierbarkeit

Der Raumprozess verwendet den bereits getesteten Fourier-/AR(1)-Baustein direkt auf Koordinaten. Standortfaktoren werden je Segment normiert; dadurch verschiebt sich die Nachfrage räumlich ohne ungewollte Änderung der jeweiligen regionalen Erwartung.

Darüber liegt ein gemeinsamer lognormaler Tageseffekt und ein segmentspezifischer Tageseffekt. Beide besitzen einen Mittelwert von 1 vor Ziehung der Paketmengen. Die Zahl der Pakete wird anschließend als Poisson-Zählung gezogen und multinomial auf Standorte und Anbieter verteilt. Alle Mengen sind ganzzahlig; jede nachfolgende Zuordnung erhält die tatsächlich gezogene Menge exakt. Eine Realisierung muss nicht exakt der erwarteten Menge entsprechen.

Optionales `persistent_site_log_sd` erzeugt beständige zusätzliche Standortunterschiede. Standard ist 0, weil dafür noch keine belastbare Reststreuung geschätzt wurde. Der Effekt bleibt über Tage und Realisierungen desselben Standortsatzes unverändert.

Seeds sind nach Datum, Segment, Realisierung und Prozess getrennt. Derselbe eingefrorene Stand und dieselbe Konfiguration reproduzieren dieselben Ergebnisdateien. Mehrere Realisierungen beschreiben hier Prozess-/Zufallsschwankung. Sie sind keine nachgewiesen kalibrierten Parameter-Konfidenzintervalle. Stärke und Reichweite der Schwankungen sind weiterhin Annahmen.

## 6. Lieferpunkte, Netzzugang und HAGRID-Export

Standardlieferpunkte sind die vorhandenen Standortpunkte, ausdrücklich keine verifizierten Eingänge. Ein optionales `delivery_mapping`-CSV enthält `site_id,carrier,delivery_point_id,x,y` im Standort-CRS. Es kann beispielsweise mehrere Nachfrageorte einem gemeinsamen Paketstationspunkt zuordnen. Private und gewerbliche Mengen bleiben dabei getrennt erhalten. Eine Umleitung verändert nicht den Empfängertyp.

Ein optionales MATSim-Netz (`network_file`, XML oder XML.gz) ermöglicht die Suche nach dem nächsten Link mit einem der konfigurierten Modi innerhalb `network_max_distance_m`. Ohne Netz wird kein Netzzugang behauptet. Auch ein nächster zulässiger Link ist noch kein bestätigter Eingang oder eine berechnete Tour.

Ausgabe je Datum/Realisierung:

- `demand_*.parquet`: Standort, Segment, PLZ, Basiserwartung, bedingte Erwartung und Mengen je Anbieter;
- `delivery_*.parquet`: Pakete je Lieferpunkt, Anbieter und Segment;
- `hagrid_*.gpkg`: GIS-Adapter mit Geometrie, numerischer ID, `postal_cod` und Anbieterfeldern;
- `export_checks.json`: Mengen- und PLZ-Prüfungen der GIS-Ausgabe.

Der Adapter verwendet die Semantik des tatsächlich geprüften Java-Codes: `<anbieter>_tag` enthält B2C, `<anbieter>_type` enthält B2B. Feldnamen werden wie beim bisherigen Shapefile auf zehn Zeichen begrenzt. Gemischte Lieferziele wurden getestet. Vollständiges MATSim-Routing wurde nicht ausgeführt. `service_event_proxy` kennzeichnet einen möglichen Bedienvorgang je Lieferpunkt/Anbieter; es ist keine validierte Stoppzahl einer gefahrenen Tour.

## 7. Ergebnisqualität des ersten vollständigen Laufs

Im ersten Lauf wurde `joint` ausgewählt. Auf zehn Test-PLZ: **27,7 % DHL-wMAPE und −9,2 % Bias**. Der Hermes-Formfehler auf den Test-PLZ beträgt 11,1 %, nach Skalierung auf deren beobachtete Summe. Das ist ausdrücklich keine absolute Hermes-Validierung.

Die Validierung lag bei allen drei Kandidaten ungefähr bei 50 %. Große Abweichungen konzentrieren sich unter anderem auf PLZ 30938, 30539 und 30855. Die Daten- und Sonderempfängerfragen sind damit nicht gelöst. Der gewählte Kandidat ist noch kein nachgewiesen überlegenes Endmodell; ein einzelner Split erlaubt keine robuste Aussage über kleine Unterschiede zwischen den Kandidaten.

Der eingefrorene Prognoselauf reproduzierte alle sechs ursprünglichen Tages-/Realisierungsdateien exakt. Private/gewerbliche Mengen bleiben bei Lieferpunkttransfer und GIS-Export erhalten. 19 Tests bestanden, darunter Kalendernormierung, Trainings-/Testtrennung, räumliche Reproduzierbarkeit, Netzzugang und die `_tag`/`_type`-Semantik.

## 8. Was Daten statt zusätzlichen Codes benötigt

Für eine fachliche Freigabe fehlen bestätigte Messdefinitionen, Referenzjahre und insbesondere belastbare zeitliche Messungen zur Kalibrierung der Schwankungen. Zusätzliche unabhängige B2B-/Anbieterdaten würden die bislang annahmenabhängigen Profile prüfen. Eine exakte PANDA-Reproduktion setzt dessen fehlende Rohdaten voraus. Keine dieser Lücken wird durch weitere Optimierung oder eine schönere Karte zu einer Beobachtung.
