# Hybride Vorhersage und beobachtungsgebundene Rekonstruktion

Die neue Hybridsuche kombiniert strukturelle Nachfrage, geglaettete Trainingsresiduen und ausschliesslich auf Trainingsgebieten kalibrierte Gesamtmengen. Eigene Residuen werden bei der raeumlichen Trainingskorrektur ausgeschlossen. Varianten: additive Residuen, logarithmische Residuen und 50/50-Mischung mit mengenkalibriertem Basismodell.

```powershell
python -m hagrid_demand.model_search --hybrid-only --config hagrid-demand/configs/improved.json --special-customers C:/Users/bienzeisler/.codex/tmp/panda-demand-review/grosskunden_final.csv --output hagrid-demand/runs/hybrid-new
```

Ergebnis auf Rohmengen: Die logarithmische Hybridvariante reduziert den mittleren Bias von -8.12 Prozent des raeumlichen Modells auf +0.24 Prozent. Der wMAPE verschlechtert sich jedoch von 29.50 auf 34.64 Prozent. Bei raeumlicher Blockpruefung steigt er von 31.02 auf 36.80 Prozent. Keine automatische Uebernahme. Das exakte Treffen einer Trainingssumme garantiert keine bessere Vorhersage in unbekannten Gebieten.

## Referenzjahr rekonstruieren

```powershell
python -m hagrid_demand.reconstruct --model-run hagrid-demand/runs/local-carriers-20260909-v3 --output hagrid-demand/runs/reconstruction-new
```

Dieser eigenstaendige Modus verbindet die vorhandenen Standort-/Anbieterprofile mit harten DHL-PLZ-Randbedingungen. Er skaliert die DHL-Standortmengen innerhalb jeder beobachteten PLZ auf deren Rohdatensumme. Relative DHL-Standortgewichte und modellierte Nicht-DHL-Mengen bleiben erhalten. Die Gesamtmarktmenge und die Anbieteranteile koennen sich deshalb aendern. Dies ist eine explizite bedingte Rekonstruktion, keine neue Marktbeobachtung.

Ergebnis: 53 beobachtete PLZ, Summe 97.906, maximale numerische Abweichung 1.82e-12. Kein kuenstlicher 0-Prozent-Testfehler: Die Zahlen wurden als Randbedingungen verwendet.

`reference_day_sites.parquet` enthaelt standortbezogene Referenzmengen und neue Anteile. `postal_checks.csv` zeigt beobachtete, vorherige und rekonstruierte DHL-Menge samt Korrekturfaktor. `carrier_postal_reference.csv`, Datenhashes und ein Dashboard werden automatisch geschrieben. Diese Referenzwerte sind weder ein konkreter Kalendertag noch automatisch eine Jahresmenge; der Nenner des Rohdaten-Tagesmittels bleibt zu klaeren.

Die genaue Verteilung innerhalb der PLZ, einzelne Strassen und Grosskundenstandorte sind damit noch nicht rekonstruiert. Insbesondere darf ein extremer Sonderstandort nicht als erwiesene hohe Nachfrage aller Empfaenger derselben PLZ interpretiert werden. Die Datei ist ein Referenzartefakt; der bestehende Zukunftsgenerator konsumiert sie noch nicht automatisch.
