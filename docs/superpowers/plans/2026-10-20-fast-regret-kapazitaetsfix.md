# FAST_REGRET: Kapazitätsfehler und Korrektur

## Einfach erklärt

jsprit speichert, wo ein Auftrag in eine Tour eingefügt werden könnte. Nach einer Touränderung prüfte FAST_REGRET teilweise nur die zwei bisher besten Alternativen neu. Eine ältere dritte Alternative blieb im Speicher. Wenn die ersten beiden später wegfielen, wurde die alte Alternative verwendet, obwohl das Fahrzeug inzwischen voll war.

Nachgewiesen im ursprünglichen 120-Aufträge-Vergleich: Seed 54, Iteration 3, Tourlasten 41/39/40 bei Kapazität 40. Ein zeitunabhängiger Minimaltest mit drei Fahrzeugen bestätigt denselben Mechanismus: Last 3 bei Kapazität 2. Ohne betroffene Tracking-Optimierung bleibt der zusätzliche Auftrag korrekt unzugewiesen. Das ist ein Fehler der eingebundenen jsprit-2.0.0-Implementierung, kein Beleg für ein fehlerhaftes Hannover-Nachfragemodell.

## Was geändert wurde

- Jede bereits gespeicherte Einfügeoption der geänderten Tour wird neu berechnet – auch Rang 3 und später sowie gespeicherte Optionen außerhalb einer optionalen räumlichen Nachbarschaft. Nicht mehr zulässige Einträge entfernt der vorhandene native Einfügeprüfer.
- Nachbar-Touren werden nicht mehr anhand eines geometrischen Abstands als garantiert zu teuer verworfen. Abstand ist bei allgemeinen Kostenfunktionen keine gültige Untergrenze für zusätzliche Tourenkosten. Ein eigener Test zeigt, dass dadurch eine neu mögliche günstigere Einfügung vorher übersehen wurde.
- Deklarierte INTRA_ROUTE/INTER_ROUTE-Abhängigkeiten aktualisieren die vollständigen Touralternativen ohne räumliche Unterdrückung. Eine gezielte Gegenprobe zeigte den alten Fehler auch im Modus ohne Tracking, wenn ein Filter nach der Lernphase eine gespeicherte Tour ausblendet.
- Zwischenergebnisse unveränderter Touren bleiben erhalten. Normales REGRET_INSERTION und der produktive Standard FAST_REGRET=false werden nicht umgestellt.

## Technische Einbindung und Wartung

Ein dokumentierter Quellcode-Backport der Klasse `com.graphhopper.jsprit.core.algorithm.recreate.RegretInsertionFast` liegt unter `hagrid/simulation/src/main/java`. Grundlage ist exakt die Maven-Veröffentlichung `com.graphhopper:jsprit-core:2.0.0`; SHA256 der unveränderten Quelldatei: `E50E1E6B0E9AEA35ED76121D735AB004F446886603E7C62FC2DF46EB610535AE`. Apache-2.0-Quellkopf bleibt erhalten. Herkunft und Umfang stehen zusätzlich in `META-INF/hagrid-jsprit-patches.properties`. Kein verändertes JAR im Maven-Cache und kein nur für Tests vorgeschalteter Klassenpfad.

Shade schließt ausschließlich die ursprüngliche gleichnamige Klasse aus der jsprit-core-Abhängigkeit aus. Die regulär kompilierte HAGRID-Backportklasse muss im ausführbaren Paket genau einmal und byteidentisch enthalten sein. Der Paketcheck prüft auch die übrigen jsprit-Klassen gegen die tatsächlich aufgelösten Laufzeitabhängigkeiten. Ein Versionsvertrag im Regressionstest verlangt 2.0.0: Bei einem künftigen Upgrade muss dieser Backport neu geprüft und möglichst entfernt werden.

Die betroffene Implementierung ist der serielle FAST-Regret-Einfüger. Der separate parallele Einfüger besitzt nicht dieselbe AffectedJobTracker-Optimierung; daraus wird hier keine allgemeine Fehlerfreiheit aller jsprit-Einfüger oder optionalen Filter abgeleitet. Explizite räumliche Filter bleiben bei bisher ungespeicherten Nicht-Nachbar-Routen eine Näherung. HAGRIDs Produktionsstandard verwendet diesen Filter nicht.

## Prüfungen und Grenzen

Die gepflegte Testklasse `FastRegretCapacityRegressionTest` umfasst 48 deterministische Kapazitätsfälle (Tracking an/aus, Regret 2/3, Koordinaten vorhanden/fehlend, Filter an/aus, keine/INTRA_ROUTE/INTER_ROUTE-Abhängigkeit), zwei Gegenproben für neu mögliche günstigere Einfügungen und den Versionsvertrag. Echte native Last-, Skill-, Fahrzeug- und Einfügeprüfer werden verwendet. Die drei Fahrzeuge sind endlich; wenn alle voll sind, ist genau ein unzugewiesener Auftrag das richtige Ergebnis.

ROT-Nachweise: `fast-regret-capacity-red-20261020-05`, `fast-regret-capacity-dependency-red-20261020-02` und `fast-regret-bound-red-20261020-01` im bestehenden calendar-day-e2e-Nachweisordner. Frühere Versuche mit Testaufbau-/Classpath-Fehlern sind separat erhalten und zählen nicht als fachliche ROT-Nachweise. Isolierte GRÜN-Prüfung aller 48 Kapazitätsvarianten: `fast-regret-capacity-expanded-green-20261020-02`.

Die Ergebnisse der Gesamtprüfung, Paketkontrolle und erneuten Problem-Seed-Läufe stehen im abschließenden Nachweis unten. Ein behobener Zulässigkeitsfehler ist noch kein Nachweis, dass FAST_REGRET schneller ist oder auf echten Hannover-Carriern bessere Touren liefert. Sichere Aktualisierung kann mehr Arbeit als das fehlerhafte Überspringen erfordern.

## Was der neue Regret-Mix bedeutet

Ein Auftrag wird beim Regret-Prinzip bevorzugt eingeplant, wenn das Verpassen seiner besten Einfügemöglichkeit besonders teuer wäre. Der neue Mix ändert vor allem, welche Aufträge davor aus bestehenden Touren entfernt werden: 40 % räumlich benachbarte Gruppen, 30 % zufällig und 30 % nach besonders hohen Kostenbeiträgen. Anschließend werden sie ausschließlich mit FAST_REGRET wieder eingesetzt. Best/Cheapest Insertion sind in diesem Versuchsprofil ausgeschaltet. Die Gewichte steuern die Auswahlhäufigkeit, nicht die Anzahl der Aufträge. Pro Schritt werden 20–35 % der Aufträge entfernt, begrenzt auf 10–50.

Das ist ein experimentelles Suchprofil. Im bisherigen kleinen Vergleich war es bei gebündelten Aufträgen vielversprechend und bei gleichmäßig verteilten Aufträgen teilweise schlechter. Es wurde deshalb nicht automatisch zum HAGRID-Standard gemacht. Die bisherigen Ergebnisse gelten für die damals getestete Version; nach dem Korrektheitsfix braucht eine Leistungsbewertung einen neuen Vergleich.

## Abschließender Nachweis

Alle 51 neuen Regressionen bestehen im regulären Maven-Testlauf. Frische Gesamtabnahme: 1687 gemeldete Prüfungen aus 207 Testquellen, 0 fehlgeschlagene Prüfungen, 0 Ausführungsfehler und 1 ausgenommene Prüfung wegen fehlender Lausitz-Realdaten. Paketprüfung: 852 kompilierte Reaktorklassen byteidentisch; genau eine korrigierte FAST-Regret-Klasse, ursprüngliche Duplikatklasse ausgeschlossen und Herkunftsmetadaten enthalten. Drei gezielte negative Paketproben verwerfen alte, doppelte und nicht autorisierte jsprit-Klassen korrekt. Eine unabhängige Quellprüfung fand keine verbleibenden kritischen Befunde im finalen Fix.

Die ursprünglichen Problem-Seeds 54 und 53 liefen mit dem frisch gebauten Paket erneut vollständig durch (je 5 s Aufwärmen plus 15 s Budget, tatsächliches Tracking=true). Jede vollständig entdeckte Kandidatenlösung wurde unabhängig auf Kapazität/Bedienung/Zeiten geprüft; keine unzulässige vollständige Kandidatenlösung beobachtet. Alle vier Auswertungspunkte nach 5 und 15 s bedienen 120 von 120 Aufträgen bei 0 harten Verletzungen. Die zeitabhängigen Suchpfade sind nicht zwingend identisch; die zeitunabhängigen ROT→GRÜN-Regressionen liefern den Ursachenbeleg. Die instrumentierten Wiederholungen sind kein fairer Leistungsbenchmark.

Niedrigster gemessener freier physischer Speicher über Gesamtabnahme und Paket-Replays: 10,035 GiB; verbindliche Reserve: 6 GiB. Eigene Prüfläufe liefen geschützt mit einem für die JVM sichtbaren Prozessor und begrenztem Heap. Der globale Produktionsschalter wurde nicht aktiviert.

Ausführbares Paket SHA256: FBAEE5668913D0FFB904C10843591FB21526E22C578D20D99468D9557497F706. Vollständiger gebundener Nachweis: [fast-regret-fix-20261020.json](C:/Users/bienzeisler/.codex/visualizations/2026/10/05/01a10c1f-6d1a-70f2-97cd-4a682036c329/calendar-day-e2e/fast-regret-fix-20261020.json). Rohdaten: acceptance-fast-regret-fix-20261020-01, fast-regret-fixed-package-replay-20261020-01 und fast-regret-package-gate-20261020-01 im calendar-day-e2e-Ordner.