# Hannover: Regret-Mix gegen jsprit 1.8

Stand: 20. Oktober 2026. Ausgewertet: **48 Hannover-Versuche** plus **18 getrennte synthetische Diagnoseläufe**. 46 Hannover-Prozesse endeten erfolgreich. Ein bereits rechtzeitig gespeicherter gültiger Endplan wurde nach einem späteren Diagnosefehler unabhängig gesichert; ein anderer Versuch bleibt nach einem Fehler des Prüfprogramms ohne bewertbaren Endplan. Während der Hauptmessung wurden keine Strategieparameter nachjustiert. Die HAGRID-Produktionsvoreinstellung bleibt unverändert.

## Antwort in einfachen Worten

**2.0 bisheriger Mix** ist gegenüber normalem 1.8-Regret im Median +0,09 % verändert (9 schlechtere Paare), gegenüber 1.8-FAST_REGRET +0,44 % (10 schlechtere Paare). Ein durchgängiger Vorteil gegenüber beiden Referenzen ist damit hier nicht belegt.
**2.0 Mix mit Cluster** ist gegenüber normalem 1.8-Regret im Median +0,15 % verändert (8 schlechtere Paare), gegenüber 1.8-FAST_REGRET +0,48 % (9 schlechtere Paare). Ein durchgängiger Vorteil gegenüber beiden Referenzen ist damit hier nicht belegt.

Verglichen wird, welche Touren innerhalb desselben Budgets fertig vorliegen. **Dieser Hannover-Test misst keine Zeitersparnis bis zu einer bestimmten Tourenqualität.** Die vollständige Abfahrtsnachbearbeitung erfolgt am Suchende; deren Zeitpunkt nahe der gesetzten Grenze wäre keine belastbare Suchgeschwindigkeit.

## Hannover-Ergebnisse bei gleicher Rechenzeit

Alle Prozentzahlen sind Mediane der verfügbaren gepaarten Kostenänderungen, nicht Quotienten unverbundener Mittelwerte. Negative Werte bedeuten günstigere Touren. Zwölf geplante Paare je Vergleich entstehen aus drei Carriern und vier Solver-Seeds; sie sind keine zwölf unabhängigen Nachfrage-Tage. Bei Beteiligung des Cluster-Mixes fehlt wegen des Prüfprogrammfehlers ein bewertbarer Vergleich. Dieser wird weder als Gewinn noch als Verlust eingesetzt und begrenzt die Aussagekraft.

| Variante | Verglichen mit | Median Kostenänderung | Besser / gleich / schlechter | Größte Änderung nach oben | Ohne bewertbaren Endplan |
|---|---|---|---|---|---|
| 2.0 bisheriger Mix | 1.8 Regret | +0,09 % | 3 / 0 / 9 | +0,80 % | 0 |
| 2.0 bisheriger Mix | 1.8 FAST_REGRET | +0,44 % | 2 / 0 / 10 | +0,81 % | 0 |
| 2.0 Mix mit Cluster | 1.8 Regret | +0,15 % | 3 / 0 / 8 | +0,80 % | 1 |
| 2.0 Mix mit Cluster | 1.8 FAST_REGRET | +0,48 % | 2 / 0 / 9 | +0,83 % | 1 |
| 2.0 Mix mit Cluster | 2.0 bisheriger Mix | -0,01 % | 6 / 0 / 5 | +0,07 % | 1 |

**47 von 48** Versuchen haben eine rechtzeitig gespeicherte vollständige gültige Endlösung. Alle 47 verfügbaren Endpläne wurden separat aus ihren gespeicherten Jobreihenfolgen und Zeiten nachgerechnet. Anschließend wurden sämtliche 47 Pläne über die native MATSim-Fabrik in Carrier-Pläne überführt, auf dem tatsächlichen Straßennetz geroutet und mit HAGRIDs PlanValidator geprüft. Kapazitäten, vollständige Bedienung, Fähigkeiten, Zeitfenster, Fahrzeugverfügbarkeit und Sieben-Stunden-Grenze passen; die Straßenkosten stimmen innerhalb der dokumentierten Rundungstoleranz mit den Matrixkosten überein. Der nicht bewertbare Versuch ist kein nachgewiesener Solver-, Kapazitäts- oder Deadlinefehler.

## Welche echten Eingaben verwendet wurden

Es sind vollständige HAGRID-Carrier aus der mit dem Kalender-Nachfragetool erzeugten Hannover-Nachfrage vom **13. Mai 2025**. „Echt“ bedeutet hier: die vorhandenen Hannover-Eingaben und das echte Netz; keine beobachteten Unternehmens-Touren und keine erfundenen Raster-Aufträge.

Die native Inventur umfasst 191 nichtleere Delivery-Service-Carrier. Vor dem Lösen wurden nach Auftragszahl und Carrier-ID die Ränge 0, 95 und 190 gewählt: **FedEx 30627 mit 53**, **DPD 30655 mit 122** und **DHL 30519 mit 389 Aufträgen**. Keine Aufträge wurden aus diesen Carriern entfernt oder vereinfacht. Beide Fahrzeug-Zeitlagen, Fahrzeugtypen, Depot-Links, Paketmengen, Bedienzeiten, Fähigkeiten und Zeitfenster bleiben erhalten. Alle drei verwenden eine unbeschränkte Flotte aus den vorhandenen Fahrzeugvorlagen.

Das tatsächliche Netz hat 230735 Knoten und 544514 gerichtete Links. Sämtliche benötigten gerichteten Wege wurden mit nativen NetworkBasedTransportCosts vorausberechnet – getrennt für den Fahrzeugtyp und für die fahrzeugunabhängigen Nachbarschaftsabfragen. Beide jsprit-Versionen erhalten identische Kosten-, Zeit- und Entfernungstabellen. Diese gemeinsame Vorbereitung liegt außerhalb des Solver-Zeitbudgets. Der Test bewertet deshalb die Suche auf echten Eingaben mit warmen, identischen Straßenkosten; er misst nicht den gesamten kalten HAGRID-Prozess.

## Die einzelnen Hannover-Fälle

Die Carrier unterscheiden sich stark in der Auftragszahl. Deshalb zuerst ihre getrennten Ergebnisse; der Gesamtmedian oben gewichtet jeden verfügbaren Seed-Vergleich gleich, nicht nach Paketmenge.

| Carrier | Variante | Median gegen 1.8 Regret | Median gegen 1.8 FAST | Bewertbare FAST-Paare | Besser / gleich / schlechter gegen FAST |
|---|---|---|---|---|---|
| fedex_30627 | 2.0 bisheriger Mix | +0,78 % | +0,78 % | 4 / 4 | 0 / 0 / 4 |
| fedex_30627 | 2.0 Mix mit Cluster | +0,79 % | +0,79 % | 4 / 4 | 0 / 0 / 4 |
| dpd_30655 | 2.0 bisheriger Mix | +0,02 % | +0,12 % | 4 / 4 | 1 / 0 / 3 |
| dpd_30655 | 2.0 Mix mit Cluster | +0,07 % | +0,24 % | 3 / 4 | 1 / 0 / 2 |
| dhl_30519 | 2.0 bisheriger Mix | +0,06 % | +0,39 % | 4 / 4 | 1 / 0 / 3 |
| dhl_30519 | 2.0 Mix mit Cluster | -0,01 % | +0,32 % | 4 / 4 | 1 / 0 / 3 |

Kosten sind konfigurierte Modellkosten. Die Tabelle zeigt zusätzlich die Tourenzahl; niedriger ist bei identischer vollständiger Bedienung günstiger.

| Carrier | Seed | 1.8 Regret | 1.8 FAST_REGRET | 2.0 bisheriger Mix | 2.0 Mix mit Cluster |
|---|---|---|---|---|---|
| fedex_30627 | 1001 | 209,64 / 1 Touren | 209,69 / 1 Touren | 211,29 / 1 Touren | 211,31 / 1 Touren |
| fedex_30627 | 1002 | 209,70 / 1 Touren | 209,69 / 1 Touren | 211,38 / 1 Touren | 211,34 / 1 Touren |
| fedex_30627 | 1003 | 209,69 / 1 Touren | 209,67 / 1 Touren | 211,26 / 1 Touren | 211,33 / 1 Touren |
| fedex_30627 | 1004 | 209,70 / 1 Touren | 209,64 / 1 Touren | 211,33 / 1 Touren | 211,37 / 1 Touren |
| dpd_30655 | 1001 | 617,01 / 3 Touren | 418,13 / 2 Touren | 418,22 / 2 Touren | nicht bewertbar (Prüfprogrammabbruch) |
| dpd_30655 | 1002 | 418,25 / 2 Touren | 616,27 / 3 Touren | 418,15 / 2 Touren | 418,12 / 2 Touren |
| dpd_30655 | 1003 | 417,77 / 2 Touren | 416,47 / 2 Touren | 418,08 / 2 Touren | 418,38 / 2 Touren |
| dpd_30655 | 1004 | 418,01 / 2 Touren | 417,32 / 2 Touren | 418,27 / 2 Touren | 418,31 / 2 Touren |
| dhl_30519 | 1001 | 2988,95 / 15 Touren | 2974,63 / 15 Touren | 2989,61 / 15 Touren | 2988,96 / 15 Touren |
| dhl_30519 | 1002 | 2993,03 / 15 Touren | 2974,53 / 15 Touren | 2816,59 / 14 Touren | 2800,10 / 14 Touren |
| dhl_30519 | 1003 | 2798,73 / 14 Touren | 2783,45 / 14 Touren | 2801,56 / 14 Touren | 2798,40 / 14 Touren |
| dhl_30519 | 1004 | 2794,80 / 14 Touren | 2794,77 / 14 Touren | 2802,38 / 14 Touren | 2799,35 / 14 Touren |

## Was wurde aus den zwei bisherigen Rückschritten?

Die beiden Rückschritte betrafen den Vergleich des bisherigen Mixes mit **1.8-FAST_REGRET**, nicht mit normalem 1.8-Regret. In der frischen Wiederholung schlagen bereits die bisherigen Mix-Einstellungen die FAST1.8-Referenz auf beiden betreffenden Seeds.

| Fall | Vorher FAST1.8 | Vorher Mix | Vorher Rückschritt | Frisch FAST1.8 | Frisch Mix | Frisch Cluster |
|---|---|---|---|---|---|---|
| uniform / 70 | 5840,00 | 5940,00 | +1,71 % | 5940 | 5920 | 5800 |
| clustered / 81 | 4760,00 | 4840,00 | +1,68 % | 4780 | 4580 | 4560 |

Ein fester Seed macht einen Wandzeitversuch nicht bitgenau reproduzierbar: JIT-Anlauf, tatsächlich erhaltene CPU-Zeit und zeitabhängige Akzeptanz beeinflussen, welche Suchschritte vor der Deadline abgeschlossen werden. Die Diagnose protokolliert außerdem Operatornutzungen. Daher sind die bisherigen kleinen Rückschritte kein belastbarer Nachweis eines systematischen Defekts. Die alte Messung bleibt unverändert dokumentiert und wird nicht durch die neue ersetzt.

Die Quell- und Protokollanalyse zeigt zwei Unterschiede: Der bisherige neue Mix begann in den ursprünglichen Problemfällen mit teureren Startlösungen; die alte FAST1.8-Suche nutzte zusätzlich Cluster-Schritte. Beides sind Anhaltspunkte, keine isoliert bewiesene Ursache. Der zusätzliche Kruskal-Cluster-Operator ist zudem nicht identisch mit dem alten Cluster-Operator.

## Der einzige getestete Verbesserungsversuch

Der bisherige Mix entfernt Aufträge radial / zufällig / nach hohen Kosten mit Gewichten 40/30/30. Der Versuchs-Mix verwendet 30/20/20 und zusätzlich 30 Prozent Kruskal-Cluster. Wiedereinfügen erfolgt ausschließlich mit dem korrigierten FAST_REGRET. Die Entfernmengen bleiben 20–35 Prozent, begrenzt auf 10–50 Aufträge. Bei 389 Aufträgen greift also die 50er-Grenze. Keine Best-Insertion, keine räumliche Ausschlussfilterung. Die Konstruktion verwendet weiterhin Regret; der globale FAST-Schalter und der unabhängige RegretFast-Einfüger behalten ihre dokumentierten unterschiedlichen Tracking-Voreinstellungen.

| Teilmenge | Cluster-Mix gegen | Median | Besser | Schlechter | Größte Änderung nach oben |
|---|---|---|---|---|---|
| Bekannte Problem-Seeds | 1.8 FAST_REGRET | -3,48 % | 2 | 0 | -2,36 % |
| Bekannte Problem-Seeds | 2.0 bisheriger Mix | -1,23 % | 2 | 0 | -0,44 % |
| Vorher unbenutzte Solver-Seeds | 1.8 FAST_REGRET | -3,24 % | 4 | 0 | -0,67 % |
| Vorher unbenutzte Solver-Seeds | 2.0 bisheriger Mix | -0,34 % | 2 | 2 | +1,33 % |

Die vier unbenutzten Solver-Seeds liegen auf denselben zwei synthetischen Nachfragen. Gegen FAST1.8 gewinnt der Cluster-Versuch dort viermal; gegenüber dem bisherigen Mix gewinnt er zweimal und verliert zweimal. Das rechtfertigt einen weiteren Kandidaten, noch keine Behauptung einer generell besseren Einstellung. Die Hannover-Parameter wurden vor den Hauptmessungen festgelegt und nicht anhand ihrer Ergebnisse ausgewählt.

## Warum die Abfahrtskorrektur mitgemessen wird

Beim Vorcheck wurde ein relevanter Integrationsunterschied sichtbar: Vor Beginn der Lieferzeit können große Wartekosten entstehen. HAGRID verschiebt die Abfahrten erst mit seinem AlgorithmEnds-Listener. Rohstände während der Suche sind dadurch nicht dasselbe wie die tatsächlich ausgegebenen Touren. Im historischen 1.8-Referenzpfad ändert dieser Listener die Zeiten, ohne den gespeicherten jsprit-Kostenwert anzupassen. Deshalb werden die tatsächlichen Endkosten in **beiden** Versionen unabhängig nachgerechnet. Der aktuelle HAGRID-Listener aktualisiert den gespeicherten Wert bereits.

Das gemeinsame Budget von 30 Sekunden umfasst Algorithmusaufbau, Startlösung, Suche, den jeweiligen nativen End-Listener, unabhängige Kostenrechnung und die vollständige Lösungskopie. Die Suche endet nach 29 Sekunden; eine Sekunde bleibt für diesen Abschluss. Nur rechtzeitig fertig kopierte Ergebnisse zählen. Ein später fertig werdender Lauf erhält keinen Zeitbonus und wird als fehlende rechtzeitige Lösung ausgewiesen. Unbereinigte SEARCH_EVENT-Zwischenstände bleiben reine Diagnosedaten. Es werden daraus keine Hannover-Zeitgewinnquotienten abgeleitet.

Dabei passen zwei Regeln während der Suche noch nicht vollständig zusammen: Die Sieben-Stunden-Prüfung zieht eine mögliche spätere Abfahrt bereits ab. Die Zielfunktion berechnet Wartekosten dagegen anhand der tatsächlich gespeicherten frühen Ankunft. Erst der End-Listener verschiebt die Abfahrt. Deshalb kann die Suche Touren anders bewerten, als sie anschließend ausgegeben werden. Das ist anhand der Zustandsaktualisierung, Dauer-Constraint, nativen Wartekosten und End-Listener-Reihenfolge nachvollziehbar. Es beweist nicht, dass es die kleinen beobachteten Kostenunterschiede verursacht.

Die relevanten HAGRID-Bausteine sind [Speichern des möglichen tatsächlichen Starts](C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID/hagrid/simulation/src/main/java/hagrid/core/routing/RouteRealStartTimeMemorizer.java), [Dauerprüfung](C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID/hagrid/simulation/src/main/java/hagrid/core/routing/MaxRouteDurationConstraint.java), [Abfahrtskorrektur am Suchende](C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID/hagrid/simulation/src/main/java/hagrid/core/routing/DepartureTimeReScheduler.java) und [Auswahl und Prüfung des ausgegebenen Carrier-Plans](C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID/hagrid/simulation/src/main/java/hagrid/core/routing/CarrierOptimizationService.java).

Eine weitere sinnvolle Stellschraube ist daher, Abfahrtszeiten und Kosten bereits während der Suche konsistent zu behandeln. Das sollte zusammen mit zeitabhängigen Reisezeiten, NCE und FIFO-Eigenschaften geprüft werden; ein beliebiges Verschieben wäre dort nicht gleichwertig. Der hiesige Versuch ändert dafür keine Produktionslogik.

## Grenzen und Betriebsbedingungen

- Vier feste Seeds 1001–1004 je Carrier, vier vollständig rotierende Ausführungspositionen, frische Java-21-JVM je Lauf und fünf Sekunden ausgeschlossene Aufwärmphase. Eine fest zugewiesene logische CPU, Idle-Priorität, 512 MiB Heap. Die synthetische Diagnose behält ihr früheres 384-MiB-Profil; ihre Laufzeiten werden nicht mit Hannover vermischt.
- Gleiches Wandzeitbudget auf derselben CPU garantiert keine exakt gleiche tatsächlich erhaltene CPU-Zeit. Die protokollierte Hauptthread-CPU-Zeit erfasst zudem weder alle JVM-Threads noch sämtliche Garbage-Collection-Arbeit. Daraus wird kein unabhängiger Laufzeitgewinn abgeleitet.
- Mindestens 11,32 GiB freier physischer RAM während der Hauptmessungen. Die 6-GiB-Reserve und der laufende RAM-/CPU-Schutz eigener Prozesse bleiben aktiv. Keine fremden Prozesse verändert oder beendet; keine Automation eingerichtet.
- Statische Netz-Free-Speeds mit den Geschwindigkeitsgrenzen des Fahrzeugtyps. Keine QSim, keine 150 MATSim-Iterationen, kein zeitabhängiges NCE-/Stau-Replanning, keine Warmstarts, keine Shipment-/Supply-/Lastenrad-Prüfung in dieser Messreihe.
- Die 1.8-Referenzen verwenden den nachweislich früheren HAGRID-Solver-Einstieg mit jsprit 1.8 auf denselben aktuellen MATSim-Kosten und Eingaben. Dies ist kein vollständig zurückgebauter historischer MATSim-Gesamtstand.
- Kleine Prozentunterschiede bleiben empfindlich gegenüber Suchstreuung und Hintergrundlast. Der Test unterstützt keine Hochrechnung auf alle 191 Carrier, andere Nachfrage-Tage oder die gesamte Produktionslaufzeit.
- Alle verfügbaren endgültigen Pläne wurden streng geprüft. Rohe Suchzwischenstände folgen einem anderen Abfahrtsvertrag und sind nicht pauschal als gültige Endpläne zertifiziert. Der frühere Kapazitätsfehler ist separat mit nativen Regressionen abgesichert.

## Nächste begründete Versuche

1. **Abfahrtszeit und Wartekosten während der Suche zusammenführen.** Hier gibt es einen konkreten Widerspruch zwischen Dauerprüfung und Kostenbewertung, der die Rangfolge von Touren beeinflussen kann. Ob eine Korrektur bessere Touren oder kürzere Laufzeiten bringt, ist noch ungemessen. Änderungen brauchen Prüfungen mit statischen und zeitabhängigen Reisezeiten.
2. **Entfernmengen nach Problemgröße untersuchen.** Die feste 50er-Grenze erlaubt beim großen Carrier nur knapp 13 Prozent Umbau. Eine größere Obergrenze könnte bessere Änderungen ermöglichen, macht jeden Schritt aber teurer. Dafür wären wieder Vergleiche bei gleichem Zeitbudget nötig; mehr entfernte Aufträge sind nicht automatisch besser.
3. **Startlösung und spätere Suche getrennt vergleichen.** Die zwei synthetischen Problemfälle starteten im neuen Mix mit teureren Lösungen. Ein zusätzlicher Vergleich mit derselben vorgegebenen gültigen Startlösung könnte zeigen, wie viel Unterschied aus Konstruktion und wie viel aus der Verbesserungssuche stammt. Das wurde hier nicht mit dem normalen Anfangslösungsverfahren vermischt.

Diese Punkte sind aus Code und Messung abgeleitete nächste Versuche. Sie wurden in dieser Reihe nicht als Produktionsänderung umgesetzt oder als Gewinn nachgewiesen.

## Nachvollziehbarkeit

Vorab-Auswahl, Seeds, Varianten und Zeitbudget sind im Protokoll festgehalten. Die ersten Vorchecks wurden vor der Hauptmessung korrigiert: zunächst passende generierte Fahrzeugtypen; dann ein eigenes natives Null-Fahrzeug-Kostenprofil; schließlich die vollständige Endnachbearbeitung im Zeitbudget. Ein Auswertungsvorcheck behandelte auf neun Dezimalstellen gleich gedruckte Kosten irrtümlich als Fehler. Alle ursprünglichen Fehlerprotokolle bleiben erhalten. Die 18 synthetischen Messungen wurden unverändert übernommen; die eingefrorene Ablaufprüfung stoppte absichtlich vor Hannover, nachdem dessen Messmethode korrigiert wurde. Kein Hannover-Hauptlauf wurde deswegen wiederholt oder ausgetauscht.

Nach 16 erfolgreichen Hannover-Läufen scheiterte der 17. Prozess erst bei der nachträglichen Prüfung eines rohen Suchzwischenstands. Sein endgültiger DPD-Plan war zuvor nach 29.155 ms vollständig gespeichert und streng geprüft: 122 Aufträge, 277 Pakete, drei Touren, Kosten 617,0062104592. Eine unabhängige Nachrechnung bestätigt diesen Plan. Das Original mit Exitcode 1 bleibt erhalten; fehlende CPU-/Nachlaufkennzahlen werden nicht erfunden. Die genaue Art des damaligen rohen Fehlers ist mangels gespeichertem Rohplan nicht nachträglich beweisbar.

Alle 17 vorhandenen primären Ergebnisse und ihre Dateien wurden unverändert übernommen; zunächst wurden nur noch fehlende Variantenläufe fortgesetzt. Die erste Ergänzung verändert ausschließlich die Diagnose nach Ablauf der Messung: Rohe Dauer wird zusätzlich mit der vom Constraint verwendeten möglichen Abfahrtsverschiebung geprüft, auffällige numerische Zwischenstände werden getrennt protokolliert. Kapazitäts-, Zeitfenster- und sonstige Prüfungen bleiben bestehen; die primäre Endplanprüfung bleibt strikt. Positive und negative Kontrollen prüfen genau diese Unterscheidung. Diese erste Änderung lässt sich bytegenau auf den ursprünglichen Quelltext zurückführen.

Nach 19 gespeicherten Endplänen stoppte der 20. Versuch (`mixcluster20-dpd_30655-1001`) vor dem Speichern eines Endplans. Das Prüfprogramm validierte alle zurückgegebenen Kandidaten, einschließlich möglicherweise ungewählter Reserven, obwohl der native End-Listener nur `Solutions.bestOf` korrigiert. Genau diese beste Lösung wählt auch HAGRIDs CarrierOptimizationService. Welcher Kandidat beim 20. Versuch scheiterte und ob er ausgewählt war, ist mangels gespeicherter Kandidaten nicht rekonstruierbar. Dieser Prüfprogrammabbruch beweist deshalb nicht, dass die ausgewählte Produktionslösung ungültig war. Ihre Gültigkeit bleibt für diesen Versuch unbekannt. Er wurde nicht wiederholt oder durch einen günstigeren Lauf ersetzt.

Die zweite Ergänzung passt die primäre Auswahl ausdrücklich an HAGRID an: zuerst die native beste Lösung auswählen, dann genau diese streng nachrechnen und kopieren. Ungültige beste Lösungen dürfen nicht durch gültige Reservelösungen ersetzt werden. Alle zurückgegebenen Alternativen werden danach getrennt diagnostisch gespeichert. Für alle 19 vorhandenen Endpläne bestätigen die ursprünglichen Kostenprotokolle, dass deren Kosten bereits der nativ ausgewählten Lösung entsprechen. Diese 19 Endpläne und der nicht bewertbare 20. Versuch bleiben erhalten; nur die letzten 28 Versuche wurden gestartet.

Diese zweite Änderung betrifft den gemessenen Abschluss: Die unnötige Reservelösungsprüfung entfällt vor der Snapshot-Grenze. Es gelten weiterhin dasselbe 30-Sekunden-Budget, derselbe Suchstopp nach 29 Sekunden, dieselben Solverparameter und dieselbe strenge Endplanprüfung. Die Messungen gehören trotzdem zu dokumentierten Protokollphasen. Fortsetzung, etwas weniger Abschlussarbeit sowie mögliche JIT-/Hintergrundlastunterschiede begrenzen besonders kleine Unterschiede. Es wird kein durchgängig unveränderter Messaufbau behauptet.

Die neue Auswertung enthält 567 numerische Meldungen zu rohen Zwischenständen. Solche Meldungen dürfen nicht mit ungültigen ausgegebenen Endplänen gleichgesetzt werden; insbesondere ist die strenge physische Dauer vor Abfahrtskorrektur eine andere Prüfung. Vollständige Meldungen, effektive Verletzungszahlen und die gespeicherten ersten auffälligen Rohstände stehen in den Nachweisen.

[Hannover-Auswertung mit Paaren und Hashes](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/hannover-regret-main-20261020-03/summary.json), [synthetische Diagnose](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/regret-cluster-diagnostic-20261020-01/summary.json), [native Straßenprüfung](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/hannover-regret-native-replay-20261020-01/native-plan-replay.json), [gesicherter 17. Endplan und unabhängige Prüfung](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/hannover-primary-recovery-20261020.json), [Auswahlkorrektur und nicht bewertbarer Versuch](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/hannover-native-selection-amendment-20261020.json), [Ablaufnachweis](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/hannover-regret-campaign-20261020-06/campaign.json).

Getestetes Paket SHA256: `FBAEE5668913D0FFB904C10843591FB21526E22C578D20D99468D9557497F706`. Quellen SHA256: `2891CF30E85F13292E33AECB148C0FE647BCFDBBDE9447041A6EF1A939995770`. Produktivcode und Standardstrategie wurden für diesen Versuch nicht geändert.
