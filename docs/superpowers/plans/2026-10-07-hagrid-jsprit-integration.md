# HAGRID–MATSim–jsprit: Implementation Plan in Phasen

**Aktueller Stand nach deiner Präzisierung (K277–K307):** Eine [gemeinsame HAGRID-Konfigurationsdatei](2026-10-09-hagrid-gemeinsame-konfiguration.md) ersetzt die unabhängige 7,5-Stunden-Regel und den impliziten modularen 3,5-Stunden-Standard. Bedarf, Planung und Scoring verwenden standardmäßig sieben konfigurierbare Stunden. Supply-, DRT-, Shared-Use-, Modular-, LMD-, Cache- und Pfadeinstellungen erreichen ihre Verbraucher. 1.466 kleine Tests aus 175 Quellen, Paketbau und genau dieses Paket im Replay bestanden. Produktion, größere Parallelität, native Abweichungen und Kalibrierung bleiben offen; Vroom/Valhalla haben weiter Vorrang bei 6 GiB Reserve. Ältere Zwischenstände darunter sind historische Nachweise.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** jsprit 2.0 integrieren und HAGRIDs vollständige Tourenplanung verständlicher, fachlich konsistent und nachweisbar effizienter machen, insbesondere beim Replanning und RAM.

**Architecture:** Ein gemeinsamer Optimierungsdienst verwendet einen universellen MATSim-kompatiblen Transportkostenanbieter und einen festen Reisezeitkontext. Konfiguration, Solverzustände, abschließende Planprüfung und Ressourcensteuerung erhalten klare Zuständigkeiten. Hannover behält seinen einmaligen, über MATSim-Iterationen gruppierten Optimierungsdurchgang; Lausitz behält seine eigenen Ablaufregime.

**Tech Stack:** Java 21, MATSim 2025.0, jsprit Core/Analysis 2.0.0, zunächst separat geprüftes jsprit IO 1.8, Maven, JUnit 5, AssertJ, vorhandene PowerShell-/Java-Benchmarks.

**Spec:** `docs/superpowers/specs/2026-10-06-hagrid-jsprit-2-integration-design.md`, überarbeitet am 2026-10-07 einschließlich Abschnitt 10.3 zu regelmäßigen Vergleichsläufen. Vor Ausführung beide Dokumente lesen.

**Status:** Nutzer hat die schrittweise Implementierung in dieser Sitzung am 2026-10-07 freigegeben. Ausführung beginnt mit Aufgabe 1; Fortschritt und Entscheidungen werden im zugehörigen Ausführungsprotokoll festgehalten. Frühere Router-Patches und Vorversuche sind Eingaben, keine Abnahme dieses Plans.

## Global Constraints

- Reihenfolge: jsprit Core/Analysis 2.0.0 → HAGRID-Integration → erst danach jsprit intern untersuchen. Java 21 und MATSim 2025.0 bleiben erhalten.
- Hannover: initial eine jsprit-Iteration; importierte Hintergrundgeschwindigkeiten/Network Change Events als exogene Randbedingung; einmalige Neuoptimierung jedes vorgesehenen Carriers.
- Snapshot vor dem ersten Replanning-Auftrag aus der letzten abgeschlossenen Simulation; bisher Startiteration 25, also Messiteration 24. Alle Gruppen verwenden denselben unveränderlichen Stand.
- Bisher maximal 16 Carrier pro Gruppe; aktive Solver und RAM-Budget separat begrenzen. MATSim-Iterationszahl und Verteilung über Iterationen sind kein Optimierungsziel.
- Ein interner Solver-Thread pro Carrier als Ausgangspunkt; zwei/vier interne Threads nur im späteren begrenzten Vergleich. Keine unkontrollierte Verschachtelung von Parallelität.
- Nutzerpräzisierung 2026-10-07: Hannover-Solvergrenze bleibt 25200 Sekunden (7 Stunden), keine Erhöhung auf 27000. Vorhandene 27000-Sekunden-Werte in Erzeugung/Scoring getrennt erfassen und ihre Migration ausdrücklich prüfen. Lausitz behält explizite eigene Grenzen.
- Sekunden, Meter, m/s intern; Lastenrad-Höchstgeschwindigkeit endlich und positiv, einmalige Umrechnung aus km/h. Nutzerstandard 2026-10-07: Lastenrad maximal 20 km/h; profil-/lauf-/CLI-seitig überschreibbar.
- Ein maßgebliches multimodales QSim-Netz; interne Modusansichten erlaubt. Zunächst ein Modus pro Carrier. Nicht unterstützte Mischflotten/offene Touren vor Simulation mit Diagnose zurückweisen.
- Keine neue Zwei-Stufen-Optimierung zwischen Supply und Zustellung, keine zusätzliche MATSim-LSP-Planungsschicht, keine Änderung des statistischen Nachfragemodells.
- Vollständige, zulässige Altpläne dürfen unter derselben Bewertung ausgewählt bleiben. Unvollständige billigere Kandidaten gelten nicht als Verbesserung.
- Alle Produktänderungen erhalten die jeweils betroffenen fachlichen Nachweise und kleine Vorher-/Nachher-Vergleiche. Mindestens fünf feste Seeds für Aussagen zu heuristischer Qualität und Solver-Laufzeit; reine Dokumentationsänderungen benötigen keinen Benchmark.
- Keine unbelegten Produktions- oder Geschwindigkeitsversprechen. Fehlende Hannover-Eingaben und nicht ausgeführte Nachweise werden ausdrücklich ausgewiesen.
- Bestehende fremde Änderungen, vorbereitete Router-Arbeit, alte Cache-Dateien und Szenarioregime erhalten. Kein pauschales Zurücksetzen, Löschen oder ungeprüftes Übernehmen der vorbereiteten Patches.
- Der parallel laufende Valhalla-Lauf hat nach ergänzender Nutzeranweisung höchste Ressourcenpriorität und soll bis Ende der Woche fertig werden. HAGRID nutzt währenddessen kleine serielle Prüfungen; große Vergleichs-/Produktionsläufe werden zurückgestellt. Keine Änderung oder Unterbrechung fremder Valhalla-Prozesse.

### Ressourcen während des Valhalla-Laufs

Für kleine Benchmark-Prozesse gelten vorläufig 384 MiB Java-Heap, höchstens 512 MiB, 64 MiB Startheap, ein für die JVM sichtbarer Prozessor und Windows-Priorität Idle. Es läuft jeweils nur ein eigener Benchmark-Prozess. Vor und während jeder Messung wird verfügbarer physischer Speicher geprüft; unter 6144 MiB Reserve startet kein weiterer Messprozess beziehungsweise ausschließlich der eigene Messprozess wird beendet und als wegen Ressourcen zurückgestellt dokumentiert. Diese Grenzwerte gelten für die kleinen Prüfungen während Valhalla, nicht als neues pauschales Produktionslimit für sämtliche HAGRID-Szenarien.

Weitere Maven-/Compiler-Aufrufe erfolgen seriell mit kleinem Heap und einem sichtbaren Prozessor. Gezielt betroffene Tests haben Vorrang; erneute vollständige Simulationssuiten und Parallelitäts-/große RAM-Versuche warten auf passende Ressourcen nach Valhalla. Ein großer vorhandener Ausgangstest wurde bereits vor der Ressourcenpräzisierung ausgeführt und bestanden. Messungen bei konkurrierender Hintergrundlast werden ausdrücklich als solche beschrieben und später vor allgemeinen Beschleunigungsaussagen unter ruhigen Bedingungen bestätigt. Der Valhalla-Termin ist damit eine Arbeitspriorität; HAGRID übernimmt keine unbelegte Fertigstellungsgarantie für den fremden Lauf.

## Review Focus

1. Leere Carrier-Gruppe und nur ein aktiver Modus: kein Pool-/Snapshot-Neuaufbau und keine unnötige Lastenrad-Dateipflicht; Tests in Aufgaben 8, 11 und 14.
2. Shipment-lastiger Carrier und fehlerhafte Warmstart-Reihenfolge: vollständige Paare, Pickup vor Delivery, angemessene RAM-Zulassung; Tests in Aufgaben 10, 14 und 15.
3. Gleiche Netz-ID bei anderer Topologie oder gleichen Fahrzeugtyp-IDs mit anderen Eigenschaften: vor Routing Konflikt melden; Tests in Aufgaben 6 und 8.
4. Abbruch während Ergebnisablage oder noch laufender Worker: keine halben Erfolgseinträge, keine verspätete Planübernahme und kein QSim-Start vor Worker-Ende; Tests in Aufgaben 11, 12 und 14.
5. Seed-/Thread-/Cache-Reihenfolge verändert die scheinbare Verbesserung: gleiche Eingaben/Budgets, getrennte Kontexte, unabhängige Nachbewertung und sichtbare Streuung; Tests/Vergleiche in Aufgaben 1, 7, 14 und 16.

---

## Phasenübersicht

| Phase | Zweck und Veränderung gegenüber heute | Abschluss / Vergleich |
|---|---|---|
| 0 – Ausgangspunkt | Git-Basis und vorbereitete Arbeit sichern; kleine reproduzierbare Referenzen einrichten | B0: heutiger Stand ausführbar und eindeutig identifiziert |
| 1 – jsprit 2.0 | Bibliothek aktualisieren, IO und ausgeliefertes Paket prüfen | B1: isolierter Versionseffekt gegenüber B0 |
| 2 – Konfiguration und Regeln | Gemeinsame Einstellungen, solverlokale Zustände, korrekte Bedienzeiten/-kosten | B2: jede fachliche Änderung begründet und separat gemessen |
| 3 – Routing und Reisezeiten | Universeller Anbieter, fester Snapshot, korrekter Zeitvertrag, begrenzte Caches | B3: genaue Referenz und optionale Näherungen geprüft |
| 4 – Gemeinsames Netz und Lastenräder | Modus vom Fahrzeug bis QSim erhalten, Zugangslinks und Geschwindigkeiten prüfen | B4: beide Modi auf demselben Netz korrekt ausführbar |
| 5 – Durchgängige Integration | Ein Optimierungsdienst, deterministischer Durchgang, sichere Endpläne, Wiederaufnahme/Replay | B5: kleiner Hannover-Ablauf und Lausitz-Regime bestehen |
| 6 – Replanning-Zeit und RAM | Zulassung vor VRP-Aufbau, wiederverwendbare Worker, Warmstart und optionale adaptive Suche | B6: Zeit-/Speicher-/Qualitätsvergleich mit kontrollierter Parallelität |
| 7 – Bereinigung und Abnahme | Alte doppelte Wege entfernen, Ausgaben konsistent machen, Gesamtwirkung belegen | B7: lokale Abnahme; Produktionsnachweis separat ausweisen |
| 8 – Spätere jsprit-Analyse | Erst jetzt intern profilieren und nächste Ansatzpunkte priorisieren | Eigenständige Übersicht mit Evidenz, Aufwand und erwarteter Wirkung |

Die Phasen bauen aufeinander auf. Ihre Aufgaben werden jeweils klein umgesetzt, getestet und separat committet. Keine Vermischung von Upgrade, Änderungen fachlicher Tour-/Scoringregeln und Näherungs-/Parallelitätsoptimierung in einem Vergleich. Phase 3 kann Verträge zunächst mit kleinen Testkontexten prüfen; produktive Umschaltung folgt nach der Modus-/QSim-Abnahme.

## Dateistruktur und Verantwortlichkeiten

Alle Pfade sind relativ zur Wurzel des tatsächlich verwendeten HAGRID-Checkouts. Hier lautet die Hauptkopie `C:/Users/bienzeisler/Documents/GitHub/HAGRID`; bestehende isolierte Arbeit liegt in `C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID` auf `codex/zone-router-fixes`. Vor Ausführung Zuordnung und Schreibrechte prüfen; keine weitere Arbeitskopie ohne Bedarf erzeugen.

Zur eindeutigen Verkürzung der Dateilisten gelten ausschließlich diese Pfadpräfixe:

| Präfix | Exakter Pfad |
|---|---|
| MAIN | `hagrid/simulation/src/main/java/hagrid/` |
| TEST | `hagrid/simulation/src/test/java/hagrid/` |
| FIXTURES | `hagrid/simulation/src/test/resources/hagrid/routing/` |
| BENCH | `tools/benchmarks/` |

| Bereich / Dateien | Verantwortung |
|---|---|
| `pom.xml`, `external/freight/pom.xml`, `hagrid/simulation/pom.xml` | Versionen und Paketkompatibilität; Freight-Quellen stammen aus dem vorhandenen Submodul |
| `MAIN/core/HagridConfig.java`, `MAIN/core/simulation/HAGRIDSimulationConfig.java`; neu `MAIN/core/config/ResolvedHagridConfig.java`, `HagridConfigResolver.java` | Bestehende Eingänge weiterverwenden, einmalig geordnet auflösen und unveränderlich an Verbraucher geben |
| Neu `MAIN/core/routing/TravelTimeSnapshot.java`, `RoutingContext.java`, `RoutingRequest.java` | Fester Reisezeitstand, Profile, Netzansichten und deren Gültigkeit/Lebensdauer |
| `MAIN/core/routing/VRPTransportCostsFactory.java`, `ZoneBasedTransportCostsFactory.java`, `ZoneBasedTransportCosts.java`; neu `ModalTransportCosts.java` | Öffentlicher universeller Vertrag; vorhandene genaue Berechnung und optionale Zonenannäherung intern weiterverwenden |
| `MAIN/core/routing/HAGRIDRouterUtils.java`, vorhandene Constraints/Updater; neu `CarrierOptimizationService.java`, `PlanValidator.java`, `PlanSelection.java` | Ein Solver-Aufbau, vollständiges Ergebnis, unabhängige Validierung und vergleichbare Planauswahl |
| `MAIN/core/simulation/CarrierVehicleReRouter.java`, `ReplanningStrategies.java`; neu `ReplanningCoordinator.java` | Lebenszyklus und deterministische Gruppen; keine zweite Solver-Implementierung |
| Neu `MAIN/core/routing/CarrierBatchExecutor.java`, `WarmStartAdapter.java`, `SearchBudgetPolicy.java` | Ressourcen, jsprit-2.0-Startlösungen und optionale Suchregel |
| Neu `MAIN/core/routing/OptimizationResultStore.java`, `CarrierReplay.java` | Geprüfte persistente Einzelergebnisse und optionaler isolierter Diagnoseaufruf |
| `MAIN/core/simulation/HAGRIDScenarioBuilder.java`, `ByModeCongestedTravelTime.java`; neu `MAIN/core/routing/ModalNetworkImporter.java` | Konsistente Netze, Eingangsprüfung und Modi; kein zweiter veränderlicher Verkehrsstand |
| Hannover-/Lausitz-Aufrufer, `CarrierRoutingMetrics`, `CarrierRoutingStatusLogger`, `ScoringFunctions`, vorhandener Lausitz-Cache | Gemeinsame Dienste verwenden, Szenariounterschiede und Kennzahlen erhalten |

Neue einfache Datentypen bleiben benannte Records/Enums in ihrer zuständigen Klasse, solange sie keine eigene umfangreiche Logik besitzen. Keine pauschale Sammlung neuer Interfaces. Bereits vorbereitete Router-/Cache-Funktionen werden übernommen, soweit ihre Tests und die nachstehenden Verträge passen.

## Gemeinsamer Prüf- und Berichtsablauf

**Vergleichsdaten:** feste Seeds `42,43,44,45,46`, mindestens drei Messwiederholungen nach Aufwärmung für kurze Benchmarks. Laufreihenfolge Alt/Neu wechseln; gleiche Maschine, JDK, Heapgrenze, Konfiguration und tatsächliche Suchbudgets. Aufbau und Suche getrennt messen. Laufzeitmessungen laufen außerhalb instabiler Millisekunden-Assertions in normalen Unit-Tests.

**Kleine Fallauswahl:** vorhandener 60-Service-/30-Iterations-Fall; zusätzlich Shipment-Paare, zeitabhängiger NCE-Fall, Auto-/Lastenradfall und wiederholte gemischte Carrier-Gruppen. Nur die vom jeweiligen Schritt betroffenen Fälle ausführen. Größere reale Einzelfälle aufnehmen, sobald verfügbar; synthetische Fälle sind keine Produktionskalibrierung.

**Referenzen:** B0 bleibt eingefroren; zusätzlich letzter bestandener Zwischenstand. Korrigierte genaue Bewertung bildet die Referenz für Zonen-/Zeitnäherungen. Veränderte Tourgrenzen oder Kostenregeln erhalten benannte getrennte Vergleichsprofile. Ein bekanntermaßen unzulässiger Altplan wird dargestellt, aber nicht als Qualitätsziel verwendet.

**Bericht je Prüfpunkt:** Code-/Patchkennung, Abhängigkeiten, Eingabefingerprints, wirksame Konfiguration, Snapshot, Seeds, Threads, Iterationen; dann Änderung, fachliche Folgen, Zeit/RAM/GC, vollständige Aufträge, Verletzungen, Fahrzeuge, Kosten und Strecke. Median/Streuung und absolute/relative Unterschiede gegen B0 und den letzten Zwischenstand. Nullwerte nicht durch Prozentdivision behandeln. Fehlende Messwerte als fehlend ausweisen.

**Ablage:** `BENCH/results/<checkpoint>/manifest.json`, `measurements.csv`, `comparison.md`; große Netz-/Snapshot-Dateien außerhalb des Repositorys einmal je Kontext ablegen und im Manifest referenzieren. Eingabepfade und Maschinenparameter werden zur Ausführung aufgelöst, nicht als Voraussetzung für andere Rechner fest verdrahtet.

**Befehle:** Alle nachstehenden Befehle werden erst bei Umsetzung im ausgewählten Checkout ausgeführt. `mvn` bezeichnet dessen verfügbares Maven; unter Windows gegebenenfalls den bekannten `mvn.cmd`-Pfad verwenden. Ein gezielter Testaufruf lautet:

```powershell
mvn -pl hagrid/simulation -am test '-Dtest=HAGRIDRouterUtilsTest' '-Dsurefire.failIfNoSpecifiedTests=false'
```

`failIfNoSpecifiedTests=false` ist für Reactor-Module ohne passenden Test erforderlich. Zusätzlich stets prüfen, dass der benannte Test im HAGRID-Surefire-Bericht tatsächlich ausgeführt wurde. Abnahme bedeutet Exitcode 0, keine Fehler und keine unerwartet übersprungenen vorgesehenen Fälle. Infrastrukturfehler sind weder erwartetes Test-Rot noch bestandene Tests.

Jede Aufgabe endet mit Prüfung des Diffs und einem Commit ausschließlich ihrer benannten Dateien; kein `git add .`. Für reine Referenzerfassung/Profilerstellung ist kein künstlich fehlschlagender Produkttest erforderlich. Bei Verhaltensänderungen zuerst den spezifischen Fehler mit einem gezielten Test nachweisen, dann korrigieren.

## Phase 0 – Ausgangspunkt und Vergleichbarkeit

### Aufgabe 1: Ausgangsstand sichern und kleine Vergleiche reproduzierbar machen

**Dateien:** übernehmen/erweitern `BENCH/README.txt`, `ZoneBasedRoutingBenchmark.java`, `run-zone-router-benchmark.ps1`; neu `BENCH/run-routing-comparison.ps1`, `RoutingComparisonProbe.java`; neu `TEST/core/routing/RoutingComparisonContractTest.java`, `TEST/core/routing/benchmark/RoutingComparison.java`; kleine versionierte Eingaben in `FIXTURES/`.

**Interfaces:** Der neue Runner akzeptiert `-BaselineCheckout`, `-CandidateCheckout`, `-Checkpoint`, `-Cases`, `-Seeds`, `-Repeats`, `-HeapMb`, `-OutputDirectory`. Fälle heißen `services`, `shipments`, `temporal`, `modal` und `groups`; während der Umsetzung werden passende Fälle gezielt ergänzt. `RoutingComparisonProbe.main(String[] args)` schreibt die oben definierten Rohmessungen und explizite Laufmetadaten. Der Test-/Benchmark-Helfer `RoutingComparison.evaluate(Metrics baseline, Metrics candidate)` liefert `Summary(boolean comparable, boolean improved, List<String> reasons)`; seine geschachtelten `Metrics` enthalten Fall-/Regelkennung, Eingaben, Budgets, Zeiten und Qualitätswerte des gemeinsamen Berichtsvertrags. Runner/Probe verwenden denselben Helfer aus dem Test-Klassenpfad, ohne ihn in das Produktions-JAR aufzunehmen. Keine Abhängigkeit von erst später entstehenden HAGRID-APIs in der B0-Probe; gleiche Eingaben pro getrennter JVM.

- [x] Aktuellen Branch, Remote-Stand, Submodul-Commit, staged/unstaged Änderungen und vorbereitete Patches erfassen. Nur bei tatsächlich neuer Basis aktualisieren, nach Sicherung eigener Änderungen; ursprüngliche B0-Kennung behalten und Git-Aktualisierung separat messen.
- [x] Vorhandene Benchmarks und Fixtures sichern; heutige Variante getrennt von den vorbereiteten Router-Korrekturen bauen. Kein Mischen alter Router-Klassen mit später inkompatiblen neuen Klassen auf demselben Klassenpfad.
- [x] Berichtstest `incompleteCheaperPlanIsNotAnImprovement` mit `assertThat(report.improved()).isFalse()` für 9/10 erledigte Aufträge trotz geringerer Kosten und `differentBudgetsAreFlagged` mit `assertThat(report.comparable()).isFalse()` ergänzen. Berichtsdaten/Fixture-Builder im Test lokal definieren; ungültige Vergleiche nicht verschweigen.
- [x] Test vor Korrektur der Vergleichsauswertung ausführen; erwartete Verletzung der jeweiligen Aussage nachweisen. Runner und Probe so ergänzen, dass vollständige Metadaten/Qualität vor einer Verbesserungsaussage geprüft werden.
- [x] `mvn -pl hagrid/simulation -am test '-Dtest=RoutingComparisonContractTest,HAGRIDRouterUtilsTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen; beide Klassen müssen bestehen.
- [x] B0 mit fünf Seeds und wiederholten Messungen erstellen; vorhandene Drei-Seed-Ergebnisse als frühere Evidenz erhalten, nicht rückwirkend als neuen Fünf-Seed-Nachweis ausgeben. Kontrolllauf B0/B0 muss dieselbe fachliche Bewertung liefern; Laufzeitstreuung sichtbar machen.
- [x] Änderungen prüfen und als `test: establish reproducible routing comparison baseline` committen.

**Abschluss:** Ein frischer Vergleich lässt sich aus Manifest und Eingaben wiederholen. Keine Aussage über Verbesserungen vor Änderung; keine Veränderung des Produktionsverhaltens.

## Phase 1 – jsprit 2.0 isoliert integrieren

### Aufgabe 2: Versionen, verwendete IO-Pfade und Packaging migrieren

**Dateien:** ändern `pom.xml`, `external/freight/pom.xml`, `hagrid/simulation/pom.xml`; neuer `TEST/core/routing/JspritCompatibilityTest.java`; bestehende `TEST/lausitz/freight/JarFingerprintTest.java`, `JspritConstructionHeuristicTest.java`, `JspritPlanCacheTest.java` erweitern, soweit Paketversion/IO betroffen ist.

**Interfaces:** Bestehende HAGRID-/MATSim-Aufrufe bleiben erhalten. Parent `jsprit.version=2.0.0`, neue getrennte Property `jsprit.io.version=1.8`; alle Core-/Analysis-Abhängigkeiten verwenden die erste, IO die zweite. Kein Java-/MATSim-Upgrade.

- [x] Test `supportsSolutionSpecWithoutLockingJobs` gegen die veröffentlichte 2.0-API und kleine Service-/Shipment-Fälle schreiben; unter 1.8 muss die neue API fehlen. Die anderen vorhandenen Laufzeitpfade zunächst als Kompatibilitätsreferenz erfassen.
- [x] `rg -n 'VehicleRoutingAlgorithms|VrpXML|VehicleRoutingProblemSolution|jsprit' hagrid/simulation/src/main external/freight/pom.xml` auswerten; tatsächlich benutzte Algorithmus-XML-/Reader-/Writer-Pfade in den Test aufnehmen. MATSim-Carrier-XML getrennt behandeln.
- [x] Drei POMs umstellen und nur notwendige API-Anpassungen vornehmen. Bestehende Seed-Regeln, REGRET_INSERTION und FAST_REGRET=false beibehalten. IO-Inkompatibilität am konkreten Pfad mit kleinstem Adapter lösen; keine still übersprungenen XML-Fälle.
- [ ] `mvn -pl hagrid/simulation -am verify` ausführen. `mvn -pl hagrid/simulation -am dependency:tree '-Dincludes=com.graphhopper:*'` und das gebaute JAR prüfen: genau Core/Analysis 2.0.0, begründetes IO 1.8; benutzte Einstiegspunkte ausführbar, Cache-Fingerprint geändert.
- [x] B1 gegen B0 mit unveränderten Regeln/Budgets ausführen. Kosten, Vollständigkeit und Laufzeit pro Seed berichten; keine identischen heuristischen Touren verlangen.
- [ ] Gezielten Diff als `build: integrate jsprit 2.0 with verified IO compatibility` committen.

**Zwischenstand 2026-10-07:** Core/Analysis 2.0.0 und IO 1.8 sind lokal integriert; Freight-Parent-Pfad korrigiert. 74 gezielte Integrationstests bestanden, danach 17 Routing-/Vergleichstests einschließlich eines zusätzlichen Regressionstests (75 unterschiedliche Tests insgesamt). B1: fünf Seeds × drei Wiederholungen je Version; vollständig bedient, keine harten Verletzungen, gemischte Kostenwirkung. Seed 46 stoppt in 2.0 nach 27 statt 30 Iterationen und ist für eine Beschleunigungsaussage ausgeschlossen. Daten und korrigierte Auswertung sind gesichert. Die kleine Messreihe ersetzt deshalb keinen vollständigen Nachweis mit fünf gleichen tatsächlichen Suchbudgets. Vollständiges verify, ausgeliefertes JAR/Fingerprint und Paketstart bleiben wegen Valhalla-Priorität offen; Aufgabe 2 erhält noch keinen Abschlussvermerk. Code und kleine Nachweise werden als lokaler Zwischenstand committet.

**Abschluss:** Build, vorhandene Tests, relevante IO-Rundläufe und Paketstart bestehen. Fachliche Altfehler sind weiter sichtbar und werden in Phase 2 getrennt behoben.

## Phase 2 – Gemeinsame Konfiguration und fachliche Regeln

### Aufgabe 3: Einstellungen einmalig auflösen und an alle Verbraucher geben

**Dateien:** ändern `MAIN/core/HagridConfig.java`, `MAIN/core/simulation/HAGRIDSimulationConfig.java`, `MAIN/hannover/pipeline/ScenarioConfig.java`, `MAIN/lausitz/IntegratedScenarioConfig.java`, `MAIN/hannover/demand/CarrierVehicleFactory.java`; neu `MAIN/core/config/ResolvedHagridConfig.java`, `HagridConfigResolver.java`; Tests `TEST/core/config/HagridConfigResolverTest.java`, vorhandene `HagridConfigTest`, `HAGRIDSimulationConfigTest` und Szenario-Parsing-Tests.

**Interfaces:** `HagridConfigResolver.resolve(HagridConfig defaults, HagridConfigResolver.Overrides profile, HagridConfigResolver.Overrides run, Map<String,String> cli)` liefert `ResolvedHagridConfig`. `Overrides` hält nur ausdrücklich gesetzte kanonische Werte; vorhandene Parser erzeugen diese Übergabe, statt alle Defaultwerte als Override einzutragen. Ergebnis besitzt typisierte Bereiche `run()`, `demand()`, `fleet()`, `tourRules()`, `routing()`, `optimization()`, `resources()`, `results()` und `scenario()`. Felder/Einheiten folgen Spec 8.2; kein neues Dateiformat erzwingen.

- [x] `precedenceAndUnits` schreiben: `assertThat(resolved.tourRules().maxRouteDurationSeconds()).isEqualTo(25200)` im Hannover-Zielprofil; explizites Lauf-/CLI-Override gewinnt, Lausitz bleibt explizit. Ein Testwert 18 km/h ergibt `assertThat(speedMps).isEqualTo(5.0)`. Nicht gesetzte Werte dürfen keine Profilwerte überschreiben. Ohne Geschwindigkeits-Override beträgt die Lastenrad-Obergrenze 20 km/h.
- [x] Gezielt roten Test ausführen: `mvn -pl hagrid/simulation -am test '-Dtest=HagridConfigResolverTest' '-Dsurefire.failIfNoSpecifiedTests=false'`.
- [ ] Auflösung Defaults → Szenarioprofil → Lauf → CLI implementieren; Validierung und Herkunft der wirksamen Werte ausgeben. Routing-/Nachfrageschwellen korrekt zuordnen. `isUsingZones` und `zoneBasedCachingEnabled` getrennt migrieren; Verfügbarkeitspuffer von bisher 3600 Sekunden ausdrücklich benennen.
- [ ] Bestehende Getter/Konstruktoren zunächst delegieren lassen; Eingabemigrationstabelle in `docs/superpowers/plans/2026-10-07-hagrid-jsprit-config-migration.md` mit alten/neuen Namen, Einheit und Verhaltensänderung erstellen. Keine Änderung von fremden Notebooks erforderlich machen.
- [ ] Resolver-, bestehende Config- und Parsing-Tests ausführen; Standardwerte und Legacy-Vergleichsprofil getrennt bestätigen. B2a misst zunächst identische aufgelöste Einstellungen; die 25200-Sekunden-Solvergrenze erhalten; bestehende Erzeugungs-/Scoringwerte von 27000 getrennt erfassen und keine Angleichung als reine Strukturänderung darstellen.
- [ ] Als `refactor: resolve shared HAGRID settings once` committen.

**Zwischenstand Aufgabe 3, 2026-10-07:** Unveränderlicher Resolver-Unterbau mit neun Zuständigkeitsbereichen, sparsamen Overrides, Herkunft/Einheitenvalidierung und gebietsgeschützten Profilen umgesetzt. Nutzerpräzisierung berücksichtigt: Hannover-Solver 25200 Sekunden; Lastenrad-Eingang in HagridConfig.vehicles standardmäßig 20 km/h, konfigurierbar. 140 gezielte Tests bestanden, davon 13 neue Resolver-/Geschwindigkeitsfälle. Migrationstabelle dokumentiert vorhandene 27000-Sekunden-Erzeugungs-/Scoringwerte getrennt. Produktive Parser-/Konstruktoradapter, Verbrauchermigration und B2a stehen noch aus; Aufgabe 3 ist nicht abgeschlossen. Aufgabe 2 bleibt ebenfalls zur vollständigen Paket-/Gesamtabnahme offen.

**Weiterer Zwischenstand Aufgabe 3:** Simulationsparser erhält ausdrücklich angegebene Schlüssel; Szenarioadapter trennt Initial-/Replanning-/Offlinebudgets, bewahrt native Lausitz-Regeln und prüft Laufidentität. Lastenrad-Override lässt sich im vorhandenen Hannover-Builder setzen und bleibt über Nachfrage-Runner und erzeugte Startargumente erhalten. 231 gezielte Tests bestanden, darunter 12 neue Adapter- und 4 Übergangsfälle. Vollständige Übernahme aller Einstellungen sowie produktive Solver-/Scoring-Verbrauchermigration und B2a bleiben offen. Kein neuer Laufzeit-/Qualitätsgewinn behauptet.

### Aufgabe 4: Solverzustände, Bedienzeiten und Tourregeln vereinheitlichen

**Dateien:** ändern `MAIN/core/routing/HAGRIDRouterUtils.java`, `StateIds.java`, `TimeWindowConstraintWithDriverTime.java`, `UpdateDepartureTimeAndPracticalTimeWindows.java`, `RouteRealStartTimeMemorizer.java`, `MaxRouteDurationConstraint.java`, `DepartureTimeReScheduler.java`, `MAIN/core/simulation/CarrierVehicleReRouter.java`, `ScoringFunctions.java`; neu `TEST/core/routing/CarrierRulesTest.java`, `StateIsolationTest.java`.

**Interfaces:** Gemeinsamer Einstieg `HAGRIDRouterUtils.configureAlgorithm(VehicleRoutingProblem vrp, ResolvedHagridConfig config, long seed, int iterations)`. `StateIds` ist eine Instanz pro `StateManager`; die bisherigen vier statischen veränderlichen IDs werden Instanzfelder. Constraints/Updater bekommen exakt diese Instanz. Vorhandene Signaturen delegieren während der Aufrufermigration.

- [ ] Tests für Services und Shipments schreiben: definierte 120 Sekunden Bedienung müssen `assertThat(duration).isEqualTo(120.0)` ergeben, unabhängig von noch nicht gesetzten Aktivitäts-Endzeiten. Bedienkostensatz 0,5 ergibt 60 Kosten, Wartezeit mit eigenem Satz zusätzlich. Zwei parallele StateManager müssen unverändert die eigenen Werte lesen.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=CarrierRulesTest,StateIsolationTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen und tatsächliche Altfehler bestätigen.
- [ ] Gemeinsamen Algorithmusaufbau und Zustandsübergabe implementieren; Kapazität, Skills, Fenster, Fahrer-/Tourgrenzen, U-Turn-/Mautregeln übernehmen. Abfahrtsänderungen lösen erneute Zeit-/Kostenrechnung aus; Suchwert und MATSim-Score bleiben getrennt.
- [ ] Tests um einen knapp innerhalb/außerhalb 25200 Sekunden liegenden Hannover-Fall und das explizite Lausitz-Limit erweitern; verspätete Abfahrtsverschiebung muss als Verletzung erkannt werden. Betroffene Scoring-Verzweigung mehrfach prüfen, damit einmalige Initialisierung tatsächlich einmalig ist.
- [ ] Gezielte Tests und B2b gegen B2a ausführen; jede erlaubte Ergebnisänderung dem behobenen Fehler oder der benannten Tourregel zuordnen. Kein pauschales Zusammenlegen aller Wirkungen.
- [ ] Als `fix: unify carrier timing costs and solver state` committen.

## Phase 3 – Reisezeitstand und universeller Kostenanbieter

**Zwischenstand Aufgabe 4, 2026-10-07:** Gemeinsamer Solveraufbau im realen Hannover-Replanner, StateManager-eigene Kennungen, korrekte Service-/Shipment-Dauern und Kostensätze sowie einmalige Überzeitstrafe umgesetzt. Abfahrtsverschiebungen werden neu bewertet und bei Fenster-/Verfügbarkeits-/Dauerverletzung oder schlechteren Suchkosten zurückgenommen. 250 gezielte Tests bestanden. Repräsentativer B2b/B2a-Vergleich bleibt offen; Aufgabe nicht als vollständig abgeschlossen markiert.

### Aufgabe 5: Unveränderlichen Reisezeitstand und Routing-Kontext bereitstellen

**Dateien:** neu `MAIN/core/routing/TravelTimeSnapshot.java`, `RoutingContext.java`; ändern `MAIN/core/simulation/ByModeCongestedTravelTime.java`; neu `TEST/core/routing/TravelTimeSnapshotTest.java`.

**Interfaces:** `TravelTimeSnapshot.capture(Network network, Map<String,TravelTime> measuredByMode, Map<String,TravelTime> fallbackByMode, int measurementIteration, double horizonSeconds, int binSizeSeconds)` erzeugt unabhängige Werte/Profile, keine spätere Live-Delegation. `TravelTime forMode(String mode)` und `String fingerprint()`; Fahrzeugobergrenzen bleiben beim Profilzugriff wirksam. `RoutingContext.create(Network network, CarrierVehicleTypes types, TravelTimeSnapshot snapshot, ResolvedHagridConfig config)` erzeugt den laufbezogenen Kontext; `fingerprint()`, `network()`, `snapshot()`, `close()` sind explizit.

- [ ] `survivesCalculatorResetAndNetworkMutation` schreiben: vorher/nachher-Abfragen und `assertThat(after).isEqualTo(before)` trotz Reset und geänderter Live-Geschwindigkeit. Unbeobachteter Link verwendet den eingefrorenen Ersatzstand, nicht eine später mutierte Funktion.
- [ ] Rot bestätigen, dann Snapshot als gemessene Zeitbins plus kompakte unveränderliche Ersatzprofile implementieren. Fahrzeug-/Modusprofile berücksichtigen, keine dichte Tabelle je Fahrzeug erzeugen. Zeitbereich und zugrunde liegende Auflösung im Fingerprint führen.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=TravelTimeSnapshotTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen; auch fehlender Modus, ungültige Binbreite und Bereichsüberschreitung ergeben eine klare Diagnose.
- [ ] B3a erfasst Aufbauzeit und Speicher des kleinen Snapshots, erklärt Unterschiede zur bisherigen veränderlichen Quelle. Lebenszyklusauslösung erst in Aufgabe 11 verdrahten.
- [ ] Als `feat: freeze shared routing travel times` committen.

**Abschluss Aufgabe 5, 2026-10-07:** Kompakter unveränderlicher TravelTimeSnapshot, eigener schreibgeschützter RoutingContext mit kopiertem Netz/Ereignissen/Fahrzeugprofilen und geprüftem ByMode-Adapter umgesetzt. 277 gezielte Tests bestanden, darunter 15 Snapshot-, neun Kontext- und drei Modusfälle. B3a enthält separate Snapshot- und kombinierte Kontextmessungen mit Rohdaten/Manifest. Der kombinierte 1000-Link-Fall benötigt 7,73–9,06 ms Snapshot plus 30,30–36,20 ms Kontextaufbau; numerischer Profilinhalt 40024 Byte, Aufbauallokationen etwa 8,22 MB. Das ist kein gehaltener Heap und kein Solver-Speedup. Lebenszyklusverdrahtung bleibt Aufgabe 11; Binanfangsabtastung ist benannt, FIFO-/Ereignisauflösung folgt in Aufgabe 7. Keine Freigabe als exakter zeitabhängiger Router.

### Aufgabe 6: Factory und alle Framework-Abfragen auf einen Vertrag umstellen

**Dateien:** neu `MAIN/core/routing/RoutingRequest.java`, `ModalTransportCosts.java`; ändern `VRPTransportCostsFactory.java`, `ZoneBasedTransportCostsFactory.java`, `ZoneBasedTransportCosts.java`; neu `TEST/core/routing/ModalTransportCostsTest.java`; vorbereitete `ZoneBasedTransportCostsFactoryTest.java` erhalten.

**Interfaces:** `RoutingRequest(Carrier carrier, RoutingContext context, String preparationMode)` bindet eine Einzelmodus-Aufgabe und deren explizites neutrales Vorbereitungsprofil. `VRPTransportCostsFactory.createVRPTransportCosts(RoutingRequest request)` liefert MATSim `VRPTransportCosts`. `ModalTransportCosts` implementiert dessen vollständigen Vertrag einschließlich Transportzeit/-kosten/-distanz, Rückwärtsabfragen, `getRouter()`, `getTravelTime()`, `getNetwork()`. Keine Änderung des MATSim-Interfaces.

- [ ] Mit gemeinsamem Netz und unterschiedlichen Auto-/Rad-Zeiten `allAccessPathsUseVehicleMode` schreiben: Kostenabfrage und tatsächliches Straßenrouting samt Ziel-Link liefern dieselbe Moduszeit. `nullVehicleUsesDeclaredPreparationMode` prüft Rad auch ohne Fahrzeug. Unbekannter Typ und widersprüchliche Typ-ID müssen scheitern.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=ModalTransportCostsTest,ZoneBasedTransportCostsFactoryTest' '-Dsurefire.failIfNoSpecifiedTests=false'` vor Implementierung ausführen und die fehlenden Verträge nachweisen.
- [ ] Interne Modusansichten vorbereiten, IDs kontrolliert zuordnen und alle Framework-Zugänge delegieren. Ziel-Link-Zeit und Fahrzeughöchstgeschwindigkeit einschließen; Adapter einmal beim Kontextaufbau, nicht pro Linkabfrage erzeugen. Keine Map-Rückgabe oder Downcasts für neue Aufrufer.
- [ ] Bestehende Factory-Methoden vorerst als Kompatibilitätsadapter behalten; neue Einzelanbieter-Pfade in kleinen Referenzfällen nutzen. Kontextstatistik wird über den Kontext, nicht per Cast auf den Anbieter gelesen.
- [ ] Tests und B3b ausführen; Zeit/Distanz/Kosten des genauen Referenzfalls sowie null-Fahrzeug-Vorbereitung explizit vergleichen.
- [ ] Als `refactor: provide universal modal transport costs` committen.

**Abschluss Aufgabe 6, 2026-10-07:** RoutingRequest bindet den Einzelmodus-Carrier und das neutrale Vorbereitungsprofil. Der universelle MATSim-VRPTransportCosts-Anbieter wählt für alle Zugänge denselben eingefrorenen Modus und Fahrzeugdeckel. Einmalige Modusgraphen/-adapter gehören zum geteilten Kontext. Der neue Factory-Einstieg ist ein Kompatibilitäts-Default; vorbereitete Legacy-Patches bleiben bis zu ihrer Einzelprüfung erhalten. 288 gezielte Tests bestanden, darunter elf neue Modusfälle und echte Freight-Tour/Ziel-Link-Abfragen. B3b bestätigt gleiche statische Fahrzeug-Zeit/Distanz/Kosten wie die native Referenz und korrekte neutrale Radvorbereitung. Der Zwischenstand hat noch keinen OD-Cache; native warme Cacheabfragen sind im Kontrollfall erwartbar schneller. Herkunfts-Link-Distanzkonvention wird ausdrücklich erst in Aufgabe 7 geändert. Variable Rückwärtsprofile werden bis zum Zeitvertrag abgewiesen. Keine produktive Gesamtabnahme oder allgemeine Beschleunigung behauptet.
### Aufgabe 7: Zeitvertrag, optionale Näherung und Cache-Lebensdauer korrigieren

**Dateien:** ändern `MAIN/core/routing/ZoneBasedTransportCosts.java`, `RoutingContext.java`, `ModalTransportCosts.java`; Tests `TEST/core/routing/ZoneBasedTransportCostsTest.java`, neu `TemporalRoutingContractTest.java`, `RoutingContextLifetimeTest.java`; `BENCH/ZoneBasedRoutingBenchmark.java` erweitern.

**Interfaces:** Kontext besitzt genaue/angenäherte Bewertung und begrenzten Cache, keine neue öffentliche Factory-Variante. Ein interner `TransportValues(timeSeconds, distanceMeters, monetaryCost)`-Record liefert zusammengehörige Werte. `RoutingContext.close()` beendet ausschließlich eigene laufende Arbeit und gibt eigene Daten frei; ein Carrier leert keinen fremden Cache.

- [ ] Tests `destinationLinkCountedOnce`, `backwardTimeMatchesForwardArrival`, `nonFifoProfileRejected`, `altBoundCoversChangedSpeeds`, `failedQueryCanRetry`, `contextsNeverShareCachedValues` ergänzen. Künstliche Kante über einer Zeitgrenze prüft die Vorwärtsankunft numerisch; Vor-/Rückwärtsprüfung nutzt dieselbe dokumentierte Toleranz.
- [ ] Gezielte Tests vor Änderungen ausführen. Vorbereitete Patches einzeln zuordnen; keine Regressionstest-Duplikate und kein ungeprüfter vollständiger Patchimport.
- [ ] Exakte Link-/Zeitsemantik korrigieren. Rückwärtssuche nur bei bewiesener monotoner Ankunftsfunktion; Nicht-FIFO-Eingaben diagnostizieren. ALT mit gültiger Untergrenze oder zulässiger Referenzsuche; FiFoTravelTime nicht ungeprüft als Lösung übernehmen.
- [ ] Räumliche Näherung und OD-Zeitquantisierung separat schalten; deterministische erreichbare Zonenvertreter, Endprüfung auf konkreten Stopps. Cache nach Kontext, Modus, Profil und Genauigkeit trennen; maximale Einträge und gleichzeitige Berechnungen begrenzen. Gemeinsame Graphaufbereitung einmalig, mutable Suchdaten pro Worker.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=ZoneBasedTransportCostsTest,TemporalRoutingContractTest,RoutingContextLifetimeTest,ModalTransportCostsTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen. Wiederholte Kontextwechsel dürfen weder historischen Thread-Router-Bestand noch fremde Cachewerte behalten.
- [ ] B3c: zuerst genaue Variante, danach Zonen und Zeitquantisierung separat; gehaltene Testpaare nach unabhängiger Cache-Füllung, genaue Nachbewertung der Solver-Touren. Speicher über mehrere Gruppen beobachten.
- [ ] Als fachlich trennbare Commits `fix: enforce temporal routing contracts` und `perf: bound and reuse routing context data` sichern; nach jedem relevanten Teil passender kleiner Vergleich.

**Zwischenstand Aufgabe 7, 2026-10-07:** Konsistente Ziel-Link-Distanz, begrenzte Rückwärtsinversion mit Vorwärtskontrolle, FIFO-Diagnose und Dijkstra mit explizitem Modus umgesetzt. 69 gezielte Tests bestehen; B3c bestätigt korrigierte Distanz bei unveränderten statischen Zeiten/Kosten. Das native Gegenbeispiel zeigt eine nichtmonotone Ankunft durch Wechsel der billigsten gemischt bewerteten Route trotz FIFO-Links. Dynamische gemischte Kosten werden deshalb bis zu einer ausdrücklich geprüften Routingpolitik diagnostiziert; statische gemischte und FIFO-reine Zeitkosten sind unterstützt. Cache-/Worker-Grenzen, Näherungsschalter und gezielte Zonenübernahme stehen noch aus; Aufgabe 7 bleibt in Arbeit.
**Weiterer Zwischenstand Aufgabe 7:** Zentrale Cache-/Routergrenzen, begrenzter gemeinsamer LRU, Fehlerwiederholung, Kontextisolation und sichere eigene Lebensdauer implementiert. 108 gezielte Tests nach Horizontkorrektur, anschließend 73 Tests des schlankeren Abfragepfads bestanden. Neuer Kontrolltest und abschließender breiter Lauf noch ausstehend: vor Start wegen 5,75 GiB freier RAM unter 6-GiB-Reserve zurückgestellt. B3c zeigt vor dieser Abfrageoptimierung höheren Aufwand als der native warme Cache; keine Beschleunigungsbehauptung. Aufgabe bleibt in Arbeit, insbesondere für Näherungsverdrahtung und vollständige Cache-/Messabnahme.
## Phase 4 – Gemeinsames Netz und Lastenräder bis QSim

### Aufgabe 8: Netze und Carrier-Eingaben konsistent laden

**Dateien:** neu `MAIN/core/routing/ModalNetworkImporter.java`; ändern `MAIN/core/simulation/HAGRIDScenarioBuilder.java`, `MAIN/core/util/NetworkFilterAndReducer.java`, `MAIN/hannover/demand/CarrierGenerator.java`, `CarrierVehicleFactory.java`, `SupplyCarrierGenerator.java`; neue Tests `TEST/core/routing/ModalNetworkImporterTest.java`, `TEST/core/simulation/CarrierInputConsistencyTest.java`.

**Interfaces:** `ModalNetworkImporter.load(ResolvedHagridConfig config)` liefert ein gemeinsames `Network`; vorhandene Inputformate bleiben unterstützt. `validate(Carriers carriers, CarrierVehicleTypes types, Network network, ResolvedHagridConfig config)` prüft IDs, Modus, Fahrzeug-/Jobzuordnung und Zugangslinks vor Registrierung. Neue Ortszuordnung benutzt zulässige Moduslinks; bestehende Stopps werden nur bei ausdrücklicher Remap-Option verschoben.

- [ ] `conflictingSharedIdFails`, `duplicateCarrierIdFails`, `carOnlyNeedsNoBikeFile`, `preservesRecordedIterationsAndMode` schreiben. Prüfen: Auto-/Rad-exklusive Links bleiben vorhanden, fehlende Messwerte werden nicht zu 40 Iterationen/900 Sekunden erfunden.
- [ ] Rote Tests ausführen, dann bevorzugt gemeinsames Ausgangsnetz laden oder Teilnetze kontrolliert vereinigen. Richtung, Geometrie, Modi, Attribute und Ereignisse prüfen; gleichnamige physisch andere Links nicht überschreiben. `bike`/`cargobike` nur mit benannter Aliasabbildung normalisieren.
- [ ] Carrier/VehicleTypes einmal lesen und im Scenario registrieren; Merge im Speicher, kombinierte XML optional erhalten. Neue Carrier-Erzeugung und bestehende Pläne gegen erreichbare Moduslinks prüfen. Doppelte IDs als Fehler vor MATSim-addCarrier melden.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=ModalNetworkImporterTest,CarrierInputConsistencyTest,DeliveryModeOfTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen.
- [ ] B4a dokumentiert Ladezeit/RAM sowie fachliche Änderungen durch korrekte Modi/Stopps. Produktionsnetz-Merge bleibt ohne echte Dateien unbewiesen.
- [ ] Als `fix: preserve modal networks and carrier input semantics` committen.

**Weiterer Zwischenstand Aufgabe 8, 2026-10-08:** Gemeinsamer lesender Netzeingangsprüfer für erste/einzige Quellen, Merge und Carrier-Grenze sowie ein geteilter iterativer Erreichbarkeitsindex pro genutztem Modus umgesetzt. Services brauchen ein erreichbar rückkehrbares Depot, Shipment-Pickup und Delivery dasselbe; Stopps werden nicht verschoben. Topologie ist nur eine notwendige Prüfung ohne Turn-/Zeit-/Kapazitätszertifikat. Factory-Typregistry jetzt instanzgebunden, Typ-/Kapazitätsauflösung separat; Erzeuger erhalten die aufgelöste Fleet-Konfiguration. Schlusslauf K32: 269/269 gezielte Tests, 14,70 GiB kleinste gemessene freie RAM-Menge. Modussichere Nachfrage-/Ortszuordnung, vollständige Erzeuger-/Offline-Netzumstellung, doppelte Typ-XML-IDs und Produktions-Ladezeit/RAM bleiben offen; Aufgabe 8 nicht vollständig abgeschlossen.

**Fortsetzung Aufgabe 8, K33–K41:** Gemeinsamer CarrierModeResolver für Importprüfung und tatsächliche Generator-Flottenzuweisung. OfflineCarrierRouting prüft Liefer-/Supply-Eingaben zusammen vor dem ersten Solver und routet exakte Modusgruppen auf dem tatsächlichen Scenario-Netz; ein nativer Anbieter je genutztem Modus pro Lauf. NativeModalTransportCosts ist mit dem Legacy-Replanning geteilt, einschließlich Modusbindung nativer/neutrale Fahrzeugwrapper. ModalRoutingNetwork kopiert getrennte Topologie/Attribute, erhält Netzeinheiten und projiziert passende nichtleere Events. Das bisherige Parcel-Netz-Aliasing ist beseitigt. CarrierVehicleTypeInputs schützt die einzelne native Typdatei-Ladung vor doppelten IDs; native Reader-Schemafallbacks sind nullsicher. K39 285/285 GREEN; nach Typ-XML K41 50/50 gezielt GREEN. Abschließender K42: 294/294 Tests GREEN, ein JVM-Prozessor, Maven/Test 512/384 MiB, mindestens 10,76 GiB RAM frei; Aufgaben bleiben teilweise offen. Modussichere neue Orts-/Depotzuordnung, vollständige Erzeuger-Ausgabegates, echte QSim, Snapshot-/Cache-/Solver-Zulassung, Produktions- und Leistungsnachweise bleiben offen; Aufgabe 8 wird nicht abgeschlossen.

**Fortsetzung Aufgabe 8, K43–K47:** CarrierGenerationAccess (91 Zeilen) teilt explizite Modusansichten und ordnet neue Liefer-Stopps sowie erreichbare Hub-/Depotzugänge zu. Pkw-Parcel-Auswahl erhalten, Rad-exklusive Links zulässig, Hub-Supply-Link bleibt unverändert. Mischflotten werden vor Fahrzeugen/RNG/Hub-Reservierung abgelehnt. Gemeinsame Eingangsprüfung und Carrier-Registrierung nach Mengen-/Supply-Prüfung; Fehler stoppen die Stufe, vorhandene Carrier bleiben erhalten. Temporäre Modusansichten werden nach dem Schritt freigegeben. Eigene Kapazitätstypen behalten Gewicht/Volumen/Motor- und Kapazitätsattribute. K47 69/69 gezielt GREEN; echte Gegenproben für Metadatenverlust und drei Abbruchfälle. K43 wegen RAM vor Start zurückgestellt, neue Ortszuordnung daher gemäß Nutzerfreigabe zunächst ohne ausgeführten RED implementiert, anschließend positiv geprüft. Supply-/Service-Merge-/Produktionsnetz-/Shipment-Gates, QSim und gepaarte Nachweise bleiben offen; keine vollständige Aufgabe-8-Abnahme.

**Gemeinsamer Schlusslauf K48:** 310/310 Tests bestanden, alle 33 ausgewählten Rootklassen nachgewiesen; 26,885 Sekunden, Auto4/1024/1024, mindestens20,74GiB frei, maxCPU28,49%, Exit0/noDeferred. Verifiziert die vorbereiteten kleinen Fälle, keinen Produktions-/Speedup-/Gesamtabschluss. Aufgaben7/8 bleiben in Arbeit; weitere Prüfungen siehe aktualisierte 2026-10-08-hagrid-jsprit-open-checks.md.

### Aufgabe 9: Geschwindigkeiten und Fahrer-Legs in echter QSim prüfen

**Dateien:** ändern `MAIN/core/simulation/HAGRIDSimulationModule.java`, `ByModeCongestedTravelTime.java`, `HAGRIDScenarioBuilder.java`, `MAIN/hannover/demand/CarrierVehicleFactory.java`; neuer `TEST/core/simulation/CargoBikeRoutingIntegrationTest.java` mit kleinen Netzen unter `FIXTURES/`.

**Interfaces:** Native Carrier-/Fahrzeugmodi und MATSim `LinkSpeedCalculator` verwenden. Für die erste Variante gilt gemeinsamer Straßenlink: importierte Linkgeschwindigkeit begrenzt durch Fahrzeugmaximum; radexklusive Links haben eigene Profile. Kein eigener Rechner, solange der native Fallback diese Regel erfüllt. Eine spätere abweichende Modusgeschwindigkeit benötigt ausdrücklich denselben Rechner im Router und QSim.

- [ ] Controller-Test mit einem Auto- und einem Rad-Carrier schreiben. Rad-Testmaximum 18 km/h dient nur der Fixture. `assertThat(driverLegMode).isEqualTo("cargobike")`; Radroute darf keinen autoexklusiven Link enthalten, beide Routenlinks müssen im QSim-Netz existieren.
- [ ] Tatsächlich fehlschlagende Modus-/Geschwindigkeitsübergaben nachweisen. Fahrzeugmaximum, QSim-Modi und Carrier-Modus verdrahten; gemischte Modi innerhalb eines Carriers sowie nicht durchgängig unterstützte offene Touren früh verständlich zurückweisen.
- [ ] Vollständige Linkfahrten einschließlich NCE-Wechsel messen. Fahrtzeit muss mindestens `length / min(profileSpeed, vehicleMax) - qsimTimeStep` betragen; kurze gerundete Links nicht als alleinigen Nachweis verwenden. Langsamere Profile, falsche km/h-Eingabe und radexklusive Wege ohne Pkw-NCE gesondert prüfen.
- [ ] Gemischte Fahrt mit FIFO als bestehendem Queue-Modell prüfen; Kapazitäts-/Speicherbeiträge dokumentieren. Kein stiller Wechsel zu PassingQ/SeepageQ. Technische Konsistenz und empirische Kalibrierung im Bericht getrennt ausweisen.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=CargoBikeRoutingIntegrationTest,ModalTransportCostsTest,CarrierInputConsistencyTest' '-Dsurefire.failIfNoSpecifiedTests=false'` und B4b ausführen.
- [ ] Als `feat: run cargo bike carriers consistently on the shared network` committen.

**Vorbereitung Aufgabe 9, 2026-10-08:** CarrierFleetPreparation setzt vor dem Lesen der Scenario-Carrier den nativen Radtypdeckel auf min(XML-Maximum, aufgelöster Fleet-Grenze); auch die Fahrzeugerzeugung benutzt dieselbe Regel. Default 20 km/h, explizit 18 km/h und langsameres XML sind in kleinen realen Typ-/Carrier-Dateien und Factory-Kontrollen geprüft; exakte bike/cargobike-Modi und Straßenlimits bleiben erhalten. Das ist noch keine QSim-Abnahme. Nächster Schritt erst nach durchgehendem Erzeuger-/Offline-Modusanschluss: tatsächliche native QSim-Legmodi und Linkzeiten einschließlich NCE messen.

## Phase 5 – Gemeinsame Optimierung und sichere Simulationseinbindung

### Aufgabe 10: Einen Optimierungsdienst mit unabhängiger Planprüfung einführen

**Dateien:** neu `MAIN/core/routing/CarrierOptimizationService.java`, `PlanValidator.java`, `PlanSelection.java`; ändern `HAGRIDRouterUtils.java`, `DepartureTimeReScheduler.java`, `MAIN/hannover/demand/Router.java`, `CarrierRouter.java`, `MAIN/lausitz/freight/LausitzFreightPreprocessor.java`; neue Tests `TEST/core/routing/PlanValidatorTest.java`, `CarrierOptimizationServiceTest.java`.

**Interfaces:** `CarrierOptimizationService.optimize(Carrier carrier, RoutingRequest request, ResolvedHagridConfig config, long seed, int iterations)` liefert den geschachtelten `Result(String carrierId, CarrierPlan plan, Set<String> unassignedJobIds, double searchCost, double finalCost, int iterations, CarrierRoutingMetrics metrics, String contextFingerprint, List<String> diagnostics)`. Dieser enthält einen eigenen Plan und Phasenzeiten; der Worker mutiert den Scenario-Carrier nicht. `PlanValidator.validate(Carrier carrier, CarrierPlan plan, RoutingRequest request, ResolvedHagridConfig config)` liefert `PlanValidator.Validation(boolean valid, Set<String> servedJobIds, List<String> violations, double finalCost)`. `PlanSelection.choose(CarrierPlan previous, Result candidate, Carrier carrier, RoutingRequest request, ResolvedHagridConfig config)` liefert `PlanSelection.Selection(CarrierPlan selected, Optional<Validation> previous, Validation candidate, boolean completed)`; Gleichstand behält den gültigen bisherigen Plan. Ohne zulässigen vollständigen Gewinner ist completed=false; ein erhaltener alter Plan ist dann kein Erfolgsnachweis.

- [ ] `partialCheapPlanCannotReplaceCompletePlan`, `shipmentPickupMustPrecedeDelivery`, `departureShiftRechecksTimeWindows`, `validCheaperPreviousPlanWins` schreiben. Assertions prüfen vollständige IDs, Reihenfolge und `assertThat(carrier.getSelectedPlan()).isSameAs(before)` während der isolierten Suche.
- [ ] Rot nachweisen, dann gemeinsame Problem-/Solver-Erstellung auf Worker-eigenen Carrier-/Planobjekten implementieren. Service-/Shipment-, Kapazitäts-, Skill-, Depot-/Zeitregeln unabhängig nachrechnen; finale Straßenroute, Ziel-Link und Kosten einschließlich vorhandener Zusatzkosten prüfen.
- [ ] Hannover-Offline- und Lausitz-Aufrufer schrittweise auf den Dienst umstellen; unterschiedliche Iterationsbudgets bleiben Parameter. Rückgaben zentral sammeln, keine geteilten ArrayList-Schreibzugriffe aus Worker-Lambdas.
- [ ] Bisherige doppelte Konfiguration nur nach Umstellung des jeweiligen Aufrufers entfernen. Ein nicht lösbarer vollständiger Carrier erhält Fehlerstatus; bisheriger Plan bleibt erhalten, aber nicht automatisch als gültig gekennzeichnet.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=PlanValidatorTest,CarrierOptimizationServiceTest,HAGRIDRouterUtilsTest,JspritConstructionHeuristicTest' '-Dsurefire.failIfNoSpecifiedTests=false'` und B5a ausführen.
- [ ] Als `refactor: share carrier optimization and validated plan selection` committen.

### Aufgabe 11: Einmaliges Replanning deterministisch in MATSim einbinden

**Dateien:** neu `MAIN/core/simulation/ReplanningCoordinator.java`; ändern `CarrierVehicleReRouter.java`, `ReplanningStrategies.java`, `HAGRIDSimulationModule.java`, `SimulationRunnerUtils.java`, `MAIN/lausitz/freight/FreightRunComposer.java`; neue Tests `TEST/core/simulation/ReplanningCoordinatorTest.java`, `ReplanningLifecycleIntegrationTest.java`; bestehender `TEST/lausitz/freight/FreightRunComposerTest.java`.

**Interfaces:** `ReplanningCoordinator.runGroup(int iteration, Carriers carriers, Supplier<RoutingContext> initialContext)` wird im Replanning-Ereignis aufgerufen; Supplier genau einmal beim ersten tatsächlich erforderlichen Auftrag. Der Koordinator besitzt stabile Carrier-Reihenfolge und Statusenum `PENDING/RUNNING/COMPLETED/FAILED/CANCELLED`, nutzt den Dienst aus Aufgabe 10 und übernimmt komplette Ergebnisse auf dem Controller-Thread. Zunächst seriell; Aufgabe 14 tauscht nur die Ausführung aus.

- [ ] Echter Controller-Test: erster Optimierungsaufruf vor TravelTimeCalculator-Reset; Snapshot-Messiteration 24 bei Start 25. Zwei LSP mit insgesamt 17 Carriern benötigen bei Gruppengröße 16 genau zwei Gruppen und trotzdem einen Snapshot. Eine leere Gruppe ruft den Supplier nicht auf.
- [ ] Vorhandene Zufallsauswahl-/Markerfehler reproduzieren. Deterministische Liste, frühe Budget-/Iterationskonfigurationsprüfung und echte Erfolgsmarker nach Prüfung/Übernahme implementieren. Keine Plankopie für unbeteiligte Carrier.
- [ ] BestPlan und Abfahrtsmutation dürfen danach keinen ungeprüften Altplan auswählen. Gewünschte Abfahrtsverbesserung nur vor erneuter Endprüfung. Mindestens eine QSim nach Übernahme der letzten Gruppe sicherstellen; diese kann unmittelbar in derselben Iteration folgen.
- [ ] Abbruch-/Ausnahmefall prüfen: keine spätere Übernahme, kein Fortschreiten zu QSim mit arbeitenden Solvern. Tatsächlich gefahrene Tour und Endexport entsprechen dem geprüften ausgewählten Plan.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=ReplanningCoordinatorTest,ReplanningLifecycleIntegrationTest,FreightRunComposerTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen; Lausitz KeepSelected und bedingte CarrierModule-Aktivierung in DRT-Fällen unverändert bestätigen.
- [ ] B5b bei unveränderter MATSim-Iterationszahl/Gruppierung durchführen; als `fix: coordinate one validated replanning pass` committen.

### Aufgabe 12: Geprüfte Ergebnisse atomar speichern und wiederaufnehmen

**Dateien:** neu `MAIN/core/routing/OptimizationResultStore.java`; ändern `MAIN/core/simulation/ReplanningCoordinator.java`, `MAIN/lausitz/freight/JspritPlanCache.java`, `JspritCacheKey.java`, `JarFingerprint.java` nur für tatsächlich gemeinsame Gültigkeitsbausteine; neuer `TEST/core/routing/OptimizationResultStoreTest.java`, vorhandene Cache-Tests.

**Interfaces:** `OptimizationResultStore.save(Carrier carrier, Result result, RoutingRequest request, ResolvedHagridConfig config, long seed)` persistiert nur validierte abgeschlossene Ergebnisse. `load(Carrier carrier, RoutingRequest request, ResolvedHagridConfig config, long seed)` liefert `Optional<Result>`; inkompatible Kennung ist Miss, beschädigtes Paket eine Diagnose. `Result` bezeichnet immer den Typ aus Aufgabe 10. Keine vollständigen MATSim-Checkpoint-Versprechen.

- [ ] `interruptedWriteIsNotACompletedResult`, `changedSnapshotIsCacheMiss`, `changedConfigurationIsCacheMiss`, `matchingEntryIsRevalidated` schreiben; `assertThat(loaded).isEmpty()` für abgebrochene Veröffentlichung und geänderten Kontext.
- [ ] Rot bestätigen, dann vollständiges Manifest, temporäre Ablage und atomare Veröffentlichung im selben Dateisystem implementieren. Keine fertige Kennzeichnung vor allen Bestandteilen; Netz-/Snapshot-Daten pro Kontext einmal speichern oder verlässlich referenzieren.
- [ ] Originalkontext muss reproduzierbar sein; ein gleicher Carriername oder hadFirstReplanning reicht nicht. Vor Rückgabe/Übernahme erneut prüfen. Alte Cache-Dateien nicht löschen, bisherigen JAR-Fingerprint und deaktivierten IDE-Cache erhalten.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=OptimizationResultStoreTest,JspritPlanCacheTest,JspritPlanCacheConcurrencyTest,JspritCacheKeyTest,JarFingerprintTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen.
- [ ] B5c mit Unterbrechung nach einem abgeschlossenen Carrier: passende fertige Ergebnisse wiederverwenden, übrige neu lösen, gleiche Endbewertung. Cache-Hits nicht als schnellere eigentliche Suche ausgeben.
- [ ] Als `feat: resume validated carrier optimization results` committen.

### Aufgabe 13: Optionalen Einzel-Carrier-Replay bereitstellen

**Dateien:** neu `MAIN/core/routing/CarrierReplay.java`, `TEST/core/routing/CarrierReplayTest.java`; `BENCH/README.txt` ergänzen.

**Interfaces:** `CarrierReplay.exportCase(Path directory, Carrier carrier, RoutingRequest request, ResolvedHagridConfig config, long seed)` schreibt Problem/Altplan, Einstellungen und gemeinsamen Kontextverweis. `CarrierReplay.replay(Path directory)` liefert `Result` über denselben Dienst aus Aufgabe 10; kein alternativer Solver. Ein `main(String[] args)` akzeptiert genau den Diagnoseverzeichnis-Pfad.

- [ ] `replayUsesIdenticalInputAndContext` und `missingContextFailsClearly` schreiben. Bei festen Budgets/Seed muss die normalisierte Endbewertung übereinstimmen; ein fehlender Kontext darf nicht auf freie Reisezeiten zurückfallen.
- [ ] Tests zuerst ausführen, dann Export/Import über geprüfte vorhandene Serialisierung ergänzen. Service-/Shipment-Paare, Fahrzeugdaten und Altplan vollständig erhalten; keine Netzduplikate pro Carrier.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=CarrierReplayTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen und einen exportierten kleinen Fall getrennt starten.
- [ ] Diagnoseaufruf mit tatsächlichem Paketnamen und Verzeichnisbeispiel in README dokumentieren; als `feat: replay isolated carrier optimization cases` committen.

## Phase 6 – Replanning-Zeit, RAM und Suchqualität verbessern

### Aufgabe 14: Ein begrenzter Ausführungsdienst für Carrier-Gruppen

**Dateien:** neu `MAIN/core/routing/CarrierBatchExecutor.java`; ändern `MAIN/core/simulation/ReplanningCoordinator.java`, `MAIN/hannover/demand/Router.java`, `MAIN/core/routing/JspritCarrierTask.java`, `GatedCarrierTask.java`, `JspritTreadPoolExecutor.java`, `CustomFutureTask.java`, `CarrierRoutingMetrics.java`; neu `TEST/core/routing/CarrierBatchExecutorTest.java`.

**Interfaces:** `CarrierBatchExecutor.run(List<CarrierBatchExecutor.Descriptor> group, Function<Descriptor,Result> solve, Consumer<Result> accept)` führt Arbeit im wiederverwendeten begrenzten Pool aus; `accept` läuft auf dem aufrufenden Koordinator-Thread vor Freigabe der Reservierung. `Descriptor` enthält nur Carrier-ID, Job-/Aktivitäts-/Profilzahlen, Seed und geschätzte reservierte Bytes. `close()` beendet Worker nach kooperativem Abbruch nachweisbar. Große VRP-Objekte entstehen erst in `solve` nach Zulassung.

- [ ] Tests für leere Gruppe, Shipment-lastige Größe, `maxActive`, RAM-Reservierung vor VRP-Aufbau, typisierte Aufgaben statt Runnable-Cast und workerfreie Zulassungswarteschlange schreiben. `assertThat(maxObservedActive).isLessThanOrEqualTo(configuredLimit)` und `assertThat(buildsBeforeAdmission).isZero()`.
- [ ] Rot nachweisen. Stabile Auswahl mit Schutz großer wartender Aufgaben implementieren; Budget umfasst MATSim, Snapshot, gemeinsame Graphen/Caches, Worker, Solver einschließlich Umwandlung/Prüfung und Reserve. Objektgrößen aus isolierten Referenzfällen kalibrieren; keine globale Heapdifferenz als exklusives Carriermaß.
- [ ] Einen zu großen Einzelcarrier vor Start diagnostizieren; kein OutOfMemoryError-Retry. Leere Gruppen erzeugen keinen Pool. Alle Ergebnisse zentral sammeln; historische Worker-Router nicht halten. Pool/Graph-Daten kontrolliert über Gruppen wiederverwenden.
- [ ] Abbruch während Suche und während Endprüfung testen: Reservierung erst nach tatsächlichem Aufgabenende frei; keine späte Übernahme und kein QSim neben mutierenden Workern. Kein Warten von Eltern auf Unteraufgaben im gleichen vollständig belegten Pool.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=CarrierBatchExecutorTest,ReplanningLifecycleIntegrationTest,RoutingContextLifetimeTest,StateIsolationTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen.
- [ ] B6a: äußere Parallelität 1/2/4, weitere nur bei erlaubtem RAM; intern zunächst 1. Gleiche Gruppenzuordnung, Seeds, Iterationen und Genauigkeit. Mindestens mehrere aufeinanderfolgende Gruppen für gehaltenen Heap, Router-/Cache-Anzahl und GC messen. Anschließend wenige große Carrier mit intern 2/4 separat prüfen, inklusive gleicher Qualitätsbewertung.
- [ ] Als `perf: bound carrier concurrency and reuse worker resources` committen. Bevorzugte Einstellung aus Evidenz ableiten; keine pauschale optimale Threadzahl festschreiben.

### Aufgabe 15: Gültige bisherige Touren als jsprit-2.0-Warmstart nutzen

**Dateien:** neu `MAIN/core/routing/WarmStartAdapter.java`, `TEST/core/routing/WarmStartAdapterTest.java`; ändern `MAIN/core/routing/CarrierOptimizationService.java`, `MAIN/core/config/ResolvedHagridConfig.java`.

**Interfaces:** `WarmStartAdapter.adapt(Carrier carrier, CarrierPlan previous, VehicleRoutingProblem problem, RoutingRequest request, ResolvedHagridConfig config)` liefert `WarmStartAdapter.Attempt(Optional<SolutionSpec> solution, String reason)`. Der Dienst übergibt eine gültige Lösung an `VehicleRoutingAlgorithm.addInitialSolution(SolutionSpec)`; keine gesperrten Initialrouten. Aktuelle Job-/Fahrzeugkennungen und Indizes werden vom aktuellen VRP materialisiert.

- [ ] Services sowie vollständige Shipment-Paare mit `ActivitySpec.visit/pickup/delivery` prüfen. `assertThat(attempt.solution()).isPresent()` für gültige Tour; fehlende Lieferung, falsche Reihenfolge, unbekanntes Fahrzeug oder verletzte neue Reisezeit ergeben leere Solution plus nachvollziehbaren Grund.
- [ ] Rot nachweisen; vollständige Übersetzung und unabhängige Prüfung implementieren. Nicht blind `MatsimJspritFactory.createRoute` verwenden, dessen geprüfter Adapter nur Services ergänzt. Keine Annahme, der alte MATSim-Score sei der neue Suchwert.
- [ ] Optimierbarkeit testen: geeigneter einfacher Fall darf gegenüber der Startlösung umordnen. Kaltstart bei ungültiger Startlösung; gültiger Altplan bleibt unabhängig davon Auswahlkandidat gemäß Aufgabe 10.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=WarmStartAdapterTest,CarrierOptimizationServiceTest,PlanValidatorTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen.
- [ ] B6b warm/kalt mit identischen Fällen, Seeds und Suchbudgets; Vorbereitung und Suche getrennt messen. Standard erst nach Vergleich festlegen; Erstplanung bleibt eine Iteration.
- [ ] Als `feat: seed jsprit with validated previous carrier plans` committen.

### Aufgabe 16: Optionale adaptive Budgets messbar erproben

**Dateien:** neu `MAIN/core/routing/SearchBudgetPolicy.java`, `TEST/core/routing/SearchBudgetPolicyTest.java`; ändern `CarrierOptimizationService.java`, `MAIN/core/config/ResolvedHagridConfig.java`, `HagridConfigResolver.java`.

**Interfaces:** `SearchBudgetPolicy.forProblem(int services, int shipments, int vehicleProfiles, ResolvedHagridConfig config)` liefert eine solverlokale Policy. `shouldStop(int completedIterations, double bestValidCost, int unassignedJobs)` berücksichtigt konfiguriertes Minimum, Maximum und Plateau. Standard ist die feste bestehende Budgetregel; adaptive Variante ausdrücklich experimentell und aus.

- [ ] `respectsMinimumAndMaximum`, `partialSolutionDoesNotCountAsQualityImprovement`, `initialPlanningStillUsesOneIteration` schreiben. Testkonfiguration min=10/max=30/Plateau=5: kein vorzeitiger Stop unter 10, Stop spätestens nach 30; Werte sind Fixture, keine neuen Produktionsstandards.
- [ ] Rot nachweisen und Policy implementieren; tatsächlich ausgeführte Iterationen/Abbruchgrund protokollieren. Services und Shipment-Aktivitäten berücksichtigen, kein Optimalitätsversprechen bei Plateau.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=SearchBudgetPolicyTest,CarrierOptimizationServiceTest' '-Dsurefire.failIfNoSpecifiedTests=false'` ausführen.
- [ ] B6c feste Referenz gegen konfigurierte adaptive Regeln; zunächst gleiche Eingaben und maximale Suchbudgets, zusätzlich gesonderter Vergleich bei gleichem Zeitbudget möglich. Geänderte tatsächliche Sucharbeit und Qualitätsverteilung ausdrücklich berichten. Keine automatische Aktivierung allein wegen kürzerer Laufzeit.
- [ ] Als `feat: add measured optional adaptive search budgets` committen.

## Phase 7 – Bereinigung und nachvollziehbare Gesamtwirkung

### Aufgabe 17: Kennzahlen, Scoring und Ausgabewege zusammenführen

**Dateien:** ändern `MAIN/core/routing/CarrierRoutingMetrics.java`, `CarrierRoutingStatusLogger.java`, `MAIN/core/simulation/ScoringFunctions.java`, `HAGRIDSimulationModule.java`, `SimulationRunnerUtils.java`, `MAIN/hannover/demand/CarrierRouter.java`; neu `TEST/core/simulation/RoutingOutputConsistencyTest.java`; vorhandene Histogramm-/Dashboard-Tests erhalten.

**Interfaces:** Bestehende Metriken kompatibel erweitern um Kontextkennung, Validierungsstatus, Vorbereitung/Suche/Endrouting/Prüfung, tatsächliche Iterationen, ausgewählte finale Kosten und getrennten MATSim-Score. Geteilte Cachewerte bleiben Kontextmetriken. Ausgabeintervalle stammen aus `ResolvedHagridConfig.results()`.

- [ ] `exportDescribesSelectedSimulatedPlan` schreiben: Jobstatus, Kontext, Touren und finale Kosten des Endexports stimmen mit dem ausgewählten gefahrenen Plan überein. Fehlende historische Laufzeit bleibt unbekannt. Histogramme für reguläre Population und Freight behalten ihre geprüfte Bedeutung.
- [ ] Rot bestätigen und Ausgaben auf die vollständigen Ergebnisse umstellen. Bereits berechnete Kennzahlen wiederverwenden; zusätzliche Eventanalyse nur bei gleicher Semantik zusammenführen. Gewünschte Endausgaben trotz reduzierter Zwischenintervalle erhalten.
- [ ] Routine-GC und feste Fünf-Sekunden-Pause in SimulationRunnerUtils entfernen; keinen Speichererfolg allein daraus ableiten. Frequenzänderungen als eigene Laufvariante führen.
- [ ] `mvn -pl hagrid/simulation -am test '-Dtest=RoutingOutputConsistencyTest,FreightEventHandlerTest,FreightEventHandlerMixedTrafficTest,SimulationRunnerUtilsLoggingTest' '-Dsurefire.failIfNoSpecifiedTests=false'` und B7a ausführen.
- [ ] Als `refactor: report validated routing outcomes consistently` committen.

### Aufgabe 18: Doppelte Altpfade entfernen und lokal abnehmen

**Dateien:** betroffene Aufrufer/Adapter aus Aufgaben 3–17, `TEST/core/ArchitectureRulesTest.java`, `BENCH/README.txt`; neuer Abschlussbericht `docs/superpowers/plans/2026-10-07-hagrid-jsprit-implementation-results.md` erst bei tatsächlich vorhandenen Ergebnissen.

**Interfaces:** Öffentlicher HAGRID-Kostenvertrag bleibt `createVRPTransportCosts(RoutingRequest)`; gemeinsame Dienste besitzen die beschriebenen Signaturen. Keine neue parallele Map-/Cast-API. Übergangsadapter nur entfernen, wenn alle wirklichen Aufrufer migriert sind; ansonsten explizit als Übergang dokumentieren.

- [ ] Nach verbliebenen `createVRPTransportCostsWithModeCongestedTravelTime`, ZoneBasedTransportCosts-Casts, statischen StateIds, doppeltem Solveraufbau und alten Aufgabenmechanismen suchen; Treffer klassifizieren. Nachgewiesen unbenutzte Altpfade und auskommentierte Kopien entfernen.
- [ ] Architekturtest gezielt auf echte unerwünschte Abhängigkeiten erweitern, insbesondere Replanner → konkrete ZoneBasedTransportCosts-Klasse; keine Tests erstellen, die lediglich sämtliche neuen Klassennamen aufzählen.
- [ ] `mvn -pl hagrid/simulation -am verify` ausführen und ausgeliefertes Paket prüfen. Ausstehende Tests/Fehler/Abweichungen einzeln berichten; alte Testergebnisse nicht als heutigen Nachweis verwenden.
- [ ] B7b: repräsentative lokale End-to-End-Fälle einschließlich zwei LSP, beider Modi, NCE, Services/Shipments, Fenster, Gruppen, Abbruch/Wiederaufnahme und Lausitz-Regimen. Abschlussvergleich B0/B1/B7 mit nachvollziehbar getrennten Versionseffekten, Korrekturen und Performanceänderungen.
- [ ] Mit vorhandenen Produktionsdaten repräsentativen Hannover-Lauf und Lausitz-Regime-/Cache-Prüfung ausführen. Vorgeschriebene vs. gefahrene Zeiten und Depot-/Engpasswirkung der Zusatzfahrzeuge ausweisen. Bei fehlenden Daten konkrete fehlende Inputs und verbleibende Aussagegrenze im Abschlussbericht festhalten; keine erfundenen Messwerte.
- [ ] Kurz dokumentieren: neue Konfiguration, einfacher Standardaufruf, Warmstart-/Replay-/Resume-Optionen und Grenzen. Als `refactor: retire migrated routing paths and document verified outcomes` committen.

**Abnahme:** Lokale Integration korrekt, Benchmarks reproduzierbar und keine unbegründeten Regressionen. Die Aussagen dürfen lauten „schneller bei gleicher geprüfter Qualität“, „weniger RAM bei gleicher Laufzeit“ oder ein ausgewiesener Kompromiss. Kein pauschales „besser“ bei weniger bedienten Aufträgen. Produktionsfreigabe nur mit tatsächlichem Produktionsnachweis; lokale Abnahme wird separat benannt.

## Phase 8 – Anschließend jsprit intern untersuchen

### Aufgabe 19: Engpässe profilieren und weitere Möglichkeiten priorisieren

**Dateien:** vorhandene `BENCH/AffectedJobLowerBoundProbe.java` und Profiling-/Replay-Werkzeuge verwenden; neuer Bericht `docs/superpowers/plans/2026-10-07-jsprit-follow-up-analysis.md` erst nach Phase 7. Noch keine Änderung am jsprit-Repository in diesem Integrationsplan.

**Interfaces:** Die Replay-Fälle und Manifeste aus Phase 5 bilden die reproduzierbare Eingabe. Ausführung bleibt auf dem freigegebenen HAGRID-Stand; ein später veränderter jsprit-Build bekommt eigene Kennung und einen eigenen Änderungsplan.

- [ ] Kleine, mittlere, große und Shipment-lastige isolierte Fälle profilieren; Vorbereitung/Jobnachbarschaften, Einfügeberechnungen, Distanzabfragen, Allokationen und Synchronisation getrennt messen.
- [ ] Quadratische Nachbarschaftsvorbereitung und interne Threads anhand tatsächlicher Profile priorisieren. Den vorhandenen Gegenbeispielfall zur AffectedJob-Untergrenze gegen den dann betrachteten konkreten jsprit-Stand prüfen; kein ungeprüfter Wechsel auf Snapshot-Versionen.
- [ ] Aktuelle offizielle jsprit-/MATSim-Quellen und passende GitHub-Beispiele mit Commit/Version ansehen; Erkenntnisse von bloßen Ideen trennen. Kleine Probes nur mit klarer Fragestellung und eigenem Versions-/Qualitätsvergleich.
- [ ] Übersicht erstellen: Befund, gemessener Anteil, Laufzeit-/RAM-/Qualitätswirkung, Parallelisierungspotenzial, Risiko, Aufwand und kleinster beweiskräftiger Versuch. Fehlender Produktionsnachweis aus Phase 7 bleibt sichtbar.
- [ ] Analyseartefakte gezielt prüfen und committen. Weitere jsprit-Implementierung benötigt einen daraus abgeleiteten eigenen Plan; diese Aufgabe behauptet keine bereits realisierten Verbesserungen.

## Selbstprüfung und Übergabe

| Spec-Anforderung | Zugeordnete Aufgaben |
|---|---|
| Versionen, IO und Paket-/Cache-Gültigkeit | 1–2, 12, 18 |
| Einmaliger gruppierter Ablauf, Snapshot, Endplanauswahl | 5, 10–12, 14 |
| Universeller Anbieter, null-Fahrzeug, finales Straßenrouting | 6–7, 9–10 |
| Zeitvertrag, FIFO, ALT und Näherungsfehler | 5–7 |
| Konfiguration, Tourregeln, Skills, Kosten und Zustandsisolation | 3–4, 10, 17 |
| Gemeinsames Netz, Lastenrad, Stopplinks, QSim-Semantik | 8–9 |
| Wiederaufnahme und reproduzierbarer Einzelfall | 12–13 |
| RAM, Nebenläufigkeit, Abbruch, Warmstart, adaptive Suche | 7, 14–16 |
| Hannover/Lausitz-Aufrufer, Ausgaben, End-to-End-Nachweise | 8, 10–11, 17–18 |
| Regelmäßige kleine Vergleiche gegenüber heute | 1 und B1 bis B7 einschließlich Zwischenprüfpunkten |
| jsprit intern erst anschließend | 19 |

Selbstprüfung bei Planerstellung: Zuständigkeiten und Abhängigkeiten abgeglichen; neue Typen sind ihren Aufgaben zugeordnet; fünf Review-Risiken besitzen konkrete Prüfungen. Veraltete Produktionsannahmen werden nicht als neue Evidenz übernommen. Keine Tests, Benchmarks oder Produktänderungen wurden durch das Schreiben dieses Plans ausgeführt.

Die schriftliche Planprüfung und Wahl der Ausführung sind erfolgt: Der Nutzer hat die schrittweise Implementierung hier in dieser Sitzung gewählt. Die Umsetzung folgt kleinen Aufgaben und regelmäßigen Vergleichsberichten; eine unabhängige abschließende Review gehört zur Abnahme. Die nachträglich präzisierte Valhalla-Priorität gilt bei allen weiteren Prüfungen.

## Verbindlicher Ressourcenschutz während Valhalla (Nutzerpräzisierung 2026-10-07)

Die physische Speicherreserve bleibt 6 GiB. Für den Start wird zusätzlich Platz für den eigenen kleinen Prozess berücksichtigt: 1536 MiB beim seriellen Maven-/Testlauf, 512 MiB beim Compiler beziehungsweise 128-MiB-Messprozess. Damit beträgt die Startschwelle 7,5 beziehungsweise 6,5 GiB; die Reserve selbst wird nicht erhöht oder abgesenkt. Ein gemeinsamer Prozessstarter unter tools/benchmarks/ResourceGuard.ps1 kontrolliert freien physischen RAM alle 200 ms, startet bei fehlendem Spielraum nicht und beendet bei weniger als 6 GiB ausschließlich den von ihm erzeugten eigenen Prozessbaum. Fehlgeschlagene Speicherabfragen beenden ebenfalls die eigene Arbeit. Eine gemeinsame Sperre verhindert parallele eigene geschützte Läufe. Niedrige Priorität, ein JVM-Prozessor und kleine Heaps bleiben verbindlich. Die Überwachung reagiert auf Messungen; sie ist keine Betriebssystemgarantie gegen kurzfristige fremde Speicherbelegung oder einen Absturz des fremden Valhalla-Laufs.

Der kleine Teststarter ist nun als tools/benchmarks/run-bounded-tests.ps1 dauerhaft vorhanden; die bisherige lokale Kopie wird ebenfalls geschützt. Testausgabe, Fehlerausgabe und beobachtete Speicherwerte werden getrennt protokolliert. Der Snapshot-/Transportkosten-Messstarter schützt sowohl javac als auch den eigentlichen Messprozess. Es werden keine fremden Prozesse verändert oder beendet.

Ein sparsam alle 30 Minuten laufender Thread-Monitor ist eingerichtet (Automation hagrid-speicherreserve-sch-tzen). Er misst nur lesend, erzeugt keine Routinganfragen und startet bei knappem Speicher keine Arbeit. Er meldet eine mögliche begrenzte Fortsetzung bei genügend Speicher; er ändert einen blockierten Zielstatus nicht eigenmächtig. Große Szenarien und Parallelitätsversuche bleiben zurückgestellt.

## Adaptive kleine Prüfungen (Nutzerpräzisierung: mehr RAM/CPU bei echtem Spielraum)

Diese Präzisierung ersetzt die zuvor festen Ein-Kern-/512-MiB-Obergrenzen für kleine Funktionstests während Valhalla. Die freie Reserve bleibt unverändert 6 GiB, Valhalla bleibt vorrangig, jeweils nur ein eigener geschützter Lauf. Vor dem Start bestimmen sechs Messintervalle à 500 ms den kleinsten verfügbaren RAM und CPU-Leerlauf; ein kurzer einzelner Leerlaufwert reicht nicht für eine höhere Stufe. Auto ist nun der Standard des kleinen Teststarters.

| Stufe | JVM-Prozessoren | Maven-/Test-Heap | Zusätzlicher eigener Startspielraum | Voraussetzung |
|---|---:|---:|---:|---|
| Klein | 1 | 512 / 384 MiB | 1536 MiB | 7,5 GiB RAM frei; mindestens 20 % CPU-Leerlauf |
| Mittel | 2 | 768 / 768 MiB | 2560 MiB | mindestens 10 GiB RAM frei; mindestens 40 % CPU-Leerlauf und CPU-Puffer erfüllt |
| Größer | 4 | 1024 / 1024 MiB | 3072 MiB | mindestens 16 GiB RAM frei; mindestens 60 % CPU-Leerlauf und CPU-Puffer erfüllt |

Für höhere Stufen wird zusätzlich rechnerisch 20 % der gesamten CPU-Kapazität für steigende Hintergrundlast freigehalten und höchstens die Hälfte der logischen Prozessoren zugeteilt. Die JVM-Prozessorzahl steuert deren Thread-/GC-Annahmen und ist keine harte Betriebssystem-CPU-Quote. Die Priorität bleibt Idle. RAM wird weiterhin alle 200 ms geprüft; bei weniger als 6 GiB oder einer fehlgeschlagenen Messung wird nur der eigene Prozessbaum beendet. Zusätzlich führt mehr als 85 % gesamte CPU-Auslastung über fünf Sekunden zur Rückstellung des eigenen Laufs. Kurze CPU-Spitzen sollen einen laufenden Test nicht unnötig abbrechen. Bei veränderter Last wird die nächste Ausführung niedriger zugelassen; ein bereits laufender JVM-Heap wird nicht nachträglich umgestellt.

Die neue Auswahl verändert weder Solverregeln noch Carrier-Parallelität oder jsprit-Suchbudgets. Laufzeitvergleiche verwenden weiter explizit feste Limits: run-bounded-tests.ps1 -ResourceMode Conservative beziehungsweise die bestehenden festen 128-MiB-/Ein-Kern-Probes. Ihre Konfiguration und tatsächlich ausgewählte Teststufe werden protokolliert. Der 30-Minuten-Monitor wurde auf diese CPU-/RAM-Auswahl angepasst und bleibt selbst lesend; keine eigenen Tests oder fremden Prozessänderungen durch den Monitor.

**Fortsetzung 2026-10-08 nach ausdrücklichem Nutzerauftrag:** Netz-/Reisezeit-Kohärenz ergänzt; ModalNetworkImporter als eigenständiger Baustein vorbereitet; Scenario-Builder registriert bereits gelesene Carrier/Fahrzeugtypen ohne erneuten XML-Lauf und überschreibt Modus/Iterationen/Messzeit nicht mehr pauschal. Doppelte Carrier-IDs werden auch beim nativen XML-Map-put geprüft. 22 weitere Testmethoden geschrieben, aber dieser Stand wurde noch nicht kompiliert oder ausgeführt. Aufgabe 7/8 bleiben offen; gemeinsames QSim-Netz und vollständige Eingangsprüfung noch ausstehend. Dauerhafte Prüfliste: docs/superpowers/plans/2026-10-08-hagrid-jsprit-open-checks.md. Frühere Messungen nicht als Nachweis dieses neuen Stands verwenden. Reine Ressourcen-Heartbeat-Automation pausiert; 6-GiB-Schutz für tatsächlich gestartete eigene Prüfungen bleibt verbindlich.

**Aktueller Stand 2026-10-08 (ersetzt die Prüfstatusangabe der obigen Fortsetzung):** 128 gezielte Tests bestanden (0 Fehler/Auslassungen), einschließlich Netz-/Ereignis-Kohärenz, Modal-Importer, Carrier-XML/Metadaten, Eingangsvalidierung und bedarfsgerechter Netzladung. Nullsicherer MATSim-Reader-Fallback nach tatsächlich nachgewiesener maskierter Duplicate-ID-Diagnose. Log k1-k5-lazy-networks-green-20261008.log; geschützter Lauf mit 4 JVM-Prozessoren und je 1024 MiB Heap, mindestens 19,04 GiB frei gemessen. Aufgabe 7/8 bleiben in Arbeit: Näherungsverdrahtung, gemeinsames QSim-Netz, echte Builder-/Eingangsdateiabnahme und vergleichbare Leistungsnachweise offen. Aufgaben 9–19 noch offen; kein vollständiger Integrationsabschluss.

**Fortsetzung Cache/Netz/Carrier 2026-10-08:** 174 gezielte Tests bestanden (0 Fehler/Auslassungen), Log k15-network-carrier-cache-verified-20261008.log. Beide Replanning-Pfade verwenden denselben passenden Modusanbieter; live Legacy-Caches werden unabhängig vom Zonenschalter erneuert, ein unnötiger globaler Router entfällt. Kompatible Teilnetze werden bei Bedarf in das tatsächliche Scenario-Netz übernommen, inklusive korrekter NCE-Linkbezüge; vorhandene Rad-Zugangslinks brauchen keine zweite Datei. Vollständiger Mini-Builder mit echten XML erhält Typidentität, ausgewählten Plan/Score, Iterationen und gemessene Laufzeit. Unvalidierte Carrier-Ausgabe entfällt; Startprüfung verlangt keine unbenutzte Rad-Datei; exakte Modi sind in Routing/QSim/Messkonfiguration eingetragen. Mindest-RAM 16,10 GiB gemessen, 2 JVM-Prozessoren, je 768 MiB Heap. Aufgabe 7/8 bleiben offen für Näherungen, gemeinsame Konfigurationsgrenzen/Legacy-Migration, vollständige Eingangskonsistenz, Produktionsdateien und gepaarte RAM-/Laufzeit-/Qualitätsvergleiche. Die echte QSim-Fahrzeit-/20-km/h-Abnahme ist Aufgabe 9. Keine vollständige Integrations- oder Speedup-Freigabe.

**Weitere Umsetzung und Klassenbereinigung 2026-10-08:** Die finale kleine Auswahl k25-refactor-completeness-verified-20261008.log hat 192/192 Fälle bestanden, ohne Fehler/Auslassungen/Ressourcenabbruch; 2 JVM-Prozessoren, je768 MiB Heap, kleinste freie RAM-Kapazität14,76GiB. CarrierVehicleReRouter472→60 und HAGRIDScenarioBuilder425→141 Zeilen; Modus-/Cache-Brücke, einzelne Kandidatensuche, Gruppensteuerung, Zonenkennzahlen, Simulationseinstellungen, Carrier-Dateien und räumliche Zuordnung haben eigene Zuständigkeiten. Erfolgsmarker/Retry/Iterationsfenster korrigiert; Worker übernehmen keine Scenario-Touren, Gruppenfehler publizieren keine Kandidaten; neueste Arbeitskopie bei wiederholter Carrier-Auswahl; echte MATSim-Zonenaktivitäten; Ein-Zonen-Indexfehler und unnötiges unbenutztes Legacy-Modusnetz behoben; unzugeordnete Aufträge werden vor Übernahme abgelehnt. Leere Batch-/Altpfade und erzwungenes System.gc entfernt. Aufgaben7/8 bleiben in Arbeit,10/11 haben zusätzliche Legacy-Vorarbeit, sind aber weiterhin ohne vollständige unabhängige Planprüfung, festen produktiven Kontext und abschließenden Koordinator. Aufgabe14 Ressourcen-Zulassung bleibt offen. Weiterhin keine gesamte Integrations-/QSim-/Speedup-/Qualitätsfreigabe; Details und ausstehende Prüfaufträge in der dauerhaften Prüfliste.

**Fortsetzung der drei beauftragten Arbeitspakete, 2026-10-08 (K49–K65b):** Supply-/Carrier-/Service-Merges auf tatsächliches Modalnetz umgestellt; finaler Hubbedarf genau einmal, WhiteLabel-Baselinepfad konfigurierbar und einmal nativ validiert, Nullmenge ohne Phantomjob, Flottenkapazität/Original-Startfenster/MIXED-Aufteilung erhalten, Service-Map-Vorschläge komplett vor Mutation geprüft. K65b 31/31 GREEN, darunter elf Merge-, sieben Supply- und fünf echte QSim-Fahrfälle: Default20/Fixture18, langsame Straße, NCE und Auto hinter Rad auf gemeinsamem FIFO-Link. Lokaler FIFO-Einfluss tatsächlich gemessen; globale 500-Fahrzeuge-Annahme bleibt offen.

**Aufgaben 10/11 konkret angeschlossen:** CarrierOptimizationService, CarrierSearchInput, PlanValidator und PlanSelection dienen dem Legacy-Replanning und dem aufgelösten Controllerpfad. Unabhängige kanonische Job-/Last-/Skill-/Zeit-/Fahrzeug-/Straßenprüfung; Kandidat und Altplan unter demselben Profil, keine Veröffentlichung während der Suche. ReplanningCoordinator/ValidatedCarrierReplanning führen einen stabilen Pass in höchstens16er-Gruppen mit einem einmal vor dem Reset erfassten Voriterations-Snapshot durch. Resolved KeepSelected verhindert anschließende ungeprüfte BestPlan-/TimeAllocation-Auswahl; rohe Legacy-Konstruktoren bleiben kompatibel. K63 36/36 GREEN: tatsächlicher fünf-Iterations-Controller mit 3 Carriern/2+1-Gruppen und echtem Reset/QSim; separat 17-Carrier-Koordinator/16+1/Messiteration24/Fehler ohne Teilpublikation. Leere Gruppen erfassen keinen Snapshot. Siebenstunden-Solverregel unverändert.

Aufgabe8 hat damit den Supply-/Merge-Modusanschluss, Aufgabe9 echte kleine QSim-Evidenz und Aufgaben10/11 den validierten Replanning-Anschluss. Keiner dieser Schritte ersetzt Produktionsdatei-/B4-/B5-Abnahmen oder die noch ausstehende gemeinsame Initialplanungs-/Lausitz-Migration. Nicht-FIFO und zeitlich variable gemischte Distanz-/Zeitziele bleiben explizite Produktionsdiagnosen; kein stilles Glätten. Vollständiger Shipment-Builder, atomare Ergebnisablage/Wiederaufnahme, Solver-Zulassung, Warmstarts und gepaarte Leistungs-/Qualitätsvergleiche bleiben offen. Keine Commit-/Push-/Merge-/Gesamtabschlussbehauptung.

**Gemeinsamer Schlusslauf K67:** 359/359 Tests, 0 Fehler/Auslassungen, alle 44 Rootklassen nachgewiesen (45 Berichte inkl. verschachtelter Suite), BUILD SUCCESS 58,285s. Auto2/Maven768/Test768, mindestens18,79GiB RAM frei, maxCPU63,77%, Exit0/noDeferred. Log k67-three-packages-final-20261008.log. Enthält Supply/Merges, fünf reale QSim-Fahrten, realen Replanning-Lebenszyklus, unabhängige Planprüfung, Zustandsisolation, jsprit-Kompatibilität und Lausitz-Freight-Komposition. Nur Dokumentation und eine nachlaufende Leerzeichenbereinigung der Controller-Testdatei danach; keine Verhaltensänderung. Die oben benannten Produktions-/Initialplanungs-/Zeitprofil-/Messgates bleiben offen; Gesamtplan nicht abgeschlossen.

## Fortsetzung 2026-10-08: Initialverbraucher und Replanning-Ergebnisablage

Aufgabe10: Hannover Router/JspritCarrierTask und Lausitz routeWithDurationCap teilen die getrennte initiale Suche, native ActivityCosts und unabhängige Prüfung. Alle fünf Ausführungsarten, initiales Budget, getrennte Metadaten, unlösbarer späterer Carrier ohne Gruppenpublikation und erneute Prüfung erlaubter Abfahrtsverschiebungen sind belegt. Native INFINITE-Fahrzeugsemantik und standardmäßige Runnable-/Future-Verträge korrigiert; vorhandene Initialpläne überspringen die Prüfung nicht mehr.

Aufgabe12: OptimizationResultStore und vollständige kanonische Eingangskennung sind implementiert; ReplanningCoordinator lädt passende abgeschlossene Suchen optional und prüft/selektiert/publiziert sie erneut. Ein atomar umbenanntes Archiv verbindet Plan, Manifest und Prüfsumme. Unterbrechung vor Veröffentlichung ergibt keinen Treffer; unterbrochener Ersatz erhält das letzte fertige Paket. Beschädigter oder trotz Prüfsumme unzulässiger Straßenplan wird diagnostiziert, nicht übernommen. Referenzierter Kontext muss vom Aufrufer reproduzierbar bereitgestellt werden; vollständiger Export/Einzelreplay bleibt Aufgabe13. Nativer Zeitverlust wurde tatsächlich reproduziert und nur für private Archive per Streaming korrigiert.

Der nachweislich aktuelle Aufträge/Fahrzeuge überschreibende v1-Initialcache wird ohne Dateilöschung ignoriert. Initialläufe berechnen bis zur zertifizierten Kontextmigration erneut. Gemeinsame JAR-Kennung und deaktivierter IDE-Cache bleiben erhalten. Rest von Aufgabe12: unabhängige Lausitz-Ganzdatei-Hit-Abnahme einschließlich der genehmigten Wellenfahrzeuge. Keine vollständige Aufgabe12-/Produktions-/B5-Abnahme behauptet.

K84: frisch498/498 Tests,0Fehler/0Auslassungen,55Rootklassen/56Berichte,BUILD SUCCESS43,315s. Auto2/Maven768/Test768,min16053239808B=14,95GiB,maxCPU57,08%,Exit0/noDeferred. Alle bisherigen44Rootklassen plus11Initial-/Store-/Lausitz-Cacheklassen enthalten; normale Lausitz-Golden-XML unverändert. Neue RED/GREEN-Nachweise K74(kanonischer Cache-Datenverlust),K79(fehlende native Plan-Carrier-Rückreferenz),K80(Zeitpräzisionsverlust),K82(ungeprüfter vorhandener Initialplan); neue API-Missing-REDs ausdrücklich als nur Kompilierung dokumentiert. Kein gepaarter Speedup-/RAM-/Qualitätsnachweis; Aufgaben13–19 und frühere Produktionsgates weiter offen.

## Fortsetzung K85–K89: breite kleine Abnahme und Kernabhängigkeiten

1.212/1.212 tatsächlich ausgeführte Tests aus146 unterschiedlichen Root-Testklassen bestanden; K88 1.196 Fälle,144Roots/183Klasseneinträge,4:11min,Auto2/768/768,min13,23GiB,maxCPU62,93%. K89 16 weitere Fälle/2Roots,14,827s,min14,15GiB,maxCPU30,45%,eigene zusätzliche JVMs auf384MiB/1AP begrenzt. Jeweils Exit0/noDeferred; nur LmdBaselineEndToEndTest mit echten Produktionsdaten zurückgestellt. Kleine DRT-/SharedUse-/Modular-/kombinierte tatsächliche Controllerläufe enthalten. GeoTools/JDBC-Freigabewarnungen bleiben separat zuzuordnen.

Aufgabe12: optionale Cache-Kennung/opaque Metadaten können gültige Neuplanung nicht mehr abbrechen; Miss mit Diagnose, gültige reguläre Suche unabhängig geprüft, Kosten==frische Baseline. Invalid-Request bleibt Fehler, fertige Archive unverändert. Aufgabe18: gleiche gemeinsame JAR-Kennung nach core.util.CodeFingerprint verschoben, Lausitz-Adapter20Zeilen, keine Kern-Studienabhängigkeit; bestehende Hashregeln/IDE-Off bleiben. Architekturregel streng, Switchboard-Eintrag folgt ausgelagerter FreightScenarioConfiguration. Neun veraltete Reflection-Testfälle verwenden das ausgelagerte Kostenmodell und behalten fachliche Assertions. Neue tatsächliche REDs K86 belegt; verschobene Testhook-/staleclass-Probleme gesondert dokumentiert.

Aufgaben12–19 sowie frühere Produktions-/Zeitprofil-/Shipment-/Mehrdatei-Gates bleiben offen. Vollständige aktuelle19-Schritt-Tabelle steht in docs/superpowers/plans/2026-10-08-hagrid-jsprit-open-checks.md. Kein allgemeiner Laufzeit-/RAM-/Qualitätsgewinn oder Gesamtabschluss aus diesem Korrektheitslauf. Keine Quelle nach K88 geändert, nur Dokumentation.

## Fortsetzung K90–K101: der vollständige Zonenrouter ist eigener Arbeitsblock

Nutzerpräzisierung: ZoneBasedTransportCosts vollständig verständlicher, effizienter und konfigurierbar refactoren; nicht nur den universellen Adapter prüfen. Aufgaben6/7/18 bleiben dafür in Arbeit. Priorität: diesen Block einschließlich neutraler Verbraucheranbindung abschließen, dann die übrige bisherige Reihenfolge fortsetzen.

Zonen-/Erreichbarkeitswahl, Cache-Generationen/Kennzahlen, Legacy-Worker-Ausleihe, Fahrzeuganpassung und variable-/Mautkosten haben eigene kleine Komponenten. Die Hauptklasse sank in diesem Block1077->526Zeilen; öffentliche Builder-/Counter-/Listener-/Wrapper-Verträge bleiben. Builder-Gruppen und tatsächliche Thread-Fallback-/Zeitbin-Regeln sind ausdrücklich dokumentiert. Kein stiller Ersatz der Live-Kosten durch den eingefrorenen Anbieter.

Der eingefrorene ModalTransportCosts-Anbieter ignorierte die zentrale Zonenkonfiguration bislang. Tatsächliches REDK92 belegt; jetzt für konstante Profile einmal pro Modus vorbereitet und im profilierten begrenzten Cache angewendet. Tatsächliche Straßenwege/finale Tourprüfung bleiben exakt. Unbewiesene variable Zonennäherung wird explizit abgewiesen; Produktionspolitik weiter offen.

K101 SchlusslaufGREEN542/542,63Roots/64Klasseneinträge,0fail/error/skip,1:03min,Auto2/768/768,min13,73GiB,maxCPU52,18%,exit0/noDeferred. Schutzreserve6GiB aktiv, kein eigener JVM-Lauf übrig. Ein statt zwei Berechnungen nur im kontrollierten Beispiel, kein allgemeiner Speedup-/RAM-/Qualitätsnachweis. Noch offen: konkrete Zone-Casts/Map-Verbraucher, gemeinsame Worker-Zulassung, vollständige Kennzahlen und mindestens fünf gepaarte Vergleichs-Seeds. Vollständige aktuelle Prüfliste: docs/superpowers/plans/2026-10-08-hagrid-jsprit-open-checks.md.
## Fortsetzung K102–K106: neutraler Kosten-Lebensdauervertrag (Aufgaben6/18)

ReplanningTransportCosts erweitert den nativen VRP-Vertrag um provider-eigene Gruppenvorbereitung, räumliche Näherungspolitik und Diagnose. CarrierRoutingCosts kennt keine konkrete Zone-Klasse mehr; Architekturprüfung sichert diese Grenze in Simulationsverbrauchern ab. Gemeinsame Anbieter werden einmal je Gruppe vorbereitet, nicht je Carrier. Live-Anbieter beginnen eine neue Cache-Generation; eingefrorene Anbieter behalten Profil/Werte und prüfen Kontext-Lebensdauer.

Tatsächlicher Fehler K102: ausgeschalteter Zonenanbieter fälschlich als Zonenrouting gemeldet. Neue Regel prüft tatsächliche Provider-Politik und bisherigen Verbraucher-Opt-in. Plain-native Anbieter bleiben kompatibel. Request-Factory dokumentiert; alte parameterlose/Map-Methoden veraltet ohne Entfernung. Ruling: historischen Live-Pfad ausdrücklich erhalten, nicht ohne Snapshot-Auftrag eingefrorene Werte einsetzen. Der aufgelöste feste Controllerpass verwendet bereits Request-Kontexte.

K10581/81 und finalK106550/550,64Roots/65Klasseneinträge,0fail/error/skip,1:04min,Auto2/768/768,min13223243776B=12,315GiB,maxCPU42,82%,exit0/noDeferred. Vorherige 63Root-Integration plus neuer neutraler Lifecycle-Test; keine Quelle nach finalem Lauf geändert. Keine großen Produktionsläufe oder allgemeine Laufzeit-/Qualitätsaussage.

Nächster Teil des vollständigen Zonenrouter-Blocks: gemeinsame RAM-Zulassung/Executorwege (Aufgabe14) sowie vollständige Kennzahlen/vergleichbare Näherungsprüfung. Danach übrige Lausitz-Trefferprüfung/Replay/Warmstarts fortführen. Produktive Zeitprofile, Shipment-/Mehrdatei-Publikation, gepaarte Seeds und Gesamtbranch-Abnahme bleiben offen.
## Teilstand Aufgabe14, 2026-10-08: gemeinsamer Executor (K107–K114)

CarrierBatchExecutor und feste Replanning-Verbindung implementiert und klein geprüft. Metadatenwarteschlange bleibt außerhalb des Pools; Budget vor solve, typisierte Ergebnisse und Caller-Annahme vor Freigabe. Pool wird über Gruppen wiederverwendet und bleibt bei leerer Gruppe unangelegt. Oversize/Duplikate scheitern vor Konstruktion. Cancellation, tatsächliches Worker-Ende und Reservation-Freigabe sind getrennt; bei Drain-Timeout bleibt laufender Speicher reserviert und erneute Arbeit gesperrt. Caller-Interrupt bleibt erhalten. Worker-eigenes Schließen wird vor jeder Lifecycle-Mutation verweigert.

Koordinator trennt Kontextaufnahme, stabile Reihenfolge, zugelassene Carrier-Vorbereitung und gemeinsame Veröffentlichung. Status und Cache-Treffer bleiben Caller-owned. Ein kleiner nativer Zwei-Worker-Vergleich hat dieselben endgültig geprüften Kosten wie der serielle Lauf; ungültige parallele Gruppe publiziert nichts. Abbruch meldet CANCELLED. Native Suchaufrufe werden nicht als sofort unterbrechbar behauptet; Reservierungen bleiben bis zum tatsächlichen Ende.

Ruling: Der Standard bleibt seriell, bis reale Byte-Reservierungen und laufbezogene Heap-/physische Budgets kalibriert sind. Explizit injizierte Budgets sind eine funktionsfähige Schnittstelle; synthetische Testbytes sind kein Carrier-RAM-Nachweis. Die zentrale maxActiveSolvers-Politik, alle alten Initial-/Legacy-Pools und B6a bleiben offen. Fertig gestagte Ergebnisse müssen ebenfalls im gemeinsamen Laufbudget berücksichtigt werden. Kein zusätzliches pauschales Produktionslimit aus der Valhalla-Prüfreserve ableiten.

K114:568/568,65Rootklassen/66Einträge,58.978s,Auto2/768/768,min13594906624B=12.66GiB,maxCPU41.88%,Exit0/noDeferred. Nur kleine vorhandene Integrationstests; kein allgemeiner Speedup, keine Suchqualitäts- oder Gesamtabschlussbehauptung. Nach diesem Lauf nur Test-Fixture-Lebensdauer und Dokumentation angepasst; gezielte Fixture-Nachprüfung separat.
Teilstand K116–K151: Alle Initial-/Legacy-/Lausitz-/feste Replanning-Ausführungswege benutzen gemeinsamen CarrierBatchExecutor; reale Byte-Kalibrierung und gemeinsames Ergebnis-/Laufbudget weiter Aufgabe14. Optionaler Warmstart (Service/Shipment, aktuelle freie SolutionSpec) und explicit adaptive Minimum/Maximum/Plateau umgesetzt, Default jeweilsfalse. Fünf gepaarte Mini-Fälle zeigen gleiche Endkosten, keinen Speedup; repräsentative B6b/B6c offen. Gemeinsame getrennte Phasen und Endbericht mit erneut geprüftem tatsächlich ausgewähltem Plan/MATSim-Score angeschlossen; Routine-GC/Pause entfernt. Aufgabe13 lokaler Replay inklusive echter eigener384MiB-JVM und exakter Snapshot/NCE-/Config-Wiederherstellung in K14842/42 geprüft, Paket-JAR-Prüfung separat offen. Aufgabe12 echte Baseline/Modular-Ganzdateitreffer unabhängig gegen aktuelle Jobs/Netz/zugelassene Flotte prüfen in K15166/66 einschließlich unveränderter GOLDEN-/Bytegleichheit; repräsentative Produktionsdaten weiterhin zurückgestellt. Kein Gesamtabschluss/Commit/Push/Merge.

## Aktuelle lokale Abnahme und verbleibende Gatter (K190–K207, 2026-10-09)

K202 ist die frische breite kleine verify/Paketprüfung:1351/1351Fälle,157Rootklassen,0Fehler/Auslassungen,5:07min. K203 prüft dieses Paket tatsächlich in separaten384MiB-/1CPU-JVMs für vollständige Services/Shipments. Unabhängiges Gesamtbranch-Review: zwei Important-Befunde durch tatsächliche Gegenproben reproduziert und repariert (interne Worker bei Suchfehlern, Originalsicherung bei unchecked Wiederherstellung); beide unabhängig bestätigt. Ergänzende Ressourcen-/Benchmarkreviews ohne Critical/Important, eine dokumentierte Stichprobendiversitätsgrenze als Minor.

Aufgabe14: Tatsächliche interne Solver-Threads und äußere Zulassungsgrenzen sind bis Initial-HAGRID, Legacy/festes Replanning und allen Lausitz-Verbrauchern angeschlossen; echte Baseline/Modular-Cachetreffer zertifizieren diese Einstellungen. Default äußerlich seriell/innerlich1. Gemeinsames MATSim-/Snapshot-/Graph-/Cache-/Plan-Basisbudget mit repräsentativen Carrier-Schätzungen und größere kontrollierte Parallelitätsvergleiche bleiben offen; keine Schätzung aus kumulativen Allokationen erfunden.

Aufgaben15/16: K206 fünfRootseeds/16Jobs/600aktuelle+600Referenzpläne, zertifizierte gleiche Starttouren/Eingaben. Warmstart spart im kleinen Vergleich Zeit, verschlechtert aber Services teils; adaptive Suche reduziert tatsächliche Arbeit und verliert punktuell Qualität. Beide Defaults bleiben aus. Mediane, einzelne Verschlechterungen, Messgrenzen und überlappende Carrier-Seeds sind im Ergebnisbericht ausgewiesen. K207 beschädigte Daten/Budgets/Zertifikate werden von der Auswertung abgelehnt.

Aufgabe18: Lokale Produktregression/Paketabnahme/Review sind erfüllt. Repräsentative Hannover-/Lausitz-, reale Hintergrund-/Depot-/Engpassabnahmen und isolierter B0/B1/B7-Versionseffekt bleiben offen. Aufgabe19 folgt der vollständigen Integrationsabnahme; noch keine jsprit-interne Änderung. Große Produktion/Parallelitätsversuche weiterhin wegen Valhalla zurückgestellt; kleine Läufe verwenden den lebenden eigenen ResourceGuard-Prozesshandle und6GiBReserve. Kein Commit/Push/Merge/Gesamtabschluss.
### Aufgabe 14: ergänzter Budgetvertrag K208–K214

Der gemeinsame Executor kann jetzt eine laufbezogene LongSupplier-Grenze aufnehmen. CarrierRunBudget trennt gemeinsamen Heap und physische/native Reserven, verwendet die kleinere positive Grenze und verweigert ungültige Messungen vor dem VRP-Aufbau. Optionaler JVM-Beobachter erfasst die gemeinsame Basis einschließlich veröffentlichter Pläne; keine exklusive Carrier-Messung und keine erzwungene GC. Der Gruppenbesitz wird vor der Budgetabfrage beansprucht; der unabhängige Reviewbefund zum veralteten Snapshot eines zweiten Aufrufers ist durch tatsächliches K212-RED und K214-GREEN abgedeckt. Fehlgeschlagene Abfragen erlauben einen späteren neuen Versuch. K214: 68/68 gezielte Prüfungen, tatsächliche gültige Touren bleiben über einen Budgetstopp der nächsten Gruppe erhalten.

Die Schnittstelle ist verfügbar, der Serienstandard bleibt unverändert. Aufgabe 14 bleibt offen für repräsentative Schätzwerte, Produktionsbudget-Anschluss mit echten Basisdaten und die zurückgestellten Parallelitätsvergleiche. K213 wurde wegen CPU-Druck geschützt abgebrochen, keine Fehlfunktion daraus abgeleitet. K215 besteht nach der Reparatur mit 1362/1362 Prüfungen, 158 kleinen Rootklassen und Paketbau (4:52min, 1CPU/Maven512/Test384MiB, min11,84GiB, maxCPU64,15%, kein Schutzabbruch). K216 prüft das aktuelle Paket zusätzlich mit getrennten Service-/Shipment-Replays erfolgreich; Endkosten100,25/94,0, vollständige Bedienung und identischer Kontext. Aktueller Paket-SHA461F5972F2970EE56C007778B7BD8B2ECAE7FD91E620BA9C6915E2B82348001C. Produktquellen zwischen beiden Läufen unverändert.

### Aufgaben 13/14: Messpfad für zertifizierte Captures K217–K223

Benchmark-only ReplayMemoryProbe und run-replay-memory-profile.ps1 nutzen die unveränderte produktive Replay-API und Paketkennung, begrenzen kleine Fälle vor JVM-Start, prüfen Eingaben/Paket vor und nach jedem Lauf und trennen Gesamt-Replay-Heap, zurückgegebenen Resultgraph, Allokation/GC und Laufzeit. Kein exklusiver Carrier-Peak, keine automatische Schätzung oder neue Defaultparallelität. Repräsentative Kalibrierung/Produktionsanschluss weiter offen.

K220 tatsächliches RED und K222 GREEN zum adaptiven effektiven Budget: Manifest2/Maximum21 vor jeder JVM abweisen; Manifest2/Maximum8 korrekt messen. Heap-Beobachter vor Graphmessung vollständig beendet/fester Peak. Vier frische Service-/Shipment-Messungen mit identischen Kosten100,25/94,0/2Iterationen und K223 neun Abweisungskontrollen bestanden. K221 wegen CPU-Druck zurückgestellt, spätere frische Prüfung erfolgreich. Keine Produktquelle/JAR verändert; K2151362/1362 ist weiterhin die letzte breite Produktabnahme. Read-only Werkzeugreview fand einen wichtigen adaptiven Grenzfehler und einen Sampler-Grenzbefund; beide Quellstellen repariert, Grenzen der Nachweise im Ergebnisbericht. Große Szenarien und Parallelitätsversuche bleiben wegen Valhalla zurückgestellt.
