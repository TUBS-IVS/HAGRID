# Breite Modellsuche

```powershell
python -m pip install -e "./hagrid-demand[research]"
python -m hagrid_demand.model_search --config hagrid-demand/configs/improved.json --special-customers C:/Users/bienzeisler/.codex/tmp/panda-demand-review/grosskunden_final.csv --output hagrid-demand/runs/model-search-new
```

Der Vergleich umfasst 13 vorab definierte Varianten: bisheriges einfaches Modell, gemeinsames Nachfrage-/Anbietermodell, Logit mit acht B2B-Anbietereffekten fuer vier Branchengruppen, positive Ridge-Regression, zwei logarithmische Ridge-Modelle, zwei Poisson-Regressionen, Splines, Random Forest, Poisson-Boosting sowie zwei geglaettete raeumliche Residualmodelle mit 5/15 km Bandbreite.

Alle verwenden denselben Beobachtungsbestand. Die Regressionsmodelle erhalten zusaetzlich Wohnstandortzahl, Betriebszahl, Beschaeftigtensumme, Quadratwurzel-/Logarithmussumme der Betriebsgroessen, Grossbetriebszahl ab 50 Beschaeftigten, Branchenexpositionen, PLZ-Flaeche und Dichten. Keine Merkmale werden aus den zurueckgehaltenen DHL-Zielwerten gebildet. Die raeumlichen Modelle korrigieren die Basisschaetzung ausschliesslich anhand der Trainingsresiduen; geringe lokale Unterstuetzung zieht die Korrektur gegen null.

## Pruefung

- Drei zufaellige Fuenffach-Aufteilungen mit Seeds 42, 73, 101.
- Zusaetzlich fuenf raeumliche Gruppen aus KMeans auf PLZ-Polygonzentren. Keine Pufferzone; benachbarte Gruppen koennen weiterhin aehnlich sein.
- Modellauswahl nur innerhalb der jeweiligen Trainingsmenge: drei innere Folds beziehungsweise die verbliebenen raeumlichen Gruppen. Skalierung, Splines und Ruecktransformation werden nur auf inneren Trainingsdaten angepasst.
- `nested_selection` bewertet die gesamte Auswahlprozedur auf den aeusseren Testgebieten. Die Auswahl der besten nachtraeglich sichtbaren Tabellenzeile waere dagegen explorativ.
- Rohmengen und bedingte Restnachfrage werden separat gerechnet. Die Restnachfrage setzt bekannte PANDA-Grosskundenmengen voraus und validiert keine Grosskundenvorhersage.
- 53 PLZ und bereits in frueheren Experimenten betrachtete Daten: kein abschliessender unabhaengiger Nachweis, keine automatisch errechnete Signifikanz.

Die direkten Regressionsmodelle sagen nur DHL voraus. Eine gute DHL-Prognose identifiziert weder das Gesamtmarktvolumen noch die uebrigen Anbieter. Sie werden deshalb nicht automatisch als vollstaendiges Nachfragemodell in den Hauptlauf uebernommen.

## Ergebnisse und Nachvollziehbarkeit

Jeder Lauf schreibt `protocol.json`, `features.csv`, saemtliche aeusseren Vorhersagen, Auswahl und innere Fehler je Fold, Fehlermeldungen, Daten-/Codehashes, Bibliotheksversion und `dashboard.html`. Zwei Suchlaeufe bleiben getrennt: v1 mit elf Varianten; v2 ergaenzt die raeumlichen Residualmodelle nach Sichtung von v1. Diese Erweiterung ist ausdruecklich explorativ.

Methodische Referenz: [scikit-learn, Nested versus non-nested cross-validation](https://scikit-learn.org/stable/auto_examples/model_selection/plot_nested_cross_validation_iris.html).

## Ergebnis vom 10.09.2026

Kein Fit ist fehlgeschlagen. 37 Tests bestehen, einschliesslich Ausschluss zurueckgehaltener Zielwerte aus den neu eingefuehrten Schaetzern.

| Pruefung | Basismodell | Raeumlich 5 km | Raeumlich 15 km |
|---|---:|---:|---:|
| Rohmengen, Mittel der zufaelligen Aufteilungen | 32.29 % | 29.60 % | 29.50 % |
| Rohmengen, raeumliche Bloecke | 33.76 % | 31.06 % | 31.02 % |
| Bedingte Restnachfrage, zufaellige Aufteilungen | 17.05 % | 15.81 % | 16.23 % |
| Bedingte Restnachfrage, raeumliche Bloecke | 17.67 % | 16.65 % | 17.32 % |

Die Verbesserung hat einen Preis: Bei Rohmengen steigt die mittlere Unterschaetzung beispielsweise von -3.45 % im Basismodell auf -8.12 % bei 15 km. Eine spaetere Korrektur der Gesamtmenge darf nur mit Trainingsdaten kalibriert und erneut geprueft werden.

Random Forest, Boosting und die direkten Regressionsmodelle schlagen das Basismodell hier nicht. Das groessere gemeinsame Modell erreicht bei raeumlich geblockter Restnachfrage 16.88 %, verschlechtert jedoch die Rohmengen. Die breite innere Modellauswahl ist bei Rohmengen instabil: 30.77 % zufaellig, aber 43.12 % raeumlich geblockt. Bei bedingter Restnachfrage erreicht sie 15.92 bzw. 16.94 %.

Folgerung: Raeumliche Residualmodelle sind ein aussichtsreicher, aber noch explorativer DHL-Baustein. Keine automatische Uebernahme als Gesamtnachfrage-/Anbietermodell. Der naechste gezielte Test sollte Raeumlichkeit und Gesamtmengenbias gemeinsam bewerten, mit einer engeren vorab festgelegten Kandidatenmenge.
