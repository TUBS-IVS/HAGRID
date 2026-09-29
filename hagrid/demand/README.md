# hagrid/demand — Paketnachfrage Region Hannover

Die Nachfrageseite von HAGRID: aus DHL-Straßenmengen 2021, Bevölkerung, Firmen und OSM-Gebäuden entsteht für jeden Tag
eines Zieljahres die Paketnachfrage je Stopp und Anbieter, als MATSim-Eingabe für `hagrid/simulation`.

| Ordner | Inhalt | Versioniert |
|---|---|---|
| `model/` | Python-Paket `hagrid_demand`: Straßenanker, Gebäude, Stopps, Versandtag/Laufzeit, Abholpunkte, Jahresspeicher, Dashboards, Tests. Einstieg: [`model/README.md`](model/README.md) | ja |
| `input/hannover/` | Rohdaten (`raw/`), OSM-Auszüge (`osm/`), Notebook-Outputs (`notebook-output/`) | nein (git-ignoriert) |
| `runs/` | Läufe: Referenz, Tagesdateien, Jahresspeicher, `annual_dashboard.html`; Dekadenläufe `decade-*` und `decade_dashboard.html` | nein (git-ignoriert) |
| `archive/notebooks/` | Die Notebook-Kette 00–06 und die Generatoren, aus denen die nationalen Reihen und Profile stammen; vom Modell abgelöst | ja |

## Schnellstart

```powershell
cd hagrid/demand/model
python -m pip install -e ".[test]"
python -m pytest -q                                  # Tests
tools\migrate-demand-input.ps1                       # einmalig: lokale Eingaben ins neue Layout (vom Repo-Stamm)
runs\hannover\run_demand_year.bat demand-2025        # Jahreslauf + MATSim-Nachfrage nach hagrid/simulation/input/hannover/demand/demand-2025/
runs\hannover\run_demand_decade.bat                 # 2025-2035 in drei Volumenszenarien + Dekaden-Dashboard (siehe model/README.md)
```

Weitere Befehle (`export-day`, `annual-dashboard`, `osm-parcel-points`, `osm-transit`) und alle Modellannahmen mit Quellen
stehen in [`model/README.md`](model/README.md); Spezifikationen und Pläne unter [`../../docs/demand/`](../../docs/demand/).

## Mehrjahresprojektion

`years` kann mehrere Jahre umfassen. Die Configs `model/configs/decade-{trend,saettigung,boom}.json` rechnen 2025–2035 mit
unterschiedlichen Anstiegen der nationalen Sendungsmenge (verkettet am Niveau 2025), einem nachfragegetrieben wachsenden
Abholnetz (neue Packstationen, Boxen und Counter an Supermärkten, Tankstellen und Kiosken) und liefern je Jahr ein
Jahres-Dashboard sowie ein gemeinsames Dekaden-Dashboard. Details und Annahmen: [`model/README.md`](model/README.md),
Abschnitt „Mehrjahresprojektion 2025–2035“.

Mit `land_use` verteilen sich Personen und Firmen zusätzlich nach der amtlichen Bevölkerungsprognose 2025–2035 je
Prognosebezirk, nach Alterung (Online-Neigung mit Kohorteneffekt) und Branchenwachstum neu; Neubaugebiete (Kronsberg-Süd,
Wasserstadt Limmer, …) und neue Betriebe werden eigene Standorte. Details: [`model/README.md`](model/README.md),
Abschnitt „Landnutzungsdynamik 2025–2035“.

## Datenfluss zu MATSim

`baseline run` schreibt je konfiguriertem Tag `hagrid_parcel_demand_<Datum>_(<Wochentag>).shp` mit Stopps (`stop_type`
`home`, `locker`, `shared_locker`, `counter`, `shop`) und Anbieterfeldern; `run_demand_year.bat` kopiert sie nach
`hagrid/simulation/input/hannover/demand/<run-id>/`, wo `HagridPaths.demandDir(runId)` sie erwartet. Jeder andere Tag des
Jahres lässt sich mit `export-day` aus dem Jahresspeicher erzeugen.
