# Zentrale Solver-Konfiguration: was jetzt geändert wurde

> Historischer Zwischenstand vor der anschließenden Vereinheitlichung: Die getrennte 7,5-Stunden-Regel und der modulare 3,5-Stunden-Standard aus dieser Seite sind inzwischen ersetzt. Aktuell gilt die [gemeinsame konfigurierbare Sieben-Stunden-Regel](2026-10-09-hagrid-gemeinsame-konfiguration.md).

Stand: 9. Oktober 2026. Umsetzung im Arbeitsbranch `codex/zone-router-fixes`.
Diese Ergänzung aktualisiert den bisherigen [einfachen Leitfaden](2026-10-09-hagrid-jsprit-abschluss-einfach.md).
Sie ist keine vollständige Produktionsfreigabe. Codeänderungen liegen im separaten Worktree, noch nicht auf GitHub/master.

## 1. Dein Fund war richtig

Die sieben Stunden waren teilweise noch fest im Router verankert. Obwohl die neue Replanning-Konfiguration bereits existierte,
haben Hannover-Initialplanung und Lausitz-Baseline nicht überall denselben konfigurierten Wert verwendet.
Ein weiterer Fehler: Der allgemeine Konfigurationsresolver übernahm ohne Profil irrtümlich die 7,5 Stunden der Bedarfserzeugung.
Beides ist jetzt behoben. Initialplanung und spätere Neuplanung können dieselbe zentrale Solver-Grenze verwenden.

`HAGRIDRouterUtils.MAXROUTEDURATION` bleibt als **veralteter öffentlicher Kompatibilitätsalias** sichtbar.
Er enthält keinen eigenen Zahlenwert mehr. Aktive Produktverbraucher greifen nicht mehr auf diesen Alias zu.
Alte Aufrufe ohne Konfiguration verwenden den benannten Standard aus `RoutingDefaults`.
Ein zentraler Standard muss weiterhin irgendwo definiert sein; entscheidend ist, dass die tatsächlichen Läufe ihn überschreiben können.

## 2. So stellst du die Grenze ein

Für den Hannover-Scenario-Builder:

```java
ScenarioConfig.builder()
    .solverMaxRouteDurationSeconds(25200)
    .build();
```

Der Builder überträgt diesen Wert sowohl an die Bedarfserzeugung als auch in die erzeugten Simulations-Startargumente.
Damit bleiben die getrennt gestarteten Schritte konsistent. Ohne expliziten Builder-Wert wird die vorhandene Konfiguration geerbt.

Direkt in HAGRID:

```java
hagridConfig.routing().setSolverMaxRouteDurationSeconds(25200);
```

Ein manuell gesetzter Java-Wert überträgt sich **nicht automatisch in einen separat gestarteten Prozess**.
Dafür den Builder verwenden oder dieselbe explizite Startoption setzen:

```text
solverMaxRouteDuration=25200
```

Im gemeinsamen aufgelösten Konfigurationsmodell heißt das Feld `tourRules.maxRouteDurationSeconds`.
Einheit: Sekunden. Vorrang: Standard → Profil → Laufkonfiguration → explizite Startoption.
Für native Lausitz-Läufe werden ungültige, gebrochene oder zu große Sekundenwerte vor Ausgaben/Preprocessing zurückgewiesen.
Widersprüchliche explizite gemeinsame und modulare Grenzen werden ebenfalls abgewiesen.

## 3. Welche Regeln bleiben getrennt?

| Einstellung | Bedeutung und Standard |
|---|---|
| Solver-Tourdauer | 25.200 Sekunden = sieben Stunden; jetzt konfigurierbar. |
| Alte Bedarf-/Scoring-Regel | 27.000 Sekunden = 7,5 Stunden; bestehende fachliche Regel bleibt unabhängig. |
| Modulare Lausitz-Touren | Eigenes bisheriges Profil mit 12.600 Sekunden = 3,5 Stunden; explizite gemeinsame Solver-Grenze überschreibt es. |
| Lastenrad | Konfigurierbare Obergrenze, Standard weiterhin 20 km/h. |
| Verfügbarkeitspuffer für LMD-Wellen | Benannter gemeinsamer Standard von 3.600 Sekunden; noch keine vollständige dynamische Migration aller Puffer-/Bedarf-/DRT-Regeln. |

Die ähnlich klingenden alten Namen sind weiterhin ein Aufräumpunkt.
Die Umstellung des leeren allgemeinen Resolvers von versehentlichen 7,5 auf sieben Solver-Stunden ist eine bewusste Verhaltenskorrektur.
Die eigenständige 7,5-Stunden-Bedarfsregel wurde nicht umgestellt.

## 4. Wo wird derselbe Wert jetzt verwendet?

- Hannover: bestehende Eingangstouren prüfen und Initialpläne lösen.
- Gemeinsamer Replanning-Dienst: dieselbe aufgelöste Tourregel verwenden.
- Lausitz Baseline und modular: Touren lösen, Fahrzeug-Wellenfenster erzeugen und zeitlich einordnen.
- Lausitz-Cache: eine geänderte Grenze gehört zur Eingabeidentität und zur Prüfung wiederverwendeter Pläne.
- Laufmetadaten: die tatsächlich wirksame Grenze als `solver_max_route_duration_s` ausgeben.
- Hannover-Scenario-Builder: denselben expliziten Wert an Erzeugung und erzeugten Simulationsstart weitergeben.

Betroffene Verantwortlichkeiten sind weiterhin getrennt: Konfiguration löst Regeln auf, Solver erzeugt Pläne,
Fahrzeugbau erzeugt Verfügbarkeiten, Cache prüft Wiederverwendbarkeit. Die zentrale Grenze verbindet diese Schritte.
Damit ist diese konkrete Konfigurationslücke geschlossen; daraus folgt nicht, dass jede übrige Zahl im Projekt schon konfigurierbar ist.

## 5. Zusätzlich einen echten Worker-Fehler repariert

Der native MATSim-Helfer `CarriersUtils.runJsprit` ließ seinen selbst erzeugten Threadpool offen.
Das trat nach erfolgreichen Aufrufen und nach Fehlern auf; ein fertig gerechneter Diagnoseprozess konnte deshalb weiterlaufen.
Zwei echte Gegenproben scheiterten vor der Reparatur mit zurückbleibenden Worker-Threads.

Der Helfer schließt seinen eigenen Pool jetzt über Java-21-Ressourcenverwaltung auf beiden Wegen.
Beide Gegenproben bestehen. Auch der getrennte Versionsvergleich beendet sich unter jsprit 1.8 und 2.0 ohne zurückbleibende native Worker.
Suchbudget, Sortierung, Zielfunktion und Tourregeln wurden dafür nicht geändert.
Dies belegt die Worker-Reparatur, keine pauschale RAM- oder Geschwindigkeitsverbesserung aller Szenarien.

## 6. Was wurde tatsächlich getestet?

| Prüfung | Ergebnis |
|---|---|
| Alle 166 erfassten kleinen HAGRID-Testklassen | 1.424 Tests bestanden, keine Fehler, keine übersprungenen Tests. |
| Frisch gebautes Paket, separater Service-/Shipment-Replay | Bestanden; jeweils zwei Iterationen, vollständige Zuordnung, Endkosten 100,25 / 94,0. |
| Ressourcenwächter und Stufenauswahl | 14 + 18 Kontrollen bestanden. |
| Benchmark-Werkzeuge | 31 Kontrollen bestanden; Quellen seit dieser Prüfung unverändert. |
| Alle 92 erfassten nativen MATSim-Freight-Testklassen | 272 Fälle: 244 bestanden, 19 fehlgeschlagen, neun bereits deaktiviert. |

Die native Wiederholung nach der Worker-Reparatur hat dieselben 19 fehlgeschlagenen Testmethoden wie zuvor.
Es wurden keine Referenzdateien oder Kosten-Erwartungen umgeschrieben, um diese Fälle grün erscheinen zu lassen.
Die neun deaktivierten Fälle stammen aus `CarrierPlanXmlReaderV2WithDtdTest`; deren vorhandene Annotation nennt keinen Grund.
Der echte datenabhängige `LmdBaselineEndToEndTest` war vom kleinen HAGRID-Lauf ausgeschlossen.

Alle eigenen JVMs waren geschützt und nacheinander gestartet: eine sichtbare CPU, Maven 512 MiB, Tests/Replay 384 MiB,
mindestens sechs GiB physisch frei. Der breite HAGRID-Lauf beobachtete mindestens 17,96 GiB frei,
der native Freight-Lauf mindestens 16,83 GiB. Die gemessene Launcher-Arbeitsmenge ist kein gesamter JVM-Baum-RAM.
Kurzzeitige CPU-Spitzen werden vom bestehenden Wächter mit fünf Sekunden Toleranz beurteilt; beide Läufe wurden nicht zurückgestellt.
Keine fremden Prozesse verändert. Das Log enthält außerdem GeoTools-Hinweise auf nicht geschlossene JDBC-Datastores;
diese Hinweise sind kein nachgewiesener Fehler der neuen Solver-Grenze und bleiben ein gesonderter Prüfpunkt.

## 7. Warum sind die 19 nativen Fälle offen?

Drei Fälle betreffen Kosten oder Fahrzeugauswahl; 16 vergleichen Ereignisse beziehungsweise gespeicherte Ausgaben.
Ein isolierter Test tauschte ausschließlich die jsprit-Core-/Analysis-Version bei identischen MATSim-Klassen und gemeinsamen Abhängigkeiten:
vier Fälle bestehen unter 1.8 und scheitern unter 2.0. 385 native Klassendateien blieben identisch.
Das belegt für diese vier Fälle einen Versionseffekt, keine Entwarnung für alle übrigen Referenzabweichungen.

Beim Fixkostenfall steigen die beobachteten Kosten von 18,36 auf 20,44, etwa 11,3 Prozent.
In zwei Batterie-/Fahrzeugfällen wird eine große statt zweier kleiner Touren gewählt.
Das ist in diesen kleinen Suchbudgets eine schlechtere Fahrzeugwahl gegenüber der Referenz; eine verletzte Batterie-Reichweite ist damit nicht bewiesen.
Für den einfachen LSP-Fall sind alle fünf Services und eine Tour vorhanden, aber die Straßenereignisse unterscheiden sich.
Die übrigen Referenzfälle brauchen weiterhin einzeln eine Prüfung von Gültigkeit, Kosten und tatsächlicher Auftragsbedienung.

## 8. Laufzeit: inzwischen einen konkreten jsprit-Hotspot eingegrenzt

Die ursprünglichen fünf Zehn-Iterationen-Fälle mit etwa 38,2 Prozent mehr Suchzeit bleiben ein eigener, unveränderter Nachweis.
Ein ergänzender Versuch verwendet 1.000 tatsächliche Iterationen, 16 Services, fünf Startwerte,
je drei frische JVM-Wiederholungen und drei Messungen nach vier Aufwärmungen: 135 Messungen über drei Varianten.

Die freigegebene 2.0-Version benötigt hier im gepaarten Median 35,9 Prozent mehr Suchzeit als 1.8 und 66,3 Prozent mehr Caller-Allokationen.
Ein isolierter Diagnosepatch vermeidet zusätzliche Kostenaufschlüsselungs-Objekte im Service-Insertion-Pfad:
gegenüber der freigegebenen 2.0-Version etwa 15,6 Prozent weniger Suchzeit und 33,5 Prozent weniger Allokationen.
Dabei bleiben die ausgewählten 2.0-Pläne und gemessenen Arbeitszähler in allen fünf Startwerten identisch.
Der Patch liegt ausschließlich im Diagnoseversuch, nicht im installierten Produkt oder als freigegebener jsprit-Fork.

Auch diese Variante bleibt im Median etwa 8,1 Prozent langsamer als 1.8.
Zwischen 1.8 und 2.0 ist die Qualität im Median gleich, in einem Startwert besser; einzelne Pläne sind nicht generell identisch.
Services sind keine Shipment- oder Produktionsabnahme. Die beiden unterschiedlichen Versuche dürfen nicht zusammengemittelt werden.
Allokationen sind kein Peak-RAM. Das ist ein konkreter Optimierungskandidat mit kleinem Nachweis, keine allgemeine Performancezusage.

## 9. Was bleibt im Gesamtplan zu tun?

1. Die 19 nativen Kosten-/Referenzfälle einzeln bewerten und gegebenenfalls reparieren; keine blinde Aktualisierung der Erwartungen.
2. Echte Hannover-/Lausitz-Netz-, Carrier- und Reisezeitdaten bereitstellen beziehungsweise zertifizierte repräsentative Replays aufnehmen.
   In den geprüften konventionellen Eingabeordnern liegen noch keine vollständigen Produktionsfälle.
3. Für mehrbinige fallende gemessene Reisezeiten festlegen: streng zurückweisen oder eine separat konfigurierte konservative FIFO-Hülle.
   Bis dahin keine stille Transformation der Reisezeiten.
4. RAM-Budgets repräsentativ kalibrieren und größere äußere/innere Parallelität sowie B0/B1/B7-Laufzeit-/Qualitätsverteilungen prüfen.
   Das Ende von Valhalla ersetzt weder diese Daten noch eine Kalibrierung.
5. Die 500-Fahrzeuge-/Hintergrundgeschwindigkeiten-Annahme, Scoring und Produktionsausgaben mit echten Fahrdaten abnehmen.
6. Weitere Verfügbarkeits-, Bedarf- und DRT-Konfiguration und die alten ähnlichen Namen systematisch aufräumen.
7. Den eingegrenzten jsprit-Hotspot mit Shipments, zusätzlichen Nebenbedingungen und größeren Fällen absichern, dann eine Upstream-Änderung vorbereiten.
8. GeoTools-Datastore-Hinweise gesondert prüfen und anschließend Git-Abnahme/Integration abschließen. Kein Commit, Push oder Merge in diesem Schritt.

Die [19-Schritte-Checkliste](2026-10-08-hagrid-jsprit-open-checks.md) bleibt die maßgebliche Übersicht.
Der Gesamtauftrag ist wegen dieser offenen Nachweise noch nicht vollständig abgeschlossen.

## 10. Nachweise für spätere Rückfragen

Im Fortschrittsledger `.superpowers/sdd/2026-10-07-hagrid-jsprit-integration`:
K261 zeigt den tatsächlichen Konfigurationsfehler vor der Reparatur; K263/K264 die Builder-Weitergabe;
K270/K271 die Worker-Gegenproben; K273 den abschließenden HAGRID-Lauf; K274 den exakten Paket-Replay;
K275 den wiederholten nativen Freight-Lauf; K276 die aktuelle Dokumentations-/Nachweisprüfung.
Die getrennten Versions- und Hotspot-Nachweise liegen im lokalen Artefaktordner unter `native-freight-version-audit/final-check`
und `core-version-cause-audit/diagnostic/final-1000`.
Das Paket ist über SHA256 gebunden; dessen vollständige Kennung steht im Replay-Manifest und in K276.
Ältere Leitfäden und PDFs bleiben als datierter Nachweis erhalten.
