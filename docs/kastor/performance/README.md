# Local performance evidence - 12 September 2026

Observed on Windows, Intel i9-12900H (14 cores / 20 logical CPUs), JDK 21.0.7.
Baseline and final JMH 1.37 runs used one fork/thread, two 1-second warmups, three
1-second measurements, `-Xmx512m -XX:ActiveProcessorCount=2`, and the GC profiler.
This workstation had other applications running and changing available resources.
Confidence intervals are wide: these are reproducible local measurements, not
production SLOs or a statistically conclusive comparison. The same-runner CI
workflow uses more samples.

The comparison gate requires GC allocation measurements and complete JMH run
settings in both reports. It compares harness/VM versions and batch sizes as well
as JVM arguments, mode, threads, forks, iterations and durations. Eight CLI
regression tests exercise its acceptance and rejection behavior. The calling job
must still establish comparable hardware and host load; JSON cannot establish that.

| Workload | Size | Before us/op | Final us/op | Change | Before B/op | Final B/op |
|---|---:|---:|---:|---:|---:|---:|
| classTargetValidation | 1000 | 4357.958 | 1765.063 | -59.5% | 1859462 | 1178934 |
| classTargetValidation | 10000 | 58816.801 | 17169.158 | -70.8% | 16323636 | 10072212 |
| classTargetValidation | 100000 | 751552.767 | 275915.383 | -63.3% | 163622333 | 101655786 |
| indexedLookup | 1000 | 0.826 | 0.444 | -46.2% | 416 | 416 |
| indexedLookup | 10000 | 1.026 | 0.493 | -52.0% | 448 | 448 |
| indexedLookup | 100000 | 0.936 | 0.520 | -44.5% | 408 | 408 |
| symmetricIsomorphism | 100 | 721.714 | 340.646 | -52.8% | 581752 | 499213 |
| symmetricIsomorphism | 1000 | 34103.431 | 3675.353 | -89.2% | 12834202 | 4795449 |

All eight core cases passed `scripts/check-performance.py` with its 15% latency
and allocation regression budget. Raw `core-baseline.json` and `core-final.json`
preserve measurements and confidence intervals; `core-candidate.json` retains the
intermediate measurement. The baseline precedes indexed isomorphism search/work
budgets and SHACL allocation/statistics cleanup. It is an in-session baseline,
not a prior public release. The final run includes the completed source changes
and Jena 6.2.0 dependency alignment.

## Native lifecycle evidence

`native-soak.csv` records 203 real-model lifecycles (three warmups plus 200 measured
cycles), alternating direct model inference and `SemanticEnricher.default().use`
enrichment. The final Windows run took about 193 seconds; the final sampled RSS
was about 172 MiB. The test checks thread recovery and limits growth between early
and late post-warmup RSS sample medians to 256 MiB. `-1` denotes an unsampled RSS
value. This is a bounded lifecycle probe, not proof of indefinite stability or
peak memory for every workload. Linux execution was blocked by WSL on 12 September; the successful 13 September
Linux lifecycle follow-up is recorded below.

The retained, ignored JMH jars provide bytecode provenance:

- `core-baseline-jmh.jar`: SHA-256 `a40e4c0fded3ac2414fa61e398a582e3a041e8ef59aa76fed9072588e2b016dc`
- `core-candidate-jmh.jar`: SHA-256 `784d91aaea6d23bf6dae0a20f2cfe5775333aae293b98a8472ad206a75de7b19`
- `final-candidate-jmh.jar`: SHA-256 `3e94cc34633d208fb491a6949da63b9f23c6fc2b9f5d476d7b36627bbcdde931`

## Exact 384-dimensional similarity envelope

The same JMH settings were used. Tree setup is amortized by warmup; vectors use
seed 42. Dense vectors are identical; sparse vectors are normalized random vectors.

| Dense | Vectors | Before ms/op | Final ms/op | Final B/op |
|---|---:|---:|---:|---:|
| false | 100 | 5.165 | 3.811 | 5978 |
| false | 1000 | 600.415 | 422.754 | 90648 |
| true | 100 | 7.967 | 5.713 | 127991 |
| true | 1000 | 795.208 | 594.822 | 12079897 |

All four final similarity cases passed the same 15% latency/allocation gate. The
final report includes explicit work/result/deadline checks; the baseline predates
them. The 1,000-vector dense case enumerates 499,500 pairs. High-dimensional exact
search remains expensive even when few pairs are returned; do not infer linear
scalability. Resource limits make exhaustion explicit; they do not remove the
underlying worst-case quadratic cost or the size of a dense result set.

## Latest similarity subtree optimization

The latest candidate skips subtrees whose endpoints have already been processed
and reuses a traversal stack within each independent iterator. Pair semantics,
public API and explicit search limits are preserved. An exhaustive seeded oracle,
a dense work-budget regression and interleaved-iterator regression passed.

This is a fresh before/after comparison on Windows, JDK 21.0.7, using two forks,
one thread, three one-second warmups and five one-second measurements per fork,
with the GC profiler and `-Xmx512m -XX:ActiveProcessorCount=2`. Both jars were run
with identical settings. The earlier reports named `*-final.json` above describe
the preceding candidate; they are not the baseline for this comparison.

| Dense | Vectors | Before ms/op | After ms/op | Before B/op | After B/op |
|---|---:|---:|---:|---:|---:|
| false | 100 | 4.937 | 3.533 | 5985.8 | 464.1 |
| false | 1000 | 664.557 | 566.826 | 91796.0 | 3652.8 |
| true | 100 | 10.744 | 7.273 | 128024.8 | 119289.3 |
| true | 1000 | 1093.466 | 627.090 | 12081947.6 | 11991882.4 |

All four cases passed the existing 15% latency/allocation regression gate. The
1,000-vector dense case was about 43% faster; the sparse case allocated about 96%
fewer bytes. These are local observations with wide confidence intervals, not
production SLOs. Exact search and dense output remain worst-case quadratic.

Raw reports: [before](similarity-pruning-before.json) and
[after](similarity-pruning-after.json). Retained ignored benchmark jar hashes:

- Before: `3e94cc34633d208fb491a6949da63b9f23c6fc2b9f5d476d7b36627bbcdde931`
- After: `33adbc683a8f2d11b10f310d7827a9f1a7d3a9ba9eeff22d263d51424ce40a60`

The current embedding check and API compatibility gate passed with 15 tests,
zero skips and zero failures. [The fresh native soak](native-soak-pruning.csv)
completed three warmups and 200 measured cycles in about 180 seconds; final
sampled RSS was about 108 MiB. Differences from the earlier RSS sample are not
attributed to this optimization. Linux verification was blocked at this measurement; the successful follow-up is below.


## Linux lifecycle verification — 13 September

[Linux native soak CSV](native-soak-linux.csv) records three warmups and 200
measured cycles on JDK 21.0.12. The corrected run took about 200 seconds; final
sampled RSS was about 415 MiB with 11 live threads. Thread and RSS growth checks
passed, as did all 15 embedding and 72 semantic-quality tests with zero skips.
The host was loaded and swapping; these timings/RSS levels are not a controlled
comparison against Windows or evidence of production peak memory. Earlier WSL
blocker statements above describe the previous day's environment; Linux
verification is now complete. No JMH benchmarks were rerun in this follow-up.
