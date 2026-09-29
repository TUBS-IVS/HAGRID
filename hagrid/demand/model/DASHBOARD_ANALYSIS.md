# Auswertung des automatischen Run-Dashboards

Run: `hannover-foundation-dashboard-20260909`, 9. September 2026. [Dashboard öffnen](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/runs/hannover-foundation-dashboard-20260909/dashboard.html).

Die folgenden Befunde beschreiben Standort- und Zuordnungsqualität. Es wurde noch keine Paketnachfrage geschätzt. „Offen“ bedeutet: kein eindeutiger geometrischer DHL-Kandidat unter der aktuellen Regel (eindeutige PLZ, nächstgelegene DHL-Linie, höchstens 100 m). Auch eindeutige Kandidaten sind fachlich noch unbestätigt.

## 1. Die Gesamtquote verdeckt die B2B-Lücke

| Bestand | Insgesamt | Offene Standorte | Anteil |
|---|---:|---:|---:|
| Private Gebäudeeinheiten | 227.641 | 3.463 | 1,5 % |
| Betriebsstätten | 52.931 | 5.416 | 10,2 % |
| Zusammen | 280.572 | 8.879 | 3,2 % |

An offenen Standorten liegen **10.516 Einwohner (0,9 % des Personenbestands)** und **85.380 Beschäftigte (13,1 % des Beschäftigtenbestands)**. Der Gesamtwert von 96,8 % eindeutigen Kandidaten ist deshalb kein ausreichendes Qualitätsurteil für ein B2B-Modell. Beschäftigte sind eine strukturelle Größe, keine direkte Schätzung fehlender Pakete.

Priorität: Betriebsstandorte und deren Adress-/Netzzuordnung prüfen, bevor aus der DHL-Passung auf B2B-Intensitäten geschlossen wird. Größere Firmenareale und abweichende Zufahrten sind mögliche Erklärungen; sie sind durch diese Auswertung noch nicht nachgewiesen.

## 2. Unterschiedliche Problemtypen je PLZ

| PLZ | Befund | Nächste Prüfung |
|---|---|---|
| 30938 | 1.945 offene Standorte von 8.507 (22,9 %); darunter 1.711 mit gleich nahen Kandidaten | Überlagerungen und Semantik der DHL-Linien prüfen; eine größere Entfernungsschwelle löst Gleichstände nicht |
| 30855 | 507 von 1.606 Betrieben offen (31,6 %); 6.749 Beschäftigte an diesen Standorten | Gewerbestandorte und tatsächliche Straßen-/Adresszuordnung gezielt abgleichen |
| 30419 | 281 von 1.156 Betrieben offen (24,3 %); 22.761 Beschäftigte betroffen | Hohe Priorität nach Beschäftigtengewicht, auch wenn die absolute Standortzahl kleiner ist |
| 30669 | 17 Standorte, alle offen | Sonderfall mit kleiner Fallzahl; 100 % nicht mit großflächiger Datenlücke verwechseln |

30938 enthält 1.554 private und 157 gewerbliche Standorte mit Gleichständen. Weitere 53 private und 181 gewerbliche Standorte haben keinen Treffer im Suchradius. Diese Trennung ist methodisch relevant: Ein geometrischer Gleichstand erfordert eine andere Lösung als eine fehlende Straßenabdeckung.

## 3. Wiederholte Straßennamen nicht pauschal zusammenführen

75 DHL-Zeilen gehören zu 33 wiederholten PLZ-/Straßenschlüsseln. Von den insgesamt 2.253 Standorten mit gleich nahen Kandidaten betreffen **1.701 denselben Straßennamen**. Bei **1.597 dieser Standorte unterscheiden sich die gemeldeten Werte**; bei 104 sind Namen und Werte gleich.

Das zeigt einen konkreten Prüfbedarf an der Beobachtungsdefinition. Gleicher Straßenname reicht weder zum Summieren noch zum Löschen einer Zeile. Auch gleicher Name und Wert beweist keine Dublette. Erst Originaldaten bzw. Metadaten können klären, ob Fragmente, Richtungen, Teilgebiete oder mehrfach übernommene Beobachtungen vorliegen.

## 4. Distanzwerte und Haushalte richtig interpretieren

Für die 273.946 Standorte mit einem Kandidaten beträgt die Mediandistanz 18,1 m, das 95. Perzentil 54,9 m. Bei Betrieben sind es 22,7 m bzw. 76,9 m. Diese Werte sind auf erfolgreiche Treffer bis 100 m bedingt; die nicht gefundenen Standorte fehlen darin. Sie begründen keine pauschale Ausweitung des Suchradius.

536.496 Personen haben keine Haushaltskennung (46,6 %). Die Gebäudeaggregation erhält dennoch alle 1.150.862 Personen. Das unterstützt den schlanken Start auf Gebäudeebene; es bestätigt keine rekonstruierte vollständige Haushaltsstruktur.

## 5. Umsetzung und Prüfung

Das Dashboard wird als eigene Stage bei jedem erfolgreichen Foundation-Lauf geschrieben. Ein vorhandener Run lässt sich separat auswerten. Sämtliche Tabellen und Karten werden aus gespeicherten Run-Artefakten erzeugt; keine externen Karten- oder Analysedienste werden aufgerufen.

Neun Python-Tests bestanden, einschließlich Prüfungen gegen Doppelzählung bei Gleichständen/PLZ-Grenzen und gegen unvollständige Statusdaten. Ein vollständiger Real-Lauf hat die automatische Dashboard-Stage erfolgreich ausgeführt. Im Browser wurden Darstellung, Betriebsfilter, PLZ-Suche und Kartenmetrik geprüft.

Empfohlene Arbeitsreihenfolge: DHL-Straßensemantik insbesondere für 30938 klären; danach Betriebszuordnungen in 30855 und 30419 priorisieren; erst anschließend die Kalibrierungsbasis freigeben. Die vorhandenen zentralen Summen sind erhalten, aber noch keine nachgewiesen richtigen lokalen Messzuordnungen.
