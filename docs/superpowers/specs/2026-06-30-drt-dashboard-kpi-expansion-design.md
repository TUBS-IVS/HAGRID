# DRT-Dashboard KPI-Erweiterung — Design

**Datum:** 2026-06-30
**Branch:** hendrik
**Scope:** Reine Python-Analyse-Änderung am DRT-Headline-Dashboard. Kein Java, keine MATSim-Reruns.
Läuft auf vorhandenem Output `DRT_BASELINE_13052025_fleet80_depot_railpt_iter150_jsprit100`.

## Ziel

Mehr KPIs im DRT-Dashboard ([build_drt_dashboard.py](../../../parcel-demand-2-matsim-pipeline/analysis/drt-headline/build_drt_dashboard.py)):
zusätzliche Headline-Karten + drei Besetzungs-Diagramme unter der Karte.

## Kern-Mechanik: Besetzungs-Zeitleiste & Segmente

Erweiterung von `reconstruct()` in
[drt_service_time.py](../../../parcel-demand-2-matsim-pipeline/analysis/drt-headline/drt_service_time.py).

Pro Fahrzeug aus dem Eventstream eine **Besetzungs-Zeitleiste** (occupancy ∈ {0..8}, Kapazität=8)
über das **aktive Tour-Fenster** (erste produktive Aufgabe → letzte; nächtliches Depot-Parken
vor/nach Dienst ausgeklammert). Daraus:

- **Segmente** = maximale Intervalle konstanter Besetzung x, je mit Dauer `d` und Distanz `dist`.
- Distanz je Segment/Level aus `veh_path` (Link-Sequenz mit Besetzung, bereits im Build-Skript) × Link-Länge.
- Zeit je Level = Σ Segmentdauer auf diesem Level.

Diese Segment-Liste ist gemeinsame Basis für Utilization **und** die drei Diagramme — eine Datenquelle,
konsistent.

## Headline-KPIs

| KPI | Definition |
|---|---|
| Anzahl Fahrzeuge | `fleet n` (vorhanden) |
| Gesamtpassagiere | `drt_rides` / legs (vorhanden) |
| **Avg. Utilization (by trips)** | Mittel über alle Segmente von (x/8), jedes Segment gleich gewichtet |
| **Avg. Utilization (by time)** | zeitgewichtet: Σ(x/8·d) / Σd über alle Segmente |
| Avg. Trip Length | Personenkm / Gesamtpassagiere |
| **Avg. DRT-Detour-Factor** | Σ `travelDistance_m` / Σ `directTravelDistance_m` (gefahren vs. direkt) |
| Total Tour Duration | Σ aktive Spanne pro Fzg — **auf Stunden gerundet** |
| Total Driving Time | Σ DRIVE (`drive_s`, vorhanden) — Stunden |
| Total Waiting Time | Tour Duration − Driving − Service (Leerlauf zwischen Aufträgen) — Stunden |
| Total Service Time | Σ STOP-Dwell (`stop_s`, vorhanden) — Stunden |

**Identität:** Tour Duration = Driving + Service + Waiting.

**Datenquellen-Korrektur:** Fahrzeugkm, Leerfahrt%, Personenkm aus MATSims autoritativer
`drt_vehicle_stats_drt.csv` (totalDistance / emptyRatio / totalPassengerDistanceTraveled).
`leg_km` = `travelDistance_m` (gefahrene In-Vehicle-Distanz inkl. Pooling-Umweg) — **Bugfix**:
zuvor Luftlinie zwischen fromX/toX → ~2× zu niedrig (4,7 statt 9,8 km Ø-Trip). Karten-Geometrie
bleibt rekonstruiert (~3% unter totalDistance, Luftlinie zwischen Link-Knoten — als Tooltip vermerkt).

Alle neuen KPIs tragen Hover-Tooltips (Definition + Datenquelle).

**Annahme a (→ Tooltip):** Utilization wird über **alle** Segmente inkl. Leerfahrt-Level-0 gemittelt.
**Annahme b (→ Tooltip):** Tour Duration & Waiting klammern nächtliches Depot-Parken aus
(erste Abfahrt → letzte Aufgabe); „Waiting" = Leerlauf *zwischen* Aufträgen, nicht Dienstende.

`Total Cost` bewusst **ausgelassen** — kein Kostenmodell im Code; aufgeschoben bis Raten entschieden.

## Diagramm unter der Karte

**Ein** horizontaler 100%-gestapelter Balken-Chart mit **drei beschrifteten Zeilen**
(Fahrten-Segmente / Betriebszeit / gefahrene km), je Zeile auf 100% normiert und nach
Besetzungslevel 0…8 aufgeteilt (0 = Leerfahrt/Deadhead). Legende unten, Hover: absolut + Anteil%.
Stil analog dem LMD-„Travel vs Service by Provider"-Chart.

Die Sektion **„Personen je Fahrzeug" (figP) entfällt** (gestrichen).

## Out of scope

Kostenmodell, MATSim-Reruns, Java-Änderungen, andere Dashboards (LMD).
