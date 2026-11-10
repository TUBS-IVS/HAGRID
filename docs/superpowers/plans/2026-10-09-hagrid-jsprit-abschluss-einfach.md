# HAGRID und jsprit einfach erklärt

> **Aktualisierung 10.10.2026:** Die folgenden Testzahlen und Paketnachweise vom 09.10. sind historische Belege. Seitdem wurde die Audit-Serie T01–T16 im Quellcode bearbeitet; dieser neue Stand ist noch UNVERIFIED und nicht vollständig abgenommen. Aktueller Überblick und offene Pflichtprüfungen: [Audit und Refactor einfach erklärt](2026-10-10-hagrid-audit-fixes-ergebnisse.md). Die automatische Ressourcenüberwachung bleibt auf Nutzerwunsch ausgeschaltet; Tests behalten den Ressourcenschutz.


Stand: 9. Oktober 2026. Aktuelle Ergänzung: gemeinsame Konfiguration und frische kleine Paketabnahme K277–K306. Produktion bleibt offen.

**Die Integration wurde umfangreich aufgeräumt, vereinheitlicht und in kleinen Tests geprüft. Die vollständige Produktionsfreigabe steht noch aus.** Aktuell bestehen 1466 von 1466 Tests aus 175 kleinen Testquellen einschließlich Paketbau und anschließendem Replay genau dieses Pakets. Der aktuelle Konfigurationsstand steht direkt unten; ältere Mess-/Paketdetails in späteren Abschnitten sind historische Nachweise.

Zum Lesen reichen zuerst die Abschnitte 1, 3, 9 und 11. Die übrigen Abschnitte erklären die Einzelheiten. Technische Nachweise und sämtliche früheren Zwischenstände stehen im [Ergebnisbericht](2026-10-07-hagrid-jsprit-implementation-results.md) und in der [19-Schritte-Checkliste](2026-10-08-hagrid-jsprit-open-checks.md).

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


## 1. Was wir erreicht haben

- **Ein gemeinsamer Weg zum Plan:** Hannover-Initialplanung, Lausitz und Replanning benutzen denselben Optimierungsdienst mit anschließender Ergebnisprüfung.
- **Klarere Zuständigkeiten:** Konfiguration, Netz, Reisezeiten, Kosten, Zonen, Suche, Prüfung und Veröffentlichung sind getrennte Bausteine. Der Replanner muss keine konkrete Zonenklasse mehr kennen.
- **Mehr Schutz vor falschen Ergebnissen:** Veraltete Cachetreffer, unpassende Netze, ungültige Touren und unvollständige Gruppen werden diagnostiziert. Ein billiger, aber ungültiger Plan wird nicht übernommen.
- **Kontrollierte Ressourcen:** Solver entstehen erst nach Zulassung. Reservierungen gelten bis zur Gruppenübernahme; Worker müssen tatsächlich beendet sein. Ein gemeinsames Laufbudget kann vor jeder Gruppe frisch geprüft werden.
- **Messbarkeit:** Suche, Routing, Prüfung, tatsächliche Iterationen und endgültig ausgewählte Touren sind getrennt sichtbar. Einzelne Carrier können mit ihrem exakten Eingabekontext exportiert und erneut geprüft werden.

Die verifizierten Produktquellen liegen im separaten Arbeitsverzeichnis `C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID` auf Branch `codex/zone-router-fixes`. **Sie sind noch nicht in den normalen Checkout integriert, committed, gepusht oder gemergt.** Im normalen Checkout liegen die verständlichen Dokumentationskopien. Das Arbeitsverzeichnis muss deshalb erhalten bleiben.

## 2. Wie MATSim, jsprit und HAGRID zusammenarbeiten

**MATSim bildet das Verkehrsnetz und die Fahrten ab. jsprit verteilt Aufträge auf Fahrzeuge und bestimmt die Reihenfolge der Stopps. HAGRID verbindet Nachfrage, Konfiguration, Verkehrsgrundlage, Optimierung und Ergebnisübernahme.**

Der vereinbarte Ablauf ist erhalten:

1. Das normale Verkehrsmodell liefert die Hintergrundgeschwindigkeiten. Netzgeschwindigkeiten und gegebenenfalls Network Change Events bilden diese Grundlage ab. Ein Network Change Event sagt beispielsweise: Ab 08:00 Uhr gilt auf dieser Straße eine andere Geschwindigkeit.
2. Delivery und Supply erzeugen beziehungsweise laden Aufträge, Depots und Fahrzeuge. Eingaben müssen zum jeweiligen Netzmodus passen.
3. Die Initialplanung verwendet standardmäßig eine jsprit-Iteration. Sie erzeugt eine Startlösung; das ist kein Nachweis einer optimalen Tour.
4. MATSim simuliert die Fahrten. Der feste Replanning-Pass beginnt standardmäßig in Iteration 25 und friert die Reisezeitgrundlage aus der abgeschlossenen Iteration 24 einmal ein.
5. Alle Carrier dieses Passes werden nacheinander beziehungsweise über den begrenzten Ausführungsdienst bearbeitet. Standardmäßig werden 16 Carrier je Gruppe verwaltet; die Solver laufen standardmäßig seriell. Alle Gruppen verwenden dieselbe eingefrorene Reisezeitgrundlage. Ein Carrier wird in diesem Pass einmal neu gelöst.
6. jsprit liefert einen Kandidaten. HAGRID routet die Tour auf den tatsächlichen Straßen und prüft Aufträge, Kapazität, Zeitfenster, Fahrzeuge, Depots und Zeiten unabhängig.
7. Ein gültiger alter Plan wird mit derselben Grundlage neu bewertet. Er kann erhalten bleiben, wenn er günstiger ist. Nur vollständig gültige Gruppen werden übernommen.

**Beispiel:** Carrier A wird zuerst und Carrier B später geplant. B darf seine Tour nicht mit zwischenzeitlich veränderten Reisezeiten berechnen, während A noch die alten Zeiten benutzt. Der eingefrorene Kontext macht ihren Vergleich konsistent.

Die Kosten aus der Tourensuche und der MATSim-Score sind verschiedene Größen. Eine günstigere jsprit-Tour ist deshalb nicht automatisch eine Verbesserung jedes MATSim-Scorings. Auch Durchschnittsgeschwindigkeiten und simulierte Reisezeiten müssen zur gleichen fachlichen Verkehrsgrundlage gehören; sie sind nicht automatisch identisch.

## 3. Annahmen und beibehaltene Entscheidungen

| Entscheidung | Einfache Bedeutung | Status oder Grenze |
|---|---|---|
| 500 zusätzliche Fahrzeuge beeinflussen den Hintergrund kaum | Hintergrundgeschwindigkeiten werden als vorgegebene Randbedingung behandelt. | Fachliche Annahme; auf echten Engpässen noch nachzuweisen. |
| Ein fester Replanning-Pass | Alle Carrier erhalten dieselbe abgeschlossene Verkehrsgrundlage. | Lokal geprüft; reale Hintergrundprofile noch abnehmen. |
| Eine initiale jsprit-Iteration | Schnell eine Ausgangslösung erzeugen, später einmal neu planen. | Beibehalten; kein Optimalitätsversprechen. |
| Hannover-Solvergrenze sieben Stunden | Die bisherige Tourgrenze von 25.200 Sekunden bleibt. | Andere Erzeugungs-/Fahrergrenzen bleiben eigenständige Regeln. |
| Lastenrad maximal 20 km/h | Standardgrenze konfigurierbar; beispielsweise 18 km/h möglich. | Langsamere Straßen und Stau können die Fahrt zusätzlich begrenzen. |
| Exaktes Routing als Standard | Wege werden mit dem normalen Cache auf dem tatsächlichen Netz berechnet. | Zonennäherung bleibt eine optionale Näherung. |
| Serieller Standard | Ein äußerer Solver zur Zeit; interne Threadzahl standardmäßig eins. | Mehr Parallelität erst nach geeigneter RAM-/Qualitätsmessung. |
| Warmstart und adaptive Suche aus | Neue Suchoptionen verändern den Standardbetrieb noch nicht. | Implementiert, getestet; Qualitätskompromisse gemessen. |
| Keine neue Zwei-Echelon-Regel | Supply und Delivery erhalten durch den Refactor keine neue zeitliche Kopplung. | Eine Freigabe der Auslieferung erst nach Supply-Ankunft wäre ein eigenes Modellprojekt. |
| Java 21, MATSim 2025.0, jsprit 2.0.0 | Core und Analysis verwenden jsprit 2.0.0. | jsprit-IO bleibt kompatibel bei 1.8; nicht jedes jsprit-Modul wurde auf 2.0 gesetzt. |

Bei zeitabhängigen Kosten benötigen wir den geprüften FIFO-Vertrag: Später losfahren darf im verwendeten Zeitmodell nicht künstlich früher ankommen. Zeitlich variable Zonennäherung und nicht belegte gemischte Zeit-/Distanzziele werden ausdrücklich diagnostiziert. Es gibt keinen stillen Wechsel auf freie Geschwindigkeiten, der ein unpassendes Profil verdeckt.

## 4. Was am Code übersichtlicher geworden ist

| Baustein | Aufgabe in einfachen Worten |
|---|---|
| `HagridConfigResolver` / `ResolvedHagridConfig` | Einstellungen einmal mit klarer Priorität auflösen und als unveränderlichen Stand weitergeben. CLI hat Vorrang vor Run-/Profilwerten. |
| `RoutingContext` und Snapshots | Netz, Reisezeitstand und gemeinsame Routingressourcen für einen definierten Abschnitt zusammenhalten. |
| `CarrierOptimizationService` | Auftrag vorbereiten, jsprit suchen lassen, Endtour routen und Ergebnis prüfen. |
| `VRPTransportCostsFactory` / `ModalTransportCosts` | Den passenden Kostenanbieter mit richtigem Modus und Fahrzeug bereitstellen. |
| `ReplanningTransportCosts` | Zusätzliche Vorbereitung, Diagnose und Lebensdauer über einen neutralen Vertrag anbieten. |
| `ZoneRoutingApproximation` | Nur die Zonen-Näherungsregel und ihre vorbereiteten Metadaten verwalten. |
| `PlanValidator` / `PlanSelection` | Touren unabhängig prüfen und zwischen gültigem alten und neuen Plan wählen. |
| `CarrierBatchExecutor` / `CarrierRunBudget` | Solver zulassen, Ressourcen reservieren, Aufgaben beenden und Gruppen geordnet übernehmen. |
| `OptimizationResultStore` / `CarrierReplay` | Geprüfte Ergebnisse speichern und einzelne Fälle mit derselben Grundlage reproduzieren. |
| Transaktionen / `OutputFileSet` | Späte Fehler zurückrollen und Dateien vorbereitet austauschen. |

**Zum alten ZoneBasedCosts-Problem:** Der Replanner fragt jetzt nach einer Fähigkeit des Kostenanbieters, statt auf eine bestimmte Zonenklasse zu casten. Das funktioniert auch für normale exakte Kosten. Ein universeller Anbieter ist damit sinnvoll, wenn Modus, Fahrzeugadapter, Wegesucher und Ziel-Link-Zeit durchgehend zusammenpassen. Genau diese Weitergabe wurde bearbeitet und klein geprüft.

`ZoneBasedTransportCosts` wurde im Refactor von ungefähr 1.077 auf 526 Zeilen reduziert; der Hannover-`Router` von ungefähr 735 auf 390. Vorbereitung, Suche und Übernahme haben benannte Zuständigkeiten. Die Zeilenzahlen zeigen eine bessere Aufteilung, aber für sich keinen Laufzeitgewinn. Öffentliche historische Adapter bleiben als veraltete Kompatibilität erhalten; sie sind nicht vollständig gelöscht.

Kontextbezogene Graphen und vorbereitete Zonenmetadaten werden geteilt. Der schnelle Zonen-Abfragepfad baut nicht für jede Kostenabfrage erneut dieselben Komponenteninformationen auf. Caches sind begrenzt und an ihren Kontext gebunden; fremde Fahrzeugprofile oder veränderte Reisezeiten dürfen nicht zufällig alte Antworten benutzen.

## 5. Netze, Fahrzeuge, Supply und Merges

**Der Netzkonsistenzfehler betrifft die Grundlage, auf der wir Kosten und Touren vergleichen:** Gleiche Link-IDs oder Dateinamen beweisen nicht, dass es dasselbe nutzbare Netz ist. Topologie, erlaubte Modi, Depotzugänge und Reisezeitdaten müssen zusammenpassen. Sonst kann eine Tour auf einem Netz berechnet und auf einem anderen geprüft beziehungsweise gefahren werden.

Die Integration prüft Netzidentität und die relevanten Zugänge statt nur Namen zu vertrauen. Fehlerhafte Modi, unpassende Jobs/Depots und nicht passende Ressourcen werden vor teurem Solveraufbau diagnostiziert. Cache- und Replay-Zertifikate berücksichtigen den tatsächlich benutzten Kontext.

Lastenrad und Pkw können auf einem gemeinsamen multimodalen Netz ihren richtigen Modus verwenden. Das Rad fährt mit seiner konfigurierten Obergrenze, auf langsameren Links entsprechend langsamer. Kleine echte QSim-Fahrten prüfen 18/20 km/h, einen langsameren Link, ein Network Change Event und einen gemeinsam benutzten FIFO-Link. Auf diesem Link kann ein Auto hinter dem Rad warten: QSim modelliert damit keine freie Überholmöglichkeit. Die Realitätsprüfung auf eurem großen Netz steht noch aus.

Delivery-/Supply-Erzeugung und Merges berücksichtigen die vorhandenen Modus-, Flotten-, Mengen-, Kapazitäts- und Fensterregeln. Reine Lieferstopps und reine Abhol-/Lieferpaare werden unterstützt. Gemischte Service-/Shipment-Aufträge innerhalb desselben Carriers werden wegen der bestehenden nativen Adaptergrenze früh abgewiesen.

Späte Supply-, Modus- oder Dateifehler dürfen keine halben Änderungen hinterlassen. Die Rückabwicklung umfasst unter anderem Pläne, Carrier-Registrierungen, Hub-Zuordnungen, Mengen-/Merge-Metadaten und Fahrzeugtypen. Die Nachfrage- und Supply-Geschäftsregeln wurden dabei nicht neu erfunden. Insbesondere ist die zeitliche Kopplung „erst nach der Supply-Ankunft ausliefern“ weiterhin kein implementiertes Zwei-Echelon-Modell.

## 6. Konkrete Fehler, die behoben wurden

| Vorheriges Risiko | Reparatur und Bedeutung |
|---|---|
| Alter Cache passt nur scheinbar | Aktuelle Aufträge, Netz, Fahrzeuge, Einstellungen und gegebenenfalls Starttour werden zertifiziert und unabhängig geprüft. |
| Unzulässiger billiger Plan gewinnt | Nachgerechnete Endtour muss alle fachlichen Prüfungen bestehen, bevor Kosten verglichen werden. |
| Konkrete Zonenklasse wird vorausgesetzt | Neutraler Kostenvertrag und korrekte Modusweitergabe ersetzen diese Kopplung. |
| Zweiter Aufrufer liest veraltetes Budget | Gruppenbesitz wird vor der frischen Budgetmessung beansprucht. Ein gleichzeitiger Aufruf startet nicht mit einer alten größeren Reserve. |
| Abgebrochener Solver gibt Ressourcen zu früh frei | Es wird auf das tatsächliche Ende der eigenen internen Worker gewartet; ein abgebrochenes Future allein reicht nicht. |
| Später Fehler veröffentlicht halbe Gruppe | Ergebnisse werden gesammelt und erst als vollständig geprüfte Gruppe übernommen. |
| Offline-/Erzeugerfehler lässt Zustandsreste zurück | Transaktionen stellen den vorherigen Zustand nach späten Modus-, Supply- oder Ausgabefehlern wieder her. |
| Fehler beim zweiten Dateiaustausch beschädigt Altstand | Dateien werden zuerst vollständig vorbereitet. Austauschfehler lösen Wiederherstellung aus. |
| Wiederherstellungsfehler löscht die Originalsicherung | Die Sicherung bleibt mit Diagnose erhalten; andere Wiederherstellungen werden weiter versucht. |
| XML-Rundung verändert einen exakten Replay-Fall | Präzise interne Übertragung bewahrt die zertifizierten Werte, ohne das allgemeine historische XML-Format zu brechen. |
| Tatsächlicher Endplan und Kennzahlen stimmen nicht überein | Beim Controller-Ende wird die aktuelle Auswahl erneut geprüft; Suchkosten und MATSim-Score bleiben getrennt. |
| Kleiner Messlauf umgeht adaptive Iterationsgrenze | Das Messwerkzeug prüft vor JVM-Start auch das wirksame adaptive Maximum, nicht nur den angeforderten Wert. |

Die wesentlichen Fehler wurden mit konkreten Gegenbeispielen reproduziert und anschließend geprüft. Der kleinere Beobachterfehler im Speicher-Messwerkzeug wurde durch sauberes Beenden vor der Graphmessung korrigiert; dafür gibt es keinen deterministischen Race-Gegenbeweis.

Die Dateirückabwicklung hilft bei behandelten Lauf-/Austauschfehlern. Sie ist kein gemeinsamer, absturzsicherer Dateisystem-Commit mehrerer Dateien. Eine gesamte MATSim-Simulation lässt sich auch nicht allein durch den Einzel-Carrier-Cache an beliebiger Stelle wiederaufnehmen.

## 7. RAM und Parallelität einfach erklärt

Im Speicher liegen gleichzeitig das MATSim-Modell, Netze, Routingcaches, schon veröffentlichte Touren, vorübergehende Solverobjekte und noch nicht aufgeräumte Java-Objekte. Der Speicher eines einzelnen Carriers ist deshalb nicht einfach „alles, was nach seinem Start mehr belegt ist“.

Der neue Laufbudgetvertrag zieht Reserven sowohl vom verfügbaren Java-Heap als auch vom freien physischen RAM ab. Die kleinere positive Restkapazität begrenzt die Zulassung. Die Messung erfolgt vor jeder nichtleeren Gruppe frisch. Fehlende oder ungültige Werte starten keinen Solver. Geschätzte Carrier-Reservierungen bleiben bis zur Gruppenübernahme gehalten, damit fertige, noch nicht veröffentlichte Ergebnisse nicht aus dem Budget verschwinden.

**Wichtig:** Dieser Mechanismus ist lokal geprüft. Die verlässlichen Schätzwerte für echte Carrier, gemeinsame Basis und Reserven müssen noch gemessen und produktiv angeschlossen werden. Der JVM-Beobachter misst gesamten gemeinsamen Heap einschließlich Garbage, keinen exklusiven Carrier-Peak. Auch Java-Heap und physischer RAM sind verschiedene Größen. Der Mechanismus allein garantiert deshalb noch keine harte Produktions-RAM-Grenze.

Es gibt zwei Parallelitätshebel: mehrere Carrier gleichzeitig und mehrere interne jsprit-Threads je Carrier. Zwei Carrier mit jeweils vier internen Threads können bereits acht Solverthreads beanspruchen. Eine Gruppengröße von 16 bedeutet dagegen nicht automatisch 16 gleichzeitige Solver. `maxActiveSolvers` begrenzt einen bereitgestellten Pool; es erstellt nicht von selbst einen kalibrierten Produktionspool.

Eigene Prüfungen haben zusätzlich den ResourceGuard: mindestens 6 GiB freier physischer RAM, konservative Startmessung und laufende Kontrolle. Bei zu wenig RAM, anhaltend hoher CPU oder Messfehlern beendet er ausschließlich seinen eigenen Prüflauf. Valhalla und fremde Prozesse werden nicht verändert. Große Szenarien bleiben zurückgestellt. Ein früherer Lauf wurde wegen CPU-Druck geschützt abgebrochen; erst die nachfolgenden erfolgreichen Läufe zählen als Nachweis.

## 8. Was tatsächlich geprüft wurde

| Prüfung | Ergebnis | Was daraus folgt |
|---|---|---|
| Breiter kleiner Produktlauf | 1.362/1.362 Tests aus 158 ausgewählten Klassen, Maven verify und Paketbau erfolgreich. | Viele lokale Verträge und Integrationsfälle bestehen; großer Produktions-End-to-End-Test ausgenommen. |
| Eigenständiger Paket-Replay | Services und Shipments vollständig, jeweils zwei tatsächliche Iterationen; Endkosten 100,25 und 94,0. | Das gebaute Paket funktioniert auch außerhalb des Testprozesses mit demselben exakten Kontext. |
| Echte kleine MATSim-Fahrten | Radgrenzen 18/20 km/h, langsamer Link, NCE und gemeinsamer FIFO-Link geprüft. | Routing und kleine Fahrtsimulation passen für diese Fälle zusammen. |
| Echte Ausgabeprüfung | Kleiner Vier-Iterationen-Lauf erhält finale Ereignisse, Pläne und beide Histogramme trotz ausgelassener Zwischenoutputs. | Ausgabeintervalle verhindern nicht versehentlich die Abschlussdateien. |
| Laufbudget / Gruppen | Frische Messung, konkurrierender Aufruf, Messfehler und nativer Zwei-Gruppen-Fall geprüft. | Frühere gültige Touren bleiben erhalten, wenn die nächste Gruppe am Budget scheitert. |
| Messwerkzeug für exportierte Carrier | Vier kleine Messungen und neun negative Kontrollen bestanden. Adaptive Grenze 21 vor JVM-Start abgewiesen; erlaubte acht Iterationen tatsächlich ausgeführt. | Reproduzierbarer kleiner Messpfad vorhanden; Produktionskalibrierung noch offen. |
| Cache / Rückabwicklung / Worker | Fehlerfälle, aktuelle Eingabezertifikate, historische Lausitz-GOLDEN-Dateien und tatsächliches Worker-Ende geprüft. | Bekannte Integrationsfehler sind lokal abgesichert. |

Der breite Produktlauf dauerte 4:52 Minuten mit einer sichtbaren CPU, 512 MiB Maven-Heap und 384 MiB Test-Heap. Mindestens 11,84 GiB physischer RAM blieben frei; die höchste beobachtete gesamte CPU-Auslastung lag bei 64,15 Prozent. Das ist ein Nachweis dieses Prüflaufs, kein Stabilitätsversprechen für einen späteren Produktionslauf.

Die vier jüngsten Replay-Speichermessungen verwendeten synthetische Ein-Job-Fälle mit 384 MiB Heap und einer CPU. Beobachtete Gesamt-Replay-Heapspitzen lagen ungefähr bei 25 bis 26 MiB. **Daraus darf man nicht das RAM-Budget eines echten Carriers ableiten.** Der nach Rückgabe erreichbare Ergebnisgraph enthält außerdem nicht mehr den bereits geschlossenen gemeinsamen Kontext und nicht die ganze MATSim-Basis.

Bei diesem Dokumentationsabschluss wurden Paketkennung, vorhandene Nachweise und Dokumente frisch abgeglichen. Es wurden keine Produktquellen geändert und kein weiterer Maven-Gesamtlauf nur für Textänderungen gestartet.

## 9. Sind wir schneller oder besser geworden?

**Es gibt belegte Verbesserungen einzelner Teile. Eine pauschale Aussage „die gesamte HAGRID-Integration ist um X Prozent schneller“ ist noch nicht belegt.**

| Vergleich auf kleinen synthetischen Fällen | Laufzeit | Ergebnisqualität | Entscheidung |
|---|---|---|---|
| Neuer Zonenindex gegen seine alte Fassung | Etwa 22 % weniger Optimierungsphasenzeit bei Services, 30 % bei Shipments. | Alle 50 Gruppenpaare mit exakt gleichen nachgerechneten Endkosten und Fahrzeugzahlen. | Refactor-Wirkung lokal belegt. |
| Zonennäherung gegen exakten Cache | Zone ungefähr 2,1 bis 3,1 Mal so langsam. | Je Fall etwa 7 bis 15 % höhere Endkosten; alle 20 Seed-/Fallpaare schlechter. | Exaktes Routing bleibt Standard. |
| Warmstart gegen feste zehn Iterationen ohne Warmstart | Etwa 15 % weniger Pipelinezeit bei Services, 18 % bei Shipments. | Services-Mediankosten 0,69 % höher, Shipments 0,61 % niedriger; 33/60 Service-Paare schlechter. | Option bleibt standardmäßig aus. |
| Adaptive Suche gegen feste zehn Iterationen | Etwa 25 % weniger Pipelinezeit bei Services, 26 % bei Shipments. | Weniger Sucharbeit; einzelne teurere Gewinner bei beiden Auftragstypen. | Option bleibt standardmäßig aus. |

Der Zonenindex-Vergleich nutzt fünf Seeds, 16 Jobs je Carrier, ein Netz mit 64 Knoten/224 Links und Vierergruppen. Gemessen wurden Optimierungsphasen ohne Kontextaufbau, Messinstrumentierung und Veröffentlichung. Der Vergleich bedeutet nicht, dass Zonennäherung jetzt besser als exaktes Routing wäre. Frühere Teilverbesserungen werden nicht addiert.

Der Suchoptionen-Vergleich nutzt fünf Root-Seeds und wiederholte Gruppen. Überlappende Carrier-Seeds begrenzen die Unabhängigkeit; es gibt acht unterschiedliche Carrier-Seeds, keine 20 unabhängigen Stichproben. Seine Zeiten umfassen die dort definierte Vorbereitung/Suche/Endrouting/Prüfung/Planwahl, aber nicht Kontextaufbau oder die gesamte HAGRID-Pipeline. Die beiden Zeitdefinitionen sind nicht direkt gleichzusetzen.

„Besser“ prüfen wir anhand vollständiger Auftragsbedienung, Zulässigkeit, nachgerechneter Endkosten, Fahrzeugzahl, Laufzeit, Speicherbedarf und separat MATSim-Score. Ein kleiner Gewinn beim Median darf einzelne deutlich schlechtere Carrier nicht verdecken. Die 500-Fahrzeuge-Annahme und die Wirkung von jsprit 2.0 allein erfordern eigene reale beziehungsweise isolierte Vergleiche.

## 10. Die 19 Planpunkte auf einen Blick

„Lokal geprüft“ bedeutet: umgesetzt und mit begrenzten Prüfungen nachgewiesen. Es bedeutet keine Freigabe des vollständigen Produktionsmodells.

| Nr. | Arbeitspaket | Heutiger Stand |
|---:|---|---|
| 1 | Ausgangspunkt und Vergleiche | Kleine Vergleiche erledigt; sauber getrennter Versions-/Gesamtvergleich offen. |
| 2 | Java, MATSim und jsprit 2.0 | Integration und Paket-Replay lokal geprüft. |
| 3 | Gemeinsame Konfiguration | Typisierte Auflösung und Weitergabe lokal geprüft; Produktionsbudget offen. |
| 4 | Solverzustände, Bedienzeiten, Regeln | Lokale Prüfungen bestanden; reale Regimeabnahme offen. |
| 5 | Snapshot und Routingkontext | Fester Voriterationsstand und Lebensdauer lokal geprüft. |
| 6 | Gemeinsamer Kostenvertrag | Neutraler Vertrag und Modusweitergabe lokal geprüft. |
| 7 | Cache und Zonennäherung | Lokal geprüft und gemessen; variable Zonennäherung gesperrt. |
| 8 | Netze, Flotten, Supply und Merges | Bestehende Regeln und Rückabwicklung lokal geprüft; echte große Eingaben offen. |
| 9 | Kleine QSim-Fahrten | Echte kleine Fahrten geprüft; Netz-/500-Fahrzeuge-Annahme offen. |
| 10 | Gemeinsamer Optimierungsdienst | Initialplanung, Lausitz und Replanning angeschlossen und lokal geprüft. |
| 11 | MATSim-Replanning-Pass | Gruppierung, Voriteration, Altplanwahl und Lebenszyklus lokal geprüft. |
| 12 | Ergebnisablage und Wiederaufnahme | Zertifizierte Einzel-/Lausitz-Treffer lokal geprüft. |
| 13 | Einzel-Carrier-Replay | Paket-Replay und begrenztes Messwerkzeug geprüft; repräsentative Fälle offen. |
| 14 | Gemeinsamer Batch-Dienst | Lebensdauer, Zulassung und frisches Budget lokal geprüft; reale RAM-Kalibrierung/Produktionsanschluss offen. |
| 15 | Warmstarts | Implementiert und klein gemessen; Default aus. |
| 16 | Adaptive Suchbudgets | Implementiert und klein gemessen; Default aus. |
| 17 | Kennzahlen, Scoring und Ausgabe | Lokal einschließlich finaler Ausgaben geprüft; repräsentative Abnahme offen. |
| 18 | Bereinigung und Abnahme | Lokale Tests/Review erledigt; Produktionsfreigabe und Git-Integration offen. |
| 19 | jsprit intern profilieren | Nach Integrationsabnahme als eigenes Arbeitspaket vorgesehen. |

## 11. Was noch ansteht und in welcher Reihenfolge

| Nr. | Arbeit | Warum sie wichtig ist | Fertig, wenn ... |
|---:|---|---|---|
| 1 | Repräsentative kleine echte Carrier auswählen und mit Kontext exportieren. | Ein-Job-Beispiele erklären keine großen Solver oder echten Netze. | Kleine und größere Services/Shipments samt Flotte, Netz, Reisezeiten und Einstellungen eindeutig festgehalten sind. |
| 2 | Gemeinsame Basis, Carrierbedarf, zurückgehaltene Ergebnisse und Reserven messen; Produktionsbudget anschließen. | RAM war neben jsprit-Replanning der eigentliche Engpass. | Konservative Schätzwerte auf echten Fällen geprüft sind und nicht als exklusive Peaks missverstanden werden. |
| 3 | Äußere und interne Parallelität getrennt mit festen Ressourcen messen. | Mehr Threads können RAM erhöhen und Suche verändern. | 1/2/4 kontrolliert verglichen sind und zulässige Qualität sowie Speicher-/CPU-Spielraum erhalten bleiben. Größere Versuche erst nach Valhalla. |
| 4 | Warmstart/adaptive Suche auf vielfältigen echten Fällen und unabhängigen Seeds prüfen. | Der kleine Vergleich zeigt auch Qualitätsverluste. | Laufzeit und einzelne schlechteste Carrier transparent vorliegen; eine bewusste Freigabeentscheidung möglich ist. |
| 5 | Hannover/Lausitz mit realem Hintergrund, NCE, Rad/Pkw, Depots und Scoring abnehmen. | Lokale Verträge ersetzen keine Modellkalibrierung. | Produktionsausgaben passen und die 500-Fahrzeuge-Annahme an relevanten Engpässen überprüft ist. |
| 6 | Alten Stand, reinen jsprit-Wechsel und gesamten Refactor getrennt vergleichen. | Sonst wissen wir nicht, welcher Teil einen Gewinn verursacht. | Gleiche Inputs, Seeds, tatsächlich gleiche Suchbudgets und Ressourcen vorliegen. |
| 7 | Änderungen mit aktuellem Upstream abgleichen und in den normalen Checkout integrieren. | Der geprüfte Code liegt bislang nur im Arbeitsverzeichnis. | Review, Konfliktauflösung und vereinbarter Commit-/PR-/Merge-Schritt erledigt sind. Keine aktuelle Upstream-Synchronität behauptet. |
| 8 | Danach jsprit selbst profilieren. | Erst dann lohnt sich gezieltes Arbeiten an den verbleibenden Such-Hotspots. | Allokation, Datenstrukturen, Nachbarschaftssuche und Parallelisierung anhand echter Profile priorisiert sind. |

Separat bleibt eine native GeoTools-Warnung: Im eingebundenen MATSim-Code schließen bestimmte GeoFileWriter-Pfade ihren Datastore nicht zuverlässig. Ein DRT-Aufrufer ist eine plausible Quelle der beobachteten Warnung; die Zuordnung ist eine Schlussfolgerung ohne Erzeugungsstack. Der fremde Submodul-Code wurde dafür nicht geändert. Das ist ein eigener Upstream-Befund.

**Der lokale Implementierungsstand ist damit dokumentiert und übergabefähig. Der vollständige Optimierungsplan bleibt für die offenen Abnahme- und Kalibrierungspunkte offen.** Der bestehende Ressourcenmonitor bleibt pausiert; es werden keine großen Versuche oder fremden Prozesse gestartet.

## 12. Wo man Nachweise und Einstellungen findet

Alle relativen technischen Pfade in diesem Abschnitt beziehen sich auf das Arbeitsverzeichnis `C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID`.

| Was | Ort |
|---|---|
| Architektur und ursprünglicher Plan | `docs/superpowers/specs/2026-10-06-hagrid-jsprit-2-integration-design.md`; `docs/superpowers/plans/2026-10-07-hagrid-jsprit-integration.md` |
| Einstellungen und Migration | `docs/superpowers/plans/2026-10-07-hagrid-jsprit-config-migration.md` |
| Aktueller Stand und vollständige technische Ergebnisse | Die oben verlinkte Checkliste und der Ergebnisbericht. Frühere Abschnitte dort sind historische Zwischenstände. |
| Detailliertes Fortschrittsledger | `.superpowers/sdd/2026-10-07-hagrid-jsprit-integration/progress.md` |
| Letzter breiter Produktnachweis | Ledgerdatei `k215-small-verify-package-20261009.log` und deren `.resources.json` |
| Paket-Replay | `tools/benchmarks/results/k216-package-replay-20261009` |
| Zone gegen exakt | `tools/benchmarks/results/k179-zone-fixed-pairs-20261009` |
| Neuer gegen alten Zonenindex | `tools/benchmarks/results/k196-zone-context-paired-20261009` |
| Warmstart / adaptive Suche | `tools/benchmarks/results/k206-search-policies-paired-20261009` |
| Neuer Replay-Speichermesspfad | `tools/benchmarks/results/k222-replay-memory-20261009`; negative Kontrollen im Ledgerordner `k223-profile-rejection-controls` |
| Bedienung der geschützten Prüfwerkzeuge | `tools/benchmarks/README.txt` |
| Gebautes Produktpaket | `hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar` |

Historische Paketkennung des früheren K215-Nachweises: `461F5972F2970EE56C007778B7BD8B2ECAE7FD91E620BA9C6915E2B82348001C`. Die Messvergleiche nutzen ihre jeweils im Manifest festgehaltenen Referenzpakete; sie werden nicht nachträglich dem neuesten Paket zugerechnet.

Für spätere kleine Prüfungen stehen `run-bounded-tests.ps1`, `run-package-replay-smoke.ps1` und `run-replay-memory-profile.ps1` bereit. Die Detailparameter stehen im Benchmark-README. Ein neuer Lauf braucht ein frisches Ausgabeverzeichnis und eine gültige Ressourcenfreigabe. Der Messpfad begrenzt unter anderem Fallzahl, Aufträge, Iterationen und Heap; er ist kein Produktionsstartskript.

## 13. Kleines Wörterbuch

| Begriff | Bedeutung |
|---|---|
| Carrier | Ein zu planender Frachtanbieter mit seinen Fahrzeugen und Aufträgen. |
| Service | Ein einzelner Liefer-/Bedienstopp. |
| Shipment | Ein zusammengehöriges Abhol- und Lieferpaar. |
| Replanning | Touren nach einer abgeschlossenen Simulation erneut planen. |
| Snapshot / Kontext | Festgehaltener Stand von Netz, Reisezeiten und passenden Einstellungen. |
| Cache | Bereits berechnete Antworten oder Pläne wiederverwenden, wenn ihre Eingaben noch passen. |
| Warmstart | Einen gültigen alten Plan als frei weiter optimierbare Startlösung benutzen. |
| Adaptive Suche | Innerhalb expliziter Grenzen je nach Verbesserungsverlauf früher stoppen. |
| Heap | Speicherbereich der Java-Objekte; nicht der gesamte physische RAM-Verbrauch. |
| Allokation | Insgesamt angelegte Objekte; nicht alles davon ist gleichzeitig im RAM. |
| Seed | Startwert des Zufallsgenerators für nachvollziehbare Vergleiche. |
| Replay | Einen gespeicherten Carrier-Fall mit seinem festgehaltenen Kontext erneut ausführen. |
| FIFO | In diesem Zeit-/Fahrmodell überholt eine spätere Abfahrt nicht künstlich die frühere. |
