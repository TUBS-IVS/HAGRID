# Kaltstart-Zuschlag + STAY-Phasen-Auswertung (Lausitz-Emissionskanal) — Design

**Datum:** 2026-08-26 · **Branch:** `hendrik` · **Status:** Design abgenommen (User, 2026-08-26)

Schliesst den letzten offenen Punkt des EMEP/EEA-Emissionskanals aus
[Plan 2026-07-28](../plans/2026-07-28-emissions-emep-eea-tier3.md) (Tasks 1–9 abgeschlossen
2026-07-31) und beantwortet im selben Zug die DRT-Ladefensterfrage, weil beide dieselbe
DVRP-Task-Sequenz brauchen.

## 1 Ziel und Ausgangslage

Kaltstart ist im Emissionskanal **nicht modelliert**. Der Fehlbetrag ist bereits gerechnet, nicht
geschätzt (METHODS-LOG §2.29, Rechenrezept `analysis/kpi/data/README.md` Abschnitt „Kaltstart"):
Fracht-NOx **+5,63 %** bei ta = 10 °C, Fracht-CO +13,94 %, DRT-NOx +1,41 % je Kaltstart,
CO₂/Energie < 1,5 %. Die 5-%-Regel des Ursprungsplans greift damit für NOx. Solange der Zuschlag
fehlt, sind **alle berichteten NOx-Zahlen eine einseitige Untergrenze**.

Zweitens ist die DRT-Elektrifizierbarkeit offen: `ev_range_exceed_drt_*` ist ausdrücklich **keine**
Elektrifizierbarkeitsaussage (Fracht-Tour = Schicht, DRT-Fahrzeugtag ≠ Schicht). Belastbar wäre
der längste Fahrblock zwischen zwei hinreichend langen STAY-Phasen.

**Beide Fragen hängen an derselben Größe** — der Task-Sequenz je Fahrzeug. Sie werden deshalb in
einem Paket erledigt statt in zwei.

## 2 Entscheidungen

| # | Entscheidung | Begründung |
|---|---|---|
| E1 | Der Zuschlag wird in die bestehenden KPIs **eingerechnet**; je Schadstoff kommt eine `*_coldstart_share`-Zeile dazu. Präzise: `freight_nox` = hot + kalt, und `freight_nox_coldstart_share` = kalt / (hot + kalt), Einheit `fraction`. | Die Untergrenzen-Caveat fällt weg, und das Paper muss nichts addieren. User 2026-08-26. |
| E2 | Auskühlschwelle **fest 60 min**, kein Sweep. | EPA (1994): Kaltstart = jeder Start ≥ 1 h nach Ende der Vorfahrt, für katalysatorbestückte Fahrzeuge. Unsere Flotte ist Euro 7 Diesel mit DPF+SCR. Zitiert über Reiter & Kockelman (2016). Belegt, nicht gesetzt. |
| E3 | Ladefenster als **Sweep 20/40/60 min**, getrennt von E2. | Ein DRT-Fahrzeug kann jederzeit neu disponiert werden — auf eine lange Standzeit kann man sich operativ nicht verlassen. Die kurzen Fenster sind die, auf die Verlass ist. User 2026-08-26. |
| E4 | ta **fest 10 °C**. | Nahe dem deutschen Jahresmittel; 0 °C bleibt Sensitivität im Limitations-Text. Nur RANGE 1 (t > 0) wird ausgewertet. User 2026-08-26. |
| E5 | Konventioneller Freight: **1 Kaltstart je Tour**. | `TimeDistance_perVehicle.tsv` hat keine Task-Sequenz; eine >60-min-Pause mitten in der Tour ist datenseitig unsichtbar. Ausgewiesene Limitation. |
| E6 | Modularer Arm: jeder Start geht an das Regime des **folgenden** Fahrblocks. | Nur so bleibt die heute exakte Invariante `drt_* + freight_modular_* == total_*` erhalten. Die 1c-Massenzurechnung greift danach unverändert. |
| E7 | BEV-Arm: Kaltstart **0**. | EMEP hat keine BEV-Kaltstartparametrisierung. Methodenkonform — aber siehe Limitation L1. |
| E8 | PM-Auspuff: expliziter **„keine Parametrisierung"-Marker**, nicht 0. | Für Euro 7 fehlt die Kaltstartparametrisierung im Sheet (verifiziert: LCV führt „PM Exhaust" im Cold-Sheet, aber nicht für Euro 7). Eine Lücke ist keine Null. |

## 3 Architektur

### A · Datenschicht

**Neu: `analysis/kpi/data/emep_cold_factors.csv`**, erzeugt von einem zweiten Sheet-Zweig in
`emep_factor_extract.py` (`COLD_EMISSIONS_PARAMETERS`).

Schema (exakt):
`powertrain,segment,pollutant,range,a,b,c,vmin,vmax,tmin,tmax,source`

- Extrahiert werden **alle drei N1-Segmente und alle Ranges** (auch RANGE 2/3 für t < 0), obwohl bei
  ta = 10 °C nur RANGE 1 gebraucht wird. Gleiche Regel wie bei den Hot-Faktoren: eine spätere
  Winter-Sensitivität soll eine Datenzeile sein, keine Code-Änderung.
- Bekannt aus dem Sheet: N1-II und N1-III sind für Kaltstart **identisch**; RANGE 1 gilt für
  v ∈ [5, 45] (CO/NOx/VOC) bzw. [10, 130] (EC), t ∈ [0, 50] bzw. [−10, 30].
- `source` ASCII-only, gleiche Konvention wie die Hot-Faktoren („2023 - Update 2025", Bindestrich).

**Erweitert: `analysis/kpi/data/emep_supplement.csv`**

| Name | Wert | Quelle |
|---|---|---|
| `coldstart_soak_min` | 60 | EPA (1994) via Reiter & Kockelman 2016, TR-D 43, 123–132, doi:10.1016/j.trd.2015.12.012 |
| `ambient_temp_c` | 10.0 | Setzung (E4), nahe deutschem Jahresmittel |
| `ltrip_km` | 12.4 | Guidebook-Default, Mitte des Gültigkeitsbands [8, 15] |
| `cold_beta_a0/a1/b0/b1` | 0.6474 / 0.02545 / 0.00974 / 0.000385 | Kap. 1.A.3.b.i-iv Tab. 3-39 |
| `cold_bc_{co,nox,voc}_{a,b}` | 0.2022/0.0064, 0.1719/0.0055, 0.2398/0.0076 | Tab. 3-46 |
| `charge_window_min_low/mid/high` | 20 / 40 / 60 | Operative Setzung (E3) |

### B · Rechenkern (`emissions_emep.py`)

Neue Funktion, additiv zu `vehicle_emissions()`:

```
extra_je_start = cold_km * ef_hot(v) * bc(ltrip) * (Q(v, ta) - 1)

cold_km = beta(ltrip) * ltrip                       # ausgewiesener ltrip-Transfer
beta    = a0 - a1*ltrip - (b0 - b1*ltrip) * ta      # Tab. 3-39
bc      = a - b*ltrip  fuer CO/NOx/VOC, sonst 1.0   # Tab. 3-46
Q       = a*v + b*ta + c, Boden 1.0, v geclampt auf [vmin, vmax] der Kaltzeile
```

**Verifiziert (2026-08-26):** bei ltrip = 12,4 und ta = 10 ergibt sich `cold_km` = 3,4988 km
(README: 3,50), und die Formel reproduziert die unabhängig gerechnete Bound auf `base10c`
(v = 35 flat): Fracht-NOx +5,61 % gegen dokumentierte 5,63 %, Fracht-CO 13,85 vs. 13,94,
Fracht-EC 0,92 vs. 0,93, DRT-NOx 1,39 vs. 1,41. Die VOC-Abweichung (3,81 vs. 3,61) ist die
Einheitsgeschwindigkeit — VOC hat als einziger Schadstoff eine negative Q-Steigung und ist damit
am geschwindigkeitsempfindlichsten.

**Der ltrip-Transfer bleibt ausgewiesen:** `beta` ist ein Anteil an der Gesamtfahrleistung,
kalibriert für ltrip ∈ [8, 15] km. Unsere Touren sind ~99 km lang, dort ist die Formel nicht
auswertbar (sie wird negativ). Übertragbar ist die **Kaltdistanz je Start**, und die ist über das
gültige Band stabil: 3,02 / 3,50 / 3,39 km bei ltrip 8 / 12,4 / 15 km.

### C · Kaltstart-Zählung (`extract_emissions.py`)

- **freight** (konventionell): `n = Anzahl Touren` (E5).
- **drt / freight_modular**: `n = 1` (Schichtbeginn) `+` Anzahl STAY-Blöcke ≥ 60 min, auf die
  wieder ein Fahrblock folgt. Eine STAY-Phase am Tagesende ohne Folgefahrt zählt **nicht**.
- Regime-Zuordnung nach E6.

### D · STAY-Blöcke und Ladefenster

`drt_service_time.reconstruct()` baut `done_tasks` je Fahrzeug bereits auf
(`analysis/drt-headline/drt_service_time.py:233`), verwirft es aber nach der Aggregation. Die
Sequenz wird **additiv** als `per_veh[v]["task_seq"]` durchgereicht — das Modul wird von anderen
Extractoren genutzt, es darf sich nichts Bestehendes ändern.

Zwei Konsumenten:
1. die Zählung aus C,
2. neue KPI `drive_block_max_km_<20|40|60>` — längster zusammenhängender Fahrblock zwischen zwei
   STAYs ≥ w — gehalten gegen `ev_range_km_low/mid/high`.

Die bestehende `ev_range_exceed_drt_*`-Zeile **bleibt stehen**, bekommt aber im `source`-String den
Hinweis, dass sie keine Elektrifizierbarkeitsaussage ist.

### E · Zahlen-Neustand

- METHODS-LOG §2.29 von „gerechnete Untergrenze" auf „implementiert" umschreiben, Bound-Tabelle als
  historische Herleitung erhalten.
- Die NOx-Zahlen in §2.36–§2.38 als **Altstand** markieren (sie stammen zusätzlich aus der alten
  Depotlogik, stehen also ohnehin unter dem Hinfällig-Beschluss vom 2026-08-17).
- Backlog-Punkt „Kaltstart-Zuschlag implementieren" nach BACKLOG-DONE; der `[M]`-Punkt
  „Ladefenster-Analyse" ebenfalls, sofern D ihn beantwortet.

## 4 Limitations (Paper-Rohtext)

- **L1 — Der BEV-Arm hat keinen Kaltstart, und das schmeichelt ihm.** EMEP führt für BEV keine
  Kaltstartparametrisierung, der Zuschlag ist dort also 0. Real haben BEV sehr wohl einen
  Kaltverbrauch, dominiert von der Kabinenheizung, und der ist im Winter erheblich. Die Näherung ist
  **einseitig zugunsten des BEV-Arms** — und zwar in genau dem Vergleich, der der Aufhänger des
  Papers werden soll. Ausschreiben, nicht abhaken.
- **L2 — Konventioneller Freight sieht nur einen Start je Tour** (E5). Eine >60-min-Pause innerhalb
  einer Tour ist in `TimeDistance_perVehicle.tsv` unsichtbar; die Frachtzahl ist damit selbst nach
  Implementierung noch eine (deutlich engere) Untergrenze.
- **L3 — PM-Auspuff bleibt unparametrisiert** (E8). Für die Bilanz irrelevant — 0,89 g Auspuff-PM
  gegen 316,6 g Abrieb im selben Lauf —, aber eine Lücke und keine Null.
- **L4 — Nur RANGE 1.** Bei ta = 10 °C korrekt; eine Winter-Auswertung braucht RANGE 2, deren
  Koeffizienten deshalb mit committet werden (A).

## 5 Nicht im Scope

- Energetisches Lademodell (Ladeleistung, Batteriekapazität, SoC-Verlauf). Eskalationspfad: nur
  falls die geometrische Schranke aus D bindend ist, dann eigener Backlog-Punkt.
- Idle-/Leerlaufemissionen.
- BEV-Kaltverbrauch (L1) — braucht eine Quelle außerhalb EMEP.
- `src/hagrid_output_analysis/**` (Kollegen-Paper-Freeze).

## 6 Risiken und Fallstricke

- ⚠️ **v-Clamp.** Die Kaltkurven für CO/NOx/VOC gelten nur bis 45 km/h. Fracht liegt bei 34–40,
  DRT kann darüber liegen. Ohne Clamping extrapoliert die Formel stillschweigend.
- ⚠️ **Monatsspalte.** Das Cold-Sheet hat eine `Month`-Dimension. Für Euro 7 LCV sehen die Werte
  monatsinvariant aus, das ist aber **ungeprüft**. Die Extraktion muss Invarianz **asserten** und
  laut abbrechen, statt still Januar zu nehmen.
- ⚠️ **`drt_service_time.py` ist geteilt.** Nur additiv erweitern; die bestehende Testsuite muss
  unverändert grün bleiben.
- **Zählungs-Grenzfälle:** STAY genau 60 min; STAY am Tagesende; Fahrzeug ohne jede DRIVE-Zeit.

## 7 Abnahmekriterien

1. Die Implementierung reproduziert auf `base10c` die dokumentierte Bound, wenn die Zählung aus C
   auf `n = 1` je Entität gezwungen wird, bei ta = 10 °C (Fracht-NOx 5,63 %, Fracht-CO 13,94 %,
   DRT-NOx 1,41 %, jeweils Toleranz ~0,1 pp). **Das ist ein unabhängig gerechneter Anker, kein
   selbstgebauter Erwartungswert.** Der Produktivpfad zählt danach nach E2/E5 — auf der DRT-Seite
   also erwartbar mehr als 1, und genau diese Differenz ist der Erkenntnisgewinn des Pakets.
2. `drt_* + freight_modular_* == total_*` bleibt auf einem 1d-Lauf exakt.
3. Extraktion bricht ab, wenn die Monatsinvarianz nicht gilt.
4. Ein v > 45 km/h wird geclampt, nachweisbar per Test.
5. `*_coldstart_share` erscheint je Schadstoff; PM trägt den Marker aus E8, BEV trägt 0.
6. `drive_block_max_km_20/40/60` erscheint für DRT und den modularen Arm.
7. Volle Suite grün. Stand vor Beginn **gemessen am 2026-08-26: 392 Tests** in
   `analysis/kpi/tests/` (nicht die 356 aus dem Juli-Plan — die Suite ist seither gewachsen).

## 8 Berührte Dateien

**Neu:** `analysis/kpi/data/emep_cold_factors.csv`

**Geändert:** `analysis/kpi/emep_factor_extract.py` · `analysis/kpi/data/emep_supplement.csv` ·
`analysis/kpi/emissions_emep.py` · `analysis/kpi/extract_emissions.py` ·
`analysis/drt-headline/drt_service_time.py` (additiv) · `analysis/kpi/data/README.md` ·
`analysis/kpi/tests/test_emissions_emep.py` · `analysis/kpi/tests/test_extract_emissions.py` ·
`docs/METHODS-LOG.md` · `docs/BACKLOG.md` · `docs/BACKLOG-DONE.md`

**Unberührt:** `src/hagrid_output_analysis/**`

## 9 Quellen

- EMEP/EEA air pollutant emission inventory guidebook 2023 – Update 2025, ch. 1.A.3.b.i-iv
  Appendix 4 (Oct 2025, COPERT 5.9.1), Sheet `COLD_EMISSIONS_PARAMETERS`; Gl. (10),
  Tab. 3-39 (beta), Tab. 3-46 (bc).
- Reiter, M. S. & Kockelman, K. M. (2016). The problem of cold starts: A closer look at mobile
  source emissions levels. *Transportation Research Part D: Transport and Environment*, 43,
  123–132. doi:10.1016/j.trd.2015.12.012 — darin EPA (1994) für die 1-h-Definition.
