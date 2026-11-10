# HAGRID–MATSim–jsprit: geprüfter Implementierungsstand

**Verständlicher Abschluss und Einstieg:** [HAGRID und jsprit einfach erklärt](2026-10-09-hagrid-jsprit-abschluss-einfach.md) enthält Ablauf, Annahmen, behobene Fehler, Messgrenzen und die offenen Schritte in einfacher Sprache. Lokal geprüfter Implementierungsstand; Produktionsfreigabe und Git-Integration bleiben offen.

Stand: 2026-10-09, Worktree codex/zone-router-fixes. Dies ist der laufend ergänzte Ergebnisbericht aus Aufgabe 18. Lokale Prüfungen, Referenzmessungen und Produktionsabnahme bleiben ausdrücklich getrennt. Der Gesamtplan ist noch nicht abgeschlossen.

## Aktueller Stand: gemeinsame Konfigurationsdatei (K277–K306)

**Bedarf, Planung und Scoring verwenden jetzt dieselbe konfigurierbare Sieben-Stunden-Tourregel.**
Die alte unabhängige 7,5-Stunden-Regel ist entfernt; auch modular gilt ohne explizite Sonderregel sieben Stunden.
Die [einfache Anleitung zur gemeinsamen Konfiguration](2026-10-09-hagrid-gemeinsame-konfiguration.md)
erklärt die sortierte Datei, alle Bereiche, Vorrang und Einheiten.

- Eine UTF-8-Datei für HAGRID-eigene Tour-, Scoring-, Supply-, Anbieter-, Fahrzeug-, DRT-, Shared-Use-, Modular-, LMD-, Cache-, Ressourcen- und Pfadeinstellungen.
- Unveränderliche aufgelöste Werte, Dateiprüfsumme und Herkunft; bekannte/prüfbare Schlüssel und CLI-Vorrang.
- Review-Lücken bei wirkungslosen CLI-Werten, Listen, Alias-Priorität, Windows-Dateipfaden, inaktiven Paketregeln und früher Validierung repariert.
- **Frisch 1466/1466 kleine HAGRID-Tests aus 175 inventarisierten Quellen bestanden**, einschließlich Paketbau.
  Zusätzlich besteht genau dieses neue Paket eigene Service-/Shipment-Replays mit Kosten 100,25/94 und zwei tatsächlichen Iterationen.
- Eigene Läufe: ein Prozessor, Maven 512 MiB, Tests/Replay 384 MiB, gemeinsame Sperre, Live-Reserve 6 GiB.
  Kleinster gemessener freier RAM über diesen Paketnachweis und vier Replay-Prozesse: 19.16 GiB.
  Eine frühere Gegenprobe wurde wegen Hintergrund-CPU vor JVM-Start zurückgestellt; keine fremden Prozesse verändert.
- Vroom/Valhalla bleiben vorrangig. Produktion, größere Parallelität, Zeitprofilpolitik, repräsentative Daten und Produktions-RAM-Kalibrierung bleiben offen.
  Die historischen 19 nativen jsprit-2.0-Abweichungen sind weiterhin separat zu bewerten; sie wurden hier nicht erneut freigegeben.

Aktuelles Paket SHA-256: `5DCE49EBC1F2D3EAC9E2D6EF2B675692CE5593B3BB756EDEC43BE4DA07F81769`. Keine allgemeinen Laufzeit-/Qualitätsvorteile aus diesem Refactor behauptet.
Code weiter im Worktree, kein Commit/Push/Merge. Die nachfolgenden älteren Nachträge sind historische Nachweise.


## Historischer Nachtrag: zentrale Solver-Grenze und native Ressourcen (K252–K276)

**Die Konfigurationslücke ist repariert; der gesamte Optimierungsplan bleibt offen.**
Die vollständige einfache Erklärung mit Startbeispielen, Regeln, Prüfungen und offenen Punkten steht in
[Zentrale Solver-Konfiguration](2026-10-09-hagrid-zentrale-solverkonfiguration.md).

- Sieben Solver-Stunden zentral konfigurierbar bis Initialplanung, Replanning, Lausitz-Wellen/Retimer/Cache und Metadaten.
  Scenario-Builder überträgt denselben expliziten Wert an Bedarf und erzeugten Simulationsstart.
  Alte 7,5-Stunden-Bedarfsregel bleibt unabhängig; MAXROUTEDURATION nur veralteter Kompatibilitätsalias.
- Neuer nativer Worker-Befund mit zwei echten Gegenproben repariert: MATSim CarriersUtils schließt seinen eigenen Executor auf Erfolg und Fehler.
- Frisch 1.424/1.424 HAGRID-Tests aus 166 kleinen Quellen bestanden; exakt neues Paket im Service-/Shipment-Replay bestanden.
  Native Freight-Wiederholung: 272 Fälle, 244 bestanden, dieselben 19 Fehlschläge, neun bereits deaktiviert. Keine Referenzen umgeschrieben.
- Vier native Fehlschläge separat kausal auf jsprit 1.8/2.0 zurückgeführt, identische 385 MATSim-Klassendateien.
  Kosten-/Fahrzeug- und verbleibende Ereignisabweichungen brauchen weiterhin individuelle Bewertung.
- Service-Hotspot diagnostisch eingegrenzt: isoliert etwa 15,6 % weniger Suchzeit / 33,5 % weniger Allokation gegenüber Release 2.0,
  gleiche 2.0-Pläne/Arbeitszähler. Kein installierter Fork und keine Shipment-/Produktionsfreigabe.
- Echte Daten, FIFO-Profilpolitik, RAM-Kalibrierung, größere Parallelität, vollständiger B0/B1/B7-Vergleich und Git-Integration bleiben offen.
  Weitere Puffer-/Bedarf-/DRT-Regeln sind noch nicht vollständig dynamisch migriert. Die alten Abschnitte unten sind historische Nachweise.

## Historischer Nachweis: kleine Abnahme und Versionsvergleich (K237–K250)

**Weitere Nachweise abgeschlossen; Produktionsfreigabe und Politik für echte schwankende Reisezeiten bleiben offen.** Produktcode und Standards unverändert. Neue Fahr-/Ladenachweise und Messwerkzeuge im Branch codex/zone-router-fixes:

- **93/93 Integrationsprüfungen:** Vier Carrier mit zwei Besitzerlabels, Pkw/Lastenrad, Services/Shipments, Network Change Event, eine Initialiteration und zwei Replanning-Gruppen. Jeder Carrier fährt in Iteration 2/3/4 auf seiner richtigen Straße und erledigt eigene Aufträge; Pickup vor Delivery und finale Kosten/Score geprüft. Statischer Ein-Bin-Hintergrund auf kleinem Netz; keine neue Zwei-Echelon-/LSP-Geschäftsregel.
- **Sechs Gegenbeispiele reparieren eine Nachweislücke:** Fehlender Carrier/Iteration/Service/Delivery, falsche Reihenfolge/Modus wurden vom alten Test übersehen. Strengerer Test weist alle zurück. Fehler im Testnachweis, kein daraus abgeleiteter Produktions-Fahrfehler.
- **17/17 XML-Ladeprüfungen:** Zwei neue Supply-Shipment-Fälle für Pkw/Lastenrad erhalten vollständige Pläne durch native Eingangsdateien, Scenario-Builder und Merge-XML. Jobidentität, Pickup/Delivery, Typ, Zeitfenster, Dauern, Abfahrt, Score und Suchmetadaten geprüft.
- **Netz/Snapshot:** Vier Event-/Turn-Restriction-Kontrollen, gleiche Netze mit anderer Einfügereihenfolge und tatsächliche Calculator-Profilentstehung ergänzt. Separate NCE-Datei im Scenario-Loader bereits geprüft.
- **Neue Suchvergleichs-Seeds:** Fünf Roots ergeben20 verschiedene Carrier-Startwerte je Jobart; 600 aktuelle/600 Referenzpläne,200 identische Vergleichstripel. Kleine Fälle mit 16 Jobs; wiederholte Gruppen sind keine unabhängigen Stichproben.
- **Isolierter jsprit 1.8/2.0-Vergleich:** Fünf gleiche Inputs, je drei frische JVM-Wiederholungen, zehn tatsächlich ausgeführte Iterationen, identische gemeinsame Abhängigkeiten/Zielfunktion. Ohne HAGRID-/MATSim-Klassen; vollständiger Integrationsvergleich bleibt offen.

**Wichtiger Profilbefund:** K238 scheitert mit 60-Sekunden-Bins an einer echten fallenden gemessenen Reisezeit auf return bei 180 Sekunden. Die strenge FIFO-Prüfung tut das Vorgesehene. Die positive Fahrabnahme verwendet ausdrücklich einen statischen 1000-Sekunden-Bin; das behebt keine allgemeinen mehrbinigen Profile. Noch zu entscheiden: streng ablehnen oder separat konfigurierte konservative FIFO-Ankunftszeit-Hülle. Diese darf Zeiten nur verlängern, muss ihren Fehler ausweisen und eine eigene Cache-/Replay-Identität erhalten. Keine stille Profiltransformation implementiert.

### Messwerte und ihre Bedeutung

K249 ist der **abschließende reparierte Versionsnachweis**: 30 Mess-JVMs plus Compiler erfolgreich. Geladene Core-JAR, alle 16 Jobs, Kapazität, Ort, Depot-Rückkehr, Horizont und gemeinsame Endkosten geprüft. Launcher bindet jetzt ResourceGuard-Prüfsumme; nicht verfügbare Allokationszähler werden -1. Fünf tatsächliche Eingangs-/Nichtüberschreib-Kontrollen bestehen ohne JVMs.

| Seed | Kosten1.8 | Kosten2.0 | Suchzeit2.0/1.8 |
|---|---:|---:|---:|
| 42 | 680 | 740 | 1.719 |
| 43 | 720 | 720 | 1.120 |
| 44 | 720 | 780 | 1.323 |
| 45 | 680 | 720 | 1.382 |
| 46 | 800 | 760 | 1.780 |

Im gepaarten Median braucht 2.0 hier **38,2 % mehr Suchzeit**, mit **5,88 % höheren Kosten** und **26,9 % mehr Caller-Allokationen**. Drei Kostenfälle schlechter, einer gleich, einer besser. Millisekundenzeiten, JIT/Hintergrundlast und kleine Inputs begrenzen die Übertragung. K244 hatte gleiche Kosten, aber anderes Zeitverhältnis 1,883; nicht zusammenmitteln. Allokation ist kein Peak-RAM. Keine pauschale Versionsbewertung/automatische Rückumstellung.

K237: gleiche exakte feste Zehn-Iterationen-Referenz, verschiedene Carrier-Seeds, unabhängig geprüfte ausgewählte Gewinner:

| Jobart | Option | Pipelinezeit-Verhältnis | Endkosten-Verhältnis | Teurere ausgewählte Paare/60 |
|---|---|---:|---:|---:|
| Services | Warmstart | 0,783 | 1,000408 | 15 |
| Shipments | Warmstart | 0,566 | 1,000143 | 21 |
| Services | Adaptiv | 0,707 | 1,000211 | 12 |
| Shipments | Adaptiv | 0,829 | 1,000000 | 6 |

Einzelne Warmstart-Kosten bis 6,48 %/6,74 % schlechter. Adaptiv spart Sucharbeit (Iterationsverhältnis 0,55/0,65); kein gleichbudgetiger Speedup. Kontext-/Referenztouraufbau, CLI-Start und Ausgabe ausgeschlossen. K206 hat andere überlappende Seeds und bleibt historisch. **Defaults bleiben aus.**

### Was jetzt noch erforderlich ist

1. **Zeitprofilpolitik:** strenge Abweisung oder separat freigegebene konservative FIFO-Hülle. Gemessene Zeiten bis dahin unverändert.
2. **Repräsentative Eingaben:** Hannover-/Lausitz-Carrier mit passendem Netz/Zeiten oder zertifizierte Replays. Keine vollständigen Fälle in den geprüften konventionellen Input-/Outputordnern gefunden.
3. **RAM-Kalibrierung/Produktionsbudget:** Carrier und gemeinsamen MATSim-/Netz-/Cache-/Ergebniszustand sowie physische/native Reserven getrennt messen. Mini-Captures sind keine Produktionsschätzung.
4. **Nach Valhalla größere Versuche:** äußere/innere Parallelität, reale Gruppen, Hannover/Lausitz und die 500-Fahrzeuge-/Hintergrundgeschwindigkeiten-Annahme. 7-h-Grenze und konfigurierbare Radobergrenze bleiben.
5. **Vollständiger B0/B1/B7-Integrationsvergleich/Git-Abnahme:** gleiche Regeln, echte Laufzeit-/RAM-/Qualitätsverteilung, Scoring/Ausgaben. Danach jsprit-interne Hotspots/Suchstrategien.

Alle eigenen JVMs: eine CPU, Test/Probe 384 MiB, Maven 512 MiB, Live-Reserve 6 GiB. K246 mindestens 9,92 GiB frei/maximale gesamte CPU 77,02 %; K249 mindestens 10,66 GiB frei, alle 31 kurzen Prozesse ohne CPU-Laufzeitstichprobe, gültige Vorabmessungen. K247 wurde vor der nächsten JVM wegen Startreserve zurückgestellt und bleibt unvollständig erhalten; K249 frischer vollständiger Nachweis. Keine fremden Prozesse verändert.

Nachweise: tools/benchmarks/results/k237-distinct-seeds-paired-20261009 und k249-core-version-final-paired-20261009; Prüf-JSONs/Logs im Fortschrittsledger. Letzter breiter Produktstand K215: 1.362/1.362 plus K216-Paket-Replay; kein Produkt-Neubau/Standardwechsel. Remote master unverändert; main älterer anderer Ausgangspunkt, kein neuer master-Rebase erforderlich. Kein Commit/Push/Merge/Automationswechsel in dieser Runde.

Die 19-Schritte-Tabelle bleibt maßgeblich. Ältere Abschnitte enthalten historische Restlisten. Einfacher Leitfaden/PDF bleiben datierter bisheriger Produktstand; diese Ergänzung enthält die späteren Nachweise.

## Bisherige Ergänzung: Messauswertung und Vergleichs-Seeds (K224–K235)

**Neu umgesetzt:** Speicherprofile lassen sich jetzt streng prüfen und verständlich zusammenfassen. Künftige Suchvergleiche verwenden verschiedene Startwerte für jeden Carrier und getrennte Startwerte für die Referenztour. Zwei Fehler in der Nachweisverarbeitung sind behoben: Ein gleichzeitig entstandener Bericht wird nicht überschrieben; eine während der Prüfung geänderte Manifestdatei wird zurückgewiesen. Fehlende CPU-Stichproben bei kurzen Prozessen werden als fehlend ausgewiesen, eine gültige CPU-Vorabmessung bleibt Pflicht.

**Frisch geprüft:** 31/31 Benchmark-Kontrollen, anschließend nochmals 31/31 in einem separaten Verzeichnis ohne lokale Messordner und ohne Produkt-JAR. Die beigefügten kleinen Testnachweise umfassen etwa 267 KiB und sind synthetische native Fälle. Ein tatsächlicher kleiner nativer Durchlauf prüft alle sechs Varianten (Services/Shipments × fest/Warmstart/adaptiv), 120 aktuelle und 120 Referenzpläne. Alle 40 Eingabe-/Referenztour-Vergleichstripel stimmen überein; mindestens 12,57 GiB RAM frei, maximale beobachtete gesamte CPU 33,78 %. Das ist ein Ein-Seed-Funktionstest mit zwei Jobs, kein neuer Qualitäts- oder Geschwindigkeitsnachweis.

**Speicherauswertung:** Die unveränderten K222-Ein-Job-Nachweise ergeben beobachtete Gesamt-JVM-Heapmaxima von 25,56/25,61 MiB und Prozessspeichermaxima von 115,16/114,18 MiB. Diese Größen sind getrennt und kein exklusiver RAM-Bedarf je Carrier. Der neue Bericht ist eine Prüfung bereits gemessener Daten, keine neue Produktionskalibrierung. Die bisherigen Produktionsstandards und das Produktpaket bleiben unverändert.

**Weiter offen:** echte repräsentative Carrier-/Netzfälle und Reserven kalibrieren → Produktionsbudget anschließen → nach Valhalla größere Parallelität vergleichen → Suchqualität/Hintergrundgeschwindigkeiten/Produktionsausgaben abnehmen → Git-Integration und später jsprit intern untersuchen. Die 19-Schritt-Checkliste unten bleibt maßgeblich.

## Wie die Integration jetzt zusammenarbeitet

1. Die bestehende Delivery-/Supply-Erzeugung prüft Modi, Depotzugänge, Flotten, Kapazitäten, Mengen und Zeitfenster. Sie führt keine neue Zwei-Echelon-Geschäftsregel ein. Reine Service- und Shipment-Carrier werden nativ unterstützt; gemischte Jobs in einem Carrier werden an der bestehenden MATSim-Adaptergrenze früh diagnostiziert.
2. Die Initialplanung behält ihren Standard von einer jsprit-Iteration und die Solver-Tourgrenze von sieben Stunden. Fahrzeuge und Wegesucher verwenden ihren tatsächlichen Netzmodus; Radtypen sind konfigurierbar auf standardmäßig 20 km/h begrenzt.
3. Der feste Replanning-Pass beginnt standardmäßig in Iteration 25 und übernimmt den Reisezeitstand aus Iteration 24 genau einmal. Nachfolgende Gruppen nutzen denselben eingefrorenen Kontext. Standardmäßig werden 16 Carrier je Gruppe verarbeitet; die äußere Solver-Ausführung bleibt zunächst seriell.
4. jsprit liefert einen Suchkandidaten. Endtouren werden auf den tatsächlichen Straßen geroutet und unabhängig auf vollständige Bedienung, Kapazität, Fenster, Flotte und Zeiten geprüft. Ein gültiger alter Plan wird im selben Kontext neu bewertet und kann bei geringeren Kosten erhalten bleiben. Suchkosten und MATSim-Score sind getrennte Größen.
5. Nur vollständig geprüfte Gruppen werden übernommen. Zertifizierte Caches berücksichtigen aktuelle Eingaben, Netz, Reisezeiten, Einstellungen und bei Warmstart die tatsächliche Starttour. Einzel-Carrier-Replays verwenden denselben Optimierungsdienst und einen gemeinsam gespeicherten exakten Kontext.

Free-Speed-/Durchschnittsgeschwindigkeiten und Network Change Events müssen fachlich zum vorgesehenen Hintergrundmodell passen. Die Annahme, dass 500 zusätzliche Fahrzeuge diese Geschwindigkeiten nicht wesentlich verändern, ist noch kein empirischer Produktionsnachweis. Variable Reisezeiten benötigen einen geprüften FIFO-Vertrag; zeitlich variable Zonennäherung und nicht belegte gemischte Zeit-/Distanzziele werden diagnostiziert.

## Was konkret sauberer und sicherer geworden ist

| Änderung | Nutzen | Bisheriger Nachweis |
|---|---|---|
| Gemeinsamer Optimierungsdienst und neutraler Kostenvertrag | Initialplanung, Legacy-Replanning, fester Pass und Lausitz teilen Suche/Prüfung; der Replanner benötigt keine konkrete Zonenklasse. | Native kleine Service-/Shipment-/Modalitäts- und Architekturprüfungen. |
| Kontextbezogene Graphen, begrenzte Caches und Router | Kein Graphaufbau je Carrier; keine unkontrollierte Sammlung alter Router/Solver. | Mehrere aufeinanderfolgende kleine Gruppen behalten eine Graph-/Routerinstanz. Repräsentatives Gesamtbudget bleibt offen. |
| Gemeinsamer CarrierBatchExecutor | Zulassung vor VRP-Aufbau, geordnete Übernahme, Reservierung bis zur Gruppenveröffentlichung, nachgewiesenes Aufgabenende bei Abbruch. | Echte kleine serielle/parallele Kostenvergleiche und Abbruch-/Lebensdauerkontrollen. Byte-Werte solcher Vertragstests sind keine Produktionskalibrierung. |
| Offline- und Erzeuger-Rückabwicklung | Spätere Supply-/Modus-/Dateifehler lassen keine halbfertigen Pläne, Carrier-Registrierungen oder Hub-/Typänderungen zurück. | Konkrete Gegenbeispiele erst fehlgeschlagen, nach Korrektur bestanden. |
| Vorbereitete Dateisätze | Alle Dateien werden vor Austausch serialisiert; Austauschfehler stellen Altdateien wieder her. | Injizierter Fehler beim zweiten Austausch und Wiederherstellungsfehler geprüft. Bei letzterem bleibt eine Sicherung mit Diagnose erhalten. Kein gemeinsamer Crash-Commit mehrerer Dateien. |
| Warmstart und adaptive Suche als Optionen | Gültige aktuelle Service-/Shipment-Touren können Startlösung sein; explizite Minimum-/Maximum-/Plateau-Regeln. | Native kleine Kontrollen. Beide Optionen bleiben standardmäßig aus. |
| Aussagekräftige Kennzahlen und Ausgabeintervalle | Vorbereitung, Suche, Endrouting, Prüfung, tatsächliche Iterationen und ausgewählter Endplan sichtbar; Zwischenoutputs konfigurierbar. | Echter kleiner Vier-Iterationen-MATSim-Lauf erhält finale Ereignisse, Pläne und beide Histogramme. |

Die Klasse Router wurde von ungefähr 735 auf 390 Zeilen verkleinert; CarrierRouter enthält jetzt benannte Schritte für Solve und Veröffentlichung mit rund 58 Zeilen. Das sind Lesbarkeitsänderungen, kein eigener Laufzeitnachweis. Routine-GC-Aufrufe und feste Pausen ohne Ressourcenfreigabe wurden entfernt; daraus wird kein allgemeiner RAM-Gewinn abgeleitet.

## Neues gemeinsames Laufbudget (K208–K214)

Vor jeder nichtleeren Carrier-Gruppe kann der Laufbesitzer nun den tatsächlichen gemeinsamen Speicherstand neu einlesen. CarrierRunBudget trennt Heapgrenze, gemeinsam belegten Heap und Heapreserve von freiem physischem RAM, physischer Reserve und nativem Startspielraum. Die kleinere positive Restkapazität begrenzt die Carrier-Reservierungen; ungültige, fehlende oder erschöpfte Messwerte führen vor dem VRP-Aufbau zu einer Diagnose.

Der Executor beansprucht zuerst exklusiv die Gruppe und liest dann genau einen Budgetstand für deren gesamte Laufzeit. Das unabhängige Review fand hierbei einen wichtigen Fehler: Ein zweiter Aufrufer konnte zuvor während der ersten Gruppe ein größeres Budget lesen und nach deren Veröffentlichung damit beginnen. K212 reproduzierte die veraltete Messung tatsächlich. Die reparierte Reihenfolge, die Freigabe nach Messfehler und die Ablehnung des gleichzeitigen Aufrufers bestehen in K214. Ein tatsächlicher nativer Zwei-Gruppen-Fall bestätigt zusätzlich, dass ein späterer Budgetstopp bereits gültig veröffentlichte Touren erhält und die nächste Gruppe vor Aufbau/Übernahme blockiert.

K214: **68/68 gezielte Tests**, sieben Rootklassen, 21,948 Sekunden; eine sichtbare CPU, Maven512/Test384MiB, mindestens 12,17 GiB RAM frei und maximale gesamte CPU59,15%, Exit0 ohne Schutzabbruch. K213 wurde zuvor wegen mehr als fünf Sekunden hohem CPU-Druck geschützt abgebrochen; daraus wird kein Testergebnis abgeleitet. Die anschließende frische breite Abnahme K215/K216 ist ebenfalls erfolgreich, siehe unten.

Der optionale JVM-Beobachter erfasst den gesamten aktuellen Heap inklusive MATSim, Kontext, Cache, veröffentlichter Pläne und noch nicht eingesammeltem Garbage. Er beweist keinen exklusiven Carrier-Peak oder eine harte RAM-Grenze. Der Laufbesitzer muss gültige physische Messungen, konservative Reserven und Carrier-Schätzwerte liefern; solche Byte-Werte sind in den Vertragstests synthetisch. Die vier bestehenden Ausführungswege können denselben Executor übernehmen, der produktive Standard bleibt seriell. Repräsentative Kalibrierung und Anschluss einer tatsächlich kalibrierten Produktionsbudgetpolitik sind weiterhin offen. ResourceGuard schützt eigene Prüfprozesse zusätzlich laufend mit der vereinbarten 6-GiB-Reserve.
## Aktuelle lokale Abnahme

K176: **1.332/1.332 Tests**, 156 kleine Rootklassen, keine Fehler oder Auslassungen, Maven verify und shaded-Paket erfolgreich. Dauer 5:12 Minuten. Mindestens 9,77 GiB physischer RAM blieben frei; maximale beobachtete gesamte CPU-Auslastung 54,17 %. Das große LmdBaselineEndToEndTest ist ausgenommen.

K177: Dasselbe Paket startet in separaten 384-MiB-/1-CPU-JVMs. Services und Shipments werden vollständig bedient, mit zwei tatsächlichen Iterationen und gemeinsamem Kontext. Geprüfte Endkosten: 100,25 beziehungsweise 94,0. Paket-SHA256: 4A3350690896FBE270C1ADBB0DB5323F003E199E5C4FC2ED78B2B4F1B2F35AEC.

K190 bestätigte anschließend die Gesamtfassung mit **1.336/1.336 Tests**, 156 kleinen Rootklassen und verify/Paketbau in 5:12 Minuten. Mindestens 11,71 GiB RAM blieben frei, die gesamte CPU erreichte maximal 80,78 %; kein Ressourcenabbruch. Der danach geprüfte Paketstand K193 besteht mit 91/91 betroffenen Tests; K194 startet Services/Shipments in separaten 384-MiB-/1-CPU-JVMs mit gleichen Endkosten 100,25/94,0, zwei tatsächlichen Iterationen und vollständiger Bedienung.

Das unabhängige Review fand keine Critical- und zwei Important-Befunde: Ein ungewöhnlich fehlgeschlagener mehrthreadiger jsprit-Lauf ließ interne Worker bestehen; eine unchecked Exception beim Wiederherstellen einer Datei konnte ihre Sicherung löschen. K191 hat beide Fehler tatsächlich reproduziert. Die Reparaturen und stärkeren Kontrollen bestehen in K192/K193: HAGRID besitzt die internen Pools, wartet bei Abbruch bis zur tatsächlichen Beendigung der Callbacks und erhält Interrupts. Fehlgeschlagene Wiederherstellungen behalten Original-Sicherungen und verhindern nicht die Wiederherstellung anderer Dateien. Rohes mehrthreadiges Algorithmus-Bauen erfordert die dokumentierte scoped API withConfiguredAlgorithm.

Die Ressourcenweitergabe wurde danach für Initial-HAGRID, Lausitz-Baseline und Modular ergänzt. Die eigene Demand-Konfiguration besitzt einen benannten resources-Abschnitt, der unveränderliche Snapshot und zentrale Resolver benutzen denselben typisierten Vertrag. Jede Initialsuche erhält die gewünschte interne Threadzahl; der äußere Grenzwert begrenzt auch größere ausdrücklich bereitgestellte Pools. Alle drei Lausitz-Simulationsaufrufe geben ihre aufgelösten Einstellungen weiter, und der Ganzdatei-Cache zertifiziert die tatsächlich benutzte Threadzahl/Obergrenze. Bestehende APIs und der Serienstandard bleiben erhalten. K200: **119/119 gezielte Tests**, einschließlich neuer Baseline-Treffer und der alten GOLDEN-/Bytekontrollen. K201 prüft zusätzlich echte Modular-Treffer erfolgreich.

CLI-Aliasse solverThreads und maxActiveSolvers sind positiv, behalten bei fehlender Angabe die Defaults und übersteuern Profil-/Run-Werte mit CLI-Vorrang. maxActiveSolvers ist eine Obergrenze; sie erzeugt keinen ungeprüften parallelen Produktionspool. Die gezielte unabhängige Ressourcen-Review bestätigt die Weitergabe und beide ursprünglichen Reparaturen ohne neue Critical-/Important-/Minor-Befunde. Sie führte selbst keine Tests aus.

**Vorheriger Produktstand K202: 1.351/1.351 Tests**, 157 kleine Rootklassen, keine Fehler/Auslassungen; Maven verify und Paketbau in 5:07 Minuten. Auto2, Maven/Test jeweils 768 MiB; mindestens 11,62 GiB RAM frei, gesamte CPU maximal 62,37 %, Exit0 ohne Ressourcenabbruch. K203 prüft genau dieses Paket erneut in separaten JVMs mit 384 MiB Heap und einer sichtbaren CPU: vollständige Services/Shipments, zwei tatsächliche Iterationen, Endkosten 100,25/94,0 und identischer Kontext. Paket-SHA256: 3AA0B685EADEF4580315B6D11FCD432E5EB7A48BCBD047192664EE174A4F71FE. Dieser frühere Paketstand ist als k202-search-policy-reference-hagrid.jar mit identischem SHA erhalten; die neue Budget-Erweiterung und deren frische Abnahme stehen im Abschnitt K208–K214. Die K206-Messungen beziehen sich weiterhin auf dieses erhaltene Referenzpaket. Das große LmdBaselineEndToEndTest bleibt ausdrücklich ausgenommen.


**Aktueller Produktstand K215: 1.362/1.362 Tests**, 158 kleine Rootklassen, keine Fehler/Auslassungen; Maven verify und Paketbau in 4:52 Minuten. Eine sichtbare CPU, Maven512/Test384MiB; mindestens 11,84 GiB RAM frei, maximale gesamte CPU64,15%, Exit0 ohne Schutzabbruch. Die vier Budget-Produkt-/Testquellen blieben bis zum Ende des Paket-Replays unverändert. Das große LmdBaselineEndToEndTest bleibt ausdrücklich ausgenommen.

K216 startet genau dieses Paket in separaten JVMs mit 384 MiB Heap und einer CPU: vollständige Services/Shipments, zwei tatsächliche Iterationen, Endkosten 100,25/94,0 und identischer Kontext e9ad9ee032269306408a58aa750b015148d816d4959d1f68855e56193ff98183. Compiler, Export und beide Replay-JVMs enden mit Exit0 ohne Schutzabbruch; mindestens 12,52 GiB RAM frei, maximale gesamte CPU29,39%. Aktueller Paket-SHA256: **461F5972F2970EE56C007778B7BD8B2ECAE7FD91E620BA9C6915E2B82348001C**. Daten: tools/benchmarks/results/k216-package-replay-20261009. Gesamtplan und repräsentative Produktions-/Kalibrierungsgates bleiben offen.
## Messung: Zonennäherung gegen exakten Cache

K179 verwendet ein kleines gemeinsames Netz mit 64 Knoten/224 Links, Integer-Zonen und 200-m-Schwelle. Je Variante: fünf Seeds 42–46, Services und Shipments mit 16/32 Jobs, fünf serielle Vierergruppen. Die ersten zwei Gruppen dienen der Aufwärmung; pro Seed wird der Median der drei übrigen Gruppen verglichen. Jede Suche hat tatsächlich zehn Iterationen. Beide Varianten bedienen sämtliche Jobs und verwenden vier Fahrzeuge je Gruppe. Alle JVMs: eine sichtbare CPU und 384 MiB Heap; Variantenreihenfolge alterniert.

| Fall, Jobs je Carrier | Exakt, Median s je Vierergruppe | Zone, Median s | Median gepaartes Zeitverhältnis Zone/exakt | Median gepaartes Endkostenverhältnis |
|---|---:|---:|---:|---:|
| Services, 16 | 0,077 | 0,160 | 2,07 | 1,070 |
| Services, 32 | 0,227 | 0,641 | 3,14 | 1,086 |
| Shipments, 16 | 0,444 | 1,321 | 2,98 | 1,085 |
| Shipments, 32 | 3,337 | 10,092 | 2,89 | 1,150 |

Das gepaarte Verhältnis wird je Seed gebildet und anschließend über Seeds zusammengefasst; es ist nicht einfach der Quotient der zwei Tabellenmediane. In allen 20 Seed-/Fallpaaren waren Zonennäherung langsamer und nachgerechnete Endkosten höher. Die zusätzlichen Solver-Allokationen lagen bei ungefähr dem 2,1- bis 2,6-Fachen. Weniger Routingberechnungen allein sind kein Geschwindigkeitsnachweis. Die Mehrzeit steckt vor allem in der Suche. Das ist ein Befund dieser kleinen, instrumentierten Fälle unter Hintergrundlast; keine allgemeine Produktionsaussage.

**Entscheidung:** Exaktes Routing mit gewöhnlichem Cache bleibt Standard. Zonennäherung bleibt eine ausdrücklich zu prüfende Option. Einen allgemeinen Speedup oder Qualitätsgewinn behaupten wir nicht. Veränderungen am schnellen Abfragepfad benötigen einen eigenen identischen Vorher-/Nachher-Vergleich.

Rohdaten und fünf Seedpaare: tools/benchmarks/results/k179-zone-fixed-pairs-20261009/measurements.csv und paired-summary.json. Manifest mit Paket-/Probe-SHA und Ressourcenbeobachtungen: manifest.json. 40 Mess-JVMs plus Compiler, 200 Gruppen/800 Carrier-Suchen; mindestens 12,73 GiB physischer RAM frei, maximale gesamte CPU 43,48 %, kein Ressourcenabbruch.

Der frühere winzige Warmstartvergleich K139 zeigte gleiche Endkosten, aber keinen Laufzeitgewinn. Der isolierte Versionsvergleich B1 hatte wechselnde Kosten-/Zeitkompromisse; Seed 46 hat unterschiedliche tatsächliche Budgets und ist kein Speedup-Nachweis. Diese Versuche werden nicht zu einer pauschalen Erfolgsquote zusammengezogen.

## Erste Wirkung des vorbereiteten Zonenindexes (K189)

Ein direkter alter/neuer Paketvergleich mit fünf Seeds und jeweils 16 Service-/Shipment-Jobs hält Nachfrage, Iterationen, Flotte, Zonenregel und Messauflösung gleich. Bei jedem der 50 Gruppenpaare sind nachgerechnete Endkosten und Fahrzeugzahl exakt identisch. Die drei gemessenen Gruppen nach zwei Aufwärmgruppen werden je Seed zusammengefasst.

| Fall | Gepaarter Median neue/alte Laufzeit | Bereich über Seeds | Neue/alte Endkosten | Allokationsverhältnis |
|---|---:|---:|---:|---:|
| Services, 16 | 0,845 | 0,685–0,961 | 1,000 | 0,956 |
| Shipments, 16 | 0,880 | 0,682–1,175 | 1,000 | 0,944 |

Das entspricht rund 16 % beziehungsweise 12 % weniger Zeit im gepaarten Median; ein Shipment-Seed ist langsamer. Das belegt eine Wirkung dieses vorbereiteten Abfragepfads in den kleinen Fällen, keine allgemeine Überlegenheit der Zonennäherung gegenüber exaktem Routing. Die endgültige gemeinsame Metadatenspeicherung bekommt einen eigenen weiteren Speicher-/Laufzeitvergleich; K189 ist der Nachweis der vorherigen Indexfassung.

K189: 20 Mess-JVMs plus Compiler, 100 Gruppen/400 Carrier-Suchen, eine sichtbare CPU/384 MiB. Mindestens 12,57 GiB physischer RAM frei, gesamte CPU maximal 42,89 %. Daten: tools/benchmarks/results/k189-zone-index-paired-20261009. Die alte Vergleichs-JAR ist unverändert mit SHA erhalten.

## Vergleich der abschließenden gemeinsamen Metadaten (K196)

Die endgültige Indexfassung teilt unveränderliche Metadaten je Zone/Eintritts-/Austrittskomponente. Der neue direkte Paketvergleich benutzt dieselben fünf Seeds, Nachfrage, Zehn-Iterationen-Budgets, Flotte und Zonenregel wie zuvor. Pro Variante werden zwei Gruppen aufgewärmt und drei gemessen. Sämtliche 50 Gruppenpaare besitzen exakt gleiche geprüfte Endkosten und Fahrzeugzahlen.

| Fall, 16 Jobs je Carrier | Neue/alte Zeit, gepaarter Median | Bereich über Seeds | Neuer/alter Kontextgraph, Median Byte | Neue/alte Solver-Thread-Allokationen |
|---|---:|---:|---:|---:|
| Services | 0,781 | 0,630–0,910 | 404.920 / 406.192 | 0,954 |
| Shipments | 0,699 | 0,663–0,908 | 503.432 / 504.704 | 0,944 |

Das sind rund **22 % beziehungsweise 30 % weniger Zeit** in diesen kleinen Zonenfällen gegenüber der alten Implementierung; kein Seed-Median wurde langsamer. Der gemeinsam gehaltene Kontext spart auf diesem Netz 1.272 Byte. Gemessen sind eindeutige erreichbare Objekte einschließlich Netz, Snapshot, Anbieter und Cache; Class-Objekte sind ausgenommen. Separat erfasste kanonische Carrier/veröffentlichte Pläne schließen diesen Kontext per Objektidentität aus. Das ist keine Messung aller nativen MATSim-Basisobjekte oder eines exklusiven transienten Carrier-Peaks.

Die Zeit ist die Summe der gemessenen Optimierungsphasen der vier Carrier; Aufbau des gemeinsamen Kontexts/Anbieters, Objektgraph-Messung, Gruppenveröffentlichung und CLI-Start sind darin nicht enthalten. Es ist keine vollständige HAGRID-End-to-End-Wallzeit. Profiling, JIT und Hintergrundlast begrenzen den Vergleich. K189 und K196 sind getrennte Messungen unterschiedlicher Fassungen/Instrumentierung; deren Prozentwerte werden nicht zusammengerechnet. Das Ergebnis ändert nicht den schlechteren Zonen-/Exakt-Vergleich K179 oder den exakten Standard.

K196: 20 Mess-JVMs plus Compiler, 100 Gruppen/400 Carrier-Suchen, eine CPU/384 MiB, mindestens 12,29 GiB RAM frei, gesamte CPU maximal 35,08 %, kein Ressourcenabbruch. Daten: tools/benchmarks/results/k196-zone-context-paired-20261009. Beide Vergleichspakete bleiben mit SHA reproduzierbar erhalten: k179-reference-hagrid.jar und k193-zone-context-current-hagrid.jar im Fortschrittsverzeichnis. Der zweite SHA ist E6197960030F08545E655E67E0AE5AF48948F523345682B66A6D86BE6673137E.
## Kleine Warmstart-/Suchbudgetvergleiche (K206)

Drei Varianten verwenden denselben exakten Cache, dieselbe Nachfrage/Flotte und dieselben zertifizierten alten Touren: feste zehn Iterationen, Warmstart mit zehn Iterationen sowie adaptive Suche mit Minimum 5/Maximum 10/Plateau 3. Jede Variante erzeugt die alte Tour zunächst mit einem separaten festen Referenzkontext und Seedoffset 100000. Aktuelle Kontexte sind jeweils passend zur tatsächlichen Konfiguration eingefroren; kein abweichender Suchvertrag wird übergangen.

Pro Variante: fünf Root-Seeds 42–46, Services/Shipments mit 16 Jobs, fünf serielle Vierergruppen; zwei Aufwärmgruppen. K206 hat 600 aktuelle und 600 Referenzpläne erzeugt; sämtliche 200 Dreierpaare besitzen identische kanonische Eingaben/Altplan-Hashes/Altkosten. Sämtliche aktuellen Kandidaten und ausgewählten publizierbaren Gewinner bestehen die unabhängige Prüfung. Warmstarts sind tatsächlich ACCEPTED; feste Varianten führen zehn Iterationen aus, adaptive Varianten fünf bis zehn.

| Jobs | Variante gegen feste Referenz | Gepaarte Pipeline-Zeit | Ausgewählte Endkosten, gepaarter Median | Tatsächliche Iterationen, Verhältnis | Teurere ausgewählte Tourpaare /60 |
|---|---|---:|---:|---:|---:|
| Services | Warmstart | 0,852 | 1,00690 | 1,000 | 33 |
| Shipments | Warmstart | 0,823 | 0,99392 | 1,000 | 0 |
| Services | Adaptiv | 0,752 | 1,00021 | 0,625 | 12 |
| Shipments | Adaptiv | 0,743 | 1,00000 | 0,550 | 6 |

Verhältnisse kleiner eins bedeuten weniger Zeit/Kosten/Arbeit. Die Zeit umfasst die Optimierungsphasen plus Altplan-/Kandidatenauswahl, Vorbereitung zur Veröffentlichung und erneute unabhängige Endprüfung. Aufbau der beiden gemeinsamen Kontexte, Erzeugung der Referenztouren, CLI-Start und Ausgabe sind ausgeschlossen. Das ist keine vollständige MATSim-End-to-End-Zeit.

**Folgerung:** Warmstart spart hier etwa 15 %/18 % Zeit. Bei Services sind ausgewählte Endkosten im gepaarten Median etwa 0,69 % höher; ein einzelnes gemessenes Tourpaar liegt bis 3,16 % über der festen Referenz. Bei Shipments sind sie im Median etwa 0,61 % niedriger; kein gemessenes ausgewähltes Paar wurde schlechter. Adaptive Suche spart hier etwa 25 %/26 % Pipelinezeit, führt aber weniger Sucharbeit aus. Der Shipment-Median von 1,0 verdeckt sechs leicht schlechtere Einzelpaare (maximal 0,0501 %); bei Services sind zwölf Paare schlechter (maximal 0,0818 %). Beide Optionen bleiben deshalb **standardmäßig aus**. Kein pauschaler Qualitäts- oder Geschwindigkeitsgewinn und keine produktive Regel aus dieser Stichprobe.

Die 60 Tourpaare pro Tabellenzeile enthalten drei Wiederholungsgruppen. Zusätzlich teilen benachbarte Root-Seeds durch die bestehende Ableitung seed+carrierIndex mehrere Carrier-Seeds: acht verschiedene Start-Seeds statt 20. Das unabhängige Werkzeugreview bewertet diese Diversitätsgrenze als Minor; keine Critical-/Important-Befunde. Die Mediane sind deskriptiv, keine unabhängige statistische Stichprobe oder Signifikanzprüfung. Die größere Kampagne muss eindeutig getrennte Seeds aus (Rootseed,Carrierindex) ableiten.

K206: 30 serielle Mess-JVMs plus Compiler, jeweils eine CPU/384 MiB, mindestens 12,12 GiB RAM frei, maximale gesamte CPU 63,40 %, kein Ressourcenabbruch. Daten: tools/benchmarks/results/k206-search-policies-paired-20261009, inklusive Manifest, CSV und paired-summary.json. K207 bestätigt, dass fehlende/doppelte Zeilen, zu wenige Iterationen, abweichende Altplan-Zertifikate, NaN-Kosten und zurückgewiesene Warmstarts keine Erfolgsauswertung erzeugen. Die Ein-Seed-Vorprüfung wird als Vergleichszusammenfassung ebenfalls abgelehnt. Das hierfür gemessene K202-Paket bleibt bytegleich als k202-search-policy-reference-hagrid.jar erhalten; spätere Budgetänderungen sind separat geprüft.

## Messpfad für exportierte Carrier-Fälle (K217–K223)

run-replay-memory-profile.ps1 kann jetzt einen bereits zertifizierten Service-/Shipment-Replayfall auswerten. Er benutzt CarrierReplay.replay aus dem tatsächlichen Paket unverändert, einschließlich kanonischer Eingangs-/Altplan-/Netz-/Reisezeitprüfung. Eingaben und Paketkennung werden vor und nach jedem Lauf geprüft; keine Änderungen an Nachfrage, Starttour, Konfiguration oder Seeds. Jede Wiederholung startet eine neue JVM mit einer CPU und 384 MiB Heap unter ResourceGuard. Ergebnisse erscheinen mit Rohdaten/Prüfsummen/Ressourcenmanifest und erst nach vollständigem Abschluss als CSV.

Während Valhalla: höchstens vier verschiedene Fälle und drei Wiederholungen, 128 Jobs, ein interner Solver-Thread, höchstens 20 wirksame Suchiterationen; Payloaddateien maximal 1MiB, Kontextarchiv maximal 8MiB komprimiert/32MiB expandiert. Größere Fälle ergeben vor der JVM eine Diagnose; Limits werden nicht automatisch erhöht. Gewünschte und tatsächlich wirksame Iterationsgrenzen werden getrennt dokumentiert.

**Tatsächlich reparierter Messwerkzeugfehler:** Ein adaptives Maximum kann das Manifest-Budget ersetzen. K220 reproduziert beide Richtungen mit sauber exportierten Fällen: Manifest2/adaptiv21 wurde zu spät gestoppt, Manifest2/adaptiv8 fälschlich zurückgewiesen. K222 bestätigt die Reparatur: 21 vor jeder JVM abgewiesen, 8 korrekt ausgeführt/gemessen. Ein vorangehender erlaubter Versuch K221 wurde wegen Hintergrund-CPU-Druck nach dem Compiler und vor dem Replay zurückgestellt; daraus wird kein Erfolg abgeleitet. Der vom Review zusätzlich benannte Heap-Beobachter wird vor der getrennten Graphmessung vollständig beendet, Fehler geprüft und Peak eingefroren; hierfür wird kein gesonderter deterministischer RED-Nachweis behauptet.

K222 prüft anschließend vier frische kleine Messungen, je zwei Wiederholungen der K216-Service-/Shipment-Fälle. Alle bedienen ihren einen Job vollständig, führen zwei Iterationen aus und behalten die geprüften Kosten100,25/94,0.

| Erfasster winziger Fall | Gesamter JVM-Heap, beobachtete Peak-Spanne in Byte | Erreichbarer zurückgegebener Ergebnisgraph in Byte |
|---|---:|---:|
| Service, 1 Job | 26.543.304–26.796.704 | 4.080 |
| Shipment, 1 Job | 26.116.960–26.850.792 | 4.472 |

Die Heapwerte sind keine exklusiven Carrier-Peaks oder physischer Prozess-RAM. Sie enthalten JVM-/gemeinsamen Zustand, Garbage und Profiling; 5ms-Sampling kann kurze Maxima übersehen. Die separate Ergebnisgraphgröße wird nach bereits geschlossenem Replaykontext gemessen, enthält also weder alle MATSim-Basisdaten noch eine gesamte gestagte Gruppe. Keine erzwungene GC. Laufzeit umfasst Eingangs-/Kontextwiederherstellung plus Optimierung/Prüfung, jedoch keinen JVM-Start/Probeaufbau oder die separate Graphmessung. Caller-Allokationen, GC-Zähler/Zeit und tatsächliche Iterationen sind zusätzliche getrennte Werte; Allokation ist kein gehaltener Heap. Diese vier synthetischen Mini-Captures sind nur eine Funktionsabnahme des Werkzeugs, keine repräsentative Speicher-Kalibrierung, kein Produktionsreservierungswert und kein Beschleunigungsnachweis.

K223: neun echte Abweisungskontrollen bestehen. Fehlende/geänderte Payloads, zu viele Iterationen/Jobs, zwei interne Threads, doppelte Fälle, übergroße Payload und vorhandenes Ausgabeverzeichnis starten keine Mess-JVM. Eine semantisch geänderte Service-Dauer mit neu berechneter Payload-Prüfsumme wird weiterhin vom nativen kanonischen Zertifikat abgewiesen; kein Erfolgs-CSV. Originalfälle und alte Ausgaben bleiben unverändert.

K222 Messungen: Compiler plus vier Replay-JVMs, mindestens 12,92 GiB RAM frei, maximale gesamte CPU35,49%, Exit0 ohne Ressourcenabbruch. Daten: tools/benchmarks/results/k222-replay-memory-20261009; adaptive Nachweise und Gegenproben im Fortschrittsverzeichnis. Paket bleibt bytegleich K215, keine Produktquellen oder Defaults geändert. Repräsentative reale Capture-Auswahl, Kalibrierung, Produktionsanschluss und größere Parallelität bleiben offen.

## Was noch notwendig ist

- Aufgabe 14: Die zentrale Grenze/Threadzahl ist für alle vier Ausführungswege angeschlossen; der gemeinsame Laufbudgetvertrag wird je Gruppe neu eingelesen und ist lokal geprüft. Repräsentative Carrier-Schätzwerte und Reserven kalibrieren und die Produktionsbudgetpolitik damit aktiv anschließen. Serienstandard bleibt; größere äußere/innere Parallelitätsversuche warten wegen Valhalla.
- Aufgaben 15/16: Die kleinen gepaarten Vergleiche mit 16 Jobs sind abgeschlossen und zeigen konkrete Zeit-/Qualitätskompromisse. Repräsentative Daten mit vielfältigeren unabhängigen Carrier-Seeds vergleichen; keine automatische Aktivierung. Defaults bleiben aus.
- Aufgabe 18: Aktuelle breite Regression, Paketabnahme und unabhängiges Review sind abgeschlossen. Repräsentative Hannover-/Lausitz-Regime-, Reisezeit-/Depot-/Engpass-Abnahme sowie ein sauber getrennter B0/B1/B7-Vergleich einschließlich isoliertem jsprit-Versionseffekt bleiben offen. Erhaltene veraltete öffentliche Adapter sind ausdrücklich dokumentierte Kompatibilität, keine zweite aktive Suchimplementierung.
- Aufgabe 19: Erst nach lokaler Integrationsabnahme jsprit intern profilieren und offizielle Quellen/Beispiele versionsbezogen analysieren. Noch keine Änderung am jsprit-Repository.

Für die Produktionskampagne sind der maßgebliche Netz-/NCE-/Reisezeitdatensatz, passende Nachfrage und Flotte, konkrete Hannover-/Lausitz-Regime, Seeds und gleiche Suchbudgets zu fixieren. Hier wird keine Aussage erfunden, dass vorhandene Produktionsdateien fehlen oder eine noch nicht ausgeführte Kampagne bestanden hat.

Separater nativer Befund: MATSim GeoFileWriter.writeGeometries schließt seine SHP-/GeoPackage-Datastores nicht; DrtZonalWaitTimesAnalyzer ruft diesen Pfad auf. Das ist eine plausible Ursache der beobachteten GeoTools-Finalizerwarnung in kleinen DRT-Läufen. Die Zuordnung der konkreten Warnung ist eine Inferenz; der externe MATSim-Checkout wurde dafür nicht verändert.

Valhalla hat weiterhin Vorrang. Eigene Prüfungen werden mit ResourceGuard gestartet und abgebrochen, falls die 6-GiB-Reserve oder die vereinbarte CPU-Druckgrenze verletzt wird. Keine fremden Prozesse werden verändert.