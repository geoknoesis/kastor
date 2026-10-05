# Release-candidate review

## Current status ? 4 October 2026

The 3 October whole-repository audit revised the assessment to **9.0/10**, identifying six reproduced correctness defects: RDF4J blank-node identity collisions, lost SHACL conformance decisions during report merging, inaccessible Jena graphs after caught partial updates, CLI default/named-graph comparison collisions, floating-point bound comparisons, and invalid date timezone acceptance.

All six have now been fixed in the working tree based on `8e4d9386c15ee0a7ad0480113ad32dc89f004168`. Five permanent regression classes cover the six defects and additional boundaries; the generated DSL test now checks numeric promotion and rejection of the next representable value. Checks for Jena, RDF4J, CLI, ontology quality, runtime and processor passed: 1,530 executed tests, three allowlisted skips, and zero failures, including API compatibility and coverage gates. Evidence: `build/six-fixes-verified.log` and `build/six-fixes-results.json`.

Repository-wide `check` subsequently passed on Windows: 3,644 executed cases, ten allowlisted skips and zero failures across the invoked test tasks. This includes all 166 W3C SHACL cases. The 55 Python tooling tests and documentation-version, build-pin and README-import checks also passed. Evidence: `build/remaining-issues-check.log` and `build/remaining-issues-broad-results.json`. This was an incremental working-tree build, not a clean cross-platform release build.

The subsequent full RDF corpus passed 1,913 executed cases with 204 allowlisted exclusions. Opt-in embedding and semantic checks passed all 166 cases with zero skips, including real-model inference, bundled semantic benchmarks and 200 native model/default-enricher lifecycle cycles after three warmups. The native soak passed its thread/RSS growth gates; it is bounded Windows evidence, not a production endurance guarantee. Required-suite gates confirmed that native and semantic cases actually ran. Evidence: `build/remaining-issues-native.log` and `build/remaining-issues-native-results.json`. A clean Linux build, fresh performance comparisons and remote release verification were not run for these fixes.

Live OpenAI verification subsequently passed with the existing GPT-4o mini configuration and a synthetic ontology finding. `OpenAiExplanationIntegrationTest` executed (zero skips) in 6.53 seconds and verified parsed explanations, finding-reference association, substantive summary, provider metadata and Markdown output. The complete LLM module reported 47 cases, 0 skips and zero failures; the required-suite gate passed. Evidence: `build/live-openai-test.log` and `build/live-openai-results.json`. This verifies one live integration scenario, not sustained API availability or model quality across arbitrary inputs.

The fixes have not received a new numerical score or independent signoff. The September results below describe earlier candidates, not verification of this working tree. Production version, repository and signing identity remain undecided; publication and remote release verification remain outstanding.

## Historical assessment ? 13 September 2026

**Updated public-release readiness: 9.4/10 (94.15/100 weighted), up from 93.15/100.** The increase reflects completed Linux verification, a fresh Windows build of the current candidate, native lifecycle evidence on both platforms, and confirmation that this is the first public release. The score is an engineering judgment, not a defect-free percentage. Production release configuration and independent signoff still prevent a 9.5+ endorsement.

This review covers the uncommitted candidate based on `6c5c50d161ff6bf5da319a24d7b936af5427a7e5`. The initial 940-file Linux snapshot manifest is `6e5feb3dee41e81cfef4d03b309abf7917d8573c7cd0b8dd2f1049b546e26f7b`. A subsequent stalled-download test setup fix is the only source difference in the final Windows/Linux snapshots, whose manifest hash is `8de41c0c94a7f642c39aad406a3fc673b5836ee1f1ebe68cd6157134e8ff5c34`. Linux completed its clean broad build before that test-only change, then passed the corrected embedding/semantic checks. Windows completed its clean broad build with the fix included. Workspace JVM/build inputs match the final snapshot; later documentation changes record these outcomes. No public publication or remote CI run has been performed.

## Highest-impact follow-up — 13 September

- Linux recovered without a restart. All seven verification phases passed: clean checks/build/staging, metadata, overall/RDF test gates, independent library consumer, published-plugin consumer and explicit configuration-cache reuse. All 390 build tasks executed. The pinned RDF revision and all 336 SHACL fixture files match the Windows inputs.
- Each clean platform build reported 3,404 tests: 3,131 executed, 273 skipped, zero failures. Each RDF conformance run reported 2,106 tests: 1,849 executed, 257 excluded, zero failures. Exclusions remain exclusions.
- Linux additionally passed all 15 embedding and 72 semantic-quality tests with zero skips, including 200 measured native/default-enricher lifecycle cycles after three warmups. The soak took about 200 seconds, with final sampled RSS about 415 MiB and 11 live threads. Thread/RSS growth gates passed; cross-platform RSS levels and timings are not directly comparable on this loaded host.
- The first native run exposed a test setup race: a 500 ms deadline could expire before the local HTTP request reached its stalled response. The test now warms the actual client/connection before the short request, uses a five-second download budget and holds the response longer than the budget. Timeout, file cleanup and watchdog cleanup assertions remain. The failed report is retained alongside the successful rerun. Production download behavior is unchanged.
- The maintainer confirmed that Kastor has never been published externally. A previous-public-binary comparison is not applicable to this first release. Preserve the first release artifacts as the future baseline.

Evidence: `build/review/platform-verification.json`, `build/review/linux-current-evidence/`, and `build/review/release-20260913/build/review/`. Fresh staging is local and unsigned; the earlier ephemeral signing exercise remains mechanical evidence only.

## Earlier fixes and scoring basis

The native CI gate previously checked only an aggregate test count. Additional ordinary tests could therefore hide missing or skipped native tests. It now requires the native inference and soak suites explicitly, rejects empty/skipped required suites, and cross-checks report summary counts against actual test cases. Both release and native workflows run six regression tests for the gate itself.

The latest optimization prunes similarity-tree subtrees containing only already processed endpoints and reuses a traversal stack per iterator. The embedding check, unchanged API baseline and all 15 tests passed with zero skips, including an exhaustive seeded oracle, dense work-budget regression, interleaved iterators, real-model inference and 200 measured native lifecycle cycles. Four fresh two-fork JMH comparisons passed the 15% latency/allocation gate. At 1,000 vectors, dense search was about 43% faster and sparse allocation fell about 96%; these remain noisy local measurements.

Gradle now includes `KASTOR_OOPS_BENCHMARK` in test inputs. The bundled semantic calibration and both OOPS benchmark tests passed. A three-phase check confirmed that toggling the flag invalidates the test result and an unchanged subsequent run is up-to-date. The native workflow now explicitly requires these semantic suites as well as native inference and soak suites. Current evidence is in `build/review/pruning-verification.json` and `build/review/oops-verification.json`; older `rescore-evidence.json` records the preceding tooling-only iteration.

The earlier Ubuntu timeout was resolved when the environment became responsive on 13 September; current Linux results are recorded above. No WSL restart, production publication or independent signoff was performed.

The subsequent release-tooling follow-up hardened the performance gate: both reports must contain GC allocation measurements and all compared JMH run settings, including JMH/VM versions and warmup/measurement batch sizes. Eight command-line regression tests cover valid reports, incomplete evidence, mismatched settings, latency/allocation regressions, throughput direction, missing/duplicate cases and invalid metrics. Release and performance workflows run these tests. That earlier tooling-only iteration left the score at 93.15; the platform follow-up above supplies the new scoring evidence.

| Dimension | Weight | Historical 82-point review | Now | Remaining deduction |
|---|---:|---:|---:|---|
| Correctness and data integrity | 25% | 85 | 95 | Supported conformance scope is explicit; excluded capabilities are not verified |
| Architecture and API consistency | 15% | 80 | 90 | Large implementation classes and provider-specific behavior remain |
| Performance and scalability | 15% | 72 | 92 | Measurements are local/noisy; exact similarity remains worst-case quadratic |
| Reliability and resource management | 15% | 80 | 96 | Cancellation is cooperative; bounded Windows/Linux soaks are not production endurance evidence |
| Tests and verification | 15% | 85 | 97 | Some opt-in remote integrations and independent CI remain unverified |
| Build and release engineering | 10% | 85 | 94 | Production version/identity/target and remote release jobs remain open |
| Documentation and usability | 5% | 85 | 95 | Some compiler warnings and release-specific migration details remain |
| **Weighted total** | **100%** | **81.55** | **94.15** | **+12.60 from the historical baseline; +1.00 this iteration** |

The latest one-point increase uses the original weights: reliability 94→96 (+0.30), tests 95→97 (+0.30), and build/release engineering 90→94 (+0.40). The measured similarity optimization remains scored at 92; no new benchmark claim is made in this iteration. Local test signatures do not establish a production signing identity.

## Verified changes

Staged-publication verification rejects blank required metadata, missing primary library JARs, empty class/source/HTML entries and corrupt ZIP contents. Seven CLI regression tests pass and run in release CI. All 26 freshly staged publications passed inspection on both Linux and Windows, followed by successful independent consumers. Staging inspection does not replace production signature verification.

- Validation preparation, compilation-cache waiting, shape traversal and execution share cancellation/deadline checks. Cyclic/deep shapes and exhausted work budgets fail explicitly.
- Isomorphism consistency checks and SHACL target discovery use indexes; duplicate focus evaluation and large intermediate allocations were reduced. Exact similarity has explicit work, result and deadline limits.
- Generator inputs are managed, relative-path-sensitive properties. Execution avoids `Project` access; registration is lazy. Tests cover edited inputs, stale outputs, renamed cross-package types and configuration-cache reuse.
- Model initialization uses per-cache coordination and cancellable file-lock/network waits. Native model/default-enricher ownership is explicit; provenance hashing uses a 64 KiB buffer. Owned Jena conversions are closed.
- Provider format aliases and graph/dataset round trips are covered. Jena 6.2.0 resolves the persistence incompatibility with Thrift 0.24.0.
- Dependency versions are aligned and locked; verification checksums, signed staging, independent consumers and CI gates are in place. Artifact collection excludes stale versions and filename collisions. Test scratch is cleaned after worker shutdown.

## Previous targeted evidence (12 September)

| Check | Result |
|---|---|
| Embedding check and API | Passed: 15 tests, zero skips/failures; API compatibility check passed without changing the baseline |
| Native lifecycle | Passed: three warmups and 200 measured cycles, about 180 seconds; final sampled RSS about 108 MiB, not attributed to the traversal optimization |
| Similarity JMH | Four fresh before/after cases passed; two forks, identical settings and retained jar hashes |
| Semantic integration and test identity | All 72 ontology-quality tests passed, zero skips/failures, including calibration and both bundled OOPS benchmarks; flag change reran tests and unchanged flag reused results |
| Workspace artifact collection | Refreshed 111 current-version JARs successfully; 183 build tasks, six executed. Public HTML documentation reused from prior verified staging because the public API documentation is unchanged. Earlier signed staging was preserved and does not contain this optimization. |

## Earlier broad evidence (before the latest optimization)

| Check | Result |
|---|---|
| Fresh Windows source-only build | Passed: 390 build tasks executed; 3,402 reported cases, 273 skipped, zero failures; staging, both fresh consumers and cache reuse passed |
| Native Windows embedding suite | 13 executed tests passed, including four real-model tests and 200 measured alternating lifecycle cycles after three warmups |
| Cross-provider regressions | 170 passed, including 300 repository lifecycle cycles and 120 seeded independent isomorphism-oracle cases |
| Workspace artifact collection | Refreshed successfully: 111 current-version JARs, 27,327,535 bytes, separated by module; documentation reused from verified final signed staging |
| Signed staging | 123 artifact signatures verified; tampered data rejected; 26 publication metadata/source/documentation checks passed |
| Library consumers | Preserved already-compiled class passed with unchanged hash; fresh Kotlin 2.4.20 compilation/execution passed |
| Published generator plugin | Marker resolution, generated-domain execution, cooperating KSP rounds and configuration-cache reuse passed |
| Performance | All 12 final cases passed the 15% latency/allocation gate; 1,000-node isomorphism allocation fell from about 12.8 MB/op to 4.8 MB/op |
| Dependency audit | 570 pinned Maven coordinates checked; zero OSV advisory IDs returned |
| Secret scan | 934 working files and 2,481 historical blobs checked; zero matches for the five configured patterns |
| Compatibility | 24 local ABI dumps compared; nine conservative flags reviewed against JVM bytecode; no supported member removal found in those flags |
| Documentation and whitespace | Final signed build reported zero unresolved documentation links; whitespace check passed |

Detailed logs and JSON evidence are retained in `build/review/`; performance data is retained in [the performance report](../../../docs/kastor/performance/README.md). Test totals include multiple task suites and are not unique coverage guarantees. Skipped tests remain exclusions; the individual inventory is `build/review/clean-windows-skips.json`, alongside the [provider capability contract](../../../docs/kastor/features/provider-capabilities.md). OSV and pattern scans have their stated detection scope.

## Remaining release gates and limitations

1. **Confirm the production publication target, signing identity and first-release version.** Local signatures use an ephemeral test key. The repository's 0.2.1 tag is not evidence of public publication. Choose a version and tag the exact release candidate; 0.3.0 RC remains a proposal, not a maintainer decision.
2. **Obtain independent signoff and run remote jobs against the exact committed candidate.** Local Windows/Linux verification is now available, but this self-review does not substitute for a second reviewer or successful remote release jobs.

Closed applicability question: the maintainer confirmed on 13 September that there is no previous public binary. Closed environment gap: local Linux clean/native verification now passes. Neither item remains an outstanding release prerequisite.

Cooperative cancellation cannot preempt arbitrary blocking provider calls. Exact high-dimensional similarity remains worst-case quadratic, particularly for dense output. Local benchmark confidence intervals are wide and host load changed between runs; the measurements do not establish production SLOs. The bounded native soak is not a production endurance test. Some legacy deprecation/cast warnings remain, although processor explicit-API and unresolved documentation-link warnings were addressed.

The complete implementation mapping is in [KASTOR_RELEASE_TASKS.md](KASTOR_RELEASE_TASKS.md), with [compatibility evidence](compatibility-review.md) and [release acceptance conditions](release-acceptance.md).
