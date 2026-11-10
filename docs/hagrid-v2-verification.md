# HAGRID V2 — Abschlussprüfung, 10.11.2026

## Umfang

Alle entwickelten Quellcodeänderungen, gemeinsamen Refactorings, Caches,
jsprit-Patches, Halte-/Abschnittsplanung, Konfigurationen, Tests und Werkzeuge
werden zusammen gesichert. Dazu gehört der separat festgeschriebene Freight-
Untermodulstand. Erzeugte Benchmark-Ergebnisse, lokale Eingabedaten und lokale
Arbeitsverzeichnisse gehören nicht zum Code-Release.

## Vorprüfung am Arbeitsstand

- Java 21, Maven-Reaktor `external/freight` + `hagrid/simulation`, gezieltes `verify`:
  29 Freight-Tests und 407 HAGRID-Tests bestanden, keine Fehler/Skips, circa 46 Sekunden.
- Die paketierten Klassen `RegretInsertionFast`, `JobNeighborhoodsOptimized`,
  `InsertionNoiseMaker`, `NetworkBasedTransportCosts`, `CarrierPlanWriter` und
  `HagridV2RoutingRunner` sind jeweils genau einmal enthalten und bytegleich mit
  den aktuell kompilierten Quellen. Der Einstieg lässt sich aus der JAR laden.
- Python-Stop-/Export-/Metadaten-/Bundle-/OOH-Tests vor der Reviewkorrektur:
  38 bestanden; zehn lokale Datenprüfungen ausdrücklich ohne Fixture-Pfade übersprungen.

Die endgültige Prüfung aus dem frisch ausgecheckten Commit wird unten ergänzt.
Kein MATSim-/QSim-Lauf, keine große Carrier-Testmatrix und kein Vollregions-RAM-Test.
Maven-/Python-Abhängigkeiten werden aus der vorhandenen lokalen Installation genutzt;
ein frischer Checkout ist kein Nachweis einer kompletten Installation auf einem
anderen Betriebssystem oder ohne bereits verfügbare Paketquellen.

## Review: gefunden und korrigiert

**Unbelegte Gegenrichtung:** Bei einem zusammengesetzten Link ohne `osm:way:id`
konnte allein der Bindestrich im Namen zusammen mit Endpunkten/Länge eine zweite
Anfahrt freigeben. Ein portabler Gegenbeispieltest reproduzierte die falsche
Freigabe. Ohne gemeinsame, explizite Straßenidentität bleibt die Gegenrichtung
nun ausgeschlossen. Auch die künstliche gemeinsame `H03_CHAIN`-Identität bei
Umzuordnungen wird nicht mehr als Nachweis genutzt.

Das legt eine reale Datenlücke offen: Im bisherigen 42-Service-Ausschnitt sind
38 kundenseitige beziehungsweise 41 querungsfähige Zuordnungen belegbar. Die
Vorbereitung verweigert bei offenen Fällen die Veröffentlichung des Gesamtmanifests.
Die vollständige alte Zuordnung war hier kein belastbarer Erfolgsnachweis.

**Portabilität:** Die bisherigen Anleitungen verwiesen noch auf den 26-Szenarien-
Runner. Sie zeigen jetzt den Einzelszenario-Einstieg. Der synthetische OOH-Test
braucht keinen Benutzerpfad mehr; regionale Fixture-Prüfungen verwenden explizite
Umgebungsvariablen statt eingebauter Rechnerpfade.

## Review: geprüfte Bereiche

Statische Quellenprüfung der Cache-Synchronisierung/-Lebensdauer, der Aktualisierung
aller veralteten Regret-Alternativen, stabiler Nachbarschaftsreihenfolgen sowie der
Wendekosten in Suche und abschließender Routenbewertung ergab dort keinen weiteren
konkreten Blocker im statischen V2-Pfad. Dazu kommen die ausgewählten Regressionstests.
Das ist keine Garantie auf Fehlerfreiheit; insbesondere beliebige zeitabhängige
Routing-Callbacks und MATSim-Replanning sind nicht damit freigeprüft.

## Gezielte spätere Verbesserungen, noch nicht gemessen

1. `SectionSearchState`: prüfen, ob der Cache-Abfahrtsschlüssel redundant ist, wenn
   die Bewertung ihre Abfahrtskandidaten ohnehin neu bestimmt; erst Gleichheit nachweisen.
2. `SectionRouteEvaluator`: unveränderte Tourpräfixe wiederverwenden, dabei sämtliche
   Anfahrtsalternativen und Zeitfensterzustände erhalten.
3. Native Pfadkosten und Wendezählung bei Cache-Misses aus derselben Pfadberechnung gewinnen.
4. In jsprits Nachbarschaftsaufbau exakte stabile Top-k-Auswahl statt vollständiger
   Sortierung prüfen, sofern k deutlich kleiner als die Jobanzahl ist.

Die kompakte Nachbarschaftsrepräsentation und die Korrektur des schnellen Regret-
Caches sind bereits implementiert. Weniger Kandidaten zu betrachten ist keine
qualitätsneutrale Ersatzoptimierung. Ein eigenständiger jsprit-Fork ist eine spätere
Packaging-/Wartungsaufgabe; die jetzige JAR enthält bereits die optimierten Klassen.

## Endgültiges Ergebnis aus dem unabhängigen Checkout

Quellstand: `7a6d9c10c74cf206214f28d1e80599828937c70c`, Freight: `a289ac2fd735cab39d4eb4a2416a5f9b677b0160`. Die nachfolgende Dokumentation
ändert den geprüften Java-/Python-Quellstand nicht.

- Vollständiger Neuaufbau und Paketierung aus versionierten Dateien erfolgreich.
- **436 gezielte Java-Tests** erfolgreich, ohne Fehler oder Skips.
- Nach Aufnahme der zuvor ignorierten synthetischen Shapefile-Fixture und Ausschluss
  der doppelten unveränderten Freight-JAR: **23 zusätzliche Erzeugungs-/Architekturtests**
  erfolgreich und finales Paket erneut gebaut.
- **40 portable Python-Tests bestanden**; zehn regionale Prüfungen ohne lokale Daten
  ausdrücklich übersprungen. Separat bestanden **alle 13 Producer-Tests mit den
  explizit bereitgestellten lokalen Hannover-Fixtures**, einschließlich der erwarteten
  Ablehnung der vier beziehungsweise einen ungeklärten Zuordnung.
- **Alle 383 Freight-Klassen** im finalen Paket sind einmalig enthalten und bytegleich
  mit dem festgeschriebenen Eigenbau. Die drei jsprit-Patchklassen sind bytegleich
  mit unseren Klassen und unterscheiden sich vom Vanilla-2.0.0-Artefakt.
- Der Runner wird aus der fertigen JAR geladen und meldet bei fehlendem Profil den
  erwarteten Aufrufhinweis. Kein vollständiger Hannover-Tag wurde gestartet.

Die Fixture-Lücke und die doppelte Freight-Paketquelle wurden vor dem Push korrigiert.
Details und genaue Testauswahl: [Verifikationsmanifest](hagrid-v2-verification.json).
Für einen Wiederholungslauf kann dessen `javaTestSelector` als Maven-`-Dtest` verwendet
werden; `-Dsurefire.failIfNoSpecifiedTests=false` ist im Zwei-Modul-Reaktor erforderlich.
