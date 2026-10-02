# Prüfung des Datenfundaments

Stand: 9. September 2026. Implementierter Umfang: Quellenmanifest, Standortverzeichnis, Beobachtungsadapter, räumliche Kandidaten und Qualitätsbericht. Kein Nachfragemodell trainiert.

## Ausgeführt

- Paket lokal als Editable installiert, ohne bestehende Laufzeitbibliotheken zu aktualisieren.
- `python -B -m pytest -q -p no:cacheprovider`: **7 Tests bestanden**.
- Vollständiger CLI-Lauf mit echten HAGRID-Inputs: `hannover-foundation-20260909-v2`, abgeschlossen in etwa 50 Sekunden auf dieser Maschine.
- Bestands- und Zuordnungszahlen stimmen mit dem vorherigen vollständigen Lauf überein.
- Eindeutige Standort-IDs, gültige Kandidatenreferenzen und Populationsbilanz geprüft.
- Im Run gespeicherte Python-Quellhashes stimmen mit dem ausgeführten Paketstand überein.
- Run-Artefakte sind vom Git-Tracking ausgeschlossen. Quellen und bestehende Notebooks wurden nicht verändert.

## Ergebnisse

| Größe | Anzahl |
|---|---:|
| Personen, ohne Verlust erhalten | 1.150.862 |
| Private Gebäudeeinheiten | 227.641 |
| Betriebsstätten | 52.931 |
| Standorte insgesamt | 280.572 |
| LSP-Linien | 12.342 |
| Hermes-PLZ/Jahr-Zeilen | 159 |
| Eindeutiger geometrischer LSP-Kandidat innerhalb PLZ und 100 m | 271.693 |
| Gleich weit entfernte LSP-Kandidaten | 2.253 |
| Kein LSP-Kandidat innerhalb Schwelle/Abdeckung | 6.618 |
| Keine PLZ-Abdeckung | 8 |

Alle Gebäudeeinheiten erfüllen die konfigurierte Koordinatentoleranz von 5 m. Das bestätigt interne Konsistenz, nicht die reale Lage oder Aktualität der Koordinaten. 75 LSP-Zeilen haben einen wiederholten PLZ-/Straßennamenschlüssel; deren Bedeutung muss geklärt werden. 765 LSP-Zeilen enthalten gemeldete Nullwerte, die erhalten bleiben.

Die Nähezuordnung ist keine validierte Beobachtungszuordnung. Es gibt keine automatisch erzeugten Allokationsgewichte und keine Disaggregation der LSP-Paketmengen. `calibration_ready` bleibt false. Fehlende Einheiten, Referenzzeiträume, Standortherkunft und PLZ-CRS-Metadaten werden im Bericht benannt.

Lokaler vollständiger Bericht: [report.md](C:/Users/bienzeisler/Documents/GitHub/HAGRID/hagrid-demand/runs/hannover-foundation-20260909-v2/report.md).

## Reproduktion

Aus dem Repository-Stamm nach Paketinstallation:

```powershell
python -m hagrid_demand foundation --config hagrid-demand/configs/hannover.json
```

Der neue Run erhält eine eigene Kennung. Derselbe Quellstand und dieselben Inputs sollten dieselben fachlichen Bestände und Zuordnungen ergeben; Laufzeitstempel und Run-ID unterscheiden sich.
