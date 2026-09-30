# KEP-Abgrenzung und verhaltensbasierte Nachfrage

Stand 10.09.2026. Recherche und Modellvergleich; Forschungsoptionen sind von implementierten Funktionen getrennt.

## Umgesetzte Abgrenzung

`lsp_exclude_above: 1000` entfernt komplette LSP-Strassenbeobachtungen mit Wert >1000 vor der PLZ-Aggregation. Genau 1000 bleibt enthalten. Ausgeschlossen werden Am Berkhopsfeld (7361) und Stockholmer Allee (4606): 11967 von 97906, verbleibend 85939. Es werden keine normalen Sockelmengen der ausgeschlossenen Strassen behalten. Diese Regel definiert auf Nutzeranweisung den Zielmarkt; die Fahrzeugart ist dadurch nicht nachgewiesen.

Der Filter gilt im Hauptfit, der lokalen Anbieterkorrektur, der aktuellen Modellsuche und der Rekonstruktion. Dateien mit ausgeschlossenen Beobachtungen und Mengenbilanzen werden geschrieben, Rohdaten bleiben unveraendert. Eingefrorene Modelle mit anderem Geltungsbereich muessen neu angepasst werden. Historische raw/core-Experimentbefehle verweigern nun eine Konfiguration mit neuem KEP-Geltungsbereich, damit keine widerspruechliche Zielgroesse unbemerkt weiterverwendet wird; stattdessen `model_search` verwenden.

## Erneute Exploration

13 Varianten, drei zufaellige Fuenffach-Aufteilungen, zusaetzlich fuenf raeumliche Gruppen. Auswahl innerhalb der Trainingsgebiete. Zweiter Lauf mit zusaetzlichen Personendaten: fuenf Altersgruppen, fehlendes Alter, Erwerbstaetige und fehlender Erwerbsstatus. Zuordnung ueber vorhandene Gebaeudeschluessel; keine Schaetzung individueller Bestelllabels aus PLZ-Werten.

| Modell | Zufallspruefung | Raeumliche Pruefung |
|---|---:|---:|
| Einfaches Modell | 17.03 % | 17.67 % |
| Raeumliches Modell 5 km | 15.79 % | 16.65 % |
| Log-Ridge 10 ohne Personenmerkmale | 20.12 % ca. | 20.99 % |
| Log-Ridge 10 mit Personenmerkmalen | 18.60 % | 17.15 % |
| Log-Ridge 100 mit Personenmerkmalen | 17.25 % | 23.66 % |

Personenmerkmale sind interessant, aber noch kein stabiler Gesamtsieger. Die uneingeschraenkte Modellauswahl mit Personenmerkmalen erreicht raeumlich 24.42 %; das ist schlechter als die gezielten Kandidaten. Der Bias des 5-km-Modells liegt bei -3.59 % zufaellig und -0.91 % raeumlich. Die Referenzrekonstruktion trifft die 85939 dagegen per auferlegter Datenbindung; das ist kein Vorhersagetest.

## Literatur mit direktem Bezug

**Reiffer et al. (2023), logiTopp:** Deutsches Modell mit Teilnahme am Onlinehandel, bedingter Bestellzahl und Lieferortwahl. Verknuepft logistische und Poisson-Regression mit synthetischen Personen und Wochenverlaeufen. Verwendet unter anderem Alter, Erwerbstaetigkeit, Einkommen und Aktivitaeten; bei uns fehlen Teile dieser Merkmale. Die Struktur ist uebertragbar, die Koeffizienten nicht ohne Pruefung. [Originalartikel, KIT](https://publikationen.bibliothek.kit.edu/1000163706/151590247), DOI 10.1016/j.retrec.2023.101368.

**POLARIS / Argonne:** Haushaltsbezogener zweistufiger Ansatz: Teilnahme und anschliessende Intensitaet. Beruecksichtigt Haushalts- und Umgebungsmerkmale und verlangt regionale Kalibrierung. [Offizielle Modelldokumentation](https://polaris.taps.anl.gov/latest_dev/polaris/theory/demand_model/ecommerce_choice.html).

**Wang und Zhou (2015):** Kombination einer binaeren Entscheidung mit einer zensierten Negativ-Binomial-Modellierung der Lieferhaeufigkeit anhand amerikanischer Haushaltsverkehrsdaten. [Originalartikel](https://www.sciencedirect.com/science/article/abs/pii/S0968090X15002442). Methodische Anregung, keine direkt fuer Hannover gueltigen Bestellraten.

## Empfohlener Modellaufbau: eigene Synthese

1. **Stabile Bestellneigung:** Person oder Haushalt bekommt eine ueber mehrere Wochen stabile Intensitaet. Alter und Erwerbstaetigkeit erklaeren einen Teil, nicht den gesamten Unterschied. Seltene und haeufige Besteller entstehen dadurch, ohne jeden Tag alles neu auszulosen.
2. **Teilnahme plus Anzahl:** Zuerst Aktivitaet im Beobachtungszeitraum; dann positive Bestellzahl. Ein echtes Hurdle-Modell braucht eine bei null abgeschnittene Zaehldistribution in Stufe zwei. Ein normales Poisson-Modell nach Teilnahme ist dagegen ein anderes Mischmodell und kann erneut null erzeugen. Negativ-Binomial ist ein Kandidat fuer zusaetzliche Streuung; deren Parameter lassen sich nicht aus einem Jahresmittel allein identifizieren.
3. **Haushaltskomponente:** Gemeinsame Einkaeufe und individuelle Bestellungen nicht einfach fuer jedes Haushaltsmitglied verdoppeln. Fehlende Haushaltskennungen nicht als Einpersonenhaushalte interpretieren. Zunaechst Zuordnung anhand vorhandener Haushalts-/Gebaeudestruktur vervollstaendigen und mehrere plausible Haushaltsvarianten testen.
4. **Bestellung ist nicht automatisch Paket:** Getrennte Stufe fuer Aufteilung einer Bestellung in Pakete, Buendelung und Lieferverzug. Erst Lieferdaten werden gegen LSP verglichen. Pakete je Bestellung und Lieferverzug benoetigen eigene Daten oder explizite Annahmen.
5. **Anbieter ueber Waren-/Versendergruppen:** Ein persoenliches Konsummuster erzeugt eine Mischung von Bestellungen; diese beeinflusst die Anbieterzuordnung. Dadurch lassen sich dauerhafte Unterschiede erzeugen, ohne anzunehmen, der Empfaenger waehle den Paketdienst selbst. Warengruppen-/Versenderanteile sind derzeit unbekannt und nur als Sensitivitaet zu behandeln.
6. **Gemeinsame Kalibrierung auf mehreren Ebenen:** Externe Bestellteilnahme und -haeufigkeit begrenzen Verhaltensparameter; bereinigte LSP-Mengen begrenzen raeumliche Lieferung. Hermes bleibt zusaetzliche Forminformation. PLZ-, Strassen- und Haushaltsdaten sind unterschiedliche Beobachtungsebenen und duerfen nicht als unabhaengige Wiederholungen derselben Menge zaehlen.

## Drei konkrete naechste Optionen

**A — Sparsames Alters-/Erwerbsmodell plus raeumliche Restkorrektur.** Am schnellsten mit vorhandenen Daten pruefbar. Koeffizienten auf wenige Gruppen begrenzen und gegen das 5-km-Modell vergleichen. Keine unbeobachtete Haushaltsstruktur erforderlich.

**B — Teilnahme-/Haeufigkeitsmodell pro Haushalt.** Groesster Nutzen fuer realistische Nulltage, Vielbesteller und Wochenverlaeufe. Dafuer Haushaltszuordnung reparieren und passende deutsche Befragungsdaten zu Bestellteilnahme/-haeufigkeit erschliessen. Kein identifizierbares vollstaendiges Verhaltensmodell allein aus 53 PLZ-Tagesmitteln.

**C — Warenkorb-/Versender-Mischung.** Interessant fuer Anbieterunterschiede innerhalb einer PLZ. Ohne entsprechende Daten zunaechst nachvollziehbare Szenarien; nicht als geschaetzte reale Anbieterwahl ausgeben.

Empfehlung: A als naechsten messbaren Schritt, B parallel datenmaessig vorbereiten, C erst anschliessend. Ein neues Vollmodell braucht nicht alle drei Komplexitaetsstufen auf einmal.

## Reproduktion

```powershell
python -m hagrid_demand.model_search --config hagrid-demand/configs/improved.json --output hagrid-demand/runs/kep-search-new
python -m hagrid_demand.model_search --config hagrid-demand/configs/improved.json --persons parcel-demand-estimation/input/persons_total.csv --output hagrid-demand/runs/kep-person-search-new
python -m hagrid_demand run --config hagrid-demand/configs/local-carriers.json --run-id kep-reference-new
python -m hagrid_demand.reconstruct --model-run hagrid-demand/runs/kep-reference-new --output hagrid-demand/runs/kep-reconstruction-new
```
