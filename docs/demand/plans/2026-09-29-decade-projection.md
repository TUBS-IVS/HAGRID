# Mehrjahresprojektion 2025–2035 — Implementierungsplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Das Tagesmodell rechnet 2025–2035 in drei Volumenszenarien, das Abholnetz wächst nachfragegetrieben mit, und ein Dekaden-Dashboard zeigt den Verlauf über die Jahre.

**Architecture:** Szenarien = Verkettung der Notebook-Fitkurven am Niveau 2025 (`series.py`); Netzwachstum = Vorpass vor der Tagesschleife (`network_growth.py`, neues Punktregister mit `year_opened`); Dekaden-Dashboard = eigener Payload-Builder + eigenständiges HTML-Template (`decade_dashboard.py`). Alles im Paket `hagrid/demand/model/src/hagrid_demand/baseline/`.

**Tech Stack:** Python 3.13, pandas/geopandas/numpy/scipy, pytest; Vanilla-JS/Inline-SVG im Template (kein CDN).

**Spec:** `docs/demand/specs/2026-09-28-decade-projection-design.md`

## Global Constraints

- Ohne `volume_scenario`-Block bleibt jeder bestehende Lauf bitidentisch (Test-Suite 310/310 bleibt grün; Stage-Hashes ändern sich nur, wenn der Block gesetzt ist).
- Code-Kommentare Englisch, Doku Deutsch. Keine Java-Änderungen. Keine Shops.
- Alle neuen Config-Schlüssel in `config.py::_ALLOWED_KEYS` bzw. `out_of_home.json` mit Defaults; Validierung wirft `ValueError` mit sprechender Meldung.
- Determinismus: jede Zufallsziehung über `named_rng(seed, ...)` mit eigenem `channel`.
- Tests: `cd hagrid/demand/model && PYTHONPATH=src python -m pytest -q tests/<datei>`; Gesamtsuite vor jedem Commit grün.

## Review Focus

1. Mehrjahreslauf mit `years` ohne 2025, aber `network_growth.reference_year = 2025`: Referenzzahl N_g(ref) muss aus dem Referenznetz (nicht aus einem gelaufenen Jahr) kommen — Test in Task 2 (`test_targets_use_reference_network_when_reference_year_not_simulated`).
2. `export_day` für 2025 nach einem Lauf mit Zusätzen 2026+: keine zukünftigen Punkte im Export — Task 2 (`test_export_day_excludes_points_opened_later`).
3. Szenario-Kurve mit nicht endlichem Kandidaten (Fit fehlgeschlagen) — Task 1 (`test_apply_volume_scenario_rejects_nonfinite_curve`).
4. Kandidatenmangel: weniger POIs als Zusätze — Task 2 (`test_grow_network_reports_shortfall`).
5. Dekaden-Dashboard mit einem Nebenszenario ohne 2035 — Task 4 (`test_decade_payload_marks_missing_years`).

---

### Task 1: Volumenszenarien (series + config + workflow)

**Files:**
- Modify: `src/hagrid_demand/baseline/series.py` (nach `_volume`; `build_series`)
- Modify: `src/hagrid_demand/baseline/config.py` (`_ALLOWED_KEYS`, neue `_validate_volume_scenario`)
- Modify: `src/hagrid_demand/baseline/workflow.py:206-208` (`_write_series`), Stage-Hash der Series/Daily-Stufe (Block in den Hash-Inputs aufnehmen; suche `_daily_code` und die Series-Stage-Signatur)
- Test: `tests/test_baseline_series.py` (anhängen), `tests/test_baseline_workflow.py` (Config-Validierung)

**Interfaces:**
- Produces: `apply_volume_scenario(basis: pd.DataFrame, policy_frame: pd.DataFrame, scenario: dict) -> pd.DataFrame`;
  `build_series(inputs, years, *, volume_fit_policy, weekly_profile=None, volume_scenario: dict | None = None) -> dict`;
  `validate_volume_scenario(value, years: list[int]) -> dict` (normalisiert, wirft ValueError).
- Config: `"volume_scenario": {"name": str, "policy": "observed_only"|"legacy_assumptions", "curve": "linear"|"logistic"|"exponential", "chain_year": int}`.

- [ ] **Schritt 1: Tests schreiben (RED)** in `tests/test_baseline_series.py`:

```python
def _scenario(policy="legacy_assumptions", curve="logistic", chain=2025, name="saettigung"):
    return {"name": name, "policy": policy, "curve": curve, "chain_year": chain}

def test_apply_volume_scenario_chains_at_basis_level():
    years = list(range(2021, 2036))
    inputs = packaged_series_inputs()
    basis = build_series(inputs, years, volume_fit_policy="observed_only")["volume"]
    out = build_series(inputs, years, volume_fit_policy="observed_only", volume_scenario=_scenario())["volume"].set_index("year")
    assert out.loc[2025, "value"] == pytest.approx(basis.set_index("year").loc[2025, "value"])
    policy = build_series(inputs, years, volume_fit_policy="legacy_assumptions")["volume"].set_index("year")
    expected = basis.set_index("year").loc[2025, "value"] * policy.loc[2035, "logistic"] / policy.loc[2025, "logistic"]
    assert out.loc[2035, "value"] == pytest.approx(expected)
    assert out.loc[2035, "status"] == "scenario_projection" and out.loc[2035, "curve"] == "legacy_assumptions/logistic"
    assert out.loc[2024, "status"] == basis.set_index("year").loc[2024, "status"]
    assert (out.scenario == "saettigung").all()

def test_build_series_without_scenario_is_unchanged():
    years = [2021, 2025]
    a = build_series(packaged_series_inputs(), years, volume_fit_policy="observed_only")["volume"]
    b = build_series(packaged_series_inputs(), years, volume_fit_policy="observed_only", volume_scenario=None)["volume"]
    pd.testing.assert_frame_equal(a, b)
    assert "scenario" not in a.columns

@pytest.mark.parametrize("bad", [
    {"name": "x", "policy": "other", "curve": "linear", "chain_year": 2025},
    {"name": "x", "policy": "observed_only", "curve": "spline", "chain_year": 2025},
    {"name": "x", "policy": "observed_only", "curve": "linear", "chain_year": 2040},
    {"name": "", "policy": "observed_only", "curve": "linear", "chain_year": 2025},
])
def test_apply_volume_scenario_rejects_bad_blocks(bad):
    with pytest.raises(ValueError):
        build_series(packaged_series_inputs(), [2021, 2025, 2030], volume_fit_policy="observed_only", volume_scenario=bad)

def test_apply_volume_scenario_rejects_nonfinite_curve():
    basis = pd.DataFrame({"year": [2021, 2025, 2030], "value": [4.5e9, 4.4e9, 5e9], "status": "observed_anchor", "curve": "linear"})
    policy = basis.assign(logistic=[4e9, np.nan, 5e9])
    with pytest.raises(ValueError, match="logistic"):
        apply_volume_scenario(basis, policy, _scenario())
```

- [ ] **Schritt 2: `pytest tests/test_baseline_series.py -q` → FAIL** (ImportError/TypeError für `volume_scenario`).
- [ ] **Schritt 3: Implementierung** in `series.py`:

```python
_SCENARIO_POLICIES = {"observed_only", "legacy_assumptions"}
_SCENARIO_CURVES = {"linear", "logistic", "exponential"}

def validate_volume_scenario(value, years):
    if not isinstance(value, dict): raise ValueError("volume_scenario must be a mapping")
    name, policy, curve, chain = value.get("name"), value.get("policy"), value.get("curve"), value.get("chain_year")
    if not isinstance(name, str) or not name.strip(): raise ValueError("volume_scenario.name must be a nonempty string")
    if policy not in _SCENARIO_POLICIES: raise ValueError(f"volume_scenario.policy must be one of {sorted(_SCENARIO_POLICIES)}")
    if curve not in _SCENARIO_CURVES: raise ValueError(f"volume_scenario.curve must be one of {sorted(_SCENARIO_CURVES)}")
    if type(chain) is not int or chain not in set(years): raise ValueError("volume_scenario.chain_year must be one of the run years")
    return {"name": name.strip(), "policy": policy, "curve": curve, "chain_year": chain}

def apply_volume_scenario(basis, policy_frame, scenario):
    """Chain the scenario's fit curve to the basis level of chain_year: value(y) = basis(chain) * C(y) / C(chain)."""
    out, curve, chain = basis.copy(), scenario["curve"], int(scenario["chain_year"])
    ref = policy_frame.set_index("year")[curve]
    later = out.year.gt(chain)
    needed = ref.reindex([chain, *out.loc[later, "year"]])
    if needed.isna().any() or not np.isfinite(needed).all() or (needed <= 0).any():
        raise ValueError(f"volume_scenario curve {curve} is not finite and positive from {chain} on")
    base = float(out.set_index("year").loc[chain, "value"])
    out.loc[later, "value"] = base * ref.reindex(out.loc[later, "year"]).to_numpy() / float(ref[chain])
    out.loc[later, "status"] = "scenario_projection"
    out.loc[later, "curve"] = f"{scenario['policy']}/{curve}"
    out["scenario"] = scenario["name"]
    return out
```

  In `build_series`: `volume = _volume(inputs, years, volume_fit_policy)`; wenn `volume_scenario` gegeben: `scenario = validate_volume_scenario(volume_scenario, years)`; `volume = apply_volume_scenario(volume, _volume(inputs, years, scenario["policy"]), scenario)`. Hinweis: `chain_year` muss in `years` liegen — `_write_series` reicht `years = sorted({*config["years"], reference_year})` durch, also ist 2025 enthalten, wenn es in `config["years"]` steht.
- [ ] **Schritt 4: config.py**: `"volume_scenario"` in `_ALLOWED_KEYS`; in `load_baseline_config` nach `config.setdefault("years", ...)`: `if "volume_scenario" in config: config["volume_scenario"] = validate_volume_scenario(config["volume_scenario"], sorted({*config["years"], config["reference_year"]}))` (Import aus `series`). Test in `tests/test_baseline_workflow.py`: Config mit ungültigem Block → `ValueError`; gültiger Block bleibt erhalten.
- [ ] **Schritt 5: workflow.py**: `build_series(..., volume_scenario=config.get("volume_scenario"))`; den Block in die Signatur/den Hash der Series-Stage und in `_daily_code` aufnehmen (suche nach `volume_fit_policy` in `workflow.py` — jede Stelle, an der es in einen Hash/Status eingeht, bekommt `volume_scenario` daneben).
- [ ] **Schritt 6: `pytest tests/test_baseline_series.py tests/test_baseline_workflow.py -q` → PASS; Gesamtsuite grün.**
- [ ] **Schritt 7: Commit** `feat(series): chained volume scenarios from the notebook fit curves`.

---

### Task 2: Wachsendes Abholnetz (network_growth + workflow + export)

**Files:**
- Create: `src/hagrid_demand/baseline/network_growth.py`
- Modify: `src/hagrid_demand/baseline/data/out_of_home.json` (Block `network_growth`, Werte aus Spec 4.3)
- Modify: `src/hagrid_demand/baseline/out_of_home.py` (`resolve_out_of_home`: Block mergen/validieren; `build_plan(..., previous_compartments: np.ndarray | None = None)`: `compartments = np.maximum(compartments, previous)` für Punkte ohne OSM-Tag, vor `locker_queue`)
- Modify: `src/hagrid_demand/baseline/workflow.py` (`_out_of_home_points` → Vorpass; `route()` → aktive Teilmenge, Belegungs-Mapping, Kapazität, Netzregister; Status)
- Modify: `src/hagrid_demand/baseline/annual.py:155-160` (Filter `year_opened <= timestamp.year`)
- Test: `tests/test_network_growth.py` (neu), `tests/test_annual_store.py` (Export-Filter), `tests/test_out_of_home.py` (`previous_compartments`)

**Interfaces:**
- Consumes (Task-unabhängig): `synthetic_candidates(pois, types)`, `out_of_home_share(year, carrier, inputs)`, `named_rng`.
- Produces:
  - `network_groups(points) -> list[tuple[str, str]]` — eindeutige `(kind, carriers)` des Referenznetzes.
  - `carrier_ooh_demand(sites_year: pd.DataFrame, profiles_year: pd.DataFrame, year: int, inputs: dict) -> dict[str, float]` — D_c(y) = Σ private annual_expected × share(private, c) × ooh_share(y, c).
  - `group_targets(reference_counts: dict[group,int], demand_ref: dict[group,float], demand_year: dict[group,float], previous: dict[group,int], elasticity: float) -> dict[group,int]` — `max(previous, round(ref * (D_y/D_ref)**elasticity))`; D_ref ≤ 0 → Ziel = previous.
  - `candidate_weights(candidates: gpd.GeoDataFrame, existing_xy: np.ndarray, demand_xy: np.ndarray, demand: np.ndarray, kind: str, cfg: dict) -> np.ndarray` — Nachfrage im Umkreis (cKDTree `query_ball_point`) × (1 − exp(−d/gap_scale_m)) × Präferenzfaktor; 0 für Kandidaten näher als `min_spacing_m` an einem Punkt derselben Gruppe.
  - `grow_network(points: gpd.GeoDataFrame, additions: dict[group,int], candidates: gpd.GeoDataFrame, demand_xy, demand, year: int, cfg: dict, rng) -> tuple[gpd.GeoDataFrame, dict]` — hängt neue Punkte an (Attribute nach Spec 4.3 Schritt 5), gibt Status `{group: {"target_added": n, "added": m, "candidates": k, "shortfall": n-m}}` zurück; jeder POI höchstens einmal je Lauf.
  - `plan_network(points, years: list[int], demand_by_year: dict[int, dict[str,float]], candidates, demand_xy_by_year, demand_by_site_year, cfg, seed) -> tuple[gpd.GeoDataFrame, dict]` — Orchestrierung über alle Jahre > reference_year, Referenzpunkte bekommen `year_opened = reference_year`, `poi_type = None`.
- Workflow-Ausgaben: `out_of_home_points.parquet` (+ `year_opened`, `poi_type`), `out_of_home_network.parquet` (Spalten nach Spec), `daily_status.json["out_of_home"]["network_growth"]`.

- [ ] **Schritt 1: Tests (RED)** `tests/test_network_growth.py` mit einem kleinen synthetischen Netz (5 Referenzpunkte zweier Gruppen, 30 Kandidaten-POIs auf einem Gitter, 50 Nachfrageorte): `test_group_targets_follow_elasticity_and_never_shrink`, `test_targets_use_reference_network_when_reference_year_not_simulated` (Referenzzahl aus `points`, nicht aus Jahresläufen), `test_candidate_weights_prefer_gaps_and_demand` (Kandidat neben bestehendem Punkt → Gewicht 0; ferner Kandidat mit Nachfrage > naher ohne), `test_candidate_weights_apply_kind_preference` (Faktor 0.25), `test_grow_network_adds_points_with_attributes` (point_id-Muster, kind, carriers, year_opened, poi_type `shop=supermarket`, synthetic True, context retail, compartments NaN), `test_grow_network_reports_shortfall`, `test_plan_network_is_deterministic_and_monotone` (gleicher Seed → gleiche Punkte; Punktzahl je Gruppe nicht fallend), `test_grow_network_disabled_adds_nothing`.
- [ ] **Schritt 2: RED bestätigen**, dann Modul implementieren (Formeln aus Spec 4.3; Ziehung wie `synthesize_shops`: `rng.choice(pool, size, replace=False, p=w/w.sum())`; Kandidatenausschluss je Gruppe über cKDTree-Abstand zum nächsten Punkt der Gruppe **vor** der Ziehung des Jahres).
- [ ] **Schritt 3: `previous_compartments` in `build_plan`** (+ Test in `test_out_of_home.py`: Vorjahr 120 > dimensioniert 80 → 120; OSM-Tag bleibt).
- [ ] **Schritt 4: workflow.py verdrahten**:
  1. In `_out_of_home_points` nach dem heutigen Aufbau: wenn `ooh["network_growth"]["enabled"]` und `len(config["years"]) > 1`: Nachfrage je Jahr aus der Projektion (`sites` der Daily-Stufe: `year, site_id, segment, annual_expected`; Koordinaten über `site_stop_of`/`site_xy_of` wie in `route()`), Profile (`profiles` des Laufs), Kandidaten aus `config["osm_points"]` innerhalb `postal_support`; `plan_network(...)` → `points`, `status["out_of_home"]["network_growth"]`. Ohne Wachstum: `points["year_opened"] = reference_year`, `points["poi_type"] = None`. (Die Funktion braucht dafür `sites`/`profiles` — Signatur um diese Argumente erweitern; Aufrufstelle Zeile ~451 liegt nach deren Laden.)
  2. `out_of_home_points.parquet` bekommt `year_opened`, `poi_type` (auch in der Spaltenliste Zeile ~456 und in `_lockers_payload` tolerant lesen).
  3. `route()`: `active = np.flatnonzero(points.year_opened.to_numpy() <= year)`; `build_plan(..., points.iloc[active].reset_index(drop=True), ..., previous_compartments=prev[active] if prev is not None else None)`; nach dem Plan: `prev = prev or point_compartments(points, ooh)`; `prev[active] = plans[year].queue.compartments`; `sized.loc[:, "compartments"] = prev` schreiben; Belegung: `stop_index = extra.stop_index.to_numpy()[active[occupancy.stop_index.to_numpy()]]`; Netzregister-Zeilen des Jahres (alle aktiven Punkte mit `compartments = prev[active]`, `lon/lat` aus `points.to_crs(4326)`) in `network_rows` sammeln.
  4. Am Ende (neben `locker_occupancy.parquet`): `pd.concat(network_rows).to_parquet(writer.directory / "out_of_home_network.parquet")` und Kopie nach `output/`.
- [ ] **Schritt 5: annual.py**: `points = points.loc[pd.to_numeric(points.get("year_opened"), errors="coerce").fillna(-np.inf) <= timestamp.year]` vor dem Anhängen; Test `test_export_day_excludes_points_opened_later` in `tests/test_annual_store.py` (Store-Fixture: Punkt mit `year_opened = 2026`, Export 2025 enthält ihn nicht, Export 2026 enthält ihn).
- [ ] **Schritt 6: Mehrjahres-Workflow-Test** in `tests/test_baseline_workflow.py` (vorhandene OOH-Fixture, `years: [2025, 2026]`, `osm_points` mit 20 POIs): `out_of_home_network.parquet` hat Zeilen für beide Jahre, Punktzahl 2026 ≥ 2025, Status-Block vorhanden, `annual_dashboard_2025.html`/`_2026.html` existieren (Task 3 liefert das Schreiben; hier nur prüfen, wenn Task 3 vor dem Testlauf fertig ist — sonst den Dashboard-Assert in Task 3 ergänzen).
- [ ] **Schritt 7: Gesamtsuite grün, Commit** `feat(out-of-home): demand-driven pickup network growth per year`.

---

### Task 3: Szenario-Configs, Mehrjahres-Dashboards, Lauf-Skript

**Files:**
- Create: `configs/decade-trend.json`, `configs/decade-saettigung.json`, `configs/decade-boom.json`
- Modify: `src/hagrid_demand/baseline/workflow.py` (Laufende: Jahres-Dashboards je Jahr)
- Create: `runs/hannover/run_demand_decade.bat`
- Test: `tests/test_baseline_workflow.py` (`test_decade_configs_load_and_dates_follow_iso_rule`)

**Interfaces:**
- Produces: `comparable_dates(years: Iterable[int]) -> list[str]` in `src/hagrid_demand/baseline/calendar_days.py`? — **Nein**: explizite `dates` in den Configs; der Test rechnet die Regel nach: `date.fromisocalendar(y, 19, 5)`, `(y, 19, 6)`, `(y, 20, 1..6)`.

- [ ] **Schritt 1:** Configs aus `baseline-daily.json` ableiten (`years: [2025..2035]`, `dates` nach R7: trend alle Jahre, Varianten nur 2030/2035; `volume_scenario` nur in saettigung/boom; `assumptions` um einen Satz zum Szenario ergänzen).
- [ ] **Schritt 2:** Test: alle drei laden über `load_baseline_config`, `dates` je Jahr = Regel, `volume_scenario.name` passt zum Dateinamen.
- [ ] **Schritt 3:** Laufende (`write_annual_dashboard`-Aufruf am Ende von `run_baseline`): bei `len(years) > 1` je Jahr `annual_dashboard_<jahr>.html` (`year=` Parameter), zusätzlich wie bisher `annual_dashboard.html`.
- [ ] **Schritt 4:** `run_demand_decade.bat` (Muster `run_demand_year.bat`; Argumente = Szenarienliste, Standard `trend saettigung boom`; danach `python -m hagrid_demand baseline decade-dashboard --run trend=hagrid\demand\runs\decade-trend ... --out hagrid\demand\runs\decade_dashboard.html`, der Aufruf existiert nach Task 4).
- [ ] **Schritt 5:** Commit `feat(demand): decade scenario configs, per-year dashboards and run script`.

---

### Task 4: Dekaden-Dashboard — Payload-Builder + CLI

**Files:**
- Create: `src/hagrid_demand/baseline/decade_dashboard.py`
- Modify: `src/hagrid_demand/cli.py` (Subcommand `decade-dashboard`, `--run NAME=PATH` mehrfach, `--out`)
- Test: `tests/test_decade_dashboard.py`

**Interfaces:**
- Produces: `build_decade_dashboard_data(runs: dict[str, Path]) -> dict` (Payload nach Spec 4.4; Schlüssel `meta, years, national, annual, plz, calendar, network, weekday`), `write_decade_dashboard(runs: dict[str, Path], out_html: Path) -> Path` (Template `templates/decade_dashboard.html`, Platzhalter `__DECADE_DATA__` → JSON `D`).
- Consumes: Jahresspeicher (`annual/days.parquet`, `plz_daily.parquet`, `locker_occupancy.parquet`, `out_of_home_network.parquet` optional, `out_of_home_points.parquet`), `carrier_profiles.parquet`, `postal_projection.parquet`, `reference_units.parquet`, `sources/postal_support.parquet` (Geometrie wie `annual_dashboard._plz_geometry`/Map-Payload — dieselbe Vereinfachung wiederverwenden), `series/volume.parquet` (nationale Anker + Szenariowerte), `config.resolved.json`, `daily_status.json`.
- Kanäle je Tag/PLZ: Hauszustellung aus `plz_daily` (Spalten wie im Jahres-Dashboard), OOH je Kind aus `stop_daily` gefiltert auf Punkt-Stops (`stop_index` ≥ erster Punkt-Index) oder — günstiger — aus `locker_occupancy.stored` je Tag und Kind (Kind über Punktregister).
- Spitzentag-Begründung: `annual_dashboard.peak_reason`-Logik wiederverwenden (Funktion importieren, nicht kopieren).

- [ ] **Schritt 1: Tests (RED)** mit einem Zwei-Jahres-Testlauf (Fixture aus `tests/test_annual_dashboard.py` erweitern, Jahre 2025/2026, kleines Netz): `test_decade_payload_shape` (alle Schlüssel, Jahre sortiert, `annual[trend][2026].parcels_total` = Summe der Tage), `test_decade_payload_json_has_no_nan`, `test_decade_payload_marks_missing_years` (Nebenszenario ohne 2026 → `annual[name][2026] is None` und `meta.scenarios[i].missing_years == [2026]`), `test_write_decade_dashboard_embeds_payload` (HTML enthält `const D =` und alle Abschnitts-IDs aus Spec 4.4), `test_cli_decade_dashboard` (Aufruf über `main([...])`).
- [ ] **Schritt 2:** Builder implementieren (vektorisiert wie `build_annual_dashboard_data`; Payload kompakt: Zahlen gerundet, PLZ-Werte als Arrays in `plz.codes`-Reihenfolge; `calendar` als Liste je Jahr mit 365/366 Einträgen).
- [ ] **Schritt 3:** CLI-Subcommand; Fehler: unbekanntes Format `NAME=PATH` → `parser.error`.
- [ ] **Schritt 4:** Suite grün, Commit `feat(dashboard): decade payload builder and CLI`.

---

### Task 5: Dekaden-Dashboard — Template

**Files:**
- Create: `src/hagrid_demand/baseline/templates/decade_dashboard.html`
- Test: `tests/test_decade_dashboard.py` (`test_template_sections_present`, `test_template_has_no_external_scripts`)

**Interfaces:** Consumes Payload `D` aus Task 4 (Schlüssel exakt wie dort). Abschnitte und Inhalte nach Spec 4.4; Stil-Tokens, Hell/Dunkel und Karten-Ansatz des `annual_dashboard.html` übernehmen (Inline-SVG-Choroplethe, `renderLockers`-Muster für Punkte).

- [ ] **Schritt 1:** Tests (RED): Abschnitts-IDs `hero, growth, mix, channels, map, hotspots, network, calendar, method`; kein `<script src=`; `<title>` „Hannover Parcel Decade“.
- [ ] **Schritt 2:** Template bauen: Szenarioschalter (Radio), Jahres-Scrubber (`<input type=range>` + Abspielen, 700 ms/Jahr, `prefers-reduced-motion` beachten), KPI-Leiste; Fächerdiagramm (SVG, Anker als Punkte, Szenariopfade als Linien, Beschriftung CAGR); Stapelflächen (Anbieter, Kanäle); OOH-Sigmoide; Choroplethe mit drei Modi und Punkt-Layer (neue Punkte pulsieren im Eröffnungsjahr, `year_opened`); Hotspot-Ranking (horizontale Balken, Top 15, B2C/B2B gestapelt); Netz-Karten (Anzahl je Kind, Fächer, Auslastung, neue Standorte je POI-Typ); Kalenderteppich (Canvas 11 × 366, Farbskala sequentiell, Tooltip mit Datum/Sendungen/Grund); Methode (Texte aus `D.meta.assumptions`). Farben: feste Anbieterfarben wie im Jahres-Dashboard; Sequentielle Skala einfarbig; Legenden und Direktbeschriftung.
- [ ] **Schritt 3:** Mit dem echten Trend-Lauf rendern, im Browser prüfen (Konsole fehlerfrei, Handybreite 375 px ohne horizontales Scrollen), Commit `feat(dashboard): decade dashboard template`.

---

### Task 6: Doku

- [ ] `hagrid/demand/model/README.md`: Abschnitt 7 „Mehrjahresprojektion“ (Szenarien mit Zahlen, Netzwachstumsregel, Ausgaben, CLI, Lauf-Skript), `hagrid/demand/README.md` und Wurzel-`README.md` je ein Absatz/Link; Ideenkarte-Eintrag „umgesetzt“.
- [ ] Commit `docs: multi-year projection`.
