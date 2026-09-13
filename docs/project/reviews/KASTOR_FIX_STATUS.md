**Kastor review remediation — 12 September 2026**

This records changes against the 33 findings in [the original review](KASTOR_CODE_REVIEW.md). That review's 48/100 score describes the original commit, not this working tree. The release contract, supported limits, migration notes, and repeatable verification commands are in [release-contract.md](docs/reference/release-contract.md).

| Findings | Implemented correction | Regression coverage |
|---|---|---|
| 1–2 | Non-destructive Jena inference views; transactional additive dataset import | Persistent close/reopen, newly added entailments, append and rollback |
| 3–5 | Fixed RDF term identity, compact color refinement, exact bounded blank-node bijection | Fixed-term mismatch, nested triple terms, symmetric non-isomorphic cycles, actual mapping |
| 6–9 | Correct default/named SPARQL rendering; bound-position reconstruction; explicit unsupported-term errors; blank-node batch rules; strict incremental JSON framing | Local HTTP server executing generated queries through Jena; all eight pattern combinations; malformed results; blank-node behavior |
| 10–13 | Full graph retained for resource validation; validity independent of report cap; real query bindings; cooperative deadlines and expanded-shape budgets; unsupported modes rejected | Multi-hop resource paths, mixed severities with cap, bound literals/blank nodes/BOUND, deadlines and unsupported options |
| 14 | Validator-local bounded LRU and version history, cache statistics, invalidation, synchronized compilation | Eviction, bounded tag count, cache hits, clearing and namespace isolation |
| 15 | Subject/predicate/object pattern API, local indexes and backend pushdown throughout domain read/write helpers | Provider pattern queries and existing runtime suites |
| 16–17 | Scoped SELECT/CONSTRUCT APIs; bounded incremental HTTP results; owned closeable parser; interruptible Flow producer | Early query/parse exit, Flow take(1), input closure, response framing |
| 18–19 | Memory rollback/isolation, local read-only transaction guards, nested joins, closed-view checks, graph clear isolation, truthful variant capabilities | Memory rollback/index restoration; Jena and RDF4J transaction and persistence tests; default/named clear checks |
| 20–22 | Input-content fingerprints, consistent aliases and packages, generated-file ownership manifest, source-set wiring, correct KSP deferral and originating inputs | Generator unit suites and independent Gradle TestKit compilation/execution, input edit, rename, and shape removal |
| 23 | Concurrent materialization registry; validation context preserved when replacing known predicates; explicit snapshot/registration policy | Existing materialization and validation-context suites; generated consumer |
| 24–25 | Real HermiT hierarchy/instance classification; separate consistency path; engine disposal; timeout, admission and output limits | Nonempty hierarchy/instance checks under a one-triple materialization budget, existing entailment and inconsistency tests |
| 26–27 | Pinned model revision; streaming verified atomic download under lock; closed session options/tokenizer/session; inference-close synchronization | Local redirect/corrupt-download test; opt-in real model inference and concurrent close |
| 28 | Exact metric-tree range pruning; finite normalized vector validation; iterative SCC traversal | Exhaustive reference comparison at five thresholds; 50,000-node path and cycle |
| 29 | Lazy merged/query graph, one scoped query session per validation, per-run regex reuse, reused data index for statistics, bounded iterative import traversal | Existing native/W3C suites and targeted validation regressions |
| 30 | Enabled persistence tests; endpoint and generator consumers; Linux/Windows CI; pinned corpora and executed-count checks | Local verification below; CI remains the cross-platform gate |
| 31 | Root build depends on checks; shared POM/signing/staging setup; missing validation publications and reasoning BOM entry; Dokka jars; API baselines; coverage and dependency reproducibility gates | Staging artifact checker, separate Maven consumer, API checks and reports |
| 32 | Explicit provider/lifecycle/performance contract, corrected existing-RDF4J example, dependency-derived provider version metadata | Compiled repository/consumer examples and provider suites |
| 33 | Four-worker, 64-queue default URL executor; exceptional overload; active I/O cancellation | Local slow server with 72 simultaneous submissions and cancellation |

**Verification status.** Local verification passed on Windows/JDK 21:

- Root `build`, API compatibility checks, conformance smoke tests, coverage reports, SHACL benchmark compilation, and all staging publication tasks completed successfully (414 tasks; `build/review/release-final4.log`). The final run used one worker and a 1 GiB Gradle heap after earlier attempts exhausted host memory.
- Test-report inventory: **3,218 cases, 270 skipped, zero failures**. This is the aggregate of retained task reports, including full conformance and smoke suites; it is not a count of distinct behavioral guarantees.
- Pinned RDF corpus: 1,050 cases per provider, zero failures; Jena skipped 53 and RDF4J skipped 206. The conformance harness now preserves each test document's base URI. Native SHACL includes 137 corpus cases. All three opt-in tests against the actual ONNX model passed.
- The Gradle TestKit consumer compiled and executed renamed types across packages, checked unchanged-input behavior, regenerated context edits, and removed obsolete generated files. Its validation exposed and fixed the plugin's missing Kotlin Gradle runtime dependency.
- All **26 staged publications** passed metadata and nonempty source/documentation artifact checks. A separate Maven consumer compiled and ran successfully against the staged BOM and both providers (`build/review/staged-consumer-final.log`).
- **38 dependency lockfiles** were generated. Dependency checksum verification was enabled during the successful build. `git diff --check` passed.
- `buildHealth` completed with non-fatal dependency-declaration recommendations. These advisory reports remain available at `build/reports/dependency-analysis/build-health-report.txt`; they are not a dependency vulnerability audit or a claim that every cleanup suggestion was applied.

Build logs and generated coverage/conformance reports remain under `build/`; release artifacts are staged locally in `build/release-repository/`.

**Release limits.** The changes do not establish universal RDF/SHACL conformance, binary compatibility with previous releases, a hard per-request JVM memory quota, or production throughput. Remote multi-request operations remain nontransactional; some provider/parser capabilities depend on upstream engines. The exact similarity algorithm can still have quadratic work for dense results. Signing is configured but requires release-owner credentials, and no public upload is part of this change. Linux/Windows CI execution must be checked before publishing.
