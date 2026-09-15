# Task 3 report — grid-free potentials and DHL reference

## Scope and implementation

Implemented only the Task 3 package files and its targeted test file:

- `hagrid-demand/src/hagrid_demand/baseline/potentials.py`
- `hagrid-demand/src/hagrid_demand/baseline/reference.py`
- `hagrid-demand/tests/test_baseline_reference.py`

The canonical potential is intentionally `private = persons` and
`business = one unit per company location`.  `power` and
`branch_multipliers` select an explicit `employee_branch_candidate`; the old
`1 + 0.1 * employees` heuristic is not present as a default or implicit fit.
Each row remains a real demand location; no grid, spatial buffer, DHL-street
split, or old `b2b_ratio` value is used.

`historical_quantreg_predict` is exactly

```text
8.1078087809025286e-07
+ 0.15205915924489793 * persons
+ 0.89862556511587188 * companies
```

and is named `historical_q75` in comparison output.  It is a historical
q=.75 comparison, not a mean or Lasso model.  The no-intercept, nonnegative
two-feature fit is labelled `DHL-response rates`; those coefficients are not
reported as total-market or causal B2B rates.  Spatial folds hold out complete
groups; preprocessing for optional employee/branch features is learned from
the training fold.  Metrics are MAE, wMAPE (returned as `None` for a
zero-actual holdout), and bias.  The result remains `comparison_only` and
becomes `not_demonstrated` with no evaluable holdouts.

`build_contiguous_groups` uses only unit IDs, PLZ, person/company support, and
same-PLZ adjacency.  It never reads a DHL target column.  A component crossing
a PLZ boundary is disconnected, oversized components and under-supported
components receive an explicit unresolved status, and every whole observation
unit occurs exactly once.

## Reconciliation and reference formulas

For carrier market shares `m`, prior carrier B2B rates `q_prior`, target `b`,
and positive change scales `scale`, `reconcile_carriers` solves

```text
min sum(((q - q_prior) / scale)^2)
subject to m @ q = b and lower <= q <= upper.
```

It first checks the feasible interval `m @ lower <= b <= m @ upper`, then uses
SLSQP with a `LinearConstraint`.  Bounds and the equality balance are checked
again independently of the optimizer success message.  The conditional
profiles are

```text
r_private,c  = m[c] * (1 - q[c]) / (1 - b)
r_business,c = m[c] * q[c] / b.
```

The `b=0` and `b=1` cases use their active segment directly and preserve zero
market-share carriers at zero conditional mass.  `solve_reference` accepts an
already reconciled `conditional` profile or raw reconciliation inputs and
records reconciliation diagnostics in the reference checks.

DHL observations are validated before aggregation; missing and negative values
are separate errors.  Whole rows with `value > 1000` are excluded, while 1000
is retained.  The scope ledger records excluded/retained rows and mass.  For
each retained PLZ, with private potential `P`, business potential `B`, and
`k = exp(eta)`, the reference uses

```text
local_b   = k * B / (P + k * B)
dhl_share = (1 - local_b) * r_private,DHL + local_b * r_business,DHL
postal_total_mean = dhl_value / dhl_share
F(eta) = sum(postal_total_mean * local_b) / sum(postal_total_mean) - b.
```

`eta` is found with `brentq` over `[-30, 30]`, `xtol=1e-12`; it is never fit
freely per site.  Constant-support and missing-sign-change cases provide a
diagnostic error.  Positive DHL requires both structural support and a
positive local DHL share.  A zero-total retained region is an error.  The
final B2B residual is checked against `1e-10`.

Annual PLZ totals multiply the mean exactly once by `operating_days`; private
and business mass is distributed inside each PLZ/segment proportional to the
corresponding potential.  `historical_share` and `structural_share` are
separately normalized per segment, while empty PLZ segment support is marked
explicitly.  Known-PLZ `unlocated` objects stay in the amount-bearing output;
objects with unknown PLZ are held in `source_quality`, with no invented demand.
The checks include reconstructed DHL mean, annual balance, allocation ledger,
and `k`/`eta`.  At consistent profiles the regional annual total is
`sum(DHL) / m_DHL * operating_days`.

`implied_rates` exposes `packages/person/operating day` and
`packages/company location/operating day`, labelled as aggregated model rates
rather than individual causal ordering rates.

## TDD record and tests

RED was recorded with:

```text
python -B -m pytest tests/test_baseline_reference.py -q
```

before either implementation module existed.  It failed seven tests with the
expected `ModuleNotFoundError` for `baseline.potentials` and
`baseline.reference`.

GREEN was recorded after implementation with the same command; the final
focused run passed 9 tests.  The test cases cover:

- SLSQP reconciliation balance and infeasible bounds;
- default people/company-location potentials and explicit employee candidate;
- exact historical-q75 identity at `1e-10`, nonnegative mean labels, and
  spatial comparison semantics;
- topology-only contiguous groups, no PLZ crossing, whole-unit accounting,
  and unresolved support;
- the 1000/1000.0001 boundary, mixed and zero-DHL PLZs, unlocated known-PLZ
  mass, and unknown-PLZ inventory;
- raw-provider reconciliation before `eta`, invalid DHL values, zero DHL
  profile, empty support, constant B2B support, missing root, and active
  segment support at B2B boundaries.

The Task 1/2/3 focused suites were also run together: 39 passed.  Fresh final
verification after the last cleanup passed the complete package suite: 102
passed, with two pre-existing `pyproj` deprecation warnings.

## Self-review and concerns

Reviewed the formulas against the design, specifically the addendum that
replaces the employee heuristic, profile feasibility, the whole-observation
threshold, zero support, and preservation of known-PLZ unlocated mass.  The
candidate-comparison APIs are deliberately comparison-only; they do not claim
accuracy from the DHL anchors used for the reference.

The task brief requested two independent Terra reviews.  The parent task
explicitly prohibited subagents, so an independent reviewer could not be run
within this delegation.  This report records that limitation rather than
claiming such a review occurred.  Task 4 still needs to wire these pure
functions into source/workflow artifacts and may refine its input adapters;
that is outside Task 3.

## Review round 1 fixes (Sol findings)

This follow-up keeps the model assumptions unchanged and closes the review
findings without adding an accuracy claim.

- Explicit spatial folds now reject empty, unknown, overlapping, or repeated
  held-out groups.  Metrics are pooled from row-level holdout predictions,
  rather than averaging fold metrics; wMAPE remains `None` if the pooled actual
  denominator is zero.  The output records held-out row counts.
- Connected components are partitioned deterministically into connected whole
  groups with at most `max_units`.  Under-supported remnants remain explicitly
  unresolved.  The result is invariant to input/edge ordering and to any
  unused DHL-target column.
- The eta equation now evaluates `local_b` in stable log-odds form.  Bracketing
  starts at `[-30, 30]` and expands up to a safe finite limit when the root is
  outside that initial interval.  Pure constant support and a genuinely
  same-sign reachable range still fail diagnostically.
- DHL values and potential weights are finite and nonnegative before scope
  classification; `+inf` can no longer be misclassified as threshold-excluded.
  Fit and comparison inputs validate finite nonnegative features/targets and
  reject all-zero responses.  The canonical potential API only requires fields
  used by canonical locations; employee data is required only by an explicit
  candidate, and a nondefault power cannot be silently ignored.
- Carrier reconciliation still calls SLSQP with a linear equality constraint,
  then applies an exact bounded one-equality quadratic active-set polish before
  its independent bound/balance checks.  This removes solver-tolerance noise
  from the analytically known quadratic optimum.
- The old regional diagnostic compared two values computed from the same postal
  total.  It now checks site allocations against every PLZ/segment total and
  the independent regional site sum; supported historical and structural
  shares are verified to normalize to one.

Review-round RED was captured by running the extended Task 3 suite against the
previous commit: eight behavioural failures exposed the missing validations,
partitioning, pooled metrics, finite checks, allocation diagnostic, and
adaptive eta range.  GREEN then passed 14 focused tests and 44 Task 1–3 tests.
Final package verification passed 107 tests, with two existing `pyproj`
deprecation warnings.

## Review round 2 fixes

The second review round again changes validation and numerical robustness only;
it makes no accuracy claim.

- Structural constancy of the eta equation is now determined from positive-DHL
  PLZ support: only the absence of any mixed private/business PLZ is constant.
  Mixed support always begins with `[-30,30]` and expands safely before a
  same-sign failure is reported.  Log-odds evaluation remains stable for the
  tested `P=1e100, B=1` root at `eta=100*log(10)`.
- Bounded carrier reconciliation now solves the monotone scalar Lagrange
  equation `m @ clip(q_prior - lambda*m*scale**2, lower, upper) = b`.  This
  gives the exact bounded quadratic optimum and permits a coordinate to leave
  a provisional clamp.  SLSQP remains the required first solver and its result
  is independently checked after the exact scalar polish.
- Contiguous grouping now searches deterministic connected subsets containing
  the next canonical unit to find a complete supported partition when one is
  feasible; only then does it use a residual-aware deterministic fallback.
  `max_units` must be a finite positive integer, and aggregated support sums
  must remain finite.
- Optional employee/branch candidates must fit every common holdout.  Any
  rank-deficient fold marks that candidate `non_comparable` with diagnostics;
  partial-fold metrics are discarded.
- Reference checks now compare allocated and expected PLZ/segment values
  directly using the model tolerances, and separately compare the direct site
  regional sum.  Postal support, inferred postal totals, and annual totals are
  checked for finite nonnegative values after aggregation.

RED was recorded with four failing second-round behaviours: clamp release,
connected feasible partitioning, rank-deficient candidate comparability, and
the extreme mixed-support eta root.  GREEN passed 20 focused Task 3 tests and
50 combined Task 1–3 tests.  Final package verification passed 113 tests, with
two existing `pyproj` deprecation warnings.

## Review round 3 fixes

The third review round addresses termination and floating-point range without
altering the reference assumptions or making an accuracy claim.

- Carrier reconciliation now snaps a target lying within the configured
  feasibility tolerance to the exact feasible boundary and records the
  JSON-safe `target_adjustment`.  Its exact projection uses finite clip
  breakpoints of the monotone Lagrange equation, detects unreachable plateaus,
  and normalizes scales before SLSQP/objective reporting so tiny positive scales
  do not create an overflow warning or an unbounded expansion loop.
- Eta brackets are derived from the finite mixed-support log-ratio range,
  extended only when necessary, and evaluated with `expit`.  There is no fixed
  700 cap: the `P=1e300, B=1e-300` case reaches `eta=600*log(10)`.  When
  `exp(eta)` cannot be represented, checks retain finite `eta`/`log_k`, set
  `k=None`, and report `k_status='overflow_log_k_retained'`.
- Site allocation divides potential weight by its segment denominator before
  multiplying annual mass, so a finite `1e308/1e308` ratio remains finite.
- Grouping no longer materializes connected subsets or recursively searches
  partitions.  It uses deterministic iterative, support-aware BFS groups and
  marks only unsupported leftovers as `unresolved_residual_support`.  It
  validates finite positive integral `max_units` and normalized unique string
  IDs, eliminating collisions such as `1` versus `'1'`.

RED captured solver-boundary handling, recursion failure on a 1,100-unit chain,
and the `eta≈1381.55` range failure.  GREEN passed 23 focused Task 3 tests.
Final package verification passed 116 tests, with two existing `pyproj`
deprecation warnings.

## Review round 4 fixes

The fourth review round keeps the reference assumptions intact and closes the
remaining numerical and grouping edge cases.

- Feasibility now uses the exact floating-point interval produced by
  `m @ lower` and `m @ upper`: a target outside it is rejected even when it is
  within the general validation tolerance.  No effective target is substituted
  downstream.  The clipped quadratic root uses exact residual signs, so valid
  interior targets such as `1e-9` and `1-1e-9` remain interior rather than
  being treated as endpoints.
- The clipped Lagrange equation evaluates the multiplier in signed log space.
  It therefore handles a required movement whose `scale**2` underflows, such
  as `m=(.5,.5)`, `q_prior=(.5,.5)`, `b=.4`, bounds
  `([0,.5],[1,.5])`, and scales `(1e-300,1)`, which returns `q=(.3,.5)`.
  Bracketing and bisection have explicit finite bounds.  The user-facing
  diagnostic `objective` is again the original
  `sum(((q-q_prior)/scale)**2)` objective; the separately named
  `conditioning_objective` is available for normalized conditioning.  An
  unrepresentable diagnostic is JSON-safe as `None` with its retained log and
  `overflow_log_retained` status.
- `log_k` remains the canonical finite eta representation at both range
  extremes.  Positive eta overflow keeps the previous
  `overflow_log_k_retained` status; negative eta underflow now returns
  `k=None` with `underflow_log_k_retained` instead of a misleading finite zero.
- Grouping still uses the iterative greedy path whenever it resolves a
  component.  If a small component has an unresolved greedy residue, a
  deterministic, memoized connected-partition repair searches supported
  partitions within explicit limits of 16 units, 50,000 states, and 100,000
  subset expansions.  A component beyond that proof budget reports
  `unresolved_search_budget`; genuinely insufficient total structural support
  remains `unresolved_structural_support`.  This repairs the reviewed star and
  path counterexamples without slowing the 1,100-unit chain or the
  30-node complete graph with zero minima.
- Group IDs are a collision-safe JSON tuple containing a version marker, PLZ
  type/value, and canonical seed, replacing ambiguous `plz:unit_id`
  concatenation.

RED added exact regressions for the near-endpoint and near-feasibility
reconciliation cases, both scale-range counterexamples, original versus
conditioning objective labels, negative eta underflow, star/path group repair,
the search-budget status, and the PLZ/colon ID collision.  The initial focused
run exposed the endpoint collapse, scale plateau, greedy residues, ambiguous
ID, and missing eta underflow status; the wide scale range additionally exposed
normalization underflow in diagnostics.  GREEN passed 26 focused Task 3 tests
and 56 combined Task 1--3 tests.  Final package verification passed 119 tests,
with the same two existing `pyproj` deprecation warnings.
