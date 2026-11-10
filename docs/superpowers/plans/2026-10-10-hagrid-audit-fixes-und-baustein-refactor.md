# HAGRID Audit Fixes und Bausteinrefactor Implementation Plan

> **Verbindliche Nutzerkorrektur während der Umsetzung:** Ein gemeinsamer Masterseed `run.seed` (Default 1337) ersetzt die vorgeschlagenen drei `randomSeeds.*`-Wurzeln und separate produktive jsprit-/LMD-/Shared-Use-Seeds. Benannte, nach fachlicher ID abgeleitete Ströme bleiben unabhängig. `RandomSeedSettings(long seed)` ersetzt den Drei-Parameter-Vertrag. CLI `seed=` bleibt Alias von `run.seed`. Alte LMD-/Shared-Use-Seednamen sind Kompatibilitätsaliase; widersprechende Angaben werden abgewiesen. Historische Legacy-APIs ohne zentrale Konfiguration behalten ihre dokumentierten Defaults. Die nachstehenden ursprünglichen Seed-Beispiele sind durch diese Entscheidung überholt; Tests und Umsetzungsledger folgen der Ein-Seed-Regel. Diese Quellumstellung ist noch nicht Java-verifiziert.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans for the already selected native execution, or superpowers:subagent-driven-development only if the user later chooses that method. Implement task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Die zwölf Auditpunkte nachvollziehbar schließen und die betroffenen HAGRID-Bausteine so refactoren, dass Konfiguration, Daten, Routing, Prüfung, Simulation und Auswertung konsistent und gezielt optimierbar sind.

**Architecture:** Bestehende Konfiguration, RoutingContext, Optimierungsdienst und Transaktionen bleiben das Fundament. Fehler werden mit kleinen Gegenbeispielen gesichert und jeweils zusammen mit der passenden Aufteilung korrigiert. Wiederverwendung und Speicheroptimierung folgen erst auf die Korrektheitsfixes.

**Tech Stack:** Java 21, MATSim 2025.0, jsprit Core/Analysis 2.0.0, jsprit-IO 1.8, JUnit 5, Maven, PowerShell ResourceGuard; Python nur für kleine Benchmarkauswertungstests.

**Spec:** [Bausteine und fachliche Entscheidungen](../specs/2026-10-10-hagrid-audit-fixes-und-baustein-refactor-design.md). Vor Umsetzung beide Dokumente lesen.

**Status:** Planungsentwurf vom 10.10.2026. Keine Aufgabe dieses neuen Plans ist bereits implementiert oder getestet. Alte erfolgreiche Tests werden nicht als Nachweis neuer Fixes übernommen. Die native schrittweise Arbeitsweise ist bereits vereinbart; dieser Auftrag erstellt den Plan.

## Global Constraints

- Implementierungsbasis ist C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID; das normale Checkout enthält nicht automatisch den aktuellen Produktstand.
- Java 21, MATSim 2025.0, jsprit Core/Analysis 2.0.0 und jsprit-IO 1.8 bleiben unverändert.
- Die gemeinsame Tourdauer bleibt zentral konfigurierbar und beträgt standardmäßig 25200 Sekunden (7 Stunden); keine unabhängige historische 7,5-Stunden-Regel.
- Die Lastenrad-Höchstgeschwindigkeit bleibt zentral konfigurierbar und beträgt standardmäßig 20 km/h.
- Eine initiale jsprit-Iteration, ein fester Replanning-Pass und dieselbe eingefrorene vorherige Reisezeitgrundlage für alle Carrier bleiben erhalten.
- Exaktes Routing bleibt Standard; Zonenapproximation bleibt optional. Warmstart und adaptive Suche bleiben standardmäßig aus.
- Die freie physische RAM-Reserve beträgt mindestens 6 GiB. Vroom/Valhalla haben Vorrang; ihre Prozesse werden nicht verändert.
- Solange Hintergrundlast nicht ausdrücklich entfallen und neu gemessen ist: Conservative, 1 JVM-Prozessor, Maven maximal 512 MiB, Tests maximal 384 MiB; Start nur ab 7,5 GiB frei und mindestens 20 Prozent CPU-Leerlauf.
- Eigene Prüfungen laufen einzeln über ResourceGuard: RAM alle 200 ms, CPU jede Sekunde; eigener Lauf bei weniger als 6 GiB, Messfehler oder mehr als 85 Prozent Gesamt-CPU über 5 Sekunden abbrechen/zurückstellen.
- Die deaktivierte Überwachungsautomation bleibt deaktiviert. Eine verschobene Prüfung ist weder bestanden noch abgeschlossen.
- Keine neue Supply-Ankunftsabhängigkeit/kein Zwei-Echelon-Modell; keine Änderungen am jsprit-Repository, keine neuen Produktions-Parallelitätsdefaults.
- Produktänderungen, Fehlerkorrekturen und Performanceexperimente erhalten getrennte Nachweise; keine still angepassten Referenzergebnisse und keine unbelegten Beschleunigungsversprechen.

## Review Focus

Diese fünf leicht übersehenen Grenzen werden ausdrücklich zusätzlich geprüft:

1. Gleicher Zeitstempel und vertauschte Eventreihenfolge bei mehreren Fahrern: keine falsche Zuordnung, doppelte Buchung oder verdeckte unvollständige Fahrt — T06.
2. Mehrfach vorhandene Paket-/Servicebezüge beim Merge: Multimengen erhalten, echte ID-Kollisionen vor Mutation abweisen — T03.
3. Linkrestriktionen an beiden OD-Rändern und über mehrere Links: gültigen Umweg finden, nicht nur das Ergebnis nachträglich verwerfen — T08.
4. Cancellation, Zeithorizontfehler und fremdes Profil: Zonen-Rückfall darf diese Fehler nicht in scheinbar erfolgreiche Ergebnisse verwandeln — T09.
5. Mutation eines bereits geprüften nativen Plans und spätere Kontextänderung: kein veralteter Validierungsnachweis — T12.

## Phasenübersicht

| Phase | Pakete | Abnahme |
|---|---|---|
| 0 – Vergleichsbasis | T01 | Seeds von Tag getrennt, aktueller Ausgangsstand gesichert |
| 1 – Ablauf und Daten | T02–T04 | Fehler werden sichtbar, Mengen erhalten, Supply-Rundlauf funktioniert |
| 2 – Scoring | T05–T06 | Score und Kosten konsistent; Arbeitszeit je Tour |
| 3 – Routing | T07–T09 | Aktuelle Aufträge, Abbiegeverbote und sicherer Zonen-Rückfall |
| 4 – Modular | T10 | Zeitfenster erreichen den tatsächlichen Scheduler |
| 5 – Technischer Refactor | T11–T13 | Kleine Zuständigkeiten, weniger doppelte Arbeit und Netzspeicher |
| 6 – Nachweise | T14–T16 | Reisezeitvertrag, faire Messungen, echte Integration und Dokumentation |

Abhängigkeiten sind fachlich, nicht bloß numerisch. Innerhalb der bestehenden Ressourcengrenzen kann an einem unabhängigen Paket weitergearbeitet werden, wenn ein Testlauf eines anderen Pakets zurückgestellt ist; ein ungeprüftes Paket wird dadurch nicht als erledigt markiert. Keine parallelen eigenen JVM-Läufe.

## Arbeitsverzeichnis und Nachweise

Die Produktbasis ist `C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID`. Alle Produkt-/Testpfade unten sind relativ zu dieser Basis. Die Planungsdateien sind im normalen Checkout gespeichert; sie sind beim Weiterarbeiten inhaltlich als neue Planungsgrundlage zu übernehmen. Die alten Pläne und das Ledger werden nicht überschrieben.

Vor dem ersten Codeeingriff:
- [ ] Aktuelles Diff, vorhandene Änderungen und Quelldateien mit Auditmanifest vergleichen. Fremde Änderungen erhalten; keinen pauschalen Reset, Clean oder Rebase.
- [ ] Eigene Baseline-Quellen/JAR eindeutig mit Hashes erfassen; ein vorhandener JAR darf nicht ohne Quellenabgleich als aktueller Build gelten.
- [ ] Frische Ressourcenmessung vor jedem späteren JVM-Start. Die Messung des Audits ist keine dauerhafte Freigabe.
- [ ] Im bisherigen Ledger einen neuen Abschnitt für diesen Plan anlegen; Audit-IDs A01–A12 und Tasks T01–T16 abbilden.
- [ ] Für jede Aufgabe Status und Belege festhalten: `planned`, `reproduced`, `implemented`, `targeted-tested`, `integration-verified` oder `deferred`.

## Gemeinsamer Testaufruf

Nur bei der späteren Umsetzung ausführen. Dieser Plan hat keine dieser Prüfungen gestartet. Der vorhandene Wrapper wurde gelesen; seine tatsächlichen Parameter sind `Tests`, `Log`, `ResourceMode` und `MavenPhase`.

```powershell
$repo = 'C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID'
$verificationDir = Join-Path $env:TEMP ('hagrid-audit-fixes-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $verificationDir | Out-Null

function Check-Hagrid([string]$Name, [string]$Tests, [string]$Phase = 'test') {
    $log = Join-Path $verificationDir ($Name + '-' + [guid]::NewGuid().ToString('N') + '.log')
    & pwsh -NoProfile -File "$repo/tools/benchmarks/run-bounded-tests.ps1" -Tests $Tests -Log $log -ResourceMode Conservative -MavenPhase $Phase
    if ($LASTEXITCODE -ne 0) { throw "Prüfung fehlgeschlagen oder zurückgestellt; Nachweis: $log" }
}
```

RED bedeutet ausschließlich den erwarteten fachlichen Assertion-/API-Fehler. Ressourcenabbruch, falscher Classpath oder Compiler-Heapmangel sind kein Beweis des Bugs. GREEN erfordert passenden aktuellen Surefire-Nachweis, Exit 0 und keinen ResourceDeferred-Status. Bei Tests neuer Schnittstellen darf der erste RED der konkret erwartete fehlende Typ sein; danach muss der inhaltliche Gegenfall laufen.

Die 16 Pakete bilden einen zusammenhängenden Refactor, sind aber einzeln test- und reviewbar. Für die Orientierung genügt zunächst die Phasenübersicht; die anschließenden Aufgaben sind der technische Arbeitsanhang.

Die Assertion-Skizzen unten beziehen sich auf lokale Testvariablen aus dem jeweils beschriebenen Arrange; sie sind keine vorgetäuschten bestehenden Fixture-APIs. Fixture-Helfer bleiben im zugehörigen Testpaket und werden nur bei tatsächlich gemeinsamer Nutzung extrahiert.

Jedes Paket endet mit Diffprüfung und eigenem Fortschrittsnachweis. Eigene isolierte Commits sind sinnvoll; bestehende fremde oder bereits uncommittete Änderungen niemals per `git add .` mit aufnehmen. Wenn die Paketänderungen nicht sicher getrennt werden können, den Patchstand dokumentieren statt fremde Arbeit zu committen.

## Dateistruktur

Bestehende Verantwortlichkeiten werden erweitert, keine zweite Architektur daneben gestellt. Geplante neue Produktbausteine:

| Bereich | Neue Dateien | Zuständigkeit |
|---|---|---|
| Konfiguration | RandomSeedSettings, RunRandomStreams | Explizite Seeds und unabhängige Ströme |
| Pipeline | PipelineStepException | Fehler mit Pflichtstufe weiterreichen |
| Demand | CarrierMergeService, CarrierMetadata, SupplyBaseline | Ein Transfervertrag und mehrwertige Baseline |
| Scoring | CarrierCostBreakdown, CarrierCostAttributeWriter, CarrierExecutionLedger, CarrierCostCalculator, CarrierScoringFunction | Beobachten, bewerten, einmalig exportieren |
| Routing | CanonicalJobValidator, LinkRouteProvider, RoadLeg, CarrierRoadRouter, TurnRestrictionValidator, NoRouteException | Ein Job-/Straßenvertrag einschließlich Fehlerarten |
| Optimierung | ModalRoutingEngine, ValidatedCarrierCandidate, RoutingProfileCompatibility | Adapter trennen, bewiesene Arbeit weiterreichen, Profilgrenzen prüfen |
| Modular | ModularTourTiming | Stopbeginn/Warten konsistent berechnen |

Records mit ausschließlich bausteininternen Daten bleiben verschachtelt. Keine Klasse nur zum Umbenennen anlegen; notwendige Zuständigkeiten bestimmen die Aufteilung, keine künstliche Zeilenobergrenze.

### Task 1: Ausgangsstand und unabhängige Zufallsströme

**ID / Phase:** T01 / 0. **Bezug:** A11; Vergleichsgrundlage für alle späteren Messungen. **Abhängigkeiten:** keine.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/core/config/RandomSeedSettings.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/util/RunRandomStreams.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/config/ResolvedHagridConfig.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/config/HagridConfigResolver.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/config/HagridConfigurationCatalog.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/HagridConfig.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/DemandProcessor.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/DeliveryGenerator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/CarrierGenerator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/CarrierVehicleFactory.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/SupplyCarrierGenerator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/util/WeightGenerator.java`
- Ändern: `config/hagrid.properties`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/config/RandomSeedSettingsTest.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/hannover/demand/GenerationSeedIsolationTest.java`

**Interfaces:** `RandomSeedSettings(long seed)`; `ResolvedHagridConfig.randomSeeds(): RandomSeedSettings`; `RunRandomStreams(RandomSeedSettings)` und `forKey(String domain, String stableId): java.util.random.RandomGenerator`. Domänen: demand.cluster, demand.weight, demand.missed, dispatch.departure, supply.direction und supply.departure; Gemeinsame Seedwurzel run.seed (Default 1337), unabhängig von Domäne und Tag.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `sameSeedsDifferentTagPreserveGeneratedInputs`: gleiche Rohdaten und Seeds, Tags vorher/nachher; gleiche Jobdaten, Fehlzustellungs-Multimengen und Fahrzeugfenster. `streamsDoNotDependOnCreationOrder`: A/B und B/A erzeugen gleiche zugehörige Werte. `configurationPrecedenceAndValidation`: gemeinsamer Default 1337, CLI überschreibt RUN; ungültiger Long wird vor Generierung abgewiesen. Zwei gleichzeitig lebende Generatorinstanzen dürfen die Zufallsfolge des jeweils anderen nicht verändern.

```java
assertEquals(before.jobsAndDepartures(), after.jobsAndDepartures());
assertEquals(a1.nextLong(), a2.nextLong());
assertEquals(1337L, defaults.randomSeeds().seed());
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T01-red' 'RandomSeedSettingsTest,GenerationSeedIsolationTest,CentralConfigurationFileTest,HagridReferenceConfigurationTest,ParseScenarioSeedTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Vor Änderungen Quellmanifest und vorhandenen JAR mit Hash als Ausgangsstand sichern; vorhandene uncommittete Änderungen dokumentieren, nicht resetten/rebasen. Stabile Seedableitung versioniert aus UTF-8-kodierten, längenpräfixierten Root-Seed/Domäne/ID-Feldern per SHA-256; erste 64 Bit definierter Byte-Reihenfolge speisen `java.util.Random`. Domänentrennung und algorithmVersion werden exportiert. Produktive globale RNG-Setter durch injizierte Streams ersetzen; verbleibende Kompatibilitätsaufrufe ausdrücklich abgrenzen. MATSim, konfigurierte jsprit-Suchen sowie LMD/Shared Use aus demselben run.seed versorgen; keine global gemeinsam konsumierte RNG-Instanz.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T01-green' 'RandomSeedSettingsTest,GenerationSeedIsolationTest,CentralConfigurationFileTest,HagridReferenceConfigurationTest,ParseScenarioSeedTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Gleiche fachliche Eingaben/Seeds ergeben unabhängig vom Tag denselben fachlichen Fingerprint. Alte und neue Seedableitung sind im Vergleich unterscheidbar; kein Versprechen identischer historischer Zufallsziehungen. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 2: Fehlerweitergabe und eindeutiger Netzeigentümer

**ID / Phase:** T02 / 1. **Bezug:** A01, A12. **Abhängigkeiten:** T01.

**Files:**
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/pipeline/PipelineExecutor.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/pipeline/ScenarioRunner.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/hannover/pipeline/PipelineStepException.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/HagridModule.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/NetworkProcessor.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/LogisticsDataProcessor.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/DemandProcessor.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/DeliveryGenerator.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/hannover/pipeline/PipelineFailurePropagationTest.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/hannover/demand/NetworkInitializationTest.java`

**Interfaces:** `PipelineStepException(String stage, Throwable cause)` ist unchecked und bewahrt die Ursache. Neuer Pipelinekonstruktor `HagridModule(String configFilePath, HagridConfig effectiveConfig)`; bestehender Konstruktor bleibt ein delegierender Kompatibilitätseinstieg. `NetworkProcessor.processNetwork()` beendet nur bei vollständig veröffentlichten Netzergebnissen normal.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `routingFailureDoesNotPublishSuccess`: absichtlich werfender Router erreicht den Aufrufer, keine erfolgreiche Summary. Entsprechend fehlende Fahrzeugdatei in LogisticsDataProcessor. `sameConfiguredNetworkIsLoadedOnce`: XML und HAGRID verweisen auf dieselbe Datei, genau ein Import und vorhandene Subnetze. `conflictingExplicitNetworksFailBeforeOutput`: verschiedene explizite Quellen erzeugen klare Diagnose vor Teilveröffentlichung.

```java
assertThrows(PipelineStepException.class, pipeline::execute);
assertFalse(outputs.hasSuccessfulSummary());
assertEquals(1, networkReads.get());
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T02-red' 'PipelineFailurePropagationTest,NetworkInitializationTest,CentralPipelineConfigurationTest,CarrierGenerationPublicationTest,SupplyModalGenerationTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Konfiguration vor Injector/Szenarioerstellung fertig anwenden. Der Demand-Pfad erstellt ein leeres Scenario mit Config; NetworkProcessor besitzt seinen einmaligen Netzimport. MATSim-XML- und HAGRID-Pfade kanonisch vergleichen, nicht beide laden. Vollständiges ScenarioUtils.loadScenario für echte Simulationsstarts behalten. Pflichtstufen werfen nach Kontextanreicherung weiter. Resultate erst nach erfolgreicher Vorbereitung registrieren; vorhandene Transaktionen/OutputFileSet nutzen, kein eigener globaler Rollbackrahmen.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T02-green' 'PipelineFailurePropagationTest,NetworkInitializationTest,CentralPipelineConfigurationTest,CarrierGenerationPublicationTest,SupplyModalGenerationTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Ein Fehler ist im gesamten Aufrufpfad ein Fehler. Derselbe Netzcontainer wird nicht erneut ungeprüft befüllt; bestehende Ausgabe bleibt bei Fehlschlag klar als vorherige Ausgabe erkennbar. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 3: Ein gemeinsamer Carrier-Merge mit Erhaltung der Metadaten

**ID / Phase:** T03 / 1. **Bezug:** A02; B03. **Abhängigkeiten:** T02.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/hannover/demand/CarrierMergeService.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/hannover/demand/CarrierMetadata.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/CarrierGenerator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/CarrierGenerationTransaction.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/hannover/demand/CarrierMergeConservationTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/hannover/demand/ModalServiceMergeTest.java`

**Interfaces:** `CarrierMergeService.merge(Carrier target, Carrier source): void` überträgt nach vollständiger Vorprüfung. `CarrierMetadata.rebuild(Carrier carrier, List<Id<CarrierService>> missedParcels): void` berechnet abgeleitete Paket-/Fehlzustellungsattribute. Quelle wird erst nach erfolgreichem Transfer aus dem Carrierbestand entfernt.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `mergePreservesParcelsAndMissedMultiplicity`: A=200/20, B=10/10 ergibt 210/30 und 85,7142857 Prozent. Doppelte Service-ID ist Fehler vor Mutation. Mehrere Fehlzustellungen am selben Service bleiben mehrfach erhalten. Fehler während Transfer rollt beide Carrier samt Metadaten zurück. Jeder vorhandene Merge-Zweig benutzt denselben Transfer.

```java
assertEquals(210, targetParcelCount);
assertEquals(30, targetMissedEntries.size());
assertEquals(100.0 * 180 / 210, parsed.successRate(), 1e-9);
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T03-red' 'CarrierMergeConservationTest,ModalServiceMergeTest,CarrierGenerationPublicationTest,CarrierGenerationModeTest,CarrierServiceMergerSplitTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Auswahl-/Entfernungslogik im Generator zunächst unverändert lassen, Transfers und abgeleitete Daten in die beiden Bausteine ziehen. Provider-/Hubidentität nicht blind vereinigen: vorhandene fachliche Zielcarrier-Regel bleibt maßgeblich. Median je Carrier lokal cachen und nach Transfer invalidieren. Die übergebene maxServiceSize vor jedem Transfer gegen Ziel+Quelle prüfen; ein zu großes Ziel wird übersprungen, nicht durch stilles Aufteilen verändert. Den bestehenden Konfigurationsursprung weiterreichen, keine zusätzliche magische 600 einführen.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T03-green' 'CarrierMergeConservationTest,ModalServiceMergeTest,CarrierGenerationPublicationTest,CarrierGenerationModeTest,CarrierServiceMergerSplitTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Mengen, Fehlzustellungen, XML-Kennzahlen und Transaktionszustand bleiben konsistent; der Generator enthält keine kopierten fachlichen Transferzweige mehr. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 4: Supply-Baseline mit mehreren Richtungen erhalten

**ID / Phase:** T04 / 1. **Bezug:** A09. **Abhängigkeiten:** T01, T02, T03.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/hannover/demand/SupplyBaseline.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/SupplyBaselineLinks.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/hannover/demand/SupplyCarrierGenerator.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/hannover/demand/SupplyBaselineRoundTripTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/hannover/demand/SupplyModalGenerationTest.java`

**Interfaces:** `SupplyBaseline.read(Path file, CarrierVehicleTypes types, Network network, Map<Id<Hub>,Hub> hubs): SupplyBaseline`; verschachtelt `enum Flow { INBOUND, OUTBOUND }`, `record Lane(String hubId, String laneId, Flow flow, Id<Link> depotLink, long baselineUnits)`; `allocate(String hubId, Flow flow, long units): Map<Lane,Long>`. Der alte Single-Link-Helfer darf nur eindeutige Fälle projizieren.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `generatedDirectionalBaselineCanBeReused`: echte Nord-/Süd-Ausgabe einlesen und wieder erzeugen; Depots, Rollen und Gesamtmenge bleiben erhalten. `dhlInboundAndOutboundRemainDistinct`. Anteile 3:2 ergeben bei 7 Einheiten 4:3; Gleichstand nach laneId. Ungültiges Depot, fehlende Hubzuordnung oder positive Nachfrage bei Nullbasis scheitern vor Veröffentlichung.

```java
assertEquals(7L, allocations.values().stream().mapToLong(Long::longValue).sum());
assertEquals(4L, allocations.get(north));
assertEquals(3L, allocations.get(south));
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T04-red' 'SupplyBaselineRoundTripTest,SupplyModalGenerationTest,CarrierGenerationPublicationTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Explizite Rollen-/Kanalattribute im Produzenten ausgeben und beim Split erhalten. Bekannte Legacy-IDs/Serviceziele nur bei eindeutiger Zuordnung normalisieren; keinen beliebigen Depotlink wählen. White-Label-Erzeugung pro zugeordnetem Kanal ausführen und Baseline-Anteile durch größte Reste skalieren. Vorhandene Fahrzeug-/Modalitätsprüfung und Generationstransaktion weiterverwenden.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T04-green' 'SupplyBaselineRoundTripTest,SupplyModalGenerationTest,CarrierGenerationPublicationTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Produzent und Konsument besitzen denselben mehrwertigen Vertrag. Die neue Zuordnungsregel ist anhand kleiner Beispiele dokumentiert; keine neue Zwei-Echelon-Zeitkopplung. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 5: Scoring-Abschluss einmalig und ohne Abfrage-Nebenwirkungen

**ID / Phase:** T05 / 2. **Bezug:** A03; erster Teil B04. **Abhängigkeiten:** T02.

**Files:**
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/simulation/ScoringFunctions.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/simulation/scoring/CarrierCostBreakdown.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/simulation/scoring/CarrierCostAttributeWriter.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/simulation/CarrierScoringLifecycleTest.java`

**Interfaces:** `CarrierCostBreakdown` ist ein unveränderlicher Record mit fixed, distance, travelTime, activity, overtime, timeWindowPenalty, totalDistanceMeters, totalTravelTimeSeconds und uTurnCount; `totalCosts(): double`. `CarrierCostAttributeWriter.write(Carrier, CarrierCostBreakdown): void`. Öffentliche vorhandene ScoringFactory-Konstruktoren bleiben nutzbar.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `finishIncludesFixedCostsBeforeExport`: nichtleere Tour mit Fixkosten 100, übrige Kosten 0; finish→getScore ergibt -100 und Attribute 100/100. `repeatedGetScoreDoesNotChangeCosts`; wiederholtes finish darf ebenfalls nicht doppelt buchen. Leerer/fehlender Plan bleibt korrekt nullkostenfähig.

```java
assertEquals(-100.0, scoring.getScore());
assertEquals(100.0, carrier.getAttributes().getAttribute("costFix"));
assertEquals(firstSnapshot, snapshotAfterSecondGetScore);
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T05-red' 'CarrierScoringLifecycleTest,DriversActivityScoringTest,ConfigurationConsumerFlowTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Berechnung vor dem Writer abschließen, festes Ergebnis speichern, getScore nur lesen lassen. In dieser Aufgabe die bestehende Zählregel der Fixkosten zunächst erhalten; die Angleichung endliche/unendliche Flotte folgt ausdrücklich in T06. Feldnamen für bestehende Auswerter erhalten, Definitionsversion dokumentieren.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T05-green' 'CarrierScoringLifecycleTest,DriversActivityScoringTest,ConfigurationConsumerFlowTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Scoreabruf verändert keine Kosten mehr; Kostenexport und abgeschlossene Komponenten stimmen überein. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 6: Fahrer-/Tourereignisse erfassen und Scoring in Bausteine aufteilen

**ID / Phase:** T06 / 2. **Bezug:** A04, A05; finaler Teil A03/B04. **Abhängigkeiten:** T05.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/core/simulation/scoring/CarrierExecutionLedger.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/simulation/scoring/CarrierCostCalculator.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/simulation/scoring/CarrierScoringFunction.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/simulation/ScoringFunctions.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/simulation/HAGRIDSimulationModule.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/simulation/CarrierExecutionScoringTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/core/simulation/FreightIntegrationAcceptanceTest.java`

**Interfaces:** `CarrierExecutionLedger(Scenario, ResolvedHagridConfig)` implementiert `BasicEventHandler`: `handleEvent(Event)`, `reset(int)` und `complete(Id<Carrier>): CarrierExecution`. Verschachtelte immutable Records: `ExecutedTour(Id<Vehicle> vehicleId, Id<Tour> tourId, double startSeconds, double endSeconds)`, `ExecutedLeg(Id<Vehicle> vehicleId, Id<Tour> tourId, double departureSeconds, double arrivalSeconds, List<Id<Link>> enteredLinks)`, `ExecutedActivity(Id<Vehicle> vehicleId, Id<Tour> tourId, String jobId, double startSeconds, double endSeconds)`, `CarrierExecution(List<ExecutedTour> tours, List<ExecutedLeg> legs, List<ExecutedActivity> activities, boolean complete, List<String> violations)`. `CarrierCostCalculator.calculate(Carrier, CarrierExecution, ResolvedHagridConfig): Evaluation`, mit `Evaluation(double score, CarrierCostBreakdown costs)`. Alle Listen werden defensiv kopiert; unvollständige Beobachtungen dürfen den Kostenrechner nicht als vollständiger Lauf erreichen.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `separateShortToursDoNotCreateOvertime`: Touren 0–20 und 26000–26020, jeweils Lieferung in der Mitte, Überstunden=0. `returnLegAndLastServiceCountTowardShift`: Überschreitung allein durch Rückfahrt/letzten Service wird erkannt. `shortGenericRouteStillCostsTime`: Departure0, LinkEnter1, Arrival11 liefert 11 Sekunden. Native Events mit 0/1/2 LinkEnter, zwei interleavenden Fahrern, gleicher Eventzeit, Stuck/fehlendem Endevent, und Iterationsreset. Endliche Flotte: zweimal dasselbe Fahrzeug → eine Fixkostenbuchung; unendliche Tourinstanzen → je eigener Einsatz.

```java
assertEquals(0.0, evaluation.costs().overtime());
assertEquals(11.0, evaluation.costs().totalTravelTimeSeconds());
assertFalse(incompleteExecution.complete());
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T06-red' 'CarrierExecutionScoringTest,CarrierScoringLifecycleTest,DriversActivityScoringTest,ConfigurationConsumerFlowTest,FreightIntegrationAcceptanceTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Ledger einmal als normalen Eventhandler binden; nicht zusätzlich dieselben FreightEvents über den Scorer einspeisen. PersonDeparture/Arrival, VehicleEnters/LeavesTraffic, LinkEnter und native CarrierTour-/Service-/Shipment-Events nach Person/Vehicle/Tour zuordnen; mögliche Reihenfolge gleicher Zeitstempel puffern, nicht per ID-String raten. Vor Finalisierung alle Puffer prüfen. Factory wird dünner Adapter; die alte Leg-/Überstundenbuchung im produktiven Pfad entfernen, damit nichts doppelt zählt. Tatsächliche Schicht und Fixkosten nach der Spec bewerten; übrige Tarife und Trennung Score/Geld erhalten.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T06-green' 'CarrierExecutionScoringTest,CarrierScoringLifecycleTest,DriversActivityScoringTest,ConfigurationConsumerFlowTest,FreightIntegrationAcceptanceTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Mindestens eine kleine echte QSim belegt richtige Kosten und Schicht je Fahrer, einschließlich Kurzfahrt. Unvollständige Fahrten werden diagnostiziert, nicht als vollständige günstige Ergebnisse publiziert. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 7: Aktivitäten gegen aktuelle Aufträge prüfen

**ID / Phase:** T07 / 3. **Bezug:** A07. **Abhängigkeiten:** T02.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/CanonicalJobValidator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/PlanValidator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/PlanSelection.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/CarrierOptimizationService.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/routing/CanonicalJobValidationTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/core/routing/CarrierReplayTest.java`

**Interfaces:** Package-private `CanonicalJobValidator.validate(Carrier carrier, Tour.TourActivity activity): List<String>` vergleicht Referenzinhalt und liefert erklärende Abweichungen; kanonische Jobauflösung ist eine eindeutige Hilfsfunktion dieses Bausteins. `PlanValidator.Validation` bleibt der öffentliche Ergebnisvertrag.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `staleWindowRejectsOldPlan`: alter Service [600,900], aktueller [0,100], Ankunft20, gleiche ID/Position/Dauer; Altplan ungültig. Entsprechend Pickup-/Delivery-Fenster, Menge und Skills ändern. Gleichwertige getrennte Serviceobjekte bleiben gültig. Frischer Kandidat wird gewählt; bestehender Replay-Fingerprint lehnt Inkonsistenz weiterhin ab.

```java
assertFalse(oldValidation.valid());
assertTrue(oldValidation.violations().stream().anyMatch(v -> v.contains("time window")));
assertSame(candidate.plan(), selection.selected());
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T07-red' 'CanonicalJobValidationTest,PlanValidatorTest,CarrierOptimizationServiceTest,CarrierReplayTest,ReplanningCoordinatorTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Keine teilweise stille Reparatur alter Aktivitäten. Alle relevanten Auftragsdaten einschließlich Kapazität, Skills und Fenster prüfen; nach bestandener Konsistenz kanonische Daten für die Kostenbewertung verwenden. Gleichwertigkeit nach Werten statt Objektidentität.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T07-green' 'CanonicalJobValidationTest,PlanValidatorTest,CarrierOptimizationServiceTest,CarrierReplayTest,ReplanningCoordinatorTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Was validiert wird, ist dieselbe Auftragsversion, die MATSim ausführt; gleiche IDs allein reichen nicht. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 8: Linkbasierten Straßenvertrag einschließlich Abbiegeverboten einführen

**ID / Phase:** T08 / 3. **Bezug:** A06. **Abhängigkeiten:** T07.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/LinkRouteProvider.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/RoadLeg.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/NoRouteException.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/CarrierRoadRouter.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/TurnRestrictionValidator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/ModalTransportCosts.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/NativeModalTransportCosts.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/PlanValidator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/CarrierOptimizationService.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/simulation/CarrierPlanRerouter.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/lausitz/freight/LausitzFreightPreprocessor.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/routing/LinkRoutingContractTest.java`

**Interfaces:** `LinkRouteProvider.routeLeg(Id<Link> from, Id<Link> to, double departure, Vehicle vehicle): RoadLeg`; `RoadLeg(NetworkRoute route, double travelTimeSeconds, double distanceMeters, double monetaryCost)` kopiert native mutable Routendaten bei Übergabe. `CarrierRoadRouter.routePlan(CarrierPlan plan, VRPTransportCosts costs): void`. `TurnRestrictionValidator.validate(Network network, String mode, List<Id<Link>> sequence): List<String>`. `NoRouteException extends IllegalArgumentException` bezeichnet ausschließlich fehlenden zulässigen Straßenpfad; Konstruktor `NoRouteException(String message)`.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `forbiddenAdjacentLinksAreNotAZeroPath`: O:A→B, D:B→C, verboten O→D; ohne Alternative kein Pfad, mit Umweg korrekte Alternative. Verbot am letzten Übergang, interne und mehrgliedrige Verbote, car/bike-Unterschied, Start=Ziel, erlaubte U-Turns und unerreichbare Ziele. Kosten-OD, veröffentlichte Route und Validator werden mit unabhängig vorgegebenen zulässigen Linkfolgen verglichen. Kein doppelter Ziel-Link und kein erneut berechneter voller Startlink.

```java
assertFalse(validationOfForbiddenRoute.valid());
assertEquals(expectedLinks, routed.route().getLinkIds());
assertEquals(expectedTravelSeconds, routed.travelTimeSeconds(), 1e-9);
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T08-red' 'LinkRoutingContractTest,ModalTransportCostsTest,NativeModalTransportCostsTest,PlanValidatorTest,ReplanningModalNetworkTest,CarrierReplayTest,LmdValidatedOptimizationTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** MATSim-Speedy-Linküberladung zuerst am Vertrag prüfen, insbesondere bereits enthaltenen Ziellink und farbige Restriktionszustände. Engine-Adapter auf linkbasierten Einstieg umstellen und konsistente RoadLeg erzeugen; keine Einschränkungen nachträglich nur herausfiltern, wenn eine gültige Alternative existiert. HAGRID-Aufrufer von NetworkRouter auf CarrierRoadRouter migrieren. Capability-Adapter für native Provider benutzen; ein reiner Knotenprovider darf auf einem Netz mit nicht abbildbaren Restriktionen nicht still als korrekt gelten. Validator kontrolliert volle gespeicherte Sequenz unabhängig vom Suchalgorithmus.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T08-green' 'LinkRoutingContractTest,ModalTransportCostsTest,NativeModalTransportCostsTest,PlanValidatorTest,ReplanningModalNetworkTest,CarrierReplayTest,LmdValidatedOptimizationTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Suchkosten, native Straßenroute und unabhängige Prüfung beachten denselben linkbasierten Vertrag; bestehende reine Knotenschnittstelle verliert an produktiven Grenzen keine Restriktionen mehr. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 9: Zonen-Näherung mit sicherem exaktem Rückfall

**ID / Phase:** T09 / 3. **Bezug:** A08. **Abhängigkeiten:** T08.

**Files:**
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/ZoneRoutingApproximation.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/ModalTransportCosts.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/GenerationTransportCache.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/RoutingMetricsCsv.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/routing/ZoneFallbackTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/core/routing/ZoneRoutingApproximationTest.java`

**Interfaces:** `ZoneRoutingApproximation.select(Link from, Link to)` bleibt reine Auswahl. Engine besitzt unterscheidbare EXACT-/REPRESENTATIVE-Cacheidentität einschließlich Modus/Profil/Zeit/Kontext; `NoRouteException` aus T08 bezeichnet ausschließlich geprüfte Nichterreichbarkeit. Diagnosezähler: representativeQueries, representativeNoPathFallbacks und exactQueries.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `unreachableRepresentativeFallsBackToReachableOriginal`: auditierter Quadratfall mit b→c-Verbot und aktivierender Schwelle. Zweite Original-OD-Anfrage mit gleichem Vertreter darf nicht den falschen Rückfallwert bekommen. Wiederholung derselben Originalanfrage nutzt den exakten Cache. Beide unlösbar → NoRouteException. Cancellation/Zeithorizont/Modusfehler lösen keinen Rückfall aus.

```java
assertEquals(exactOriginalCost, approximatedWithFallbackCost, 1e-9);
assertEquals(1L, statistics.representativeNoPathFallbacks());
assertThrows(CancellationException.class, cancelledQuery);
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T09-red' 'ZoneFallbackTest,ZoneRoutingApproximationTest,ModalTransportCostsTest,GenerationTransportCacheTest,CarrierReplayTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Nur NoRoute des Repräsentanten abfangen; echte Originalendpunkte über T08 berechnen und separat speichern. Zonenwahl von Cache-Keybildung und tatsächlichem Routing lesbar trennen. Keine neue Standarddistanz und keine automatische Zonenaktivierung.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T09-green' 'ZoneFallbackTest,ZoneRoutingApproximationTest,ModalTransportCostsTest,GenerationTransportCacheTest,CarrierReplayTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Eine erreichbare Originalverbindung scheitert nicht allein am Vertreter; Cachetreffer, Rückfall und echte Fehler sind unterscheidbar. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 10: Modulare Stopfenster bis in die tatsächlichen Tasks erhalten

**ID / Phase:** T10 / 4. **Bezug:** A10. **Abhängigkeiten:** T07, T08.

**Files:**
- Ändern: `hagrid/simulation/src/main/java/hagrid/lausitz/modular/ModularFreightTour.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/lausitz/modular/ModularTourConverter.java`
- Neu: `hagrid/simulation/src/main/java/hagrid/lausitz/modular/ModularTourTiming.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/lausitz/modular/ModularTourScheduler.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/lausitz/modular/ModularTourDispatcher.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/lausitz/modular/ModularStayTaskEndTimeCalculator.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/lausitz/modular/ModularTimeWindowIntegrationTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/lausitz/modular/ModularTourConverterTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/lausitz/modular/ModularTourSchedulerTest.java`

**Interfaces:** `ModularFreightTour.Stop(Id<Link> link, double serviceDuration, int parcels, double earliestStart, double latestStart)` mit validiertem Fenster. Alter 3-Argument-Konstruktor delegiert als dokumentierte Kompatibilität auf [0, +Infinity]; frühester Start muss endlich/nichtnegativ sein, spätester Start darf +Infinity, niemals NaN oder kleiner als frühester Start sein; produktiver Converter setzt immer echte Fenster. `ModularTourTiming.service(double arrival, double duration, double earliest, double latest): Optional<ServiceTiming>` mit immutable `ServiceTiming(waitSeconds,startSeconds,endSeconds)`.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `earlyArrivalWaitsForDeliveryStart`: Ankunft07:24, Fenster07:30–08:00 → sechs Minuten Warten, Service07:30. `lateArrivalLeavesScheduleUntouched`: Ankunft08:01 wird abgelehnt. Genau08:00 darf beginnen und danach weiterdauern, sofern äußere Grenzen passen. Mehrere wartende Stops, Endfenster vor Fahrzeugende, nachträgliche tatsächliche Fahrtverzögerung sowie Rollback prüfen. Konfigurationswert bis erzeugten QSim/DRT-Tasks verfolgen.

```java
assertEquals(360.0, timing.waitSeconds());
assertEquals(27000.0, timing.startSeconds());
assertEquals(scheduleBefore, scheduleAfterRejectedAttempt);
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T10-red' 'ModularTimeWindowIntegrationTest,ModularTourConverterTest,ModularTourSchedulerTest,ModularTourDispatcherTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Zeitfenster im Converter übernehmen und Stop-/Record-Konstruktoren validieren. Vorbereitung der gesamten Taskkette berücksichtigt Warten, Rückfahrt, beide Umrüstungen, Schicht und Dienstende. Erst danach Plan ändern. Geeigneten bestehenden Stay-/Wait-Task verwenden und Freight-Status/Update-Logik während des Wartens erhalten. Bei realer Verspätung KPI-Verletzung sichtbar machen und verbleibende Kette neu prüfen; keine rückwirkend als rechtzeitig bewertete Bedienung.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T10-green' 'ModularTimeWindowIntegrationTest,ModularTourConverterTest,ModularTourSchedulerTest,ModularTourDispatcherTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Offline-Auftrag und tatsächlich geplante/ausgeführte modulare Bedienung verwenden dieselben Fenster; Lieferbeginn wird nicht mit Tour-Aktivierungszeit verwechselt. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 11: Routingadapter, Engine und Konfiguration übersichtlich trennen

**ID / Phase:** T11 / 5. **Bezug:** Bausteinrefactor; universeller Provider. **Abhängigkeiten:** T01, T08, T09.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/ModalRoutingEngine.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/ModalTransportCosts.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/RoutingContext.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/VRPTransportCostsFactory.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/ZoneBasedTransportCostsFactory.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/ReplanningTransportCosts.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/simulation/HAGRIDSimulationModule.java`
- Ändern: `hagrid/simulation/src/test/java/hagrid/core/ArchitectureRulesTest.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/routing/TransportProviderContractTest.java`

**Interfaces:** Öffentlich bleibt `VRPTransportCostsFactory.createVRPTransportCosts(RoutingRequest): VRPTransportCosts`; `ReplanningTransportCosts` bleibt Lebensdauer/Diagnose, `LinkRouteProvider` der T08-Straßenvertrag. Package-private `ModalRoutingEngine` übernimmt die bisher verschachtelte Engine mit kontextgebundenen Caches/Modalgraphen und `close(): void`; keine globale Engine.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `oneFactoryHandlesCarAndBikeWithCorrectTargetLinkTime`, `exactProviderNeedsNoZoneClass`, `vehiclelessPreparationHasExplicitMode`, `closingContextWaitsForActiveLease`. Bestehender Architekturtest verbietet Produkt-Casts auf ZoneBasedTransportCosts außerhalb dokumentierter Legacy-Adapter. Neue kanonische Seeds durch File/CLI bis Verbraucher; keine zweite Konfigurationsquelle.

```java
assertEquals(expectedBikeTimeAt20Kmh, bikeCost, 1e-9);
assertFalse(exactProvider.usesSpatialApproximation());
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T11-red' 'TransportProviderContractTest,ModalTransportCostsTest,RoutingContextTest,RoutingContextLifetimeTest,ReplanningLifecycleIntegrationTest,ArchitectureRulesTest,HagridReferenceConfigurationTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** ModalTransportCosts wird auf jsprit-/MATSim-Adaptierung reduziert; Graph-/Worker-/Cache-Verwaltung erhält eigenen Baustein. Bestehende GenerationTransportCache/ZoneRoutingApproximation wiederverwenden. Provider-Namen dürfen nicht implizieren, dass exakte Kosten zwingend Zonen benötigen. Deprecated APIs nur delegieren lassen; erst nach geprüftem Caller-Inventar entfernen. Konfigurationsgruppen samt Einheiten im bestehenden config/hagrid.properties pflegen, nicht alle Einstellungen in eine neue riesige Klasse ziehen.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T11-green' 'TransportProviderContractTest,ModalTransportCostsTest,RoutingContextTest,RoutingContextLifetimeTest,ReplanningLifecycleIntegrationTest,ArchitectureRulesTest,HagridReferenceConfigurationTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Eine universelle Factory, eindeutig begrenzte Verantwortlichkeiten und keine neue parallele Kosten-/Konfigurationshierarchie; Verhalten bleibt gegenüber dem korrigierten Stand gleich. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 12: Bereits geroutete und geprüfte Kandidaten sicher weiterreichen

**ID / Phase:** T12 / 5. **Bezug:** B01. **Abhängigkeiten:** T07, T08, T09, T11.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/ValidatedCarrierCandidate.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/CarrierOptimizationService.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/PlanSelection.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/CarrierPlanFingerprint.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/simulation/ReplanningCoordinator.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/OptimizationResultStore.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/routing/ValidatedCandidateReuseTest.java`

**Interfaces:** `ValidatedCarrierCandidate` kapselt eine unabhängige Planvorlage; package-private `capture(CarrierPlan plan, Carrier canonical, RoutingRequest request, ResolvedHagridConfig config, double routeLimitSeconds, double uTurnPenalty): ValidatedCarrierCandidate` prüft vor Erzeugung. `matches(Carrier canonical, RoutingRequest request, ResolvedHagridConfig config, double routeLimitSeconds, double uTurnPenalty): boolean`, `validation(): PlanValidator.Validation`, `materialize(Carrier canonical): CarrierPlan`. Cache-/Nachweisidentität bindet Eingabe-, Plan-, Kontext- und tatsächlichen Bewertungsregelfingerprint einschließlich expliziter Grenzen. Kein öffentliches mutable Planfeld.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `unchangedCandidateRoutesAndValidatesOnce` zählt teure Aufrufe statt Millisekunden. Verändertes Jobfenster, Netz/NCE, Kostenprofil oder Route macht den Nachweis unbrauchbar. Mutation einer ausgegebenen Plan-Kopie verändert weder Vorlage noch spätere Kopie. Alter Gewinner wird unter neuer Messung frisch bewertet; Gleichstand behält gültigen alten Plan.

```java
assertEquals(1, roadRoutingCalls.get());
assertEquals(1, fullValidationCalls.get());
assertFalse(candidate.matches(changedCarrier, request, config, routeLimit, uTurnPenalty));
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T12-red' 'ValidatedCandidateReuseTest,CarrierOptimizationServiceTest,ReplanningCoordinatorTest,OptimizationResultStoreTest,CarrierReplayTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Search erzeugt einmal geprüften Kandidaten; Auswahl und Veröffentlichung übernehmen ihn, solange Eingabe und Kontext unverändert sind. Native mutable Pläne niemals als öffentliches Zertifikatsfeld exponieren. Finale Bindungs-/Publikationsprüfung bleibt; Reduktion betrifft identische vollständige Straßenarbeit innerhalb desselben Kontexts, nicht Schutz vor Änderungen. Resultat-Store und Replay behalten unabhängige Nachweisprüfung beim Wiederladen.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T12-green' 'ValidatedCandidateReuseTest,CarrierOptimizationServiceTest,ReplanningCoordinatorTest,OptimizationResultStoreTest,CarrierReplayTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Weniger teure Aufrufe bei identischen korrekten Ergebnissen, ausdrücklich ohne stale-plan-Abkürzung. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 13: Netzvorbereitung und Speicher ohne neue Lebensdauerfehler reduzieren

**ID / Phase:** T13 / 5. **Bezug:** B02. **Abhängigkeiten:** T11, T12.

**Files:**
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/ModalRoutingEngine.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/RoutingNetworkSnapshot.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/RoutingContext.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/RoutingNetworkIdentity.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/routing/ModalNetworkPreparationTest.java`
- Ändern: `tools/benchmarks/CarrierMemoryProbe.java`

**Interfaces:** Engine hält pro tatsächlich angefragtem Modus genau eine vorbereitete immutable Modusdarstellung. Neue Snapshot-Erzeugung ohne ungenutzte modale Fingerprintberechnung bleibt package-private; der öffentliche vollständige Kontextfingerprint bleibt unverändert. Lebensdauer weiterhin ausschließlich über RoutingContext.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** `unusedBikeModeDoesNotAllocateGraph`: car-only-Abfrage bereitet einen Graphen. Gleichzeitige erste Abfragen desselben Modus erstellen genau einen Graphen. NCE/Attribute/Restriktionen/Adjazenz bleiben in Modussicht korrekt und unveränderlich. Geschlossener Kontext gibt keine Ressourcen mehr aus; fehlgeschlagene Erstvorbereitung hinterlässt keinen halben Eintrag.

```java
assertEquals(1, statistics.preparedGraphs());
assertEquals(beforeContextFingerprint, afterContextFingerprint);
assertThrows(IllegalStateException.class, queryAfterClose);
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T13-red' 'ModalNetworkPreparationTest,RoutingContextTest,RoutingContextLifetimeTest,LinkRoutingContractTest,ModalTransportCostsTest,CarrierReplayTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Zunächst lazy Graphvorbereitung und ungenutzte modale Hashes entfernen. Danach nur nach kleiner Speicherprobe unnötige Kopien reduzieren: immutable Modussicht auf den bereits eingefrorenen Kontext mit eigener gefilterter Adjazenz, keine Referenz auf das mutable Quellnetz. Native Speedy-Arrays bleiben nach ihren realen ID-Indizes dimensioniert; kein ungesicherter Renumbering-Trick. Kontext- und Modusspeicher getrennt messen.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T13-green' 'ModalNetworkPreparationTest,RoutingContextTest,RoutingContextLifetimeTest,LinkRoutingContractTest,ModalTransportCostsTest,CarrierReplayTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Unveränderte Routingergebnisse; weniger nachgewiesene Vorbereitung/Objekte. RAM-Nutzen wird erst nach Messung quantifiziert. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 14: Reisezeitvertrag bis zu realen Hintergrundprofilen prüfen

**ID / Phase:** T14 / 6. **Bezug:** Bekannte FIFO-/Mischkosten-Grenze; ursprünglicher MATSim-Ansatz. **Abhängigkeiten:** T08, T11.

**Files:**
- Neu: `hagrid/simulation/src/main/java/hagrid/core/routing/RoutingProfileCompatibility.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/TravelTimeSnapshot.java`
- Ändern: `hagrid/simulation/src/main/java/hagrid/core/routing/ModalRoutingEngine.java`
- Test neu: `hagrid/simulation/src/test/java/hagrid/core/routing/RoutingProfileCompatibilityTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/core/routing/TemporalRoutingContractTest.java`

**Interfaces:** `RoutingProfileCompatibility.check(RoutingContext context): List<Violation>` mit immutable `Violation(String mode, String linkId, double boundarySeconds, String rule)`; vollständige Prüfung erfolgt vor teurer Engine-Vorbereitung. Sie diagnostiziert vorhandene Regeln und schwächt sie nicht ab.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** Konstante Linkmittelwerte bestehen; stückweise 60→30 Sekunden werden mit Link/Zeitgrenze abgewiesen. Unterstütztes FIFO-Profil mit reinem Zeitziel besteht; variables Profil plus Meterkosten/Zonen wird wie bisher abgewiesen. NCE-bedingte Tempoerholung wird geprüft. Zweite Gruppe verwendet exakt denselben Snapshot wie die erste; keine zusätzliche Rückkopplung der 500 Fahrzeuge eingeführt.

```java
assertTrue(checkConstantProfile.isEmpty());
assertEquals("car", decreasingProfileViolations.getFirst().mode());
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T14-red' 'RoutingProfileCompatibilityTest,TemporalRoutingContractTest,TravelTimeSnapshotTest,ReplanningLifecycleIntegrationTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Kompatibilität an einer Stelle verständlich beschreiben und vor Kostenprovider-Aufbau prüfen. Eine kleine anonymisierte/ausgewählte echte Profilprobe einlesen, wenn verfügbar; bei fehlenden Daten als offener Input festhalten. Unterstützte Produktionskombination exakt dokumentieren. Nicht unterstützte Zielprofile bekommen einen konkreten Folgefall mit nötiger Modell-/Algorithmusentscheidung; keine stille Free-Speed-Ersatzrechnung oder automatische Glättung.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T14-green' 'RoutingProfileCompatibilityTest,TemporalRoutingContractTest,TravelTimeSnapshotTest,ReplanningLifecycleIntegrationTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Für vorhandene kleine/reale Proben ist klar, welcher Reisezeitvertrag unterstützt ist. Die allgemeine Produktionsfreigabe bleibt bei inkompatiblen oder fehlenden Zielprofilen ausdrücklich offen. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 15: Größere Zonenvergleiche und Refactor-Wirkung messen

**ID / Phase:** T15 / 6. **Bezug:** B07; noch ausstehender Nutzervergleich 1500/3000/5000 Meter. **Abhängigkeiten:** T01–T14; die sachlich betroffenen Tests müssen bestanden sein.

**Files:**
- Neu: `tools/benchmarks/ZoneThresholdProbe.java`
- Neu: `tools/benchmarks/run-zone-threshold-comparison.ps1`
- Neu: `tools/benchmarks/summarize-zone-threshold-comparison.py`
- Ändern: `tools/benchmarks/README.txt`
- Test neu: `tools/benchmarks/tests/test_zone_threshold_summary.py`

**Interfaces:** Launcherparameter `-OutputDirectory <neu> -Seeds @(42,43,44) -ThresholdMeters @(1500,3000,5000) -JobCounts @(16,32) -Pilot`; exakte Variante immer zusätzlich. JSON-Manifest bindet JAR-/Quell-/Guard-/Inputhashes, Seed, tatsächlich absolvierte Iterationen, Profil, Schwelle, JVM-Grenzen und Ressourcenstatus. Zusammenfassung akzeptiert nur vollständige zusammenpassende Paare.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** Zuerst Python-Auswertung mit kleinen synthetischen Resultdateien: wechselnde Inputs, fehlende Variante, ungleiche echte Iterationszahl und ResourceDeferred dürfen keinen Speedup erzeugen. Pilot: Services und Shipments, ein Seed/ein Jobcount; alle Jobs bedient und exakte Abschlusskosten vorhanden. Schwellen liegen auf einem genügend großen Netz tatsächlich innerhalb der Verbindungsdistanzverteilung; COVERAGE weist benutzte Approximation aus.

```python
assert not summary['comparable']  # when actual iterations differ
assert summary['quality']['unassigned_jobs'] == 0  # complete fixture
```

- [ ] **Step 2 – RED prüfen:** `python -m unittest discover -s tools/benchmarks/tests -p test_zone_threshold_summary.py` auf den gezielt fehlerhaften/unvollständigen Messfällen ausführen; keine JVM für diesen Schritt.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Vorbereitete Artefaktprobe aus zone-threshold-comparison nur nach Lesen/Compiler-/Pilotprüfung übernehmen; sie wurde bisher nicht ausgeführt. Netz24×24 mit500m Abstand und dokumentierter Zonengröße als erster synthetischer Fall, danach kleine reale Carrier. Ein CPU/384MiB für beide Seiten, feste tatsächliche Suchbudgets, abwechselnde Variantenreihenfolge und frische JVMs für unabhängige Wiederholungen. Vorbereitung, Suche, Straßenrouting, Prüfung, Gesamtzeit, Speicher, Cache-/Zonenanteil und exakt bewertete Qualität ausgeben. Keine ungeschützte alte 1-GiB-Benchmarkdatei starten.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** Auswertungstests wiederholen, danach geschützten Pilot über den neuen Launcher: `& pwsh -NoProfile -File "$repo/tools/benchmarks/run-zone-threshold-comparison.ps1" -OutputDirectory (Join-Path $verificationDir 'zone-pilot') -Pilot`. Erst bei vollständigem Pilot auf drei Seeds/weitere Jobgröße erweitern. Jeder Ressourcenabbruch erhält DEFERRED.
- [ ] **Step 5 – Paket abschließen:** Bericht trennt gemessenen Effekt, Messstreuung und offene Repräsentativität. Bei fehlendem Spielraum bleiben vorbereitete Versuche offen; keine Schwelle wird allein wegen eines Minifalls zum Standard. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

### Task 16: Gesamtabnahme, Kompatibilität und einfache Dokumentation

**ID / Phase:** T16 / 6. **Bezug:** Alle Auditpunkte, Refactor-Abnahme, offene frühere Gates. **Abhängigkeiten:** T01–T15; verschobene Abnahmen sichtbar.

**Files:**
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/core/simulation/FreightIntegrationAcceptanceTest.java`
- Test erweitern: `hagrid/simulation/src/test/java/hagrid/core/ArchitectureRulesTest.java`
- Ändern: `docs/superpowers/plans/2026-10-08-hagrid-jsprit-open-checks.md`
- Ändern: `docs/superpowers/plans/2026-10-09-hagrid-jsprit-abschluss-einfach.md`
- Ändern: `docs/superpowers/plans/2026-10-09-hagrid-gemeinsame-konfiguration.md`
- Neu: `docs/superpowers/plans/2026-10-10-hagrid-audit-fixes-ergebnisse.md`

**Interfaces:** Abschlussbericht verwendet pro Audit-ID Status `REPRODUCED`, `FIXED_TARGETED`, `INTEGRATION_VERIFIED` oder `DEFERRED` plus Belegpfad. Kein Gesamt-Fertigstatus, solange ein Pflichtfall unbestätigt ist. Öffentliche Legacy-Adapter besitzen eine Caller-Liste und dokumentierte Migration.

- [ ] **Step 1 – Gegenbeispiele und Regressionen formulieren:** Kleine echte QSim prüft je Iteration und Carrier/Fahrzeug die erwarteten Fahrten und vollständigen Services/Pickups/Deliveries nach Replanning, nicht nur aufsummierte Modusereignisse. Kostenattribute gegen Ledger, Netze/Abbiegeverbote, beide Modi und NCE prüfen. Alle vorhandenen relevanten Tests und kompletter aktueller Testbestand; Zahl nicht auf historische1466 festschreiben. Anschließend verify/package und Replay exakt dieses neuen JAR.

```java
assertEquals(expectedCarrierIds, observedCarrierIdsInFinalIteration);
assertEquals(expectedJobIds, completedJobIdsInFinalIteration);
assertEquals(publishedCosts, executionCosts);
```

- [ ] **Step 2 – RED prüfen:** `Check-Hagrid 'T16-red' 'FreightIntegrationAcceptanceTest,ArchitectureRulesTest,ReplanningCoordinatorTest,CarrierReplayTest'` ausführen. Erwartet ist der bezeichnete neue Fehler; vorhandene unabhängige Regressionen sollen nicht zusätzlich scheitern.
- [ ] **Step 3 – Implementieren und fachlich begrenzen:** Ergebnisdokument mit vorher/nachher, getroffenen Modellentscheidungen, wirksamen Konfigurationswerten und gemessener Wirkung schreiben. Bekannte19 native Freight-Fälle sowie reale Profil-/Produktions-/RAM-Gates einzeln nachführen; Referenzänderungen begründen. Veraltete unabhängige7,5h-Beschreibungen nur in aktuellen Anleitungen korrigieren, historische Messergebnisse bewahren. Git-Integration erst nach Status-/Diffprüfung: isolierte eigene Commits, keine fremden Änderungen, kein automatischer Rebase/Merge/Push.
- [ ] **Step 4 – GREEN und betroffene Integration prüfen:** `Check-Hagrid 'T16-green' 'FreightIntegrationAcceptanceTest,ArchitectureRulesTest,ReplanningCoordinatorTest,CarrierReplayTest'` ausführen. Erwartet: alle angegebenen aktuellen Tests bestanden, keine übersprungenen neuen Fälle und kein Ressourcenabbruch.
- [ ] **Step 5 – Paket abschließen:** Der Nutzer erhält eine verständliche Tabelle erledigt/getestet/offen, neue Klassenlandkarte, Konfigurationsbeispiele und belastbare Messgrenzen. Technischer Abschluss und Produktionsfreigabe bleiben unterscheidbar. Diff und Mess-/Testbelege prüfen, Ledger aktualisieren, nur eindeutig eigene geprüfte Änderungen separat committen; offene Abnahme konkret markieren.

## Gezielte Messpunkte

| Zeitpunkt | Bezug | Aussage |
|---|---|---|
| Nach T01 | Gesicherter bisheriger Stand | Gleiche Inputs/Seeds für späteren Vergleich; bekannte Bugs bleiben als solche markiert |
| Nach T06 und T10 | Korrigierte Fachlogik | Gewollte Änderungen an Kosten/Zeitfenstern erklären; keine rohe Qualitätsverbesserung behaupten, wenn sich die Bewertung geändert hat |
| Vor/nach T12 | Gleicher korrigierter Stand | Zahl vollständiger Routings/Validierungen und Replanning-Zeit |
| Vor/nach T13 | Gleicher korrigierter Stand | Graphvorbereitung, Kontext-RAM, JVM-RAM und identische Routen/Kosten |
| T15 | Exakt gegen 1500/3000/5000 m | Zeit-/RAM-Gewinn gegen exakt nachbewertete Qualität, nicht nur jsprit-Suchkosten |
| T16 | Neu gebautes Paket | Tatsächliche Integration und Paketidentität |

Mindestens drei unabhängige Seeds für den begrenzten ersten Vergleich; Wiederholungen desselben Seeds sind keine unabhängigen Problemfälle. Warmups getrennt ausweisen. Kein Speedup bei ungleichen Eingabefingerprints oder ungleichen tatsächlichen Suchbudgets. Ergebnisfehler, unbediente Jobs und harte Verletzungen werden vor Laufzeit interpretiert.

## Optionale Erweiterungen nach der Pflichtabnahme

Diese beiden Ideen sind im Entwurf aufgenommen, ändern aber die fachliche Auswahlpolitik. Sie bleiben nach den Fehlerkorrekturen separat messbare Pakete mit unverändertem Standard:

- **O1 – Weitere modulare Fahrzeuge versuchen.** Vorschlag `modular.maximumDispatchCandidates=1` als kompatibler Default; opt-in 2 oder 3. Betrifft ModularTourDispatcher und zentrale Modular-Konfiguration. Erst nach kompletter unverändernder Machbarkeitsprüfung des ersten Kandidaten den nächsten versuchen; kein Kandidat darf den Fahrplan eines abgelehnten Vorgängers verändert haben. Test: nächstes Fahrzeug Dienstende zu früh, zweites Fahrzeug schafft Tour. Wirkung an bedienten Touren UND zusätzlichen Routingaufrufen messen.
- **O2 – Mehrere jsprit-Lösungen abschließend vergleichen.** Vorschlag `optimization.finalCandidateCount=1` als kompatibler Default; opt-in 2 oder 3. Betrifft CarrierOptimizationService, PlanSelection und zentrale Optimierungskonfiguration. Unterschiedliche gefundene Lösungen nach Tourstruktur deduplizieren, jeweils exakt routen/prüfen und nach derselben finalen Kostenfunktion wählen. Test: niedrigere Suchkosten verlieren nach erlaubter finaler Straßenbewertung gegen zweiten Kandidaten. Qualität und Zusatzzeit getrennt ausweisen.

Vor Umsetzung dieser optionalen Pakete die knappen Entwürfe zu eigenen Aufgaben mit Caller-/Testinventar ausarbeiten. Keine optionale Optimierung automatisch aktivieren, um einen Pflichtfehler zu verdecken.

## Gesamtabnahme und noch offene frühere Themen

Nach allen gezielten Paketen vollständigen aktuellen Bestand geschützt ausführen:

```powershell
Check-Hagrid 'T16-complete-suite' '*' 'verify'
& pwsh -NoProfile -File "$repo/tools/benchmarks/run-package-replay-smoke.ps1" -OutputDirectory (Join-Path $verificationDir 'package-replay')
```

Voraussetzung: frische Ressourcenfreigabe durch die Wrapper. Einen Vollsuite-Abbruch nicht durch einen vergangenen grünen Stand ersetzen. Paket-Replay verwendet den tatsächlich gerade erzeugten JAR; Hash, Quellenstand und Ergebnisse zusammen dokumentieren.

Die bestehende Liste `2026-10-08-hagrid-jsprit-open-checks.md` bleibt die Grundlage für native jsprit-2.0-Freight-Fälle, reale Hintergrundprofile, Produktionsdaten, die 500-Fahrzeuge-Annahme, Parallelitäts-/RAM-Kalibrierung und Git-Veröffentlichung. Korrektheits- und Refactorabschluss dieses Plans ist keine automatische Produktionsfreigabe.

## Selbstprüfung des Plans

- [x] A01–A12 sind über die Tabelle in der Spec vollständig zugeordnet.
- [x] Für alle Pflichtpakete sind Dateien, Schnittstellen, konkrete Gegenfälle und ausführbare Prüfeinstiege angegeben.
- [x] Native nodebasierte Nachrouting-Aufrufe und GenericRoute/Ereignisgrenzen werden ausdrücklich mit migriert.
- [x] Neue Namen haben einen Produzenten; NoRouteException kommt aus T08, RoadLeg/LinkRouteProvider ebenfalls, Kostenrecords aus T05, Beobachtungen aus T06.
- [x] Die fünf Review-Focus-Grenzen haben konkrete Tests in ihren jeweiligen Aufgaben.
- [x] Betriebsannahmen, geänderte Fachregeln und reine Performance-Refactors sind unterscheidbar.
- [x] Keine Tests, Produktionsänderungen oder neuen Automationen wurden durch das Schreiben dieses Plans ausgeführt.

Die Häkchen dieser Selbstprüfung betreffen ausschließlich die Qualität des Plans. Sämtliche Umsetzungsschritte oben sind offen.
