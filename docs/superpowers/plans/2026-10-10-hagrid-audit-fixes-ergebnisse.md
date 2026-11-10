> Aktueller Performance-Nachtrag vom 20.10.2026: [jsprit-Performance und offene Gesamtabnahme](2026-10-20-jsprit-performance.md). Die folgenden Zahlen gehören zum historischen Basisbericht.

# HAGRID: Audit, Fehlerbehebung und Refactor einfach erklärt

Stand: 14. Oktober 2026. Arbeitskopie: `codex/zone-router-fixes`.

**Der aktuelle HAGRID-Code ist gebaut und die verfügbaren automatisierten Prüfungen sind grün: 1.600 bestandene Java-Tests, 33 bestandene Python-Auswertungstests und ein separater Replaytest des frisch gebauten JAR.** Ein Lausitz-Realdatentest wurde wegen fehlender Nachfrage übersprungen. Die native MATSim/Freight-Suite bleibt mit 19 bekannten Assertion-Abweichungen rot. Eine uneingeschränkte Gesamt- oder Produktionsfreigabe ist deshalb nicht erteilt.

## Was ist erledigt, getestet und noch offen?

| Paket | Änderung und Zweck | Aktueller Nachweis / Grenze |
|---|---|---|
| T01 | Ein Master-Seed; getrennte Zufallsfolgen je Aufgabe und stabiler ID | Konfiguration, Nachfrage-/Supply-Consumer und Tag-Isolation getestet. Andere Bibliothek oder Parallelität garantiert keine bitgleichen Lösungen. |
| T02 | Fehler weitergeben; Netzimport erst vollständig prüfen, dann veröffentlichen | Pipeline-/ScenarioRunner-Fehler und Netzkonflikte getestet. Kein Erfolgsexport nach Pflichtfehler. |
| T03 | Gemeinsamer Carrier-Merge einschließlich Jobs, Metadaten und fehlender Sendungen | Erhaltung, Rollback, Split und nativer XML-Roundtrip getestet. |
| T04 | Supply-Baseline je Hub, Richtung, Lane und Depot; ganzzahlige Verteilung | Mehrere Depots, Richtungen, Rundung, Seed-Isolation und 200-Paket-Roundtrip getestet. Leere Vorlagen zählen nicht als positive Lieferung. |
| T05 | Kosten vor Export abschließen; Score-Abfrage verändert nichts | Lifecycle-/Export-Gegenfälle und Ereigniskosten getestet. |
| T06 | Tatsächliche native Fahrten und Aktivitäten bewerten | Getrennte Touren, Rückfahrt, Service, interleavende Fahrer sowie 0/1-LinkEnter-Fahrten getestet; beobachtete Zeit und tatsächlich gemessene Distanz getrennt. |
| T07 | Alte Plan-Jobs vollständig mit aktuellen Jobs vergleichen | Fenster, Skills, Kapazität und veraltete Payloads getestet. |
| T08 | Gemeinsamer Link-Routing-Vertrag einschließlich Abbiegeverbote | Adapter, veröffentlichte Straßen und echte QSim-Grenzfälle getestet. Fahrzeug-ID-Bindung am nativen Übergang korrigiert. |
| T09 | Getrennte exakte/Zonen-Cache-Schlüssel, begrenzter No-Path-Fallback | Unerreichbarer Vertreter, erreichbarer Original-Link, negative Einträge, Parallelität und Lebensdauer getestet. |
| T10 | Modulare Stops behalten Zeitfenster; Wartezeit und verspätete Ausführung stimmen | Echte QSim-Wartefahrt und durch native Netzänderung verzögerte Fahrt bestanden. Service bleibt erhalten; Verspätung und vollständige Zustellung beobachtet. |
| T11 | Dünner Kostenadapter, eigene Routing-Engine, allgemeine Factory | Moduswahl, Car/Bike, Factory-Verträge und Lifecycle getestet. Legacy-Adapter bleiben klar begrenzt. |
| T12 | Eigene geprüfte Kandidatenkopien; Prüfung an Inputs, Plan und Kontext binden | Mutationen und Gewinnerauswahl getestet; Zähler belegen Wiederverwendung. Kein gemessener allgemeiner Vorher/Nachher-Speedup behauptet. |
| T13 | Modusnetze/-graphen erst bei erster Verwendung vorbereiten | Ein Graph je verwendetem Modus, gleichzeitiger Erstzugriff, echter Konstruktionfehler mit Wiederholung und ein Admission-Permit getestet. Kleine Speicherprobe gemessen. Geteilte immutable Adjazenzsicht und Produktionskalibrierung bleiben offen. |
| T14 | Reisezeit-Kompatibilität zentral prüfen und erklären | Synthetische Profil-/NCE-Gegenfälle getestet. Das tatsächlich verwendete Hannover-Hintergrundprofil ist noch nicht verfügbar. |
| T15 | Größerer gepaarter Zonenvergleich bei 1,5/3/5 km | 48 Messläufe, 16 separate Warmups, Services/Shipments mit 16/32 Jobs und drei unabhängigen Seeds vollständig gemessen und nachbewertet. Ergebnis gemischt; Tabelle unten. |
| T16 | Gemeinsame Abnahme, neue Paketidentität, native Fahrtests, Quellcodeprüfung und Dokumentation | Verfügbare HAGRID-Abnahme und Paket-Replay bestanden. Native 19 Abweichungen, reale Hannover-Eingaben, repräsentativer RAM und Git-Integration bleiben eigene offene Gates. |

Die Tests belegen die genannten funktionalen Gegenfälle. Weniger Vorbereitung und weniger wiederholte Prüfschritte sind beobachtbar; ein allgemeiner Laufzeit- oder RAM-Gewinn gegenüber dem gesamten alten HAGRID-Stand ist damit noch nicht gemessen.

## Was hat der große Testlauf zusätzlich gefunden und korrigiert?

1. **Falsche Fahrzeugbindung auf veröffentlichten Straßen.** Der Kostenadapter verwendete intern ein Typfahrzeug. Dessen ID konnte in einer Route bleiben, obwohl MATSim Freight für die Ausführung ein anderes Fahrzeug erzeugt. Nun erhält eine direkte Adapterantwort die tatsächliche Caller-ID auf einer Kopie; bei Tour-Publikation wird die ID geleert, damit native Freight sie selbst bindet. Kein gemeinsamer Cache-Eintrag wird verändert. Die echte Simulation mit vier Carriern, beiden Modi und Services/Shipments läuft jetzt durch.
2. **Replay verlor Informationen, die native XML nicht speichert.** Depotzeitfenster, Straßen-Distanz, Reisezeit und Fahrzeug-ID müssen für die strikte Planidentität erhalten bleiben. Eine kleine eigene Metadatenstruktur ergänzt diese Felder. Vor Wiederherstellung werden Touren, Grenzen und Straßen geprüft; anschließend muss der komplette Fingerprint stimmen. Manipulierte Fenster werden weiterhin abgelehnt. Alte Previous-Plan-Captures ohne die präzisen Metadaten müssen neu erzeugt werden.
3. **Compiler- und Testfixturefehler.** Fehlender Import und zwei AssertJ-Wildcard-Aufrufe wurden korrigiert. Veraltete lokale Erwartungen wurden auf die bereits vereinbarten Verträge aktualisiert: lazy Graphaufbau, getrennte exakte/Vertreter-Cacheeinträge, drei zusätzliche Diagnosefelder und zwingende unveränderliche Metadaten. Die nativen Upstream-Referenzen wurden nicht geändert.
4. **Noch fehlende Integrationsgegenfälle.** Neue Tests führen einen echten ersten Graphbaufehler mit erfolgreicher Wiederholung und eine tatsächliche QSim-Verzögerung nach Fahrbeginn aus. Im Verzögerungsfall wird die frühere Wartezeit nicht erneut angehängt; Service dauert weiterhin zehn Sekunden und die geladene Sendung wird ausgeliefert.

## Euer beabsichtigter Ablauf bleibt erhalten

1. Nachfrage, Carrier und Fahrzeuge mit der zentralen Konfiguration erzeugen.
2. Initiale Touren mit einer jsprit-Iteration vorbereiten.
3. Reisezeiten des Hintergrundverkehrs einmal erfassen und als unveränderlichen Routing-Kontext bereitstellen.
4. Alle Carrier auf derselben Grundlage neu planen. Die zusätzlichen etwa 500 Fahrzeuge ändern diese vorgegebene Reisezeitgrundlage in diesem Ansatz nicht.
5. Kandidaten, alte Pläne und endgültige Straßen unabhängig vom jsprit-Suchscore auf Zulässigkeit prüfen.
6. Nur gültige Ergebnisse veröffentlichen und gefahrene Touren aus MATSim-Ereignissen bewerten.

Die geringe Rückwirkung der etwa 500 Fahrzeuge ist eine Modellannahme, keine durch diese kleinen Tests bestätigte Aussage. Supply-Ankunft und frühestmögliche letzte Zustellung sind weiterhin nicht zeitlich gekoppelt; das wäre eine zusätzliche fachliche Erweiterung.

## Zonenvergleich: Größere Schwellen helfen teilweise

Gemessen wurden ein synthetisches 24×24-Netz mit 500-m-Linkabstand, 2-km-Zonen, vier endlichen Fahrzeugen, zehn tatsächlich ausgeführten Suchiterationen, ein sichtbarer JVM-Prozessor und 384 MiB Heap. Je Variante gibt es drei unabhängige Seeds; Warmups wurden getrennt und ausgeschlossen. Gesamtdauer meint Optimierung einschließlich abschließender Straßen-/Ergebnisprüfung, ohne JVM-Start, Nachfrageaufbau oder komplette MATSim-Simulation. Alle 48 Messläufe haben keine unzugeordneten Jobs und keine harten Prüfverletzungen. Kosten werden für beide Varianten exakt nachbewertet.

**Negative Prozentwerte sind hier besser:** weniger Zeit beziehungsweise niedrigere Kosten. Jede Tabellenzeile enthält zwei getrennte Mediane über die drei Seeds; sie behauptet nicht, dass Zeit und Qualität im selben Einzelfall gemeinsam besser sind.

| Auftragsart | Jobs | Schwelle | Änderung Gesamtdauer, Median | Änderung exakt nachbewerteter Kosten, Median |
|---|---:|---:|---:|---:|
| Services | 16 | 1.5 km | 8.4 % | 2.8 % |
| Services | 16 | 3.0 km | 13.8 % | 8.3 % |
| Services | 16 | 5.0 km | 10.3 % | 2.8 % |
| Services | 32 | 1.5 km | -4.7 % | 12.2 % |
| Services | 32 | 3.0 km | -2.5 % | 3.2 % |
| Services | 32 | 5.0 km | 0.4 % | 1.1 % |
| Shipments | 16 | 1.5 km | -8.1 % | 12.3 % |
| Shipments | 16 | 3.0 km | -3.0 % | 11.6 % |
| Shipments | 16 | 5.0 km | -1.9 % | 2.0 % |
| Shipments | 32 | 1.5 km | 14.3 % | 11.0 % |
| Shipments | 32 | 3.0 km | -1.8 % | 3.7 % |
| Shipments | 32 | 5.0 km | -26.9 % | -4.3 % |

Bei 32 Shipments und 5 km sind die Gesamtlaufzeiten im Median etwa 27 % kürzer. Die einzelnen Seeds zeigen die Grenze: Seed42 ist etwa 4,3 % langsamer und 4,4 % günstiger; Seed43 etwa 26,9 % schneller und 8,2 % teurer; Seed44 etwa 27,1 % schneller und 4,3 % günstiger. Drei kleine synthetische Fälle ergeben keine Produktionsentscheidung und kein gesichertes Konfidenzintervall. Ein günstigeres Ergebnis ist auch kein Optimalitätsbeweis.

Damit ist die frühere pauschale Aussage „Zonennäherung ist immer langsamer und schlechter“ zu eng. Es gibt nutzbare Fälle, jedoch keine übergreifend robuste Verbesserung. **Exaktes Routing bleibt der Standard; Zonenrouting bleibt konfigurierbar.** Eine konkrete Produktionsschwelle muss auf euren Carrier-/Netzdaten geprüft werden.

## Speicherprobe: Was die Zahlen aussagen

Je ein Service- und Shipment-Replay wurde in drei frischen JVMs wiederholt. Beobachtete maximale JVM-Heapnutzung: 25,27 beziehungsweise 25,29 MiB; maximale Prozess-Working-Sets: 122,08 beziehungsweise 124,44 MiB. Der nach geschlossenem Kontext erreichbare Ergebnisgraph beträgt 6.496 beziehungsweise 7.064 Byte. Endkosten bleiben in allen Wiederholungen 100,25 beziehungsweise 94,0.

Das sind absichtlich winzige Ein-Auftrag-Fixtures. Heap, Prozessspeicher, Allokation und Ergebnisgraph sind unterschiedliche Messgrößen. Sie sind **kein exklusiver RAM-Wert pro produktivem Carrier**, keine Kalibrierung für ein großes MATSim-Netz und kein Vorher/Nachher-Beleg. Eine automatische höhere Carrier-Parallelität wurde damit nicht freigeschaltet.

## Einstellungen und Modellgrenzen

Die pflegbare Datei ist [`config/hagrid.properties`](../../../config/hagrid.properties).

```properties
run.seed=1337
tourRules.maxRouteDurationSeconds=25200
fleet.cargoBikeMaxSpeedKmh=20
optimization.initialIterations=1
optimization.warmStartEnabled=false
optimization.adaptiveSearchEnabled=false
routing.zoneBasedCachingEnabled=false
routing.zoneBasedCachingThresholdMeters=0
```

- 25200 Sekunden sind sieben Stunden. Die frühere unabhängige 7,5-Stunden-Regel ist entfernt. Fahrzeugfenster begrenzen zusätzlich.
- Lastenräder haben standardmäßig höchstens 20 km/h; zulässige Links, Modus und Reisezeiten müssen ebenfalls passen.
- Der größere Zonentest erzwingt für die Vergleichbarkeit zehn Iterationen mit einer eng begrenzten Testkonfiguration. Der produktive Default für adaptive Suche wurde nicht verändert.
- Warmstart bleibt standardmäßig aus. Weitere Fahrzeug- oder finale Lösungskandidaten (O1/O2) sind separate optionale Erweiterungen.
- Fallende stufenförmige Reisezeiten werden ausdrücklich abgelehnt; auch eine Geschwindigkeits-Erholung durch NCE kann das auslösen. Es gibt keine stillschweigende Glättung und keinen heimlichen Free-Speed-Ersatz. Das echte Hannover-Profil muss hierzu separat bewertet werden.

## Die Bausteine im Code

| Zuständigkeit | Klassen/Bausteine |
|---|---|
| Gemeinsame Werte und Herkunft | `HagridConfigResolver`, `ResolvedHagridConfig` |
| Stabile Zufallsfolgen | `RunRandomStreams` |
| Eingefrorene Routinggrundlage | `RoutingContext`, `TravelTimeSnapshot`, `RoutingNetworkSnapshot` |
| Allgemeiner Kostenanbieter | `RoutingRequest`, `DefaultVRPTransportCostsFactory`, `ModalTransportCosts` |
| Modusnetze, Cache und Wegesucher | `ModalRoutingEngine` |
| Exakte Straßen und Abbiegeverbote | `LinkRouteProvider`, `RestrictedLinkPathFinder`, `CarrierRoadRouter` |
| Aktuelle Jobs und Plan-Zulässigkeit | `CanonicalJobValidator`, `PlanValidator` |
| Geprüfte eigene Kandidaten | `ValidatedCarrierCandidate`, `CarrierPlanCopies` |
| Tatsächliche Ausführung und Kosten | `CarrierExecutionLedger`, `CarrierCostCalculator`, `CarrierCostAttributeWriter` |
| Modulare Zeitfenster | `ModularTourTiming`, `ModularFreightWaitTask`, Scheduler/Dispatcher |
| Präziser Replay-Roundtrip | `CarrierReplay`, `CarrierReplayPlanState` |
| Profilgrenzen und Messausgabe | `RoutingProfileCompatibility`, `RoutingMetricsCsv` |

Die alte `ZoneBasedTransportCostsFactory` bleibt als veralteter Namensadapter. Der normale Modulpfad verwendet die allgemeine Factory. Die historischen Roh-Konstruktoren von `ReplanningStrategies` und `HAGRIDSimulationModule` sowie einige explizite Legacy-Tests verwenden weiterhin eine Live-Modus-Map. Externe produktive Caller dieses alten Vertrags müssen auf aufgelöste Konfiguration und `RoutingRequest` migrieren, bevor dieselben eingefrorenen Garantien gelten. Diese Abgrenzung wird nicht durch einen Klassennamen aufgehoben.

## Auditpunkte und aktuelle Belege

| Audit | Gegenstand | Aktueller Status / Gegenprobe |
|---|---|---|
| A01 | Verschluckte Pipelinefehler | INTEGRATION_VERIFIED; `PipelineFailurePropagationTest`, `CarrierInputConsistencyTest` |
| A02 | Verlorene Merge-Metadaten | INTEGRATION_VERIFIED; `CarrierMergeConservationTest`, `SupplyBaselineRoundTripTest` |
| A03 | Fixkosten erst nach Export | INTEGRATION_VERIFIED; Lifecycle-/Export-/Ereignisscoringtests |
| A04 | Arbeitszeit über mehrere Touren falsch | INTEGRATION_VERIFIED; `CarrierExecutionScoringTest`, Freight-QSim-Abnahme |
| A05 | Kurze Legs ohne LinkEnter-Zeitbeleg | INTEGRATION_VERIFIED; `NativeShortLegAcceptanceTest` und Ledger-Grenzfälle |
| A06 | Abbiegeverbot am OD-Rand | INTEGRATION_VERIFIED; Link-Vertrag, publizierte Straßen und native Fahrten |
| A07 | Veraltete Jobs/Fenster | INTEGRATION_VERIFIED; `CanonicalJobValidationTest`, `ValidatedCandidateReuseTest` |
| A08 | Unerreichbarer Zonenvertreter | INTEGRATION_VERIFIED; `ZoneFallbackTest`, Parallelitäts-/Cache-Gegenfälle |
| A09 | Supply mit mehreren Depots | INTEGRATION_VERIFIED; Verteilung, Richtung und XML-Roundtrip |
| A10 | Modulare Stop-Fenster verloren | INTEGRATION_VERIFIED; echte Warte-/NCE-Verzögerungsfahrten plus Dispatcher/Scheduler |
| A11 | Tag beeinflusst Zufalls-Eingaben | INTEGRATION_VERIFIED; Konfiguration und tatsächliche Generierungsconsumer |
| A12 | Netz mehrfach/teilweise importiert | INTEGRATION_VERIFIED; Staging-/Publikations- und Fehlerfälle |

Die Statusaussage bezieht sich auf die ausgeführten automatisierten Gegenfälle, nicht auf ein reales Hannover-Produktionsmodell. Alle 199 Testquellwurzeln sind in der aktuellen Abnahme erfasst. Die einzige Daten-Ausnahme akzeptiert exakt `LmdBaselineEndToEndTest.bootsOnRealData` mit der bekannten Staging-Assumption; neue/andere Skips sowie Fehler werden weiterhin abgelehnt. Ohne expliziten Schalter bleibt die Abnahme für Skips strikt.

## Was bleibt offen?

1. **Hannover-Realdatenlauf und echtes Hintergrundprofil.** Die weitere gezielte Suche hat Rohdaten in alten lokalen Ordnern und abgeschlossene Kalender-Runs im Demand-Worktree gefunden. Ein frischer Kalender-Tagesexport13.05.2025 und dessen HAGRID-Lese-/Gruppierungsübergabe sind inzwischen geprüft:48.529 Features,201.747 Pakete, vollständig erhalten. Vollständige Carrier-/MATSim-Erzeugung und150-Iterationslauf sind noch nicht gestartet: Die zugehörigen Hannover-Fahrzeugtypen und Konfiguration fehlen weiterhin; GitHub-master enthält für diese Eingaben nurgitkeep. Nachfrage allein ersetzt nicht den zusammengehörigen Hintergrundlauf und die vorbereitete Netz-/Reisezeit-/NCE-Grundlage. Details stehen im neuen [Kalender-Tag-Testbericht](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/README.md).
2. **Lausitz-Realdaten:** liegen fachlich beim Kollegen und werden hier nicht erzeugt oder als Blocker für den Hannover-Teil behandelt. Der vorhandene Lausitz-Test ist sichtbar datenbedingt verschoben, nicht bestanden.
3. **Native jsprit-2.0-Abweichungen:** alle19 erneut reproduziert, Details unten. Insbesondere die zwei Batterie-Auswahlen und der gemischte Fixkostenfall brauchen weitere Solver-/Qualitätsarbeit; die vorliegende Prüfung beseitigt diese Abweichungen nicht.
4. **Produktions-RAM/Parallelität und 500-Fahrzeuge-Annahme:** repräsentative gemeinsame Modellbasis, Carriergrößen und zurückgehaltene Gruppenergebnisse messen. Der kleine Replay-Pilot reicht dafür nicht. Weitere immutable Netzkopieroptimierung erst mit belastbarer Probe.
5. **Eigener Vorher/Nachher-Vergleich T12/T13:** gleiche korrigierte Fachlogik und gleiche Eingaben; Replanningzeit, vollständige Routing-/Validierungsaufrufe, Kontext- und Gesamt-JVM-Speicher getrennt messen. Aktuelle funktionale Zähler ersetzen diesen Laufzeitvergleich nicht.
6. **GIS-Lebensdauerwarnungen:** der Fullsuite-Log enthält GeoTools/JDBC-Meldungen über nicht geschlossene DataStores. Herkunft und Auswirkungen separat lokalisieren; die grünen Tests beweisen keinen sauberen Datenbank-Lifecycle. Der native DRT-Geopackage-Export ist ein zu prüfender Pfad, keine bestätigte alleinige Ursache.
7. **Git-Integration:** Änderungen sind weiterhin uncommitted; die Arbeitskopie enthält umfangreiche ältere/fremde Änderungen. Kein automatischer Commit, Rebase, Merge oder Push ist erfolgt.

Die 6-GiB-Reserve wurde auch bei den jetzt freigegebenen Läufen beibehalten. HAGRID-Verify wählte aktuell zwei Prozessoren und 768-MiB-Maven-/Testheaps, die native Fullsuite vier Prozessoren und 1024 MiB. Die Vergleichsmessungen nutzten ausdrücklich fixe 1 CPU/384 MiB. Eigene Prozesse liefen seriell unter dem Schutz; keine fremden Prozesse wurden angefasst und die abbestellte Automation bleibt aus.

## Native Freight: 19 Abweichungen bleiben sichtbar

Aktueller Lauf: **291 Tests, 19 Failures, 0 Errors, 9 Skips**, identische19 fehlgeschlagene Class/Method-Paare wie in der historischen Liste. 18 Assertionmeldungen sind gleich; beim LSP-Scorefall unterscheidet sich der aktuelle Zahlenwert leicht. Matching mit alten Fehlern ist kein Fehlernachweis der Referenzen und kein Zulässigkeits-/Optimalitätsbeweis.

Bei allen15 Ereignisvergleichsfehlern sowie dem zusätzlichen LSP-Scorefall stimmen die Abschluss-Multimengen aus Typ, Carrier, Job und Kapazitätsbedarf mit den Referenzen überein. Die Anzahl der gefahrenen Linkereignisse weicht ab. Daraus folgt kein Beweis für identische Wege, Kapazitäten, Zeitfenster, Energie oder Kosten. Die zwei Batterie-Fixtures liefern im aktuellen nativen Diagnoselauf je eine 24-km-Tour mit dem großen Fahrzeug (80/80 Kapazität, 30-km-Energiegrenze); beide Jobs sind genau einmal enthalten. Der ursprüngliche Test erwartet zwei kleine Touren. Die gemischte Fixkosten-Fixture wählt weiterhin zweimal TypB statt der günstigeren historischen gemischten Auswahl. Keine native Erwartung wurde umgeschrieben.

| Nr. | Native Klasse / Methode | Aktuelle Abweichung | Bewertung |
|---|---|---|---|
| 1 | `org.matsim.freight.carriers.jsprit.DistanceConstraintFromVehiclesFileTest / Carrier2SmallBatteryTest_Version3` | Not the correct amount of scheduled tours ==> expected: <2> but was: <1> | REPRODUCED_UNRESOLVED: Eine große Tour statt zwei kleiner; Jobs vollständig, beobachtete Kapazität und Energiegrenze eingehalten; Auswahl-/Qualitätsfrage offen. |
| 2 | `org.matsim.freight.carriers.jsprit.DistanceConstraintTest / Carrier2SmallBatteryTest_Version3` | Not the correct amount of scheduled tours ==> expected: <2> but was: <1> | REPRODUCED_UNRESOLVED: Eine große Tour statt zwei kleiner; Jobs vollständig, beobachtete Kapazität und Energiegrenze eingehalten; Auswahl-/Qualitätsfrage offen. |
| 3 | `org.matsim.freight.carriers.jsprit.FixedCostsTest / test_carrier3CostsAreCorrectly` | expected: <-18.36> but was: <-20.439999999999998> | REPRODUCED_UNRESOLVED: Zwei Typ-B-Touren, Score -20.44; historische gemischte Auswahl -18.36 besser. Auswahl-/Qualitätsabweichung offen. |
| 4 | `org.matsim.freight.logistics.examples.lspReplanning.CollectionLSPReplanningTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 5 | `org.matsim.freight.logistics.examples.lspScoring.CollectionLSPScoringTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 6 | `org.matsim.freight.logistics.examples.lspScoring.MultipleIterationsCollectionLSPScoringTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 7 | `org.matsim.freight.logistics.examples.mobsimExamples.ExampleMobsimOfSimpleLSPTest / testForRuntimeExceptionsAndCompareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 8 | `org.matsim.freight.logistics.examples.multipleChains.ExampleTwoLspsGroceryDeliveryMultipleChainsTest / testOutputIsEqual` | Lines have different content:  ==> expected: <<LspPlan score="-12482.46589378013" selected="false">> but was: <<LspPlan score="-12482.31250573104" selected="false">> | REPRODUCED_UNRESOLVED: Score eines nicht ausgewählten LSP-Plans unterscheidet sich; abgeschlossene Job-Multimenge gleich. Referenzvergleich bleibt rot. |
| 9 | `org.matsim.freight.logistics.lspMobsimTests.MultipleIterationsCollectionLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 10 | `org.matsim.freight.logistics.lspMobsimTests.MultipleIterationsCompleteLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 11 | `org.matsim.freight.logistics.lspMobsimTests.MultipleIterationsFirstAndSecondReloadLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 12 | `org.matsim.freight.logistics.lspMobsimTests.MultipleIterationsFirstReloadLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 13 | `org.matsim.freight.logistics.lspMobsimTests.MultipleIterationsMainRunLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 14 | `org.matsim.freight.logistics.lspMobsimTests.MultipleShipmentsCollectionLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 15 | `org.matsim.freight.logistics.lspMobsimTests.MultipleShipmentsCompleteLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 16 | `org.matsim.freight.logistics.lspMobsimTests.MultipleShipmentsFirstAndSecondReloadLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 17 | `org.matsim.freight.logistics.lspMobsimTests.MultipleShipmentsFirstReloadLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 18 | `org.matsim.freight.logistics.lspMobsimTests.MultipleShipmentsMainRunLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |
| 19 | `org.matsim.freight.logistics.lspMobsimTests.RepeatedMultipleShipmentsCompleteLSPMobsimTest / compareEvents` | expected: <FILES_ARE_EQUAL> but was: <MISSING_EVENT> | REPRODUCED_UNRESOLVED: Abgeschlossene Job-Multimenge gleich; Anzahl entered/left-link-Ereignisse verschieden. Vollständige Ereignisparität bleibt rot. |

## Nachweise zum Nachlesen

Die Pfade liegen in `.superpowers/sdd/2026-10-10-hagrid-audit-fixes-und-baustein-refactor/verification/2026-10-14/`:

- `acceptance-final-01/acceptance.json` und `verify.log`: aktueller Build, 1.601 Testfälle einschließlich genau eines datenbedingten Skips, Quelle/JAR/Schutzskript gebunden.
- `package-replay-01/manifest.json`: separater neuer JVM-Replay für Services und Shipments, gemeinsame Kontextdatei und identische Endkosten.
- `python-tests-01.stderr.log`: 33 aktuelle Auswertungstests bestanden.
- `memory-pilot-summary-01/overview.txt` und `summary.json`: sechs geprüfte kleine Speicherbeobachtungen; `memory-pilot-01/build-evidence.json` bindet den Build.
- `zone-full-01/manifest.json`, `results.json`, `summary.json`: 48 Messläufe und 16 ausgeschlossene Warmups, Inputs/Seeds/Budgets und Qualität.
- `native-01-summary.json`, `native-event-semantics-03.json`, `native-diagnostic-01/manifest.json`: alle19 aktuellen nativen Abweichungen und abgegrenzte Diagnosen.
- `fresh-source-review.md`: neue unabhängige Quellcodeprüfung ohne neu belegten Critical/Important/Minor-Befund; keine Laufzeitfreigabe durch den Reviewer.

Source-Hash des geprüften HAGRID-Builds: `4DADF08B0CAAAA34637F49C7E4023C5731527E927E24EFA342704A62BE5082A0`.
JAR-SHA256: `011DED73DD0AFB7BAC1AC266828B2C5D9D72ACEC7C77C0B1596D1AE0F9AC5FEB`.

Das [Fortschrittsledger](../../../.superpowers/sdd/2026-10-10-hagrid-audit-fixes-und-baustein-refactor/progress.md) bewahrt Entscheidungen und ältere Zwischenstände. Der aktuelle Überblick steht dort oben. Die [statische Quellenprüfung](../../../.superpowers/sdd/2026-10-10-hagrid-audit-fixes-und-baustein-refactor/verification/source-checks.json) prüft zusätzlich Datei-Hashes, PowerShell-Syntax und Whitespace-Diffs; sie ist ein eigener statischer Nachweis und kein Ersatz für die aktuellen Laufprotokolle.

## Initiales jsprit0 und wiedergefundene Hannover-Eingaben — 14.10.2026

Für den angefragten Tageslauf unterstützt die INITIAL-Phase jetzt echte0 Verbesserungsschritte: Jobs werden weiterhin eingefügt, Straßenrouten gebaut und der vollständige Plan geprüft. Fehlende Aufträge und ungültige Touren dürfen auch bei0 nicht veröffentlicht werden. Die zentrale Einstellung ist `optimization.initialIterations=0`; ScenarioConfig und der bisherige Adapter geben0 unverändert weiter. Standard bleibt1; negative Budgets sowie0 für Offline-/Replanning-Suche bleiben unzulässig.

Sieben neue Regressionen wurden zunächst rot und danach grün geprüft, einschließlich Services/Shipments, Null-Budget-Weitergabe und ungültigem Auftrag vor Veröffentlichung. 93 gezielte Tests ohne Fehler. Der erste vollständige Lauf hatte genau eine alte direkte Setter-Erwartung0→1; ausschließlich diese veraltete Vertragsannahme wurde angepasst. Der zweite aktuelle Lauf: 1608 Testaufrufe, 0 Fehlerassertionen, 0 Ausführungsfehler, 1 weiterhin explizit fehlender Lausitz-Realdatencheck. Das aktuelle JAR ist an Quellen und Ressourcenschutz gebunden. Eine separate Probe gegen dieses Paket bestätigt den echten Kalenderimport mit201747 Paketen, initial0 in zentraler Konfiguration und Adapter sowie native Konstruktion ohne Suchiteration.

Für euren festen Hintergrund und einen Replanning-Durchgang ist initial0 eine sinnvolle Ausgangseinstellung: frühere Simulationsergebnisse verwenden zunächst die Starttouren; ab dem konfigurierten Replanning werden die Carrier gruppenweise am selben eingefrorenen Reisezeitstand geprüft/optimiert. 150 MATSim-Iterationen sind deshalb keine150 jsprit-Suchläufe pro Carrier. Laufzeitgewinn und gleichwertige Endqualität dieser0-Option sind noch nicht durch einen vollständigen Tagesvergleich belegt.

Korrektur der früheren Eingabesuche: Fahrzeugtypen, volle MATSim-Ausgabekonfiguration, Netz und Network Change Events wurden im lokalen Archiv `parcel-analysis/input/simRes/basecase_13052025_iter150_jsprit1000` gefunden. Hashgleiche Kopien und eine explizit dokumentierte Konfigurationskopie liegen im isolierten Testordner; alte absolute Pfade ersetzt, Threads auf2 begrenzt, Seed1337. Archivierte Carrier werden nicht als frische Nachfrage wiederverwendet. Der native Konfigurations- und Fahrzeugtyp-Leser wurde mit den wiedergewonnenen Dateien geprüft. Die Semantik des archivierten Netz-/NCE-Paars als gewünschte Hintergrundgrenze muss noch geprüft werden.

Der vollständige Tageslauf bleibt offen: Original-Zonen-Shapefile plus Begleitdateien und die derzeit vom LogisticsDataProcessor gelesenen Paketstations-/Provider-Standort-CSV-Dateien wurden nicht gefunden. Der Dateipreflight listet konkrete Pfade. Fixe zusätzliche Paketstationsnachfrage ist ausgeschaltet; die unconditionalen Legacy-Standortleser sind deshalb als eigener Bereinigungsbedarf erkennbar, aber hier nicht still übersprungen oder mit erfundenen Daten ersetzt. Keine initialen Carrierdateien oder150-Iterations-QSim-Ausführung behauptet. Lausitz bleibt beim Kollegen, Automation bleibt aus, fremde Prozesse unverändert.

Nachweise: `calendar-day-e2e/initial-zero-decision.json`, `acceptance-initial-zero-02/acceptance.json`, `zero-current-build-probe-02/probe-proof.json`, `hannover-input-preflight.json` und `recovered-hannover-inputs.json`. Bei der ersten eigenen Konfigurationskopie ging durch DtdProcessing.Ignore die native XML-Versionsdeklaration verloren; die Paketprobe reproduzierte den Fehler, danach wurde ausschließlich die Testkopie mit der Originaldeklaration repariert und erneut geprüft (`recovered-config-doctype-repair.json`). Das ältere `import-and-zero-budget.json` zeigt ausdrücklich den Zustand vor der0-Unterstützung; die neue Paketprobe ersetzt seine Aussage zum aktuellen Verhalten.

## Wiederaufnahme und echter Hannover-Stadtlauf — 20.10.2026

Nutzerfreigabe am17.10.: alle Tests/Fixes fortsetzen und gegen den ursprünglichen Stand benchmarken; am20.10. wieder erreichbar und Arbeit aufgenommen. Die Pause wird damit aufgehoben; die alte Ressourcen-Automation bleibt aus. Keine fremden Prozesse oder Git-Referenzen verändert. Primärer Checkout enthält Altdaten; produktive Refactor-Quellen bleiben im Worktree zone-router-fixes/HAGRID.

Die vor der Pause ergänzten Eingabeoptionen sind enthalten: demand.filterRegions=HANNOVER wählt die Stadt aus; hubs.loadLegacyLocations=false überspringt nur die ausdrücklich ungenutzten Kataloge. Der historische Standardtrue bleibt. Fixe zusätzliche Paketstationsnachfrage darf ihre Pflichtquelle nicht deaktivieren. Nachfrage, Region-Geometrie, Depots und Fahrzeugtypen bleiben erforderlich. Originale Fahrzeugtypen im Nachbarprojekt vroom-valhalla-lmd-hannover/data/vehicles gefunden und hashgleich separat kopiert. POM-JTS-Pins1.16.1 verursachten beim echten Shapefile-Schreiben einen NoSuchMethodError; jetzt gemeinsames JTS1.20.0 passend zu MATSim, Regression mit echtem GIS-Schreiben grün.

Erste frische Gesamtprüfung:1613Aufrufe/0Fehler/1fehlenderLausitz-Realdatencheck. Ein echter Stadttag mit eigenem ScenarioRunner-Profil startete anschließend, initial0, Datum13.05.2025, Masterseed1337, Master-Basisfall160_l/SIMPLE_STAGGERED/Dispatch7–14/Amazon+1/Lieferfenster8–20. 6GiB JVM-Heap,2sichtbareCPU,1aktiverSolver, Idle und laufender6GiB-RAM-/CPU-Schutz. Abbruch nach28,3s an der bestehenden Mengenprüfung; keine initialen fertigen Pläne oder QSim behauptet. 89852Stadtpakete und10746B2B-Services waren tatsächlich vollständig vorhanden. Die Prüfung zog einen zusätzlichen B2C-Splitservice fälschlich auch von der B2B-Serviceanzahl ab. Das war ein Fehlalarm der Klassenzählung, kein nachgewiesener Paketverlust.

Fix: eigener B2B-Splitzähler in HAGRIDSummary/CarrierGenerator; CarrierGenerationTransaction stellt beide Zähler bei Fehlern wieder her. Fünf neue Gegenproben: reinerB2C-Split, reinerB2B-Split, gemischteSplits, spätesFehlschlagen mit Rückrollen und echteB2B-Mengendifferenz bleibt unzulässig. Nach Korrektur einer reinen Test-Fixture-Typisierung sauber2ROT/3GRÜN; anschließend32betroffeneTestsGRÜN. Aktuelle vollständige verfügbare Abnahme nach dem Fix:1618Aufrufe/0Fehler/0Ausführungsfehler/1explizit ausgenommenerLausitz-Realdatencheck,202Testquellen. Aktuelles Paket und Quellenhash sind erneut gebunden. NativeUpstream-Kompatibilität, vollständige Produktion und neue Benchmarks sind gesonderte noch offene Nachweise.

Zweiter echter Initiallauf ist gestartet: initial-hannover-2026-10-20-02, TagCAL0_HANNOVER_20261020_R2. Nachfrage, Delivery-/Supply-Erzeugung und Service-Merge haben bestanden; native Eingabedateien sind vorhanden, Routenkonstruktion läuft mit0Suchiterationen. Kein26-Fälle-Batch. Ruling: Teiloutputs als Zwischenstand dokumentieren, Enderfolg erst nach produktiver Validierung aller Carrier, nativerXML-Job-/Straßenrouten-Rücklese und0/VALIDATED-Metriken melden — verhindert einen weiteren scheinbar fertigen Lauf mit fehlenden Touren.

Ausgangsstand für neue Vergleiche ist der ursprünglich dokumentierte Commitafbaf33b72f5273f80ec2c844056618d4b6c4add (metadata-2026-10-05/B0-Manifest). Alte B0-Classpaths zeigen auf veränderliche target-Verzeichnisse und dürfen nicht ungeprüft als frische Baseline dienen. Reiner jsprit1.8→2.0-Versionsvergleich, identische Solver-Eingaben und vollständige Pipeline müssen getrennt berichtet werden. Laufzeit, RAM, unzugewiesene Jobs, harte Verletzungen und unabhängig bewertete Kosten gemeinsam erfassen; keine pauschale Schnelleraussage aus bisherigen Mischresultaten.

Nachweise im calendar-day-e2e-Ordner: acceptance-calendar-inputs-2026-10-20-01, initial-hannover-2026-10-20-01, split-accounting-clean-red-20261020.log, split-accounting-green-20261020.log, acceptance-split-fix-2026-10-20-01 und initial-hannover-2026-10-20-02/run-proof.json. Zweiter Initiallauf/150QSim/Originalvergleich bleiben bis zu ihren eigenen Endnachweisen offen. Vollsimulation benötigt weiterhin eine belegte Zonen-/importierte-Zonen-Regel und kompatible reale NCE/Reisezeitprofile; die strikten Profilprüfungen werden nicht still entfernt.

## Korrektur zum zweiten echten Initiallauf und Hub-Metadaten — 20.10.2026

Der oben als laufend dokumentierte zweite Initiallauf endete nach214,004s mit einem Fehler. Nachfrage, Supply und Merge waren erfolgreich; die erste DHL-Suche (389Jobs) lief182,235s. Erst danach scheiterte der kanonische Eingabe-Fingerabdruck an einem echten Carrier-Attribut vom Typ hagrid.core.util.Hub. Es gab keine erfolgreich veröffentlichte fertige Routing-Gesamtlösung und keine QSim. Die kleinen bisherigen Testcarrier enthielten diesen produktiven Metadatentyp nicht.

Ursache und Fix: RoutingData unterstützt jetzt den exakten bekannten Hub-Typ mit einer tiefen Kopie und einem strukturierten, stabilen Fingerabdruck sämtlicher unterstützter Zustände (ID, Anbieter, Adresse, Typ, Koordinate, Link, Nachfrage, Kapazität, Kapazitätsflag, White-Label-Nachfrage und weitere Attribute). Unbekannte veränderliche Objekte werden weiterhin abgewiesen. Der Hub-Kopierkonstruktor kopiert Koordinaten unabhängig und übernimmt die zuvor vergessene White-Label-Nachfrage. CarrierOptimizationService prüft den Eingabe-Fingerabdruck bereits vor dem teuren Transport-/Suchaufbau und kontrolliert ihn nach Suche und Ergebnisvalidierung erneut. Dadurch scheitern weitere nicht unterstützte Metadaten früh, statt nach einer mehrminütigen Suche.

Beleg: fünf neue HubRoutingDataTest-Fälle zuerst sauber ROT (2Assertionsfehler,3reproduzierte Hub-Fehler), danach72betroffene Tests GRÜN einschließlich Kopieren, Fingerabdruckänderung, Null-Iterations-Suche mit echtem Hub, Cache/Replay/Publikation und frühzeitiger Ablehnung unbekannter Metadaten ohne Transportaufruf. Vollständige aktuelle verfügbare Prüfung danach VERIFIED:1623Aufrufe,0Fehler,0Ausführungsfehler,1expliziter fehlender Lausitz-Realdatencheck,203Testquellen. JAR-/Quellenbindung: acceptance-hub-fix-2026-10-20-01/acceptance.json. Das ist die automatisierte HAGRID-Abnahme; große Hannover-Fahrt und native Upstream-Kompatibilität behalten eigene Nachweise.

Der frische ursprünglicher-Stand-Vergleich läuft anschließend separat unter original-entrypoint-comparison-2026-10-20-01. Umfang: originale Solver-Einstiegsklassen aus Commitafbaf33b72f5273f80ec2c844056618d4b6c4add/jsprit1.8 gegen aktuellen Legacy-Einstieg/jsprit2 auf derselben nativen Freeflow-Kostenfixture. 60Services,30Iterationsobergrenze,5Seeds,3Wiederholungen,abwechselnde Reihenfolge,2ausgeschlossene Aufwärmlösungen jeJVM,1CPU/384MiB. Dieser abgegrenzte Vergleich misst weder den gesamten Hintergrund-Replanning-Dienst noch den vollständigen realen Tag; Ergebnisse erst nach allen gepaarten Messungen bewerten. Anschließend ist der dritte echte Hannover-Initialversuch mit frischem TagR3 vorgesehen. Die6GiB-Reserve und laufender Schutz eigener Prozesse bleiben verbindlich; die alte Automation bleibt aus.

## Frischer ursprünglicher-Stand-Vergleich abgeschlossen — 20.10.2026

Nach kurzer CPU-Spitze wurde der erste Versuch bereits vor dem nächsten Prozessstart konservativ zurückgestellt (original-entrypoint-comparison-2026-10-20-01,2Messungen). Die Grenze wurde nicht aufgeweicht. Nach erneut gültigen Ressourcenmessungen ist die komplette zweite Reihe original-entrypoint-comparison-2026-10-20-02 mit30Messungen abgeschlossen; beide Belege bleiben erhalten. Quell-/JAR-/Guard-/Fixture-Identitäten vor/nach der Reihe stimmen überein.

Die folgenden Änderungen sind Median der fünf gepaarten pro-Seed-Verhältnisse; jeSeed zunächst Median aus drei Wiederholungen. Vergleich: zehn originale Solver-Einstiegsklassen aus dem anfänglichen Commitafbaf33/jsprit1.8 gegen aktuellen Legacy-Solver-Einstieg/jsprit2; identische aktuelle native MATSim-Freeflow-Kostenfixture unter beiden. 60Jobs,gleiche30Iterationsobergrenze,1CPU/384MiB,2ausgeschlossene Aufwärmlösungen je frischerJVM,abwechselnde Reihenfolge. Nicht das vollständige alte oder neue Gesamtsystem.

| Messgröße | Aktuell gegenüber ursprünglichem Solver-Einstieg |
|---|---:|
| Reine Suchzeit | +31,6% |
| Gesamtzeit der Fixture | +9,3% |
| Unabhängig exakt bewertete Tourenkosten | Median0%;jeSeed−4,0%bis+5,8% |
| Touren | unverändert2 |
| Vollständig bediente Aufträge | überall60von60 |
| Harte Verletzungen | überall0 |
| Gemessener JVM-Heap-Höchststand | gepaarterMedian+1,1% |
| Direkter Prozess-Arbeitsspeicher | gepaarterMedian+5,2% |
| Während der Messung insgesamt neu angelegte Bytes | +79,7%;das ist Allokationsvolumen,kein gleichzeitig benötigterRAM |

JederSeed liefert innerhalb eines Profils dieselbe Tourenreihenfolge über alle drei Wiederholungen. Beide respektieren die gleiche Obergrenze; die aktuelle Legacy-Abbruchregel endet beiSeed46 nach28statt30Strategien, alle übrigen beobachteten Fälle nach30. Daher keine Behauptung identischer tatsächlich ausgeführter Iterationszahl. Ergebnis: hier kein Geschwindigkeitsgewinn und kein allgemeiner Qualitätsgewinn; das Refactoring und der Versionswechsel allein liefern keinen belegten Performancevorteil. Die früheMetadatenprüfung und bessere Fehlerabgrenzung sind separat belegte Korrektheits-/Betriebsvorteile. Für Ursache/Verbesserung muss man insbesondere Suchstrategien und Allokationen untersuchen; diese Reihe alleine lokalisiert den Mehraufwand nicht eindeutig auf die jsprit-Version. Die fünf kleinen Seeds ersetzen keine produktive Performanceaussage.

Neue Eingabeprüfung: archiviertes Netz vollständig stromweise untersucht,544514Links,544514ganzzahlig lesbare vorhandeneZonenattribute,0ungültigeZonenwerte,alleModescar. Das eröffnet die explizite Weiterverwendung importierterZonen ohne erfundeneGeometrie; die spätere nativeSzenario-/NCE-Prüfung bleibt erforderlich. Beleg: recovered-network-zone-inspection-20261020.json.

Dritter frischer Initiallauf initial-hannover-2026-10-20-03/TagCAL0_HANNOVER_20261020_R3 ist anschließend mit dem aktuellen1623fach geprüften Paket gestartet,Datum13.05.2025,nurHannoverStadt,initial0,2CPU/6GiBHeap/1Solver,6GiBfreieReserve und lebenderRAM-/CPU-Schutz. Der erste echte DHL-Carrier mit389Jobs hat Suche/Routing/Validierung erfolgreich durchlaufen; weitere Carrier laufen. Das bestätigt den behobenen Hub-Fehler am produktiven Carrier, ersetzt aber keinen Gesamterfolg. Vollständiger Initialerfolg,150MATSim-Iterationen und vollständiger realer Vorher/Nachher-Vergleich sind noch nicht behauptet.

Der Nutzer hat die schlechtere Laufzeit ausdrücklich als verfehltes Ziel markiert. Performance-Abnahme bleibt daher offen. Zur Ursachenabgrenzung ist run-three-way-regression-isolation.ps1 vorbereitet (noch nicht ausgeführt): ursprünglicherHAGRID-Einstieg/jsprit1.8; derselbe ursprünglicheEinstieg/jsprit2; aktuellerEinstieg/jsprit2. FünfSeeds/dreiWiederholungen mit rotierender Reihenfolge,gleicheFixture/gleicheObergrenze/Ressourcen/Ergebnisprüfung. Die diagnostische alteImplementierung ist keine Produktions-Rücknahme der Korrektheitsfixes. So lassen sich Versions- und HAGRID-Codeeffekte besser unterscheiden. Erst nach Freigabe der gemeinsamen Prozesssperre starten; der aktuelle echte Initiallauf wird dafür nicht parallel belastet. Die neue Kontrollreihe muss mögliche API-Inkompatibilitäten explizit melden und darf nicht trotzdem als gemessen gelten.
