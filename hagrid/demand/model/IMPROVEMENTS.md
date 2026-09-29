# Modell- und Zeitverbesserungen

## Start

```powershell
python -m hagrid_demand run --config hagrid-demand/configs/improved.json --run-id improved-seasonal-20260909
```

Neue Run-ID fuer weitere Laeufe verwenden. Der mitgelieferte Lauf umfasst 20.–26.12.2021 mit zwei Realisierungen. Er ist ein Funktionsnachweis; zwei Realisierungen reichen nicht fuer stabile Quantilschaetzungen. Fuer eine reine Anwendung `predict --model-run <vorheriger Lauf>` verwenden. Keine MATSim-Ausfuehrung.

## Raeumliches Modell

Die Betriebsgroesse kann mit `business_size_power` eingestellt werden. 1 bedeutet die bisherige lineare Beschaeftigtenannahme; 0.5 die Quadratwurzel je Betrieb. Erst nach dieser Transformation wird auf PLZ summiert. Kalibrierung und Standortanwendung verwenden dieselbe Exposition. Ein eingefrorenes Modell verweigert eine nachtraeglich geaenderte Exposition.

Der explorative Vergleich verwendet dieselben drei Seeds und je fuenf PLZ-Folds. Bei festen Anbieterprioren sinkt der mittlere Rohmengen-wMAPE von 33.21 auf 32.29 Prozent. Bedingt auf die aus PANDA vorgegebenen DHL-Grosskundenmengen sinkt er von 18.91 auf 17.05 Prozent. Diese Sondermengen werden im Hauptlauf noch nicht separat eingebaut; 17.05 Prozent ist deshalb kein Fehlerwert des vollstaendigen Hauptmodells.

`improved.json` verwendet den einfachen Kandidaten mit Exponent 0.5. Die Auswahl wurde nach Betrachtung der bisherigen Testdaten getroffen. Auch der im Hauptdashboard weiterhin berichtete einzelne Split ist damit explorativ und keine neue unabhaengige Bestaetigung. Rohmengen und Bedingung auf bekannte Sonderstandorte werden getrennt berichtet.

Vergleich erneut ausfuehren:

```powershell
python -m hagrid_demand.size_experiment --config hagrid-demand/configs/model.json --special-customers C:/Users/bienzeisler/.codex/tmp/panda-demand-review/grosskunden_final.csv --output hagrid-demand/runs/size-effects-new
```

## Zeitmodell

- `weekly_profile_file` konsumiert das typische Wochenprofil aus dem alten HAGRID-Export. Die zugrunde liegenden Schweizer Daten 2019–2021 bleiben ein uebertragenes Saisonprofil, keine gemessene deutsche Zeitreihe.
- Die 2021-Zeilen des Exports liefern genau ISO-Woche 1 bis 52. Woche 53 wird ausdruecklich als Mittel von Woche 52 und 1 behandelt. Das ist eine dokumentierte Annahme. Tageszuordnung erfolgt ueber echte ISO-Wochen; Normierung ueber das echte Kalenderjahr inklusive Schaltjahr.
- Monatsgewichte muessen beim Wochenprofil neutral sein. Weihnachten steckt bereits im Profil; kein zusaetzlicher pauschaler Weihnachtsboost.
- `seasonality_strength` steuert je Segment die Saisonstaerke. 0 neutralisiert das Profil, 1 uebernimmt es; andere Werte sind Annahmen.
- `date_start` und `date_end` erzeugen einen zusammenhaengenden Zeitraum. Beide ersetzen die einzelne Datumsauflistung.
- `week_log_sd`, `week_rho`, `year_log_sd`, `year_rho` steuern korrelierte Wochen-/Jahresschwankungen. Sie wirken gemeinsam auf beide Segmente. Die bestehenden Tages- und raeumlichen Schwankungen bleiben zusaetzlich wirksam.
- Zufallswerte haengen von Seed, Realisierung und verankertem Zeitpunkt ab; das Anfordern eines weiteren Datums aendert keine frueheren Werte.
- Die deterministische Jahreserwartung wird erhalten. Realisierte Jahresmengen koennen aufgrund der Schocks abweichen; es gibt keine erzwungene exakte Jahresmenge jeder Stichprobe.

## Automatische Auswertung

Jeder Hauptlauf schreibt `temporal.html` sowie Tages-, ISO-Wochen- und Monatssummen als CSV. Quantile werden nach Summierung je Realisierung berechnet, nicht durch Addition einzelner Tagesquantile. Teilwochen/-monate enthalten nur angeforderte Tage. Die Baender beschreiben die konfigurierten Simulationen, keine kalibrierten Vorhersageintervalle und keine geschaetzte Parameterunsicherheit.

## Noch offen

Historische OSM-/Gebaeudemerkmale, validierte Sonderstandortbehandlung im Hauptmodell, automatisch geladener Feiertagskalender, statistische Parameter-/Anbieterunsicherheit und neue Zukunftsstandorte sind damit noch nicht implementiert. Die nationale Wachstumskurve bleibt die bisherige explizite Annahme. Diese Erweiterung behauptet keine vollstaendige Umsetzung aller zuvor diskutierten Forschungsansaetze.
