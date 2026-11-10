# HAGRID V2: zentral konfigurierter Routing-Einstieg

Stand: 10.11.2026. Lokaler Integrationsbranch `codex/hagrid-v2-rollout`.

## Status

Der neue Einstieg plant genau ein Szenario mit der vorhandenen HAGRID-Pipeline und jsprit.
Er startet keine MATSim-Simulation und erzeugt keine Simulation-BAT-Datei.
Der Branch enthält den entwickelten V2-Stand einschließlich gemeinsamer Refactorings
und der angepassten jsprit-Klassen. Die benötigte Freight-Version wird über den
Git-Untermodul-Commit fest verknüpft. Den Checkout samt Untermodul und Python-Werkzeugen
verwenden; die JAR allein enthält die Vorbereitung der HAGRID-Eingangsdaten nicht.

## Enthaltene Entwicklung

| Bereich | Enthalten |
|---|---|
| jsprit 2.0 | Core/Analysis 2.0.0; IO 1.8 für das bestehende XML-Format; Kompatibilitätstests |
| jsprit-Patches | Korrektur der Cache-Aktualisierung in `RegretInsertionFast`, kompakte exakte Nachbarschaften in `JobNeighborhoodsOptimized`, Iterations-Noise-Kompatibilität in Freight |
| Speicher und Laufzeit | Begrenzte Transportkosten-Caches, getrennte neutrale Kosten, Router-/Snapshot-Wiederverwendung, aufgeräumte Worker-Lebensdauer und begrenzte Parallelität |
| Routenbewertung | Konsistente Wendeaufschläge einschließlich nativer Supply-Suche; vollständige Endprüfung und genaue XML-Zeiten |
| Haltepositionen | HAGRID-Kundenposition, Quelle, Seite und Längsposition bleiben von Python bis Java erhalten; zulässige Anfahrten werden mitgesucht |
| Abschnitte | Längenbegrenztes Zusammenfassen, Teilstrecken und Fahrzeiten, Wiederauflösung in ursprüngliche Services mit abschließender Prüfung |
| Datenvorbereitung | Automatisches HAGRID-eigenes Inputpaket, Prüfung gegen das tatsächliche Java-Netz; keine zusätzlichen Straßendownloads oder Fußwegkosten |
| Gemeinsame Struktur | Zentrale Konfigurationsauflösung, reproduzierbare Seeds, Kosten-/Tourregeln und passende Hannover-/Lausitz-Verbraucher einschließlich modularer Planung |
| Nachvollziehbarkeit | Regressionstests, Benchmark-Werkzeuge, Test-Fixtures und bisherige Entwicklungsdokumentation; erzeugte Benchmark-Läufe sind kein Teil des Releases |

Die gepatchten Klassen werden beim Paketieren gezielt anstelle der Originalklassen
verwendet; die Paketprüfung vergleicht die tatsächlich eingebauten Klassen.
`routing.sectionFastRegret` bleibt standardmäßig `false`: Der korrigierte schnelle
Pfad ist enthalten, wird aber nicht pauschal für jeden Fall eingeschaltet.
Ein späterer eigener jsprit-Fork kann diese drei Kern-Patches übernehmen, ohne
HAGRID-spezifische Halte- und Straßenmodelle in jsprit zu verschieben.

## Eine Konfigurationsdatei

`config/hagrid2-sections.properties` ist das eigenständige Profil. Es lädt nicht zusätzlich
`config/hagrid.properties`. Nicht gesetzte Werte verwenden die vorhandenen Java-Defaults.

| Schlüssel | Arbeitswert | Bedeutung |
|---|---:|---|
| `run.concept` | basecase | Ein Szenario |
| `run.date` | 2025-05-13 | Tag des vorhandenen HAGRID-Inputs; vor eigenem Lauf anpassen |
| `run.tag` | v2 | Kennung für Ausgaben; für Varianten ändern |
| `demand.filterRegions` | Hannover | Regionalfilter |
| `routing.sectionMaxSpanMeters` | 200 | Maximale Gruppierungsspanne entlang der Straße; kein Mindestabstand zwischen Stopps |
| `routing.sectionAllowStreetCrossing` | false | Kundenseite bedienen; true erlaubt die gegenüberliegende Straßenseite als Halteoption |
| `routing.sectionUnknownTurnPolicy` | NETWORK_REVERSAL | Unklare Netzumkehrungen bleiben als unklar markiert und erhalten einen weichen Aufschlag |
| `tourRules.uTurnPenaltyCost` | 0.1 | Zusätzliche generalisierte Kosten je Wendung; keine Sekunden und kein Verbot |
| `optimization.initialIterations` | 100 | Suchbudget der initialen Tourenplanung |
| `optimization.offlineIterations` | 100 | Suchbudget der Offline-Abschnittsoptimierung |

200 m und 0.1 sind Arbeitswerte, keine nachgewiesenen realistischen Optima.
Eine zweite Anfahrtsrichtung entsteht nur aus den zulässigen, nachgewiesenen
Netzrichtungen. Das Einschalten der Straßenquerung erfindet keine Gegenrichtung
auf einer Einbahnstraße. Es wird kein Fußwegmodell eingeführt.

## Lokal starten

Java 21; Main-Klasse `hagrid.hannover.HagridV2RoutingRunner`;
Programmargument `config/hagrid2-sections.properties`; Arbeitsverzeichnis: Repository-Wurzel.
Der Runner verwendet die bestehenden HAGRID-Pfade; Inputpfade bei Bedarf über die
vorhandenen zentralen Pfadschlüssel konfigurieren. Fehler werden weitergeworfen.
Der alte `HAGRID2MATSimPipelineRunner` bleibt ein separater Mehrszenario-Einstieg.

Die Python-Umgebung muss das aktuelle HAGRID-Demand-Paket samt Abhängigkeiten enthalten.
Ein frischer Export muss `hagrid-section-inputs.json`, `section_stops.parquet`
und `section_streets.parquet` neben dem Demand bereitstellen. `sectionHagridInputs=auto`
findet dieses Paket; es werden ausschließlich HAGRID-Eingangsdaten verwendet.
Bei Bedarf `routing.sectionEvidencePython` auf den passenden Python-Interpreter setzen.

## Auf einem anderen Rechner installieren

Voraussetzungen: Git, Java 21, Maven 3.9 und Python >=3.11. Die ersten Maven-/pip-
Installationen benötigen Zugang zu den konfigurierten Paketquellen.

```shell
git clone --branch codex/hagrid-v2-rollout https://github.com/TUBS-IVS/HAGRID.git
cd HAGRID
git submodule update --init external/matsim-libs
python -m venv .venv
```

Die Umgebung aktivieren (Windows PowerShell: `.venv\Scripts\Activate.ps1`,
Linux/macOS: `source .venv/bin/activate`), danach:

```shell
python -m pip install -e "hagrid/demand/model[test]"
mvn -pl hagrid/simulation -am -DskipTests package
java -cp hagrid/simulation/target/hagrid-1.0-SNAPSHOT.jar hagrid.hannover.HagridV2RoutingRunner config/hagrid2-sections.properties
```

Vor dem letzten Befehl die eigenen vorhandenen HAGRID-Eingangsdaten bereitstellen:
Netz, Fahrzeugtypen, Hubs, Geodaten und den frischen Demand-Export für `run.date`.
Siehe `hagrid/simulation/input/README.md` für die bestehende Eingabestruktur.
Diese Daten und Simulationsergebnisse werden nicht durch einen Code-Checkout erzeugt.
Das Profil ist ein Einzelszenario-Einstieg; ein großer Hannover-Tag kann entsprechend
lange rechnen und benötigt passend bemessenen JVM-Heap. Die Kurztests sind kein
RAM-Nachweis für die ganze Region.

Den festgeschriebenen Untermodulstand mit `git submodule update --init` verwenden,
nicht ungeprüft `--remote`. Ein vorhandener Checkout wird mit `git switch
codex/hagrid-v2-rollout`, `git pull --ff-only` und dem Untermodul-Update aktualisiert;
vorher eigene Änderungen sichern.

## Bekannte Grenzen

- Das Abschlussreview hat die Gegenrichtungsprüfung verschärft: gleiche Endpunkte,
  Länge und ein zusammengesetzter Linkname reichen nicht als Nachweis derselben
  Straße. Ohne belastbare gemeinsame Straßenidentität wird keine Gegenrichtung
  erfunden. Im bestehenden 42-Service-Prüfausschnitt sind damit 38/42 kundenseitig
  beziehungsweise 41/42 bei erlaubter Querung zuordenbar. Bei ungeklärten Services
  wird kein unvollständiger Gesamtplan veröffentlicht; der Vorbereitungsbericht
  nennt die betroffenen Stellen. Diese älteren Daten benötigen noch eindeutige
  Zuordnungsnachweise, bevor dieser Ausschnitt vollständig mit Abschnitten läuft.

- Abschnittspläne sind derzeit nicht für wiederholtes MATSim-Replanning freigegeben.
  Der bestehende BASECASE-Simulationsstart lässt außerdem den dafür benötigten
  statischen Null-Iterationslauf nicht zu. Dieser Einstieg ist daher für die Tourenplanung.
- Die bekannte XML-Warnung zum `Hub`-Objekt besteht: `hubId` wird gespeichert,
  das vollständige Java-Objekt ohne Converter nicht. Dieses Release ändert das nicht.
- 200 m und Wendeaufschlag 0.1 sind keine empirisch kalibrierte allgemeine Empfehlung.
  Straßenquerung bleibt eine Modellannahme, keine Garantie für reale Querbarkeit.
- Zusammengefasste Abschnitte vereinfachen die Suche; der Export enthält anschließend
  wieder die ursprünglichen Service-Aktivitäten. Weniger jsprit-Jobs bedeutet hier
  nicht automatisch weniger Zustellungen in MATSim.

## Verifikation und Review

Gezielte Tests und Paketprüfung werden vor dem Push an einem frischen Checkout
ausgeführt. Kein MATSim-Lauf und keine große Carrier-Testmatrix. Das genaue Ergebnis
steht in `docs/hagrid-v2-verification.md`; daraus darf keine neue Qualitäts- oder
Laufzeitüberlegenheit für sämtliche Carrier abgeleitet werden.
