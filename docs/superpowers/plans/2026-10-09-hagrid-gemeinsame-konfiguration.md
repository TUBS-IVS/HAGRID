# Die gemeinsame HAGRID-Konfiguration – einfach erklärt

> **Aktualisierung 10.10.2026:** Die folgenden Testzahlen und Paketnachweise vom 09.10. sind historische Belege. Seitdem wurde die Audit-Serie T01–T16 im Quellcode bearbeitet; dieser neue Stand ist noch UNVERIFIED und nicht vollständig abgenommen. Aktueller Überblick und offene Pflichtprüfungen: [Audit und Refactor einfach erklärt](2026-10-10-hagrid-audit-fixes-ergebnisse.md). Die automatische Ressourcenüberwachung bleibt auf Nutzerwunsch ausgeschaltet; Tests behalten den Ressourcenschutz.


Stand: 9. Oktober 2026. Produktcode im Arbeitsbranch `codex/zone-router-fixes`, noch nicht veröffentlicht.

## Was sich geändert hat

Du kannst die HAGRID-Einstellungen in **einer nach Themen sortierten Datei** pflegen:
[`config/hagrid.properties`](../../../config/hagrid.properties). Aus dieser Datei erhalten Bedarfserzeugung,
Planung und Simulation ihre Einstellungen. Intern bleiben kleine Bausteine für DRT, Supply, Scoring,
Fahrzeugabfahrten, modulare Umschläge und Lausitz-Wellen getrennt. So wird aus der zentralen Datei keine riesige unübersichtliche Java-Klasse.

**Die gemeinsame Tourdauer ist standardmäßig sieben Stunden.** Die unabhängige historische
7,5-Stunden-Regel für Bedarf und Scoring ist entfernt. Auch modulare Touren übernehmen ohne explizite
Sonderregel sieben Stunden. Eine Tageszeit wie 07:30 ist weiterhin der Beginn eines Zustellfensters;
sie ist keine Arbeitsdauer von 7,5 Stunden.

## So benutzt du die Datei

Für einen Simulationsstart ergänzt du das bestehende Szenarioargument:

```text
config=config/hagrid.properties,concept=basecase,date=2025-05-13,tag=meinTest
```

Im Hannover-Builder:

```java
ScenarioConfig.builder()
    .configurationFile(Path.of("config/hagrid.properties"))
    .build();
```

Die erzeugten Startargumente reichen dieselbe Datei weiter. Bei Verwendung der Datei setzt der Generator
keine versteckten Standardwerte für MATSim-/jsprit-Iterationen darüber. Explizite optionale Builder-Werte
für Tourdauer und Lastenradgeschwindigkeit behalten Vorrang. Ein direkt gestarteter Java-Verbraucher kann
`hagridConfig.loadConfiguration(path)` verwenden.

Ein `#` am Zeilenanfang bedeutet: **Beispiel ausgeschaltet, den vorhandenen Profilwert erben**.
Besonders Datenpfade, Anbieterlisten und Tagesfenster solltest du nur aktivieren, wenn sie zu deinem Datensatz passen.

## Welche Bereiche du einstellen kannst

| Bereich | Beispiele | Wirkung |
|---|---|---|
| Touren und Scoring | Dauer, Puffer, Tagesende, Bedienzeiten, Strafen | Bedarf, Planung, Fahrzeugfenster und Bewertung verwenden zusammenpassende Regeln. |
| Suche und Cache | Iterationen, Replanning-Beginn, Gruppengröße, Cachegrenzen, Zeitraster | Suchaufwand und Speicherbedarf lassen sich getrennt steuern. |
| Fahrzeuge | Lastenradtempo, aktive Größen, Anbietergrößen, Abfahrten und Streuung | Fahrzeugerzeugung bzw. Routing übernimmt diese Werte; die Fahrzeug-XML bleibt für Typen/Kapazitäten maßgeblich. |
| Bedarf und Hubs | Zustellquoten, Anbieterlisten, Hubgrenzen, Locker und Netzfilter | Vor der Bedarfserzeugung laden; vorhandene Auftragsdateien werden dadurch nicht nachträglich verändert. |
| Supply | Kapazität, Bedienzeit, frühe/späte Fenster und Zufallsstreuung | Zubringererzeugung verwendet dieselbe konfigurierte Zeitbasis. |
| Lausitz LMD | Wellenstunden, Fahrzeugkopien, Zufallsseeds, B2C-/B2B-Quoten | Änderungen erreichen Builder und Retimer und gehören zur Cacheidentität. |
| DRT | Halte-/Wartezeiten, Raster, Rebalancing, Betriebsfenster, Kapazität, PT-Anbindung | HAGRID-Komposition und vorbereitete DRT-Dateien berücksichtigen die relevanten Werte. |
| Shared-Use | Sitze, Paketplätze, Ladezeit, Anmeldefrist, Paketseed | Nur aktive Paketmodelle müssen deren zusammenhängende Regeln erfüllen. |
| Modular | Umschlagdauer, Vorlauf, Bootstrapfaktor, Budget-/Abfahrtsoptionen | Dispatcher, tatsächliche Aufgaben, Zeitaktualisierung und Kennzahlen verwenden dieselbe Uhr. |
| Dateien und Ergebnisse | Rohdaten, vorbereitete Netze/Pläne, Change Events, Cache und Ausgaben | Einmal erfasste Dateipfade werden konsequent an die betreffenden Verbraucher weitergegeben. |

Es ist eine gemeinsame Oberfläche für die **HAGRID-eigenen Einstellungen**, kein Ersatz für die gesamte
native MATSim-XML. Native Strategieentscheidungen und fachliche Modellvarianten bleiben erhalten.
Beispielsweise setzt die modulare Strategie ihre eigenen Reisezeitparameter; eine allgemeine DRT-Einstellung
darf diese Strategie nicht unbemerkt ändern. Algorithmische Konstanten werden nicht als scheinbar wirksame Schalter angeboten.

## Vorrang, Einheiten und Fehler

Der Vorrang lautet: **Standard → Szenarioprofil → Datei/Laufwerte → explizite Startwerte**.
Zum Beispiel überschreibt `tourRules.maxRouteDurationSeconds=14400` am Start die sieben Stunden aus der Datei.
Die alten Startnamen bleiben kompatibel. `maxTourDuration` bleibt als alter CLI-Schalter auf modulare
Szenarien begrenzt; für eine gemeinsame Regel den kanonischen Namen oder `solverMaxRouteDuration` verwenden.
Widersprüchliche Aliasse innerhalb derselben Quelle werden abgewiesen. Ein höherrangiger CLI-Wert darf einen Dateiwert ersetzen.

- Zeiten mit `Seconds` sind Sekunden; Tageszeiten sind Sekunden seit Mitternacht. `25200` bedeutet sieben Stunden.
- `DispatchHours` sind Stunden von 0 bis 23. `JitterMinutes` sind Minuten; Supply-Streuungen mit `Seconds` sind Sekunden.
- Lastenradtempo ist km/h, Netzgeschwindigkeit mit `Mps` ist m/s. Standard fürs Lastenrad: 20 km/h.
- Kommagetrennte Listen funktionieren in der Datei und bei den dafür vorgesehenen Startoptionen.
- Pfade in der Datei sind relativ **zur Datei**. CLI-Pfade sind relativ zum Arbeitsverzeichnis. Schreibe Windows-Pfade mit `/`, beispielsweise `C:/Daten/netz.xml.gz`, damit das Properties-Format sie eindeutig liest.
- UTF-8, bekannte Schlüssel und eindeutige Einträge sind verpflichtend. Tippfehler, ungültige Zahlen,
  fehlerhafte Zeitfenster und unvollständige adaptive Suchgrenzen erzeugen eine verständliche Fehlermeldung.

Die Datei wird pro gestarteter Konfiguration einmal erfasst. Ein späteres Bearbeiten verändert einen
laufenden Snapshot nicht. Der Simulationsbericht enthält Dateipfad, SHA-256 und aufgelöste Planungswerte
mit Herkunft. Ein separat gestarteter Prozess liest seine Datei neu; zwischen Bedarfserzeugung und Simulation
sollte daher dieselbe unveränderte Datei verwendet werden.

## Welche konkreten Fehler repariert wurden

- Akzeptierte Zustellquoten und Listen aus Startoptionen erreichten bisher teilweise ihren Verbraucher nicht.
- Der Start konnte einen anderen jsprit-Wert anzeigen als der tatsächlich aufgelöste Optimierungsdienst benutzte.
- Gemeinsame und modulare Aliasse konnten den Vorrang von CLI gegenüber Datei verhindern.
- Ein normalisierter Versandpunktordner wurde mit dem Dateinamen falsch zusammengeklebt; unter Windows
  konnte zusätzlich der Anbieter falsch aus dem ganzen Pfad statt aus dem Dateinamen gelesen werden.
- Relative Supply-Eingaben wurden vom falschen Verzeichnis aus gesucht.
- Reines Personen-DRT prüfte ungenutzte Paket-Sitz-/Anmeldefristen und konnte dadurch unnötig scheitern.
- Manche ungültigen Dateien wurden erst nach dem Anlegen von Ausgaben erkannt. Dateicapture und aktive
  Projektionen werden jetzt vor den entsprechenden Lauf-/Cache-/Kopierschritten geprüft.
- Ungültige UTF-8-Zeichen wurden still ersetzt; solche Dateien werden jetzt abgewiesen.

## Was du bei Änderungen beachten musst

Neue Tourregeln, Wellen und Zustellquoten können Ergebnisse bewusst verändern. Geänderte Lausitz-Regeln
gehen in den Cachekontext ein; geänderte DRT-Eingaben erfordern neu vorbereitete Dateien.
Es gibt weiterhin eine separate konfigurierbare native Fahrer-Sicherheitsgrenze. Die gemeinsame Tourgrenze
bleibt die eigentliche Sieben-Stunden-Vorgabe. Tagesfenster und Verfügbarkeitspuffer haben andere Aufgaben.

Vroom und Valhalla haben weiter Vorrang. Eigene Prüfungen laufen seriell mit einem sichtbaren Prozessor,
512 MiB Maven-/384 MiB Test-Heap und mindestens **6 GiB freier RAM-Reserve**. Bei zu hoher Hintergrundlast
oder Messfehlern wird nur der eigene Lauf zurückgestellt bzw. beendet. Diese Prüfgrenzen steuert der
Ressourcenschutz außerhalb der fachlichen HAGRID-Datei; die Produkt-Solvergrenzen stehen im Abschnitt Ressourcen.

## Was noch offen ist

Frisch bestanden: **1466/1466 kleine HAGRID-Tests aus 175 Testquellen**, Paketbau und anschließend vier
eigene Replay-Prozesse mit genau diesem Paket (Services 100,25; Shipments 94; zwei Suchiterationen).
Die 14 neuen Review-Gegenproben und der reale Versandpunktleser sind enthalten. Alte Paketnachweise werden
nicht auf neue Quellen übertragen. Vollständige Produktionsszenarien und größere Parallelvergleiche
bleiben während Vroom zurückgestellt. Die bereits dokumentierten nativen jsprit-2.0-Abweichungen,
Kalibrierungs-/Datensatzfragen und späteren jsprit-internen Optimierungen bleiben eigene offene Arbeitspakete.
Aus dem Refactor folgt keine belegte allgemeine Beschleunigung oder Ergebnisverbesserung.

Nachweise: [Fortschrittsübersicht](2026-10-08-hagrid-jsprit-open-checks.md),
[Ergebnisbericht](2026-10-07-hagrid-jsprit-implementation-results.md).
