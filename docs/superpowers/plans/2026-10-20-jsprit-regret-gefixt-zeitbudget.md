# Korrigiertes FAST_REGRET und Regret-Mix gegen jsprit 1.8

Stand: 20. Oktober 2026. Alle 32 vorab festgelegten Messläufe abgeschlossen. Es gab keine Auswahl schöner Ergebnisse und keine Nachjustierung der Solver während des Versuchs.

## Ergebnis

Gegen normales **1.8-Regret** liefert der neue Mix in **8 von 8 Fällen günstigere Touren**. Die Kostenänderung beträgt im gepaarten Median **-2,85 %**. Dieselbe Endqualität dieser Referenz erreicht er in 8 von 8 Fällen früher, im gepaarten Median mit **46,6 % weniger Zeit bis zum Qualitätsziel**. Für diese Zeitangabe sind alle acht Fälle enthalten; es gibt keine verfehlten Ziele gegenüber dieser Referenz.

Gegen **1.8 FAST_REGRET** ist der Mix in **6 von 8 Fällen günstiger**, in **2 Fällen teurer**. Der gepaarte Median beträgt **-3,17 %**; die größte Verschlechterung **+1,71 %**. Diese beiden Referenzen führen deshalb zu unterschiedlichen Antworten auf die Frage nach einem durchgängigen Vorteil.

Das Ziel „schneller ohne schlechtere Touren“ wird in diesen acht Fällen nicht durchgehend erreicht. Die Tabelle weist alle Verschlechterungen und verfehlten Qualitätsziele aus. Ein günstiger Median reicht für eine Umstellung nicht aus.

Negative Kostenänderungen bedeuten günstigere Touren. Die Mediane sind aus den jeweils gepaarten Fällen berechnet; acht Seeds auf zwei festen synthetischen Nachfragen sind keine acht unabhängigen realen Verkehrsszenarien.

| Variante | Median Kosten gegenüber 1.8 Regret | Schlechtere Fälle | Median gegenüber 1.8 FAST_REGRET | Schlechtere Fälle |
|---|---:|---:|---:|---:|
| 2.0 FAST_REGRET, korrigiert | -1,52 % | 2 / 8 | -1,80 % | 3 / 8 |
| 2.0 Regret-Mix, korrigiert | -2,85 % | 0 / 8 | -3,17 % | 2 / 8 |

Von **96 vorgegebenen Zeitpunkten** lagen an **95** vollständige gültige Lösungen vor; an **1** frühen Zeitpunkten gab es noch keine vollständige Lösung. Alle **32 Endpunkte nach 30 Sekunden** und alle **762 gespeicherten Verbesserungslösungen** bedienen 120 von 120 Aufträgen und haben null unabhängig erkannte harte Verletzungen. Es wurde im gemessenen Verbesserungsprotokoll keine überladene Lösung beobachtet. Zusätzlich war der Ursachenfix zuvor mit 51 nativen Regressionen und zwei gesonderten Prüfungen jeder vollständig entdeckten Kandidatenlösung abgesichert worden.

Frühe Punkte ohne vollständige Lösung bleiben ausdrücklich Teil des Ergebnisses:

| Variante / Nachfrage / Seed | Zeitpunkt ohne vollständige Lösung |
|---|---:|
| 2.0 FAST_REGRET, korrigiert / clustered / 81 | 5 s |

## Wird dieselbe Qualität früher erreicht?

Ziel ist jeweils die Endqualität der passenden 1.8-Referenz nach 30 Sekunden. Verglichen wird der erste aufgezeichnete Zeitpunkt, an dem dieselben oder niedrigere Tourenkosten vorliegen. Ein nicht erreichtes Ziel zählt als Fehlschlag innerhalb des Budgets und bekommt keine erfundene Erfolgszeit. Die Referenz muss ihr Endergebnis ebenfalls erst finden; sie erhält nicht pauschal eine Zielzeit von 30 Sekunden.

| Variante / Referenzziel | Früher erreicht | Später erreicht | Gleich schnell | Nicht in 30 s erreicht |
|---|---:|---:|---:|---:|
| 2.0 FAST_REGRET, korrigiert / 1.8 Regret | 5 / 8 | 1 / 8 | 0 / 8 | 2 / 8 |
| 2.0 FAST_REGRET, korrigiert / 1.8 FAST_REGRET | 5 / 8 | 0 / 8 | 0 / 8 | 3 / 8 |
| 2.0 Regret-Mix, korrigiert / 1.8 Regret | 8 / 8 | 0 / 8 | 0 / 8 | 0 / 8 |
| 2.0 Regret-Mix, korrigiert / 1.8 FAST_REGRET | 6 / 8 | 0 / 8 | 0 / 8 | 2 / 8 |

Die vollständigen Zielzeiten gegenüber 1.8 FAST_REGRET stehen unten. Die maschinenlesbare Auswertung enthält zusätzlich den Vergleich mit normalem 1.8-Regret. Eine Zeitratio nur erfolgreicher Fälle ist dort ausdrücklich bedingt und darf nicht die Zahl der verfehlten Ziele ersetzen.

## Wo unterscheiden sich die Ergebnisse?

| Variante | Verteilte Aufträge: Median ggü. 1.8 FAST_REGRET | Schlechter | Gebündelte Aufträge: Median | Schlechter |
|---|---:|---:|---:|---:|
| 2.0 FAST_REGRET, korrigiert | -1,01 % | 2 / 4 | -2,93 % | 1 / 4 |
| 2.0 Regret-Mix, korrigiert | -2,50 % | 1 / 4 | -5,22 % | 1 / 4 |

Nach 30 Sekunden ergeben sich folgende Tourenkosten. Es sind synthetische Zielfunktionswerte, keine Eurobeträge.

| Nachfrage / Seed | 1.8 Regret | 1.8 FAST_REGRET | 2.0 FAST_REGRET | Neuer Regret-Mix |
|---|---:|---:|---:|---:|
| verteilt / 70 | 6040 | 5840 | 5980 | 5940 |
| verteilt / 71 | 6000 | 6000 | 6020 | 5860 |
| verteilt / 72 | 5920 | 6020 | 5860 | 5860 |
| verteilt / 73 | 5940 | 5960 | 5820 | 5740 |
| gebündelt / 80 | 4980 | 4800 | 4560 | 4480 |
| gebündelt / 81 | 4920 | 4760 | 5260 | 4840 |
| gebündelt / 82 | 4960 | 4780 | 4560 | 4560 |
| gebündelt / 83 | 4940 | 4800 | 4740 | 4520 |

Erste aufgezeichnete Zielerreichung gegenüber 1.8 FAST_REGRET:

| Nachfrage / Seed | Zielkosten 1.8 FAST_REGRET | Referenzzeit | Korrigiertes FAST_REGRET | Neuer Mix |
|---|---:|---:|---:|---:|
| verteilt / 70 | 5840 | 18,65 s | in 30 s nicht erreicht | in 30 s nicht erreicht |
| verteilt / 71 | 6000 | 16,06 s | in 30 s nicht erreicht | 10,16 s |
| verteilt / 72 | 6020 | 26,01 s | 12,63 s | 15,11 s |
| verteilt / 73 | 5960 | 17,94 s | 13,07 s | 5,85 s |
| gebündelt / 80 | 4800 | 25,66 s | 8,67 s | 10,62 s |
| gebündelt / 81 | 4760 | 15,38 s | in 30 s nicht erreicht | in 30 s nicht erreicht |
| gebündelt / 82 | 4780 | 22,34 s | 10,03 s | 7,99 s |
| gebündelt / 83 | 4800 | 25,80 s | 17,19 s | 7,92 s |

## Was genau gemessen wurde

- Vier Profile: 1.8 Regret, 1.8 FAST_REGRET, korrigiertes 2.0 FAST_REGRET und korrigierter neuer Regret-Mix. **REGRET_INSERTION für die Startlösung; Best/Cheapest Insertion in allen Profilen mit Gewicht null.** Die registrierten Modulobjekte können trotzdem unbenutzte Best-Insertion-Objekte enthalten; entscheidend sind die geprüften aktiven Strategiegewichte.
- Neuer Mix: Entfernen räumlicher Gruppen / zufälliger Aufträge / teurer Aufträge mit Auswahlgewichten 0,4 / 0,3 / 0,3; danach ausschließlich FAST_REGRET. Je Schritt werden 20–35 % der Aufträge entfernt, begrenzt auf 10–50. Kein zusätzlicher räumlicher Filter.
- Zwei Nachfrageformen mit jeweils vier Seeds: verteilte Aufträge, Nachfrage 5678 und Seeds 70–73; gebündelte Aufträge, Nachfrage 9876 und Seeds 80–83. Sechs bisherige Fälle werden wiederholt; die beiden zusätzlichen Seeds wurden vor dem Hauptlauf festgelegt. Alle vier Varianten durchlaufen jede Reihenfolgeposition je Nachfrageform einmal.
- Derselbe 35×35-Knoten-Raster, 100-m-Links, 10 m/s, 120 Service-Aufträge, Kapazität 40, 60 s Bedienzeit, sieben Stunden Einsatzgrenze. Dieselben aktuellen nativen MATSim-Transportkosten in allen Profilen. Die 1.8-Referenzen verwenden den nachweislich historischen Solver-Einstieg und jsprit 1.8; dies ist kein vollständig zurückgebauter alter MATSim-Gesamtstand.
- Java 21, eine für die JVM sichtbare CPU, dieselbe feste logische CPU-Zuordnung, Idle-Priorität, 384 MiB Heap und frische JVM je Messlauf. Vor jeder Messung fünf Sekunden ausgeschlossene Aufwärmphase. **30 Sekunden Zeitbudget einschließlich Algorithmusaufbau, Startlösung und Suche.** Gemeinsamer Netz-/Problemaufbau liegt davor. Gleiche zeitabhängige native Akzeptanzregel: Anfangsschwelle 3 %, Alpha 0,15.
- Nur Lösungen, deren unabhängige Kopie vor der Deadline fertig war, zählen. Der Prozess darf eine bereits begonnene Iteration beenden; deren spätere Lösung bekommt keinen Qualitätsbonus. Größter Nachlauf: 382,1 ms. Die 5-/15-Sekundenwerte sind Zwischenstände der 30-Sekunden-Abkühlung und keine eigenständigen Kurzläufe.
- Die tatsächlich geladenen Core-/FAST-Klassen wurden im Messprozess per Bytecode-Hash geprüft. Eingaben und kompilierte Testklassen wurden einmalig eingefroren und vor weiteren Läufen verglichen. Historische Quellen wurden gegen ihr früheres Zertifikat geprüft. Das ausführbare korrigierte Paket blieb über alle Messungen unverändert.
- Kapazität, Bedienung, Linkzuordnung, Zeitfenster, Ankunfts-/Endzeiten, Einsatzgrenze und Tourenkosten wurden unabhängig nachgerechnet. Die Prüfung gespeicherter Verbesserungen erfolgt nach der Zeitmessung. Sie ist keine Prüfung sämtlicher verworfener Kandidaten während dieser Leistungsmessung; dafür gab es die getrennten instrumentierten Fehlerdiagnosen.

## Grenzen und nächste Konsequenz

**Dokumentierte Auswertungskorrektur:** Nach der 22. tatsächlichen Messung stoppte das Prüfskript, weil es irrtümlich schon nach fünf Sekunden eine vollständige Lösung verlangte. Der betreffende FAST_REGRET-Lauf hatte gültige Ergebnisse nach 15 und 30 Sekunden. Sein langsames Ergebnis wurde unverändert beibehalten. Eine datierte Ergänzung zum Protokoll behandelt frühe fehlende Lösungen ausdrücklich als solche; sie verbessert keine Messung. Alle 22 vorhandenen Messungen wurden mit identischen Rohlog-Hashes übernommen und ausschließlich die zehn noch fehlenden geplanten Messungen fortgesetzt. Ein erster Fortsetzungsversuch scheiterte vor einem neuen JVM-Messlauf an einer zu strengen Prüfung der zwei Profilmeldungen für Aufwärm- und Hauptphase; auch dieser Fehlversuch ist separat erhalten. Kein Solverlauf wurde deshalb wiederholt oder ersetzt.

Die Fortsetzung fand später statt und kann anderen Hintergrundlasten ausgesetzt gewesen sein. Feste CPU, Heap, Aufwärmphase, Reihenfolge und Schutzregeln blieben gleich; die tatsächlich erhaltene Hauptthread-CPU-Zeit steht in den Rohdaten. Diese Einschränkung ist bei kleinen Zeitunterschieden zu beachten.

Nach Abschluss aller 32 erfolgreichen Messprozesse wurde zusätzlich ein reiner Auswertungsfehler korrigiert: Eine leere Gruppe wurde in PowerShell als zusätzlicher leerer Wert gezählt. Die Auswertung V3 prüft stattdessen die tatsächlich vorhandenen Zeitpunkte. Die ursprünglichen Fehlerprotokolle und sämtliche Messdaten bleiben unverändert erhalten. Das erfolgreiche Endergebnis stützt sich auf die geprüften Prozessausgänge, Rohlogs, Klassen-/Quellhashes und die unabhängige Nachrechnung; kein alter Fehlerstatus wurde still überschrieben.

Die Ergebnisse gelten für kleine synthetische Service-Probleme auf einem konstant schnellen Netz. Reale Hannover-Carrier, Shipment-Aufträge, mehrere Fahrzeugprofile sowie zeitabhängige MATSim-Reisezeiten/NCE sind damit nicht abschließend bewertet. Gleiche verfügbare Zeit auf derselben CPU bedeutet nicht exakt identische tatsächlich erhaltene CPU-Zeit. Die Rohdaten enthalten die CPU-Zeit und Allokationen des Hauptthreads; das ist weder die gesamte JVM-CPU-Zeit noch der RAM-Höchststand.

Mindestens **7,20 GiB** freier physischer RAM wurden beobachtet. Die verbindliche 6-GiB-Reserve blieb durch den laufenden Schutz eigener Prozesse abgesichert. Keine großen Produktionsläufe, keine Änderung der HAGRID-Standardeinstellungen und keine neue Automation.

Die historischen Ergebnisse des ungepatchten Pakets werden nicht mit diesen Messungen zusammengeworfen. Ein entfernter Korrektheitsfehler beweist keinen Geschwindigkeitsgewinn. Sichere Aktualisierung arbeitet unter Umständen mehr als fehlerhaftes Überspringen. Der native globale FAST-Schalter und die unabhängige RegretFast-API haben unterschiedliche Tracking-Voreinstellungen; die gemessenen Gesamtprofile enthalten auch diesen Unterschied.

## Nachweise

[Vorab festgelegtes Protokoll](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/fixed-regret-time-campaign-20261020-01/protocol.json), [datierte Ergänzung für frühe fehlende Lösungen](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/fixed-regret-time-main-20261020-03/protocol-amendment.json), [vollständige Auswertung mit Paaren und Rohlog-Hashes](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/fixed-regret-time-main-20261020-03/summary.json). Neben der Auswertung liegen alle 32 Einzellogs, Messpunkt-/Verbesserungskurven, gepaarte Ergebnisse, Start-/Ressourcenprotokolle und die eingefrorenen Quellen/Klassen. Die Ergebnisse wurden nach Abschluss unabhängig anhand der Rohdaten gegengeprüft.

Getestetes Paket SHA256: FBAEE5668913D0FFB904C10843591FB21526E22C578D20D99468D9557497F706. Quellstand SHA256: 2891CF30E85F13292E33AECB148C0FE647BCFDBBDE9447041A6EF1A939995770.