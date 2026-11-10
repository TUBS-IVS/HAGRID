# HAGRID-Konfigurationsmigration — Zwischenstand 2026-10-07

**Aktualisierung 9. Oktober 2026:** [Zentrale Solver-Konfiguration](2026-10-09-hagrid-zentrale-solverkonfiguration.md) beschreibt die aktuelle siebenstündige Solver-Regel einschließlich Scenario-Builder. Ältere Aussagen zur festen Initialgrenze oder 27000 Sekunden als allgemeinem Solver-Standard unten sind historisch; die eigenständige 7,5-Stunden-Bedarfsregel bleibt erhalten.

**Aktueller Stand K277–K306:** Die [gemeinsame Konfigurationsdatei](2026-10-09-hagrid-gemeinsame-konfiguration.md) ersetzt die unabhängige 7,5-Stunden-Regel und das implizite modulare 3,5-Stunden-Profil. Weitere Puffer-, Supply-, DRT-, Shared-Use- und LMD-Verbraucher sind jetzt angeschlossen. Die ältere Beschreibung darunter ist historisch.

## Verbindliche Präzisierung

Die Hannover-Solvergrenze bleibt bei **25200 Sekunden (7 Stunden)**. Die vorher geplante Erhöhung auf 27000 entfällt. Lastenräder erhalten eine konfigurierbare Höchstgeschwindigkeit, standardmäßig **20 km/h**. Intern wird diese einmal in m/s umgerechnet. Die Geschwindigkeit ist eine Obergrenze; langsamere Streckenprofile und Verkehrsbedingungen bleiben wirksam.

## Jetzt umgesetzt

`HagridConfigResolver.resolve(defaults, profile, run, cli)` erzeugt ein unveränderliches Ergebnis mit neun benannten Bereichen. Die Reihenfolge ist Defaults → ausdrücklich gesetztes Szenarioprofil → ausdrücklich gesetzter Laufwert → ausdrücklich gesetzter CLI-Wert. `Overrides` enthält nur angegebene kanonische Werte; ein leerer Lauf überschreibt keine Profileinstellungen. Unbekannte Namen, falsche Einheiten/Namen und ungültige Werte werden abgewiesen. `provenance()` und `effectiveValues()` halten die Quelle und den Eingabewert der bisher migrierten skalaren Einstellungen fest.

`hannoverTarget()` behält die 7-Stunden-Solvergrenze, Startiteration 25 und höchstens 16 Carrier je Gruppe. `hannoverLegacySolver()` verwendet nach der Nutzerpräzisierung dieselbe Solvergrenze; es reproduziert allein noch nicht sämtliche alten Scoringregeln. `lausitz(explicitDurationSeconds)` verlangt die eigene Tourgrenze und übernimmt keinen Hannover-Replanning-Zeitplan. Gebietsverwechslungen der benannten Profile werden vorab abgewiesen.

Der Szenario-Parser bewahrt jetzt seine ausdrücklich eingegebenen Schlüssel als unveränderliche Map. `SimulationRoutingConfigAdapter` führt diese mit der vorhandenen Nachfragekonfiguration zusammen. Bereits materialisierte Parserdefaults stehen vor dem Szenarioprofil, ausdrückliche CLI-Einstellungen dahinter. Die bisherigen Konstruktoren bleiben unterstützt: ihre tatsächlich verwendeten Routingargumente sind Laufeinstellungen und erhalten keine erfundene CLI-Herkunft. Gebiet, Konzept, Datum und Tag müssen übereinstimmen.

Lastenradwerte lassen sich zusätzlich über `ScenarioConfig.builder().cargoBikeMaxSpeedKmh(18)` und im Simulationsaufruf über `cargoBikeMaxSpeedKmh=18` angeben. Der bestehende Nachfrage-Runner überträgt ein ausdrücklich gesetztes Builder-Override; der Batch-Generator übernimmt es in die Startargumente. Ein nicht gesetzter Builderwert überschreibt keine vorhandene Profileinstellung. Gemeinsamer Standard bleibt 20 km/h.

Der Hannover-Replanner verwendet jetzt den gemeinsamen Solveraufbau mit aufgelöster Konfiguration. Initialplanung und weitere Scoring-/Erzeugungsverbraucher sind noch nicht vollständig migriert. Nachfrageversorgung, Hub- und DRT-spezifische Erweiterungen sowie vollständige Laufmetadaten sind ebenfalls noch nicht vollständig in das Ergebnis übertragen. Die Geschwindigkeitsangabe wird eingelesen und weitergegeben; ihre tatsächliche Durchsetzung in Fahrzeugadaptern, Routing und QSim folgt in der Modusphase.

## Bedeutung der vorhandenen Solver-Eingänge

| Ablauf | `jspritIter` wird abgebildet auf | Solver-Tourgrenze / erhaltene Besonderheit |
|---|---|---|
| Konventioneller Hannover-Ablauf | `optimization.replanningIterations` | 25200 Sekunden; Nachfrage-Initialbudget bleibt separat, standardmäßig 1. |
| Lausitz LMD_BASELINE | `optimization.offlineIterations` | 25200 Sekunden; `maxTourDuration` des modularen Konzepts wird nicht übernommen. |
| DRT_BASELINE mit Freight | `optimization.offlineIterations` | Vorplanung der konventionellen Carrier, weiterhin 25200 Sekunden. |
| DRT_MODULAR | `optimization.offlineIterations` | Eigene `maxTourDuration`, standardmäßig 12600 Sekunden; keine Hannover-Replanning-Gruppen. |
| DRT_SHAREDUSE oder DRT_BASELINE ohne Freight | Kein Carrier-Solverbudget | In diesen Zweigen wird bisher kein Carrier-jsprit-Auftrag gestartet. Rohwerte bleiben für die Zuordnung erhalten. |

Lausitz verwendet bisher die native Netzbewertung ohne Zonenannäherung und mit null U-Turn-Aufschlag. Die dort bisher ungenutzten allgemeinen Flags `zoneCaching` und `uTurnPenalty` werden als Eingaben bewahrt, aber nicht plötzlich zu wirksamen Routingregeln erklärt. Offline- und Replanning-Budget dürfen in einem aufgelösten Routingauftrag nicht gleichzeitig gesetzt sein.

## Abbildung und Einheiten

| Bisheriger Eingang | Neuer Eingang / Bereich | Einheit und Wirkung |
|---|---|---|
| `HagridConfig.routing.maxRouteDurationSeconds` | Defaults für `tourRules.maxRouteDurationSeconds` | Sekunden. Der bisherige POJO-Default bleibt 27000; das Hannover-Solverprofil setzt ausdrücklich 25200. Eine Auflösung ohne Profil ersetzt noch keine produktive Szenariokonfiguration. |
| Solverkonstante 25200 | Hannover-Profil → `tourRules.maxRouteDurationSeconds` | 7 Stunden bleiben erhalten. Keine implizite Erhöhung auf 7,5 Stunden. |
| Scoring-/Erzeugungsgrenzen 27000 | Erfassung und spätere gezielte Verbrauchermigration | Eigene bisherige Regeln. Eine Angleichung ist fachlich zu prüfen, nicht als bloßer Strukturwechsel zu verstecken. |
| `maxDriverTimeMinutes` | `tourRules.maxDriverTimeSeconds` | Minuten × 60, Default 36000 Sekunden. Nicht mit Tourdauer gleichsetzen. |
| `durationPerParcelMinutes` | `tourRules.durationPerParcelSeconds` | Minuten × 60, Default 120 Sekunden. |
| `maxDurationPerStopMinutes` | `tourRules.maxDurationPerStopSeconds` | Minuten × 60, Default 900 Sekunden. |
| Lieferzeitfenster in Stunden | `tourRules.deliveryWindowStartSeconds`, `deliveryWindowEndSeconds` | Stunden × 3600; Start muss vor Ende liegen. |
| Versteckte Addition in `CarrierVehicleFactory.calculateEndTime` | `tourRules.vehicleAvailabilityBufferSeconds` | Default 3600 Sekunden ausdrücklich erfasst; Umschaltung der Fabrik steht noch aus. |
| `uTurnPenaltyCost` | `tourRules.uTurnPenaltyCost` | Bestehende Score-/Kostenpunkte, keine Umdeutung als Euro oder Sekunden. |
| Nachfragegrenzen `demandBorder`, `dhlBorder`, `carrierMergeThreshold` | `demand.*` | Positive ganze Auftrags-/Paketgrenzen; keine Routing-Cache-Schwellen. |
| `HagridConfig.vehicles.cargoBikeMaxSpeedKmh`, Builder `cargoBikeMaxSpeedKmh`, CLI gleichnamig | Override `fleet.cargoBikeMaxSpeedKmh` → `fleet().cargoBikeMaxSpeedMps()` | Default 20 km/h = 5,555… m/s; explizit z. B. 18 km/h = 5 m/s. Endlich, positiv und darstellbar. Fahrzeugadapter/Routing/QSim folgen in der Modusphase. |
| `isUsingZones` | `routing.isUsingZones` → `legacyZoneProviderEnabled()` | Auswahl des bisherigen Anbieterpfads; unabhängig von tatsächlicher Näherung. Fehlt zunächst, solange der konkrete alte Aufrufer ihn nicht ausdrücklich liefert. |
| `zoneBasedCachingEnabled` / CLI `zoneCaching` | `routing.zoneBasedCachingEnabled` | Räumliche Näherung; standardmäßig deaktiviert. Explizites `false` bleibt ein Override. Für Lausitz-native Offlinepfade bisher unwirksam. |
| `zoneBasedCachingThresholdMeters` / CLI `zoneThreshold` | `routing.zoneBasedCachingThresholdMeters` | Meter, nicht Nachfragegrenze. Der Parserdefault 1500 bei aktivierter Näherung bleibt DEFAULTS; nur ein ausdrücklich eingegebener Schwellwert erhält CLI-Vorrang. |
| `HagridConfig.routing.jspritIterations` | `optimization.initialIterations` | Initialbudget aus der Nachfragekonfiguration, standardmäßig 1. Das Simulations-`jspritIter` darf nicht versehentlich dieses Initialbudget ersetzen. |
| Simulations-`jspritIterations` / CLI `jspritIter` | `optimization.replanningIterations` oder `offlineIterations` | Nach tatsächlichem Ablauf getrennt; kein Überschreiben des Nachfrage-Initialbudgets. |
| Solver-Threads / spätere Zulassung | `resources.internalSolverThreads`, `maxActiveSolvers` | Ausgangspunkt 1 interner Thread; Zahl aktiver Solver zunächst ausdrücklich anzugeben. Keine Vermischung mit Carrier-Gruppengröße. |
| `carrierRoutingCacheEnabled` | `results.carrierPlanCacheEnabled` | Persistenter Plancache, unabhängig vom räumlichen Kosten-Cache. |
| Fahrzeug-XML und aktive Größen | `fleet.vehicleTypesFile`, `activeSizes` | XML bleibt Quelle der Fahrzeugtypen. Listen werden kopiert, nicht global weiterverändert. |

## Ausstehende Schritte und Nachweise

1. Der Simulationsparser und die Lastenrad-Builder-/Startargumentkette sind angebunden. Weitere Nachfrage-, Hub- und szenariospezifische Eingänge schrittweise in denselben Zuständigkeitsvertrag übernehmen.
2. Der Hannover-Simulationseinstieg übergibt jetzt eine einmal aufgelöste Konfiguration an MATSim-Modul, Strategieanbieter und Replanner. Suchbudget, Startiteration, Gruppengröße und Solver-Tourgrenze werden dort verbraucht; Kosten-Cache und U-Turn-Scoringparameter stammen aus demselben Objekt. Alte Konstruktoren bleiben kompatibel; flache Getter beschreiben weiterhin die ursprünglichen Eingaben. Weitere Nachfrage-/Erzeugungs-/Scoring- und Lausitz-Verbraucher sind noch zu migrieren.
3. Jeden fachlichen Regelwechsel separat prüfen. Die 7-Stunden-Solvergrenze ist verbindlich; bestehende Scoring-/Verfügbarkeitsregeln sind noch separat zu behandeln.
4. Kleiner Kontrollvergleich des echten Replanning: alter und aufgelöster Konstruktor bedienen bei gleichen bisherigen Regeln dieselben zwei Aufträge mit einer Tour, gleicher gerouteter Distanz/Fahrzeit und je zwei ausgeführten Iterationen. Dieser Funktionstest ersetzt keinen repräsentativen B2a-Benchmark mit mehreren Seeds und begründet keinen Laufzeit-/RAM-Vorteil.
5. Vollständige jsprit-2.0-Paket-/Gesamtabnahme aus Aufgabe 2 bleibt wegen Valhalla-Priorität offen. Kleine Prüfungen laufen seriell; große Benchmarks folgen unter passenden Ressourcenbedingungen.

## Prüfnachweis für den angeschlossenen Hannover-Verbraucher

197 gezielte Tests bestanden am 2026-10-07, einschließlich acht neuer Konfigurations-/Replanning-Prüfungen. Die echten Mini-Läufe decken auch den MATSim-Strategieanbieter und native Netzbewertung ab. Dabei wurde ein vorhandener Modusfehler sichtbar: Native MATSim-Hilfsfahrzeuge kopieren den Fahrzeugmodus nicht; die jsprit-Vorbereitung erzeugt zusätzlich einen virtuellen Fahrzeugtyp. Der Adapter löst registrierte Typkennungen auf und unterstützt virtuelle Anfragen bei genau einem eindeutig registrierten Modus. Mehrdeutige Vorbereitung benötigt weiterhin den expliziten Kontext der späteren universellen Routing-Umstellung. Pro Modus wird ein Reisezeitadapter angelegt statt pro Linkabfrage. Der Zeitgewinn daraus ist noch nicht gemessen.

Alle Prüfungen liefen seriell, mit einem sichtbaren CPU-Kern, 384 MiB Test-Heap und 512 MiB Maven-/Compiler-Heap. Große Abnahme- und Parallelitätsläufe bleiben wegen Valhalla zurückgestellt. Lastenrad 20 km/h ist als konfigurierbarer Eingang vorhanden; vollständige Durchsetzung in Routing und Simulation folgt in der dafür vorgesehenen Phase.

## Gemeinsamer Solveraufbau und Fehlerkorrekturen

Der echte Hannover-Replanner nutzt jetzt denselben Algorithmusaufbau wie die Routing-Hilfsfunktionen. Die Konfiguration liefert Suchbudget, internen Threadumfang sowie Tour- und Fahrergrenze. Die Fahrergrenze wird innerhalb der verbindlichen Tourgrenze berücksichtigt. Die bisherigen Replanning-Ruinparameter bleiben erhalten. Ein ausdrücklich gesetzter Diagnose-Seed gilt jetzt auch für Replanning; der bisherige Standard 4711 bleibt bestehen. Skills werden ausdrücklich in den gemeinsamen Zustand-/Constraint-Vertrag aufgenommen.

Vier bisher globale, veränderliche Zustandskennungen gehören nun zum jeweiligen StateManager. Ein Test mit verschachtelten zwei Managern reproduzierte zuvor einen Zugriff außerhalb des Zustandsarrays. Bedienzeiten für Services und beide Shipment-Aktivitäten kommen aus der definierten Operationsdauer: 120 Sekunden bleiben auch vor einer Zeitberechnung 120 Sekunden. Bedienung mit Kostensatz 0,5 ergibt 60; Warten verwendet den eigenen Satz. Zuvor konnte die Bedienzeit null und die Bedienkosten fälschlich vom Wartekostensatz abhängig sein.

Die bestehende Überzeitstrafe wird pro Scoring-Komponente einmal statt nach jeder folgenden Aktivität berechnet. Die bisherige Scoringgrenze 27000 Sekunden bleibt getrennt von der Solvergrenze 25200. Die komplette Zuordnung zu Fahrer/Tour wird damit noch nicht umgebaut.

Prüfnachweis: 241 gezielte Tests, keine Fehler und keine übersprungenen Tests; seriell mit einem sichtbaren CPU-Kern, 384 MiB Test-Heap und 512 MiB Maven-/Compiler-Heap. Das belegt die geprüften Regeln, keinen allgemeinen Laufzeitvorteil. Der repräsentative B2b-Vergleich bleibt offen.
## Abfahrtsverschiebung abgesichert

Nach der Suche wird eine spätere Abfahrt probeweise zeitlich neu berechnet. Lieferfenster, Fahrzeugende sowie Tour-/Fahrergrenze müssen weiterhin passen. Ungültige oder nach derselben Suchkostenfunktion teurere Änderungen werden vollständig zurückgenommen; Zustände und Lösungskosten werden aktualisiert. Nicht bediente Aufträge behalten die Strafkosten der ursprünglichen jsprit-Zielfunktion. Der interne Aufruf erhält den echten StateManager und dieselbe Zielfunktion wie die Suche.

Ein alter kontextloser Konstruktor bleibt für den öffentlichen Zeitberechnungshelfer erhalten, verschiebt am Algorithmusende aber keine Abfahrt mehr: ohne Zielfunktion lässt sich deren Wirkung nicht verlässlich bewerten. Produktive gemeinsame Solver verwenden den neuen Konstruktor.

250 gezielte Tests bestanden. Im kleinen statischen Kontrollfall sinken die tatsächlichen Suchkosten von 208 auf 28, weil 90 Sekunden Wartezeit zum Satz 2 entfallen; Aufträge und Fahrzeug bleiben gleich. Zeitabhängige Gegenbeispiele werden zurückgenommen. Das ist ein belegter lokaler Korrekturfall, kein allgemeiner Laufzeit- oder Qualitätsgewinn. Repräsentative Vergleiche und vollständige Abnahme bleiben offen.
## Eingefrorene Reisezeiten — erster Baustein aus Aufgabe 5

TravelTimeSnapshot erfasst Reisezeiten je Modus, Link und Zeitbin und behält danach keine Live-Reisezeitfunktion. Die Linklänge für Fahrzeughöchstgeschwindigkeiten wird ebenfalls eingefroren. Unveränderte Bins teilen einen Wert; gespeichert werden nur Übergänge. Das vermeidet eine dichte Tabelle je Fahrzeug. Ein fehlender Modus, unerlaubter/fehlender Link oder eine Abfrage außerhalb des erfassten Horizonts scheitert mit Diagnose.

Der echte MATSim-Reisezeitrechner wurde im Test mit Ein-/Ausfahrereignissen gefüllt und anschließend zurückgesetzt. Sowohl beobachtete Werte als auch der Ersatzwert eines unbeobachteten Links bleiben unverändert, auch wenn das Live-Netz danach andere Längen/Geschwindigkeiten hat. Ein ausdrücklich als NaN markierter fehlender Messwert verwendet den separat eingefrorenen Ersatzanbieter; negative oder unendliche Werte werden abgewiesen.

Der Zeitvertrag ist ausdrücklich Abtastung am Binanfang mit konstantem Wert innerhalb des Bins. Er übernimmt keine beliebige Interpolation oder innerhalb des Bins liegende NetworkChangeEvents exakt. FIFO, Ereignis-/Auflösungsabgleich und exakte Rückwärtsbewertung folgen in Aufgabe 7; diese Grundlage schaltet noch keinen produktiven Reisezeitanbieter um.

Prüfnachweis: 265 gezielte Tests bestanden, davon 15 Snapshotfälle. Der kleine B3a-Aufbaufall verwendet 1000 Links, zwei Modi, 96 Bins und genau ein veränderliches Profil. Drei Messungen nach zwei Aufwärmläufen ergeben 19,21 / 21,54 / 20,56 ms Aufbauzeit. Gespeicherter numerischer Inhalt: 40024 Byte gegenüber 1552000 Byte derselben numerischen Felder in einer dichten Referenzdarstellung. Das ist keine Messung des tatsächlich gehaltenen Java-Heaps. Beim Aufbau wurden je rund 580 kB im Hauptthread allokiert; der größte beobachtete Prozessspeicherwert war 140247040 Byte, kein garantierter Spitzenwert. Der Messprozess hatte 128 MiB Heap, einen CPU-Kern und niedrige Priorität.

Reproduzierbare Rohdaten: tools/benchmarks/results/B3a-snapshot-2026-10-07/measurements.csv und manifest.json; Runner: tools/benchmarks/run-snapshot-capture.ps1. Der Aufbauwert wird wegen paralleler Last nicht als allgemeiner Laufzeitvorteil bewertet.

Aufgabe 5 ist mit dem eigenen RoutingContext und Modusadapter abgeschlossen. Ein Kontext kopiert das Netz einmal und teilt es anschließend zwischen Carrier-Aufgaben. Topologie, Attribute, NetworkChangeEvents, Reisezeiten und Fahrzeug-Routingprofile bleiben von späteren Änderungen unabhängig. Öffentliche Netzansichten sind schreibgeschützt; nicht unterstützte veränderliche Attribute werden mit Diagnose abgewiesen. Bereits eingefrorene Reisezeiten dürfen nicht mit geänderten Linklängen verbunden werden. Der Modusadapter verweigert unerlaubte Links und ausdrücklich widersprüchliche Fahrzeugmodi.

277 gezielte Tests bestanden. Die zusätzliche B3a-Kontextmessung umfasst 1000 Links, zwei Modi und 96 Bins: Snapshot 7,73 / 8,04 / 9,06 ms, zusätzlicher Kontextaufbau 30,30 / 32,48 / 36,20 ms. Numerischer Profilinhalt bleibt 40024 Byte; kombinierte Hauptthread-Allokationen rund 8,22 MB. Größter beobachteter Prozessspeicherwert 142397440 Byte, kein garantierter Peak. Allokationen und Prozesswerte sind keine Messung des gehaltenen Kontext-Heaps. Rohdaten: tools/benchmarks/results/B3a-context-2026-10-07/measurements.csv und manifest.json. Derselbe begrenzte Runner prüft verfügbare Speicherreserve und unveränderte Eingabefingerprints.

Der Kontext wird noch nicht produktiv für alle Carrier aktiviert. Lebenszyklusverdrahtung bleibt Aufgabe 11, zeitliche/FIFO-Verträge Aufgabe 7. Universeller Kostenanbieter, Cachebegrenzung, Replanning-Koordination und vollständige Lastenrad-Durchsetzung folgen in weiteren Planphasen; Aufgabe 4 bleibt für den repräsentativen B2b/B2a-Vergleich offen.
## Universeller Kostenanbieter — Aufgabe 6

Neue Aufrufer erhalten einen einzelnen MATSim-VRPTransportCosts-Anbieter über RoutingRequest. Die bisherige öffentliche Modus-Map ist dafür nicht erforderlich. Fahrzeugtyp-ID, Geschwindigkeit und Routingkosten werden gegen das eingefrorene Profil geprüft. Der Modus bleibt bis zum Wegesucher und der Ziel-Link-Reisezeit erhalten; native MATSim-Hilfsfahrzeuge ohne Modus werden über die registrierte Typ-ID aufgelöst. Ein Carrier mit gemischten Modi oder widersprüchlicher Vorbereitungsangabe wird früh abgewiesen.

Null-Fahrzeug-Vorbereitung verwendet den ausdrücklich erklärten Modus, einen neutralen Zeitkostensatz und den schnellsten registrierten Modusdeckel. Im kleinen Lastenradfall gelten damit weiterhin 20 km/h. Die Graphvorbereitung geschieht einmal pro Kontext/Modus, nicht pro Carrier. Mutable Wegesucher werden im Zwischenstand pro Modus seriell benutzt; die begrenzte Worker-Wiederverwendung und OD-Caches folgen in Aufgabe 7. Nach Kontextschluss verweigern auch zuvor erhaltene Wegesucher/Reisezeitadapter weitere Arbeit.

288 gezielte Tests bestanden, darunter eine tatsächliche Freight-Tour und elf neue Modusfälle. B3b, sechs Links/zwei Modi: Fahrzeugzeiten Auto 15 s/Rad 36 s, Kosten 75/76 und native Herkunfts-Link-Distanzen 280/180 m. Neutrale Radvorbereitung ergibt 36 s und Kosten 36. Drei Wiederholungen stimmen mit der nativen statischen Fahrzeugreferenz überein. Rohdaten: tools/benchmarks/results/B3b-modal-2026-10-07/measurements.csv und manifest.json.

400 wiederholte Abfragen benötigten 1,01–3,14 ms im neuen noch ungecachten Referenzanbieter gegenüber 0,13–0,30 ms im bereits gefüllten nativen OD-Cache. Das ist absichtlich kein gleichartiger Geschwindigkeitsvergleich und kein belegter Vorteil; der begrenzte neue Cache wird separat eingeführt und gemessen. Modusvorbereitung 1,16–1,29 ms, größter beobachteter Prozessspeicherwert 140808192 Byte, kein garantierter Peak. Alle Prüfungen bleiben klein und seriell.

Die Distanzkonvention wird zusammen mit dem exakten Zeitvertrag in Aufgabe 7 korrigiert, sodass Strecke/Kosten/Zeit dieselben gefahrenen Links beziehen. Rückwärtsabfragen zeitveränderlicher Profile scheitern im Zwischenstand ausdrücklich. Der Anbieter wird vor diesen Gates nicht als exakte produktive Gesamtlösung aktiviert.
## Zeitvertrag — erster Teil aus Aufgabe 7

Die genaue neue Referenz zählt bei Zeit, Strecke und Kosten dieselben Links: Zwischenweg plus Ziel-Link, Herkunfts-Link ausgeschlossen. Im statischen B3c-Kontrollfall ändern sich dadurch die Distanzen von nativ 280/180 auf 300/200 m; Zeiten und Kosten bleiben 15/36 s beziehungsweise 75/76. Drei kleine Wiederholungen bestehen, Rohdaten unter tools/benchmarks/results/B3c-temporal-2026-10-07/.

Rückwärtsabfragen ermitteln innerhalb des erfassten Horizonts die letzte zulässige Abfahrt und prüfen deren Vorwärtsankunft. Bei einer Sprunglücke in erreichbaren Ankunftszeiten enthält die zurückgegebene Zeit einen konservativen Puffer; die Fahrtkosten stammen aus der tatsächlichen Abfahrt. Nicht erreichbare frühe Fristen liefern den Unzulässigkeitswert. Ein eigenes Bereichsdiagnosesignal erlaubt es, zu späte Suchversuche zu verwerfen, ohne andere Routingfehler zu verschlucken.

69 gezielte Tests bestehen, darunter elf Zeitvertragsfälle und die 18 bereits vorbereiteten Zonenfälle. Sinkende stückweise konstante Reisezeitprofile werden als Nicht-FIFO gemeldet, ohne stille Normalisierung. Dijkstra verwendet den ausdrücklich erklärten Modus und benötigt keine unbewiesene Free-Speed-ALT-Untergrenze.

Eine wichtige Einschränkung ist belegt: FIFO einzelner Links reicht bei gemischten Zeit-/Kilometerkosten nicht für eine monotone Ankunft des jeweils billigsten Wegs. Das native MATSim-Gegenbeispiel kommt bei Abfahrt 99 um 140 an, bei Abfahrt 100 dagegen um 111, weil die billigste Route wechselt. Deshalb akzeptiert der neue genaue Zwischenstand veränderliche Profile nur mit positivem reinem Zeitkostenziel; statische gemischte Kosten bleiben unterstützt. Eine andere dynamische Routingpolitik benötigt einen eigenen Nachweis und wird nicht still eingeführt. Vor produktiver Migration muss dies gegen die tatsächlichen Fahrzeugprofile geprüft werden.

Aufgabe 7 ist weiterhin offen: begrenzte Caches und Worker-Lebensdauer, unabhängige Näherungsschalter, Einzelübernahme der vorbereiteten Zonenänderungen und umfassender B3c-Vergleich folgen. Der Zwischenstand ist keine vollständige Integration oder Produktionsabnahme.
## Cache- und Lebensdauer-Unterbau — weiterer Zwischenstand aus Aufgabe 7

Die gemeinsame Routingkonfiguration enthält nun transportCacheEnabled (true), maximumCacheEntries (100000), maximumConcurrentRouters (1) und odTimeQuantizationSeconds (0, genaue Abfahrt). Grenzen und zeitliche Näherung werden über dieselben sparsamen Profil-/Lauf-/CLI-Overrides aufgelöst. Der vorhandene räumliche Zonenmodus bleibt davon unabhängig; seine vollständige neue Verdrahtung steht noch aus.

Der Kontext hält einen begrenzten gemeinsamen Cache für zusammengehörige Zeit-/Distanz-/Kostenwerte. Neutrale Vorbereitung und konkrete Fahrzeugprofile teilen keine Einträge. Fehler werden nicht dauerhaft als Futures gespeichert. Ein gemeinsames Zulassungslimit begrenzt gleichzeitig rechnende Abfragen; je Modus werden mutable Wegesucher bei Bedarf erzeugt und wiederverwendet. Ihr Bestand wächst nicht mit früheren Aufrufer-Threads. Die einmalige Klassifikation konstanter Profile verhindert eine komplette Netzprüfung bei jedem Cachezugriff.

Ein Kontextabschluss sperrt neue Abfragen, prüft laufende eigene Arbeit auf Abbruch und wartet auf deren Freigabe, bevor er seine Graphen, Wegesucher und Caches freigibt. Er unterbricht keine fremden Prozesse oder Executor. Der native Wegesucher wird nicht mitten im internen Rechenschritt gewaltsam beendet. Auch gefüllte Caches prüfen weiterhin den vollständigen Reisezeithorizont; zu späte Fahrten werden abgewiesen.

108 gezielte Prüfungen bestanden nach Cache-/Horizontkorrektur. Danach bestanden 73 gezielte Prüfungen des schlankeren Abfragepfads. Ein zusätzlicher Kontrolltest für veränderliche eigene jsprit-Typen ist geschrieben, aber der abschließende breitere Lauf wurde vor Maven-Start wegen nur 5,75 GiB freiem Speicher zurückgestellt (verbindliche Reserve 6 GiB). Deshalb sind dieser Kontrolltest, der frische breite Abschluss und die Messung nach der Abfrageoptimierung noch offen. Die Änderungen dieses Cache-Unterbaus sind noch nicht als fertige Gesamtaufgabe abgenommen.

Kleine B3c-Rohdaten liegen getrennt unter B3c-bounded-cache-on-2026-10-07, B3c-bounded-cache-off-2026-10-07 und B3c-cache-hotpath-before-2026-10-07 in tools/benchmarks/results/. Bei 10000 wiederholten Abfragen vor dem schlankeren Abfragepfad lagen native warme Cachezeiten bei 3,30 / 1,25 / 0,51 ms, der neue begrenzte Cache bei 22,78 / 18,12 / 12,06 ms. Eingaben, Zeiten/Kosten, drei Cacheeinträge und zwei Wegesucher waren stabil. Diese Messung zeigt weiteren Abfrageaufwand; sie belegt keine Beschleunigung gegenüber dem alten nativen Anbieter. Die abnehmenden Zeiten und Hintergrundlast verlangen eine spätere kontrollierte Bestätigung. Der Messprozess bleibt auf 128 MiB Heap, einen CPU-Kern und niedrige Priorität begrenzt.

Aufgabe 7 bleibt offen für die neue Zonen-/Zeitnäherungsverdrahtung, Einzelübernahme der Legacy-Korrekturen und die restlichen B3c-Nachweise. Aufgaben 8–19 sowie frühere große Abnahmegates bleiben Teil des aktiven vollständigen Integrationsziels. Kein Produktivlauf, Push oder Merge wurde durchgeführt.
Zusätzliche Quellprüfung bei weiterhin gesperrten Tests: Der Übergang vom Reisezeitsnapshot zum Netzkontext prüft bislang Link-IDs, Modi und Längen. Unveränderte Kennungen bei gedrehter Topologie, geänderte Knotenkoordinaten oder nachträglich ergänzte Hintergrundereignisse werden noch nicht geprüft. Drei entsprechende Regressionstests sind vorbereitet; ihre erwarteten Fehler und die anschließende Korrektur stehen wegen der Speicherreserve aus. Die native erneute Reserveprüfung ergibt 2,82 GiB; kein Test-/Messprozess wurde gestartet. Diese offene Identitätsprüfung gehört zur vollständigen Routingabnahme und wird nicht durch bisherige grüne Fälle ersetzt.
## Weiterer Modalnetz-/Cache-Anschluss, 2026-10-08

Der Replanner benutzt registrierte Modusanbieter nun auch bei ausgeschaltetem bisherigen Zonenpfad. Problemgraph, Router und Ziel-Link-Reisezeit stammen aus demselben Anbieter; fehlt dessen Modus, erfolgt kein stiller Wechsel auf car. Im MATSim-Modul bleibt räumliche Näherung nur bei aktivem bisherigen Zonenpfad UND aktiviertem Näherungsschalter zugelassen. Legacy-Konstruktoren ohne Anbieterregistry verwenden einen explizit gefilterten nativen Fallback je Fahrzeugmodus. Vorhandene Live-Caches werden je Vorbereitung erneuert; die universelle eingefrorene Kontext-Lebensdauer und globale Konfigurationslimits sind weiterhin offen. Der zusätzliche globale Pfadrechner entfällt. Importierte exakte Modusnamen werden nach Eingangsvalidierung in QSim-, Routing- und Mess-Konfiguration ergänzt; DRT bleibt beim nativen DVRP-Pfad. Eine optionale zusätzliche Rad-Datei wird erst beim bedarfsgerechten Netzaufbau verlangt. Frische Auswahl174/174 bestanden; kein allgemeiner Leistungsgewinn belegt.


## Aktualisierung: Supply und validierter Hannover-Replanning-Pfad (2026-10-08)

HagridConfig.supply().setBaselineCarriersFile(path) macht den WhiteLabel-Ausgangsplanpfad ausdrücklich konfigurierbar. Der historische Default carrierPlans_Supply.xml bleibt erhalten. Ein Lauf liest den Baselineplan einmal und prüft exakte Hubzuordnung, Modus und den tatsächlichen nativen Fahrzeug-Depotlink; mehrdeutige Depots werden abgewiesen. Dies ist noch keine vollständige Supply-Migration in den unveränderlichen Resolver.

Die aufgelöste HAGRIDSimulationModule-Variante bindet nun den validierenden einmaligen Replanning-Pass. Default Start25 nutzt genau den Reisezeitstand24 für alle Gruppen; Gruppengröße16 und vorhandenes Replanning-Budget bleiben separate Einstellungen. KeepSelected erhält den geprüften Gewinner; die historische BestPlan-/TimeAllocation-Zufallsauswahl entfällt in diesem Pfad. Direkte alte Konstruktoren behalten den Kompatibilitätsablauf. Initialplanung/Lausitz und Scoring-/Erzeugungsregeln sind weiterhin nur teilweise migriert.

Die Geschwindigkeit ist inzwischen in nativen Typen, Routing und tatsächlichen kleinen QSim-Fahrten geprüft: Default20km/h, explizite18km/h, langsameres Straßenprofil und NCE. Ein gemeinsamer FIFO-Link kann ein Auto hinter einem Rad verzögern; keine Kalibrierungs- oder Netzeinflussfreigabe daraus. Der aufgelöste Snapshotpfad diagnostiziert nicht-FIFO und zeitlich variable gemischte Ziele ausdrücklich; der kleine Lebenszyklusnachweis verwendet pro Iteration konstante kontrollierte Messprofile.

## Aktualisierung 2026-10-08: Initialsuche und Ergebnisablage

Die beiden Offline-Initialverbraucher verwenden den gemeinsamen CarrierOptimizationService mit erhaltener Initial-Suchregel/nativen ActivityCosts. Hannover liefert sein konfiguriertes Initialbudget und U-Turn-Kosten; Lausitz liefert das jeweilige bisherige Initialbudget und die bestehende Wellenverschiebung. Schon ausgewählte Pläne und alle neu vorbereiteten Kandidaten werden unabhängig geprüft. Der frühere v1-Cache wird ohne Dateiänderung ignoriert, weil er kanonische Jobs/Fahrzeuge anhand eines Namens ersetzte.

ResolvedHagridConfig.results().carrierPlanCacheEnabled() aktiviert beim ReplanningCoordinator nur mit vorhandenem carrierPlanCacheDirectory die zertifizierte Unterablage validated-replanning-v1. Ohne laufenden JAR-Fingerprint bleibt die IDE-Ablage aus. Der Store benötigt genau den passenden eingefrorenen RoutingRequest-Kontext und ist kein vollständiger Simulationscheckpoint. Einstellungen/Job-/Flotten-/Seed-/Kontext-/Codeänderungen verhindern Wiederverwendung. Ein zusätzlicher separater Exportkontext und die unabhängige Lausitz-Ganzdatei-Hit-Abnahme bleiben offen.

K84 ist der frische gemeinsame Korrektheitsnachweis:498/498,55Rootklassen,0Fehler/Auslassungen,min14,95GiB physisch frei,2JVM-Prozessoren/768MiB-Heaps. Die produktive Carrier-RAM-Zulassung, Warmstarts, optionale Budgets und realen Hintergrundprofile bleiben spätere Plan-/Abnahmegates; kein allgemeiner Leistungsgewinn behauptet.

## Aktualisierung 2026-10-08: Zonenkonfiguration tatsächlich wirksam (K90–K101)

routing.zoneBasedCachingEnabled (Defaultfalse) und zoneBasedCachingThresholdMeters werden jetzt auch im gemeinsamen eingefrorenen ModalTransportCosts-Anbieter angewendet. Diese Einstellung bedeutet räumliche Näherung über vorbereitete Zonenrepräsentanten; transportCacheEnabled/maximumCacheEntries und maximumConcurrentRouters bleiben eigenständige Cache-/Arbeitsgrenzen. Modus und Fahrzeugprofil bleiben getrennt. Die bestehenden Schwellwert-/Override-/Einheitenregeln ändern sich nicht.

Aktuell unterstützt: konstante eingefrorene Reisezeitprofile; tatsächliches Straßenrouting und endgültige unabhängige Tour-/Zeitfensterprüfung verwenden echte Links. Variable Reisezeitprofile plus eingeschaltete Zonennäherung ergeben eine explizite Diagnose, bis deren zeitlicher Vertrag belegt ist. DefaultOFF bleibt der exakte Pfad. Keine neue Konfigurationsdatei und keine automatische Aktivierung.

Der alte öffentliche ZoneBasedTransportCosts.Builder bleibt eine klar gruppierte Kompatibilitätsgrenze für Live-Zeitmodelle, Zeitbins, räumliche Näherung, Cache/Worker und Maut. Sein Thread-Fallback ist keine harte globale Parallelitätsgrenze. Die noch bestehende Legacy-Factory/Typkopplung und vollständige Kennzahlen werden in Aufgaben6/7/18 weiter migriert. K101542/542kleine Tests grün; Produktionsprofile und gepaarte Laufzeit-/Qualitätsmessung offen.
## Aktualisierung K102–K106: Lebensdauer ohne konkreten Zonentyp

Die neue ReplanningTransportCosts-Fähigkeit ändert keine Konfigurationsschlüssel/Defaults. Gruppenvorbereitung erneuert nur Live-Caches; eingefrorene Request-Anbieter behalten ihren aufgenommenen Reisezeitstand. Diagnose/Zone-Metadaten folgen tatsächlichem räumlichem Schalter und dem bestehenden Verbraucher-Opt-in. Native Anbieter ohne diese Fähigkeit werden weiterhin unterstützt.

Die parameterlose Auto-Factory und die Map-Factory sind jetzt Deprecated(forRemoval=false) und als rohe Legacy-Kompatibilität gekennzeichnet. Ihre Implementierungen bleiben erhalten. createVRPTransportCosts(RoutingRequest) ist der dokumentierte neue Hauptweg; der aufgelöste feste Replanning-Pass benutzt bereits die gemeinsamen Request-Kontexte. Ein Map-Rückgabewert ist keine Voraussetzung für zeitabhängige Reisezeiten. K106550/550kleine Prüfungen bestanden; Ressourcen-/Parallelitätszulassung bleibt Aufgabe14.
## Teilstand K107–K114: Ausführung und Reservierung

Der feste Replanning-Pass benutzt nun CarrierBatchExecutor. Seine bisherigen Konstruktoren bleiben seriell. Der explizite neue Konstruktor benötigt einen aufruferseitig begrenzten Executor und einen Carrier-Reservierungsschätzer; der Koordinator besitzt und schließt diesen Executor. Die explizite Zulassung verlangt positive Byte-Schätzungen und verweigert Überschreitungen vor Problemaufbau.

Es entstehen keine neuen Konfigurationsschlüssel und keine automatische Erhöhung der Produktparallelität. resources.maxActiveSolvers muss noch mit der real kalibrierten Laufbudgetpolitik verbunden werden; die bestehende internalSolverThreads-Auswertung des Suchdienstes bleibt erhalten. Synthetische Byte-Werte in Executor-/Koordinator-Tests belegen Zulassungslogik, keine tatsächlichen Carrier-Heapgrößen. Die6-GiB-Reserve bleibt der vereinbarte Schutz eigener Prüfprozesse während Valhalla, kein neu erfundenes allgemeines Produktionslimit.

K114568/568kleine Prüfungen bestanden. RAM-Kalibrierung, gemeinsame Budgets einschließlich gestagter Ergebnisse und Migration der Initial-/Legacy-Pools bleiben Aufgabe14.
## Aktualisierung K116–K141: gemeinsame Ausführung und optionale Suchregeln

Initialplanung/Delivery/Supply, der rohe historische Replanner und Lausitz benutzen nun ebenfalls CarrierBatchExecutor. Die bisherigen ThreadingType-Namen sind kompatible Bezeichnungen derselben Ausführung. Der Standard bleibt seriell, bis reale Reservierungen kalibriert sind; explizite Konstruktoren erlauben bereits budgetierte kontrollierte Ausführung. Keine automatische Produktparallelität aus synthetischen Test-Bytewerten.

optimization.warmStartEnabled ist ein strikter Boolean, Defaultfalse. Gültige Altpläne werden anhand aktueller Job-/Fahrzeugkennungen gegen den aktuellen eingefrorenen Kontext geprüft und als frei optimierbare jsprit2.0 SolutionSpec übersetzt. Verschobene Abfahrten und nicht abbildbare Klon-/Mehrfachtour-Fahrzeuge ergeben erklärten Kaltstart. Ein ungültiger Warmstart verhindert keine reguläre Suche.

optimization.adaptiveSearchEnabled ist Defaultfalse. Bei true müssen optimization.adaptiveMinimumIterations, optimization.adaptiveMaximumIterations und optimization.adaptivePlateauIterations explizit positive Ganzzahlen sein; Minimum darf Maximum nicht überschreiten. Erstplanung bleibt unabhängig davon eine Iteration. Der Abbruchgrund und die tatsächlich ausgeführten Iterationen werden protokolliert; ein Plateau ist kein Optimalitätsnachweis.

Der gemeinsame CSV-Schreiber erhält die bisherigen13Spalten und ergänzt Kontext, Prüfstatus, tatsächliche Iterationen, Vorbereitung/Suche/Endrouting/Prüfung, Such-/Endkosten, MATSim-Score, Warmstartstatus/-grund und Suchabbruchgrund. Historische Cachephasen bleiben leer/unbekannt. Cache-Ladezeit wird nicht als historische jspritComputationTime eingetragen.

Beim regulären Controller-Ende schreibt die feste Integration carrier-routing-outcomes.csv nach erneuter Prüfung des aktuell ausgewählten Plans und mit dessen aktuellem MATSim-Score. Die Endkosten beziehen sich auf den eingefrorenen Routingkontext. Routine-GC und feste Fünf-Sekunden-Pause sind entfernt.

K139: fünf gepaarte winzige Warm-/Kaltfälle mit identischen10Iterationen und gleichen Endkosten110.0; Warmstart-Median20.901ms versus17.127ms kalt. Kein allgemeiner Leistungsgewinn; Default bleibt aus. Breite K1401282/1284bestanden; die zwei veralteten Fixtures sind in K14163/63betroffenen Tests korrigiert. Noch kein vollständiger Integrationsabschluss.
## Einzel-Carrier-Replay (Aufgabe13, K148)

Die freiwillige Diagnosefunktion hagrid.core.routing.CarrierReplay.exportCase(directory, carrier, request, config, seed) schreibt einen vollständigen einzelnen Suchfall: kanonische Jobs/Flotte, ausgewählter Altplan, exakt aufgelöste Einstellungen und Verweis auf den eingefrorenen Kontext. Sibling-Verzeichnisse teilen ../.contexts/<Kontextkennung>.zip. Dieses Paket enthält das Netz nur einmal, komprimierte numerische Reisezeitübergänge und genaue Netzereignisse; es wird vor Veröffentlichung erneut eingelesen und anhand derselben Fingerprints geprüft.

CarrierReplay.replay(directory) nutzt denselben CarrierOptimizationService. Es gibt keinen alternativen Solver und keinen Rückfall auf Free-Speed, wenn Kontext oder Prüfsummen fehlen. Bestehende Fallverzeichnisse werden nicht überschrieben. Unvollständige Fälle ohne manifest.json sind nicht abspielbar. Nicht exakt von der nativen Serialisierung erhaltene Eingangsmetadaten ergeben eine Diagnose statt stiller Veränderung. Zum Verschieben der Fälle die gemeinsame .contexts-Ablage mitnehmen. Ein anderes Java-/jsprit-/HAGRID-Build kann trotz gleicher Daten andere Suchergebnisse erzeugen; der Replay ist kein versionsübergreifendes Gleichheitsversprechen.

Beispiel nach tatsächlich geprüftem Package-Build:
java -Xms32m -Xmx384m -XX:ActiveProcessorCount=1 -cp <HAGRID-jar> hagrid.core.routing.CarrierReplay <Fallverzeichnis>
Während Valhalla auch diesen eigenen Prozess ausschließlich mit Invoke-HagridGuardedProcess starten; Heap/CPU-Parameter allein schützen die6-GiB-Reserve nicht. Ein separater kleiner JVM-Prozess ist in K148 tatsächlich geprüft, das ausgelieferte shadedJAR bleibt der Paketabnahme vorbehalten.

K148:42/42kleine Tests bestanden, zehn konkrete Replay-Fälle inklusive Services, präziser Shipment-Paare, Warmstart, historischer Flottenklone, ursprünglichem geschlossenem Kontext, eigenständigem JVM-Aufruf und zeitlich variablem reinem Zeitkostenprofil mit NCE60.125s. Kein vollständiger MATSim-Checkpoint.

## Lausitz-Ganzdatei-Cachetreffer (Aufgabe12, K151)

Die beiden echten HitReplay-Aufrufer prüfen jetzt unabhängig die aktuelle Carrier-/Jobdomäne, Modalität, Flottenpolitik und gespeicherten Straßenpläne gegen die frisch erzeugte Nachfrage. Baseline erlaubt ausdrücklich vorhandene _hH_vN_tN-Wellenklone mit der bestehenden Abfahrts-/21Uhr-Endregel; Modular/nonretimed erlaubt diese Ergänzung nicht. Native XML-Zeitrundung wird nur innerhalb der privaten Cacheprüfung nach derselben Time.writeTime/parseTime-Regel berücksichtigt. Originale Daten, globale Zeitformate, Seeds und GOLDEN-Ausgaben ändern sich nicht.

Ein semantisch ungültiges Paket wird vor dem Kopieren mit IOException diagnostiziert; der bestehende optionale Cachepfad berechnet dann frisch. Eine alte fertig gespeicherte Datei wird nicht automatisch gelöscht, und die vorhandene globale Widerspruchs-/BLOCKED-Politik bleibt erhalten. K15166/66kleine native Baseline-/Modular-/Cache-/Gegenprüfungen bestanden, einschließlich tatsächlicher unveränderter Baseline-GOLDEN-/Bytegleichheitstreffer.
## Ausgabeintervalle und Veröffentlichung (K167–K177)

Die zentralen Schlüssel results.eventsWriteInterval und results.plansWriteInterval sind optional und positiv; ohne explizite Angabe bleiben die geladenen MATSim-XML-Werte erhalten. results.freightHistogramWriteInterval hat den bisherigen Standard 1. Die CLI-Aliase sind eventsEvery, plansEvery und freightHistogramEvery. Null, negative Werte und Integer-Überlauf werden vor dem Start diagnostiziert. Finale Ereignisse, native Endpläne und beide Histogramme bleiben auch außerhalb des Intervalls erhalten; ein echter kleiner Vier-Iterationen-Lauf prüft dies.

Neue Ergebnisse/Replay-Manifeste enthalten diese Einstellungen vollständig. Die erweiterte aufgelöste Konfiguration verändert Zertifikate: Ein altes Archiv ist kein stillschweigend gleichwertiger neuer Routingkontext. K177 prüft das K176-Paket tatsächlich in separaten 384-MiB-/1-CPU-JVMs mit vollständigen Services und Shipments.

CarrierRouter behandelt alle Modal-/Delivery-/Supply-Gruppen als einen Offline-Versuch. Spätere Fehler setzen ausgewählte Pläne, Planlisten und Metadaten zurück. Die beiden Carrier-Erzeuger setzen zudem ihre Änderungen an Scenario, Hubmengen, Merge-Log, Summary und Fahrzeugtypen zurück. OutputFileSet serialisiert sämtliche Dateien vor dem Austausch; Austauschfehler stellen die vorherigen Dateien wieder her. Bei Wiederherstellungsfehlern bleiben Sicherungen mit Diagnose erhalten. Das ist ein Rückabwicklungsvertrag bei beobachteten Fehlern, kein gemeinsamer Dateisystem-Commit bei Maschinenabsturz.

Die vier Ausführungswege verwenden CarrierBatchExecutor. runStaged hält die vollständigen Carrier-Reservierungen bis zur Übernahme der ganzen Gruppe und prüft vorher, ob deren Summe passt. Gemeinsame MATSim-/Snapshot-/Graph-/Cache-Daten und bereits veröffentlichte Pläne benötigen zusätzlich ein bemessenes Laufbudget. Der Standard bleibt seriell, bis dafür repräsentative Kalibrierung vorliegt. Kleine Referenzmessungen sind keine automatische Produktions-RAM-Schätzung.

## Gemeinsame Solver-Ressourcen (K200–K202)

CLI solverThreads steuert resources.internalSolverThreads; maxActiveSolvers steuert resources.maxActiveSolvers. Beide sind positive Integer mit CLI-Vorrang, ohne Angabe bleiben die bisherigen Defaults. Beispiel für einen bewusst seriellen Lauf: solverThreads=1,maxActiveSolvers=1. Eine höhere äußere Obergrenze erzeugt keinen zusätzlichen Pool; dessen Größe, gemeinsames Basis-/Ergebnisbudget und Carrier-Schätzungen müssen vom kalibrierten Run-Owner kommen.

Demand-Code konfiguriert denselben Vertrag über hagridConfig.resources().setInternalSolverThreads(...) und setMaxActiveSolvers(...). snapshot() friert beide Werte ein. Initial-HAGRID und Lausitz-Baseline/Modular erhalten sie bis zur echten Suche und Zulassung; Lausitz-Ganzdateischlüssel nehmen die tatsächlich verwendeten Einstellungen auf. Alte Methoden delegieren mit einem internen Solver-Thread und nicht gesetzter äußerer Grenze; die Standardpoolgröße bleibt eins. Suchbudgets, Initialkonstruktion, Ruin-Regeln, Wave-Retime und Tourgrenzen werden dadurch nicht ersetzt.

Mehrthreadige eigene Algorithmen außerhalb des gemeinsamen Dienstes laufen synchron innerhalb HAGRIDRouterUtils.withConfiguredAlgorithm(..., algorithm -> ...). Die rohe configureAlgorithm-API mit mehreren Threads wird zurückgewiesen, da sie keinen unabhängigen Fehler-/Abbruch-Lebenszyklus besitzt. Der scoped Pfad setzt einen HAGRID-eigenen Executor vor dem Build, verwendet keinen nativen Shutdown-Hook und hält den äußeren Worker/dessen Reservierung bis zum tatsächlichen Ende aller internen Callbacks.

K200119/119 und die Modular-Ressourcenkontrolle in K201 bestanden. K202 bestätigt sämtliche CLI-/Verbraucher-/Regressionsprüfungen mit1351/1351Fällen und Paketbau. K203 prüft das neue Paket erfolgreich für vollständige Services/Shipments. Produktiv-RAM und optimale Parallelität sind weiter offen.
## Laufbezogenes Speicherbudget (Aufgabe 14, K208–K214)

CarrierRunBudget liefert die vor jeder nichtleeren Gruppe neu beobachtete Zulassungsgrenze. State enthält sechs explizite Byte-Werte: Heapgrenze, aktuell gemeinsam belegter Heap, Heapreserve, freier physischer RAM, physische Reserve und nativer Startspielraum. Die verfügbare Carrier-Reservierung ist das Minimum aus Heapgrenze minus gemeinsamer Belegung/Heapreserve und freiem physischem RAM minus physischer Reserve/nativem Spielraum. Die Subtraktion wird ohne überlaufende Summen geprüft; fehlende, ungültige oder erschöpfte Messwerte verhindern den Problemaufbau.

CarrierBatchExecutor(int, LongSupplier, Duration) ergänzt den bisherigen Konstruktor mit konstantem long-Budget. Ein Laufbesitzer kann denselben begrenzten Executor mit diesem Budget an Initial-HAGRID, Legacy-Replanning, den festen Koordinator oder Lausitz übergeben. Der Executor beansprucht zuerst exklusiv die Gruppe, liest dann genau einmal ihre Grenze und hält diese bis zum Ende unverändert. Ein gleichzeitiger zweiter Aufruf wird vor der Messung zurückgewiesen. Auch eine fehlgeschlagene Messung gibt die Gruppenbesitznahme wieder frei. Staged-Gruppen prüfen die Summe aller vollständigen Carrier-Reservierungen vor VRP-Aufbau und halten sie bis zur Veröffentlichung.

forCurrentJvm(physicalProbe, physicalReserve, heapReserve, nativeHeadroom) ist ein optionaler Beobachter des gesamten aktuellen JVM-Heaps. MATSim, Kontext, Cache, bereits veröffentlichte Pläne und noch nicht eingesammelter Garbage sind darin enthalten; Abfrage und Carrier-Schätzungen können dem tatsächlichen Verbrauch hinterherlaufen. Der Aufrufer muss den physischen Messwert, konservative Reserven und Carrier-Schätzungen liefern. Keine erzwungene GC, kein eigener nativer Messdienst und keine erfundene Standardreserve. Das ist Zulassungsrechnung, kein harter RAM-Nachweis oder Ersatz des laufenden ResourceGuard.

Keine neuen CLI-Schlüssel oder automatische Produktparallelität: Die bisherigen seriellen Standardkonstruktoren bleiben erhalten. Repräsentative Carrier-Bytekalibrierung und Aktivierung einer gemeinsamen Produktionsbudgetpolitik stehen noch aus. K214 besteht mit 68/68 gezielten Prüfungen, einschließlich echter zwei MATSim-Carrier-Gruppen: Nach gewachsener gemeinsamer Basis bleiben die ersten gültigen veröffentlichten Pläne erhalten, während die nächste Gruppe vor Aufbau/Übernahme blockiert wird. Die Byte-Werte dieser Kontrolle sind synthetisch. K213 wurde zuvor wegen CPU-Druck vom Schutz abgebrochen; Die reparierte Fassung besteht anschließend mit 1362/1362 kleinen Prüfungen und Paketbau in K215; K216 prüft genau das Paket erfolgreich mit separaten Service-/Shipment-Replays, vollständigen Ergebnissen und gleichem Kontext.

Beispiel für einen Laufbesitzer, nachdem physische Abfrage, Reserven und Carrier-Schätzer tatsächlich kalibriert sind (keine neuen Defaults):

```java
var budget = CarrierRunBudget.forCurrentJvm(
    availablePhysicalBytes, physicalReserveBytes, heapReserveBytes, nativeHeadroomBytes);
var batches = new CarrierBatchExecutor(
    calibratedWorkerCeiling, budget, java.time.Duration.ofSeconds(60));
try (var coordinator = new ReplanningCoordinator(
        resolvedConfig, seed, resultStore, batches, calibratedCarrierBytes)) {
    // Der Controller ruft coordinator.runGroup(...) in den vorgesehenen Iterationen auf.
    // Der Koordinator besitzt und schließt den übergebenen Executor.
}
```

availablePhysicalBytes ist ein gültiger LongSupplier, calibratedCarrierBytes ein ToLongFunction<Carrier>. calibratedWorkerCeiling ist eine evidenzbasierte Obergrenze; resources.maxActiveSolvers kann sie im Dienst nur weiter reduzieren. Ein Timeout bedeutet bei nicht beendeten Workern keine vorzeitige Reservierungsfreigabe oder sichere Wiederverwendung. Alle bisherigen Eigentums-/Abbruchregeln gelten auch beim dynamischen Budget.
