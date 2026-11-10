# HAGRID–MATSim–jsprit: offene Prüfungen

> **Aktualisierung 10.10.2026:** Die folgenden Testzahlen und Paketnachweise vom 09.10. sind historische Belege. Seitdem wurde die Audit-Serie T01–T16 im Quellcode bearbeitet; dieser neue Stand ist noch UNVERIFIED und nicht vollständig abgenommen. Aktueller Überblick und offene Pflichtprüfungen: [Audit und Refactor einfach erklärt](2026-10-10-hagrid-audit-fixes-ergebnisse.md). Die automatische Ressourcenüberwachung bleibt auf Nutzerwunsch ausgeschaltet; Tests behalten den Ressourcenschutz.


**Verständlicher Abschluss und Einstieg:** [HAGRID und jsprit einfach erklärt](2026-10-09-hagrid-jsprit-abschluss-einfach.md) enthält Ablauf, Annahmen, behobene Fehler, Messgrenzen und die offenen Schritte in einfacher Sprache. Lokal geprüfter Implementierungsstand; Produktionsfreigabe und Git-Integration bleiben offen.

Stand: 2026-10-09. Arbeitsbranch: codex/zone-router-fixes.

## Aktueller Stand: gemeinsame Konfigurationsdatei (K277–K306)

**Bedarf, Planung und Scoring verwenden jetzt dieselbe konfigurierbare Sieben-Stunden-Tourregel.**
Die alte unabhängige 7,5-Stunden-Regel ist entfernt; auch modular gilt ohne explizite Sonderregel sieben Stunden.
Die [einfache Anleitung zur gemeinsamen Konfiguration](2026-10-09-hagrid-gemeinsame-konfiguration.md)
erklärt die sortierte Datei, alle Bereiche, Vorrang und Einheiten.

- Eine UTF-8-Datei für HAGRID-eigene Tour-, Scoring-, Supply-, Anbieter-, Fahrzeug-, DRT-, Shared-Use-, Modular-, LMD-, Cache-, Ressourcen- und Pfadeinstellungen.
- Unveränderliche aufgelöste Werte, Dateiprüfsumme und Herkunft; bekannte/prüfbare Schlüssel und CLI-Vorrang.
- Review-Lücken bei wirkungslosen CLI-Werten, Listen, Alias-Priorität, Windows-Dateipfaden, inaktiven Paketregeln und früher Validierung repariert.
- **Frisch 1466/1466 kleine HAGRID-Tests aus 175 inventarisierten Quellen bestanden**, einschließlich Paketbau.
  Zusätzlich besteht genau dieses neue Paket eigene Service-/Shipment-Replays mit Kosten 100,25/94 und zwei tatsächlichen Iterationen.
- Eigene Läufe: ein Prozessor, Maven 512 MiB, Tests/Replay 384 MiB, gemeinsame Sperre, Live-Reserve 6 GiB.
  Kleinster gemessener freier RAM über diesen Paketnachweis und vier Replay-Prozesse: 19.16 GiB.
  Eine frühere Gegenprobe wurde wegen Hintergrund-CPU vor JVM-Start zurückgestellt; keine fremden Prozesse verändert.
- Vroom/Valhalla bleiben vorrangig. Produktion, größere Parallelität, Zeitprofilpolitik, repräsentative Daten und Produktions-RAM-Kalibrierung bleiben offen.
  Die historischen 19 nativen jsprit-2.0-Abweichungen sind weiterhin separat zu bewerten; sie wurden hier nicht erneut freigegeben.

Aktuelles Paket SHA-256: `5DCE49EBC1F2D3EAC9E2D6EF2B675692CE5593B3BB756EDEC43BE4DA07F81769`. Keine allgemeinen Laufzeit-/Qualitätsvorteile aus diesem Refactor behauptet.
Code weiter im Worktree, kein Commit/Push/Merge. Die nachfolgenden älteren Nachträge sind historische Nachweise.


## Historischer Nachtrag: zentrale Solver-Grenze und native Ressourcen (K252–K276)

**Die Konfigurationslücke ist repariert; der gesamte Optimierungsplan bleibt offen.**
Die vollständige einfache Erklärung mit Startbeispielen, Regeln, Prüfungen und offenen Punkten steht in
[Zentrale Solver-Konfiguration](2026-10-09-hagrid-zentrale-solverkonfiguration.md).

- Sieben Solver-Stunden zentral konfigurierbar bis Initialplanung, Replanning, Lausitz-Wellen/Retimer/Cache und Metadaten.
  Scenario-Builder überträgt denselben expliziten Wert an Bedarf und erzeugten Simulationsstart.
  Alte 7,5-Stunden-Bedarfsregel bleibt unabhängig; MAXROUTEDURATION nur veralteter Kompatibilitätsalias.
- Neuer nativer Worker-Befund mit zwei echten Gegenproben repariert: MATSim CarriersUtils schließt seinen eigenen Executor auf Erfolg und Fehler.
- Frisch 1.424/1.424 HAGRID-Tests aus 166 kleinen Quellen bestanden; exakt neues Paket im Service-/Shipment-Replay bestanden.
  Native Freight-Wiederholung: 272 Fälle, 244 bestanden, dieselben 19 Fehlschläge, neun bereits deaktiviert. Keine Referenzen umgeschrieben.
- Vier native Fehlschläge separat kausal auf jsprit 1.8/2.0 zurückgeführt, identische 385 MATSim-Klassendateien.
  Kosten-/Fahrzeug- und verbleibende Ereignisabweichungen brauchen weiterhin individuelle Bewertung.
- Service-Hotspot diagnostisch eingegrenzt: isoliert etwa 15,6 % weniger Suchzeit / 33,5 % weniger Allokation gegenüber Release 2.0,
  gleiche 2.0-Pläne/Arbeitszähler. Kein installierter Fork und keine Shipment-/Produktionsfreigabe.
- Echte Daten, FIFO-Profilpolitik, RAM-Kalibrierung, größere Parallelität, vollständiger B0/B1/B7-Vergleich und Git-Integration bleiben offen.
  Weitere Puffer-/Bedarf-/DRT-Regeln sind noch nicht vollständig dynamisch migriert. Die alten Abschnitte unten sind historische Nachweise.

## Historischer Nachweis: kleine Abnahme und Versionsvergleich (K237–K250)

**Weitere Nachweise abgeschlossen; Produktionsfreigabe und Politik für echte schwankende Reisezeiten bleiben offen.** Produktcode und Standards unverändert. Neue Fahr-/Ladenachweise und Messwerkzeuge im Branch codex/zone-router-fixes:

- **93/93 Integrationsprüfungen:** Vier Carrier mit zwei Besitzerlabels, Pkw/Lastenrad, Services/Shipments, Network Change Event, eine Initialiteration und zwei Replanning-Gruppen. Jeder Carrier fährt in Iteration 2/3/4 auf seiner richtigen Straße und erledigt eigene Aufträge; Pickup vor Delivery und finale Kosten/Score geprüft. Statischer Ein-Bin-Hintergrund auf kleinem Netz; keine neue Zwei-Echelon-/LSP-Geschäftsregel.
- **Sechs Gegenbeispiele reparieren eine Nachweislücke:** Fehlender Carrier/Iteration/Service/Delivery, falsche Reihenfolge/Modus wurden vom alten Test übersehen. Strengerer Test weist alle zurück. Fehler im Testnachweis, kein daraus abgeleiteter Produktions-Fahrfehler.
- **17/17 XML-Ladeprüfungen:** Zwei neue Supply-Shipment-Fälle für Pkw/Lastenrad erhalten vollständige Pläne durch native Eingangsdateien, Scenario-Builder und Merge-XML. Jobidentität, Pickup/Delivery, Typ, Zeitfenster, Dauern, Abfahrt, Score und Suchmetadaten geprüft.
- **Netz/Snapshot:** Vier Event-/Turn-Restriction-Kontrollen, gleiche Netze mit anderer Einfügereihenfolge und tatsächliche Calculator-Profilentstehung ergänzt. Separate NCE-Datei im Scenario-Loader bereits geprüft.
- **Neue Suchvergleichs-Seeds:** Fünf Roots ergeben20 verschiedene Carrier-Startwerte je Jobart; 600 aktuelle/600 Referenzpläne,200 identische Vergleichstripel. Kleine Fälle mit 16 Jobs; wiederholte Gruppen sind keine unabhängigen Stichproben.
- **Isolierter jsprit 1.8/2.0-Vergleich:** Fünf gleiche Inputs, je drei frische JVM-Wiederholungen, zehn tatsächlich ausgeführte Iterationen, identische gemeinsame Abhängigkeiten/Zielfunktion. Ohne HAGRID-/MATSim-Klassen; vollständiger Integrationsvergleich bleibt offen.

**Wichtiger Profilbefund:** K238 scheitert mit 60-Sekunden-Bins an einer echten fallenden gemessenen Reisezeit auf return bei 180 Sekunden. Die strenge FIFO-Prüfung tut das Vorgesehene. Die positive Fahrabnahme verwendet ausdrücklich einen statischen 1000-Sekunden-Bin; das behebt keine allgemeinen mehrbinigen Profile. Noch zu entscheiden: streng ablehnen oder separat konfigurierte konservative FIFO-Ankunftszeit-Hülle. Diese darf Zeiten nur verlängern, muss ihren Fehler ausweisen und eine eigene Cache-/Replay-Identität erhalten. Keine stille Profiltransformation implementiert.

### Messwerte und ihre Bedeutung

K249 ist der **abschließende reparierte Versionsnachweis**: 30 Mess-JVMs plus Compiler erfolgreich. Geladene Core-JAR, alle 16 Jobs, Kapazität, Ort, Depot-Rückkehr, Horizont und gemeinsame Endkosten geprüft. Launcher bindet jetzt ResourceGuard-Prüfsumme; nicht verfügbare Allokationszähler werden -1. Fünf tatsächliche Eingangs-/Nichtüberschreib-Kontrollen bestehen ohne JVMs.

| Seed | Kosten1.8 | Kosten2.0 | Suchzeit2.0/1.8 |
|---|---:|---:|---:|
| 42 | 680 | 740 | 1.719 |
| 43 | 720 | 720 | 1.120 |
| 44 | 720 | 780 | 1.323 |
| 45 | 680 | 720 | 1.382 |
| 46 | 800 | 760 | 1.780 |

Im gepaarten Median braucht 2.0 hier **38,2 % mehr Suchzeit**, mit **5,88 % höheren Kosten** und **26,9 % mehr Caller-Allokationen**. Drei Kostenfälle schlechter, einer gleich, einer besser. Millisekundenzeiten, JIT/Hintergrundlast und kleine Inputs begrenzen die Übertragung. K244 hatte gleiche Kosten, aber anderes Zeitverhältnis 1,883; nicht zusammenmitteln. Allokation ist kein Peak-RAM. Keine pauschale Versionsbewertung/automatische Rückumstellung.

K237: gleiche exakte feste Zehn-Iterationen-Referenz, verschiedene Carrier-Seeds, unabhängig geprüfte ausgewählte Gewinner:

| Jobart | Option | Pipelinezeit-Verhältnis | Endkosten-Verhältnis | Teurere ausgewählte Paare/60 |
|---|---|---:|---:|---:|
| Services | Warmstart | 0,783 | 1,000408 | 15 |
| Shipments | Warmstart | 0,566 | 1,000143 | 21 |
| Services | Adaptiv | 0,707 | 1,000211 | 12 |
| Shipments | Adaptiv | 0,829 | 1,000000 | 6 |

Einzelne Warmstart-Kosten bis 6,48 %/6,74 % schlechter. Adaptiv spart Sucharbeit (Iterationsverhältnis 0,55/0,65); kein gleichbudgetiger Speedup. Kontext-/Referenztouraufbau, CLI-Start und Ausgabe ausgeschlossen. K206 hat andere überlappende Seeds und bleibt historisch. **Defaults bleiben aus.**

### Was jetzt noch erforderlich ist

1. **Zeitprofilpolitik:** strenge Abweisung oder separat freigegebene konservative FIFO-Hülle. Gemessene Zeiten bis dahin unverändert.
2. **Repräsentative Eingaben:** Hannover-/Lausitz-Carrier mit passendem Netz/Zeiten oder zertifizierte Replays. Keine vollständigen Fälle in den geprüften konventionellen Input-/Outputordnern gefunden.
3. **RAM-Kalibrierung/Produktionsbudget:** Carrier und gemeinsamen MATSim-/Netz-/Cache-/Ergebniszustand sowie physische/native Reserven getrennt messen. Mini-Captures sind keine Produktionsschätzung.
4. **Nach Valhalla größere Versuche:** äußere/innere Parallelität, reale Gruppen, Hannover/Lausitz und die 500-Fahrzeuge-/Hintergrundgeschwindigkeiten-Annahme. 7-h-Grenze und konfigurierbare Radobergrenze bleiben.
5. **Vollständiger B0/B1/B7-Integrationsvergleich/Git-Abnahme:** gleiche Regeln, echte Laufzeit-/RAM-/Qualitätsverteilung, Scoring/Ausgaben. Danach jsprit-interne Hotspots/Suchstrategien.

Alle eigenen JVMs: eine CPU, Test/Probe 384 MiB, Maven 512 MiB, Live-Reserve 6 GiB. K246 mindestens 9,92 GiB frei/maximale gesamte CPU 77,02 %; K249 mindestens 10,66 GiB frei, alle 31 kurzen Prozesse ohne CPU-Laufzeitstichprobe, gültige Vorabmessungen. K247 wurde vor der nächsten JVM wegen Startreserve zurückgestellt und bleibt unvollständig erhalten; K249 frischer vollständiger Nachweis. Keine fremden Prozesse verändert.

Nachweise: tools/benchmarks/results/k237-distinct-seeds-paired-20261009 und k249-core-version-final-paired-20261009; Prüf-JSONs/Logs im Fortschrittsledger. Letzter breiter Produktstand K215: 1.362/1.362 plus K216-Paket-Replay; kein Produkt-Neubau/Standardwechsel. Remote master unverändert; main älterer anderer Ausgangspunkt, kein neuer master-Rebase erforderlich. Kein Commit/Push/Merge/Automationswechsel in dieser Runde.

Die 19-Schritte-Tabelle bleibt maßgeblich. Ältere Abschnitte enthalten historische Restlisten. Einfacher Leitfaden/PDF bleiben datierter bisheriger Produktstand; diese Ergänzung enthält die späteren Nachweise.

## Bisherige Ergänzung: Messauswertung und Vergleichs-Seeds (K224–K235)

**Neu umgesetzt:** Speicherprofile lassen sich jetzt streng prüfen und verständlich zusammenfassen. Künftige Suchvergleiche verwenden verschiedene Startwerte für jeden Carrier und getrennte Startwerte für die Referenztour. Zwei Fehler in der Nachweisverarbeitung sind behoben: Ein gleichzeitig entstandener Bericht wird nicht überschrieben; eine während der Prüfung geänderte Manifestdatei wird zurückgewiesen. Fehlende CPU-Stichproben bei kurzen Prozessen werden als fehlend ausgewiesen, eine gültige CPU-Vorabmessung bleibt Pflicht.

**Frisch geprüft:** 31/31 Benchmark-Kontrollen, anschließend nochmals 31/31 in einem separaten Verzeichnis ohne lokale Messordner und ohne Produkt-JAR. Die beigefügten kleinen Testnachweise umfassen etwa 267 KiB und sind synthetische native Fälle. Ein tatsächlicher kleiner nativer Durchlauf prüft alle sechs Varianten (Services/Shipments × fest/Warmstart/adaptiv), 120 aktuelle und 120 Referenzpläne. Alle 40 Eingabe-/Referenztour-Vergleichstripel stimmen überein; mindestens 12,57 GiB RAM frei, maximale beobachtete gesamte CPU 33,78 %. Das ist ein Ein-Seed-Funktionstest mit zwei Jobs, kein neuer Qualitäts- oder Geschwindigkeitsnachweis.

**Speicherauswertung:** Die unveränderten K222-Ein-Job-Nachweise ergeben beobachtete Gesamt-JVM-Heapmaxima von 25,56/25,61 MiB und Prozessspeichermaxima von 115,16/114,18 MiB. Diese Größen sind getrennt und kein exklusiver RAM-Bedarf je Carrier. Der neue Bericht ist eine Prüfung bereits gemessener Daten, keine neue Produktionskalibrierung. Die bisherigen Produktionsstandards und das Produktpaket bleiben unverändert.

**Weiter offen:** echte repräsentative Carrier-/Netzfälle und Reserven kalibrieren → Produktionsbudget anschließen → nach Valhalla größere Parallelität vergleichen → Suchqualität/Hintergrundgeschwindigkeiten/Produktionsausgaben abnehmen → Git-Integration und später jsprit intern untersuchen. Die 19-Schritt-Checkliste unten bleibt maßgeblich.

## Produktstand K215 und frühere Abnahme (K208–K223)

**Die Integration ist weitgehend umgesetzt und lokal geprüft; der vollständige Plan ist noch offen.** Der aktuelle Produktstand K215 besteht mit **1.362/1.362 Tests** aus 158 kleinen Rootklassen einschließlich Paketbau. K216 prüft genau dieses Paket erfolgreich in separaten JVMs mit 384 MiB Heap und einer sichtbaren CPU für vollständige Services/Shipments. Jetzt zusätzlich: gemeinsames Laufbudget vor jeder Gruppe neu beobachten, ohne veralteten Snapshot eines gleichzeitigen Aufrufers. Die drei wichtigen Integrationsbefunde (Worker-Ende, Dateisicherung, Budgetabfrage) sind mit tatsächlichen Gegenproben repariert; die Reparaturen wurden unabhängig im Quellcode bestätigt.

| Bereich | Was jetzt funktioniert | Was noch fehlt |
|---|---|---|
| jsprit2.0/MATSim/Modalität | Gemeinsamer Optimierungsdienst, fester Voriterationsstand, geprüfte Gruppenübernahme; Radmodi mit konfigurierbaren 20 km/h. | Produktionsnachweis der Hintergrundgeschwindigkeiten/500-Fahrzeuge-Annahme und vollständiger Integrationsvergleich; K249 kleiner Versionseffekt erledigt. |
| Cache/Wiederaufnahme | Aktuelle Jobs/Netz/Fahrzeuge/Metadaten und Warmstart-Starttour geprüft. Lausitz zertifiziert die tatsächlich benutzten Ressourcen; alte GOLDEN-/XML-Formate erhalten. | Repräsentative echte Daten. |
| Einzelreplay | Aktuelles Paket: vollständige Services/Shipments, identischer Kontext und Endkosten 100,25/94,0. | Repräsentative größere Fälle. |
| Speicher/Worker | Alle vier Wege teilen den begrenzten Executor und wirksame Thread-/Zulassungsgrenzen. Neuer Laufbudgetvertrag trennt gemeinsame Heapbasis und physische/native Reserven, misst je Gruppe neu und hält Reservierungen bis zur Veröffentlichung. Interne Worker enden tatsächlich vor Freigabe. | Repräsentative Carrier-/Reserve-Kalibrierung, Aktivierung der Produktionsbudgetpolitik und größere Parallelität. Standard seriell. |
| Zonenrouter | Neutraler Kostenvertrag, getrennte Cache-/Topologie-/Fahrzeug-/Workerbausteine. K196: gleiche Zonenregel/Endkosten, kleine Fälle mit 16 Jobs rund 22 %/30 % weniger Optimierungsphasenzeit als vorher. | Produktion. K179 Zone langsamer/teurer als exakter Cache; exakter Standard bleibt. Variable Zonennäherung gesperrt. |
| Warmstart/adaptive Suche | Implementiert/geprüft, Defaults aus. K206-Vergleich mit 16 Jobs abgeschlossen: schneller, aber Services teils teurer; adaptiv weniger Sucharbeit und einzelne Qualitätsverluste. | Repräsentative Qualitäts-/Laufzeitverteilung. |
| Kennzahlen/Ausgaben | Getrennte Phasen/Iterationenzahl, tatsächlich ausgewählter Endplan/MATSim-Score und finale Ereignisse/Pläne/Histogramme. | Repräsentative Scoring-/Ausgabeabnahme. |
| Fehler über Gruppen/Dateien | Offline-Lauf/Erzeuger setzen Pläne/Mengen/Metadaten/Typen zurück. Auch unchecked Wiederherstellungsfehler behalten Original-Sicherungen und unterbrechen andere Wiederherstellungen nicht. | Kein gemeinsamer Dateisystem-Commit bei Maschinen-/Prozessabsturz. |

**Zusätzlich K222/K223:** Messwerkzeug für bereits exportierte zertifizierte Carrier-Fälle fertig und mit vier kleinen Service-/Shipment-Messungen plus neun Abweisungskontrollen geprüft. Gleiche Eingaben/Paketkennung, getrennte Beobachtung von Gesamt-Replay-Heap, Ergebnisgraph, Allokation und GC. Effektive adaptive Grenzen vor jeder JVM geprüft: 21 abgewiesen, erlaubte8 tatsächlich ausgeführt. Die benutzten Captures sind synthetische Ein-Job-Fälle; repräsentative Kalibrierung bleibt offen. K221 wurde wegen CPU-Druck zurückgestellt, anschließend bestand die frische Prüfung. Keine Produktquellen/Defaults seit K215 verändert.

**Neuer Budgetnachweis K214:** 68/68 gezielte Tests. Ein tatsächlicher nativer Zwei-Gruppen-Fall erhält die ersten gültigen veröffentlichten Touren, wenn die gewachsene gemeinsame Basis die nächste Gruppe blockiert. Die Messung erfolgt erst nach exklusiver Gruppenbesitznahme; ein gleichzeitiger zweiter Aufrufer liest kein veraltetes Budget. Abfragefehler erlauben einen späteren neuen Versuch. Die Byte-Werte der Vertragstests sind synthetisch; der optionale JVM-Beobachter erfasst gesamten gemeinsamen Heap einschließlich Garbage, keinen exklusiven Carrier-Peak.

**Messgrenzen:** K196: fünf Seeds, 64 Knoten/224 Links, 16 Jobs/fünf Vierergruppen, 50 Gruppenpaare mit gleichen Endkosten/Fahrzeugen. 22 %/30 % betreffen Carrier-Optimierungsphasen ohne Kontextaufbau/Messinstrumentierung/Veröffentlichung. Der Kontextgraph ist 1.272 Byte kleiner auf diesem winzigen Netz. Die K166-Referenzen mit 16/32 Jobs und Thread-Allokationen sind keine Produktions-Heapkalibrierung.

**Zusätzlich K206:** fünf Root-Seeds, 600 aktuelle/600 Referenzpläne. Warmstart etwa 15 %/18 % weniger Pipelinezeit; Services-Mediankosten 0,69 % höher, Shipments 0,61 % niedriger. Adaptiv etwa 25 %/26 % weniger Zeit durch weniger Sucharbeit, einzelne Kostenverschlechterungen. Nicht nur Mediane: 33/60 Service-Warmstart-Paare schlechter, adaptive 12/60 Service- und 6/60 Shipment-Paare schlechter. Alle vollständigen Gewinner unabhängig validiert. Wiederholungsgruppen und überlappende Carrier-Seeds begrenzen die Stichprobe; Defaults bleiben aus.

**Schutz:** K215 mindestens 11,84 GiB physischer RAM frei, maximale gesamte CPU 64,15 %, kein Schutzabbruch; eine CPU, Maven512/Test384MiB. K213 war zuvor wegen anhaltendem CPU-Druck geschützt abgebrochen worden und zählt nicht als Testergebnis. K214/K215/K216 danach erfolgreich mit unverändertem Live-Schutz. Die 6-GiB-Reserve bleibt verbindlich. Große Szenarien und größere Parallelitätsversuche bleiben wegen Valhalla zurückgestellt.

**Nächste Reihenfolge:** den verfügbaren Laufbudgetvertrag mit repräsentativen Carrier-/Reserve-Schätzungen kalibrieren und produktiv anschließen → kontrollierte Parallelität nach Valhalla messen → Warmstart-/Suchqualität auf vielfältigeren echten Eingaben bestätigen → Hannover-/Lausitz-Produktionsabnahme/getrennten Versionsvergleich durchführen → danach jsprit intern analysieren. Gesamtbranch-Review und kleine K206-Vergleiche erledigt.

Der [Ergebnisbericht](2026-10-07-hagrid-jsprit-implementation-results.md) enthält Messwerte und Aussagegrenzen. Die 19-Schritt-Tabelle weiter unten ist die vollständige Checkliste. Frühere Zwischenstände sind historisch; ihre damaligen nächsten Schritte sind keine heutige Arbeitsreihenfolge.

## Vorheriger Stand: alle Ausführungswege vereinheitlicht, Warmstarts und Kennzahlen (K116–K141)

Der breite kleine Lauf K140 prüfte **1.284 Fälle**: 1.282 bestanden, zwei alte Testannahmen scheiterten. Diese zwei Fixtures sind korrigiert und die betroffenen **63/63 Fälle** bestehen in K141. Das ist noch kein vollständig grüner neuer Gesamtlauf. K140: 151 Rootklassen, 4:44 Minuten, feste JVM1/Maven512/Test384MiB, mindestens **11,45 GiB RAM frei**, maximale gesamte CPU-Auslastung61,77%; kein Schutzabbruch. K141: Auto2/768/768, mindestens12,11GiB frei, Exit0. Produktion bleibt wegen Valhalla zurückgestellt.

| Teil | Jetzt umgesetzt und klein geprüft | Verbleibende Grenze |
|---|---|---|
| Initial-, Replanning- und Lausitz-Ausführung | Derselbe CarrierBatchExecutor übernimmt Zulassung, Worker-Lebensdauer und geordnete Ergebnisannahme. Alte Initial-Threading-Namen bleiben kompatible Bezeichnungen. Router wurde von etwa735 auf390 Zeilen verkleinert; Vorbereitung, Suche und Übernahme sind benannt getrennt. | Standard weiterhin seriell; echte Byte-Kalibrierung und gemeinsame Ergebnis-/Laufbudgets offen. |
| Fehlgeschlagene Gruppen | Keine vorzeitigen totalDeliveries-/Unassigned-/Plan-Metadaten. Ein fehlerhafter Carrier verhindert die Gruppenübernahme. Legacy-Pools werden vor Veröffentlichung geschlossen. | Keine vollständige Rückabwicklung aller Modi/Dateien eines Produktionslaufs behauptet. |
| Services und Shipments | Reine Shipment-Carrier werden nativ gelöst und zählen Mengen korrekt. Gemischte Service-/Shipment-Carrier werden vor Nebenwirkungen mit der nativen Adaptergrenze diagnostiziert. | Keine neue Zwei-Echelon- oder gekoppelte Supply-Politik. |
| Warmstarts | Aktuelle Service-Visits und vollständige Pickup-/Delivery-Paare, unabhängige Prüfung gegen aktuellen Snapshot, freie Optimierbarkeit. Fehlende Jobs/Fahrzeuge oder geänderte Zeitfenster ergeben erklärten Kaltstart. | Standard aus. Verschobene Abfahrten sowie nicht abbildbare Klon-/Mehrfachtour-Fahrzeuge fallen erklärt auf Kaltstart zurück. |
| Adaptive Suche | Optionales explizites Minimum, Maximum und Plateau; unvollständige Lösungen zählen nicht als Qualitätsverbesserung. Tatsächliche Iterationen einschließlich letzter Abbruchiteration werden richtig gezählt. | Standard aus; größere Qualitätsverteilung/Budgetkalibrierung offen. |
| Gemeinsame Kennzahlen | Vorbereitung, Suche, Endrouting und Prüfung getrennt; Kontext, Validierung, tatsächliche Iterationen, Such-/Endkosten, Warmstart-/Abbruchgrund und MATSim-Score separat. Die bisherigen13CSV-Spalten bleiben zuerst erhalten. | Historische Cachephasen bleiben unbekannt; Cache-Ladezeit ist keine Suchzeit. |
| Tatsächlicher Endplan | carrier-routing-outcomes.csv prüft beim regulären Controller-Ende den aktuell ausgewählten Plan erneut und liest dessen MATSim-Score. Ungültige aktuelle Auswahl ist ausdrücklich INVALID_FINAL. | Kosten beziehen sich auf den eingefrorenen Routingkontext; reale Hintergrundkalibrierung bleibt offen. |
| Laufbereinigung | Routine-System.gc und feste Fünf-Sekunden-Pause entfernt. | Kein allgemeiner RAM- oder Laufzeitgewinn allein daraus abgeleitet. |

**Seit K148/K151 zusätzlich lokal geprüft:** Der Einzel-Carrier-Replay exportiert und zertifiziert exakte Jobs/Flotte/Altplan/Einstellungen sowie einen gemeinsamen Netz-/Snapshot-Kontext. Zehn konkrete Fälle bestehen, einschließlich eigenständiger384MiB-/1CPU-JVM, präziser Shipment-Paare, historischer Flottenklone und variablem reinem Zeitkostenprofil mit NCE60.125s. Fehlender/beschädigter Kontext führt zu einer Diagnose. K14842/42gesamt,29,079s, mindestens12,52GiB frei.

Lausitz-Baseline und Modular prüfen nun echte Ganzdatei-Cachetreffer unabhängig gegen aktuelle Nachfrage, Modi, zugelassene Fahrzeuge und gespeicherte Straßen. Die bestehende Baseline-Wellenregel ist ausdrücklich berücksichtigt; allgemeine Flotten-/Zeitregeln bleiben streng. K15166/66gesamt,37,987s, mindestens12,13GiB frei. Die alten GOLDEN-/Bytegleichheitstreffer bestehen unverändert.

**Abgeschlossene Abnahme K152:** 1.302/1.302 Fälle aus153kleinen Rootklassen bestanden, Maven verify/Paketbau erfolgreich; spätere gezielte Korrekturen und aktueller Paketstand siehe heutigen Abschnitt oben. Das produktive LmdBaselineEndToEndTest ist bewusst ausgenommen. Vier alte öffentliche Worker-Adapter und der unbenutzte createRoutingProblem-Helfer sind als veraltete Kompatibilität markiert; kein aktiver Produktionsaufrufer gefunden, APIs bleiben erhalten.
**Fünf gepaarte kleine Warmstart-Vergleiche (K139):** Seeds42–46, jeweils10 tatsächliche Iterationen, gleiche vollständige Endkosten110,0. Median der Optimierung insgesamt: kalt17,127ms, warm20,901ms; Suchphase kalt12,374ms, warm15,742ms. Dieses winzige Beispiel zeigt **keinen Geschwindigkeitsgewinn**; die kurzen Zeiten/JIT-Effekte erlauben keine Produktionsaussage. Kontextaufbau ist hier nicht enthalten; kein exklusiver Carrier-Heap wurde gemessen.

**Nächste Arbeit:** Paketabnahme und breite kleine Regression → echte Budgetkalibrierung/gemeinsames Ergebnisbudget → feste Zonen-/Suchqualitätsvergleiche → repräsentative Produktionsabnahme und Gesamtbranch-Review. Einzelreplay und Lausitz-Ganzdateitreffer sind nun lokal geprüft. Der Gesamtplan bleibt offen.

## Vorheriger Stand: gemeinsamer Carrier-Executor am festen Replanning-Pass (K107–K114)

**568/568 Tests bestanden**, 65 ausgewählte Root-Testklassen und 66 tatsächliche Klasseneinträge inklusive verschachtelter Suite. Schlusslauf K114: BUILD SUCCESS, 58,978 Sekunden, Auto2, Maven/Test je768MiB, mindestens **12,66GiB physischer RAM frei**, höchste gemessene gesamte CPU-Auslastung41,88%, Exit0 ohne Zurückstellung. Die6-GiB-Reserve wurde überwacht. Keine fremden Prozesse verändert. Die anschließende reine Test-Fixture-Bereinigung hat in K115 nochmals 18/18 betroffene Tests bestanden (26,278s, mindestens13,06GiB frei, Exit0). Produktcode nach K114 unverändert.

| Teil | Jetzt umgesetzt und klein geprüft |
|---|---|
| Gemeinsame Ausführung | CarrierBatchExecutor besitzt einen wiederverwendeten begrenzten Pool. Leere Gruppen erzeugen keine Worker. Wartende Metadaten belegen keine Solver-Worker; schwere Problemobjekte entstehen erst in solve. |
| Explizite Reservierung | Ein aufruferseitig budgetierter Byte-Betrag wird vor dem Start reserviert. Große wartende Carrier werden nicht dauernd von kleineren überholt. Übergröße und doppelte IDs scheitern vor jedem Aufbau. |
| Tatsächliches Aufgabenende | Abbruch einer Future gilt nicht als beendeter Worker. Ein noch laufender Worker behält seine Reservierung; bei Ablauf der Wartefrist wird Wiederverwendung verweigert. Freigabe erfolgt erst beim tatsächlichen Ende. |
| Replanning-Verbindung | Der feste Pass übergibt Descriptoren, sammelt fertig geprüfte Ergebnisse auf seinem Controller-Thread und übernimmt erst eine vollständige Gruppe. Snapshot, Carrier-Seeds, alte Planbewertung und zertifizierter Cache bleiben erhalten. |
| Verständlichere Zuständigkeiten | Kontextaufnahme, Reihenfolge, Carrier-Vorbereitung und Gruppenübernahme sind getrennte benannte Methoden. Worker ändern weder Status-Map noch Cache-Trefferzähler noch ausgewählte Carrier-Pläne. |
| Ergebnisgleichheit | Ein kleiner nativer Vergleich mit drei Carriern und gleichem Seed zeigt gleiche geprüfte Kosten bei serieller Ausführung und zwei zugelassenen Workern. Eine ungültige parallele Gruppe übernimmt keinen früheren gültigen Plan. |
| Behobene Abbruchfehler | Worker-eigenes close wird sofort abgelehnt, statt auf sich selbst zu warten. Eine bereits unterbrochene Initialsuche startet keinen VRP-Aufbau mehr. Ein abgebrochener Koordinator meldet CANCELLED statt FAILED. |

**Wichtige Grenze:** Der neue explizite Budgetpfad ist funktional geprüft; die Byte-Werte der kleinen Zulassungstests sind synthetische Reservierungen. Sie sind keine gemessenen Heapgrößen realer Carrier. Der produktive Standard des festen Passes bleibt deshalb seriell. Noch keine automatische höhere Parallelität, kein allgemeiner RAM-Grenznachweis und kein allgemeiner Geschwindigkeitsgewinn.

**Nächste Schritte innerhalb Aufgabe14:**

1. Reservierungen an isolierten Service-/Shipment-Referenzfällen kalibrieren; gemeinsames MATSim-/Snapshot-/Graph-/Cache-/fertiges Ergebnis-Budget und Laufreserve getrennt berücksichtigen. Keine globale Heapdifferenz als exklusiven Carrierbedarf ausgeben.
2. Die verbliebenen Initial-Router-/Legacy-Ausführungswege auf denselben Dienst umstellen; native interne Solver-Threads und äußere Parallelität gemeinsam budgetieren.
3. Laufbezogene verfügbare Ressourcen und die zentrale maxActiveSolvers-Einstellung mit dieser gemessenen Budgetpolitik verbinden.
4. Danach kleine feste Vergleiche und Speicher-Lebensdauer über wiederholte Gruppen; größere Produktion und Parallelitätsmessungen bleiben wegen Valhalla zurückgestellt.

Die Tests allein schließen Aufgabe14 und die Gesamtintegration nicht ab. Zonen-Kennzahlen, gepaarte Laufzeit-/Genauigkeitsvergleiche, variable Zonenprofile, Lausitz-Replay/Warmstart und Packaging-Abnahme bleiben in der bestehenden Liste erhalten.

Log: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k114-batch-final-regression-20261008.log samt Ressourcenprofil. Produktcode danach unverändert. Kein Commit, Push, Merge oder Automationswechsel.

## Vorheriger Stand: neutraler Replanning-Vertrag angeschlossen (K102–K106)

**550/550 Tests bestanden**, ohne Fehler oder Auslassungen. Schlusslauf K106:64 ausgewählte Root-Testklassen,65 tatsächliche Klasseneinträge inklusive verschachtelter Suite; BUILD SUCCESS,1:04Minuten. Auto2, Maven/Test je768MiB, mindestens **12,32GiB physischer RAM frei**, höchste gemessene gesamte CPU-Auslastung42,82%, Exit0 ohne Zurückstellung. Die6-GiB-Reserve wurde durchgehend überwacht; keine fremden Prozesse verändert.

**Jetzt zusätzlich abgeschlossen und klein geprüft:**

- Die drei konkreten Zone-Typabfragen in CarrierRoutingCosts sind entfernt. ReplanningTransportCosts ergänzt den nativen VRP-Vertrag um Vorbereitung vor der Gruppe, tatsächliche Näherungspolitik und Diagnose. Der Replanner kennt keine konkrete Zonenklasse mehr.
- Ein gemeinsam unter mehreren Modi registrierter Anbieter wird genau einmal je Gruppe vorbereitet. Live-Zonenanbieter beginnen eine neue Cache-Generation; eingefrorene Anbieter behalten ihren Snapshot und bereits berechnete Werte. Einzelne Carrier setzen weiterhin keinen gemeinsamen Cache zurück. Geschlossene Kontexte scheitern vor der Arbeit.
- Räumliche Näherung wird anhand der tatsächlichen Provider-Einstellung gemeldet. Zuvor war schon der Klassenname ausreichend, sodass ein ausgeschalteter Zonenanbieter fälschlich als Zonenrouting gezählt wurde; K102 belegt diesen Fehler.
- Diagnose ist auch für den universellen eingefrorenen Anbieter angeschlossen. Plain-native VRP-Anbieter bleiben ohne Zusatzvertrag nutzbar. Ein Architekturtest verhindert neue konkrete Zone-Abhängigkeiten in den Simulationsverbrauchern.
- Request-basierte Factory-API ist als Hauptweg dokumentiert. Parameterlose Auto- und Map-Varianten sind als veraltet ohne Entfernung markiert; ihre bisherige Live-Funktion bleibt für rohe historische Konstruktoren erhalten. Der aufgelöste feste Replanning-Pass benutzt bereits RoutingRequest-Kontexte. Es wird kein Live-Profil stillschweigend durch einen eingefrorenen Stand ersetzt.

Die neue Test-Fixture vergaß zunächst das native Carrier-Modusattribut, dessen Default car ist; dies wurde nach Quellprüfung in der Fixture korrigiert, nicht durch einen Produkt-Workaround. K10581/81 und K106550/550 anschließend grün.

**Nächster struktureller Schritt:** gemeinsamer Ressourcen-/Batch-Dienst (Aufgabe14), der Solver erst nach RAM-Zulassung aufbaut, parallele Aufgaben begrenzt und Abbruch/Ergebnisannahme kontrolliert. Dazu gehört der noch erhaltene direkte Legacy-Thread-Router. Danach vollständige Kennzahlen und gepaarte Genauigkeits-/Laufzeitvergleiche des Zonenrouters; übrige Lausitz-Treffer-/Replay-/Warmstart-Aufgaben bleiben erhalten. Räumliche Näherung zeitlich variabler Profile, produktive Hintergründe und große Vergleichsläufe sind weiterhin offene Abnahmepunkte.

Log: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k106-routing-contract-final-regression-20261008.log, Ressourcenprofil daneben. Nach dem grünen Lauf nur Dokumentation geändert. Kein Commit, Push, Merge oder vollständiger Integrationsabschluss.

## Vorheriger Stand: Zonenrouter-Aufteilung (K90–K101)

Der vom Nutzer erneut bekräftigte vollständige Umbau von ZoneBasedTransportCosts gehört zu Aufgaben 6, 7 und 18. Er ist ein eigener priorisierter Arbeitsblock; erfolgreiche Prüfungen des gemeinsamen Anbieters allein schließen ihn nicht ab.

**Frischer gemeinsamer Schlusslauf K101: 542/542 Tests bestanden**, ohne Fehler oder Auslassungen. 63 ausgewählte Root-Testklassen, 64 tatsächliche Klasseneinträge einschließlich einer verschachtelten Suite; BUILD SUCCESS, 1:03 Minuten. Enthalten sind die Carrier-/Initial-/Replanning-/Supply-/Merge-/echten kleinen QSim-/Cache-Prüfungen aus K94 sowie die neuen Komponentenprüfungen. Auto2, Maven/Test je768 MiB, mindestens **13,73 GiB physisch frei**, höchste gemessene gesamte CPU-Auslastung52,18%, Exit0 ohne Ressourcen-Zurückstellung. Der Schutz der6-GiB-Reserve blieb aktiv. Kein fremder Prozess verändert; keine Valhalla-Stabilitätszusage.

| Baustein | Jetzt umgesetzt und klein geprüft |
|---|---|
| Räumliche Zonenwahl | ZoneRoutingApproximation besitzt Erreichbarkeitskomponenten, stabile Repräsentanten und Distanzschwelle. Vorbereitung einmal pro gefiltertem Modusnetz; ausgeschaltet oder ohne mehrere Zonen entstehen keine Topologie-Indizes. |
| Cache-Lebensdauer | GenerationTransportCache besitzt begrenzte fertige Einträge, gemeinsame laufende identische Anfragen sowie kumulierte und periodische Kennzahlen. Ein altes laufendes Ergebnis kann nach Reisezeitwechsel den neuen Cache nicht befüllen; Fehler bleiben wiederholbar. Native öffentliche Zähler bleiben kompatibel. |
| Router-Ausleihe | LegacyRoutingWorkers besitzt Ausleihe/Freigabe und Fehlerbehandlung. Gleichzeitige Nutzer teilen keinen belegten Router. Der historische Thread-Fallback bleibt ausdrücklich sichtbar; dies ist kein neuer globaler RAM-/Solver-Zulassungsdienst. |
| Fahrzeuge und Kosten | LegacyRoutingVehicle übernimmt Modus/Geschwindigkeit/Typidentität. VehicleRoutingDisutility besitzt unveränderliche variable Kostenraten; TollRoutingDisutility ergänzt Maut am tatsächlich befahrenen Link zur Eintrittszeit. Der öffentliche verschachtelte Fahrzeugadapter bleibt erhalten. |
| Lesbare Einstellungen | Builder nach Reisezeit/Ziel, räumlicher Näherung, Cache/Router und Maut geordnet; alte Methoden und Defaults bleiben. Die Hauptklasse sank in diesem Block von1.077 auf526 Zeilen; die sechs Komponenten haben jeweils33–126 Zeilen einschließlich Lizenzkopf. |
| Zentraler Zonenschalter | ModalTransportCosts verwendet nun routing.zoneBasedCachingEnabled und zoneBasedCachingThresholdMeters tatsächlich. Repräsentantenwerte teilen den vorhandenen begrenzten Cache je Fahrzeugprofil. Straßenrouting und unabhängige finale Tour-/Zeitfensterprüfung verwenden die wirklichen Links. |

**Tatsächlich korrigiert:** Der gemeinsame eingefrorene Anbieter ignorierte zuvor die vorhandene zentrale Zonenkonfiguration. Die Gegenprobe K92 schlug deshalb fehl; eingeschaltete konstante Profile verwenden jetzt die Näherung. Nicht nachgewiesene räumliche Näherung bei zeitlich variablen Profilen wird ausdrücklich abgewiesen. Ohne Schalter, unter der Schwelle, bei fehlender Zuordnung oder ungeeigneter Erreichbarkeit wird exakt geroutet. Ein kleines Kontrollbeispiel benötigt eine statt zwei Wege-Berechnungen; daraus folgt noch kein allgemeiner Laufzeit- oder Qualitätsgewinn.

**Für den vollständigen Abschluss dieses Blocks noch offen:**

1. Konkrete Zone-Typabfragen in CarrierRoutingCosts durch einen passenden neutralen Lebensdauer-/Diagnosevertrag ersetzen und verbleibenden Map-/Legacy-Factory-Verbraucher sauber anschließen. Live-Reisezeiten/Maut dürfen dabei nicht stillschweigend durch einen eingefrorenen Kontext ersetzt werden.
2. Die bislang absichtlich erhaltenen direkten Thread-Router und übrigen Ausführungswege mit dem gemeinsamen Ressourcen-/Executor-Dienst abstimmen. Die begrenzten Frozen-Worker und der Legacy-Fallback sind aktuell unterschiedliche Verträge.
3. Gemeinsam dokumentierte Kennzahlen für exaktes Routing, räumliche Näherung und endgültige Straßen-Touren fertig anschließen; Näherungsqualität, Laufzeit und RAM mit festen Eingaben und mindestens fünf gepaarten Seeds belegen. Große Läufe bleiben während Valhalla zurückgestellt.
4. Produktive Zeitprofile und fachliche Politik für nicht-FIFO/gemischte variable Ziele abnehmen. Der explizite Fehler bei unbewiesener variabler Zonennäherung ist eine erkennbare Grenze, keine abgeschlossene zeitabhängige Lösung.

Log: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k101-zone-refactor-final-regression-20261008.log; Ressourcenprofil daneben. Nach diesem grünen Lauf keine Produktquelle geändert. Kein Commit, Push, Merge oder Gesamtabschluss.

## Vorheriger Stand: breite kleine Abnahme und weitere Cache-Bereinigung (K85–K89)

**1.212/1.212 Tests bestanden**, ohne Fehler oder Auslassungen, in zwei frischen geschützten Läufen: K88 1.196 Fälle aus 144 Root-Testklassen (183 Klasseneinträge einschließlich verschachtelter Suiten), K89 weitere 16 Fälle aus zwei Klassen. Damit sind alle 146 ausgewählten Testklassen der HAGRID-Testquellen geprüft. Nur LmdBaselineEndToEndTest mit echten bereitgestellten Daten und Überschreiben dauerhafter Produktionsausgaben wurde zurückgestellt. Die synthetischen DRT-/SharedUse-/Modular-/kombinierten Freight-Controllerläufe sind enthalten.

K88: BUILD SUCCESS, 4:11 Minuten, Auto2, Maven/Test je 768 MiB, mindestens **13,23 GiB** physischer RAM frei, höchste gemessene gesamte CPU-Auslastung 62,93 %, Exit 0 ohne Zurückstellung. K89: 14,827 Sekunden, mindestens **14,15 GiB**, maxCPU 30,45 %, Exit 0. Zusätzliche eigene JVMs dieses zweiten Laufs erhalten über ausschließlich im eigenen Startprozess gesetzte JAVA_TOOL_OPTIONS maximal 384 MiB Heap/einen Prozessor; die expliziten Maven-/Test-Parameter bleiben bestehen. Kein Speicherwert beschreibt den gesamten JVM-Baum. Keine fremden Prozesse verändert; kein Produktions-, Speedup-, Qualitäts- oder Valhalla-Stabilitätsversprechen.

**Jetzt zusätzlich umgesetzt:**

- Optionaler Ergebnis-Cache fällt bei nicht reproduzierbar hashbaren Metadaten oder fehlender Code-Kennung mit Diagnose auf die reguläre Suche zurück. Ein tatsächlich fehlerhafter Request scheitert weiterhin. Die Neuplanung wird unabhängig geprüft, unveränderte Aufträge/Metadaten und Gleichheit der Endkosten gegenüber frischer Suche sind getestet. Bestehende fertige Archive bleiben erhalten.
- JAR-Kennung nach core.util.CodeFingerprint verlagert: gleiche Streaming-Hash-Regeln, IDE-Cache weiterhin deaktiviert, bestehender Lausitz-Adapter auf 20 Zeilen verkleinert. Der gemeinsame Kern greift dafür nicht mehr in das Lausitz-Paket. Legacy-Testoverride bleibt lokal und kann den Kerncache nicht aktivieren.
- Veraltete Reflection in neun Bedienzeit-/Kostenprüfungen durch das tatsächlich ausgelagerte Kostenmodell ersetzt; Assertions für Service, Pickup, Delivery und getrennte Warte-/Servicekosten erhalten. Die Architekturregel bleibt streng; ihr Switchboard-Eintrag folgt der bereits ausgelagerten tatsächlichen Konfigurationsklasse.
- Tatsächliche Gegenproben K86: Cache-Metadaten/Kennung brachen vorher gültiges Replanning ab, Kern hatte eine unerlaubte Studienabhängigkeit. Beide Fehlergruppen jetzt grün. Fehlende Hook-Zuordnung und alte kompilierte Testklasse beim Verschieben waren getrennt protokollierte Test-/Build-Probleme.

### Der gesamte Plan in 19 Schritten

„Lokal geprüft“ bezeichnet die kleinen tatsächlichen Tests; Produktions- und Vergleichsgates bleiben separat. Die grundlegenden Regeln bleiben: sieben Stunden Solvergrenze, Radlimit standardmäßig 20 km/h konfigurierbar, ein einmaliger Replanning-Pass mit festem Voriterationsprofil.

| Nr. | Arbeitspaket | Stand und verbleibender Schritt |
|---|---|---|
| 1 | Ausgangspunkt und Vergleiche | Fünf gepaarte Seeds für exakten/Zonenvergleich und strukturellen Indexvergleich abgeschlossen. Vollständiger B0/B1/B7-Integrationsvergleich und repräsentative Fälle offen. K249 kleiner isolierter Core-Versionseffekt bei gleichem tatsächlichem Budget fertig; vollständiger B0/B1/B7-Integrationsvergleich offen. |
| 2 | Java/MATSim/jsprit 2.0 | Integration, Kompatibilität, Paketbau und tatsächlicher eigenständiger Paket-Replay lokal geprüft; Produktion offen. |
| 3 | Gemeinsame Konfiguration | Gemeinsame Datei, sieben Stunden für Bedarf/Planung/Scoring und modular, tatsächliche Supply-/DRT-/LMD-/Cache-Verbraucher, Pfade und Prioritäten geprüft. K277–K306 samt frischem Paket-Replay. Native XML bleibt maßgeblich; Produktionsbudget-Kalibrierung offen. |
| 4 | Solverzustände, Bedienzeiten, Regeln | Lokale Zustands-/Kosten-/Grenzprüfungen bestanden; produktive Regimen ebenfalls abnehmen. |
| 5 | Snapshot und Routing-Kontext | Fester Voriterationsstand und Lebensdauer geprüft; echte zeitlich schwankende Daten noch freigeben. K240 echte Calculator-Profilentstehung geprüft; K238 zeigt fallende Mehrbin-Zeiten. Profilpolitik offen. |
| 6 | Gemeinsamer Kostenvertrag | Universeller Request-Anbieter, Zonenbausteine und neutrale Diagnose-/Lebensdaueranbindung lokal geprüft. Keine konkreten Zone-Abhängigkeiten im Replanner; veraltete Live-Map-API nur für historische Kompatibilität erhalten. |
| 7 | Zeitvertrag, Cache und Zonennäherung | Kontextgebundene begrenzte Caches/Generationen und konstante Zonenschalter geprüft. K179 Zone schlechter als exakt; K196 neuer Zonenindex schneller als alter bei gleichen Endkosten. Variable Zonennäherung gesperrt, Produktion offen. |
| 8 | Netze, Flotten, Supply, Merges | Modal-/Depot-/Kapazitäts-/Mengen-/Fensterprüfungen und vorhandene reine Shipment-Eingaben geprüft. Offline-Lauf und Erzeuger mit Rückabwicklung und vorbereiteten Dateisätzen geprüft. Eine neue Shipment-Erzeugungs-/Zwei-Echelon-Geschäftsregel gehört nicht zum freigegebenen Refactor. Große echte Eingaben bleiben Produktionsabnahme. K250 komplette vorhandene Shipment-Pläne für Pkw/Lastenrad durch XML-Builder/Merge geprüft. |
| 9 | Reale kleine QSim-Fahrten | 18/20 km/h, langsamer Link, NCE und gemeinsamer FIFO-Link geprüft; reale Netzkalibrierung und 500-Fahrzeuge-Annahme offen. K246 alle vier Carrier mit eigenen Services/Shipments in jeder Replanning-Iteration2/3/4 geprüft. |
| 10 | Gemeinsamer Optimierungsdienst | Hannover-Initialplanung, Lausitz und Replanning teilen Suche/Prüfung; finale Produktions-/Shipment-Abnahme offen. |
| 11 | MATSim-Replanning-Pass | Voriteration, Gruppierung, Altplanvergleich, KeepSelected und Controller-Lebenszyklus geprüft; reale Hintergrundprofile offen. |
| 12 | Ergebnisablage/Wiederaufnahme | Atomare geprüfte Einzelergebnisse und unabhängige echte Lausitz-Ganzdateitreffer einschließlich genehmigter Baseline-Wellenfahrzeuge lokal geprüft. Repräsentative Produktionsdaten noch abnehmen. |
| 13 | Einzel-Carrier-Replay | Zertifizierter Export/Import und tatsächlicher Paket-JAR-Replay mit gemeinsamem Kontext lokal geprüft; repräsentative große Fälle offen. |
| 14 | Gemeinsamer Batch-Ausführungsdienst | Alle vier Wege, gehaltene Gruppenreservierung, tatsächliches internes Worker-Ende und wirksame Thread-/Zulassungsgrenzen lokal geprüft. Frischer gemeinsamer Laufbudgetvertrag einschließlich Abfragefehler/gleichzeitigem Aufrufer/erhaltenen nativen Touren geprüft. Kleine Speicherreferenzen und Messpfad für zertifizierte Carrier-Captures vorhanden/geprüft; repräsentative Byte-/Reserve-Kalibrierung, Produktionsanschluss und größere Parallelität offen. |
| 15 | Warmstarts | Implementiert/geprüft; K23716Job-/fünfRootseed-Vergleich schneller, beide Jobarten mit einzelnen teureren Gewinnern. Default aus; Klon-/Abfahrtsfälle erklärt kalt. Größere/diversere Produktionsmessung offen. K237 neue getrennte Carrier-Seeds: beide Jobarten mit einzelnen teureren Gewinnern; Default aus. |
| 16 | Adaptive Suchbudgets | Regeln implementiert/geprüft; K20616Job-Paare sparen Zeit/Iterationen, zeigen einzelne teurere Gewinner trotz vollständiger Bedienung. Default aus. Produktionskalibrierung offen. K237 neue getrennte Carrier-Seeds bestätigen einzelne teurere Gewinner; Default aus. |
| 17 | Kennzahlen/Scoring/Ausgabe | Gemeinsame Phasen/CSV und erneut geprüfter tatsächlich ausgewählter Endplan samt MATSim-Score implementiert. Zentrale Ausgabeintervalle und echte finale Ereignisse/Pläne/Histogramme klein geprüft; repräsentative Regimeabnahme offen. |
| 18 | Altpfade/Bereinigung/Abnahme | K273: 1424/1424 kleine HAGRID-Tests, K274 exaktes Paket-Replay bestanden. Nativer Executor auf Erfolg/Fehler geschlossen. K275: 272 native Fälle, 19 verbleibende Fehlschläge/neun deaktiviert. Produktion/B0-B1-B7/Git-Integration und restliche Konfiguration offen. |
| 19 | jsprit intern profilieren | Kleiner kausaler Service-Hotspot-Versuch abgeschlossen: diagnostisch 15,6 % weniger Suchzeit/33,5 % weniger Allokationen bei gleichen 2.0-Plänen. Vier native Versionsabweichungen bestätigt. Shipment-/Produktionsprüfung, weitere Suchqualität/Parallelisierung und Upstream-Optimierung offen. |

**Aktuelle Reihenfolge:** siehe heutigen Abschnitt oben. Ausführungswege, Lausitz-Trefferprüfung, Einzelreplay, Kennzahlen und Gesamtbranch-Review sind lokal abgeschlossen. Verbleibende Produktions-/Budget-/Vergleichsgates sind in der Tabelle benannt.

**Weitere konkrete Bereinigungsstellen:** Die drei Zone-Typabfragen sind seit K106 entfernt. Ein alter ReplanningStrategies-Aufrufer nutzt die ausdrücklich veraltete Live-Map-Factory nur noch im rohen Kompatibilitätspfad. Historischer Befund vor K116: Router besaß mehrere Ausführungswege; seit K121 vereinheitlicht. HAGRIDRouterUtils.createRoutingProblem hat aktuell keinen gefundenen Quellaufrufer, dessen Entfernung gehört mit API-Abnahme in Aufgabe18. Historischer Befund vor K138: Routine-GC mit fünf Sekunden Pause; inzwischen entfernt. Diese Befunde sind klassifiziert, noch nicht als beseitigt ausgewiesen. GeoTools meldete im breiten Lauf JDBC-Datastores ohne Freigabe; genaue Herkunft noch ermitteln und Ressourcen-Lebensdauer prüfen, bevor dies als behobener HAGRID-Fehler gilt.

Logs: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k88-all-small-20261008.log und k89-bounded-child-jvms-20261008.log, Ressourcenprofile jeweils daneben. Nach den grünen Läufen nur Dokumentation geändert. Kein Commit, Push, Merge oder Gesamtabschluss.

## Vorheriger Stand K84: Initialplanung und sichere Ergebnis-Wiederaufnahme

**Frischer gemeinsamer Schlusslauf K84: 498/498 Prüfungen bestanden**, ohne Fehler oder Auslassungen. Alle 55 ausgewählten Root-Testklassen sind im tatsächlichen Lauf enthalten (56 Berichte einschließlich einer verschachtelten Suite). 43,315 Sekunden; Auto-Stufe 2, Maven/Test je 768 MiB, mindestens **14,95 GiB** physischer RAM frei, höchste gemessene gesamte CPU-Auslastung **57,08 %**, Exit 0 ohne Ressourcen-Zurückstellung. K84 enthält die bisherigen Supply-/Merge-/QSim-/Controller-/Konfigurationsprüfungen, neue Initialverbraucher, Ergebnisablage und bestehende Lausitz-Cache-/JAR-/Nebenläufigkeitsprüfungen. Die normale Lausitz-Golden-XML bleibt unverändert. Dies ist Korrektheitsevidenz; kein gepaarter Geschwindigkeits-/Qualitäts-/RAM-Nachweis und keine Valhalla-Stabilitätszusage.

| Schritt | Stand | Konkrete Wirkung |
|---|---|---|
| Gemeinsame Initialsuche (Aufgabe 10) | Implementiert und klein geprüft | Hannover und Lausitz nutzen denselben getrennten Such-/Straßenrouting-/Prüfdienst. Die bisherige Initial-Suchregel, native ActivityCosts, Initialbudget und Lausitz-Abfahrtsregel bleiben erhalten. Alle fünf historischen Ausführungsarten sind klein geprüft. Auch schon ausgewählte Initialpläne müssen die unabhängige Prüfung bestehen. |
| Sichere Übernahme | Implementiert und klein geprüft | Hannover übernimmt eine komplette Modusgruppe; Lausitz übernimmt die komplette ausgewählte LMD-Gruppe erst nach Prüfung. Ein später fehlerhafter Carrier erzeugt keinen angeblich erfolgreichen Teilgruppenplan. Lausitz-Abfahrtsverschiebungen werden auf einer getrennten Eingabe geroutet und erneut geprüft. Keine ganze-Lauf-/Mehrdatei-Transaktion behauptet. |
| Fahrzeug-/Executorfehler | Reproduziert und behoben | MATSim erzeugt pro Tour einer unendlichen Flotte einen eigenen Fahrer und ein eigenes Fahrzeug: wiederholte Template-ID ist dort kein FINITE-Überlappungsfehler; jede Tour trägt Fixkosten. Endliche Flotten behalten die Überlappungsprüfung. Normale Runnable-Aufgaben und explizite Future-Rückgabewerte funktionieren im historischen priorisierten Executor. |
| Alter Hannover-Cache | Datenverlust reproduziert und verhindert | Der alte v1-Cache ersetzte aktuelle Aufträge/Fahrzeuge anhand des Carrier-Namens. Er wird ignoriert, alte Dateien bleiben unverändert. Diese Initialläufe rechnen erneut; es wird keine gültige Live-Reisezeitkennung erfunden. Obsolete Cache-/Reflexions-/doppelte Solverhilfen wurden aus dem Router entfernt. |
| Ergebnisablage und Replanning-Wiederaufnahme (Aufgabe 12) | Kern und Koordinatoranschluss implementiert und klein geprüft | Ein atomar veröffentlichtes Archiv enthält geprüften Plan, Manifest und Prüfsumme. Kennung umfasst vollständige Aufträge/Flotte, eingefrorenen Netz-/Reisezeitkontext, Konfiguration, Seed, Java-Laufzeit und vorhandenen JAR-Fingerprint. Jeder Treffer wird erneut unabhängig geprüft. Beschädigte Pakete werden diagnostiziert und erhalten. |
| Wiederaufnahme nach Gruppenfehler | Tatsächlich klein geprüft | Erster Carrier fertig, zweiter unlösbar: keine Teilgruppe übernommen. Neuer Koordinator nutzt die abgeschlossene passende Suche des ersten Carriers und löst den zweiten neu; beide Endbewertungen entsprechen dem frischen Vergleich. |
| Zeitpräzision der Archive | Reproduziert und behoben | Das native Standard-XML schnitt Nachkommasekunden ab. Nur private Ergebnisarchive erhalten exakte Fahrzeug-/Job-/Tour-/Legzeiten über einen Streaming-Schritt; die globale MATSim-Zeitformatierung und normale Produktions-XML ändern sich nicht. |

Die drei Zuständigkeiten der neuen Ablage sind getrennt: CarrierInputFingerprint (46 Zeilen), OptimizationResultStore (143 Zeilen), PreciseCarrierPlanXml (85 Zeilen). Der Hannover-Router ist gegenüber dem Arbeitsbranch-Basisstand netto um 289 Zeilen kleiner; seine verbleibenden historischen Ausführungsarten werden erst in Aufgabe 14 vereinheitlicht.

**Noch offen – in Arbeitsreihenfolge:**

1. Aufgabe 12 vollständig schließen: unabhängige Übernahmeprüfung der bisherigen Lausitz-Ganzdatei-Cache-Hits einschließlich erlaubter Wellenfahrzeuge. Die bisherigen JAR-/Byte-/Concurrency-Verträge sind frisch grün; die zusätzliche fachliche Hit-Abnahme ist damit nicht erledigt. Native Initial-Caches erst mit reproduzierbarer zertifizierter Reisezeitkennung erneut aktivieren.
2. Aufgabe 13: optionaler Einzel-Carrier-Replay mit vollständig exportiertem Kontext. Der aktuelle Ergebnisstore verlangt den passenden eingefrorenen Kontext vom Aufrufer; er ist kein vollständiger MATSim-Checkpoint.
3. Aufgabe 14: gemeinsamer begrenzter Carrier-Ausführungsdienst mit RAM-Zulassung vor VRP-Aufbau, nachgewiesenem Abbruch/Lebensende und zentraler Übernahme. Die Testprozess-Schutzregeln ersetzen diese Produktionszulassung nicht.
4. Aufgaben 15–17: gültige Warmstarts, ausdrücklich optionale Suchbudgets und einheitliche Kennzahlen. Cache-Wiederverwendung als Wiederaufnahme ausweisen, nicht als schnellere eigentliche Suche. Bei späteren Warmstarts muss die Startlösung zusätzlich Teil der Gültigkeitskennung werden.
5. Aufgaben 18–19 und frühere Produktionsgates: restliche Klassenbereinigung, vollständiger Shipment-Builder, echte Eingangsdateien, festgelegte Politik für schwankende Hintergrundprofile, Paketprüfung und frische Gesamtprüfung. Mindestens fünf gepaarte Seeds für echte Laufzeit-/RAM-/Qualitätsvergleiche; große Abnahme während Valhalla zurückstellen. Danach die separate jsprit-Analyse.

7-Stunden-Solvergrenze, Default 20 km/h für konfigurierbare Lastenräder und 6 GiB freie physische Reserve bleiben verbindlich. Kein Commit/Push/Merge, kein vollständiger Integrationsabschluss. Audit-Log: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k84-initial-resume-combined-20261008.log und .log.resources.json.
## Bereits geprüfte Grundlage: die drei beauftragten Arbeitspakete

Diese Grundlage wurde mit K67 gemeinsam klein geprüft: 359/359 bestanden, ohne Fehler oder Auslassungen. Die aktuelle Fortsetzung oben ergänzt Initialmigration und Ergebnisablage. Der gesamte 19-Aufgaben-Plan bleibt offen.

| Paket | Jetzt umgesetzt und klein geprüft | Verbleibende Prüfgrenze |
|---|---|---|
| 1. Supply und Merges | Supply auf tatsächlichem Scenario-Netz und exaktem Flottenmodus; Hubmengen einmal aus finalen Lieferjobs, keine Phantomlieferung bei Nullmenge; konfigurierter WhiteLabel-Baselinepfad einmal gelesen und native Depots geprüft. Carrier-Merge nur bei passendem Modus und rückkehrbarer Erreichbarkeit. Service-Merge erhält Stopplinks, Paketaufteilung und individuelle Startzeitfenster; Segmente passen in die tatsächliche kleinste Fahrzeugkapazität. Alle Service-Map-Vorschläge werden vor gemeinsamer Übernahme geprüft. | Kein neuer Zwei-Echelon-Solver. Vollständige Rückabwicklung von Hub-/Summary-/Logänderungen und atomare Mehrdateiausgabe fehlen. Vollständiger Shipment-Builder und reale Produktionsdateien separat offen. |
| 2. Echte kleine MATSim-Fahrtests | Fünf tatsächliche QSim-Fahrfälle auf einem gemeinsamen Netz: 18 km/h, Default 20 km/h, langsamere Straße, NCE-Verlangsamung und Auto hinter Rad auf gemeinsamem FIFO-Link. Native Carrier-/Fahrzeugmodi und radexklusive Links bleiben erhalten; keine stecken gebliebenen Fahrer. | Ein Auto kann lokal durch ein langsameres Rad warten. Kein Wechsel des Queue-Modells. Die Annahme vernachlässigbaren Netzeinflusses aller 500 Fahrzeuge und empirische Kalibrierung brauchen Produktionsnachweis. |
| 3. Gemeinsamer Replanning-Dienst | Worker-eigene Suche, native Straßenrouten und unabhängige Prüfung aller Jobs, Kapazität, Skills, Depot, Zeitfenster, Fahrzeugprofil, Verfügbarkeit und Kosten. Altplan und Kandidat werden unter demselben Profil bewertet; gültiger besserer Altplan bleibt erhalten. Deterministische Gruppen, ein eingefrorener vorheriger Reisezeitstand, Übernahme erst nach Prüfung der ganzen Gruppe. | Hannover-/Lausitz-Initialsuche ist ebenfalls angeschlossen (siehe aktuelle Fortsetzung). Sichere Replanning-Ergebnisablage ist angeschlossen; unabhängige Lausitz-Cache-Hit-Abnahme, Ressourcen-Zulassung produktiver Solver und Warmstarts bleiben offen. |

**Prüfnachweise:** K63 36/36 bestanden, einschließlich eines echten MATSim-Controllers über fünf Iterationen. Dort werden drei Carrier in zwei Gruppen (2+1) vor dem echten Reisezeit-Reset übernommen und anschließend gefahren; der einmal eingefrorene Stand der Voriteration bleibt für beide Gruppen identisch. Separater Koordinatortest: 17 Carrier, Gruppen 16+1, genau ein Snapshot mit Messiteration 24; Fehler übernimmt keine Teilgruppe. K65b 31/31 bestanden, einschließlich aller elf Mergefälle, sieben Supplyfälle und fünf QSim-Fahrfälle. Die drei abschließenden Mergefehler wurden vorher tatsächlich nachgewiesen und anschließend behoben.

**Gemeinsamer Schlusslauf K67:** 359/359 Tests bestanden, 0 Fehler/Auslassungen, BUILD SUCCESS, 58,285 Sekunden. Alle 44 ausgewählten Root-Testklassen sind tatsächlich nachgewiesen (45 Berichtseinträge einschließlich einer verschachtelten Suite), darunter Lausitz-Freight-Komposition, Zustandsisolation und jsprit-Kompatibilität. Der Lauf umfasste alle fünf QSim-Fahrfälle, den echten Controller-Lebenszyklus sowie Supply/Merge und unabhängige Planprüfung. Zwei alte Regressionserwartungen wurden auf den neuen KeepSelected-/Fehlerdiagnose-Vertrag angepasst; ihre tatsächlichen Fehler bleiben als K66 protokolliert. Auto-Stufe 2: je 768 MiB Maven-/Test-Heap; kleinste gemessene freie RAM-Menge 20180058112 Byte = **18,79 GiB**, höchste gemessene gesamte CPU-Auslastung **63,77 %**, Exit 0 ohne Ressourcen-Zurückstellung. Log und Ressourcenprofil: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k67-three-packages-final-20261008.log sowie .log.resources.json. Keine fremden Prozesse geändert. Diese Korrektheitsprüfung ist kein gepaarter Leistungsnachweis.

**Wesentliche Änderung im aufgelösten Hannover-Pfad:** Der validierende Controller-Dienst übernimmt die einmalige Neuplanung. KeepSelected erhält anschließend den geprüften Gewinner; die bisherige zufällige BestPlan-/TimeAllocation-Auswahl darf keinen ungeprüften historischen Plan zurückholen. Die alten direkten Konstruktoren behalten ihre Kompatibilitätsstrategien. Der gültige Altplan bekommt vor Übernahme eine getrennte Kopie mit aktualisierten nativen Legzeiten.

**Wichtige offene Produktionsgrenze:** Der Snapshot-/Routingvertrag diagnostiziert nicht-FIFO-Zeitprofile und zeitlich variable gemischte Distanz-/Zeitziele. Er glättet oder ersetzt diese nicht heimlich. Der erfolgreiche Controller-Test verwendet kontrollierte, pro Iteration konstante Messprofile; er belegt keinen vollständigen Betrieb mit den realen, zeitlich schwankenden Hintergrunddaten. Eine geeignete geprüfte Zeit-/Zielfunktionspolitik ist dafür noch erforderlich.

**Frühere Reihenfolge nach K67 (aktueller Anschlussstand siehe oben):**

1. Verbleibende Initialplanungs-Verbraucher und vollständige Shipment-Eingaben auf die gemeinsame Prüfung umstellen; reale Hintergrundprofile mit einer ausdrücklich festgelegten Zeit-/Kostenpolitik abnehmen.
2. Sichere Ergebnisablage/Wiederaufnahme und gemeinsame RAM-/CPU-Zulassung produktiver Carrier-Solver.
3. Warmstarts, gezielte Suchbudgets und einheitliche Kennzahlen; mindestens fünf gepaarte Seeds für Laufzeit, RAM und Ergebnisqualität bei festen Eingaben/Budgets.
4. Große Produktionsabnahme erst mit Rücksicht auf Valhalla; danach die eigene jsprit-Analyse.

7-Stunden-Solvergrenze und Default 20 km/h bleiben verbindlich. Eigene Prüfungen behalten 6 GiB freie RAM-Reserve und den laufenden CPU-/RAM-Schutz. Kein allgemeiner Speedup, RAM-Gewinn, Qualitätsgewinn oder Valhalla-Stabilitätsversprechen aus diesen Korrektheitstests.

## Historische Zwischenstände

## Fortsetzung: modussichere Liefer-Erzeugung und Abbruchgrenzen

Neue Liefer-Stopps verwenden die tatsächlichen Linkfreigaben der konfigurierten Flotte. Fahrrad und Lastenrad bekommen eigene zulässige Zugänge einschließlich exklusiver Radlinks. Die bisherige Pkw-Auswahl geeigneter Parcel-Straßen bleibt erhalten. `CarrierGenerationAccess` (91 Zeilen) übernimmt diese Verantwortung aus der großen Erzeugerklasse; Modusansichten werden einmal je genutztem Modus geteilt und nach dem Erzeugungsschritt freigegeben.

- **Hub-/Depotzuordnung:** Ein Hub muss Kapazität haben und alle Jobs über einen zulässigen Depotlink mit Rückkehr bedienen können. Ein räumlich näherer Hub in einer getrennten Komponente wird übersprungen. Der gemeinsame Hub-Link für Supply wird nicht verändert; neue Lieferfahrzeuge erhalten ihren eigenen Moduszugang. Die Erreichbarkeit ist eine notwendige Topologieprüfung, keine vollständige Tourzulässigkeit.
- **Flotte vorab prüfen:** Mischflotten scheitern vor Fahrzeugerzeugung, Zufalls-Zeitverschiebung und Hub-Reservierung. Die bestehende Nachfrage-/Verfügbarkeitslogik, 7-Stunden-Solvergrenze und konfigurierbare Radbegrenzung bleiben erhalten.
- **Klare Abbrüche:** Fehler bei Services, unpassende Gesamtmengen und unerreichbare Depots stoppen die Erzeugerstufe. Carrier werden erst nach Mengen-, Supply- und Eingangsprüfung im Scenario registriert; unzulässige Eingaben bekommen keine neue Carrier-/Typausgabe. Bereits vorhandene Carrier werden vor Beginn erkannt und erhalten. Dies ist keine atomare Mehrdatei-Ablage und keine vollständige Rückabwicklung aller Hub-/Summary-/Merge-Log-Änderungen.
- **Eigene Fahrzeugkapazitäten:** Gewichtslimit, Volumen und Motor-/Kapazitätsattribute werden vom Ausgangstyp übernommen; nur die konfigurierte Paketkapazität ändert sich. Separate native Attributcontainer, unveränderter Ausgangstyp, Breite, Sitz-/Stehplätze und Kosten sind geprüft. Die generelle tiefe Kopie beliebiger veränderlicher Attributwerte bleibt eine eigene Prüfgrenze.

K43 wurde bei 7,32 GiB frei vor JVM-Start zurückgestellt. Die Umsetzung erfolgte gemäß ausdrücklicher Nutzerfreigabe mit protokollierten offenen Tests. K44 bestätigte anschließend acht Modus-/Depotfälle; zwei neue Kapazitäts-Gegenproben zeigten das verlorene Gewichtslimit. K45 hatte zunächst zwei Test-API-Fehler, korrigiert ohne Produktänderung. K45b bestätigte drei vollständige Auto-/Rad-/Lastenrad-XML-Rückimporte und die korrigierten Metadaten, zeigte aber zwei verschluckte Erzeugerfehler. K46 zeigte das bisherige Überschreiben einer vorhandenen Carrier-Sammlung. Diese drei Abbruchfälle sind im anschließenden K47 geprüft: **69/69 bestanden**, ohne Fehler oder Auslassungen.

K47: 27,144 Sekunden; 4 sichtbare JVM-Prozessoren, Maven/Test je 1024 MiB; mindestens **20,24 GiB** physischer RAM frei, höchste gemessene gesamte CPU-Auslastung **37,59 %**, Exit 0 ohne Zurückstellung. Kein allgemeiner Laufzeit-/Qualitätsgewinn und keine Stabilitätszusage für Valhalla.

Der gemeinsame Schlusslauf **K48: 310/310 Tests bestanden**, 0 Fehler/Auslassungen; alle 33 ausgewählten Root-Testklassen sind nachgewiesen (34 Berichtseinträge einschließlich einer verschachtelten Suite). BUILD SUCCESS, 26,885 Sekunden. 4 JVM-Prozessoren, Maven/Test je 1024 MiB, mindestens **20,74 GiB** physischer RAM frei, höchste gemessene gesamte CPU-Auslastung **28,49 %**, Exit 0 ohne Zurückstellung. Log: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k48-modal-generation-final-20261008.log; Ressourcenprofil daneben. Nach diesem Lauf wurde nur Dokumentation geändert. Die gemessene Reserve belegt diesen eigenen Prüflauf; keine neue gepaarte Beschleunigungs-/Qualitätsaussage.

**Nächste offene Schritte in Reihenfolge:**

1. Supply-Erzeugung und spätere Service-/Carrier-Merges durchgehend modussicher machen; vollständige Shipment-Eingaben und Produktionsnetz-Dateien prüfen. Eine Inselzuordnung wird derzeit diagnostiziert; Stops werden nicht heimlich auf weit entfernte Komponenten verschoben.
2. Kleine echte QSim-Fahrten: Default 20 km/h, konfigurierte 18 km/h, langsame Straßenprofile, NetworkChangeEvents, radexklusive Links und Zeitgleichheit zwischen Router und Simulation.
3. Produktiven Replanning-Dienst vervollständigen: ein fester Hintergrund-Reisezeitstand, einmal alle Carrier lösen, Kandidaten unabhängig auf Vollständigkeit/Zulässigkeit bewerten und zulässige Altpläne unter derselben Bewertung vergleichen.
4. Begrenzte produktive Caches, sichere Ergebnisablage/Wiederaufnahme, RAM-/CPU-Zulassung eigener Carrier-Solver, Warmstarts und gezielte Suchbudgets/Statistiken.
5. Gepaarte Laufzeit-/RAM-/Qualitätsnachweise mit festen Eingaben und mindestens fünf Seeds; große Abnahmen während Valhalla zurückstellen. Anschließend die vereinbarte Analyse weiterer jsprit-Optimierungen.

Aufgaben 7/8 bleiben in Arbeit; Aufgabe 9 hat Grundlagen, aber noch keine echte QSim-Abnahme. Der Gesamtplan ist nicht abgeschlossen.

## Fortsetzung: Generatoren, Offline-Routing und Typ-XML

Gemeinsamer Modusentscheid und gemeinsame Netzaufbereitung sind umgesetzt. K39: **285/285 Tests bestanden**. Nach der zusätzlichen Typ-XML-Korrektur K41: **50/50 gezielte Prüfungen bestanden**. Der abschließende gemeinsame Lauf K42 umfasst **294/294 bestandene Tests**, ohne Fehler oder Auslassungen. Alle 32 ausgewählten Root-Testklassen sind im tatsächlichen Lauf nachgewiesen; eine hat zusätzlich eine verschachtelte Testsuite.

- **Carrier-Modus:** CarrierModeResolver prüft importierte Angaben ohne Änderung der Eingabe. Neu erzeugte Liefer- und Supply-Carrier erhalten den eindeutigen Modus ihrer Flotte. bike/cargobike werden exakt erhalten; Mischflotten werden ausdrücklich abgelehnt. Die Prüfung ist aus dem großen Eingangsvalidator ausgegliedert.
- **Offline-Routing:** Beide Carrier-Sammlungen werden vor dem ersten Solver gemeinsam auf IDs, Typen, Modus und erreichbare Zugangslinks geprüft. OfflineCarrierRouting verwendet das tatsächliche Scenario-Netz und teilt einen nativen Anbieter je genutztem Modus zwischen Liefer- und Supply-Gruppen. Der unbenutzte Zonenanbieter wird nicht mehr erzeugt. Kleine tatsächliche jsprit-Touren mit einer Iteration und XML-Rückimport sind geprüft.
- **Gemeinsamer nativer Anbieter:** NativeModalTransportCosts wird auch vom Legacy-Replanning verwendet. Native jsprit-Fahrzeugadapter bekommen den registrierten Modus und das registrierte Geschwindigkeitsprofil; neutrale Bewertungsabfragen bleiben an denselben Modus gebunden. Dieser Kompatibilitätspfad hat weiterhin die native Live-Cache-/1800-Sekunden-Semantik; die vollständige Umstellung auf den begrenzten Snapshot-Anbieter bleibt offen.
- **Netzkopien:** ModalRoutingNetwork erhält Kapazitätsperiode, Zellgröße und Fahrstreifenbreite; Knoten, Koordinaten, Links und unterstützte Attribute werden getrennt übernommen. Netzevents beziehen sich nur auf verbleibende Links; Events ohne verbleibende Links entfallen. Das Parcel-Service-Netz verändert nicht mehr das Pkw-Routing-Netz und enthält keine fremden Nachbarschaftsreferenzen.
- **Typdateien:** CarrierVehicleTypeInputs liest einmal nativ in eine lokale Registry. Alte XML-Dateien dürfen doppelte Typ-IDs nicht mehr überschreiben. Der native CarrierVehicleTypeReader verdeckt die Duplicate-ID-Diagnose nicht mehr durch eine NullPointerException. Moderne Datei-/URL-/Stream-Kontrollen und gültige Legacy-Pkw-Dateien sind geprüft. Die kleine Reader-Korrektur liegt zusätzlich im lokalen MATSim-Untermodul.

Die neuen Bausteine haben klare Zuständigkeiten: CarrierModeResolver 56 Zeilen, NativeModalTransportCosts 70, ModalRoutingNetwork 73, CarrierVehicleTypeInputs 40 und OfflineCarrierRouting 50. CarrierRouter ist von 187 auf 132 Zeilen verkleinert; NetworkProcessor von 125 auf 99. Ausgegliederte Klassen zählen separat; dies ist kein gemessener Beschleunigungsnachweis.

Gegenproben: K33 vier tatsächliche Generatorfehler → K34 72/72 GREEN; K35 drei tatsächliche Offline-Fehler → K36b 43/43 GREEN; K37c vier tatsächliche Netzfehler → K38 40/40 GREEN; K40b zwei tatsächliche Typ-XML-Fehler → K41 50/50 GREEN. K36 hatte zunächst einen Paket-Zugriffsfehler; K37/K37b zunächst unvollständige Testfixtures, korrigiert vor den tatsächlichen Gegenproben. K40 wurde bei 14,05 % minimalem CPU-Leerlauf vor JVM-Start zurückgestellt. Ein späterer frischer Check erlaubte K40b mit konservativer Stufe 1/512/384. Kein fremder Prozess wurde verändert.

K42: 1 sichtbarer JVM-Prozessor, Maven-Heap 512 MiB, Test-Heap 384 MiB; kleinste gemessene freie RAM-Menge **10,76 GiB**, höchste gemessene CPU-Gesamtauslastung **73,01 %**, Exit 0 ohne Zurückstellung. Laufzeit 1:19 min. Log: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k42-generation-offline-types-final-20261008.log, Ressourcenprofil daneben. Diese Zahlen sind ein Nachweis des eigenen kleinen Prüflaufs; sie belegen keinen allgemeinen Speedup oder die Stabilität fremder Valhalla-Prozesse.

**Weiter offen:** Neue Rad-Stopps und Hub-/Depotzuordnung verwenden noch nicht durchgehend erreichbare Moduslinks; das Parcel-Service-Netz behält seine bisherige Pkw-Geocoding-Auswahl. Die vollständige Nachfragepipeline, echte QSim-Fahrzeiten, unabhängige Endplanprüfung, produktiver fester Snapshot, sichere Wiederaufnahme und Ressourcen-Zulassung der Carrier-Solver bleiben die nächsten Planpunkte. Die native Typkopie und eigene Kapazitätstypen brauchen noch eine Prüfung der Engine-/Kapazitäts-Metadaten. Kein allgemeiner Laufzeit-, RAM- oder Qualitätsgewinn behauptet.

## Fortsetzung: Netzeingänge und Fahrzeugtypen

Die nächste Refactoring-Stufe ist umgesetzt und klein geprüft. Der gemeinsame Schlusslauf K32 umfasst **269 Tests, ohne Fehler oder ausgelassene Fälle**; alle 27 ausgewählten Testklassen sind im tatsächlichen Lauf nachgewiesen (eine Klasse hat zusätzlich eine verschachtelte Testsuite). Log: .superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k32-input-fleet-refactor-final-20261008.log, Ressourcenprofil daneben als .resources.json.

- **Erreichbarkeit:** Ein gemeinsamer, bedarfsgerecht aufgebauter Index gerichteter Komponenten je Modus prüft Services gegen mindestens ein Depot. Pickup und Delivery derselben Sendung müssen vom selben Depot aus erreichbar sein und eine Rückkehr erlauben. Autolinks verbinden keine Radkomponenten. Identische Depot-/Stopplinks benötigen keine erfundene Rundfahrt. Iterative Suche ist mit einem gerichteten 4000-Knoten-Kreis geprüft. Kein Netz wird je Carrier kopiert oder geändert.
- **Netzkonsistenz:** Derselbe lesende Prüfer wird beim Einlesen, vor jedem Teilnetz-Merge und am Carrier-Eingang benutzt. Auch die erste/einzige Quelle und der Ein-Datei-Pfad prüfen Koordinaten, Linkwerte, Endpunkte und tatsächliche Nachbarschaftsreferenzen. NaN/ungültige Werte werden vor Routing gemeldet; gültige Null-Längen und Null-Kapazitäten bleiben erhalten. Ereignis-/Turn-/Metadatenprüfung ist damit nicht vollständig abgenommen.
- **Fahrzeugtypen:** CarrierVehicleFactory wurde von 365 auf 68 Zeilen verkleinert; die Typauflösung liegt im separaten CarrierVehicleTypeCatalog (185 Zeilen). Die Registry gehört einer Instanz. bike/cargobike werden nicht mehr pauschal zu car. XML-Typen, eigene Kapazitäten und registrierte Objektidentität sind geprüft.
- **Geschwindigkeit:** CarrierFleetPreparation (36 Zeilen) begrenzt native Radtypen auf min(XML-Maximum, konfiguriertem Deckel), bevor Scenario-Carrier gelesen werden. Gemeinsamer Default 20 km/h, konfigurierte 18 km/h = 5 m/s und langsameres XML sind geprüft. Die Hannover-Erzeuger reichen die aufgelöste Fleet-Konfiguration an die Factory weiter. Straßenfahrzeuge, Hannover-Solvergrenze 25200 Sekunden und bisherige Verfügbarkeits-/Supply-Zeitregeln bleiben in den geprüften Kontrollen erhalten.

Zwischenläufe: K26 fünf echte Topologie-REDs → K27 52/52 GREEN; K28 vierzehn echte Netzeingangs-REDs → K29 77/77 GREEN; K30 hatte zunächst ausschließlich einen Test-API-Fehler, korrigiert ohne Produktänderung; K30b zehn echte Fleet-/Scenario-REDs → K31 61/61 GREEN. Schlusslauf K32: 2 sichtbare JVM-Prozessoren, Maven/Test je 768 MiB Heap, kleinste gemessene freie RAM-Menge **14,70 GiB**, höchste gemessene Gesamtauslastung **58,51 %**, Exit 0 ohne Ressourcen-Zurückstellung. Diese Messung belegt die eingehaltene Reserve im eigenen Prüflauf, keine allgemeine Valhalla-Stabilität.

**Nächster Implementierungsbaustein:** Modussichere neue Stop-/Hub-/Depotzuordnung und die verbleibenden Erzeuger-Ausgabegates; anschließend kleine echte QSim-Fahrten mit 18-km/h-Fixture, langsamen Profilen, NetworkChangeEvents und radexklusiven Links. Doppelte Typ-IDs sind jetzt geprüft; ein kompletter Shipment-Plan beim Builder bleibt offen. Native Quellprüfung bestätigt den Standard-Fallback min(Fahrzeugmaximum, Linkgeschwindigkeit); sie ersetzt keinen gemessenen QSim-Lauf.

Aufgaben 7/8 sind weiterhin in Arbeit; Aufgabe 9 hat jetzt die Geschwindigkeitsvorbereitung, aber noch keine QSim-Abnahme. Zonen-/Zeitnäherungen, dynamische gemischte Routingkosten, unabhängige Endplanprüfung, fester Replanning-Snapshot, Wiederaufnahme und vorab begrenzte Solver-Parallelität bleiben offen. Kein neuer gepaarter Laufzeit-/RAM-/Qualitätsvergleich und kein Gesamtabschluss.

## Früherer Zwischenstand K25 und weiterhin verbindliche Regeln

Auf ausdrücklichen Nutzerwunsch wird Code auch bei zurückgestellter Testausführung weiter implementiert. Diese Liste ist der dauerhafte Prüfauftrag, kein Erfolgsnachweis.

Netz-/Reisezeit-Konsistenz, Modalnetz-Importer, In-Memory-Carrier-Ladepfad und Eingangsvalidierung sind implementiert. Der damalige geschützte Lauf k25-refactor-completeness-verified-20261008.log hat 192 Tests bestanden, ohne Fehler, Auslassungen oder Ressourcenabbruch; Maven BUILD SUCCESS, 1:20 Minuten. Zwei für die JVM sichtbare Prozessoren, je 768 MiB Maven-/Test-Heap; kleinste gemessene freie RAM-Kapazität 15848161280 Byte (14,76 GiB), höchste gemessene gesamte CPU-Auslastung 63,60 Prozent. Das ist Korrektheitsevidenz für diese Auswahl und kein Vorher/Nachher-Performancevergleich. Die vorherigen 174- und 190-Test-Läufe sowie alle tatsächlich beobachteten RED-/Fixture-Fälle bleiben im Fortschrittsledger erhalten.

Neu angeschlossen und geprüft:
- Beide Replanning-Pfade verwenden den passenden modal aufgelösten VRPTransportCosts-Anbieter einschließlich seines Netzes, Routers und Ziel-Link-Profils. Der Zonenschalter entscheidet nicht mehr über den Fahrzeugmodus. Ein fehlender Modusanbieter wird diagnostiziert.
- Vorhandene live gespeicherte Reisezeiten werden auch bei ausgeschalteter Zonen-Näherung vor dem Replanning erneuert. Ein zusätzlich erzeugter globaler Router entfällt; beide Strategien teilen die vorbereiteten Modal-Anbieter.
- Wenn das tatsächliche Scenario-Netz alle erforderlichen Rad-Zugangslinks bereits enthält, wird keine zweite Rad-Datei geladen. Andernfalls werden kompatible Teilnetze kontrolliert in das tatsächliche Scenario-Netz übernommen. Beide alten Netz-Aliase zeigen darauf; Netzevents bleiben auf die richtigen Links bezogen.
- Der vollständige Builder ist mit winzigen echten Config-/Netz-/Carrier-/Typ-/Event-XML-Dateien geprüft. Typidentität, ausgewählter Plan, Score und gemessene Einstellungen bleiben erhalten; fehlender Carrier-Modus wird aus dem tatsächlichen Fahrzeugtyp abgeleitet.
- Die kombinierte Carrier-Ausgabe wird erst nach erfolgreicher Prüfung und Registrierung geschrieben. Die allgemeine Launch-Vorprüfung verlangt keine unbenutzte Rad-Datei. Tatsächlich benötigte zusätzliche Eingaben prüft der bedarfsgerechte Loader.
- Exakte importierte Modusnamen wie bike bleiben erhalten und sind auch in QSim-, Netzwerk-Routing- und Reisezeitmess-Konfiguration eingetragen; die native DVRP-Behandlung von DRT bleibt erhalten. Das ist noch keine vollständige Abnahme der Fahrzeuggeschwindigkeit in QSim.

Der native MATSim CarrierPlanXmlReader maskierte eine Duplicate-ID-Ausnahme ohne Ursache mit einer NullPointerException. Seine Schema-Fallback-Prüfung ist jetzt nullsicher; gültige XML und die Duplicate-ID-Diagnose sind über Datei, URL und Stream geprüft. Beim Stream bleibt die native UncheckedIOException-Verpackung erhalten. Die Änderung liegt im lokalen MATSim-Untermodul.

Aktueller Planstand: Aufgabe 1 abgeschlossen; 2–4 umgesetzt mit offenen Paket-/Verbraucher-/Vergleichsgates; 5–6 Grundlagen abgeschlossen, produktiver Lebenszyklus weiterhin Aufgabe 11; 7–8 in Arbeit. Aufgaben 9–19 bleiben offen; 10/11 haben nun zusätzliche Legacy-Vorarbeit, aber keinen vollständigen Dienst/Koordinator. Insbesondere Zonen-/Zeitnäherungen, volle QSim-Abnahme, abschließender Optimierungsdienst, einmaliges Replanning, Wiederaufnahme, begrenzte Carrier-Parallelisierung und Warmstart sind nicht als fertig ausgewiesen.

Der Gesamtplan bleibt offen. Der Importer und Carrier-Ladepfad sind im Builder verdrahtet; kompatible gemeinsame Netze sind mit kleinen echten Eingaben geprüft. Verlustfreie Zusammenführbarkeit der Produktionsdateien, Modusprofile/Alias-Migration und konkrete QSim-Fahrzeiten sind weiterhin nicht nachgewiesen.

Die reine Ressourcen-Heartbeat-Automation ist pausiert. Für tatsächliche eigene Java-Läufe bleiben Valhalla-Vorrang, mindestens 6 GiB freier physischer RAM, begrenzte CPU/Heaps und der laufende Schutz des eigenen Prozessbaums verbindlich. Keine fremden Prozesse ändern oder beenden.


## Refactoring und weitere Fehlerbehebungen 2026-10-08

Die Einstiegsklassen sind nach Zuständigkeiten aufgeteilt; öffentliche Replanning-Konstruktoren, Suchbudgets, Start-/Gruppeneinstellungen, Strategiegewichte und die Hannover-Solvergrenze von 25200 Sekunden bleiben erhalten. Die Zeilenangaben vergleichen den Stand unmittelbar vor diesem Refactoring mit dem aktuellen Quelltext; ausgegliederte Klassen zählen separat. Kleine Klassen sind hier kein Laufzeitnachweis.

| Baustein | Verantwortung / Größenvergleich |
| --- | --- |
| CarrierVehicleReRouter | Kompatibler Strategieaufbau: 472 → 60 Zeilen. Jede erstellte Strategie hat einen eigenen Registry-/Fallback-Lebenszyklus. |
| CarrierReplanningModule | Vorbereitung, geordnete Carrier-Gruppe, Worker-Ergebnisse, Übernahme auf dem aufrufenden Controller-Thread und Abschlussmarker. |
| LegacyCarrierPlanOptimizer | jsprit-Suche, vollständige Zuordnung und native Straßenroute als eigener Kandidat; keine Tour-/Iterations-/Erfolgsmetadaten am Scenario-Carrier veröffentlichen. Dies ist noch nicht der abschließende Optimierungsdienst aus Aufgabe 10. |
| CarrierRoutingCosts | Exakte Modusauflösung und Legacy-Cache-Brücke für beide Replanning-Pfade; benötigte native Modusanbieter einmal pro Vorbereitung aufbauen. Unbenutzte Modusnetze werden im Legacy-Fallback nicht erzeugt. |
| CarrierRoutingZoneMetrics | Zonenstatistik über Aufträge bzw. tatsächliche MATSim-TourActivity-Objekte. |
| HAGRIDScenarioBuilder | Geordneter Scenario-Aufbau und Validierungs-/Veröffentlichungsgate: 425 → 141 Zeilen. |
| FreightScenarioConfiguration | Bisherige Simulationseinstellungen und validierte native Carrier-Modi. Diese Ausgliederung ist noch keine vollständige Umstellung sämtlicher Verbraucher auf ResolvedHagridConfig. |
| CarrierInputFiles | Carrier-XML einmal lesen/zusammenführen und erst nach erfolgreicher Builder-Prüfung schreiben. Alte Parcel-Type-Schreibweise einmal nach dem Merge normalisieren. |
| FreightZoneAssigner | Räumlichen Index aufbauen und gemeinsames Netz klassifizieren; vorhandene Zonen und bisherige Auswahlpriorität bewahren. |

Tatsächlich mit Gegenproben behoben:

- Ein Carrier erhält den Erfolgsmarker erst nach abgeschlossener Suche, Zuordnung, Straßenroute und Übernahme. Ausnahmen erlauben einen erneuten Versuch und überschreiben vorherige Messwerte nicht.
- Die Optimierungsfreigabe wird für jede MATSim-Vorbereitung neu berechnet; sie bleibt nach Iteration 100 oder beim Wechsel auf eine frühere Iteration nicht aktiv.
- Worker liefern eigene Kandidaten. Wenn eine Berechnung in der Gruppe fehlschlägt, werden keine Kandidaten dieser Gruppe übernommen; der Controller-Thread übernimmt nach erfolgreichem Gruppenabschluss.
- Mehrfaches Auswählen desselben Carriers löst ihn einmal und aktualisiert die zuletzt ausgewählte Arbeitskopie, statt eine inzwischen unselektierte Kopie zu verändern.
- Die alte Zonenstatistik suchte jsprit-Aktivitäten in einer MATSim-Tour. Der native Aktivitäts-Fallback zählt nun die tatsächlich vorhandene Zone.
- Ein Ein-Zonen-Input brach beim räumlichen Index ab: Das bisherige Argument war die Knotenkapazität, nicht die Zahl gespeicherter Objekte. Der Index verwendet begrenzte Standard-Verzweigung; Überlappungspriorität ist explizit erhalten. Eine und 32 disjunkte Zonen sowie mehrstufige überlappende Polygone sind klein geprüft.
- Ein registrierter, aber unbenutzter Lastenradtyp verlangte im Legacy-Autofallback trotzdem ein Radnetz. Es wird jetzt nur der angefragte Modus aufgebaut.
- Ein tatsächlicher nicht einplanbarer jsprit-Auftrag durfte bislang einen leeren/unvollständigen Erfolg erzeugen. Fehlende Lösung bzw. unzugeordnete IDs werden nun vor der Übernahme diagnostiziert. Der vorhandene 60-Sekunden-Grenztest mit 120-Sekunden-Services erwartet folgerichtig einen Fehler statt eines erfolgreichen leeren Plans.

Entfernt sind die ungenutzte zweite Optimierungsliste, auskommentierte Versuchspfade, künstliche Nullzuweisungen und System.gc nach jedem Batch. Leere Gruppen starten keinen Executor und bauen keine Fallback-Netze. Laufzeiten werden mit einer monotonen Uhr in Sekunden einschließlich Nachkommastellen erfasst. Quantitative RAM-/Zeitvorteile sind weiterhin nicht gemessen.

Weitere Abnahme bleibt erforderlich: unabhängige Prüfung jedes Endplans, Vergleich mit dem gültigen bisherigen Plan, stabile globale Carrier-Reihenfolge, echte Zustände und Umgang mit importierten false-/String-Markern, Verhindern späterer ungeprüfter Altplanauswahl, ein fester produktiver Reisezeitkontext und vorab begrenzte Solver-/RAM-Zulassung. Die neu eingeführte Legacy-Gruppenübernahme ist keine atomare Ergebnisspeicherung aus Aufgabe 12. Abbruch-/Interrupt-/langsam terminierende Worker und mehrere reale Gruppen sind noch gesondert zu prüfen.

Bei der räumlichen Zuordnung bleiben CRS, echte Shapefiles, Grenzpunkte, unzulässige Geometrien und vollständige Ganzzahl-/Bereichsprüfung der Zonen-IDs weitere Eingangs-/Vergleichsgates. Der frühere globale Y-/X-/Eingangsreihenfolge-Vorrang ist für mehrstufige Überlappungen geprüft; daraus folgt keine Kalibrierung der Zonen-Näherung.


## Zuerst: kleine, getrennte Prüfpakete

Ausführung ausschließlich im Worktree:

C:/Users/bienzeisler/.codex/worktrees/zone-router-fixes/HAGRID

Der vorhandene Runner tools/benchmarks/run-bounded-tests.ps1 prüft Ressourcen vor dem Start und schützt nur seinen eigenen Lauf. Für jeden Lauf einen neuen Lognamen verwenden; vorhandene Nachweise nicht überschreiben. Bei Zurückstellung bleibt der Auftrag offen, während unabhängige Codearbeit weitergehen kann.

| ID | Prüfpaket | Frischer Nachweis 2026-10-08 | Weitere Abnahme |
| --- | --- | --- | --- |
| K1 | TravelTimeSnapshotTest, RoutingContextTest | 32/32 bestanden; drei beobachtete Kohärenz-RED-Fälle jetzt GREEN, einschließlich fünf zusätzlicher Kontrollen. | Reale Profilentstehung und ruhender Capture-Zeitpunkt offen. |
| K2 | ModalNetworkImporterTest | 10/10 bestanden, inklusive kleiner Netzwerk-XML. | Builder-Verdrahtung geprüft; weitere Event-Konflikte und reale Produktionsdateien offen. |
| K3 | CarrierInputConsistencyTest, CarrierNetworkInputTest | 24/24 bestanden; echte XML über Datei/URL/Stream, Metadaten und Typidentität sowie Modus-/Depot-/Service-/Shipment-Linkprüfung. | Kleiner vollständiger Builder geprüft; doppelte Typ-IDs im Typ-XML offen; topologische Prüfung jetzt in K12 bestanden. |
| K4 | RoutingContextLifetimeTest, ModalTransportCostsTest, TemporalRoutingContractTest, RoutingCacheConfigurationTest, ByModeCongestedTravelTimeTest | 40/40 bestanden auf dem neuen Netzidentitätsstand. | Gepaarter Cache-/Laufzeitvergleich und Näherungsverdrahtung offen. |
| K5 | SimulationRoutingConsumerTest, HAGRIDRouterUtilsTest, JspritConstructionHeuristicTest | 17/17 bestanden; kleine native Solver- und Verbraucherregressionen. | Vollständiger Builder mit kleinen Dateien geprüft; echte QSim-Abnahme separat offen. |
| K6 | ScenarioModalNetworksTest | 9/9 bestanden; erforderliche Teilnetze werden kontrolliert in das tatsächliche Scenario-Netz übernommen, Events bleiben zugeordnet, Konflikte publizieren keine Teil-Aliase. | Produktionsdateien und konkrete RAM-/Zeitersparnis nicht gemessen. |
| K7 | ReplanningModalNetworkTest | 20/20 bestanden; Modusanbieter und Native-Fallback, Cache-Erneuerung, Abfahrtsrouting, Marker/Retry/Fenster, eigene Kandidaten, Gruppenfehler, neueste Arbeitskopie, leere/ungenutzte Netze und tatsächliche unzugeordnete Aufträge. | Unveränderlicher produktiver Snapshot-Lebenszyklus, volle unabhängige Planprüfung, Status-/Altplanauswahl und Leistungsnachweise offen. |
| K8 | ScenarioCarrierLoadingTest | 15/15 bestanden mit winzigen echten XML-Dateien und zwei reinen Existenz-Prüfungen; Plan-/Typ-/Messwert-Erhaltung, Netzevents, kein unvalidierter Output, bike-Konfiguration, optionale Rad-Datei und Mixed-Normalisierung. | Kein kompletter Nachfrage-/Shapefile-/Produktionslauf; nativer Typdeckel jetzt geprüft; QSim-Linkzeiten weiterhin offen. |
| K9 | ZoneBasedTransportCostsTest, ZoneBasedTransportCostsFactoryTest | 21/21 bestehende Legacy-Cache-/Modusregressionen frisch bestanden. | Noch vorhandene Legacy-Brücken und konkrete gemeinsame Konfigurationslimits bis zur vollständigen Kontextmigration prüfen. |
| K10 | DeliveryModeOfTest, RunOutputCollisionTest | 4/4 bestehende Erzeugungs-/Ausgabe-Konfliktkontrollen frisch bestanden. | Vollständige Erzeuger-/Scoring-/Ausgabemigration bleibt in den zugeordneten Aufgaben offen. |
| K11 | FreightZoneAssignmentTest | 6/6 bestanden: ein Polygon, Endpunkt-Fallback, bestehender Zonentag, kein erfundener Tag, mehrstufige Überlappungspriorität und 32 disjunkte Polygone. | Reale Shapefiles/CRS/Grenzpunkte, vollständige ID-/Geometrieprüfung und gepaarte Laufzeitmessung offen. |
| K12 | CarrierTopologyInputTest | 10/10 bestanden: gerichtete Hin-/Rückwege, getrennte Inseln, exakte Radmodi, mehrere Depots, gemeinsame Shipment-Erreichbarkeit, gleiche Link-IDs und 4000-Knoten-Kreis. | Turn-/Zeit-/Kapazitätsfeasibilität und modussichere neue Ortszuordnung bleiben offen. |
| K13 | ModalNetworkInputValidationTest | 15/15 bestanden: erste/einzige Quelle, Datei-Schnellpfad, Carrier-Grenze, Koordinaten, Skalare, Nachbarschaften und erlaubte Nullwerte. | Weitere Netzmetadaten, Ereignispayloads und Produktionsdateien bleiben offen. |
| K14 | CarrierVehicleFactoryTest, CargoBikeConfigurationFlowTest, HagridConfigResolverTest, SimulationRoutingConfigAdapterTest | 49/49 bestanden: instanzgebundene Registry, exakte Modi, Konfigurationsfluss, Default-/18-km/h-Deckel, langsames XML, Kapazitätsalias und Supply-Verträge. | Vollständige Nachfrage-Ortszuordnung, echte QSim-Linkzeiten, globale RNG-Ablösung und Prüfung der vorhandenen Supply-Zeiteinheiten bleiben offen. |

| K15 | CarrierGenerationModeTest | 6/6 bestanden: tatsächliche Delivery-/Supply-Erzeugermethoden, bike/cargobike/car/truck und explizite Mischflotten-Ablehnung. | Keine vollständige Nachfragepipeline; modussichere neue Orte und Ausgabegates offen. |
| K16 | OfflineCarrierModalRoutingTest | 3/3 bestanden: echte native Ein-Iterations-Touren für Pkw und beide Radmodi auf gemeinsamem Netz, XML-Rückimport und Supply-Eingangsfehler vor jeder Delivery-Übernahme. | Native Cache-/Fehlerpublikation und unabhängige vollständige Endplanannahme bleiben offen. |
| K17 | NativeModalTransportCostsTest, ParcelServiceNetworkTest | 7/7 bestanden: registrierte/neutrale Fahrzeugprofile, Moduskonflikt, Netzeinheiten, verbleibende und ausgeschlossene Events sowie getrennte Parcel-Objekte/Nachbarschaften. | Weitere Ereignis-/Turn-/Metadaten und echte QSim-Zeiten offen; kein Produktions-Snapshot-Lebenszyklus. |
| K18 | CarrierVehicleTypeInputsTest | 6/6 bestanden: gültige moderne Typdateien und erhaltene Duplicate-ID-Diagnose über Datei/URL/Stream. Builder K8 zusätzlich moderne/alte doppelte Typ-IDs und gültige Legacy-Pkw-Datei. | Vollständige Shipment-/Typdimensionen, Legacy-Stream-Schema-Retry und Produktionsdateien bleiben separate Prüfungen. |

Gezielte Wiederholungsbefehle (nur nach betroffenen Änderungen und bei tatsächlich zugelassenen Ressourcen):

```powershell
& ./tools/benchmarks/run-bounded-tests.ps1 -Tests 'TravelTimeSnapshotTest,RoutingContextTest' -Log './.superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k1-network-coherence-next.log'
& ./tools/benchmarks/run-bounded-tests.ps1 -Tests 'ModalNetworkImporterTest' -Log './.superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k2-modal-importer-next.log'
& ./tools/benchmarks/run-bounded-tests.ps1 -Tests 'CarrierInputConsistencyTest' -Log './.superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k3-carrier-input-next.log'
& ./tools/benchmarks/run-bounded-tests.ps1 -Tests 'RoutingContextLifetimeTest,ModalTransportCostsTest,TemporalRoutingContractTest,RoutingCacheConfigurationTest,ByModeCongestedTravelTimeTest' -Log './.superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k4-routing-regressions-next.log'
& ./tools/benchmarks/run-bounded-tests.ps1 -Tests 'SimulationRoutingConsumerTest,HAGRIDRouterUtilsTest,JspritConstructionHeuristicTest' -Log './.superpowers/sdd/2026-10-07-hagrid-jsprit-integration/k5-consumers-next.log'
```

## K1: genauer Inhalt

Ergänzung K240: Einfügereihenfolge und tatsächliche Profilentstehung ergänzt; siehe aktuellen Stand oben.

Bereits tatsächlich fehlgeschlagen und jetzt im frischen Lauf bestanden:

- RoutingContextTest.refusesChangedTopologyWithTheSameCapturedLinkIdsAndLengths
- RoutingContextTest.refusesChangedCoordinatesBetweenTravelTimeAndContextCapture
- RoutingContextTest.refusesNewBackgroundChangeEventsAfterTravelTimeCapture

Zusätzliche Kontrollen jetzt im frischen Lauf bestanden:

- RoutingContextTest.acceptsAnEquivalentDetachedGraphForTheSameSnapshot
- RoutingContextTest.refusesChangedExistingEventPayloadAfterTravelTimeCapture
- RoutingContextTest.refusesChangedTurnRestrictionsAfterTravelTimeCapture
- TravelTimeSnapshotTest.refusesNetworkMutationDuringSampling
- TravelTimeSnapshotTest.fingerprintChangesWhenGeometryChangesEvenWithIdenticalTravelTimes

Zusätzlich noch zu ergänzen: unterschiedliche Einfügereihenfolge bei gleichem Netz, gleichzeitige überlappende NCE und Profilentstehung aus einem realen kleinen TravelTimeCalculator. Die neue Identität berücksichtigt auch unterstützte Attribute und Ereignispayloads; sie muss zur schreibgeschützten Graphkopie passen. Ein Vergleich vor/nach Abtastung ist kein atomarer Snapshot bei beliebigen konkurrierenden Fremdmutationen; Produktionsverdrahtung braucht einen ruhenden Capture-Zeitpunkt.

## K2: genauer Inhalt

Ergänzung K240: doppelte/teilweise überlappende Events, fehlende Links und kompatible Modus-Turn-Restrictions ergänzt; separate NCE-Datei bereits im ScenarioCarrierLoadingTest geprüft.

Zehn Methoden im frischen Lauf bestanden:

- sharedLinkUnionsModesWithoutInventingAliases
- preservesCarAndBikeExclusiveLinks
- conflictingSharedIdFails
- conflictingNodeCoordinatesAndSharedSpeedsFailRatherThanOverwrite
- normalizesExplicitAliasInTurnRestrictionsAndDetachesAttributes
- conflictingSharedAttributesFail
- preservesDetachedChangeEventsAndAppliesIdenticalImportedEventOnce
- conflictingOverlappingEventsFail
- carOnlyNeedsNoBikeFile
- aliasChainsAreRejected

Noch zu ergänzen: doppelte identische Events innerhalb einer einzigen Quelle müssen scheitern; teilweise überlappende Event-Linkmengen; Referenzen auf fehlende Links; mehrere kompatible Modus-Turn-Restrictions auf gemeinsamem Link; separate Event-Datei über den Scenario-Loader; weitere Netzwerk-Metadaten und Ereignispayloads. Erste/einzige Link-/Node-Einträge sind jetzt mit K13 geprüft. Ein gemeinsames Eingangsnetz ohne Alias/zweite Datei wird direkt geladen, ohne zusätzliche Graphkopie.

## K3: genauer Inhalt

Ergänzung K250: vollständige vorhandene Shipment-Pläne für Pkw/Lastenrad durch nativen XML-Builder/Merge geprüft. Nachstehende Methodenliste ist historisch.

Ursprüngliche sieben Methoden im frischen Lauf bestanden; hinzu kommt preservesNativeXmlRoundTripSettings für Datei, URL und Stream:

- duplicateCarrierIdFailsBeforeInputsAreMutated
- preservesRecordedIterationsAndMode
- missingRecordedSettingsRemainMissing
- rejectsMismatchedMapKey
- registersTheParsedObjectsAndTypesWithoutRoundTrip
- refusesToOverwriteScenarioInputs
- nativeXmlReaderCannotSilentlyReplaceDuplicateCarrierIds

CarrierNetworkInputTest ergänzt zwölf bestandene Kontrollen für Modus, Typregistry, Fahrzeug-/Auftrags-ID und Depot-/Service-/Pickup-/Delivery-Zugangslinks. Der vollständige kleine Builder-Lauf ist jetzt mit Liefer-/Supply-/Typ-/Config-/Netz-/Event-Dateien geprüft; ausgewählter Service-Plan, Score und Typidentität bleiben erhalten. Noch zu ergänzen: vollständiger Shipment-Plan im Builder; doppelte Typ-IDs im Typ-XML sind jetzt mit K8/K18 geprüft. Topologische Erreichbarkeit über Modusgraphen ist mit K12 geprüft. Fehlende jsprit-Einstellungen explizit auflösen bzw. verständlich ablehnen. Die Entfernung der 40/900/car-Überschreibungen ist eine fachliche Fehlerkorrektur, keine reine Performanceänderung.

## Historischer Prüfauftrag aus K18: Vergleiche und spätere Abnahme

| Prüfpunkt | Inhalt / Annahme | Status / sinnvoller Start |
| --- | --- | --- |
| B1 / Aufgabe 2 | jsprit-Version isoliert, Packaging/JAR/IO-Pfade und tatsächliches gleiches Suchbudget | Kleine frühere Versionsmessung vorhanden; Seed 46 mit abweichendem tatsächlichem Budget nicht als Speedup verwenden. Vollständiges Paket-verify und Starttest offen. |
| B2a / Aufgabe 3 | Alle Verbraucher verwenden dieselbe aufgelöste Konfiguration | Resolver/Adapter früher gezielt geprüft; vollständige Migration, identisches Regelprofil und Vergleich offen. Solvergrenze 25200 s erhalten; Erzeugung/Scoring 27000 s separat behandeln. |
| B2b / Aufgabe 4 | Service-/Shipment-Zeiten, Tourgrenzen, Kosten und Verschiebungen | Kleine Korrektheitsnachweise früher bestanden; repräsentativer, getrennt erklärter Ergebnisvergleich offen. |
| B3a | Snapshot-/Kontextaufbau nach neuem Graphdigest | Frühere Zahlen gelten für ältere Implementierung. Neue Messung mit frischem Build: Aufbauzeit, Allokationen und gehaltene Daten getrennt erfassen. Mehr Hasharbeit beim Capture gegen Kohärenzschutz abwägen. |
| B3b/B3c | Gleiche statische Zeit/Kosten; Ziel-Link genau einmal; begrenzter Cache und Worker | Frühere Kontrollwerte vorhanden. Abschließende Cache-Abnahme und gepaarter Vergleich nach Abfrageoptimierung offen. Früherer nativer warmer Cache war schneller; kein allgemeiner Speedup belegt. |
| B3c Näherungen | Zone-/Zeitnäherung getrennt von genauer Referenz | Zonenflag noch nicht durchgehend verdrahtet; positive neue Zeitquantisierung bisher bewusst abgelehnt. Neue Implementierung und gehaltene Testpaare plus genaue Tour-Nachbewertung offen. |
| Zeitabhängiges Routing | FIFO, Vorwärts/Rückwärtsvertrag und gemischte Kosten | Kleines tatsächliches Gegenbeispiel: Ankunft billigster gemischter Route trotz FIFO-Links nicht monoton. Dynamische gemischte Kosten weiter diagnostiziert; Routingpolitik noch explizit zu entscheiden/testen. |
| B4a / Aufgabe 8 | Gemeinsames Netz und In-Memory-Carrier-Laden | Importer-/Merger-/Eingangsvalidierung und gemeinsame Scenario-Netzumstellung mit echten kleinen Dateien geprüft; vollständige Eingangskonsistenz, Produktionsdateien sowie Ladezeit-/RAM-Vergleich offen. Ladezeit/RAM getrennt messen, keine unbewiesenen Speichergewinne behaupten. |
| B4b / Aufgabe 9 | Echte QSim-Auto-/Lastenrad-Legs auf gemeinsamem Netz | Gemeinsames Scenario-Netz und Modus-Konfiguration vorbereitet; echte QSim-Fahrzeit-/Fahrzeugdeckel-Fixture und vollständige Verdrahtung noch offen. Default max. 20 km/h konfigurierbar; Testfixture 18 km/h. Fahrzeugmaximum, langsame Profile, NCE-Grenzen und radexklusive Links prüfen. |
| B5a / Aufgabe 10 | Gemeinsamer Optimierungsdienst und unabhängige Endplanprüfung | Legacy-Kandidaten jetzt ohne Scenario-Übernahme und unzugeordnete Aufträge abgelehnt. Unabhängige Vollprüfung, Fenster, Kapazität, Verfügbarkeit, 7-h-Grenze und Auswahl des gültigen besseren Plans weiter umzusetzen/prüfen. |
| B5b / Aufgabe 11 | Einmaliges Replanning aus festem Hintergrundstand | Noch offen: Initiallösung eine Iteration, Messstand vor Start, deterministische Gruppen, alle Carrier einmal, Router-/Snapshot-Lebensdauer, native Lausitz-Regime. |
| B5c / Aufgabe 12 | Atomare Ergebnisspeicherung, Abbruch und Wiederaufnahme | Noch umzusetzen/prüfen: nur passende vollständige Ergebnisse wiederverwenden; fehlende Carrier lösen; kein Cachehit als eigentliche Suchbeschleunigung ausweisen. |
| Aufgabe 13 | Einzel-Carrier-Replay | Noch umzusetzen/prüfen: reproduzierbare Eingaben/Regeln/Seed/Snapshot, nur angefragten Carrier lösen. |
| B6a / Aufgabe 14 | Kontrollierte Solver-Parallelität und Zulassung vor VRP-Aufbau | Ausführung, Reservierung/Lebensdauer und frischer gemeinsamer Budgetvertrag klein geprüft. Repräsentative Kalibrierung und getrennte externe/interne 1/2/4-Profile über mehrere Gruppen noch offen; größere Versuche warten wegen Valhalla. |
| B6b / Aufgabe 15 | Gültige Pläne als jsprit-Warmstart | Noch umzusetzen/prüfen. Warm/kalt bei identischen Budgets; Vorbereitung/Suche getrennt, alle harten Regeln identisch. |
| B6c / Aufgabe 16 | Optionale adaptive Suchbudgets | Noch umzusetzen/prüfen; keine Default-Aktivierung allein wegen kürzerem Lauf. Ressourcen-Autostufung von Java ist nicht adaptive jsprit-Suche. |
| B7a / Aufgabe 17 | Gleiche Kennzahlen, Bewertung und Ausgaben | Noch umzusetzen/prüfen: Scoring/Reports gegen identische Endpläne, Korrekturen nicht verdecken. |
| B7b / Aufgabe 18 | Lokaler gesamter Ablauf und Altpfadbereinigung | Erst nach Komponenten-GREEN: zwei LSP, beide Modi, NCE, Fenster, Services/Shipments, Gruppen, Wiederaufnahme und Lausitz. Produktion/große Szenarien während Valhalla zurückgestellt. |
| Aufgabe 19 | Danach jsprit-interne Engpässe und Optimierungsideen | Erst nach Integration profilieren, Auswirkungen auf Laufzeit, RAM und Ergebnisqualität getrennt belegen. |

Längere Vorher/Nachher-Vergleiche verwenden feste JVM-/Heap-/Thread-Einstellungen; adaptive Ressourcenwahl ist nur für kleine Korrektheitsprüfungen vorgesehen. Kleine Probes: drei Wiederholungen. Solver-Vergleiche: fünf Seeds 42–46, gleiche tatsächlich verwendete Budgets und vollständige fachliche Bewertung; Median/Streuung, RAM/GC und Qualität gemeinsam ausweisen.

## Schon vorhandene Schutz-Nachweise

Die Ressourcen-Schutzscripts wurden vor diesem Fortsetzungsschritt tatsächlich mit 14 bzw. 18 Assertions geprüft. Sie sind hier nicht geändert worden. Diese frühere Evidenz beweist weder die neuen Java-Änderungen noch die Stabilität des fremden Valhalla-Prozesses. Bei späteren Änderungen am Schutz sind passende Schutztests erneut auszuführen.

## Fortsetzungsregel

Nach jeder Änderung betroffene IDs ergänzen. Nach jedem echten Lauf Logpfad, Codekennung, Ressourcenprofil und Resultat eintragen. Fehlgeschlagene Fälle behalten ihre Fehlerursache; sie werden erst nach beobachtetem GREEN geschlossen. Nach Implementierung ohne Ausführung steht ausdrücklich „implementiert, Prüfung offen“. Keine Teilaufgabe oder Gesamtintegration nur wegen geschriebener Tests abschließen.


## Ergänzung: Benchmark-Artefakte

run-snapshot-capture.ps1 lehnt jetzt fehlende Klassen und Quellen ab, die neuer als die Produktklassen sind; zugehörige innere Klassen werden ebenfalls erfasst. Quellen- und Binärhashes gehen ins Manifest. Die PowerShell-Syntax wurde lesend geprüft, ohne das Script auszuführen. Noch offen: Stale-/Missing-Class-Verweigerung als gezielter Kontrolltest und frischer, geschützter Smoke nach K1/K4. Die Zeitstempelprüfung ist kein Beweis einer vollständig reproduzierbaren Übersetzung. Keine neue Probe vor frischem Produktbuild.
