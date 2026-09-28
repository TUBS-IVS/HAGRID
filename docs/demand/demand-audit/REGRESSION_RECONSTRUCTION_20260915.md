# Ursprüngliche Regression rekonstruieren und gezielt verbessern

Stand: 15.09.2026. Quellenprüfung und Entwurf; noch kein neuer Modellfit und kein nachgewiesener Genauigkeitsgewinn.

## Gesicherter Ursprung

Der vom Nutzer bereitgestellte Paperentwurf `C:/Users/bienzeisler/Downloads/Transportmetrica_B_Transport_Dynamics_Unpacking_the_Last_Mile_An_Agent_Based_Geospatial_Exploration_EWGT_Special_Issue.pdf` beschreibt auf S. 11–15, Abschnitt 3.3, die vorgelagerte Schätzung. Tabelle 2 auf S. 12 wurde auch visuell geprüft.

- Quantilsregression bei q=0.75, nicht Lasso: parcel_count ~ const + person_count + company_count.
- Gedruckte Koeffizienten: const=8.108e-7, Personen=0.1521, Unternehmen=0.8986; Pseudo-R²=0.6134, Residualfreiheitsgrade=15914.
- Der vorhandene historische Vergleichsexport lässt sich mit den präziser gespeicherten Koeffizienten `const=0.00000081078087809025286`, `persons=0.15205915924489793` und `companies=0.89862556511587188` exakt rekonstruieren. Diese Werte belegen die numerische Gleichung des Exports, nicht den nicht veröffentlichten Trainingsfilter, die Zielkonstruktion oder die historische B2B-Regel.
- Danach lokale B2B-Anteile mittels multiplikativer Faktoren und genetischer Optimierung an einen globalen Zielanteil anpassen; anschließend Anbieteraufteilung über angenommene B2B-Orientierungen und Marktanteile.
- Die genaue Formel vom Regressionsbeitrag zum ursprünglichen B2B-Anteil und die Erzeugung der `fit`-Klassen fehlen im beschriebenen Abschnitt. Ebenso bleibt der genaue Trainingsfilter und die Herstellung der Zielgröße aus den Straßenbeobachtungen zu rekonstruieren.

Numerischer Abgleich mit `parcel-demand-estimation/input/final_grid_250_region_results_update.csv` (15917 Zeilen): Die gedruckte Formel reproduziert `predicted` mit mittlerer absoluter Abweichung 0.002902 und maximaler Abweichung 0.083697. Die präziser gespeicherten Koeffizienten oben reproduzieren den vorhandenen Vergleichsexport exakt; das ist kein Nachweis des vollständigen Trainingsverfahrens. Die einfache Quote `100*0.8986*company_co/predicted_paper` reproduziert `b2b_ratio` nicht (MAE 13.8168 Prozentpunkte). Nur 7386 Zellen besitzen positive `total_coun`; die Tabellenfreiheitsgrade passen dagegen zu allen 15917 Zellen bei drei Parametern. Der im Text genannte Trainings-Subset ist deshalb prüfbedürftig.

## Methodische Korrekturen

1. Das 75%-Quantil ist kein bedingter Mittelwert. Seine Koeffizienten dürfen nicht als gemessene durchschnittliche Bestellraten ausgegeben werden. Den alten Fit als Vergleich rekonstruieren, für erwartete Nachfrage ein Mittelwertmodell prüfen.
2. Aus DHL-Gesamtmengen allein sind lokale B2B-Anteile und alle Anbieterprofile nicht unabhängig identifizierbar. Markt-/Segmentannahmen explizit festhalten; DHL-Vorhersage und B2B-Plausibilität getrennt beurteilen.
3. Zufällige GA-Lösungen sind keine empirisch begründeten Tagesverläufe. Referenzkalibrierung deterministisch; Tagesprozess und Parameterunsicherheit separat entsprechend bestehender Planung.
4. Die Paperformel für PLZ-Mengen auf S. 15 verwendet den Anteil der Zellen an allen Zellen. Das ist keine geeignete Nachfragegewichtung. Mengen nach Nachfragebasis verteilen und Bilanz erhalten.
5. Das bisher geplante Potenzial `1 + 0.1*employees` ist eine nachgelagerte Gewichtungsheuristik, keine Rekonstruktion der ursprünglichen Regression. Dieser Teil der Baseline-Spezifikation ist bis zum Vergleich vorläufig.

## Vergleich innerhalb des HAGRID-Grundgedankens

A. Historischer Vergleich: ursprünglicher q=.75-Fit auf altem Raster, soweit aus Quellen rekonstruierbar. Unbekannte Regeln ausdrücklich markieren, keine erfundene exakte Reproduktion.

B. Bevorzugter einfacher Kandidat: nichtnegative additive Mittelwertschätzung `mu = beta_person*persons + beta_company*companies`, zunächst ohne freien Intercept. Unternehmensgröße als separater Erweiterungskandidat; mehrere Branchen erst bei ausreichender Unterstützung und mit Regularisierung. Die Koeffizienten sind aggregierte Modellraten, keine kausalen individuellen Bestellraten. Bei DHL als Ziel gilt eine explizite Beobachtungsgleichung mit DHL-Anteilen je Segment, statt DHL-Koeffizienten direkt zu Marktkoeffizienten umzudeuten.

C. Erweiterung bei belegtem Mehrwert: additive positive Beiträge mit regional teilweise gemeinsam geschätzten Parametern; bei kleinen Stichproben starke Rückbindung an gemeinsame Raten. Nichtlinearer Mittelwertvergleich optional, ohne dessen Merkmalsbeiträge automatisch als B2B-Zerlegung zu interpretieren. Tagesmittel nicht als unabhängige ganzzahlige Poisson-Beobachtungen behandeln.

## Räumliche Unterstützung ohne Pflichtraster

- Ganze beobachtete Straßen bzw. fachlich geklärte Beobachtungsgruppen als kleinste Kalibriereinheit. Keine künstlichen Trainingsfälle durch Aufteilung derselben beobachteten Menge.
- Als Alternative innerhalb jeder PLZ benachbarte Straßen zu zusammenhängenden Gruppen bündeln. Gruppierung aus Geometrie und vorhandenen Personen-/Betriebsdaten, mit Mindestunterstützung und begrenzter räumlicher Ausdehnung; keine neue OSM-Pflicht.
- DHL-Mengen nicht gleichzeitig zur Konstruktion von Clustern und als unabhängig behauptete Testziele verwenden. Ein mengenbasiertes Clustering wäre ein gesonderter, nur auf Trainingszielen aufgebauter Vergleich.
- Zuerst ohne Clustering testen; dann mehrere Struktur-/Größenschwellen. Clustering ist ein Stabilisierungsversuch, keine garantierte Verbesserung. Gebäude und Betriebsstandorte bleiben Nachfrageorte; Gruppen dienen der Schätzung.
- Alle Kandidaten auf identischen zurückgehaltenen Beobachtungseinheiten und räumlichen Folds vergleichen. Kein scheinbarer Gewinn durch gröbere Testaggregation. Regularisierung und Gruppenparameter nur im Training wählen.

## Abnahme und Integration

Vor finaler Umsetzung der Referenzmathematik in Aufgabe 3: Trainingsziel, Nullwerte, räumliche Zuordnung und historische B2B-Regel dokumentieren; alte Regression gegen einfache Mittelwertkandidaten vergleichen. Der Ausschluss ganzer Beobachtungen über 1000 Paketen bleibt konfiguriert und dokumentiert. Auswahl anhand räumlich zurückgehaltener wMAPE/MAE, Bias, Größenklassen, Koeffizientenstabilität und Bilanzen. Eine auf dieselben 2021-Ziele kalibrierte Rekonstruktion separat von Testgüte ausweisen. Keine garantierte Fehlerschwelle vor Ergebnissen nennen.

Jahresfortschreibung, konfigurierbarer DHL-Einfluss, Kalender, räumlich wechselnde Tagesnachfrage, Monte Carlo und ein gemeinsames Stage-Dashboard bleiben bestehen. Die zusätzliche Stage „Strukturmodell“ zeigt Herkunft, Kandidatenvergleich, räumliche Unterstützung und Unsicherheit. Noch keine Implementierung dieser Varianten erfolgt.

Methodenquellen: https://www.statsmodels.org/stable/examples/notebooks/generated/quantile_regression.html ; https://pysal.org/spopt/notebooks/maxp.html ; https://pysal.org/spopt/notebooks/skater.html
