# HAGRID Fehlerkorrekturen und Bausteinrefactor – Entwurf

> **Verbindliche Nutzerkorrektur während der Umsetzung:** Ein gemeinsamer Masterseed `run.seed` (Default 1337) ersetzt die vorgeschlagenen drei `randomSeeds.*`-Wurzeln und separate produktive jsprit-/LMD-/Shared-Use-Seeds. Benannte, nach fachlicher ID abgeleitete Ströme bleiben unabhängig. `RandomSeedSettings(long seed)` ersetzt den Drei-Parameter-Vertrag. CLI `seed=` bleibt Alias von `run.seed`. Alte LMD-/Shared-Use-Seednamen sind Kompatibilitätsaliase; widersprechende Angaben werden abgewiesen. Historische Legacy-APIs ohne zentrale Konfiguration behalten ihre dokumentierten Defaults. Die nachstehenden ursprünglichen Seed-Beispiele sind durch diese Entscheidung überholt; Tests und Umsetzungsledger folgen der Ein-Seed-Regel. Diese Quellumstellung ist noch nicht Java-verifiziert.

Stand: 10. Oktober 2026. Dieser Entwurf beschreibt die nächste Umsetzung auf Grundlage des Java-Audits. Er dokumentiert die vorgeschlagene Architektur und die fachlichen Entscheidungen; er behauptet keine bereits ausgeführten Korrekturen. Der Nutzer hat die Ausarbeitung dieses Plans beauftragt. Die bisher gewählte Ausführung ist schrittweise und direkt in derselben Sitzung.

Der Plan liegt im normalen Checkout bei den vorhandenen Planungsunterlagen. Seine Produktpfade beziehen sich ausdrücklich auf den Refactor-Arbeitsstand in `C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID`. Nicht versehentlich im älteren Produktcode des normalen Checkouts implementieren.

## 1. Ziel und Erfolg

HAGRID soll dieselben Aufträge, Netze, Zeitfenster, Fahrzeugregeln und Kosten konsistent durch Bedarfserzeugung, jsprit-Planung, MATSim-Ausführung und Auswertung tragen. Jeder der zwölf Auditpunkte erhält einen prüfbaren Abschluss. Die vorhandenen großen Klassen werden dort zerlegt, wo ein Fehler oder unnötige Arbeit eine unklare Verantwortung sichtbar macht.

Erfolg bedeutet:
- Fehlgeschlagene Pflichtschritte erzeugen keinen erfolgreichen Laufstatus.
- Mengen und fachliche Metadaten bleiben bei Merges erhalten.
- Scoring und exportierte Kosten stammen aus derselben abgeschlossenen Berechnung.
- Abbiegeverbote, aktuelle Auftragsfenster und modulare Lieferfenster werden auch bei der tatsächlichen Ausführung berücksichtigt.
- Supply-Ausgaben sind als Baseline wieder nutzbar, einschließlich mehrerer Depotrichtungen.
- Ein anderer Ausgabename verändert bei gleichen expliziten Seeds nicht die fachlichen Eingaben.
- Wiederverwendung und Speicheroptimierungen verändern keine zuvor gültigen Ergebnisse.
- Laufzeit, RAM und Qualität werden getrennt und mit vergleichbaren Eingaben gemessen.

## 2. Gewählte Vorgehensweise

**Empfehlung: schrittweiser Refactor entlang der fehlerhaften Übergänge.** Ein kleines Gegenbeispiel kommt zuerst; danach folgen Korrektur, passende Aufteilung und Prüfung des produktiven Aufrufers. Bestehende Schnittstellen bleiben während der Migration als schmale Adapter verfügbar.

Nur punktuelle Patches wären zunächst günstiger, ließen aber mehrfach vorhandene Merge-, Routing- und Kostenregeln bestehen. Ein vollständiger Neuaufbau aller Klassen auf einmal hätte eine größere Regressionsfläche und erschwerte den Vorher/Nachher-Vergleich. Der gewählte Weg nutzt das vorhandene Konfigurations-, Kontext-, Transaktions- und Ressourcenfundament weiter.

## 3. Globale Vorgaben

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

## 4. Bausteine und Verträge

| Baustein | Verantwortung | Was er nicht nebenbei entscheiden soll |
|---|---|---|
| Konfiguration und Zufallsströme | Einmal aufgelöste Einstellungen, Quellenpriorität und explizite Seeds; eine verständliche Properties-Datei | Keine zufälligen Defaults aus Lauf-Tag oder dynamischer CPU-Abfrage im Solver |
| Pipeline und Netzvorbereitung | Reihenfolge, genau ein Netzeigentümer, eindeutiger Erfolg/Fehler | Fehler nicht nur protokollieren und Erfolg fortsetzen |
| Carrier-Merge | Aufträge und zugehörige Mengen/Fehlzustellungen gemeinsam übertragen | Keine eigenen Routing- oder Scoringregeln |
| Supply-Baseline | Hub, Richtung, Transportrolle, Depot und Mengenanteil erhalten | Mehrere Depots nicht auf einen beliebigen Link reduzieren |
| Ausführungsbeobachtung | Tatsächliche Ereignisse je Carrier/Tour/Fahrzeug erfassen | Keine Kosten buchen und keine unvollständige Fahrt als null Sekunden behandeln |
| Kostenberechnung und Export | Beobachtungen bewerten, unveränderliches Ergebnis einmalig veröffentlichen | `getScore()` verändert keine Daten |
| Routingadapter und Engine | Gemeinsame mode-/fahrzeugrichtige Kosten und legbasierte Straßenwege | Keine fachliche Auftragsmutation |
| Zonenregel und Cache | Endpunkte auswählen; eindeutig passende Ergebnisse begrenzt wiederverwenden | Erreichbarkeit eines Vertreters nicht mit Original-OD gleichsetzen |
| Planprüfung und Auswahl | Aktuelle Aufträge und vollständige Straßenfolge prüfen; gültigen Gewinner wählen | Keine veralteten Jobobjekte und keine bloß nominellen Zertifikate |
| Modularer Scheduler | Stopfenster bewahren; erst gesamte Kette planen, dann Fahrplan ändern | Keine Lieferung außerhalb der offline gültigen Fenster |
| Replanning-Dienst | Schritte koordinieren und Resultate kontrolliert übernehmen | Keine erneute identische Straßenarbeit ohne geänderte Grundlage |

Kein eigener Interface-Typ für jede kleine Funktion: öffentliche Verträge nur an realen Konsumentengrenzen, sonst package-private Klassen und kleine Records. Bestehende `ResolvedHagridConfig`, `RoutingContext`, `CarrierOptimizationService`, `ReplanningTransportCosts` und Transaktionen werden erweitert, nicht durch parallele Frameworks ersetzt.

## 5. Fachliche Entscheidungen

### Konfiguration, Seeds und Netz

Ein kanonischer Schlüssel: `run.seed`, Default `1337`, CLI-Alias `seed`. Derselbe Masterseed versorgt MATSim, konfigurierte jsprit-Suchen, Bedarf, Dispatch, Supply, LMD und Shared Use. Benannte Ströme bleiben voneinander unabhängig. Priorität DEFAULTS < PROFILE < RUN < CLI; signed-long-Validierung und Export einschließlich Ableitungsversion. Alte separate LMD-/Shared-Use-Seednamen sind Aliase, keine unabhängigen Einstellungen; widersprechende Angaben führen zu einer Diagnose.

Stabile benannte Zufallsströme werden aus Root-Seed, Fachbereich und fachlicher ID abgeleitet. Das Lauf-Tag kommt darin nicht vor. Frühere tagabhängige Ergebnisse lassen sich über archivierte Inputs weiter vergleichen; eine neue Seed-Regel darf nicht als derselbe historische Lauf ausgegeben werden.

Für die Hannover-Bedarfspipeline wird die effektive Konfiguration vor dem Szenarioaufbau aufgelöst. Das konfiguriert ausgewählte Netz wird genau einmal geladen. Zwei widersprechende explizite Netzquellen führen vor der Verarbeitung zu einer verständlichen Diagnose, nicht zu einem stillen Wechsel. Normale Simulationsstarts mit weiteren MATSim-Eingaben behalten ihren eigenen vollständigen Szenarioladevorgang.

### Merge und Supply

Ein Merge prüft zuerst ID-Kollisionen und Modalität, führt den Transfer innerhalb der vorhandenen Transaktion aus und aktualisiert danach alle abgeleiteten Attribute. Fehlzustellungen sind eine Multimenge: mehrere fehlgeschlagene Pakete am selben Service dürfen nicht durch ein Set verschwinden. Der geometrische Suchcache lebt nur während eines Merge-Durchlaufs und wird nach einem Transfer invalidiert.

Supply wird über stabile Baseline-Kanäle identifiziert: Hub, Transportrolle und Richtung/Quellcarrier. Neue Ausgaben enthalten explizite Zuordnungen. Bekannte alte Produzentenformate werden deterministisch eingelesen; unauflösbare fremde Zuordnungen werden mit Kontext abgewiesen. Inbound- und Outbound-Transport dürfen nicht vermischt werden.

Für veränderte White-Label-Mengen bleiben die Baseline-Anteile der passenden Kanäle erhalten. Ganze Einheiten werden durch größte Reste verteilt, mit stabilem Kanal-ID-Tie-Break. Gleiche Menge erhält dieselbe Verteilung. Positive Nachfrage ohne zuordenbare positive Baseline-Anteile ist ein Eingabefehler. Diese vorgeschlagene Verteilungsregel wird mit Beispielen dokumentiert; sie führt keine zeitliche Supply-Delivery-Kopplung ein.

### Scoring

Zunächst wird ausschließlich der finish/getScore-Fehler korrigiert. Danach wird die komplette Bewertung an explizite Ausführungsbeobachtungen gebunden. Für Arbeitszeit gilt tatsächliche Tourabfahrt bis tatsächliche Rückkehr, einschließlich Warten und Services. Verschiedene Fahrer/Touren teilen keine Startzeit.

Zeitkosten erhalten die tatsächliche Leg-Dauer aus PersonDeparture/PersonArrival. Distanz folgt den tatsächlich beobachteten LinkEnter-Ereignissen; ein Abfahrtslink wird nicht pauschal zusätzlich voll gezählt. Service- und Zeitfensterkomponenten folgen den bisherigen konfigurierten Tarifen, jetzt mit eindeutiger Tourzuordnung.

Fixkosten folgen der vorhandenen finalen Planbewertung: bei endlicher Flotte einmal pro eingesetztem Fahrzeug, bei unendlicher Flotte pro tatsächlich eingesetzter Tourinstanz. Eine nicht ausgeführte Tour darf nicht als beobachteter Einsatz erscheinen. Diese Angleichung ist eine fachliche Korrektur und erhält einen eigenen Vergleichsnachweis.

MATSim-Score und Geldkosten bleiben benannte unterschiedliche Größen: beispielsweise U-Turn-Nutzen/Strafe darf nicht unbemerkt in eine anders definierte Geldsumme wandern. Alle ausgegebenen Komponenten stammen dennoch aus derselben fertigen Bewertung. Unvollständige Ereignisse oder abgebrochene Touren ergeben einen expliziten unvollständigen Status statt ein scheinbar gültiges Nullergebnis.

### Routing, Validierung und Zonen

Eine Straßenverbindung wird über Start- und Ziellink beschrieben, nicht nur über deren Knoten. Der Startlink ist der Aufenthaltsort; er wird nicht noch einmal vollständig gefahren. Bei verschiedenen Links wird der Ziellink genau einmal berücksichtigt. Derselbe Link ergibt ohne Fahrt Null. Modus, Fahrzeugprofil, Zeitgrundlage und mehrgliedrige Abbiegeverbote gehören zum selben Vertrag.

Der linkbasierte MATSim-Speedy-Einstieg ist ein möglicher Adapter, seine Start-/Zielsemantik muss zuerst mit kleinen Netzen bewiesen werden. Nur die Transportkosten zu korrigieren reicht nicht: auch die Nachrouting-Aufrufe in Optimierung, Rerouter und Lausitz müssen denselben Linkvertrag benutzen. Allgemeine native Kostenanbieter bleiben über einen geprüften Adapter nutzbar; keine Casts auf eine konkrete Zonenklasse.

Planprüfung vergleicht die gespeicherten Aktivitätsdaten mit den aktuellen kanonischen Aufträgen. Bei abweichenden Fenstern, Dauer, Ort, Menge oder benötigten Skills ist ein Altplan ungültig. Es wird nicht teilweise still repariert. Der nächste frische Suchkandidat baut auf aktuellen Aufträgen auf.

Falls ein Zonenvertreter unerreichbar ist, wird exakt zwischen den ursprünglichen Endpunkten gerechnet. Nur echte Nichterreichbarkeit des Vertreters löst das aus, nicht Abbruch, Zeitbereichsfehler oder falscher Modus. Repräsentative und exakte Ergebnisse besitzen unterscheidbare Cacheidentitäten. Die endgültige Straßenroute und Planprüfung sind immer exakt.

Ein geprüfter Kandidat wird für Wiederverwendung gegen mutierende native Planobjekte gekapselt. Sein Nachweis bindet kanonische Eingaben, genaue Planstruktur, Kostenregeln und Routingkontext. Unveränderte Nachweise dürfen innerhalb desselben lebenden Kontexts wiederverwendet werden; nach Änderungen wird neu geprüft. Diese Optimierung kommt nach den Korrektheitsfixes.

### Modulare Ausführung

Jeder Stop trägt frühesten und spätesten Servicebeginn. Warten bei früher Ankunft wird als eigener Zeitabschnitt berücksichtigt; die späteste Startzeit begrenzt den Servicebeginn, nicht automatisch dessen Ende. Dienstende und zentrale Tourdauer begrenzen zusätzlich die vollständige Kette. Es gibt keine teilweise Fahrplanmutation bei später Ablehnung.

Die zusätzliche Idee, nach einem ungeeigneten nächsten Fahrzeug weitere Kandidaten zu versuchen, ist eine optionale Änderung der Dispatchpolitik und kein notwendiger Teil des Zeitfensterfixes.

### Zeitvariable Hintergrundprofile

Konstante Linkmittelwerte bleiben unterstützt. Die bestehenden Ablehnungen nicht unterstützter zeitvariabler Profile werden nicht abgeschaltet. Für reale Tagesprofile wird die unterstützte Kombination aus Datenrepräsentation, FIFO-Eigenschaft, Kostenfunktion und Network Change Events ausdrücklich geprüft und diagnostiziert.

Das Entfernen einer Abnahmeprüfung ist kein Ersatz für einen passenden Algorithmus. Automatische Glättung, Mittelung oder Interpolation würde die fachliche Verkehrsgrundlage verändern und ist nicht implizit Teil dieses Refactors. Falls reale Zielprofile heute nicht unterstützt werden, bleibt die Produktion für diesen Fall gesperrt und ein konkretes Folgepaket mit Profilbeispiel und Modellentscheidung wird festgehalten.

## 6. Auditabdeckung

| Audit | Inhalt | Aufgabe im Umsetzungsplan |
|---|---|---|
| A01 | Pipeline verschluckt Fehler | T02 |
| A02 | Merge verliert Metadaten | T03 |
| A03 | Fixkosten nach Export | T05, T06 |
| A04 | Carrierweite Arbeitszeit | T06 |
| A05 | Kurzstrecken fehlen im Scoring | T06 |
| A06 | Abbiegeverbote an OD-Rändern/Validator | T08 |
| A07 | Veraltete Aktivitätsfenster | T07 |
| A08 | Unerreichbare Zonenvertreter | T09 |
| A09 | Supply-Mehrdepot-Rundlauf | T04 |
| A10 | Modulare Zeitfenster | T10 |
| A11 | Tag verändert Eingaben | T01 |
| A12 | Doppelter Netzimport | T02 |

Auditquelle: `C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/hagrid-java-audit-2026-10-10/hagrid-java-audit.txt`. Die Gegenbeispiele sind statisch begründet, noch keine ausgeführten neuen Java-Reproduktionen.

## 7. Messung und Abnahme

Korrektheitsänderungen können Kennzahlen absichtlich verändern. Deshalb gibt es zwei Bezugspunkte: den dokumentierten bisherigen Stand und einen nach den Fehlerkorrekturen geprüften Stand für reine Performance-Refactors.

Jeder Fix erhält ein deterministisches Gegenbeispiel, die relevanten vorhandenen Regressionstests und mindestens einen Test des realen Konsumenten. Laufzeiten werden nicht mit engen Zeitgrenzen in Unit-Tests geprüft. Wiederverwendung wird zunächst über gezählte teure Aufrufe nachgewiesen.

Vergleiche messen Vorbereitung, jsprit-Suche, Straßenrouting, Prüfung, gesamten Replanning-Abschnitt, beobachteten JVM-Speicher und Ergebnisqualität. Gemessener Prozessspeicher, Heap, Allokationen und erreichbarer Ergebnisgraph sind verschiedene Werte. Keine dieser Größen wird allein zur exklusiven RAM-Zusage pro Carrier.

Für Zonen: exakt, 1500, 3000 und 5000 Meter; gleiche Geometrie, Eingaben, Seed, JVM-Grenzen und tatsächliche Sucharbeit. Die vorbereitete größere Probe muss zunächst kompiliert, geprüft und mit einem kleinen Pilot ausgeführt werden. Der alte 200-Meter-Minifall entscheidet nicht über den Nutzen größerer Schwellen.

Native kleine QSim-Prüfungen müssen jede erwartete Carrier-/Fahrzeugausführung und Jobbedienung nach Replanning nachweisen. Zusammengezählte Modusereignisse allein reichen nicht. Paketprüfung bezieht sich auf den neu erzeugten JAR-Hash.

Die weiterhin offenen nativen jsprit-2.0-Freight-Fälle, reale Produktionsdaten, die 500-Fahrzeuge-Hintergrundannahme und repräsentative Parallelitäts-/RAM-Kalibrierung bleiben sichtbar in der vorhandenen offenen Checkliste. Dieser Plan erklärt sie nicht durch kleine erfolgreiche Tests für erledigt.

## 8. Phasen und Umsetzungsmethode

| Phase | Aufgaben | Ergebnis |
|---|---|---|
| 0 | T01 | Vergleichbare Eingaben und gesicherter Ausgangsstand |
| 1 | T02–T04 | Verlässlicher Ablauf, konsistente Carrier- und Supply-Daten |
| 2 | T05–T06 | Kosten und Arbeitszeit korrekt, Scoring aufgeteilt |
| 3 | T07–T09 | Aufträge, Straßenrouting und Zonen konsistent |
| 4 | T10 | Modulare Touren halten ihre Lieferfenster |
| 5 | T11–T13 | Kleinere Routingbausteine, weniger Mehrfacharbeit und Netzspeicher |
| 6 | T14–T16 | Reisezeitvertrag, faire Vergleiche, Integration und verständliche Dokumentation |

Ausführung bleibt nativ, schrittweise in derselben Sitzung. Es ist keine neue Wahl eines Ausführungsmodells erforderlich. Beim Übergang in die Implementierung wird dieser konkrete Plan als Arbeitsgrundlage verwendet; bereits erledigte frühere Integrationsschritte werden nicht neu begonnen.

Zu jeder Aufgabe werden Ergebnis, tatsächlich ausgeführte Tests, bewusste Kennzahländerungen und offene Abnahmen im Fortschrittsledger dokumentiert. Ein Commit umfasst nur die geprüften Dateien des Pakets; bestehende fremde oder ältere uncommittete Änderungen werden nicht mit eingepackt.

## 9. Selbstprüfung dieses Entwurfs

- Alle zwölf Auditpunkte sind Aufgaben zugeordnet.
- Fehlerkorrektur, technischer Refactor und optionale Modelländerung sind unterscheidbar.
- Zeitfenster, Fixkosten, Seeds und Supply-Zuordnung sind ausdrücklich entschieden.
- Öffentliche Kompatibilität, native Ereignisreihenfolge und mutierbare Pläne sind als Testgrenzen aufgenommen.
- Keine neue Zwei-Echelon-Logik, kein automatischer Produktionswechsel und keine Aufhebung der Ressourcengrenzen.
