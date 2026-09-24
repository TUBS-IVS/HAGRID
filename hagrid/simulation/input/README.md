# hagrid/input — Eingabedaten (nicht versioniert)

Diese Datei ist getrackt und dient dem Java-Code als Root-Marker (`HagridPaths`);
alle Unterordner sind git-ignoriert und werden lokal befüllt.

| Ordner | Studie | Herkunft |
|---|---|---|
| `common/emissions/` | beide | EMEP/EEA-Faktoren, siehe `SOURCES.md` dort und `analysis/lausitz/kpi/data/README.md` |
| `hannover/{config,demand,geodata,hubs,network,vehicles}/` | Hannover | Paketnachfrage 2025-05-13, Region-Hannover-Shapes, KEP-Hubs, MATSim-Netz; den Aufbau beschreibt die Ordnerliste in dieser Tabelle |
| `lausitz/{config,demand,drt,hubs,network,population,transit,vehicles}/` | Lausitz | matsim-lausitz v2024.2 (öffentlich) + HAGRID-Nachfrage; Details in `docs/DATA-LAUSITZ.md` |

Migration von einem Checkout vor dem 2026-09-17: `tools/migrate-input-layout.ps1`
(danach `mvn -q clean install`). Ein leerer Baum wird nicht per Skript erzeugt: die
Ordner entstehen beim Migrieren bzw. beim Ablegen der Daten aus den Quellen oben.
`tools/setup_hagrid_io.bat` ist zurückgezogen — es beschrieb ein Layout, das es nicht
mehr gibt; siehe BACKLOG.
