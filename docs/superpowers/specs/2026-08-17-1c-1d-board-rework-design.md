# Design Spec: 1c/1d Board Rework — Mechanism Tabs, Usage-Coloured Maps, Convention Labelling

**Date:** 2026-08-17
**Status:** Draft — awaiting user review
**Scope:** the per-run v2 dashboard for `DRT_SHAREDUSE` (1c) and `DRT_MODULAR` (1d). Baseline and
LMD boards change in exactly one place (§6). The comparison page inherits the new tabs without
any change of its own, because it calls the same `build_tab` entry points.
**Parent:** [`2026-07-10-run-dashboard-v2-design.md`](2026-07-10-run-dashboard-v2-design.md),
plans [C](../plans/2026-07-13-run-dashboard-v2-planC-rendering.md) /
[D-maps](../plans/2026-07-13-run-dashboard-v2-planD-maps.md)

---

## 1. Motivation

Four defects, all in the presentation layer — no extractor produces wrong numbers.

**The second tab on a 1c/1d board is the LMD tab, and it is structurally empty.** It appears
because `has_lmd` asks "are there freight KPIs?"
([`render.py:522`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render.py#L522)) — and
1c/1d do have freight, it simply rides the DRT fleet. What renders is
[`render_lmd.py`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render_lmd.py): provider
bars, per-provider cost stacks, vehicle-type bars, a fleet donut, carrier scores. 1c/1d have no
providers, no vehicle types and no carriers, so the tab is mostly empty or zero-filled.

**The mechanism KPIs already exist and are shown nowhere.**
[`extract_modular.py`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/extract_modular.py)
emits 24 `freight` rows (including `tours_completed_late`, `parcels_served_late` and the full δ
decomposition) plus 6 `modular` rows (swaps, retooling hours, deadhead). `chi_detour.py` writes
per-outcome histograms and quantiles into `kpi_distributions.csv`. Both reach the CSV and stop
there.

**The map hides the integration mechanism.** Polylines are bucketed by seat occupancy
([`maps.py:66`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py#L66)). On 1d a
freight excursion is occupancy-0, so it is drawn exactly like an empty run — the code compensates
with a legend caption
([`maps.py:23-25`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/maps.py#L23)), which is a
note, not a distinction. On 1c freight overlays passenger occupancy and is not encoded at all.

**Two delivery rates sit unlabelled next to each other.** Since the 2026-08-10 convention change
(METHODS-LOG §2.21) every arm reports `delivery_rate` on one basis, with the net value preserved
as `delivery_rate_net_overlay`. Both land in the KPI table with nothing saying which subtracts the
not-at-home overlay.

## 2. Decisions (user-decided 2026-08-17)

| # | Decision | Consequence |
|---|---|---|
| D1 | The boards are a **working instrument** for run analysis, not a source of paper figures | Optimise for diagnostic depth; no export path, no figure polish |
| D2 | The second tab becomes a **mechanism tab per scenario** | 1c → "Cargo-Hitching", 1d → "Kapsel-Tausch"; Baseline/LMD keep today's tab unchanged |
| D3 | **Approach A**: two new renderers plus a dispatcher | Not a branching `render_freight.py`, not data-driven block assembly |
| D4 | The map gets a **toggle** between occupancy and usage mode | Both colourings stay available; neither replaces the other |
| D5 | net/gross: **labelling only** | The convention itself is already implemented; only the board is silent about it |
| D6 | `*_pax` / contamination-marker consolidation, ctrl1d badges, Plan-D maps, `occ_km`, parcels/h stay **out** | See §10 |

## 3. Architecture

### 3.1 Module layout

Two new modules mirroring the existing pair, each exposing the signature that
[`render_drt.py:564`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render_drt.py#L564) and
[`render_lmd.py:855`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render_lmd.py#L855)
already use:

```python
def build_tab(data, uid, compact=False, map_block=None) -> (html, js)
```

- `render_shareduse.py` — the 1c mechanism tab
- `render_modular.py` — the 1d mechanism tab

Both are imported inside the calling function, not at module level: `render_drt`/`render_lmd`
import names back out of `render`, and a module-level import would be circular
([`render.py:511-516`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render.py#L511)).
The new modules follow that rule for the same reason.

Rejected alternatives: a single `render_freight.py` with internal branches (`render_lmd.py` is
already 993 lines, and a χ distribution shares no shape with a capsule-swap balance), and
data-driven block assembly (does not solve the label, and the diagnostic narrative cannot be
assembled from KPI availability).

### 3.2 Tab dispatch

`has_lmd` is replaced by a scenario-driven choice. The scenario is read from the `scenario`
column of `data.kpis`
([`render.py:151`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render.py#L151)), so
neither caller changes and no second source of truth appears — `build_kpis.py` and
`build_comparison.py` keep calling `render_run_page(data, title, maps)` /
`render_comparison_page(runs, title)` as they do today.

| `scenario` | Tab 2 label | Renderer |
|---|---|---|
| `DRT_SHAREDUSE` | Cargo-Hitching | `render_shareduse` |
| `DRT_MODULAR` | Kapsel-Tausch | `render_modular` |
| anything else with provider rows or `freight` KPIs | LMD | `render_lmd` (unchanged) |

The DRT tab (`has_drt`, gated on `passenger` KPIs) is untouched in all cases.

**Fallback:** an unknown or missing scenario value falls through to the existing `has_lmd`
behaviour rather than dropping the tab. A board that silently loses a tab is worse than one
showing the old one.

### 3.3 Shared building blocks

`render_lmd._dist_bar`
([`render_lmd.py:475`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render_lmd.py#L475))
is exactly the histogram renderer 1c needs, and `_ts_bar`, `_bin_label`, `_fmt_num` are similarly
generic. **Rule: extract to a shared module only on second use, not on speculation.** When
`render_shareduse` needs `_dist_bar`, that function (and only it) moves to a shared helper module
with both callers updated in the same edit. No pre-emptive refactoring of `render_lmd.py`.

## 4. Tab content

### 4.1 Cargo-Hitching (1c)

1. **χ detour distribution, delivered vs. expired**, from the per-outcome histogram series
   `chi_detour.py` writes via `dist_row`
   ([`chi_detour.py:156`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/chi_detour.py#L156)).
   The two buckets overlaid is the comparison the instrument was built for.
2. **Cumulative curve** "how many segments would have needed a χ ≤ x". Per
   [`chi_detour.py:20-22`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/chi_detour.py#L20)
   this is the single-run approximation of δ(χ) and therefore the chart that places the sweep
   grid.
3. **Channel balance**: injected → submitted → delivered / delivered_late / expired, from the
   `channel` KPI group in
   [`extract_shareduse.py`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/extract_shareduse.py).
4. **Freight tiles**: `delivery_rate`, segments, evaluations
   (`chi_detour_segments_evaluated`, `chi_detour_evaluations_total`).

**Vintage warning (new, and the reason this tab is more than a port).** The detour CSV carries no
version stamp, and files written before the 2026-08-13 gate fix are biased — in `chid600det`
35 % of segments sat at exactly 0, which makes `p25 = 0` a clamp boundary rather than a quantile
([`chi_detour.py:41-50`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/chi_detour.py#L41)).
The tab detects the signature heuristically (share of exact-zero segments above a threshold) and
prints a visible caveat next to the chart. A board that draws a pre-fix file silently produces
precisely the kind of number nobody can place six weeks later.

### 4.2 Kapsel-Tausch (1d)

All values already exist in `kpis_long.csv`; this is rendering only.

1. **Tour lifecycle funnel**: `tours_planned → tours_dispatched → tours_completed`, with the
   outflows `tours_expired_pending`, `tours_rejected_at_splice`, `tours_pending_eod`,
   `tours_dispatched_incomplete`. This view does not exist today in any form.
2. **δ decomposition**: `delta_parcels` split into `delta_share_undispatched` versus
   `delta_share_dispatched_incomplete` — "never left" and "left but did not finish" are different
   failures with different fixes.
3. **C8 lateness**: `tours_completed_late`, `parcels_served_late` beside their on-time
   counterparts.
4. **Cost of coupling**: the `modular` group — `swaps_completed`, `retooling_hours`,
   `peak_concurrent_swaps`, `freight_vehicle_hours`, `deadhead_km_planned` vs.
   `service_km_planned`.

Every block follows the existing `v is None -> continue` guard, so a run missing a KPI drops the
block instead of rendering a zero.

## 5. Map: usage-mode colouring

### 5.1 Data path

`geometry.reconstruct_drt_paths_detailed` already produces
`veh_path[vehicle_id] = [(link_id, occ_pax, occ_parcels, t), ...]`
([`geometry.py:57-61`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/geometry.py#L57)) —
separate counters. `project_paths` then flattens them to `(link_id, pax + parcels)`
([`geometry.py:118-128`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/geometry.py#L118)),
and that 2-tuple is what the map consumes. The information is produced and discarded on the way
to the renderer.

`maps._build_vehicles` gains a second bucketing over the detailed paths, keyed by usage category
instead of occupancy:

| Category | Condition | Present in |
|---|---|---|
| passengers only | `occ_pax > 0 and occ_parcels == 0` | 1c, 1d |
| freight only | `occ_pax == 0 and occ_parcels > 0` | 1c, 1d |
| both | `occ_pax > 0 and occ_parcels > 0` | 1c only |
| empty | both zero | all |

The occupancy buckets stay as they are; the emitted map block carries both, and a radio switches
between them — same pattern as the existing LMD tours/stops/heat radio
([`render_maps.py:89-95`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render_maps.py#L89)
plus its JS at
[`:247-260`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render_maps.py#L247)).
That "both" is 1c-only falls out of the data, not out of a convention: capsule swap is
exclusive by construction.

### 5.2 The 1d gap — the one real risk in this spec

`occ_parcels` is documented as "parcels aboard (1c **only**; always 0 elsewhere)"
([`geometry.py:83`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/geometry.py#L83)),
because 1c models parcels as `parcel_`-prefixed persons and therefore gets the split from
`PersonEntersVehicle` for free. For 1d the separation comes from DVRP task windows
([`geometry.py:72-74`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/geometry.py#L72)),
which is how the modular arm already separates freight km from pax km — but that derivation lives
in the extractor, not in the map path.

So: **1c is wiring, 1d needs the task windows pulled into the map layer.** Whether those windows
are already available per vehicle or must be reconstructed is not settled by this spec. The
implementation plan resolves it in its first 1d task; if it turns out expensive, that is reported
rather than quietly dropped, and the toggle ships 1c-only in the interim (the 1c half stands on
its own).

## 6. Convention labelling

Concretely, in the KPI table: `delivery_rate` keeps its position and gains the qualifier
*operativ* in its label; `delivery_rate_net_overlay` is rendered immediately below it, visually
subordinated (indent or muted styling — the plan picks one), labelled *netto (Overlay abgezogen)*
and carrying a one-line note that the overlay is cosmetic per the 2026-08-10 decision
(METHODS-LOG §2.21). The two rows must never be separable by sorting or grouping — the whole
defect is that they can be read independently.

This applies to **all** arms, not only 1c/1d: the confusion arises exactly where both appear side
by side, which is every board that has freight. Sources are already carried per row and cited in
tooltips
([`render._kpi_source:198`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render.py#L198)),
so this is a presentation change, not a data change.

## 7. Testing

TDD throughout, following the existing fixture style in
[`analysis/kpi/tests/`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/tests/):

- **Dispatch**: for each of the four scenario values, the expected tab label. Discriminates
  against the LMD tab returning on 1c/1d, and against the new tabs leaking onto Baseline/LMD.
- **Per renderer**: a fixture `kpis_long.csv` produces the expected blocks, and a fixture missing
  a KPI drops that block rather than rendering a zero.
- **Vintage warning**: a fixture with a high exact-zero share triggers the caveat, one without
  does not. Both directions — a warning that always fires says nothing.
- **Map categories**: 4-tuple paths bucket into the four categories; a 1c fixture yields a
  non-empty "both" bucket, a 1d fixture does not.
- **Regression**: the existing Python KPI suite plus a smoke build of one real 1c and one real 1d
  run directory.

## 8. Facts verified against the code (2026-08-17)

Everything asserted above was read, not remembered:

- `has_lmd` triggers on `freight` KPIs — [`render.py:522`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render.py#L522)
- `RunData` fields and the `scenario` column — [`render.py:151`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render.py#L151), [`:168-186`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/render.py#L168)
- identical `build_tab` signature in both existing renderers — `render_drt.py:564`, `render_lmd.py:855`
- detailed 4-tuple paths and their flattening — `geometry.py:57-61`, `:118-128`
- `occ_parcels` is 1c-only — `geometry.py:83`
- occupancy bucketing in the map — `maps.py:66`; the 1d legend-caption workaround — `maps.py:23-25`
- radio pattern to copy — `render_maps.py:89-95`, `:248-259`
- histogram emission — `chi_detour.py:156`; vintage caveat — `chi_detour.py:41-50`
- the 24 + 6 modular KPI names — `extract_modular.py`
- the delivery-rate convention is implemented in all four extractors, with
  [`tests/test_delivery_rate_convention.py`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/tests/test_delivery_rate_convention.py)
  pinning it

## 9. Risks and open items

1. **1d map derivation** (§5.2) — the only genuinely unknown cost in this spec.
2. **`BIN_WIDTH_S = 100` is too coarse below 200 s** ([`chi_detour.py:65`](../../../parcel-demand-2-matsim-pipeline/analysis/kpi/chi_detour.py#L65)).
   Independent of this work and listed in BACKLOG as `[L]`; it only bites once the new χ grid is
   run. Not fixed here, but the 1c tab is the place where it will become visible.
3. **No valid 1c run exists right now.** Both operating points were invalidated by the χ-gate fix
   (METHODS-LOG §2.35), so the 1c tab can be built and tested against `chid600det` for *shape*,
   but its numbers must not be read as results until the anchor rerun lands.
4. **The delivery-rate convention work is uncommitted** — four modified extractors plus a new test
   sit in the working tree. This rework builds on top of it.

## 10. Out of scope

Deliberately excluded, each recoverable later without rework:

- `*_pax` rows, contamination markers, `pax_only` overrides and meta rows (BACKLOG `[M]` "KPI-Landschaft konsolidieren") — the one item that would get cheaper alongside this work, since the tab structure is open anyway
- ctrl1d badges without `*_pax` companions (BACKLOG `[L]`)
- Plan-D maps proper (depot siting, vehicle tours into the tabs)
- `occ_km` and delivered parcels/h (BACKLOG `[M]`, Plan D (b))
- any change to the extractors, the cost function, or the legacy Java dashboard

## 11. Effort

Sections 3, 4 and 6 roughly 1–1.5 days. The map: ~0.5 days for 1c, 0.5–1.5 days for 1d depending
on §5.2.
