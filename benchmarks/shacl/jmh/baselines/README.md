# SHACL benchmark baselines

`reference.json` is the reviewed baseline manifest of the performance gate (`.github/workflows/performance-regression.yml`):
the source revision the candidate is compared with, the regression budget (`maximumRegression`), the benchmark pattern
and the minimum number of cases each comparison must cover.

The gate builds the JMH jar of the baseline revision and of the candidate, runs both on the same runner with
`scripts/run-performance-comparison.py` (for the `.*ScalingBenchmark.*` cases: 5 warmup and 8 measurement iterations of
1 s, 2 forks; longer than the defaults of `./gradlew :benchmarks:shacl:jmh`), and fails with `scripts/check-performance.py`
when a case regresses by more than the budget.

`SimilarityClusteredBenchmark` (embedding-like vectors at 10,000 and 50,000 labels, exact and LSH search, tree build) is
not in the gate: run it on demand when the similarity search changes.

To move the baseline, change `commit` in `reference.json` (or pass `baseline_ref` to the workflow) to a reviewed revision
that contains the same benchmark cases, and say why in `reason`.

See [SHACL validation architecture](../../../../docs/kastor/design/shacl-validation-architecture.md) for threshold policy intent.
