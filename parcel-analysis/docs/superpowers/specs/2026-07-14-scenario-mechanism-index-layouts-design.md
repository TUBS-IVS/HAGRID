# Scenario mechanism index layouts

## Objective

Extend the existing Full Consolidation mechanism-index plot in
`specialIssue-journal-split/04_costs_efficiency_and_tradeoffs.ipynb` to compare
Moderate, High, and Full Consolidation at the established journal width of
7.48 inches. Baseline is the common reference (`Index = 100`).

## Shared data and visual encoding

- Reuse the existing `weekly`, `bl`, `compare_sc`, and scenario labels computed
  in the diagnostic cell; do not introduce a second aggregation path.
- Show the same ten metrics in every scenario panel and preserve their order.
- Keep the existing semantic group colors for Operations, Cost component,
  Outcome, and Utilization.
- Use a thin dashed vertical line at index 100, neutral connector lines, colored
  markers, and direct integer value labels.
- Use the notebook's established paper typography and a 7.48-inch figure width.
- Use one shared legend per figure and avoid repeated axis labels or titles.

## Output variants

### Variant A: three panels in one row

Moderate, High, and Full Consolidation are arranged from left to right. Metric
labels appear only on the left panel, all panels use the same x-axis limits, and
the shared legend is placed below the panels. This version prioritizes direct
horizontal scenario comparison.

### Variant B: 2 x 2 with legend panel

The three scenario panels occupy the first three cells. The fourth cell contains
the category legend and a concise note that all values are indexed to the
Baseline. This version prioritizes readability at print size.

### Variant C: 2 x 2 with Baseline reference panel

The first cell presents the absolute Baseline values for the ten metrics as a
compact aligned reference list with appropriate units. The other three cells
show Moderate, High, and Full Consolidation as indices relative to that
Baseline. A small shared legend is placed below the grid. This version combines
absolute context with relative scenario effects.

### Variant D: 2 x 2 with Baseline benchmark spine

Keep Variant C unchanged for comparison and add a more visual treatment of its
Baseline panel. The ten Baseline observations remain aligned row by row with the
scenario panels, but are anchored to a vertical reference spine. Semantic group
colors mark the spine, faint row guides connect labels and values, and numeric
values and units use separate aligned columns. This preserves quantitative
clarity while making the reference panel read as part of the figure rather than
as a detached table.

The horizontal guides in the benchmark panel terminate before the numeric value
column so that no rule runs through text.

### Variant E: 2 x 2 with Baseline operating profile

Add a deliberately less tabular alternative while retaining all ten Baseline
metrics. The panel uses three large weekly-scale indicators for tours, total
distance, and total vehicle cost; a proportional stacked bar for fixed,
distance, and overtime cost; and a compact 2 x 2 productivity block for distance
per tour, cost per parcel, parcels per tour, and vehicle utilization. The other
three scenario panels remain unchanged. Direct labels make the internal colors
self-explanatory without expanding the shared figure legend.

## Files

The notebook cell will save three PDF outputs and matching PNG previews:

- `journal_cost_extra_09_mechanism_index_1x3.pdf`
- `journal_cost_extra_09_mechanism_index_2x2_legend.pdf`
- `journal_cost_extra_09_mechanism_index_2x2_baseline.pdf`
- `journal_cost_extra_09_mechanism_index_2x2_baseline_spine.pdf`
- `journal_cost_extra_09_mechanism_index_2x2_baseline_profile.pdf`

The existing Full-only output is replaced because all of its information is
contained in the new comparison figures.

## Verification

- Execute the notebook through the modified diagnostic cell from a fresh kernel.
- Confirm all three PDFs and PNG previews are produced without missing-variable
  errors.
- Render the PDFs and inspect them at full two-column width for clipped labels,
  overlapping value annotations, inconsistent scales, and excessive whitespace.
- Numerically confirm that every plotted index equals the scenario value divided
  by the corresponding Baseline value times 100.
