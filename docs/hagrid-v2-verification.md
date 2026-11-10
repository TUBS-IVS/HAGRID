# HAGRID V2 — Abschlussprüfung, 10.11.2026

**Maßgeblicher veröffentlichter Quellstand:** `566aed5dbcaec3d8d5d771b0c8acb98534efb93a` auf der bereinigten
GitHub-Basis `c9075bb9f53286d95120394d1df511ea5e44dc20`, Freight `a289ac2fd735cab39d4eb4a2416a5f9b677b0160`.
**597 gezielte Java-Tests bestanden**, keine Fehler/Skips, Paketbau erfolgreich.
**40 portable Python-Tests bestanden**; zehn Prüfungen ohne regionale Fixture-Pfade
übersprungen. Mit expliziten lokalen Hannover-Daten bestanden separat alle 13
Producer-Tests. Der Python-Code blieb bei der Historienübertragung bytegleich im Git-Index.

Die Java-Prüfung umfasst V2-Routing, Caches, Abschnitte, Konfiguration, XML,
Python-Exportimport, Architektur und die zusätzlich integrierten GitHub-Regressionen.
Keine MATSim-/QSim-Simulation, kein Vollregions-RAM-Nachweis und keine große Carrier-Matrix.
Maven-/Python-Abhängigkeiten wurden aus der vorhandenen Installation verwendet.

Alle 383 Freight-Klassen im finalen JAR stimmen byteweise mit unserem Eigenbau
überein und sind einmal enthalten. Die drei jsprit-Patchklassen unterscheiden sich
vom Vanilla-2.0.0-Artefakt. Der neue Einstieg ist aus der JAR ladbar.
Genaue Testauswahl und Paket-SHA: [Verifikationsmanifest](hagrid-v2-verification.json).

## Sichere Historie und Integration

Der erste Push wurde vom Repository-Schutz gegen private Alt-Historie blockiert.
Der veröffentlichte Branch wurde deshalb direkt auf dem bereinigten GitHub-Master
aufgebaut; ausschließlich die entwickelten Quelländerungen wurden übertragen.
Die gesperrten Alt-Commits sind keine Vorfahren des Releases. Private Eingabedaten,
alte Notebooks und generierte Benchmark-Läufe wurden nicht wieder aufgenommen.
Der alte lokale Stand bleibt separat gesichert und wurde nicht gepusht.

Neuere GitHub-Änderungen an Ausgabepfaden, Zustellraten, Cache-Schutz und U-Turn-
Diagnosen bleiben erhalten. Der U-Turn-Test prüft die reale Auswahl zwischen
unabhängig nachgerechneten Alternativen; die Cache-Tests isolieren getrennte
Prozess-Szenarien, ohne den Produktionsschutz gegen unpassende Cache-Nutzung
auszuschalten. Der jsprit-2.0-Routenvergleich prüft das zusätzliche Zustellbias-
Attribut separat mit seinem exakten Seed, statt es mit dem alten Bytehash zu verwechseln.

## Zusätzlich vor Veröffentlichung korrigiert

- Die bisher ignorierte synthetische Vier-Stopp-Shapefile-Fixture wird einschließlich
  aller fünf Bestandteile versioniert. Der echte Python-Exportimport-Test läuft im Checkout.
- Die transitive unveränderte Freight-JAR wird vom Paket ausgeschlossen; sie enthält
  keine Klasse, die nicht bereits in unserem geprüften Freight-Modul vorhanden ist.

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

