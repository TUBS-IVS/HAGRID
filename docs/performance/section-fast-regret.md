# Section FAST regret insertion

`routing.sectionFastRegret` accepts `true` or `false` and defaults to `false`. To opt in, configure `routing.sectionMaxSpanMeters`, `routing.haltCatalogFile`, and `resources.internalSolverThreads=1`. Every section-selected carrier must have an INFINITE fleet. Unsupported combinations fail before routing publication. Other carriers retain their normal routing policy. The normal section search retains its iteration budget, size-based ruin and early termination.
