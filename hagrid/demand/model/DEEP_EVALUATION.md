# HAGRID-Nachfrage: Top-down, Verhaltensmodelle und räumliche Genauigkeit

## Entscheidung

Für das Referenzjahr 2021 empfiehlt sich eine beobachtungsgebundene, hybride Rekonstruktion: verlässliche DHL-Mengen auf ihrer tatsächlichen räumlichen Beobachtungsebene erhalten und mit Personen-, Gebäude- und Betriebsinformationen auf Empfängerstandorte verteilen. Ein Modell muss bekannte Mengen nicht zunächst ungenau neu erfinden. Die Qualität der anschließenden Verteilung muss jedoch gesondert geprüft werden.

Für unbeobachtete Standorte, andere Anbieter und zukünftige Veränderungen bleibt ein erklärendes Modell notwendig. Der derzeit beste geprüfte Kandidat verbindet ein einfaches Nachfragemodell mit wenigen Personenmerkmalen und räumlicher Restkorrektur. Die neuen Versuche mit Top-down-Normierung, OSM-Nutzungsflächen und Hermes als lokalem Prädiktor verbessern diesen Kandidaten nicht. Dies ist kein Beweis, dass weitere Verbesserung unmöglich ist; die untersuchten Erweiterungen liefern dafür aber noch keine belastbare Grundlage.

Die Trennung zwischen Rekonstruktion, räumlicher Vorhersage und Zukunftsprognose ist entscheidend. Eine exakte Kalibrierung auf bekannte PLZ-Werte ist kein unabhängiger Test. Umgekehrt sind etwa 15 Prozent Fehler auf zurückgehaltenen Gebieten kein Grund, die vorhandenen Referenzbeobachtungen in der Anwendung durch ungenauere Modellwerte zu ersetzen.

## Zielgröße und Datenlage

Die Zielmenge umfasst DHL-Straßenbeobachtungen von 2021 mit Werten bis einschließlich 1000. Zwei größere Beobachtungen wurden vollständig ausgeschlossen: 7361 und 4606. Die verbleibende Summe beträgt 85939. Diese Abgrenzung definiert den betrachteten KEP-Nachfragescope; sie beweist nicht für jede ausgeschlossene Beobachtung eine bestimmte Fahrzeugart.

Die Rohvariable wird entsprechend der bisherigen HAGRID-Verwendung als Tagesmittel behandelt. Ihr genauer Nenner bleibt unbestätigt. Eine Hochrechnung mit 313 Betriebstagen oder ein Vergleich mit Jahresmengen ist deshalb eine zusätzliche Annahme. Für die vorliegenden räumlichen Tests werden weder Jahreswachstum noch zufällige Tagesprofile benötigt.

Vorhanden sind eine synthetische Bevölkerung mit 1150862 Personen, Empfängerstandorte, Unternehmen mit Branche und Beschäftigtenzahl, DHL-Straßengeometrien, Hermes-PLZ-Daten sowie OSM-Nutzungsflächen. Alter und Erwerbstätigkeit sind verfügbar, individuelle Onlinebestellungen dagegen nicht. Nur 13 Personen konnten im verwendeten Gebäude-/PLZ-Mapping nicht räumlich zugeordnet werden; die mengenmäßige Abdeckung ist damit hoch. Eine hohe Zuordnungsquote bestätigt allerdings weder das Datenjahr noch die Richtigkeit jedes Merkmals.

536496 Personen haben keine Household-Kennung. Die alternative Kennung h_id ist nicht eindeutig: 25803 von 33324 h_id-Werten mit bekannten Haushalten kommen in mehreren Haushalten vor. Auch die Kombination aus Gebäude und h_id liefert keine eindeutigen Ergänzungskandidaten für die fehlenden Kennungen. Daraus dürfen keine tatsächlichen Haushalte konstruiert werden. Für ein Haushaltsmodell werden die ursprüngliche Synthesezuordnung oder externe Haushaltsgrößenrestriktionen benötigt.

## Was die Literatur beiträgt

### Top-down und konsistente Summen

Top-down-Verfahren prognostizieren eine übergeordnete Gesamtmenge und verteilen diese anhand von Anteilen. Konsistente Summen sind damit konstruktiv erreichbar. Die Verfahren verbessern aber nicht automatisch die Qualität der Verteilungsanteile. Reconciliation verbindet Schätzungen verschiedener Ebenen unter Summenbedingungen; ihre Stärke hängt von den Informationsquellen und Fehlerstrukturen ab.[^1][^2]

Für HAGRID bedeutet dies: Eine nationale Marktmenge kann eine regionale DHL-Menge nicht ohne zusätzliche Annahmen ersetzen. Anbieter, Marktsegment, Berichtszeitraum und die neue Ausschlussregel müssen zusammenpassen. Auch ein korrektes Regionstotal kann falsch verteilt werden. Das ist genau die Schwäche, die im zusätzlichen Top-down-Test sichtbar wird.

### Personen und Haushalte als Nachfrageursprung

Die deutsche logiTopp-Studie modelliert Onlinehandel über Teilnahme, Bestellzahl und Lieferortwahl. Sie verbindet logistische und Poisson-Modelle mit soziodemografischen sowie Aktivitätsmerkmalen und einer mehrtägigen Simulation. Die empirische Grundlage und das deutsche Anwendungsumfeld machen die Struktur relevant; die Koeffizienten sind aber nicht automatisch auf Hannover 2021 übertragbar.[^3]

POLARIS verwendet einen zweistufigen Ansatz auf Haushaltsebene mit Haushalts- und Umgebungsmerkmalen und regionaler Kalibrierung.[^4] Für HAGRID ist diese Modellfamilie vor allem interessant, um stabile Bestellneigung, Nulltage und Häufigkeitsunterschiede abzubilden. Aus 53 aggregierten Tagesmitteln lassen sich Teilnahme und bedingte Häufigkeit jedoch nicht getrennt identifizieren: verschiedene Kombinationen können denselben Mittelwert ergeben. Externe Befragungsdaten oder individuelle Lieferverläufe sind erforderlich.

Die offene Lyon-Modellkette zeigt, wie eine synthetische Bevölkerung mit Paketnachfrage verbunden werden kann.[^5] Ihre Reproduzierbarkeit ist methodisch wertvoll. Daraus folgt jedoch keine hier nachgewiesene Fehlerquote für Hannover. Eine realistisch erscheinende Simulation und eine auf unabhängigen Paketdaten validierte Schätzung sind unterschiedliche Nachweise.

### Betriebe, Flächen und räumliche Struktur

Die FHWA unterscheidet Gütermengen, Güterfahrten und Servicefahrten und betont die Bedeutung von Betriebsgröße und wirtschaftlicher Tätigkeit. Sie behandelt außerdem nichtlineare Größeneffekte und lokale Kalibrierung.[^6] Damit ist unsere Prüfung unterproportionaler Beschäftigteneffekte fachlich begründet. Die dortigen Fahrten- oder Tonnageraten dürfen aber nicht als Pakete pro Betrieb übernommen werden.

Eine aktuelle Studie aus Thessaloniki untersucht die Verknüpfung von Paketnachfrage und Stadtstruktur mit offenen Raumdaten.[^7] Sie motiviert das Prüfen zusätzlicher Nutzungsinformationen. Aus dem zugänglichen Abstract lässt sich keine unmittelbar mit unserem räumlichen wMAPE vergleichbare Erfolgszusage ableiten. Entscheidend bleibt der lokale Vergleich auf zurückgehaltenen Gebieten.

## Prüfaufbau

Die aktuellen Vergleiche verwenden dieselben 53 PLZ und dieselbe bereinigte Zielgröße. Drei zufällige Fünffach-Aufteilungen werden durch eine zusätzliche Prüfung mit fünf räumlichen Gruppen ergänzt. Die Gruppen stammen aus Clustering der PLZ-Zentren. Es gibt keinen räumlichen Puffer; Abhängigkeiten zwischen benachbarten Gruppen sind daher weiterhin möglich.

Modellauswahl erfolgt innerhalb der äußeren Trainingsmenge. Die äußeren Testziele werden nicht für Skalierung, Regressionsparameter oder räumliche Residuen verwendet. In den automatisierten Tests werden zurückgehaltene Zielwerte gezielt verändert, um zu prüfen, dass die Schätzung unverändert bleibt. Bei der Hermes-Übertragung gilt eine andere Informationslage: Hermes am Testort ist ausdrücklich ein bekannter Prädiktor; nur DHL bleibt dort verborgen.

Die Daten wurden bereits in früheren Experimenten untersucht. Auch verschachtelte Kreuzvalidierung macht diese Gesamtrecherche nicht zu einem neuen, vollständig unberührten Abschlusstest. Sie verhindert eine direkte Nutzung der äußeren Ziele bei der jeweiligen Auswahl. Die Ergebnisse sind weiterhin explorativ. Dieses Vorgehen entspricht dem Zweck verschachtelter Validierung, Auswahl und Leistungsbewertung zu trennen.[^8]

Hauptmaß ist der wMAPE: Summe absoluter Fehler geteilt durch beobachtete Gesamtmenge. Ergänzend wird der Bias berichtet. Ein kleiner Bias kann große lokale Über- und Unterschätzungen verdecken. Umgekehrt kann ein niedrigerer wMAPE mit einer stärkeren systematischen Unterschätzung einhergehen. Beide Kriterien müssen gemeinsam betrachtet werden.

## Top-down-Test

Es wurden die außerhalb der Testgebiete geschätzten Vorhersagen verwendet und drei Informationsstände verglichen:

1. Keine bekannte Gesamtmenge: reguläre Vorhersage.
2. Bekannte Regionssumme: proportionale Normierung aller Vorhersagen auf 85939.
3. Bekannte Summe jedes Testblocks: zusätzliche räumliche Summeninformation, danach Verteilung innerhalb des Blocks.

Die letzten beiden Varianten sind bedingte Allokationstests. Ihre bekannten Summen stammen aus den Beobachtungen. Sie dürfen nicht als unabhängige Mengenvorhersagen bezeichnet werden.

| Personen-/Raummodell | Zufällige Aufteilungen | Räumliche Gruppen |
|---|---:|---:|
| Ohne bekannte Summe | 15,36 % | 15,98 % |
| Bekannte Regionssumme | 16,47 % | 16,21 % |
| Bekannte Testblock-Summen | 15,74 % | 14,93 % |

Die Regionsnormierung setzt den Bias auf null, verschlechtert hier jedoch den wMAPE. Das Modell verteilt einen Teil der zusätzlich erforderlichen Menge in Gebiete, die bereits überschätzt werden. Erst feinere bekannte Summen helfen etwas. Sind alle einzelnen PLZ-Mengen vorgegeben, wird deren Fehler konstruktiv null; offen bleibt dann die Verteilung innerhalb der PLZ.

Die Aussage lautet daher nicht, dass Top-down ungeeignet ist. Top-down ist für eine Referenzrekonstruktion sinnvoll, wenn belastbare Summen auf ausreichend feiner Ebene vorliegen. Ein einziges korrektes Regionstotal ist aber kein Ersatz für lokale Nachfragetreiber.

## Zusätzliche OSM-Nutzungsflächen

Der neue Datenadapter berechnet Wohn-, Industrie-, Gewerbe- und Einzelhandelsflächen je PLZ aus dem vorhandenen OSM-Landuse-Bestand. Polygone werden je Nutzungsklasse vereinigt, damit Überlappungen derselben Klasse nicht doppelt gezählt werden. Die Verarbeitung verwendet keine DHL-Zielwerte. Der historische Datenstand bleibt unbestätigt; dies ist kein neu erhobener Gebäudeflächenbestand für 2021.

Die Flächen werden mit den bisherigen Personenmerkmalen kombiniert und in stark regularisierten Residualmodellen sowie direkten Regressionen geprüft. Die beste Personen-/Raumvariante bleibt überlegen:

| Modell | Zufällige Aufteilungen | Räumliche Gruppen |
|---|---:|---:|
| Personen plus Raum | 15,36 % | 15,98 % |
| Personen, Nutzungsflächen und Raum | 16,94 % | 16,44 % |
| Direkte Log-Ridge-Regression mit Flächen | 20,07 % | 16,99 % |

Damit ist nicht bewiesen, dass Gebäudedaten nutzlos sind. Landuse-Fläche misst eine andere Eigenschaft als nutzbare Wohn- oder Gewerbefläche, Zahl der Einheiten oder tatsächliche Betriebsfunktion. Der Test spricht gegen die ungeprüfte Übernahme dieser groben Flächenmerkmale in den Hauptfit.

## Hermes als zweite lokale Informationsquelle

In einem weiteren Vergleich wurde Hermes am jeweiligen Testort als bekannter Prädiktor verwendet. Getestet wurden ein auf Trainingsgebieten geschätztes DHL/Hermes-Verhältnis, eine 50/50-Mischung mit dem Basismodell und eine positive Regression mit Bevölkerung, Betriebsexposition und Hermes.

| Variante | Zufällige Aufteilungen | Räumliche Gruppen |
|---|---:|---:|
| Personen plus Raum | 15,36 % | 15,98 % |
| DHL/Hermes-Verhältnis | 25,99 % | 24,20 % |
| Mischung mit Basismodell | 19,48 % | 19,11 % |
| Regression mit Hermes | 19,98 % | 23,59 % |

Die innere Auswahl entscheidet in sämtlichen äußeren Folds für das Personen-/Raummodell. Hermes ist folglich in dieser einfachen Form kein geeigneter Ersatz für die lokale DHL-Nachfrage. Unterschiedliche Kundenstrukturen und unbestätigte Mengendefinitionen sind plausible Erklärungen, aber durch diesen Test nicht einzeln nachgewiesen. Andere Anbieter sollten deshalb weiterhin nicht über einen überall identischen DHL-Faktor erzeugt werden.

## Fehlerkonzentration und PLZ 30855

Bei räumlich geblockter Prüfung des Personen-/Raummodells werden in 30855 rund 1698 statt 5660 geschätzt. Diese Differenz erklärt etwa 28,9 Prozent des gesamten absoluten Fehlers. Ohne diese PLZ läge der diagnostische wMAPE der übrigen Gebiete bei ungefähr 12,2 Prozent. Das ist keine neue Leistungskennzahl und kein Grund, die PLZ auszuschließen.

Die hohe Menge entsteht aus 289 Straßenbeobachtungen. Ihr Median liegt bei 12, der größte Einzelwert bei 174. Die Grenze von 1000 entfernt hier nichts. Alle geprüften Straßenmittelpunkte liegen im verwendeten Polygon der angegebenen PLZ. Ein einfacher PLZ-Geometriefehler erklärt diesen Befund somit nicht; damit sind weder alle Grenzgeometrien noch die Messdefinition verifiziert.

Die Standortmerkmale umfassen dort 21054 Personen, 1606 Betriebe und 21830 Beschäftigte. Eine zusätzliche frei geschätzte PLZ-Konstante könnte die Menge nachträglich treffen, würde aber ohne weitere Daten kaum auf unbekannte Gebiete übertragen. Vorrangig sollten räumliche Unternehmensabdeckung, Datenstände und Unterschiede der DHL-Erhebung geprüft werden. Es ist ausdrücklich nicht belegt, dass die Beobachtung falsch ist.

## Empfohlene Architektur

**Referenzzustand 2021:** Bereinigte DHL-Beobachtungen als Mengenanker verwenden. Die vorliegende Rekonstruktion tut dies auf PLZ-Ebene. Der nächste Ausbau sollte die vorhandenen Straßenbeobachtungen berücksichtigen, sobald deren Additivität und räumliche Zuordnung verlässlich sind. Standorte ohne eindeutige Zuordnung müssen als Restmenge oder unsichere Zuordnung sichtbar bleiben. Keine Zwangsverteilung auf zufällige Gebäude.

**Räumliche Verteilung innerhalb der Beobachtungseinheiten:** Personen, Betriebe und gegebenenfalls verifizierte Gebäudeinformationen liefern relative Gewichte. Getrennt prüfen, ob diese Gewichte die Straßen- oder Gebäudeverteilung verbessern. PLZ-Summen allein können das nicht bestätigen. Räumliche Felder erzeugen Schwankungen, ersetzen aber keine validierte mittlere Verteilung.

**Veränderung gegenüber 2021:** Zunächst relative Änderungen modellieren, etwa durch Bevölkerungs- und Betriebsentwicklung sowie Nachfrageintensität. Die empirische Referenzstruktur bleibt Ausgangspunkt. Ein Referenzfehler soll nicht automatisch in jedes Zukunftsjahr fortgeschrieben werden; gleichzeitig darf eine rein nationale Wachstumskurve lokale Strukturänderungen nicht überdecken.

**Andere Anbieter:** Als zusätzliche, unsichere Modellschicht behandeln. Branchenprofile, Marktanteile und Hermes-Forminformation bleiben nützlich, aber die DHL-Rekonstruktion identifiziert keine UPS-, FedEx- oder Amazon-Nachfrage. Der vollständige Markt muss seine eigene Datenbasis und Unsicherheitsdarstellung behalten.

**Verhaltenssimulation:** Erst nach der mittleren Nachfrageverteilung Teilnahme, Bestellfrequenz, Paketaufteilung und Lieferverzug ergänzen. Bestellungen sind nicht automatisch Pakete oder Stopps. Ohne passende Beobachtungen müssen diese Übergänge als Annahmen oder Szenarien gekennzeichnet bleiben.

## Priorisierte nächste Schritte

1. Straßenbasierte Rekonstruktion und Evaluation vorbereiten: Beobachtungseinheiten, eindeutige Verknüpfungen und Restmengen definieren. Nicht mehr ausschließlich auf 53 aggregierten PLZ kalibrieren.
2. 30855 als gezielten Datenabgleich bearbeiten, ohne den Bezirk aus dem Benchmark zu entfernen. Ein Teilproblem mit fast 29 Prozent Fehleranteil hat höhere Priorität als zusätzliche allgemeine Modellparameter.
3. Originale Haushaltszuordnung und historische Gebäudemerkmale erschließen. Grobe Landuse-Flächen haben keinen stabilen Mehrwert gezeigt; das rechtfertigt keine pauschale Gleichsetzung mit detaillierten Gebäudeinformationen.
4. Einen abschließenden räumlich und möglichst zeitlich unabhängigen Prüfbestand definieren. Die bisherigen Fehlerraten sind keine Garantie für 2030 oder für neue Stadtteile.
5. Verbesserung nach mehreren Kriterien freigeben: lokale Fehler, Bias, Stabilität, Datenbedarf und Übertragbarkeit. Keine Variante nur wegen einer günstigen Tabellenzeile übernehmen.

## Implementierung und Nachweise

`topdown.py` führt die bedingten Summenvergleiche aus. `landuse.py` erzeugt die Flächenmerkmale. `model_search.py` enthält den zusätzlichen Flächen- und Anbietertransfervergleich. Alle Läufe schreiben Vorhersagen, Kennzahlen, Protokoll und Herkunftshashes. 54 Tests bestehen, einschließlich Schutz gegen Zielwert-Leakage, expliziter Nutzung bekannter Hermes-Prädiktoren, Summenerhaltung und doppelter Flächenzählung.

Die neuen Tests liefern keine bessere allgemeine Modellvariante. Das Produktionsmodell wurde deshalb nicht aufgrund dieser Zusatzversuche ausgetauscht. Die beobachtungsgebundene Rekonstruktion und die unabhängigen Vorhersageprüfungen bleiben getrennte Artefakte.

## Quellen

[^1]: Hyndman, R. J.; Athanasopoulos, G. (2021, Onlinefassung): [Forecasting: Principles and Practice, Single level approaches](https://otexts.com/fpptr/single-level.html). Top-down-Anteile und Bottom-up-Aggregation.
[^2]: Hyndman, R. J.; Athanasopoulos, G. (2021, Onlinefassung): [Forecast reconciliation](https://otexts.com/fpp3/reconciliation.html). Konsistente Aggregationsbedingungen.
[^3]: Reiffer, A. S.; Kübler, J.; Kagerbauer, M.; Vortisch, P. (2023): [Agent-based model of last-mile parcel deliveries and travel demand incorporating online shopping behavior](https://publikationen.bibliothek.kit.edu/1000163706/151590247). Research in Transportation Economics 102, 101368, insbesondere Abschnitte 3.1–3.4.
[^4]: Argonne National Laboratory, POLARIS-Team, Dokumentation 26.03: [E-commerce choice](https://polaris.taps.anl.gov/latest_dev/polaris/theory/demand_model/ecommerce_choice.html). Haushaltsbezogenes zweistufiges Modell.
[^5]: Hörl, S.; Puchinger, J. (2023): [From synthetic population to parcel demand: A modeling pipeline and case study for last-mile deliveries in Lyon](https://www.sciencedirect.com/science/article/pii/S2352146523009420). Transportation Research Procedia 72, 1707–1714, DOI 10.1016/j.trpro.2023.11.644. Zugänglicher Abstract; keine vollständige Methodenreplikation.
[^6]: Federal Highway Administration: [Freight and Land Use Travel Demand Evaluation, Section 3, Topic A](https://ops.fhwa.dot.gov/publications/fhwahop18073/section3a.htm). Unterscheidung Gütermenge/Fahrten, Betriebseffekte und lokale Kalibrierung.
[^7]: [Integrating Urban Factors as Predictors of Last-Mile Demand Patterns: A Spatial Analysis in Thessaloniki](https://www.mdpi.com/2413-8851/9/8/293), Urban Science 9(8), 293 (2025). Zugänglicher Abstract/Suchauszug; Volltextzugriff begrenzt, keine numerischen Erfolgswerte übernommen.
[^8]: scikit-learn-Dokumentation: [Nested versus non-nested cross-validation](https://scikit-learn.org/stable/auto_examples/model_selection/plot_nested_cross_validation_iris.html). Auswahl und Bewertung getrennt; lokale Tests mit scikit-learn 1.7.2.

Lokale Ergebnisquellen: `runs/person-hybrid-20260910`, `runs/topdown-20260910`, `runs/landuse-search-20260910`, `runs/crosscarrier-20260910`, `runs/household-audit-20260910`. Die Auswertung wurde am 10.09.2026 abgeschlossen. Die angegebenen Datenstände beziehen sich auf den untersuchten lokalen Bestand, nicht auf einen neu erhobenen historischen Gebäudebestand.
