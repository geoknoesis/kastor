> Historical reassessment before the subsequent release-readiness work. See [KASTOR_RELEASE_TASKS.md](KASTOR_RELEASE_TASKS.md) for current changes, evidence and outstanding release gates.

**Kastor post-fix review — 12 September 2026**

**Public-release readiness: 82/100, up 34 points from 48/100.** The code now supports a scoped public beta/release candidate with its documented limitations. I would still require clean cross-platform release verification before endorsing a stable release.

This is a targeted reassessment of the uncommitted working tree based on `6c5c50d161ff6bf5da319a24d7b936af5427a7e5`, not a new exhaustive audit. I inspected residual high-risk implementation paths and build/consumer configuration, rechecked test-report counts and staged artifacts, and reviewed the successful remediation build logs. I did not rerun the entire test suite or perform production load profiling in this reassessment. The score is an engineering judgment using the original weights, not a percentage of defect-free code.

| Dimension | Weight | Before | Now | Gain | Reason |
|---|---:|---:|---:|---:|---|
| Correctness and data integrity | 25% | 40 | 85 | +45 | Destructive inference/import behavior, false graph equality, false validation, and generator defects now have corrections and regression coverage |
| Architecture and API consistency | 15% | 60 | 80 | +20 | Pattern queries, scoped resources, transaction rules, and capability contracts are clearer; large classes and backend differences remain |
| Performance and scalability | 15% | 40 | 72 | +32 | Indexes, backend pushdown, bounded caches/queues, lazy query setup, metric-tree pruning, and iterative traversal address structural costs; workload measurements remain absent |
| Reliability and resource management | 15% | 40 | 80 | +40 | Rollback, close/cancel ownership, bounded output, and native cleanup improved; limits are still cooperative and some initialization is serialized |
| Tests and verification | 15% | 65 | 85 | +20 | Persistence, HTTP, cancellation, generator consumer, full RDF corpus, and real-model tests improve coverage substantially; skipped capabilities and integration gaps remain |
| Build and release engineering | 10% | 40 | 85 | +45 | Root build checks, API baselines, coverage, locks/checksums, real documentation jars, staging, and a Maven consumer now work locally |
| Documentation and usability | 5% | 65 | 85 | +20 | Explicit capability/lifecycle/migration guidance replaces overbroad claims; documentation and dependency cleanup remain |
| **Weighted total** | **100%** | **48** | **82** | **+34** | **Current unrounded total: 81.55** |

The biggest weighted gains are correctness (+11.25 points), reliability (+6), performance (+4.8), and release engineering (+4.5). Passing more tests alone is not the reason for the increase: regressions now exercise previously broken behavior.

**Evidence supporting the increase**

- Rechecking the retained XML reports returned **3,218 reported cases, 270 skipped, zero failures**, or 2,948 executed cases. This includes multiple task suites and is not a count of unique guarantees. The original review's 835-case sample covered a narrower scope, so comparing those totals as a test-coverage percentage would be misleading.
- The pinned RDF corpus reports 1,050 cases per provider: Jena skipped 53; RDF4J skipped 206; neither reported failures. The 259 corpus skips remain exclusions, not passes.
- The generator TestKit consumer compiled and ran cross-package renamed types, verified unchanged inputs, edited context contents, and checked stale generated-file deletion. All three opt-in real ONNX model tests passed during remediation.
- The recorded root build, API checks, benchmark compilation, coverage generation, and staging run passed: `build/review/release-final4.log`, 414 tasks. Benchmark compilation is not a throughput benchmark. Current API baselines do not prove compatibility with earlier released artifacts.
- Rechecking staged publications validated metadata and nonempty Kotlin source/HTML documentation jars for **26 publications**. The independent BOM/provider consumer also passed: `build/review/staged-consumer-final.log`.
- Dependency locking produced 38 lockfiles. Checksum verification was active during the successful build. Dependency health passed with advisory recommendations: `build/review/build-health-final.log`. Neither checksum verification nor dependency health constitutes a vulnerability audit.

**Remaining findings and score deductions**

1. **P1 release gate — clean cross-platform and actual publication-path evidence is still missing.** Linux/Windows CI and the manual [release-readiness workflow](../../../.github/workflows/release-readiness.yml) are configured, but this session demonstrates local Windows execution. Signing is configured without an exercised signed release. Before stable publication, run a clean checkout on both platforms, verify signed artifacts and the intended repository path, and record the exact tested revision. These are verification gaps, not newly reproduced data-loss bugs.

2. **P2 verification gap — the published generator plugin is not consumed end to end.** [GenerationConsumerTest.kt](../../../kastor-gen/gradle-plugin/src/test/kotlin/com/geoknoesis/kastor/gen/gradle/GenerationConsumerTest.kt#L70) uses `withPluginClasspath()`. The independent [release-smoke build](../../../release-smoke/build.gradle.kts) consumes library artifacts and the BOM, not the Kastor plugin through `pluginManagement`. Add a consumer that resolves the staged plugin marker and transitive dependencies, then generates and compiles code. Also extend generator coverage to a cooperating KSP processor/multiple rounds and supported Gradle/Kotlin version combinations.

3. **P2 resource limitation — validation deadlines do not interrupt all preparation.** [NativeShaclValidator.kt](../../../rdf/shacl/validation/src/main/kotlin/com/geoknoesis/kastor/rdf/shacl/providers/NativeShaclValidator.kt#L114) establishes a deadline, then prepares/digests/compiles shapes and constructs indexes before the first context deadline check. A timeout can therefore be detected after expensive preparation rather than stop it promptly. HermiT's [input memory check](../../../rdf/reasoning/hermit/src/main/kotlin/com/geoknoesis/kastor/rdf/hermit/HermitRdfReasoner.kt#L34) is an estimate, not a heap limit. The release contract correctly narrows these guarantees. Add preparation-phase cancellation and adversarial workload tests; use process isolation where hard limits are required. No new timeout-overrun reproduction was run here.

4. **P2 scalability — improved algorithms still need measured envelopes.** [Isomorphism matching](../../../rdf/core/src/main/kotlin/com/geoknoesis/kastor/rdf/GraphIsomorphism.kt) bounds search states, but each consistency check can scan many triples sharing a predicate; the state budget is not a total-work or wall-clock bound. [SimilarityIndex](../../../tools/onto-quality/embed/src/main/kotlin/com/geoknoesis/kastor/ontoquality/embed/SimilarityIndex.kt) can still approach quadratic work for dense/high-dimensional searches. [Domain property helpers](../../../kastor-gen/runtime/src/main/kotlin/com/geoknoesis/kastor/gen/runtime/KastorGraphOps.kt) push filters down but still materialize matching lists per access. Measure 1k/10k/100k workloads, allocation/peak heap, first-row latency, backend calls, and cancellation before publishing scale claims.

5. **P2 build portability — generator configuration remains coupled to `Project` and late configuration.** [OntoMapperPlugin.kt](../../../kastor-gen/gradle-plugin/src/main/kotlin/com/geoknoesis/kastor/gen/gradle/OntoMapperPlugin.kt#L28) registers work in `afterEvaluate`; [OntologyGenerationTask.kt](../../../kastor-gen/gradle-plugin/src/main/kotlin/com/geoknoesis/kastor/gen/gradle/tasks/OntologyGenerationTask.kt#L361) resolves files through `project.projectDir`. Input-content tracking is fixed, but configuration-cache compatibility is not established by the passing consumer. Move execution inputs to managed properties and add an explicit configuration-cache test before claiming support.

6. **P2 initialization contention — model setup is globally serialized.** [ModelDownloader.kt](../../../tools/onto-quality/embed/src/main/kotlin/com/geoknoesis/kastor/ontoquality/embed/ModelDownloader.kt) holds a synchronized method and blocking file lock across cache validation/download. Separate cache roots also serialize, and every construction rehashes existing model assets. This is safer than the original races but can delay concurrent startup. Consider per-cache coordination, cancellable lock acquisition, and measured validation reuse that preserves integrity. The asset-size cap alone does not bound lock wait time.

7. **P2 maintainability and coverage — cleanup and exclusions remain.** Dependency-health advice is non-fatal, not absent. Explicit-API and documentation-link warnings remain in build logs. Large implementation classes, repeated adapters, and public legacy helper types still increase maintenance cost. Review declaration advice individually, resolve warnings, and maintain an explicit supported-capability/skip inventory. Remote-service behavior, long-running retention, old-version compatibility, and production throughput are not established by these local results.

These findings qualify the earlier statement that fixes covered all 33 review findings: the principal defects were addressed, while broad improvement recommendations and release validation are not universally complete. No new P0 was identified in this targeted pass; that is not proof that none exists.

**Release decision and next milestones**

- **Scoped public beta:** reasonable after the clean CI gate is green, with supported providers/features and known exclusions stated clearly.
- **Stable release:** defer until cross-platform clean builds, the staged plugin consumer, signed publication verification, and an API/versioning decision are complete.
- **Toward 90/100:** add representative performance/retention measurements, close the preparation-timeout and generator portability gaps, and reduce dependency/API/documentation warnings. Re-score from evidence after that work; completing a checklist does not automatically earn a specific score.

The original assessment is preserved in [KASTOR_CODE_REVIEW.md](KASTOR_CODE_REVIEW.md); the implementation mapping is in [KASTOR_FIX_STATUS.md](KASTOR_FIX_STATUS.md).
