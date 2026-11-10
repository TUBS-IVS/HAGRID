# HAGRID: jsprit 2.0 und Refactor der MATSim-Integration

Stand: 2026-10-07, ergänzt nach Nutzerfeedback um gemeinsame Konfiguration, Wiederaufnahme/Replay, Warmstart, adaptive Suchbudgets, Lastenräder im gemeinsamen Netz und regelmäßige kleine Vergleiche mit der heutigen Implementierung. jsprit-Replanning und RAM sind die Engpässe; die Verteilung über MATSim-Iterationen bleibt erhalten.
Status: Grundlage des Umsetzungsplans vom 2026-10-07; der Nutzer hat die schrittweise Implementierung in dieser Sitzung freigegeben. Der parallel laufende Valhalla-Lauf hat nach ergänzender Anweisung höchste Ressourcenpriorität.

## 1. Verbindliches Ziel

Zuerst jsprit Core/Analysis 2.0.0 integrieren, anschließend die gesamte HAGRID-MATSim-jsprit-Integration und ihre beteiligten Java-Klassen korrigieren, vereinfachen und messen. Erst danach jsprit intern auf weitere Verbesserungen untersuchen.

Der Umfang reicht von der Erstplanung über Netze, Reisezeiten, Optimierung und Straßenrouting bis zu geprüfter Planübernahme, Scoring, Kennzahlen und Plan-Cache. Auch die direkten Hannover- und Lausitz-Aufrufer gehören dazu. Nachfrageerzeugung, allgemeine Analysewerkzeuge und DRT-Fachlogik bleiben eigenständige Bereiche.

Die vorhandene HAGRID-Konfiguration wird nach Verantwortlichkeiten aufgeräumt und zu einem gemeinsamen, geprüften Einstieg zusammengeführt. Dazu gehören die unmittelbar benötigten Einstellungen der Carrier-Erzeugung, insbesondere Fahrzeuge, Stopplinks und Tourregeln; das statistische Nachfragemodell wird dabei nicht geändert. Lastenrad-Carrier auf einem gemeinsamen multimodalen Netz sind ein ausdrücklich gewünschter Anwendungsfall und erhalten Nachweise bis zur tatsächlichen MATSim-Ausführung.

Die Zielarchitektur besitzt **einen universellen Transportkosten-Anbietertyp für alle unterstützten Modi**. Er wählt intern anhand des Fahrzeugs den passenden Modusrouter. Aufrufer benötigen weder eine öffentliche Modus-Map noch Casts auf ZoneBasedTransportCosts.

## 2. Geprüfter Ausgangspunkt

| Gegenstand | Stand und Konsequenz |
|---|---|
| Git-Basis | Lokaler Hauptstand und GitHub-master wurden am 2026-10-06 verglichen: beide afbaf33b72f5273f80ec2c844056618d4b6c4add. Aktuell kein Rebase nötig. Vor Implementierung erneut prüfen. |
| Arbeitsbranch | codex/zone-router-fixes enthält die dokumentierten Entwürfe sowie noch nicht committete Router-Korrekturen, Tests und Benchmarks. Diese Arbeit erhalten und gezielt weiterverwenden. |
| Laufzeit/Abhängigkeiten | Java 21, MATSim 2025.0, jsprit 1.8; lokaler Freight-Quellstand 2db6789b6c3602c3f10487cdf4d01ad6fdc0c1be. |
| Upgrade-Vorversuch | Hauptklassen kompilierten mit Core/Analysis 2.0.0 und IO 1.8; ein kleiner Solver-Lauf funktionierte. Vollständige Tests, Packaging und tatsächlich benutzte IO-Pfade fehlen noch. |
| Router-Nachweise | Vorbereitete Korrekturen und synthetische Beschleunigungen liegen vor. Sie belegen weder die gesamte Simulationslaufzeit noch die Güte zeitabhängiger Produktionslösungen. |
| Diagnose | Bedienzeit/-kosten, Abfahrtsänderung, Rückwärtszeit, Cache-Zeitbinning, mögliche State-ID-Kollisionen, fehlender Anbieter und ungültige ALT-Untergrenzen wurden bereits gezielt untersucht. |
| Produktionsdaten | Die erforderlichen Hannover-Konfigurations- und Netzereignisdateien sind im geprüften Arbeitsbereich nicht verfügbar. Der vollständige Produktionsnachweis bleibt davon abhängig. |

Fremde Notebook-, Nachfrage- und Auswertungsänderungen im Hauptarbeitsbereich werden erhalten. Falls GitHub vor der Umsetzung weiterläuft, zuerst die vorhandene eigene Arbeit im Arbeitsbranch sichern, anschließend gezielt aktualisieren und die betroffenen Nachweise wiederholen. Das Hauptarbeitsverzeichnis wird dafür nicht zurückgesetzt.

## 3. Modellablauf und feste Verkehrsbedingungen

### 3.1 Fachlicher Ablauf

1. Für Hannover schnelle Startpläne mit einer jsprit-Iteration erzeugen; vorhandene ausdrücklich abweichende Einstellungen bleiben nachvollziehbar.
2. Die Touren auf dem Netz mit den aus dem normalen MATSim-Modell übernommenen Verkehrsbedingungen simulieren.
3. Zu Beginn der einmaligen Neuoptimierung einen gemeinsamen Reisezeitstand festhalten.
4. Jeden vorgesehenen Carrier genau einmal erfolgreich bearbeiten; die Arbeit kann in Gruppen und parallel erfolgen.
5. Die ausgewählten geprüften Pläne abschließend simulieren und auswerten.

Die etwa 500 Lieferfahrzeuge werden als kleine Zusatznachfrage modelliert. Ihr vernachlässigbarer Einfluss auf die Hintergrundverkehrslage ist eine zu prüfende Annahme. Anzahl und Einfluss an einzelnen Depot-/Engpasslinks sind dabei getrennt zu betrachten.

Das Modell führt keine wiederholte jsprit-Rückkopplung bis zu einem Verkehrsgleichgewicht ein. Lausitz behält seine bestehenden Offline-Regime, Iterationsbudgets und szenariospezifischen Tourgrenzen.

Die gemeinsame Optimierung von Zubringer- und Auslieferungsstufe gehört nicht zu dieser Etappe. Supply-/Hub-Ankunftszeiten werden nicht zu neuen dynamischen Freigabebedingungen für andere Carrier umgebaut. Bestehende Annahmen zur Warenverfügbarkeit werden im Szenarioprofil sichtbar gemacht; der Lauf wird nicht als zeitlich synchronisierte Zwei-Stufen-Optimierung ausgewiesen. Eine spätere Kopplung wäre ein eigenes Modellprojekt mit eigener fachlicher Abnahme.

### 3.2 Quelle und Zeitpunkt des Reisezeitstands

Für den bestehenden Hannover-Ablauf wird der letzte vollständig abgeschlossene HAGRID-Simulationsstand vor Beginn der Neuoptimierung festgehalten. Beim bisherigen Start in Iteration 25 sind dies die Ergebnisse der vorherigen abgeschlossenen Iteration. Die importierten Network Change Events bleiben die exogene Randbedingung dieses Laufs.

Gemessene Reisezeiten und aus Hintergrundgeschwindigkeiten abgeleitete Linkzeiten werden nicht addiert. Für unbeobachtete Links/Zeitintervalle wird die vorhandene, ausdrücklich dokumentierte Ersatzregel mit den importierten zeitabhängigen Netzgeschwindigkeiten verwendet. Fahrzeughöchstgeschwindigkeiten werden weiterhin berücksichtigt.

Ein direkter Optimierungslauf ausschließlich gegen importierte Hintergrundprofile ist als ausdrücklich gewählte Eingabe möglich; er ersetzt den beschriebenen Simulationsablauf nicht stillschweigend. Jeder Lauf protokolliert Quelle, Messiteration, Zeitauflösung, Gültigkeitshorizont und Umgang mit fehlenden Beobachtungen.

Alle Carrier des Durchgangs erhalten denselben Stand, auch wenn sie über mehrere MATSim-Iterationen verteilt bearbeitet werden. Eine Referenz auf einen später zurückgesetzten TravelTimeCalculator ist kein Snapshot. Gemessene Werte, notwendige Ersatzprofile und relevante Netz-/Fahrzeugparameter werden unabhängig vom veränderlichen Simulationszustand festgehalten. Der Speicherbedarf wird vor dem Aufbau abgeschätzt und gemessen; vollständige dichte Tabellen je Fahrzeug werden vermieden.

### 3.3 Abschluss und Planauswahl

Die Steuerung verwaltet eine feste Liste der vorgesehenen Carrier und deren Zustände: ausstehend, laufend, abgeschlossen, fehlgeschlagen oder abgebrochen. Ein Startmarker ist kein Erfolgsnachweis. Ein begrenzter Reparaturversuch gehört zur selben Bearbeitung; unbeschränkte Wiederholungen gibt es nicht.

Startiteration, Gruppengröße und Arbeitsbudgets werden ausdrücklich konfiguriert. Die bisherigen Hannover-Werte 25 und maximal 16 Carrier pro Gruppe dienen als Ausgangspunkt. Die Gruppen bleiben über mehrere MATSim-Iterationen verteilt; die Konfiguration muss genügend Replanning-Gelegenheiten für alle Gruppen bieten. Gruppengröße und gleichzeitig aktive Solver sind verschiedene Größen: Eine Gruppe mit 16 Carriern darf beispielsweise mit einer kleineren Anzahl aktiver Solver abgearbeitet werden. Nach der letzten Gruppe muss mindestens eine QSim-Ausführung mit den freigegebenen Endplänen stattfinden; sie kann im selben Controller-Durchlauf unmittelbar auf das Replanning folgen. Unpassende Kombinationen aus Start, Ende und Gesamtiterationszahl erzeugen eine verständliche Konfigurationsmeldung. Die Bearbeitung aller Carrier darf nicht vom zufälligen Aufruf einer gewichteten Planstrategie abhängen.

Ein zuvor vorhandener Plan wird gegen denselben festen Reisezeitstand neu bewertet und geprüft. Ist er vollständig, zulässig und günstiger als der neue Kandidat, darf er ausgewählt bleiben. Der Carrier gilt dann als bearbeitet mit unverändertem, erneut geprüftem Plan. Ein erfolgreicher Solver-Aufruf verlangt keine künstliche Verschlechterung durch einen neuen Plan.

Nach Abschluss eines Carriers dürfen BestPlan-Auswahl oder weitere Abfahrtsmutationen keinen ungeprüften Altplan an dessen Stelle setzen. Für den vorgesehenen einmaligen Hannover-Ablauf wird der geprüfte Plan zur abschließenden Simulation festgehalten. Eine spätere ausdrücklich aktivierte Planänderung muss erneut geprüft werden und ihren geänderten Status ausweisen.

### 3.4 Einbindung in den tatsächlichen MATSim-Lebenszyklus

Hannover lädt seine vorher erzeugten Carrier-Pläne über HAGRIDScenarioBuilder. SimulationRunnerUtils installiert anschließend das native CarrierModule und die HAGRID-Bindungen für Optimierung und Scoring. Die Erstplanung ist ein vorheriger Pipeline-Schritt; ein direkter Start des Simulations-Runners erzeugt sie nicht automatisch erneut.

Der geprüfte lokale MATSim-Ablauf lautet:

1. Iterationsbeginn; ab der zweiten ausgeführten Iteration folgt das Replanning-Ereignis.
2. CarrierControllerListener ruft den CarrierStrategyManager auf. Hier muss die HAGRID-Steuerung den gemeinsamen Reisezeitstand vor dem ersten Optimierungsauftrag einmalig sichern und geprüfte Ergebnisse übernehmen.
3. Vor QSim setzt EventsHandlingImpl die Eventhandler zurück. Das betrifft sowohl den TravelTimeCalculator als auch den CarrierAgentTracker; letzterer erzeugt seine Carrier-Agenten aus dem jetzt ausgewählten Plan neu.
4. FreightAgentSource erzeugt die Fahrer/Fahrzeuge für die ausgewählten Touren und bringt sie in QSim ein. Simulationsereignisse liefern neue Reisezeitmessungen und die Grundlage für das Carrier-Scoring.
5. Nach QSim bewertet CarrierControllerListener die gefahrenen Pläne. Danach folgen Iterationsausgaben. Am Laufende schreibt CarrierModule Carrier/Fahrzeugtypen und kann zusätzlich eine ereignisbasierte Analyse starten.

Deshalb wird der Snapshot im ersten vorgesehenen Replanning-Aufruf vor dem Eventhandler-Reset erzeugt, nicht als bloße Referenz bei der frühen Modulinstallation und nicht erst nach dem Reset. Bei Startiteration 25 liegt dort der Messstand aus Iteration 24 vor. Ein Integrationstest muss diese Reihenfolge mit echten Controller-Ereignissen nachweisen. Die Solver-Worker sind vor Rückkehr aus dem Replanning-Schritt beendet; QSim sieht ausschließlich vollständig übernommene Pläne.

Ein kleines Ablaufprofil beschreibt Erstplanungsbudget, Snapshot-/Optimierungszeitpunkt und abschließende Bewertung. Hannover verwendet den einmaligen Optimierungsablauf; Lausitz behält seine Offline-Planung mit KeepSelected. Die vorhandene Entscheidung, ob DRT-Fälle überhaupt zusätzlich CarrierModule ausführen, bleibt erhalten, damit Aufträge nicht doppelt transportiert werden. Fachliche Carrier/LSP-Zuordnung bleibt bestehen; dieser Refactor führt keine zusätzliche MATSim-LSP-Planungsschicht ein.

### 3.5 Gruppierung als bewusste Ressourcenbegrenzung

Der Nutzer hat präzisiert: Die MATSim-Iterationen sind zeitlich nicht das Problem; teuer sind jsprit-Replanning und dessen RAM-Bedarf. Die Verteilung der Carrier über Iterationen ist deshalb eine bewusste Entwurfsentscheidung und bleibt bestehen. Eine Verkürzung des MATSim-Ablaufs oder Zusammenlegung aller Gruppen in einen einzigen Replanning-Schritt ist kein Optimierungsziel dieses Refactors.

Optimiert werden die Arbeit innerhalb jeder Gruppe und die Lebensdauer ihrer Daten. Es gibt getrennte Grenzen für Carrier je Iteration, aktive Solver und Speicherbudget. Die Zahl vorhandener CPU-Kerne ist keine ausreichende RAM-Grenze. Fertige Solver, temporäre Plankopien und aufgabenspezifische Zustände dürfen nicht über spätere Gruppen hinweg festgehalten werden. Geteilte Netzdaten, der feste Reisezeitstand und begrenzte Caches werden dagegen innerhalb ihres ausdrücklich bemessenen Budgets wiederverwendet.

## 4. Etappen und Abhängigkeiten

| Etappe | Ergebnis | Abschlusskriterium |
|---|---|---|
| A: jsprit 2.0 | Konsistente Core-/Analysis-Version, minimale API-Anpassungen, geprüfte IO-Pfade und Packaging | Beide Module bauen und bestehen ihre vorhandenen Tests; genutzte Laufzeitpfade und Artefakte sind kompatibel |
| B1: fachliche Grundlage | Gegliederte wirksame HAGRID-Konfiguration, gemeinsame Problem-/Solver-Konfiguration, korrekte Bedienzeiten, lokale Zustände und Ergebnisbewertung | Konfigurationsmigration und gezielte Regressionen bestehen; korrigierte Referenzfälle sind eindeutig bewertbar |
| B2: Routing | Fester Reisezeitstand, universeller Adapter, gemeinsames Auto-/Lastenradnetz, Modusprofile, Zeitvertrag, austauschbare Zonenannäherung und Cache | Verträge für beide Modi bis QSim, Zeitabhängigkeit, Cache-Isolation und Straßenrouting sind nachgewiesen |
| B3: Integration | Gemeinsamer Optimierungsdienst, einmalige Bearbeitung, sichere Planübernahme, Scoring/Kennzahlen, konsistente gespeicherte Ergebnisse und Replay | Hannover-Ende-zu-Ende-Fall, Wiederaufnahmeprüfung und Lausitz-Regimeprüfung bestehen |
| B4: Laufzeit und RAM | Innerhalb der bestehenden Gruppen CPU-/Speicherbudget abstimmen, Datenlebensdauer begrenzen, Warmstart und konfigurierbare Suchbudgets vergleichen | Vergleichbare Replanning-Zeiten, Speicherverläufe und Qualitätsmessungen liegen vor |
| C: jsprit intern | Profiling und begründete nächste Optimierungen | Erst nach den lokalen HAGRID-Abnahmen; ausstehende Produktionsnachweise bleiben ausdrücklich sichtbar |

Die Etappen sind fachliche Meilensteine. Der spätere Umsetzungsplan zerlegt sie in kleine Änderungen mit passenden Tests und verbindlichen kleinen Vergleichsläufen gemäß Abschnitt 10.3. Diese Prüfpunkte liegen auch innerhalb der Etappen nach fachlich zusammenhängenden Änderungen; ihre sinnvolle Häufigkeit wird bei der Umsetzung festgelegt. Versionseffekte, fachliche Korrekturen und reine Strukturänderungen bleiben einzeln nachvollziehbar. Vorbereitete Router-Patches werden nicht ungeprüft komplett in die Versionsmigration aufgenommen.

## 5. Upgrade-Vertrag

Core und Analysis werden auf das veröffentlichte jsprit 2.0.0 festgelegt. Java 21 und MATSim 2025.0 bleiben die Grundlage. Parent, HAGRID-Modul und lokales Freight-Modul verwenden konsistente Versionen; der aufgelöste Abhängigkeitsbaum und das ausgelieferte JAR dürfen keine konkurrierenden Core-Versionen enthalten.

IO erhält eine eigene Versionsangabe. Ausgangspunkt ist das im Vorversuch verwendete IO 1.8. Kompilierung allein reicht nicht: benutzte Algorithmus-Konfigurationen, Reader/Writer und tatsächliche Einstiegspunkte werden geprüft. MATSim-Carrier-XML und jsprit-Algorithmus-XML sind verschiedene Formate; ihre Serializer sind nicht austauschbar. Ein notwendiger Adapter wird auf den tatsächlich inkompatiblen Aufruf begrenzt.

REGRET_INSERTION, FAST_REGRET=false und die vorhandenen Seed-Regeln bleiben bei der Migration erhalten. Bewusste Unterschiede zwischen Erst- und Neuplanung werden zunächst erfasst und anschließend als benannte Varianten der gemeinsamen Konfiguration behandelt. Jeder Solver besitzt seinen eigenen Zufallszahlengenerator; Parallelisierung ändert die Seed-Zuordnung nicht. Neue Standardwerte der Bibliothek werden geprüft und ausgewiesen.

Bestehende Tests und XML-Rundläufe müssen mit 2.0 funktionieren. Kleine gepaarte Solver-Fälle berichten Kosten, Fahrzeuge, unzugewiesene Aufträge und Laufzeit; identische heuristische Touren über Bibliotheksversionen hinweg werden nicht vorausgesetzt.

Die technische Migration kann abgeschlossen werden, während bekannte HAGRID-Fehler noch in B bearbeitet werden. Neue produktive Ergebnisse benötigen die relevanten fachlichen und Routing-Abnahmen.

## 6. Zielstruktur mit wenigen klaren Zuständigkeiten

Die Namen sind Entwurfsnamen. Ein zusätzlicher Typ entsteht nur, wenn er einen eigenen Vertrag oder zusammengehörige Daten trägt. Bestehende passende Bausteine werden weiterverwendet.

| Verantwortung | Aufgabe |
|---|---|
| Routing-Kontexte | Ein fester Reisezeitstand, daraus passende Modusnetze und unveränderliche Fahrzeug-/Kostenprofile; Kontextkennung und Genauigkeitsregeln |
| Universeller Transportkosten-Adapter | Ein VRPTransportCosts-Vertrag für alle unterstützten Modi; Auswahl anhand des Fahrzeugs und Delegation aller erforderlichen Framework-Aufrufe |
| Netzberechnung, Zonenannäherung und Cache | Gemeinsame Berechnung von Zeit/Distanz/Kosten; optionale räumliche Näherung; begrenzte Wiederverwendung im passenden Kontext |
| Solver-Konfiguration und Optimierungsdienst | Problem erstellen, solverlokale Zustände/Constraints setzen, Suche ausführen und ein vollständiges Ergebnis zurückgeben |
| Steuerung, Prüfung und Übernahme | Carrier einplanen, Ergebnisse unabhängig prüfen, alten und neuen Plan vergleichbar bewerten und gültigen Plan übernehmen |

Der Replanner steuert den Ablauf. Er baut keine zweite Kopie der Solver-Konfiguration auf und kennt keine konkrete ZoneBasedTransportCosts-Klasse. Statistik und Kontextlebensdauer werden ausdrücklich übergeben; ein einzelner Carrier setzt keinen gemeinsam genutzten Cache anderer laufender Carrier zurück.

### 6.1 Universeller Anbieter und Factory

Die bisherige normale createVRPTransportCosts()-Methode kann bereits zeitabhängige Reisezeiten verwenden, bindet aber fest den bereitgestellten Auto-Anbieter. Die zweite Methode mit Map-Rückgabe verteilt Anbieter auf Modi; die Map ist keine Voraussetzung für Stauzeiten.

Der künftige HAGRID-Vertrag hat einen einzelnen Rückgabewert:

~~~java
VRPTransportCosts createVRPTransportCosts(RoutingRequest request);
~~~

Der Auftrag beschreibt das Optimierungsproblem, seine Fahrzeugprofile, den gemeinsamen Reisezeitstand und die Regel für Abfragen ohne Fahrzeug. Eine parameterlose Variante wäre nur dann gleichwertig, wenn die Factory bereits eindeutig an diesen Auftrag gebunden ist. Es werden nicht beide neuen Varianten ohne Bedarf parallel eingeführt.

Intern bleiben getrennte, korrekt gefilterte Modusnetze und passende Router bestehen. Die Map der MATSim-Reisezeitfunktionen kann intern weiterverwendet werden. Leichte Adapter pro Problem teilen vorbereitete Kontexte und Caches; universell bedeutet nicht eine einzige veränderliche globale Instanz.

### 6.2 Vollständiger Framework-Vertrag

- Zeit, Distanz, Kosten und Rückwärtsabfragen wählen dasselbe Fahrzeug-/Modusprofil. Die Zuordnung zur MATSim-Fahrzeugdefinition wird beim Kontextaufbau geprüft und festgehalten. Widersprüchliche Typkennungen dürfen nicht still überschrieben werden.
- getRouter() liefert einen delegierenden Wegesucher. Dessen Aufrufe erhalten das MATSim-Fahrzeug und wählen das passende gefilterte Netz. Knoten und Links werden über ihre IDs dem jeweiligen Netz zugeordnet; veränderliche Suchzustände gehören zu einem Arbeiter.
- getTravelTime() verwendet dieselbe Zuordnung, auch beim gesondert berechneten Ziel-Link. Start-/Ziel-Link-Zählung und Wegezeiten müssen zwischen Kostenschätzung und NetworkRouter übereinstimmen.
- getNetwork() liefert eine gemeinsame Nachschlageansicht aus demselben Ausgangsnetz. Sie besitzt kompatible IDs, Geometrie, Attribute und Netzereignisse. Die Wegesuche selbst erfolgt im Modusnetz. Jeder ausgegebene Routenlink und Stopplink muss auch im tatsächlich von QSim verwendeten Szenarionetz existieren und für seinen Modus geeignet sein; gleiche IDs allein beweisen keine gleiche Topologie oder Zeitbasis.
- Unbekannte Fahrzeugprofile, fehlende Modusanbieter und ungültige Stopplinks werden erkennbar behandelt. Unerreichbare Wege sind unzulässig. Es gibt keinen stillen Luftlinienwechsel und keinen verbotenen Link, der durch eine sehr niedrige Ersatzgeschwindigkeit doch befahrbar wird.

Das bestehende lokale MATSim-Interface VRPTransportCosts bleibt kompatibel. Änderungen an allgemeinen MATSim-Interfaces sind für diese Zielstruktur nicht erforderlich.

Der Modusvertrag endet nicht beim Kostenanbieter: Im lokalen Freight-Code setzt CarrierAgent den Modus der Fahrer-Legs aus CarriersUtils.getCarrierMode(carrier), nicht aus dem Fahrzeugtyp. Deshalb müssen Carrier-Modus, Fahrzeugtyp, jsprit-Adapter, gefiltertes Netz, Leg-Modus, QSim-Konfiguration und Reisezeitmessung zusammenpassen. Pauschale setCarrierMode("car")-Zuweisungen bei Erzeugung oder Laden werden durch eine geprüfte Zuordnung ersetzt. Bestehende Einzelmodus-Carrier können damit das native CarrierModule unverändert nutzen. Eine echte Mischung verschiedener Modi innerhalb eines Carriers benötigt zusätzlich eine gezielte Anpassung der Fahrerplan-Erzeugung; der universelle Kostenanbieter allein unterstützt diese Ausführung noch nicht. Solche Eingaben werden bis dahin vor dem Lauf ausdrücklich zurückgewiesen, nicht still auf Auto gesetzt oder mit veränderter Auftragszuordnung aufgeteilt.

Auch weitere Übergaben an Fahrerpläne werden geprüft: Der lokale CarrierAgent setzt die letzte Aktivität auf den Fahrzeug-Depotlink. Eine fachlich offene Tour mit anderem Endlink darf daher nicht allein wegen einer erfolgreichen jsprit-Lösung als vollständig unterstützt gelten. Angeforderte offene Touren benötigen eine konsistente Übersetzung bis zum Fahrerplan; eine noch nicht unterstützte Kombination scheitert vor der Simulation mit konkreter Diagnose.

### 6.3 Abfragen ohne Fahrzeug

Am veröffentlichten Core 2.0.0 wurde geprüft, dass AvgServiceAndShipmentDistance für Auftragsnachbarschaften getTransportCost mit null-Fahrzeug aufruft. Die Modusauswahl kann dort nicht aus einem Fahrzeug abgeleitet werden.

Für die vorhandenen Probleme mit einem Modus wird ein ausdrücklich festgelegtes neutrales Vorbereitungsprofil dieses Modus verwendet; dessen Kosten- und Geschwindigkeitsregeln werden gegenüber der bisherigen Vorbereitung festgehalten. Es ist kein wechselnder globaler Standardmodus. Auch solche Werte dürfen nicht mit konkreten Fahrzeugprofilen im Cache kollidieren.

Eine künftige gemischte Flotte innerhalb desselben Problems benötigt eine ausdrücklich festgelegte Vorbereitungsmetrik. Diese Erweiterung wird nicht allein durch den universellen Adapter als fachlich fertig erklärt. Die bisher unabhängigen Carrier werden nicht zu einem gemeinsamen Problem zusammengelegt.

### 6.4 Genauigkeit und Zeitvertrag

Räumliche Zonenannäherung und zeitliche OD-Quantisierung sind getrennte Einstellungen. Ein ausgeschalteter Zonenmodus bedeutet nicht automatisch kontinuierlich genaue Reisezeiten. Die Referenz berechnet konkrete Stopps ohne zusätzliche OD-Zeitquantisierung und verwendet die ausdrücklich festgehaltene zugrunde liegende Zeitfunktion.

Zonenrepräsentanten müssen deterministisch und erreichbar sein. Kosten, Zeit und Distanz stammen aus derselben Transportabfrage. Die abschließende Tourprüfung verwendet konkrete Stopps und Straßenwege. Bei aktivierter Näherung bleiben deren Fehler gegenüber der Referenz messbar.

Die zeitabhängige Korrektheit ist ein eigener Abnahmepunkt:

- Der Gültigkeitshorizont umfasst alle zulässigen Abfahrten und Tourenden. Außerhalb davon wird nicht still ein beliebiger letzter Cache-Wert verwendet.
- FIFO-Annahmen werden an Profil- und Ereignisgrenzen geprüft. Eine spätere Abfahrt darf bei einem als FIFO verwendeten Profil keine frühere Ankunft erzeugen. Zusätzliche OD-Zeitbins dürfen diese Eigenschaft nicht zerstören.
- Nicht-FIFO-Eingaben werden ausdrücklich gemeldet. Eine konservative Normalisierung wäre eine eigene konfigurierte Profiltransformation mit ausgewiesenem Fehler; sie wird nicht still zum Bestandteil des Refactors. Ohne gültige Voraussetzungen wird zeitabhängige Routing-Korrektheit nicht als nachgewiesen freigegeben.
- Rückwärtsabfragen suchen eine zur vorgegebenen Ankunft passende Abfahrt und prüfen sie mit der Vorwärtsfunktion. Bin-/Ereignisgrenzen und begrenzte Suchbereiche gehören zum Vertrag. Binäre Suche setzt eine nachgewiesene monotone Ankunftsfunktion voraus.
- Die vorhandene FiFoTravelTime-Klasse wird nicht ungeprüft als fertige Lösung übernommen: In ihrer Segment-Schleife wird die Ausgangszeit erneut zur Geschwindigkeitsabfrage verwendet. Ihre Eignung benötigt einen gezielten Nachweis.
- ALT-Untergrenzen müssen für alle Geschwindigkeiten und Kostenprofile des Kontexts gültig sein. Bei fehlendem Nachweis wird eine zulässige schwächere Untergrenze beziehungsweise geeignete Referenzsuche verwendet. Ein Wechsel von ALT zu Dijkstra löst keine nicht-FIFO-Eingabe.

### 6.5 Cache und Nebenläufigkeit

Transportwerte gehören zum festen Reisezeitstand, Modusnetz, wirksamen Fahrzeug-/Kostenprofil und zur Genauigkeitsregel. Der Schlüssel enthält alle innerhalb eines Kontexts noch variablen Eingaben. Kontextwechsel erzeugen getrennte Gültigkeitsbereiche.

Cache-Größe, laufende Berechnungen und Lebensdauer sind begrenzt. Gleichzeitige identische Abfragen dürfen dieselbe Berechnung teilen. Fehlgeschlagene Berechnungen werden nicht dauerhaft als erfolgreiche Werte gespeichert. Gemeinsame Wegesuchdaten dürfen geteilt werden; Suchzustände bleiben lokal zum Arbeiter.

Der persistente Plan-Cache ist davon getrennt. Der vorhandene Lausitz-JAR-Fingerprint berücksichtigt den gesamten ausgelieferten JAR-Inhalt; im IDE-Betrieb ist der Cache bereits deaktiviert. Das wird erhalten und mit dem 2.0-Paket geprüft. Neue resultatrelevante Konfigurationen, Eingaben und Reisezeitstände müssen in den Manifesten erkennbar sein. Alte Cache-Dateien werden nicht gelöscht.

### 6.6 Ein gemeinsames Netz für Auto und Lastenrad

Ziel ist ein maßgebliches multimodales Szenarionetz mit gerichteten Links und eindeutigen Modusfreigaben. Auto, Lastenrad oder beide können auf einem Link zugelassen sein. Daraus werden intern die passenden Routingnetze abgeleitet. Ein Lastenrad bleibt auf seiner Tour ein Lastenrad; das gemeinsame Netz führt keinen automatischen Wechsel des Fahrzeugmodus ein.

Die bisherigen Auto-/Lastenrad-Dateien bleiben als Migrationseingabe lesbar. Vorzugsweise wird das vorhandene vollständige Ausgangsnetz verwendet; der lokale NetworkFilterAndReducer verweist bereits auf eine kombinierte car_cargobike-Datei. Wenn nur Teilnetze verfügbar sind, ist ihre Zusammenführung ein geprüfter Import: gemeinsame IDs müssen zu denselben Knoten, Richtungen und Geometrien gehören; unterschiedliche physische Wege behalten unterschiedliche IDs. Abweichende Geschwindigkeitsattribute werden als Modusprofile behandelt und nicht durch zufällige Importreihenfolge überschrieben. Alle ausgegebenen Routen müssen im tatsächlich simulierten Netz existieren. Ohne die Produktionsdateien ist ihre verlustfreie Zusammenführbarkeit nicht nachgewiesen.

Ein reiner Autolauf benötigt keine zusätzliche Lastenrad-Netzdatei. Benötigte Eingaben ergeben sich aus den aktivierten Modi und dem gewählten Importpfad. Bei gemeinsamem Netz gibt es keine separate Pflichtdatei pro Modus.

Depots, Hubs und Zustellstopps benötigen für den jeweiligen Modus erreichbare Zugangslinks. Ein bereits auf einen reinen Autolink gelegter Stopp wird nicht allein durch einen Fahrzeugwechsel radtauglich. Bei neuer Carrier-Erzeugung erfolgt die Zuordnung aus den vorhandenen Ortsdaten im zulässigen Modusnetz; vorhandene Pläne erhalten eine Prüfung und nur bei ausdrücklich gewählter Neuverortung eine dokumentierte Änderung. Größere Verschiebungen werden gemeldet. Netzbereinigung darf notwendige Radverbindungen nicht aufgrund der Autokomponente entfernen. Die Namen bike und cargobike werden an Importgrenzen ausdrücklich abgebildet, nicht per Teilstring im Replanner erraten.

Die Fahrzeughöchstgeschwindigkeit wird je Fahrzeugtyp festgelegt und gemeinsam von jsprit-Adapter, Routing und QSim verwendet. Eingaben in km/h werden einmal in MATSim-m/s umgerechnet. Nutzerstandard am 2026-10-07: Lastenräder haben eine konfigurierbare Höchstgeschwindigkeit von 20 km/h, die Profil, Lauf oder CLI ausdrücklich überschreiben können. Für aktivierte Lastenradprofile ist eine endliche positive Höchstgeschwindigkeit erforderlich; es wird keine unbegrenzte Auto-Voreinstellung geerbt. Sie ist eine Obergrenze, keine überall erreichbare Fahrgeschwindigkeit. Link-/Wegtyp, vorhandene Geschwindigkeitsdaten und gegebenenfalls ausdrücklich verfügbare Steigungs-/Oberflächenattribute bestimmen die darunterliegende Profilgeschwindigkeit. Ohne solche Daten wird ein einfaches dokumentiertes Profil verwendet; keine unkalibrierten Detailfaktoren erfinden.

Für den ersten kompatiblen Radfall gilt auf gemeinsamen Straßen die importierte zeitabhängige Linkgeschwindigkeit zusammen mit der Fahrzeugobergrenze; auf eigenständigen Radwegen gelten deren eigene Profile ohne automatisch übertragene Pkw-Netzereignisse. Dies ist eine benannte Modellannahme für gemeinsame Straßen, keine allgemeine Behauptung über das Fahrverhalten von Lastenrädern im Pkw-Stau. Eine spätere beziehungsweise ausdrücklich konfigurierte abweichende Radgeschwindigkeit auf demselben Link benötigt dieselbe Regel in Optimierung und QSim. Der lokale DefaultLinkSpeedCalculator bietet dafür die native LinkSpeedCalculator-Erweiterung; eine nur im Routing geänderte Zeitfunktion genügt nicht. Ein eigener Rechner muss die Fahrzeugobergrenze selbst berücksichtigen, weil sein Ergebnis den Standard-Fallback ersetzt. Ereignisse zu Sperrung, Kapazität und Fahrstreifen werden nicht pauschal als reine Pkw-Geschwindigkeitsdaten behandelt.

QSim verwendet standardmäßig FIFO-Linkwarteschlangen. Auf gemischten Links werden deshalb zusätzlich Überholen, Warteschlangen sowie Speicher-/Durchflussbeiträge der Fahrzeuge geprüft. PassingQ oder SeepageQ werden nur mit ausdrücklich passendem Modus-/Szenarioprofil und nach einem gemischten Vergleichsfall aktiviert; kein globaler Wechsel der Autodynamik allein aufgrund eines Lastenrad-Flags. Der erste Radfall kann das bisherige Warteschlangenmodell als dokumentierte Vereinfachung beibehalten. Die Freigabe unterscheidet technische Funktionsfähigkeit von empirischer Kalibrierung.

Geschwindigkeitsprüfungen berücksichtigen MATSim-Zeitschritte und vollständige Linkfahrten. Eine Geschwindigkeitsberechnung aus beliebigen Aktivitätsereignissen oder einem gerundeten sehr kurzen Link ist kein belastbarer Höchstgeschwindigkeitsnachweis. Der lokale QueueWithBuffer quantisiert die freie Linkfahrzeit auf Simulationszeitschritte; diese Toleranz wird im Test ausdrücklich ausgewiesen.

## 7. Fachliche Korrekturen und Planübernahme

Bedienzeiten stammen aus der Auftragsdefinition. Ankunft, Warten und Bedienbeginn sind getrennte Größen; Bedienkosten verwenden den Bedienkostensatz. Fahrzeugkapazitäten, Zeitfenster, Depot-/Schichtgrenzen, offene Touren und Skills werden in der gemeinsamen Konfiguration behandelt. Bewusste Szenariounterschiede wie das kürzere Lausitz-Budget bleiben Parameter.

Jede Abfahrtsänderung führt zu einer erneuten Bewertung von Reisezeiten, Zeitfenstern, Schichtdauer und Kosten. Der Solver und die endgültige Tourprüfung verwenden dieselben fachlichen Definitionen. MATSim-Score, jsprit-Suchwert und nachgerechnete finale Kosten werden als unterschiedliche Größen ausgewiesen; vorhandene U-Turn- und Mautregeln werden dabei ausdrücklich berücksichtigt.

Worker arbeiten auf eigenen Problem-/Planobjekten und geben ein vollständiges Ergebnis zurück. Sie ändern während der Suche weder den ausgewählten Carrier-Plan noch Erfolgsmarker. Die Steuerung übernimmt erst nach folgenden Prüfungen:

- Alle erforderlichen Aufträge sind vollständig und genau einmal zugeordnet; bei Sendungen stimmen Abholung, Lieferung und Reihenfolge.
- Wege und Stopps sind für Fahrzeug und Modus zulässig; Kapazität, Skills, Zeitfenster und Schichtgrenzen passen.
- Finales Straßenrouting, Abfahrten, Bedienzeiten und Kosten sind konsistent.
- Alter und neuer zulässiger Plan wurden unter demselben Snapshot und derselben Kostenfunktion verglichen. Bei gleichem Wert gilt eine deterministische Auswahlregel.

Nicht zugewiesene Aufträge werden explizit mitgeführt. Ein billigerer unvollständiger Kandidat ist keine Verbesserung gegenüber einer vollständigen Lösung. Ist wegen korrigierter Regeln kein vollständiger Plan möglich, wird der Carrier als nicht erfolgreich abgeschlossen ausgewiesen.

Reparatur ist innerhalb eines begrenzten Budgets mit genauerer Bewertung möglich. Bei Fehler oder Abbruch bleibt der vorige Zustand erhalten, erhält aber nicht automatisch das Prädikat gültig. Ein Lauf mit fehlgeschlagenen oder unbearbeiteten Carriern darf nicht als vollständig neu optimiertes Produktionsresultat erscheinen.

Nach Übernahme werden Touren, finale Kosten, jsprit-Kennzahlen, Auftragsstatus und Kontextkennung gemeinsam gespeichert. Erfolgsstatus und ausgewählter Plan müssen zueinander passen.

### 7.1 Warmstart und konfigurierbare Suchbudgets

Warmstarts werden als schaltbare, geprüfte Integrationsfunktion umgesetzt und gegen den bisherigen Neuaufbau verglichen. Das veröffentlichte jsprit 2.0.0 bietet VehicleRoutingAlgorithm.addInitialSolution(SolutionSpec): Die Touren werden mit den Fahrzeugen, Jobs und Aktivitätsindizes des aktuellen VRP materialisiert und bleiben veränderbar. Das ist der bevorzugte Integrationsweg. Die bisherige Route wird nicht als gesperrte initiale Problemroute eingebaut, wenn jsprit Reihenfolge und Zuordnung noch optimieren soll.

Der lokale MatsimJspritFactory.createRoute-Adapter verarbeitet beim Rückübersetzen nur ServiceActivity; Shipment-Abholungen und -Lieferungen werden dort nicht ergänzt. createSolution übernimmt zudem zunächst den negierten MATSim-Score beziehungsweise einen Ersatzwert. Diese Methode wird daher nicht blind als vollständiger HAGRID-Warmstart verwendet. jsprit 2.0 berechnet bei addInitialSolution zwar den Zielfunktionswert neu; fachliche HAGRID-Prüfungen und vollständige Übersetzung der Aufträge bleiben trotzdem erforderlich.

Der HAGRID-Adapter übersetzt Fahrzeugkennungen, Stoppreihenfolge, Services sowie getrennte Shipment-Abholungen/-Lieferungen, Abfahrten und relevante Zeitfenster. Er verwendet das aktuelle VRP und den festen Reisezeitstand. Vor Verwendung werden Auftragserhaltung, Kapazität, Skills, Zeit-/Schichtgrenzen, offene Touren und Kosten geprüft. Ungültige oder noch nicht unterstützte Startpläne führen mit protokolliertem Grund zum normalen Neuaufbau; es werden keine Aktivitäten still ausgelassen. Der gültige Altplan bleibt unabhängig vom Warmstart als vergleichbarer Übernahmekandidat erhalten.

Suchbudgets erhalten benannte Konfigurationsvarianten: zunächst das feste Referenzbudget; daneben eine experimentelle Regel nach Problemgröße und beobachtetem Verbesserungsverlauf. Sie besitzt Mindest-/Höchstbudget und eine ausdrücklich konfigurierte Abbruchregel. Services, Shipments/Aktivitäten und Fahrzeugprofile werden berücksichtigt. Ein Plateau ist ein Abbruchsignal, kein Optimalitätsbeweis. Gemessen werden beste gültige Kosten, unzugewiesene Aufträge und Aufwand; die Regel wird erst nach dem gepaarten Qualitätsvergleich als bevorzugte Variante gewählt. Das Erstplanungsbudget von einer Iteration bleibt ein eigener Wert.

### 7.2 Gespeicherte Ergebnisse und reproduzierbare Einzelfälle

Für den Hannover-Replanning-Durchgang werden abgeschlossene, unabhängig geprüfte Carrier-Ergebnisse atomar gespeichert. Ein Manifest verbindet sie mit Carrier-/Job-/Fahrzeugdaten, aufgelöster Konfiguration, Softwarestand, Seed, Netz-/Reisezeitkontext, Genauigkeitsregeln und Ergebnisstatus. Nach einem Abbruch können ausschließlich dazu passende Ergebnisse wieder eingelesen und vor Übernahme validiert werden; fehlende oder nicht passende Carrier werden neu berechnet. Ein laufender Solver wird nicht als abgeschlossen gespeichert. Der bestehende Lausitz-Cache wird bei passenden Verantwortlichkeiten weiterverwendet, seine scenarioabhängigen Regime und Gültigkeitsregeln bleiben erhalten.

Die Wiederaufnahme betrifft die Tourenoptimierung und garantiert keinen vollständigen Checkpoint aller MATSim-internen Zustände. Erforderliche Reisezeitprofile werden unabhängig vom inzwischen weitergelaufenen Controller gespeichert beziehungsweise eindeutig aufgelöst. Wird der ursprüngliche Kontext nicht reproduziert, ist das Ergebnis kein gültiger Wiederaufnahme-Treffer. Ein bloßes hadFirstReplanning-Attribut ist kein Ersatz für dieses Manifest.

Optional kann ein einzelner Carrier als Diagnosepaket exportiert und mit demselben Optimierungsdienst erneut gelöst werden. Enthalten sind die vollständigen Problem-/Startplandaten, die aufgelösten Einstellungen und ein überprüfbarer Verweis auf den gespeicherten Routing-Kontext. Gemeinsame Netze und Zeitprofile werden einmal je Kontext abgelegt, nicht pro Carrier dupliziert; ihre Dateikennungen und Fingerprints werden geprüft. Ein solcher Replay startet die isolierte Optimierung, nicht die gesamte Nachfrageerzeugung. Kleine, mittlere, große und problematische Fälle bilden die wiederverwendbare Vergleichssammlung.

## 8. Betroffene Java-Bereiche und Konfiguration

| Bereich | Umbau |
|---|---|
| ZoneBasedTransportCosts und beide HAGRID-Factories | Universeller Adapter, wiederverwendbare Netzberechnung, getrennte Zonen-/Zeitregeln, Kontext und Cache |
| HAGRIDRouterUtils und Solver-Aufbau in CarrierVehicleReRouter | Eine gemeinsame Problem-/Solver-Konfiguration mit benannten Varianten |
| StateIds, Constraints, State-Updater, DepartureTimeReScheduler | Solverlokale Zustände, konsistente fachliche Regeln und geprüfte Abfahrtsänderungen |
| JspritCarrierTask, CarrierVehicleReRouter, ReplanningStrategies | Gemeinsamer Optimierungsdienst, eindeutige Bearbeitungszustände und kontrollierte Planübernahme |
| HagridConfig, HAGRIDSimulationConfig, ScenarioConfig, direkte Runner-/CLI-Adapter | Gemeinsamer Konfigurationseinstieg, klar abgegrenzte Bereiche, geprüfte Auflösung und Übergang alter Aufrufer |
| HAGRIDSimulationModule, relevante Teile von HAGRIDScenarioBuilder und ByModeCongestedTravelTime | Klare Injektion, feste Reisezeitbasis, gemeinsames Netz und durchgängige Moduszuordnung |
| HAGRIDSimulationRunner/SimulationRunnerUtils, direkte Carrier-Erzeuger und FreightRunComposer | Ablaufprofile, korrekte Eingangsdaten, eindeutige Modulaktivierung und Abschlussausgaben |
| Hannover-CarrierRouter und Router; LausitzFreightPreprocessor und direkte Aufrufer | Gemeinsame Dienste integrieren, Regime erhalten, unnötige Kontextaufbauten entfernen |
| JspritTreadPoolExecutor, CustomFutureTask, GatedCarrierTask und übrige Aufgabenplanung | Typisierte Aufgaben, ein Ressourcenbudget, geordnete Fehlerweitergabe und Abbruch |
| ScoringFunctions, CarrierRoutingMetrics, CarrierRoutingStatusLogger, JspritPlanCache/Schlüssel | Vollständige Ergebnisse, konsistente Kennzahlen und überprüfbare Cache-Gültigkeit |

Bestehende Parameter und Aufrufer werden bei der Migration erfasst. Insbesondere wählt isUsingZones bisher einen Anbieterpfad, während zoneBasedCachingEnabled die tatsächliche räumliche Näherung einschaltet. Diese beiden Bedeutungen dürfen nicht zu einem neuen Flag mit anderem Verhalten verschmelzen. Alte Konfigurationen erhalten eine dokumentierte Abbildung auf die tatsächlichen neuen Einstellungen; unklare Kombinationen werden gemeldet.

Überflüssige Methoden, Klassen und auskommentierte Altpfade werden nach Migration ihrer echten Aufrufer entfernt. Es entsteht keine neue allgemeine Plugin-Architektur und keine zweite Auswahlstruktur aus zahlreichen nahezu identischen Interfaces.

### 8.1 Zusätzliche Befunde aus der Prüfung des gesamten Ablaufs

Die folgenden Stellen wurden am vorhandenen Java-Code und den lokalen MATSim-Aufrufern nachverfolgt. Ein bedingter Fehler bedeutet nicht, dass die fehlenden Produktionsdaten bereits diesen Fall enthalten.

| Befund und Beleg | Anpassung | Erwartete Wirkung |
|---|---|---|
| HAGRIDScenarioBuilder:230–234 überschreibt bei allen zusammengeführten Carriern den Modus mit car, die jsprit-Iterationen mit 40 und die Rechenzeit mit 900.0. Auch CarrierGenerator enthält pauschale car-Zuweisungen. CarrierAgent:116 übernimmt den Carrier-Modus in die tatsächlich gefahrenen Legs. | Modus konsistent aus zulässigen Fahrzeugprofilen bestimmen/validieren; echte Solver-Metadaten erhalten und fehlende Werte als unbekannt kennzeichnen. | Hoch für Korrektheit und Vergleichbarkeit; für Lastenrad-Carrier betrifft dies Optimierung und Simulation, nicht nur Beschriftungen. |
| HAGRIDScenarioBuilder:215–242 lädt zwei Carrier-Dateien, schreibt die Zusammenführung und lädt sie bei :114 wieder über FreightConfig. VehicleTypes werden ebenfalls vorher und über FreightConfig gelesen. CarrierModule selbst verwendet die bereits vorhandenen Scenario-Carriers. | Zusammenführung im Speicher mit denselben Framework-Registrierungen; kombinierte XML als optionales reproduzierbares Artefakt erhalten. Keine behauptete zusätzliche Neuladung durch CarrierModule. | Weniger Start-I/O und einfachere Datenzuständigkeit; Ausmaß messen. |
| Derselbe Builder lädt Hauptnetz und zusätzliche car-/bike-Netze (:95–111); assignZones bearbeitet nur das Hauptnetz. Die Zusatznetze laden dort keine Network-Change-Events-Datei. | Eine geprüfte Netz-/Profilbasis; Modusansichten und Zonen daraus ableiten. Abweichende Eingaben ausdrücklich abgleichen, statt unterschiedliche Netze blind gleichzusetzen. | Hoch für korrekte Modi/Zeitprofile, zusätzlich weniger Aufbau und Speicher. Produktionsdaten müssen zeigen, ob Links/Ereignisse tatsächlich abweichen. |
| Beim Merge werden Carrier über addCarrier eingefügt. Diese MATSim-Methode verwirft bei gleicher ID den weiteren Carrier mit Warnung. | Doppelte Carrier-IDs vor dem Merge als Eingabefehler melden; Job-/Fahrzeugzuordnung und Ergebnisanzahl abgleichen. Keine automatische Zusammenlegung fremder Aufträge. | Verhindert unvollständige Läufe bei kollidierenden Eingaben; kein Nachweis einer vorhandenen Produktionskollision. |
| ReplanningStrategies mischt BestPlan, Abfahrtsmutation und jsprit mit gewichteter Strategieauswahl und verschiedenen Abschaltzeitpunkten. CarrierVehicleReRouter führt zusätzlich eigene Start-/Stop-/Gruppenregeln. | Eine deterministische Steuerung des einmaligen Durchgangs; bewusst gewünschte Abfahrtsoptimierung als eigener begrenzter Schritt vor Endprüfung. | Hoch für vollständige Neuoptimierung und nachvollziehbare Endpläne. |
| createTravelTime erzeugt pro Linkabfrage einen ByModeCongestedTravelTime; die Abfahrts-/Rerouting-Strategie übergibt zusätzlich ausdrücklich Auto-Reisezeiten an CarrierReRouteVehicles. | Modusadapter einmal vorbereiten und denselben universellen Vertrag bei jsprit und anschließendem Routing nutzen. | Weniger Objektaufbau im häufigen Aufruf; korrigiert insbesondere falsche Ziel-Link-Zeiten für andere Modi. |
| CarrierModule startet bei aktiviertem dumpDataAtEnd eine eigene ereignisbasierte Analyse; der optionale HAGRID-Dashboardpfad liest Events/Netz/Carrier nochmals. HAGRIDSimulationModule schreibt zwei Histogramme in jeder Iteration. SimulationRunnerUtils:693–696 erzwingt zusätzlich GC und fünf Sekunden Pause. | Ausgabeintervalle und aktivierte Auswertungen ausdrücklich konfigurieren, benötigte Endausgaben sichern; doppelte Datenarbeit nur nach Gleichwertigkeitsprüfung zusammenführen; Routine-GC/Pause entfernen. | Gering bis mittel für kleine Fälle, bei großen Events-Dateien möglicherweise deutlich; Gesamtanteile messen. |
| Das verwendete Freight-LegHistogram trennt reguläre Population und außerhalb davon erzeugte Fahrer tatsächlich. Die Bezeichnung withoutFreight ist im vorhandenen Hannover-Pfad daher kein belegter Filterfehler. | Filter bei gemeinsamer DRT-Nutzung fachlich prüfen; keine unbegründete Reparatur des bestehenden Hannover-Filters. | Verhindert eine unnötige Verhaltensänderung. |

Der aktuelle Replanner baut das Problem aus Carrier-Aufträgen und übergibt keine bestehende Lösung an jsprit. Die konkretisierte Warmstart-Anbindung und deren Qualitätsvergleich sind in Abschnitt 7.1 beschrieben; der Vorbereitungsaufwand wird dabei separat gemessen.

Die betroffenen Runner werden auf Ablaufzusammenstellung reduziert; Netz-/Carrier-Laden, Optimierung, Simulationsintegration und Auswertung erhalten klare Verantwortlichkeiten. DRT- und Lausitz-Fachlogik wird dabei nicht pauschal in den Hannover-Replanner verschoben.

### 8.2 Gemeinsame, gegliederte HAGRID-Konfiguration

Es gibt einen gemeinsamen Einstieg und eine eindeutig aufgelöste Laufkonfiguration mit kleinen fachlichen Teilobjekten. Der vorhandene gegliederte HagridConfig-Ansatz wird dafür weiterentwickelt. Die vielen überladenen Konstruktoren von HAGRIDSimulationConfig, flache Kompatibilitätsgetter und nochmals in Runnern gesetzte Standardwerte werden schrittweise durch benannte Konfigurationsbereiche und Adapter ersetzt. Ein bloßes Verschieben aller bisherigen Felder in eine noch größere Klasse ist nicht das Ziel.

| Bereich | Inhalt |
|---|---|
| Lauf/Szenario | Gebiet, Konzept, Datum, Seed, Eingaben und MATSim-Konfigurationsreferenz |
| Nachfrage/Versorgung | Vorhandene Erzeugungs-, Anbieter-, Hub-, Merge-/Split-Einstellungen und Annahmen zur Warenverfügbarkeit |
| Fahrzeuge/Modi | Fahrzeugtypen und Kostenprofile, Modus, Kapazität, Höchstgeschwindigkeit und benötigte Netzfreigaben |
| Fachliche Tourregeln | Tour-/Fahrerzeit, Bedienzeiten, Zustellzeitfenster, Fahrzeugverfügbarkeit, ausdrücklich benannte Puffer und Strafterme |
| Netz/Routing | Maßgebliches multimodales Netz, Verkehrsprofile/Ereignisse, Genauigkeit, Routing-Cache und Moduszuordnung |
| Optimierung/Replanning | Erst-/Neuplanungsbudget getrennt, Warmstart, feste/adaptive Suchregel, Startiteration und Carrier je Gruppe |
| Ressourcen | Aktive Solver, interne Threads, Heap-/Prozessreserve und gemeinsame Speicherbudgets |
| Ergebnisse | Ausgabeintervalle, geprüfte Ergebnisablage, Wiederaufnahme und optionaler Replay-Export |

Konzeptspezifische DRT-/Lausitz-Einstellungen bleiben benannte Erweiterungen ihres Szenarioprofils. MATSim behält seine native Konfiguration; ein Adapter überträgt die betroffenen freightspezifischen Werte aus der aufgelösten HAGRID-Konfiguration. Bestehende Fahrzeug-XML-Dateien sind eine Eingabequelle für den Fahrzeugbereich, keine zweite unabhängig gepflegte Kopie derselben Werte. Für diesen Refactor wird kein zusätzliches Konfigurationsformat allein aus kosmetischen Gründen eingeführt.

Die Auflösung folgt einer dokumentierten Reihenfolge: gemeinsame Standardwerte, benanntes Szenarioprofil, ausdrückliche Laufeinstellungen, ausdrücklich angegebene CLI-Überschreibungen. Jeder Wert besitzt einen verantwortlichen Bereich. Vor dem Start werden Einheiten, Wertebereiche, Moduszuordnung und widersprüchliche Kombinationen geprüft; das fertige Ergebnis wird unveränderlich an die benötigten Dienste übergeben und mit der Herkunft der wirksamen Werte gespeichert. Solver und Scoring erhalten die benötigten Teilobjekte; sie lesen keine unabhängigen Konstanten oder veränderlichen globalen Einstellungen nach.

Nutzerpräzisierung am 2026-10-07: Die Hannover-Solvergrenze bleibt bei 25200 Sekunden (7 Stunden); die zuvor geplante Erhöhung auf 27000 entfällt. Das vorhandene HagridConfig.routing.maxRouteDurationSeconds und Scoring stehen bisher auf 27000 Sekunden. Diese Erzeugungs-/Scoringwerte werden als eigene wirksame Regeln dokumentiert; eine spätere Angleichung ist fachlich gesondert zu testen und darf die Solvergrenze nicht still erhöhen. Solver und abschließende Zulässigkeitsprüfung erhalten den gemeinsamen 25200-Sekunden-Wert des Hannover-Profils, sofern der Lauf ihn ausdrücklich überschreibt. Lausitz-Tourbudgets behalten ihre expliziten Werte. Fahrzeit, gesamte Tourdauer und Fahrzeugverfügbarkeitsfenster bleiben verschiedene Begriffe; der bisherige zusätzliche Verfügbarkeitspuffer wird ausdrücklich benannt statt als versteckte Addition fortgeführt.

Zeiten werden intern einheitlich in Sekunden, Distanzen in Metern und Geschwindigkeiten in m/s geführt; benutzerseitige km/h-/Minutenwerte werden am Eingang konvertiert. Alte Namen werden in einer Migrationstabelle auf neue Bereiche abgebildet. Bestehende Batch-/Notebook-/Runner-Aufrufe erhalten während der Migration kompatible Adapter und Hinweise; veraltete Zugänge werden nach Migration ihrer echten Aufrufer entfernt. Ein Test des vollständig aufgelösten Profils belegt, welche Werte bei Initialplanung, Replanning, Prüfung und Scoring tatsächlich ankommen.

## 9. Ressourcen und Fehlerbehandlung

Zusätzliche Nutzerpriorität vom 2026-10-07: Der parallel laufende Valhalla-Lauf soll bis Ende der Woche fertig werden und hat Vorrang. Währenddessen erfolgen HAGRID-Prüfungen klein, seriell und mit begrenztem Heap/niedriger CPU-Priorität; große Produktions-, Parallelitäts- und vollständige Wiederholungsversuche werden zurückgestellt. Fremde Valhalla-Prozesse werden nicht verändert. Die vorläufigen Prüfgrenzen und Speicherreserve stehen im Umsetzungsplan; sie sind keine allgemeine neue Produktionsvorgabe. Vergleiche unter Hintergrundlast erhalten diese Einschränkung ausdrücklich.

Carrier-Parallelität und interne Solver-Parallelität teilen ein begrenztes Budget. Ausgangspunkt ist ein Solver-Thread pro Carrier. Es gibt keine Plankopien für unveränderte Carrier, keine Pools für leere Aufgabenlisten und kein routinemäßiges erzwungenes System.gc().

Jeder Solver besitzt eigene Zustände und einen eigenen Generator nach der festgelegten Seed-Regel. Stabile Aufgabenreihenfolgen und deterministische Gleichstandsregeln ermöglichen einen Vergleich von serieller und paralleler Bearbeitung.

Zeitbudgets und Abbruch sind kooperativ und werden an geeigneten Solver-/Aufgabengrenzen geprüft. Future.cancel(true) allein beweist nicht, dass Berechnungen beendet sind. Nach Abbruch werden keine verspäteten Ergebnisse übernommen; der Controller startet keine weitere Simulation mit noch mutierenden Optimierungs-Workern. Ein harter Prozessabbruch zu einer garantierten Sekunde würde Prozessisolation erfordern und gehört nicht ohne nachgewiesenen Bedarf zum Refactor.

Kennzahlen unterscheiden Kontextaufbau, Snapshot-Speicher, Wegesuche, Cache, Solver-Vorbereitung, Suche, Straßenrouting, Prüfung und gesamte Laufzeit. Gemeinsam genutzte Cache-Kennzahlen werden dem Kontext zugeordnet; pro Carrier werden daraus keine vermeintlich exklusiven Trefferzahlen abgeleitet.

Für den gesamten HAGRID-Lauf werden zusätzlich Eingabeverarbeitung, jede QSim-Ausführung, Event-/Dateiausgabe und nachgelagerte Auswertung getrennt gemessen. Der Schwerpunkt liegt nach Nutzerpräzisierung auf Replanning-Dauer und Speicherbedarf bei beibehaltener Gruppierung und Iterationszahl. Ergebnisdateien vermerken den zuletzt gefahrenen freigegebenen Plan und den zugehörigen Routing-Kontext; Suchkosten und zuletzt gemessene Simulationskosten werden nicht vermischt.

### 9.1 Konkrete Befunde zu Parallelität und RAM

| Stelle im vorhandenen Hauptstand | Befund | Konsequenz |
|---|---|---|
| CarrierVehicleReRouter:84,162,370 | Maximal 16 Carrier pro Gruppe; der neue Threadpool jeder Gruppe verwendet availableProcessors. Bei genügend Kernen können alle 16 Solver gleichzeitig aktiv werden, unabhängig von ihrer Größe. | Gruppengröße beibehalten, gleichzeitige Solver getrennt und speicherabhängig begrenzen. |
| ZoneBasedTransportCosts:634,998–1005 und CarrierVehicleReRouter:370 | getRouter speichert Wegesucher dauerhaft unter numerischen Thread-IDs; spätere Gruppen erzeugen neue Threads. Die Map entfernt Router beendeter Threads nicht. | Lebensdauer an einen begrenzten Worker-/Kontextbestand binden; kein Wachstum mit historischen Thread-IDs. GC kann weiterhin erreichbare Router nicht freigeben. |
| ZoneBasedTransportCosts:423–428 | Jeder Aufruf der Standard-Factory erzeugt eine neue SpeedyALTFactory. Deren vorgesehene Wiederverwendung von Graph und Landmarkdaten wird damit zwischen solchen Aufrufen verhindert. | Unveränderliche Aufbereitung genau einmal je kompatiblem Netz-/Kostenkontext teilen; veränderliche Suchdaten bleiben getrennt. Initialisierung kontrolliert durchführen, damit paralleler Erstzugriff keine Mehrfachaufbauten erzeugt. |
| ZoneBasedTransportCosts:618,1113 | Ein statischer Transportwert-Cache wächst ohne Eintragsgrenze und ohne Freigabe an einer Kontextgrenze. | Begrenzte kontextgebundene Wiederverwendung mit Gesamtbudget über alle Modi/Profile; nicht pro Anbieter ein unkontrolliert vervielfachtes Budget. |
| Hannover Router:397–408 | Der Kommentar beschreibt zunächst nur einen großen Carrier, tatsächlich sind initialLargeConcurrency und desiredLargeConcurrency beide gleich threads. Die Semaphore begrenzt große Aufgaben deshalb nicht zusätzlich zum Pool. | Bestehende Absicht erhalten, Zulassung vor Aufgabenstart anhand des Ressourcenbudgets statt nur CPU-Zahl steuern. |
| Hannover Router:348–363; JspritCarrierTask:getPriority | Eine Sortierung zählt Services und Shipments, die Groß-/Klein-Einstufung zählt nur Services. | Jobs, Aktivitäten, Fahrzeugprofile und gemessene Größenklassen berücksichtigen. Ein Carrier mit vielen Shipments darf nicht fälschlich als klein gelten. |
| Hannover Router:475–507; CustomFutureTask:Konstruktor | Der MAT_SIM_THREAD_POOL-Zweig übergibt eine normale Runnable-Lambda; CustomFutureTask castet sie ungeprüft zu PrioritizedRunnable. Dieser Zweig ist damit fehlerhaft. Die tatsächlich übergebene LinkedBlockingQueue sortiert zudem nicht nach der vorhandenen Comparable-Priorität. | Typisierte Aufgaben mit expliziter Reihenfolge; Fehler im alternativen Offline-Zweig beheben, ohne ihn als Ursache des derzeitigen Replanning-Peaks auszugeben. |
| Hannover Router:395,460,554,656 | Mehrere Worker schreiben in denselben normalen ArrayList-Speicher für routedTimes. | Ergebnisse und Laufzeitstatistik zentral sammeln; zusätzliche Datenrennen aus dem gemeinsamen Ausführungsdienst entfernen. |

Im vorbereiteten Arbeitsbranch existieren bereits Änderungen zu begrenztem Cache, gemeinsamem Graphaufbau und ThreadLocal-Routern. Diese sind gezielt weiterzuverwenden und gegen das gemeinsame RAM-Budget zu prüfen. Ein Limit von einer Million Einträgen je Kontext oder das Wort ThreadLocal allein belegt noch keinen begrenzten Gesamtspeicher. Router-Pool, Worker-Router, Modi/Profile und zwischen Gruppen erhaltene Daten müssen gemeinsam gezählt werden.

Am veröffentlichten jsprit-2.0.0-Quellartefakt wurde zusätzlich geprüft: Jsprit.Builder verwendet standardmäßig einen Thread, baut aber bereits vor der Suche Auftragsnachbarschaften auf. Der Aufruf setzt deren Kapazität auf ungefähr die halbe Jobzahl; JobNeighborhoodsFactory wählt JobNeighborhoodsOptimized mit int-Nachbartabellen. Die Zahl vorbereitender Distanzabfragen und gespeicherter Nachbarschaftseinträge wächst damit für normale Probleme quadratisch mit der Jobzahl; hinzu kommen unter anderem Sortierung und temporäre Objekte. Das begründet größenabhängige Speicherabschätzungen und getrennte Messung von Vorbereitung und Suche. Es ist keine gemessene Aussage über den größten Speicherverbraucher der HAGRID-Produktion. Änderungen an dieser jsprit-internen Struktur bleiben der späteren jsprit-Etappe vorbehalten.

### 9.2 Aufgabensteuerung innerhalb der bestehenden Gruppen

Ein gemeinsamer, begrenzter Ausführungsdienst ersetzt die unterschiedlichen Aufgabenmechanismen in Erst- und Neuplanung. Er besitzt eine feste Obergrenze aktiver Worker und wird für den Lauf wiederverwendet; keine Poolerzeugung bei leerer Gruppe. Die obere Gruppengröße bleibt unabhängig davon bestehen. Zwischen Gruppen sind keine Solver aktiv; deren große Problem-, Zustands- und temporäre Lösungsobjekte sind nicht mehr referenziert. Erforderliche Endpläne bleiben natürlich Teil des Szenarios.

Die Zulassung erfolgt vor dem Aufbau des VRP und des Solvers, nicht erst nach deren speicherintensiver Initialisierung. Wartende Einträge tragen nur Carrier-Kennung und benötigte Metadaten. Es werden keine Aufgaben zuerst in Worker geschickt, die dort alle auf eine Speicher-Semaphore warten und dadurch passende kleinere Aufgaben blockieren. Nach Fertigstellung wird das Ergebnis geprüft/übernommen, die Reservierung freigegeben und ein weiterer passender Carrier derselben Gruppe gestartet. Stabile Auswahlregeln mit Schutz vor dauerhaftem Zurückstellen großer Carrier erhalten eine reproduzierbare Gruppenzuordnung.

Das Speicherbudget reserviert Platz für das bereits geladene MATSim-Szenario, den festen Reisezeitstand, gemeinsame Netzdaten, Caches, Worker-Suchdaten und eine Sicherheitsreserve. Nur der verbleibende Teil steht gleichzeitig aktiven Solvern einschließlich Aufbau, Ergebnisübersetzung und Endprüfung zur Verfügung. Eintragsgrenzen werden anhand gemessener Objektgrößen konservativ in Budgetanteile übersetzt; Heap und Prozessspeicher werden getrennt beobachtet. Ein reiner freeMemory-Wert ist keine zuverlässige Zulassungsregel.

Die anfänglichen Reservierungen stammen aus isolierten kleinen, mittleren und großen Referenzfällen und berücksichtigen Services/Shipments, Aktivitäten und Fahrzeugprofile; sie enthalten Reserve für vorübergehende Allokationen. Bei unerwartetem Speicherdruck werden neue Starts angehalten und die künftige Zulassung konservativer. Ein allein bereits zu großer Carrier wird vorab als nicht unter dem Budget ausführbar gemeldet, statt die übrigen Grenzen still zu umgehen. Die Regel reduziert Überlastungsrisiken; sie garantiert keinen harten Speicherdeckel für einen unbekannten Solverfall. Ein OutOfMemoryError ist kein regulärer Retry-Mechanismus.

Zunächst läuft jeder Carrier-Solver mit einem internen Thread. Für wenige sehr große Carrier wird anschließend eine kleinere Carrier-Parallelität mit zwei beziehungsweise vier internen jsprit-Threads verglichen. Diese Parallelisierung betrifft Einfügeberechnungen, nicht beliebig unabhängig ausführbare Suchiterationen. Beide Ebenen teilen das CPU-/RAM-Budget. Gemeinsame Transportkosten, Constraints und Zustandszugriffe benötigen vorher eine Prüfung auf sichere parallele Verwendung; ausschließlich lesbar gedachte Aufrufe können auch lazy initialisieren. Elternaufgaben dürfen nicht auf Unteraufgaben warten, für die derselbe vollständig belegte Pool keinen freien Worker mehr hat.

Solver-StateManager, Zufallsgenerator und veränderliche Lösungen werden nicht zwischen Carriern geteilt. Router-/ThreadLocal-Daten werden bei Kontextwechsel oder Laufende gezielt freigegeben. Nur ausdrücklich budgetierte Suchdaten dürfen zwischen Gruppen erhalten bleiben; sie müssen zusammen mit dem nachfolgenden QSim-Bedarf in das Laufbudget passen. Pauschales Nullsetzen lokaler Variablen und System.gc ersetzen diese Lebensdauerregeln nicht.

## 10. Nachweise und Abnahme

| Prüfung | Erforderlicher Nachweis |
|---|---|
| Upgrade | Bestehende HAGRID-/Freight-Tests, benutzte IO-Pfade, aufgelöste Abhängigkeiten, ausgeliefertes JAR und veränderter Plan-Cache-Fingerprint |
| Gemeinsame Konfiguration | Reihenfolge von Standards/Profil/Lauf/CLI, Einheiten, alte Zugänge und identische wirksame Regeln in Erzeugung, Initialplanung, Replanning, Prüfung und Scoring; 7-Stunden-Solvergrenze erhalten, bestehende Erzeugungs-/Scoringwerte separat ausgewiesen |
| Diagnosefälle | Regressionen für Bedienzeit/-kosten, Abfahrtsverschiebung, State-ID-Isolation, fehlenden Anbieter, ALT-Untergrenze, Zeitbin-FIFO und Rückwärtszeit |
| Universeller Anbieter | Auto/Lastenrad mit verschiedenen Zeitfunktionen und exklusiven Links; dasselbe Ergebnis über Kostenabfragen und finales Straßenrouting inklusive Ziel-Link |
| Fahrzeuglose Abfragen | Beide Einzelmodus-Probleme verwenden ihr erklärtes Vorbereitungsprofil; kein stiller Auto-/Luftlinien-Fallback |
| Snapshot | Messwert-Reset, folgende Simulationsiteration und spätere Netzänderungen verändern den gespeicherten Stand nicht; alle Carrier verwenden dieselbe Kennung |
| Controller-Übergabe | Echter Replanning-/BeforeMobsim-/Scoring-Ablauf; Snapshot vor Reset, übernommene Touren in QSim und Endexport enthalten denselben freigegebenen Plan |
| Simulierter Modus | Auto- und Lastenrad-Carrier behalten vom Eingangsplan bis zu Fahrer-Legs und gemessenen Events ihren Modus; widersprüchliche oder noch nicht unterstützte Mischflotten werden vorab gemeldet |
| Gemeinsames Netz | Auto-exklusive, Rad-exklusive und gemeinsame Links; zulässige Richtungen und Zugangslinks; Teilnetzimport mit Konfliktdiagnose und ausgegebene Route auf demselben QSim-Netz |
| Lastenradgeschwindigkeit | Konfigurierte Höchstgeschwindigkeit und km/h-Umrechnung, langsamere Linkprofile, Network-Change-Grenzen und Messwert-Fallback; Routing-/QSim-Konsistenz mit benannter Zeitschritttoleranz |
| Gemischte QSim-Fahrten | Auto und Lastenrad auf gemeinsamem Link; dokumentierte Queue-/Überholannahme, Fahrzeugbeiträge zu Kapazität/Speicher, keine nur im Router wirksame Sondergeschwindigkeit |
| Eingangs-/Netzkonsistenz | Keine erfundenen jsprit-Messwerte, kein stiller Carrier-Verlust bei ID-Konflikt; alle Stopps/Routenlinks im QSim-Netz, passende Zonen und zeitabhängige Profile |
| Fahrerplan-Semantik | Start-/Endlink, Abfahrten, Bedienzeiten und gewünschte Behandlung von Zeitfenstern stimmen; insbesondere offene Touren werden durchgängig unterstützt oder vorab als nicht unterstützt gemeldet |
| Parallelität/Cache | Abwechselnde Modi und Profile, gemeinsame und fehlschlagende Abfragen, Cache-Limit und Kontextwechsel; keine Vermischung |
| RAM-Zulassung | Gruppengröße und aktive Solver getrennt; VRP-Aufbau erst nach Zulassung; große/kleine und Shipment-lastige Carrier; kein blockierter Worker-Vorrat durch wartende Zulassungen |
| Speicher über Gruppen | Wiederholte Gruppen nach Aufwärmung ohne Ansammlung historischer Thread-Router, Solver oder Cache-Kontexte; Router-/Cache-Anzahlen bleiben innerhalb der festgelegten Grenzen |
| Aufgabenmechanismen | Kein Runnable-Cast-Fehler, keine parallelen Schreibzugriffe auf ungeschützte Ergebnislisten; Ausnahme/Abbruch gibt reservierte Ressourcen erst nach tatsächlichem Aufgabenende frei |
| Vollständiger Ablauf | Mindestens zwei LSP, beide Modi, zeitabhängige Ereignisse, Bedienzeiten und Zeitfenster; alle Carrier abgeschlossen und der ausgewählte Plan geprüft |
| Fehlerpfade | Fehlender Modus, unzulässiger Kandidat, Abbruch und zu kurze Laufkonfiguration erzeugen eindeutige Ergebnisse statt falscher Erfolgsmarker |
| Planentscheidung | Günstiger gültiger Altplan darf bleiben; schlechter/unvollständiger Kandidat ersetzt ihn nicht; spätere Strategien wählen keinen ungeprüften Altplan |
| Warmstart | Services und vollständige Shipment-Paare, gültige aktuelle Fahrzeug-/Aktivitätszuordnung, veränderbare Touren, neue Reisezeiten und Kosten, begründeter Neuaufbau bei ungültiger Startlösung |
| Adaptive Suche | Feste Referenz und konfigurierte Varianten unter gleichen Eingaben/Seeds; Mindest-/Höchstbudget, Plateau-Regel, vollständige Bedienung und nachgerechnete Qualität |
| Wiederaufnahme/Replay | Abbruch nach abgeschlossenem Carrier, atomare vollständige Ablage, geänderte Eingaben/Konfiguration/Software/Zeitprofile als Miss, kaputtes Paket als Fehler; isolierte Reproduktion desselben VRP |
| Lausitz | Bestehende Regime, Tourbudgets, Auswahlregeln, Cache-Hit/-Miss und Auftragsstatus bleiben korrekt |

Bei festen Iterationsbudgets und Seeds müssen serieller Lauf und begrenzter Parallelbetrieb auf den deterministischen Referenzfällen gleichwertige Ergebnisse liefern. Wandzeit-Abbrüche werden separat geprüft; sie können unterschiedliche tatsächlich ausgeführte Iterationszahlen verursachen.

Reine Strukturänderungen erhalten Transportwerte und fachliche Ergebnisse bei identischen Einstellungen. Fachliche Korrekturen dürfen Ergebnisse verändern und weisen den jeweiligen Grund aus. Alte unzulässige Touren sind keine Qualitätsreferenz.

### 10.1 Laufzeit und Ergebnisqualität

Die Vergleiche umfassen 1.8-Ausgangsstand, reine 2.0-Migration und die korrigierte/refaktorierte Integration. Zusätzlich wird eine korrigierte Referenz ohne räumliche und zusätzliche OD-Zeitnäherung gegen die optionalen Näherungen verglichen.

Für kleine Solver-Benchmarks werden mindestens fünf feste Seeds und wiederholte Messungen nach Aufwärmung verwendet. Eingaben, JVM, Heap, Hardware, Threads, Solver-Budget und Genauigkeit werden festgehalten. Berichtet werden Median und Streuung, Cache-Miss/-Hit getrennt sowie Kontextaufbau und gesamte Laufzeit.

Die Qualität umfasst vollständige Auftragsbedienung, harte Verletzungen, Fahrzeuge, final nachgerechnete Kosten, Strecke und Reisezeitfehler. Bei gültigem Altplan darf die ausgewählte Lösung unter derselben Bewertung nicht schlechter sein. Für heuristische Varianten über mehrere Seeds werden Verbesserungen und Verschlechterungen gleichermaßen gezeigt; Zonenannäherung besitzt keine pauschale Qualitätsgarantie.

Ein schnellerer Lauf gilt nur bei vergleichbarer geprüfter Qualität als Verbesserung. Ein einmaliger Produktionslauf ist ein Pilotnachweis, keine allgemeine statistische Beschleunigungsaussage.

Der Parallelitätsvergleich behält MATSim-Iterationszahl, Gruppenzuordnung, Reisezeitstand, Seeds und Suchbudgets bei. Getestet werden ein, zwei, vier und bei ausreichendem Budget weitere gleichzeitige Carrier mit je einem Solver-Thread. Für große Carrier folgen separat Varianten mit weniger gleichzeitigen Carriern und zwei beziehungsweise vier internen Threads. Keine Variante wird allein für eine Messung über das festgelegte RAM-Budget zugelassen. Warmstart und Ausgabeintervalle bleiben eigene Versuche; eine reine Router-Beschleunigung wird nicht auf den Gesamtlauf hochgerechnet.

Primäre Messgrößen sind Wandzeit einer vollständigen Replanning-Gruppe, abgeschlossene Carrier pro Zeit, maximal beobachteter Heap- und Prozessspeicher, GC-Zeit, Allokationsvolumen, Router-/Cache-Bestand und Speicherverlauf über aufeinanderfolgende Gruppen. Messauflösung und Profiling-Aufwand werden angegeben. Solver-Vorbereitung, Suche, abschließendes Routing und Prüfung werden getrennt erfasst. Einfache Vorher-/Nachher-Differenzen des gemeinsamen Heaps werden bei parallelen Solvern nicht als exklusiver Speicherbedarf eines Carriers ausgegeben; dafür dienen isolierte Kalibrierläufe und geeignete Allokationsprofile.

Ziel ist eine messbar kürzere Replanning-Zeit innerhalb des vorgegebenen Speicherbudgets bei gleicher geprüfter Qualität. Eine reine Speicherverbesserung mit unveränderter Laufzeit wird ebenso ausgewiesen wie ein Zeit-/Speicher-Kompromiss. Es wird weder eine feste optimale Threadzahl noch eine Beschleunigung vorab behauptet.

### 10.2 Produktionsnachweis und verbleibende Datenabhängigkeit

Mit verfügbaren Daten folgen ein repräsentativer Hannover-Lauf und eine Lausitz-Regime-/Cache-Prüfung. Vorgeschriebene und tatsächlich gefahrene Reisezeiten werden insgesamt sowie an Depot-/Engpasslinks verglichen. Netzkapazitäten oder Hintergrundverkehr werden dafür nicht ungefragt verändert.

Die lokale Integration kann mit synthetischen und vorhandenen Referenzfällen abgenommen werden. Die Produktionsaussage bleibt ausdrücklich offen, solange die tatsächlichen Eingaben fehlen oder der Zeitvertrag auf ihnen nicht bestätigt ist. Ein nächster jsprit-Profiling-Schritt darf diese Einschränkung nicht als erledigt darstellen.

### 10.3 Regelmäßige kleine Vergleiche während der Umsetzung

Auf ausdrücklichen Nutzerwunsch werden Laufzeit, RAM und jsprit-Ergebnisse fortlaufend in kleinen aussagekräftigen Tests mit der heutigen Implementierung verglichen. Die Prüfung erfolgt nach zusammenhängenden Änderungen mit möglichem Einfluss auf Verhalten oder Leistung und vor dem nächsten darauf aufbauenden Schritt. Sie wird nicht erst auf die Schlussabnahme verschoben. Reine Dokumentationsänderungen oder einzelne interne Umbenennungen lösen keinen vollständigen Benchmark aus. Nach einem bestandenen Vergleich wird ohne erneute Messung weitergearbeitet, solange keine neue relevante Änderung oder offene Auffälligkeit vorliegt.

Vor der ersten Produktänderung werden der tatsächlich ausgeführte Ausgangsstand, vorhandene uncommittete Patches, Abhängigkeiten, wirksame Konfiguration, Eingaben und Seeds eindeutig festgehalten. Ein Commit allein identifiziert einen veränderten Arbeitsbaum nicht. Die heutige jsprit-1.8-Implementierung bleibt als feste Ausgangsreferenz erhalten. Zusätzlich wird gegen den letzten geprüften Zwischenstand verglichen, damit sowohl der Gesamteffekt als auch die Ursache einer neuen Veränderung erkennbar bleiben. Eine notwendige Aktualisierung der Git-Basis wird separat dokumentiert und erhält einen eigenen Referenzlauf.

| Prüfpunkt | Kleiner gezielter Vergleich |
|---|---|
| Vor Beginn und nach der reinen jsprit-2.0-Migration | Identische kleine VRP-Fälle mit festen Eingaben und Seeds; Vollständigkeit, Zulässigkeit, Kosten, Fahrzeuge, Laufzeit und Speicherbedarf |
| Nach fachlichen Korrekturen oder geänderter Konfiguration | Betroffener Fehlerfall und mindestens ein unveränderter Kontrollfall; alte und neue Regeln sowie ihre Ergebniswirkung ausdrücklich gegenüberstellen |
| Nach Änderungen an Kostenanbieter, Reisezeiten, Zonen oder Cache | Betroffene Routing-Verträge und kleiner Solver-Fall einschließlich abschließender Routenbewertung; relevante Modi und Zeitabhängigkeit einbeziehen |
| Nach Änderungen an Replanning, Planübernahme oder Warmstart | Kleiner integrierter Ablauf mit festem Reisezeitstand und geprüfter Endlösung; Vorbereitungs-, Such-, Routing- und Prüfzeit getrennt erfassen |
| Nach Änderungen an Parallelität oder Datenlebensdauer | Kleine repräsentative Carrier-Gruppe, einschließlich eines größeren oder Shipment-lastigen Falls; mehrere Gruppen für Speicherwachstum, gleiche Aufträge und Suchbudgets |
| Am Ende einer Etappe | Zusammenfassung gegen die feste Ausgangsreferenz und gezielte Wiederholung der von dieser Etappe betroffenen Nachweise; größere Produktionsläufe bleiben eine eigene Abnahme |

Vorhandene geeignete Tests und Benchmark-Werkzeuge werden wiederverwendet. Die Auswahl bleibt klein und richtet sich nach der geänderten Funktion; neue Fälle werden ergänzt, wenn bestehende Fälle deren Wirkung nicht abdecken. Für Aussagen über heuristische Ergebnisqualität und Solver-Laufzeit gelten auch hier die mindestens fünf festen Seeds und wiederholten Messungen nach Aufwärmung aus Abschnitt 10.1. Ein einzelner kurzer Funktionslauf darf zunächst einen Fehler aufdecken, begründet aber noch keine Beschleunigungs- oder Qualitätsaussage. Wandzeit, maximal beobachteter Speicher und Messaufwand werden unter vergleichbaren Bedingungen erfasst; Aussagen über den gesamten Replanning-Schritt benötigen einen entsprechenden integrierten Vergleich.

Zu jedem Prüfpunkt wird ein kurzer Vorher-/Nachher-Bericht mit Bezug auf Code-, Eingabe- und Konfigurationsstand abgelegt: Was wurde geändert? Was ändert sich fachlich gegenüber heute? Wie verändern sich Laufzeit, RAM, vollständige Bedienung, Zulässigkeit, nachgerechnete Kosten und Fahrzeugzahl? Messbare Differenzen werden absolut und relativ sowie mit Streuung angegeben; bei einem Ausgangswert von null entfällt die relative Angabe. Neben dem Gesamteffekt wird die Differenz zum letzten geprüften Zwischenstand gezeigt. Änderungen der Bewertungsregeln werden offengelegt; unterschiedlich definierte Zielfunktionswerte werden nicht direkt als Qualitätsgewinn verglichen. Soweit möglich werden beide Lösungen mit derselben gültigen Bewertung nachgerechnet.

Das Ergebnis darf ausdrücklich schneller, langsamer, besser, schlechter, unverändert oder im Messrauschen liegen. Eine reine Strukturänderung muss keinen Geschwindigkeitsgewinn liefern, soll aber gleichwertige fachliche Ergebnisse erhalten. Eine fachliche Fehlerkorrektur kann berechtigt mehr kosten als ein zuvor unzulässiger Plan. Neue unbegründete Verletzungen, verlorene Aufträge oder auffällige Leistungsverschlechterungen werden vor dem darauf aufbauenden Schritt untersucht und behoben oder als ausdrücklich begründeter Zielkonflikt ausgewiesen. Weniger Sucharbeit oder geringere Genauigkeit wird nicht ohne die zugehörige Qualitätswirkung als Optimierung dargestellt.

Falls ein Vergleich wegen fehlender Daten oder technischer Voraussetzungen nicht möglich ist, hält der Bericht die fehlende Voraussetzung, einen möglichen kleineren Ersatznachweis und die verbleibende Aussagegrenze fest. Nicht ausgeführte Vergleiche gelten nicht als bestanden. Die kleinen Prüfungen bleiben verbindliche Arbeitspakete des späteren Umsetzungsplans und werden bei der Umsetzung in sinnvollen Abständen kurz für den Nutzer zusammengefasst.

## 11. Ergebnis der Gesamtprüfung

| Präzisierung gegenüber dem vorherigen Entwurf | Bedeutung |
|---|---|
| Git-Stand geprüft; Aktualisierung nur bei neuer Basis | Vermeidet unnötigen Rebase und Verlust vorbereiteter Arbeit |
| Herkunft, Zeitpunkt und Lebensdauer des Snapshots konkretisiert | Verhindert wechselnde Randbedingungen und doppelte Anrechnung von Verkehr |
| Universeller Anbieter mit problembezogener Regel für null-Fahrzeug | Vereinfacht die API und erhält die notwendigen Modusunterschiede |
| FIFO, Rückwärtszeit und ALT als eigene Korrektheitsabnahme | Verhindert, dass schnellere Näherungen als exakte zeitabhängige Lösung gelten |
| Geprüfter Altplan darf besser bleiben | Verhindert eine Verschlechterung allein wegen eines neuen Solver-Laufs |
| Abschluss, Auswahl und spätere Planmutationen geregelt | Macht die einmalige Bearbeitung aller Carrier überprüfbar |
| IO-Formate, Konfigurationsmigration und Cache getrennt beschrieben | Verhindert verdeckte Kompatibilitäts- und Vergleichsfehler |
| Kooperativer Abbruch und belastbare Benchmarks präzisiert | Verhindert falsche Timeout- und Laufzeitversprechen |
| Tatsächliche Controller-/QSim-Übergaben geprüft | Legt Snapshot-Zeitpunkt, Worker-Abschluss und gefahrenen Endplan verbindlich fest |
| Carrier-Modus bis zum Fahrer-Leg verfolgt | Erfasst den pauschalen car-Fehler und die Grenze echter Mischflotten; universelle Kosten allein genügen dort nicht |
| Eingangsmetadaten, Mehrfach-I/O und Auswertung einbezogen | Macht den Refactor zu einer Prüfung der gesamten Integration und die Laufzeitbeiträge vergleichbar |
| Iterationsverteilung nach Nutzerpräzisierung erhalten | Optimiert das teure jsprit-Replanning und RAM innerhalb der vorhandenen Gruppen |
| Gruppengröße, aktive Solver und Speicherbudget getrennt | Verhindert, dass viele CPU-Kerne automatisch zu vielen gleichzeitig speicherintensiven Solvern führen |
| Thread-Router, Graphaufbereitung und Cache-Lebensdauer konkretisiert | Beseitigt strukturelle Speicherhalter und ermöglicht kontrollierte Wiederverwendung |
| Interne jsprit-Parallelität als eigener Vergleich | Prüft zusätzliche Beschleunigung ohne verdeckte Vervielfachung der Threads oder ungeprüft geteilte Zustände |
| Konfiguration gegliedert und zur wirksamen Quelle gemacht | Entfernt konkurrierende Konstanten und macht fachliche Änderungen sichtbar |
| Zeitliche Kopplung von Supply und Zustellung abgegrenzt | Erhält das vorhandene Modell ohne ungefragte Zwei-Stufen-Optimierung |
| Warmstart über jsprit-2.0-SolutionSpec konkretisiert | Vermeidet Probleme mit Aktivitätsindizes und gesperrten Initialrouten; Services und Shipments werden geprüft |
| Wiederaufnahme und optionaler Einzel-Carrier-Replay ergänzt | Macht teure Ergebnisse wiederverwendbar und Problemfälle einfacher reproduzierbar |
| Lastenräder bis zum gemeinsamen Netz und QSim beschrieben | Verbindet erlaubte Wege, Fahrzeugobergrenze, Verkehrsprofile und ausdrücklich benannte Dynamikannahmen |
| Kleine Vergleichsläufe innerhalb der Umsetzung verbindlich gemacht | Zeigt fortlaufend Änderungen gegenüber heute und dem letzten geprüften Stand; trennt Laufzeit, RAM, Lösungsqualität und fachliche Korrekturen |

Der zugehörige schriftliche Umsetzungsplan liegt unter docs/superpowers/plans/2026-10-07-hagrid-jsprit-integration.md. Er übernimmt regelmäßige kleine Vergleiche und strukturierte Phasen. Der Nutzer hat anschließend die schrittweise Umsetzung in dieser Sitzung gewählt und den Ressourcenvorrang für Valhalla ergänzt. Diese Dokumentüberarbeitung selbst verändert keine Produktklassen.

## 12. Quellen und lokale Evidenz

- [Geprüfte Git-Basis](https://github.com/TUBS-IVS/HAGRID/commit/afbaf33b72f5273f80ec2c844056618d4b6c4add)
- [Veröffentlichtes jsprit 2.0.0](https://central.sonatype.com/artifact/com.graphhopper/jsprit/2.0.0)
- [jsprit 2.0.0: Threadvorgabe und Aufbau der Auftragsnachbarschaften](https://github.com/graphhopper/jsprit/blob/v2.0.0/jsprit-core/src/main/java/com/graphhopper/jsprit/core/algorithm/box/Jsprit.java)
- [MATSim-Beispiel: Verkehrsbedingungen aus bestehender Simulation übernehmen](https://github.com/matsim-org/matsim-libs/blob/c89719f9140cb2491478f1d445c3647863198bd7/code-examples/src/main/java/org/matsim/codeexamples/network/RunCreateNetworkChangeEventsFromExistingSimulationExample.java)
- [MATSim-Carrier-Optimierung und Konsistenzprüfung](https://github.com/matsim-org/matsim-libs/blob/c89719f9140cb2491478f1d445c3647863198bd7/contribs/freight/src/main/java/org/matsim/freight/carriers/CarriersUtils.java)
- [HAGRID-Factory-Vertrag](https://github.com/TUBS-IVS/HAGRID/blob/afbaf33b72f5273f80ec2c844056618d4b6c4add/hagrid/simulation/src/main/java/hagrid/core/routing/VRPTransportCostsFactory.java)
- [HAGRID-Factory-Implementierung](https://github.com/TUBS-IVS/HAGRID/blob/afbaf33b72f5273f80ec2c844056618d4b6c4add/hagrid/simulation/src/main/java/hagrid/core/routing/ZoneBasedTransportCostsFactory.java)

Lokales Audit-Verzeichnis dieses Chats:
C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/zone-router-audit

Dort liegen integration-audit-evidence-2026-10-05.json unter output/, die beiden Diagnose-Probes, compat-2.0-final.log, compat-2.0-smoke.log und universal-provider-null-vehicle-2.0-evidence.txt. Zusätzliche Vertragsprüfung erfolgte am lokalen TravelTimeCalculator, NetworkRouter, CarrierTimeAndSpaceTourRouter, FiFoTravelTime und JarFingerprint.

Die erneute Prüfung der Laufzeitkette stützt sich zusätzlich auf HAGRIDSimulationRunner, SimulationRunnerUtils, HAGRIDScenarioBuilder, HAGRIDSimulationModule, ReplanningStrategies, CarrierVehicleReRouter, CarrierGenerator und FreightRunComposer. Im lokalen MATSim wurden AbstractController:132–171, EventsHandlingImpl:81–89, TravelTimeCalculator:367–381, CarrierControllerListener:71–81, CarrierAgentTracker:75–80, CarrierAgent:86–151, FreightAgentSource:68–94, CarrierModule:137–152, Carriers:56–60 und das Freight-LegHistogram:162–177 gegengeprüft. Zeilenangaben beziehen sich auf den bei dieser Prüfung vorliegenden Quellstand.

Die RAM-/Parallelitätsprüfung verwendet zusätzlich Hannover Router, JspritCarrierTask, GatedCarrierTask, JspritTreadPoolExecutor, CustomFutureTask, ZoneBasedTransportCosts und SpeedyALTFactory. Die jsprit-Aussagen wurden im lokalen veröffentlichten jsprit-core-2.0.0-sources.jar unter dem benachbarten jsprit-audit-Verzeichnis an Jsprit, JobNeighborhoodsFactory und JobNeighborhoodsOptimized geprüft und der Thread-/Vorbereitungsaufruf am GitHub-Tag v2.0.0 gegengeprüft. Die Tabelle trennt Hauptstand-Befunde von bereits vorbereiteten, noch nicht vollständig abgenommenen Korrekturen im Arbeitsbranch.

Konfigurations-/Warmstart-/Lastenrad-Ergänzungen: HagridConfig.RoutingConfig:381–458, HAGRIDSimulationConfig-Konstruktoren, CarrierVehicleFactory.calculateEndTime, NetworkFilterAndReducer, MatsimJspritFactory:409–449 und :582–594, DefaultLinkSpeedCalculator:47–70, AbstractQSimModule.addLinkSpeedCalculatorBinding, DefaultQNetworkFactory, QueueWithBuffer:609–622 und :1076–1089 sowie TravelTimeCalculator:437–459. Im veröffentlichten jsprit-2.0.0-Quellartefakt: VehicleRoutingAlgorithm.addInitialSolution/verifyAndAdaptSolution und die Klassen SolutionSpec, RouteSpec, ActivitySpec, SolutionSpecMaterializer. Die Warmstart-API wurde zusätzlich mit javap am vorhandenen jsprit-core-2.0.0.jar erfolgreich bestätigt, einschließlich SolutionSpec-Überladung und ActivitySpec.visit/pickup/delivery. Ein integrierter HAGRID-Warmstart ist damit noch nicht ausgeführt.

Selbstprüfung: Einmalige Neuoptimierung und feste Randbedingung sind erhalten; der universelle Adapter ist durchgängig beschrieben; Varianten und Fehlersituationen haben explizite Regeln; Vergleichs- und Produktionsgrenzen sind benannt. Keine Produktänderungen oder neuen Leistungsergebnisse durch diese Dokumentprüfung.
