# Regret und neue jsprit-Strategien bei gleicher Rechenzeit

Stand: 20. Oktober 2026. Produktionscode und Standardeinstellungen wurden durch diesen Versuch nicht verändert.

## Ergebnis auf einen Blick

Die unabhängige Auswahl fiel auf **2.0 neuer unabhängiger Mix + FAST_REGRET**. Bei den sechs anschließend geprüften neuen Fällen beträgt die mediane Kostenänderung **-4,2 %** gegenüber 1.8 Regret und **-0,2 %** gegenüber 1.8 FAST_REGRET. Positive Werte bedeuten schlechtere Tourenkosten. In **2 von 6** Fällen war die Variante schlechter als 1.8 Regret, in **2 von 6** schlechter als 1.8 FAST_REGRET.

Die ausgewählte 2.0-Variante dominiert die 1.8-Kontrollen nicht in allen neuen Fällen. Damit ist „schneller ohne schlechtere Touren“ für alle geprüften Fälle weiterhin nicht erreicht. Ein günstiger Median allein reicht für eine Übernahme nicht.

Alle gewerteten Lösungen bedienen sämtliche 120 Aufträge und haben null unabhängig erkannte Regelverletzungen. Die Zeitfenster, Kapazität, Zuordnung der Aufträge zu Links, Ankunfts-/Endzeiten und die Sieben-Stunden-Grenze wurden nachgerechnet. Die Kosten werden unabhängig aus dem Netz und den Touren ermittelt; Suchkosten und nachgerechnete Kosten stimmen überein.

Zusätzlich wurden 2 frühere Zwischenlösungen von 2.0 mit globalem FAST_REGRET beanstandet. Sie werden nicht als erfolgreiche Zielerreichung gezählt. Die Zusatzdiagnose reproduzierte einen dieser Tourenstände exakt und wies eine Überladung nach: 41 Aufträge bei Kapazität 40. Die einzelnen Prüfkategorien des anderen ursprünglichen Zwischenstands sind noch nicht vollständig isoliert. Das ist ein weiterer Grund gegen eine automatische Produktionsfreigabe.

An 0 früheren Zwischenpunkten lag noch keine vollständige Lösung vor; solche Zwischenpunkte gelten nicht als Erfolg. Die verglichenen Endpunkte nach 15 beziehungsweise 30 Sekunden sind vollständig.

## Was genau verglichen wurde

Die Startlösung verwendet überall REGRET_INSERTION. Auf ausdrücklichen Wunsch sind auch während der Verbesserungssuche sämtliche Best-/Cheapest-Insertion-Anteile in allen Kontrollarmen ausgeschaltet. Diese Regret-only-Kontrollen unterscheiden sich deshalb absichtlich von der früheren gemischten Vergleichsreihe mit dem 61-%-Zeitunterschied. Deren Zahlen werden hier nicht vermischt.

- **1.8 Regret und 1.8 FAST_REGRET:** bisheriger HAGRID-Einstieg mit der jeweiligen jsprit-Version und ausschließlich Regret bei der Wiederherstellung.
- **2.0 Regret und 2.0 FAST_REGRET:** aktuelle Integration, jeweils mit langsamer beziehungsweise schneller Regret-Implementierung.
- **Neuer unabhängiger Mix:** Aufträge werden radial, zufällig oder nach hohen Entfernungskosten entfernt; Wiederherstellung ausschließlich mit FAST_REGRET. Gewichte 0,4 / 0,3 / 0,3.
- **Kruskal-Mix:** zusätzlich das neue gruppenweise Entfernen über Kruskal; Gewichte Kruskal 0,5 / radial 0,3 / zufällig 0,2. Wiederherstellung ausschließlich mit FAST_REGRET.

Die beiden neuen Mischungen entfernen jeweils 20–35 % der Aufträge, begrenzt auf 10–50. Es wurde kein räumlicher Filter eingeschaltet. Eine zusätzliche Quellprüfung zeigte jedoch einen wichtigen Unterschied der 2.0-APIs: Der globale FAST_REGRET-Schalter belässt Affected-Job-Tracking standardmäßig aktiv; die unabhängig registrierte Insertion.regretFast()-Wiederherstellung setzt es ausdrücklich auf false. Diese Unterschiede gehören damit zu den getesteten Gesamtprofilen. Ein Effekt kann nicht isoliert nur der Entfernungsmethode zugeschrieben werden. Alle Arme verwenden dieselbe aktuelle native MATSim-Kostenimplementierung, dasselbe Netz und dieselbe Nachfrage. 1.8 bezeichnet hier den ursprünglichen Solver-Einstieg mit jsprit 1.8, nicht einen komplett wiederhergestellten historischen MATSim-Stack.

## Gleiche Zeit statt gleicher Iterationszahl

Die Uhr beginnt **vor dem Aufbau des Algorithmus**. Strategievorbereitung, Initiallösung und Verbesserungssuche zählen damit ins gleiche Budget. Der gemeinsame Aufbau von Netz und Routingproblem liegt davor. Die Bereitschaft, vorübergehend schlechtere Lösungen zu akzeptieren, folgt in allen Armen derselben zeitabhängigen nativen Schrimpf-Regel: 3 % Anfangsschwelle, Alpha 0,15. Die Schwelle wird direkt beim Akzeptieren aktualisiert. Die Produktions-Abbruchregel bei ausbleibender Verbesserung ist für den Versuch durch die gemeinsame Deadline ersetzt; die Produktionskonfiguration selbst bleibt unverändert.

Eine nach Ablauf fertig gewordene Iteration erhält keinen Qualitätsbonus: Gewertet wird nur die beste vollständige Lösung, deren unabhängige Kopie vor der Deadline vorlag. Der Prozess darf die begonnene Iteration noch beenden; der größte gemessene Nachlauf betrug **278,9 ms**. 5-/15-Sekundenpunkte innerhalb eines längeren Laufs sind Zwischenstände seiner Abkühlung, keine eigenständig optimierten Kurzläufe.

Java 21, ein sichtbarer Prozessor, feste Zuordnung zum logischen Prozessor 0, Idle-Priorität und 384 MiB Heap. Vor jeder Messung läuft eine ausgeschlossene Aufwärmphase von nominal 5 Sekunden. Jede Variante/Seed-Kombination verwendet eine frische JVM. Hauptrechenzeit und insgesamt neu angelegte Bytes sind zusätzlich in den Rohdaten erfasst; Allokation bedeutet keinen gleichzeitig belegten RAM-Höchststand. Die Pflichtreserve von 6 GiB blieb geschützt; mindestens **10,2 GiB** freier physischer RAM wurden beobachtet.

## Vorauswahl: sechs Seeds, jeweils 15 Sekunden

120 räumlich verteilte Service-Aufträge, feste Nachfrage 1234, Solver-Seeds 50–55. Alle sechs Varianten durchlaufen alle sechs Positionen der Reihenfolge. Die Auswahlregel wurde vor dem Hauptlauf gespeichert: ungültige/fehlende Varianten ausschließen; dann möglichst wenige schlechtere Fälle gegenüber 1.8 Regret, niedrigste mediane gepaarte Kostenänderung, geringste maximale Verschlechterung, niedrigster CPU-Median und feste Gleichstandsregel. Damit wird kein einzelner schöner Lauf herausgepickt.

| Variante | Median Kostenänderung gegen 1.8 Regret | Besser / gleich / schlechter |
|---|---:|---:|
| 1.8 Regret (Referenz) | 0,0 % | 0 / 6 / 0 |
| 1.8 FAST_REGRET | +0,3 % | 2 / 1 / 3 |
| 2.0 Regret | +1,4 % | 2 / 0 / 4 |
| 2.0 FAST_REGRET | +1,0 % | 2 / 0 / 4 |
| 2.0 neuer unabhängiger Mix + FAST_REGRET | -0,5 % | 3 / 0 / 3 |
| 2.0 Kruskal-Mix + FAST_REGRET | +1,4 % | 1 / 0 / 5 |

Innerhalb von 2.0 allein verändert FAST_REGRET die Kosten gegenüber dem normalen Regret bei gleicher 15-Sekunden-Frist im gepaarten Median um **+0,3 %**. Das ist von der Frage getrennt, ob 2.0 auch die 1.8-Kontrolle schlägt.

## Neue Nachfrage zur Kontrolle: jeweils 30 Sekunden

Die ausgewählte 2.0-Variante wurde anschließend ohne Nachjustieren gegen beide 1.8-Kontrollen getestet: neue verteilte Nachfrage 5678 mit Seeds 70–72 und neue gebündelte Nachfrage 9876 mit Seeds 80–82. Die drei Varianten durchlaufen jede Reihenfolgeposition einmal. Tourenkosten sind synthetische Zielfunktionswerte, keine Eurobeträge.

| Nachfrage / Seed | 1.8 Regret | 1.8 FAST_REGRET | Ausgewählte 2.0-Variante | Änderung gegen 1.8 FAST_REGRET |
|---|---:|---:|---:|---:|
| Verteilt / 70 | 6040,0 | 5840,0 | 5820,0 | -0,3 % |
| Verteilt / 71 | 5880,0 | 5920,0 | 5960,0 | +0,7 % |
| Verteilt / 72 | 5920,0 | 5980,0 | 6180,0 | +3,3 % |
| Gebündelt / 80 | 4860,0 | 4820,0 | 4500,0 | -6,6 % |
| Gebündelt / 81 | 5000,0 | 4760,0 | 4760,0 | 0,0 % |
| Gebündelt / 82 | 4900,0 | 4780,0 | 4580,0 | -4,2 % |

Um die Zielqualität von 1.8 Regret zu erreichen, reichten die 30 Sekunden für die ausgewählte 2.0-Variante in **2 von 6** Fällen nicht. Gegenüber der Zielqualität von 1.8 FAST_REGRET waren es **2 von 6**. Nicht erreichte Ziele werden ausdrücklich als solche ausgewiesen und nicht künstlich als 30-Sekunden-Erfolge gezählt:

| Neue Nachfrage / Seed | Zielkosten 1.8 FAST_REGRET | 1.8 braucht dafür | 2.0 braucht dafür |
|---|---:|---:|---:|
| uniform / 70 | 5840,0 | 5,8 s | 27,9 s |
| uniform / 71 | 5920,0 | 12,8 s | in 30 s nicht erreicht |
| uniform / 72 | 5980,0 | 9,1 s | in 30 s nicht erreicht |
| clustered / 80 | 4820,0 | 26,9 s | 7,0 s |
| clustered / 81 | 4760,0 | 18,9 s | 21,4 s |
| clustered / 82 | 4780,0 | 22,3 s | 7,2 s |

## Zusatzdiagnose der FAST_REGRET-Auffälligkeit

Nach dem Hauptvergleich wurden weitere vollständige Kandidaten einzeln geprüft. Mit dem nativen Tracking-Standard lautet der Status **REPRODUCED_INVALID_COMPLETE_CANDIDATE**; explizit mit abgeschaltetem Tracking **NO_VIOLATION_OBSERVED_IN_THIS_REPLAY**. Anzahl erfasster überladener Touren im Standard-Replay: 1. Nachgewiesene Last/Kapazität: 41/40. Die vollständigen Routen und tatsächlichen Tracking-Flags sind gesichert. Seed 54 lieferte dabei in Iteration 3 denselben beanstandeten Tourenhash wie in der Hauptkampagne. In beiden zusätzlich geprüften Seeds 54 und 53 wurde bei explizit abgeschaltetem Tracking keine Verletzung beobachtet.

Diese Zusatzläufe sind instrumentierte Diagnosen und gehen nicht als Zeit-/Qualitätsmessungen in die 54 Hauptläufe ein. Die Tracking-Voreinstellungen der zwei APIs unterscheiden sich tatsächlich. Eine Gegenprobe ohne Wiederauftreten ist noch kein Beweis, dass damit jede Fehlerursache behoben wäre: Zeitbudgetsuche kann auf andere Suchpfade gelangen. Insbesondere werden im Hauptlauf finale gültige Lösungen von möglicherweise unzulässigen Zwischenkandidaten unterschieden. Der globale FAST_REGRET-Schalter wird aufgrund dieses Befunds nicht für die Produktion freigegeben. Der Produktionsstand nutzt weiterhin seine bisherigen Einstellungen.

[Zusätzlicher Diagnose-Nachweis](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/equal-time-fast-diagnostic-summary-20261020.json). Der erste Startversuch der Zusatzdiagnose scheiterte an einem fehlerhaften JVM-Startparameter, bevor eine Messlösung erzeugt wurde; dieser Fehlversuch gehört ebenfalls nicht zur Kampagne.

## Was daraus folgt und was offen bleibt

Ein Zeitbudgetvergleich beantwortet die Frage, welche gültige Tour nach derselben verfügbaren Zeit vorliegt. Mehr Iterationen allein bedeuten noch keinen Vorteil. Die Zielzeittabelle zeigt zusätzlich die erste aufgezeichnete gültige Zielerreichung. Bei Profilen mit ungültigen Zwischenständen können weitere gültige Zwischenstände oberhalb des ungültigen Kostenminimums im bisherigen Ledger fehlen; dort wäre diese Zeit nur eine obere Grenze, kein exakter erster Zeitpunkt.

In den sechs neuen Kontrollfällen hatten die drei beteiligten Varianten keine beanstandeten aufgezeichneten Zwischenstände; ihre Zielzeittabelle ist von dieser Einschränkung nicht betroffen. Die zwei Auffälligkeiten stammen aus dem separaten globalen FAST_REGRET-Arm der Vorauswahl.

Es gibt weiterhin keine automatische Umstellung der Produktion. Eine allgemeine Behauptung über Hannover, simulierte Reisezeiten/NCE, Shipment-Aufträge, mehrere Fahrzeugprofile oder parallele Solver lässt sich aus diesem kleinen konstant schnellen Netz nicht ableiten. Für eine Übernahme sind die realen Carrier und ihre tatsächlichen Nebenbedingungen gesondert zu prüfen. Die Refactorings und die moderne Integration werden dadurch nicht mit einem nachgewiesenen Vorteil der Suchstrategie gleichgesetzt.

Die Kampagne umfasst 54 gemessene JVM-Läufe: 36 zur Vorauswahl und 18 für die neuen Kontrollfälle. Checkpoints desselben Laufs sind keine unabhängigen Wiederholungen. Es wurden keine Produktionsheuristiken, Budgets oder Defaultwerte geändert.

Nachweise: [vorab gespeichertes Protokoll](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/equal-time-strategy-protocol-20261020.json), [Kampagnenstatus und Auswahl](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/equal-time-regret-campaign-20261020-01/campaign.json), [Vorauswahl](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/equal-time-regret-discovery-20261020-01/summary.json), [verteilte Kontrollnachfrage](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/equal-time-regret-holdout-uniform-20261020-01/summary.json), [gebündelte Kontrollnachfrage](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/equal-time-regret-holdout-clustered-20261020-01/summary.json). Jeweils daneben: Einzellogs, Lösungskurven, Messwerte, Input-/Quell-/Paket-Hashes und Ressourcenschutz. Paket: AEA4574827F2ED7058041556AF6F0AC0797A04342A19B904FDBBF533052AB1EE. Der kurze Smoke02 wurde nach einer Änderung seines Launchers von der Identitätsprüfung verworfen und gehört nicht zur Auswertung.


## Nachtrag: Ursachenfix nach dieser Messkampagne

Der oben dokumentierte serielle FAST_REGRET-Kapazitätsfehler wurde anschließend in HAGRID als versionierter jsprit-2.0.0-Backport korrigiert und mit51 neuen Regressionen, der vollständigen verfügbaren Gesamtabnahme, Pakettreue und zwei Problem-Seed-Replays abgesichert. Details: [FAST_REGRET-Kapazitätsfix](2026-10-20-fast-regret-kapazitaetsfix.md). Die historischen Messwerte oben bleiben Ergebnisse des damaligen ungepatchten Pakets; sie dürfen nicht als neue Leistungsmessung des korrigierten Pakets ausgegeben werden. Es erfolgte keine automatische Umstellung des Produktionsstandards.