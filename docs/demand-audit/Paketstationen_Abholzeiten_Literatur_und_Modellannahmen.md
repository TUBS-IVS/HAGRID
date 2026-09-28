# Abholzeiten an Paketstationen: Literaturbefunde und Modellannahmen

**Stand der Quellenprüfung:** 28. September 2026  
**Anwendungsfall:** Simulation von Paketabholung, Fachbelegung und Kapazitätsengpässen an Paketstationen.  
**Umfang:** Zusammenführung der diskutierten Erkenntnisse; keine systematische Literaturübersicht und keine empirische Kalibrierung für ein bestimmtes Untersuchungsgebiet.

## 1. Entscheidung für die erste Modellversion

Als Baseline wird das gemeinsame Referenzprofil von **Sailer, Klein und Steinhardt (2026)** verwendet: **60 % / 20 % / 20 %** für die drei Modelltagklassen. Die genaue Zeitdefinition steht in Abschnitt 2.1. [S1](#s1)

Für den Prototyp ist die Übernahme einer veröffentlichten Parametrisierung methodisch vertretbar. Eine eigene Erhebung des Abholverhaltens ist dafür nicht zwingend erforderlich. Entscheidend ist, das Profil als **literaturgestützte Modellannahme** und nicht als nachgewiesenes Verhalten der örtlichen Bevölkerung zu bezeichnen.

Die ursprünglich diskutierte stationsweise Beta-Streuung um 40 % mit einer Standardabweichung von 8 Prozentpunkten wird **zunächst nicht verwendet**. In den hier geprüften Quellen findet sich keine empirische Kalibrierung genau dieser Kombination. Sie bleibt eine mögliche spätere Szenarioannahme.

**Arbeitsentscheidung:** Ein einheitliches Referenzprofil implementieren, Annahmen dokumentieren und weiterbauen. Vor belastbaren Aussagen zu benötigten Fachzahlen oder Überlastungen zusätzlich die Empfindlichkeit gegenüber veränderten Abholzeiten untersuchen.

## 2. Literaturbefunde

### 2.1 Sailer, Klein und Steinhardt (2026): Referenzprofil

Abschnitt 5.1.2, Tabelle 2, enthält folgende Profile: [S1](#s1)

| Einstellung | Kundengruppe | Modelltag 1 | Modelltag 2 | Modelltag 3 |
|---|---|---:|---:|---:|
| `id` | Beide Kundengruppen | 60 % | 20 % | 20 % |
| `pf` | Premiumkunden | 80 % | 10 % | 10 % |
| `pf` | Standardkunden | 50 % | 25 % | 25 % |

Der Ersttagesanteil orientiert sich an Hovi et al.; Gesamtprofile sind **Versuchsparameter**. `pf` unterscheidet Kunden, nicht Stationen. [S1](#s1)

**Zeitdefinition:** Fachzuteilung am Vorabend; `b = 1` ist der modellierte Zustelltag. Tageszeit: gleichverteilt über 20 Zeitpunkte. Obergrenze: drei Modelltage. [S1](#s1)

**Präzisierung:** Fachfreigabe umfasst Kundenabholung oder Betreiberentnahme bei Fristablauf. „Alle bis Tag 3 vom Kunden abgeholt“ wäre deshalb zu stark. [S1](#s1)

### 2.2 Hovi et al. (2023): Beobachtete Abholzeiten in Norwegen

Der TØI-Forschungsbericht untersucht das PostNord-Paketstationsnetz in Norwegen. Er ist ein wissenschaftlicher Forschungsbericht, kein Zeitschriftenartikel. Die englische Zusammenfassung berichtet im Abschnitt „Pick-up times“, Seite iv: [S2](#s2)

| Beobachtete Größe | Ergebnis |
|---|---:|
| Abholung innerhalb eines Tages nach Zustellung | Rund 60 % |
| Durchschnittliche Zeit bis zur Abholung | 31,6 Stunden |
| Durchschnitt an Standorten bei Lebensmittelgeschäften | 30,5 Stunden |
| Durchschnitt an Verkehrsknotenpunkten | 34,5 Stunden |

Die Ersttagesanteile sind zwischen den Standorttypen ähnlich, während mittlere Abholzeiten und länger liegende Sendungen Unterschiede zeigen. Späte Zustellungen und Zustellungen gegen Ende der Arbeitswoche gehen mit längeren Abholzeiten einher. Außerdem werden räumliche Unterschiede beschrieben; nachts wird wenig abgeholt und manche Standorttypen werden am Wochenende seltener besucht. [S2](#s2)

**Einordnung:** Der Bericht begründet, zeitliche und räumliche Unterschiede grundsätzlich zu berücksichtigen. Die geprüfte Zusammenfassung liefert jedoch keine vollständige empirische Tagesverteilung für Deutschland und keine Standardabweichung der Erstabholanteile zwischen Stationen von 8 Prozentpunkten.

### 2.3 Morganti et al. (2014): Historische deutsche Betreiberangabe

In Abschnitt 5.1, Seite 186, wird für DHL-Packstationen berichtet, dass **70 % der Pakete innerhalb von 24 Stunden** abgeholt werden. Als Ursprung nennen die Autoren **DHL (2011)**. [S3](#s3)

Das ist eine historische Betreiberangabe, die in einem wissenschaftlichen Beitrag wiedergegeben wird. Sie ist weder eine aktuelle deutsche Kalibrierung noch eine unabhängige Vollerhebung durch die Autoren. Eine vollständige Aufteilung auf spätere Abholtage wird an dieser Stelle nicht angegeben.

**Einordnung:** Geeignet als historischer Vergleichswert. Für eine konkrete Simulation muss zusätzlich geklärt werden, wie sich die übrigen 30 % verteilen. Die ursprüngliche DHL-Mitteilung wurde für diese Notiz nicht separat geprüft; der Wert wird ausdrücklich als Angabe bei Morganti et al. dokumentiert.

### 2.4 Schnieder, Hinde und West (2021): Belegungsmodell mit Restbestand

Die Studie nutzt reale Lieferdaten aus London, übernimmt aber das Abholverhalten aus einer Literaturangabe: 70 % Abholung innerhalb von 24 Stunden. Weil Angaben zu längeren Liegezeiten fehlen, wird für spätere Tage angenommen, dass jeweils **30 % des noch vorhandenen Bestands** einen weiteren Tag liegen bleiben. Die Annahme wird in Abschnitt 3.1, Seite 5, erläutert. [S4](#s4)

**Einordnung:** Die realen Lieferdaten machen die Abholverteilung nicht zu einer beobachteten Verteilung. Das Paper liefert ein nachvollziehbares Vorbild für die Fortschreibung von Fachbelegung über mehrere Tage. Die daraus berechneten Tagesanteile stehen in Abschnitt 4.3 dieser Notiz.

### 2.5 Sethuraman et al. (2024): Datenbasierte Lagerdauerprognose bei Amazon

Das Paper beschreibt ein Kapazitätsmanagementsystem mit einem eigenen Modul für Lagerdauerwahrscheinlichkeiten. Im Abschnitt „Package Dwell Time Probability Estimation“ werden Klassen von **0 bis 6 Tagen** verwendet; **0 bedeutet Entfernung am Zustelltag beziehungsweise im erläuterten Kundenbeispiel Abholung am Zustelltag**. [S5](#s5)

Die Prognose berücksichtigt Versandoption, Zustellwochentag, Tag des Monats und historische Lagerdauerstatistiken. Beschrieben werden Random-Forest-Klassifikation, gemeinsame Nutzung von Informationen ähnlicher Stationen und eine Kalibrierung der Wahrscheinlichkeiten mittels isotonic regression. Die Ergebnisse fließen in die Kapazitätsplanung ein. [S5](#s5)

**Wichtig:** Die Lagerdauer umfasst auch Sendungen, die nicht vom Kunden abgeholt werden. Im beschriebenen Betrieb kann nach drei Tagen eine Rückholung ausgelöst werden, deren Ausführung weitere Tage benötigt. Das ist die im Paper dargestellte Fallkonstellation, keine hier überprüfte Aussage über aktuelle Rückholregeln. [S5](#s5)

**Einordnung:** Methodisches Vorbild für spätere datenbasierte Stationsunterschiede. Die Veröffentlichung begründet keine pauschale Beta-Verteilung um 40 % und stellt kein universelles, für alle Stationen identisches Tagesprofil bereit.

## 3. Zeitdefinitionen: drei unterschiedliche Größen

Für die Implementierung sind folgende Begriffe getrennt zu halten:

| Größe | Definition |
|---|---|
| Kalendertag der Abholung | Zustelltag, Folgetag, zweiter Folgetag usw. |
| Verstrichene Zeit seit Einlagerung | Beispielsweise unter 24 Stunden, 24 bis unter 48 Stunden usw. |
| Modelltagindex | Tagesklasse innerhalb der definierten Ereignisreihenfolge des Modells |

**Beispiel:** Einlagerung am Montag um 16:00 Uhr, Abholung am Dienstag um 09:00 Uhr. Das Paket wird nach **17 Stunden**, aber **am folgenden Kalendertag** abgeholt.

Daraus folgt rein rechnerisch: **40 % am Zustelltag und 60–70 % innerhalb von 24 Stunden können miteinander vereinbar sein.** Die unterschiedlichen Zahlen widersprechen sich nicht zwingend. Sie bestätigen einander aber auch nicht, solange Bezugszeitpunkt und Zeitfenster verschieden sind.

Für die Übertragung des Referenzprofils ist deshalb eine explizite Zuordnung erforderlich. Insbesondere darf aus `b = 1` nicht ohne Prüfung ein zusätzlicher voller Wartetag nach der tatsächlichen Einlagerung werden. Ebenso wenig darf eine Tagesklasse ohne weitere Annahme in eine exakt 24-stündige Lagerdauer übersetzt werden.

### Abholung und Fachfreigabe getrennt dokumentieren

Für die **Kapazität** ist entscheidend, wann das Paket das Fach tatsächlich verlässt. Für **Kundenverhalten und Abholverkehr** ist dagegen entscheidend, ob es der Kunde abholt oder der Betreiber entfernt.

Ein Modell, das nur Freigabeereignisse erzeugt, sollte deshalb keine scheinbar empirische Zahl von Kundenabholungen ausgeben. Ohne gesonderte Annahme oder Daten zum Freigabegrund bleiben Kundenabholung und Betreiberentnahme in einer reinen Belegungsrechnung ununterschieden.

## 4. Mathematische Umsetzung und Plausibilitätsprüfungen

Die folgenden Rechnungen sind **eigene Ableitungen** aus den angegebenen Profilen, keine zusätzlichen empirischen Ergebnisse.

### 4.1 Eine Tagesklasse je Paket ziehen

Für das gewählte Dreitagesprofil gilt:

$$
P(D=1)=0.60,\qquad P(D=2)=0.20,\qquad P(D=3)=0.20.
$$

Eine mögliche Umsetzung ist eine einmalige kategoriale Ziehung bei der Einlagerung. Das Ereignis für die Freigabe wird anschließend in der gezogenen Tagesklasse geplant. Ein konkreter Zeitpunkt innerhalb der Klasse erfordert zusätzlich eine Zeitregel.

Das Profil beschreibt Wahrscheinlichkeiten. Bei einer einzelnen Station mit wenigen Sendungen müssen deshalb nicht in jedem Durchlauf exakt 60 %, 20 % und 20 % realisiert werden. Entscheidend sind korrekte Ziehungen und statistisch plausible Ergebnisse über ausreichend viele Sendungen beziehungsweise Wiederholungen.

### 4.2 Tagesanteile sind keine bedingten Abholwahrscheinlichkeiten

Wer statt einer einmaligen Ziehung jeden Tag die noch vorhandenen Pakete prüft, benötigt die bedingte Freigabewahrscheinlichkeit:

$$
h_d=P(D=d\mid D\geq d)
=\frac{P(D=d)}{1-\sum_{j<d}P(D=j)}.
$$

Für das Referenzprofil ergibt sich:

| Tagesklasse | Anteil aller Pakete | Freigabewahrscheinlichkeit unter den zu Tagesbeginn verbliebenen Paketen |
|---|---:|---:|
| 1 | 60 % | 60 % |
| 2 | 20 % | 50 % |
| 3 | 20 % | 100 % |

**Nicht korrekt wäre:** Am ersten Tag 60 %, am zweiten Tag 20 % des Restbestands und am dritten Tag nochmals 20 % des Restbestands zu entfernen. Das erzeugt ein anderes Profil und lässt Pakete nach der dritten Klasse übrig.

Für den diskreten Klassenindex gelten:

$$
E[D]=1\cdot0.60+2\cdot0.20+3\cdot0.20=1.60,
$$

$$
\operatorname{SD}(D)=0.80.
$$

**Diese Werte sind keine automatisch gültige mittlere Lagerdauer von 38,4 Stunden beziehungsweise Stundenstreuung.** Dafür wären tatsächlicher Einlagerungszeitpunkt, Freigabezeit innerhalb der Tagesklasse und Zeitgrenzen erforderlich.

### 4.3 Geometrische Fortschreibung des 70-%-Ansatzes

Unter der in Abschnitt 2.4 beschriebenen Fortsetzungsannahme ist der tägliche Verbleibsanteil `r = 0.30`. Daraus folgt für den diskreten Tagesindex:

$$
P(D=d)=(1-r)r^{d-1}=0.70\cdot0.30^{d-1}.
$$

| Abholklasse | Rechnerischer Anteil aller Pakete |
|---|---:|
| Erste Klasse | 70,0 % |
| Zweite Klasse | 21,0 % |
| Dritte Klasse | 6,3 % |
| Nach der dritten Klasse noch vorhanden | 2,7 % |

Bei einer ausdrücklich gewählten Einteilung in aufeinanderfolgende 24-Stunden-Intervalle entsprächen diese Klassen den ersten 24, den folgenden 24 und den nächsten 24 Stunden. Die Tabelle ist eine mathematische Übersetzung der Fortsetzungsannahme, keine zusätzliche Messung.

**30 % Restbestand nach der ersten Klasse sind nicht 30 % Abholungen in der zweiten Klasse.** Von diesem Rest werden dort wiederum 70 % entfernt: `0.30 × 0.70 = 0.21`.

## 5. Ursprüngliche Idee: stationsweise Beta-Verteilung

### 5.1 Was damit modelliert würde

Die ursprüngliche Idee lautete: Jede Station erhält einen eigenen Erstabholanteil, verteilt um 40 % mit einer Standardabweichung von 8 Prozentpunkten. Die späteren Abholtage folgen relativ zueinander einer gemeinsamen Grundlinie.

Die Beta-Verteilung würde dabei **Unterschiede der Erstabholwahrscheinlichkeit zwischen Stationen** beschreiben. Sie ist nicht unmittelbar die Verteilung der Lagerdauer einzelner Pakete.

### 5.2 Passende Parameter

Für eine Beta-Verteilung mit Mittelwert `μ` und Standardabweichung `σ` gilt:

$$
\kappa=\frac{\mu(1-\mu)}{\sigma^2}-1,\qquad
\alpha=\mu\kappa,\qquad
\beta=(1-\mu)\kappa.
$$

Mit `μ = 0.40` und `σ = 0.08` ergibt sich:

$$
\kappa=36.5,\qquad \alpha=14.6,\qquad \beta=21.9,
$$

also:

$$
p_s\sim\operatorname{Beta}(14.6,21.9).
$$

**Das ist eine rechnerische Parametrisierung der eigenen Vorgabe, kein Literaturbefund.** Acht Prozentpunkte bedeuten hier eine Standardabweichung von `0.08`, nicht acht Prozent des Mittelwerts.

Für dauerhafte Stationsunterschiede würde `p_s` einmal je Station und Simulationsreplikation gezogen und innerhalb dieser Replikation konstant gehalten. Eine neue Ziehung je Paket oder je Tag wäre ein anderes Modell.

### 5.3 Den Rest proportional zur Grundlinie verteilen

Sei die gemeinsame Grundlinie:

$$
q=(q_1,\ldots,q_K),\qquad \sum_{d=1}^{K}q_d=1,\qquad q_1<1.
$$

Dann lässt sich die ursprüngliche Idee konsistent formulieren als:

$$
P(D=1\mid s)=p_s,
$$

$$
P(D=d\mid s)=(1-p_s)\frac{q_d}{1-q_1},\qquad d\geq2.
$$

Damit bleiben die relativen Anteile der späteren Klassen erhalten und die Wahrscheinlichkeiten summieren sich zu eins.

**Rechenbeispiel:** Bei der Grundlinie `(0.60, 0.20, 0.20)` und einem Stationswert `p_s = 0.40` entsteht `(0.40, 0.30, 0.30)`. Das ist nicht mehr die ursprüngliche Baseline. Bei `p_s = 0.70` entsteht entsprechend `(0.70, 0.15, 0.15)`.

**Entscheidung:** Für den ersten Modellstand nicht aktivieren. Eine spätere Nutzung muss ausdrücklich als unkalibriertes Heterogenitätsszenario gekennzeichnet werden.

## 6. Was die Referenz wissenschaftlich absichert – und was nicht

Die Quellenwahl beantwortet die Frage, **woher eine Annahme kommt**. Sie beweist nicht automatisch, dass diese Annahme im eigenen Untersuchungsgebiet gilt.

| Verwendung | Einordnung |
|---|---|
| Referenzprofil für einen Prototyp übernehmen | Vertretbar, wenn Zeitdefinition und Einschränkungen dokumentiert werden |
| Das Profil als lokale Messung darstellen | Nicht durch die Übernahme einer Literaturquelle gedeckt |
| Fachbedarf und Rückstau unter dieser Annahme berechnen | Möglich; Ergebnisse gelten zunächst bedingt auf die Annahme |
| Daraus belastbare Kapazitätsempfehlungen ableiten | Zusätzlich Sensitivitäten und möglichst lokale Plausibilisierung erforderlich |
| Aus allen Freigaben Kundenabholfahrten erzeugen | Nur mit einer expliziten Annahme zum Freigabegrund |

Für eine erste Sensitivität können schnellere und langsamere Profile betrachtet werden. Werden die kundengruppenspezifischen Profile aus Abschnitt 2.1 auf die gesamte Population angewendet, ist **diese populationsweite Anwendung eine eigene Szenarioentscheidung** und nicht die unveränderte Versuchsanordnung des Papers.

Die Auswirkungen längerer Liegezeiten sollten gesondert geprüft werden. Ein Dreitagesprofil kann einen Bestand jenseits seiner dritten Klasse definitionsgemäß nicht abbilden. Die Länge dieses Verteilungsschwanzes ist daher eine andere Unsicherheitsdimension als allein der Erstabholanteil.

## 7. Hinweise für Warteschlange, Fachbelegung und Auswertung

Die nachfolgenden Punkte sind **eigene Umsetzungshinweise**, keine Behauptung über bereits vorhandenen oder getesteten Code.

### Ereignislogik

Ein Paket belegt ein Fach erst ab tatsächlicher Einlagerung. Wartet es wegen fehlender Kapazität außerhalb der Station, zählt es zur Warteschlange, nicht zum belegten Fachbestand. Sein Abhol- beziehungsweise Freigabeprozess darf nicht vor der Einlagerung ablaufen.

Bei gleichen Zeitstempeln muss eine eindeutige Reihenfolge gelten, etwa Freigabe vor erneuter Belegung. Bei einer täglichen statt ereignisbasierten Simulation müssen insbesondere Tagesanfang, Zustellzeitpunkt und Tagesende voneinander unterschieden werden. Dieselbe Tagesverteilung kann bei anderer Ereignisreihenfolge zu anderen Belastungsspitzen führen.

Die Warteschlangenregel bleibt eine eigenständige Modellannahme. Aus einem Abholprofil folgt nicht, ob überzählige Pakete warten, umgeleitet oder abgewiesen werden und wann ein neuer Zustellversuch stattfindet.

### Kapazitätsdaten

Für geplante Fachzahlen aus OSM sind tatsächliche Verfügbarkeit, Vollständigkeit und Größenaufteilung im verwendeten Datenauszug zu prüfen. Ein vorhandener Stationsstandort ist für sich genommen kein Nachweis über dessen Fachzahl. Fehlende oder geschätzte Werte sollten in der Eingabedatei als solche erkennbar sein.

Die Abholliteratur ersetzt keine Prüfung dieser Kapazitätsdaten. In dieser Notiz wurden keine OSM-Daten abgefragt und keine stationsspezifischen Fachzahlen validiert.

### Mindestprüfungen vor Dashboard und Ergebnisinterpretation

| Prüfung | Erwartetes Verhalten |
|---|---|
| Wahrscheinlichkeiten | Nicht negativ; Summe gleich eins |
| Paketstatus | Jedes Paket befindet sich zu einem Zeitpunkt in genau einem Zustand |
| Zeitkonsistenz | Keine Freigabe vor Einlagerung; keine unbeabsichtigte Verschiebung um einen ganzen Tag |
| Kapazität | Belegung bleibt zwischen null und der verfügbaren kompatiblen Fachzahl |
| Restbestand | Korrekte Übernahme zwischen Tagen; keine doppelte Freigabe |
| Reproduzierbarkeit | Gleiche Eingaben und Zufallsstartwerte erzeugen gleiche Ergebnisse |
| Rand des Simulationszeitraums | Noch belegte Fächer und wartende Pakete werden ausgewiesen, nicht stillschweigend entfernt |

Für Belegungsdatei und Dashboard sind insbesondere zeitlicher Fachbestand, verfügbare Kapazität, wartende Pakete, nicht erfolgreiche Einlagerungen und Lagerdauerverteilung relevant. Werden Kundenabholung und Betreiberentnahme getrennt modelliert, sollten sie auch getrennt ausgewertet werden.

Die vorgesehene Reihenfolge bleibt: **zuerst Tests, dann Zustands- und Warteschlangenlogik, Kapazitätsdaten, Belegungsdatei und Dashboard**. Diese Notiz dokumentiert dafür die fachliche Grundlage; sie implementiert diese Komponenten nicht.

## 8. Formulierungsvorschlag für die Methodendokumentation

Der folgende Text ist ein eigener Formulierungsvorschlag, kein wörtliches Zitat:

> Die Simulation verwendet das Referenzprofil `id` nach Sailer, Klein und Steinhardt (2026, Tabelle 2). Es wird einheitlich auf alle Stationen angewendet und als literaturgestützte Modellannahme, nicht als lokale empirische Kalibrierung, behandelt. Die Zuordnung der Tagesklassen zur Ereigniszeit wird explizit festgelegt. Die Ergebnisse beschreiben die Fachbelegung; Kundenabholung und Betreiberentnahme werden ohne zusätzliche Daten nicht getrennt identifiziert. Stationsspezifische Zufallsunterschiede werden in der Baseline nicht unterstellt.

Eine Sensitivitätsanalyse sollte in der späteren Methodendokumentation nur dann als durchgeführt beschrieben werden, wenn sie tatsächlich gerechnet und ausgewertet wurde.

## 9. Quellen und Fundstellen

### S1

**Sailer, D., Klein, R., & Steinhardt, C. (2026).** *Dynamic Demand Management for Parcel Lockers*. **Transportation Science**, online veröffentlicht am 28. August 2026.

- DOI und Volltext: <https://doi.org/10.1287/trsc.2024.0871>
- Geprüfte Verlagsfassung: <https://pubsonline.informs.org/doi/10.1287/trsc.2024.0871>
- Maßgebliche Fundstellen: Abschnitt 3.1.1 für Zeitdefinition und Freigabe; Abschnitt 5.1.2 und Tabelle 2 für die numerischen Profile.

### S2

**Hovi, I. B., Pinchasik, D. R., Dong, B., Strømstad, H., & Brunstad, Ø. L. (2023).** *Parcel lockers as delivery solution – Usage patterns, experiences and effects of network expansions*. TØI Report 1959/2023. Institute of Transport Economics, Oslo. Hauptbericht auf Norwegisch, englische Zusammenfassung verfügbar.

- Publikationsseite: <https://www.toi.no/publications/parcel-lockers-as-delivery-solution-usage-patterns-experiences-and-effects-of-network-expansions-article38266-29.html>
- Englische Zusammenfassung: <https://www.toi.no/getfile.php/1375937-1688040469/Publikasjoner/T%C3%98I%20rapporter/2023/1959-2023/1959-2023_Summary.pdf>
- Geprüfte Fundstelle für die Abholkennzahlen: englische Zusammenfassung, Seite iv, „Pick-up times“.

### S3

**Morganti, E., Seidel, S., Blanquart, C., Dablanc, L., & Lenz, B. (2014).** *The Impact of E-commerce on Final Deliveries: Alternative Parcel Delivery Services in France and Germany*. **Transportation Research Procedia, 4**, 178–190.

- DOI: <https://doi.org/10.1016/j.trpro.2014.11.014>
- Institutioneller Publikationsnachweis: <https://elib.dlr.de/94960/>
- Geprüfter Volltext auf ResearchGate: <https://www.researchgate.net/publication/273834549_The_Impact_of_E-commerce_on_Final_Deliveries_Alternative_Parcel_Delivery_Services_in_France_and_Germany>
- Fundstelle: Abschnitt 5.1, Seite 186; die 70-%-Angabe wird dort auf DHL (2011) zurückgeführt.

### S4

**Schnieder, M., Hinde, C., & West, A. (2021).** *Combining Parcel Lockers with Staffed Collection and Delivery Points: An Optimization Case Study Using Real Parcel Delivery Data (London, UK)*. **Journal of Open Innovation: Technology, Market and Complexity, 7**(3), 183.

- DOI: <https://doi.org/10.3390/joitmc7030183>
- Institutioneller Nachweis: <https://repository.lboro.ac.uk/articles/journal_contribution/Combining_parcel_lockers_with_staffed_collection_and_delivery_points_an_optimization_case_study_using_real_parcel_delivery_data_London_UK_/19651035>
- Geprüfter Volltext auf ResearchGate: <https://www.researchgate.net/publication/367461156_Combining_Parcel_Lockers_with_Staffed_Collection_and_Delivery_Points_An_Optimization_Case_Study_Using_Real_Parcel_Delivery_Data_London_UK>
- Maßgebliche Fundstelle: Abschnitt 3.1, Seite 5, für die Übernahme der 70-%-Angabe und die Fortsetzungsannahme zum Restbestand.

### S5

**Sethuraman, S., Bansal, A., Mardan, S., Resende, M. G. C., & Jacobs, T. L. (2024).** *Amazon Locker Capacity Management*. **INFORMS Journal on Applied Analytics, 54**(6), 455–470.

- DOI: <https://doi.org/10.1287/inte.2023.0005>
- Geprüfte Verlagsfassung: <https://pubsonline.informs.org/doi/full/10.1287/inte.2023.0005>
- Freier Preprint: <https://arxiv.org/abs/2312.06579>
- Maßgebliche Fundstelle: Abschnitt „Package Dwell Time Probability Estimation“.

---

**Zusammenfassung:** Die Baseline ist als veröffentlichte Modellannahme begründbar. Die stationsweise 40-%-/8-Prozentpunkte-Beta-Verteilung ist dagegen eine eigene, bislang unkalibrierte Erweiterung. Zeitdefinition, Freigabegrund und längere Liegezeiten müssen bei der Interpretation der Ergebnisse ausdrücklich berücksichtigt werden.
