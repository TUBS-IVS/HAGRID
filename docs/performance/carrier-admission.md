# Begrenzte parallele Carrier-Suche

Die erste Tourenerstellung (`Router`, aufgerufen durch `CarrierRouter`) und das validierte Replanning (`ReplanningCoordinator`) lesen optional `resources.carrierAdmissionPolicyFile`. Ohne diese Datei bleibt die Ausführung seriell. Eine höhere `resources.maxActiveSolvers`-Zahl allein hebt diesen Schutz nicht auf; das wird jetzt protokolliert.

```properties
resources.internalSolverThreads=1
resources.maxActiveSolvers=2
resources.carrierAdmissionPolicyFile=memory-policy.json
routing.maximumConcurrentRouters=2
optimization.carriersPerGroup=4
```

Der Dateipfad wird relativ zur HAGRID-Konfigurationsdatei aufgelöst. Die Datei wird einmal beim Erzeugen des jeweiligen Run-Owners gelesen. Änderungen während einer laufenden Gruppe verändern deren Zulassung nicht. Alte Replay-Archive erhalten keine neuen Pflichtfelder und lesen diese Datei beim Wiederherstellen eines Routing-Kontexts nicht.

## Inhalt der Speicherdatei

Schema-Version 1 enthält:

| Feld | Bedeutung |
| --- | --- |
| `schemaVersion` | `1` |
| `maxActiveSolvers` | Kalibrierte Obergrenze gleichzeitig aktiver Carrier |
| `byteBudget` | Obergrenze für die Summe der Carrier-Reservierungen einer Gruppe |
| `heapReserveBytes` | Zusätzliche freie Heap-Reserve |
| `physicalReserveBytes` | Freizuhaltender physischer Arbeitsspeicher |
| `nativeHeadroomBytes` | Reserve außerhalb des Java-Heaps |
| `measurement` | Herkunft und Grenzen der Messung, aus der die Reservierungen abgeleitet wurden |
| `carriers` | Zuordnung der Carrier-ID zu `inputFingerprint` und `reservedBytes` |

Der Fingerprint wird mit `CarrierAdmissionPolicy.inputFingerprint(carrier)` aus den **vorbereiteten** Aufträgen, Fahrzeugen und Metadaten erzeugt. Vorhandene Touren und bekannte abgeleitete Laufattribute werden ausgenommen. Eine fehlende oder nicht mehr passende Zuordnung führt zum Abbruch vor der Carrier-Suche. Die Reservierung muss Aufbau, Suche, Konvertierung, Endprüfung und das noch nicht veröffentlichte Ergebnis abdecken. Änderungen an Netz, Solver, Einstellungen oder Hardware erfordern eine erneute Prüfung der Schätzung; der Eingabefingerprint zertifiziert keine Speicherobergrenze.

Vor jeder nicht leeren Gruppe gilt:

* Worker-Obergrenze: Minimum aus Konfiguration, Speicherdatei und verfügbaren Prozessoren. Bei mehreren äußeren Workern ist genau ein interner jsprit-Thread erforderlich.
* Verfügbares Bytebudget: Minimum aus der Datei, verbleibendem Heap nach Reserve und freiem physischem Speicher nach Reserven. Die Beobachtung findet zwischen Gruppen statt, ohne erzwungene Garbage Collection.
* **Die gesamte Gruppe** muss mit allen Reservierungen passen. Ergebnisse bleiben bis zur vollständigen Veröffentlichung reserviert; ein günstigerer Speicherbedarf bereits fertiger Ergebnisse wird nicht erfunden.
* Ausgabe erfolgt in Eingabereihenfolge. Seeds, Suchbudget, Kosten und Endprüfung bleiben unverändert. Der bestehende Owner schließt den Pool einschließlich Abbruchbehandlung.

Die Prüfung ist konservativ. Unaufgeräumter Heap und bereits gebundene Speicherseiten können eine Gruppe blockieren, obwohl ein späterer GC Platz freigeben könnte. Reservierungen sind Schätzungen, kein Schutz gegen beliebige Fehler oder unterschätzte Daten. Gemeinsam wachsender Cache, Netz und Verkehrsdaten gehören zusätzlich in die Planung des Gesamtbudgets.

`optimization.carriersPerGroup` begrenzt die Replanning-Gruppe. Die Startplanung verarbeitet weiterhin die jeweilige komplette Modalgruppe; die Worker-Zahl teilt diese nicht automatisch in kleinere Veröffentlichungsgruppen. Deren gesamte Reservierung muss deshalb passen. Eine Datei aus dem kleinen lokalen Versuch darf nicht unverändert für die Startplanung der gesamten Region eingesetzt werden.

## Cache-Grenzen auseinanderhalten

`routing.maximumCacheEntries` und `routing.maximumConcurrentRouters` begrenzen den neuen Kontext-Router. Ein kleineres Limit reserviert nicht automatisch weniger RAM: Der Cache legt nur tatsächlich verwendete Einträge an. Zu kleine Grenzen können massive Neuberechnungen verursachen.

Die native erste Tourenerstellung benutzt weiterhin `NetworkBasedTransportCosts` mit einem separaten nativen Cache. Die genannte Grenze darf deshalb nicht als Obergrenze für sämtliche Caches der Pipeline verstanden werden. Eine parallele Startplanung benötigt eine eigene Speicherprüfung. Die Speicherdatei behebt nicht rückwirkend den hohen RAM-Bedarf des früheren 1.8-All-Regions-Laufs.

Die Hannover-Messungen, Quellhashes und reproduzierbaren Programme für diese Änderung liegen im Arbeitsverzeichnis `.superpowers/hagrid-pipeline-performance-2026-11-05` des Haupt-Checkouts. Die lokale Parallelmessung verwendet acht Kopien eines echten Carriers mit 53 Aufträgen. Sie ist keine Kalibrierung für acht unterschiedliche große Carrier oder die vollständige Region.
