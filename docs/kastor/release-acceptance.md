# Release acceptance and supported limits

The acceptance ledger is [KASTOR_RELEASE_TASKS.md](../../KASTOR_RELEASE_TASKS.md).
An engineering score is a review judgment, not a guarantee or an automated release switch.

## Version and compatibility

Build and test with JDK 21, Gradle 9.5.1, Kotlin 2.4.20 and KSP 2.3.12. Other
combinations require their own consumer verification. Jena 6.2.0 is paired with
Thrift 0.24.0: the older Jena 6.1.0 TDB2 adapter fails persistence writes with
Thrift 0.24.0. The Kotlin upgrade also
changes the minimum supported Kotlin compiler for generated/compiled consumers.
The repository has a 0.2.1 tag, but the maintainer confirms there has been no
public publication. Choose the first-release version and tag its exact source;
0.3.0 RC remains a proposal subject to the maintainer's release-channel decision.

The maintainer confirmed on 13 September 2026 that Kastor has never been
published for external users. A previous-public-binary comparison is not
applicable to this first release. Preserve its artifact checksums for future
compatibility checks. For subsequent releases, identify the previous public artifact and preserve its checksum. Build
its source revision with its original toolchain to obtain the baseline ABI dumps;
never replace that baseline with candidate dumps. Run
`python scripts/compare-api-dumps.py BASELINE CANDIDATE` and review every reported
removal, signature change and abstract requirement. Run existing binary consumers
against the candidate as well: dump comparison does not verify behavior or all
Kotlin source compatibility. Baseline updates in a PR require an explicit
compatibility/migration explanation. Public removals and newly required methods
must wait for the next breaking-change version; deprecate first where practical.

## Deadlines and ownership

Native SHACL uses one monotonic budget across preparation, digesting, compilation,
index construction, target traversal and backend queries. Cancellation is
cooperative. An arbitrary provider's blocking `getTriples()` cannot be preempted
by a caller-side budget; use process isolation when a hard wall-clock or heap
ceiling is required. Cyclic SHACL lists/paths/property definitions fail explicitly;
path and nested property-shape depth is limited to 128.

Blank-node isomorphism matching defaults to 1,000,000 candidate states, 50,000,000
cooperative work checks and 30 seconds. An overload accepts explicit limits.
Exhaustion/interruption throws `IllegalStateException`; it never reports a false
non-match. Nested triple terms are limited to 128 levels. These limits cover
Kastor's processing, not arbitrary blocking provider snapshot calls.

Model initialization shares only in-flight work for the same canonical cache
directory. Different directories have separate locks. Lock wait, verification,
network read and writes consume the same deadline (10 minutes by default, with
an explicit `Duration` overload). Cancellation does not cancel another caller's
shared work. Completed files are reverified on later calls; cache hits do not
silently bypass asset integrity. Returned cache paths are canonical absolute paths.

Exact similarity search defaults to 50,000,000 distance evaluations, 1,000,000
result pairs and 30 seconds, including tree construction and waiting for its
cache. `SimilaritySearchLimits` can be supplied to the search or semantic
enricher. Exceeding a limit throws instead of returning an apparently complete
partial enrichment. Dense output and high-dimensional exact search can still be
quadratic; limits bound admitted work, not algorithmic complexity. Callers that
only want a prefix can explicitly consume the lazy sequence with `take(n)`.

Close repositories and native models with `use`. `SemanticEnricher.default()`
owns a native model and must also be closed with `use`; an enricher constructed
with a caller-supplied model does not close that model. Model provenance hashing
uses a 64 KiB buffer rather than allocating the entire model file. Scoped query/parser callbacks
own their live iterators; values intended to outlive a scope must be materialized.
The lightweight memory provider is for graph operations and does not implement
SPARQL. Provider-specific conformance exclusions remain exclusions, not passes.

## Test scratch files

Tests place JVM temporary files under each module's `build/test-tmp/<task>`
location (module builds live under the root `build/modules/` tree). After a
successful test worker exits, Gradle removes that task's scratch directory.
Failed-run scratch is retained until the next execution or `clean`. This permits
Windows TDB memory maps to close before cleanup and avoids filling the system
temporary volume with persistent test databases.

## Evidence required for a release

1. Clean Linux and Windows jobs on the same source revision: checks, conformance
   smoke, executed-test count, ABI checks, staging and both independent consumers.
2. Published plugin marker resolution, generated-code execution, cooperating KSP
   rounds, input edits/removals and explicit configuration-cache reuse.
3. Signed artifact verification using the intended release key and repository.
   `scripts/verify-local-signing.py` validates local mechanics with a **test key**;
   those artifacts must never be promoted as a production-signed release.
4. `scripts/audit-dependencies.py` and a redacted history/working-tree credential
   scan. OSV package matches require reachability review; no findings is not proof
   of absence. Keep checksums and lockfiles after dependency changes.
5. JMH latency and allocation reports on representative scales. Run the candidate
   and baseline on the same idle machine/JDK/options, then use
   `scripts/check-performance.py`. Its default regression budget is 15%; tune it
   from repeated measurements, not a single noisy run. Dense similarity queries
   can still require quadratic work/output; do not advertise universal linearity.
6. Native lifecycle soak (`KASTOR_RUN_SOAK_TESTS=1`, optionally
   `KASTOR_SOAK_CYCLES=200`) and provider lifecycle tests. The native CSV records
   elapsed time, heap, threads and periodic resident memory on Windows/Linux
   (`rss_bytes=-1` means no sample). It alternates direct model and default-enricher
   lifecycles, checks thread growth and a conservative 256 MiB RSS-growth budget.
   Heap is not native RSS. Use a stable runner
   and a representative sustained workload before making production memory claims.
7. An independent reviewer signs off on the exact candidate and evidence. A fresh
   self-review is useful but does not substitute for independent approval.

Keep logs, workload parameters, source revision, environment and exclusions with
the release. A failed or missing gate must remain visible rather than be converted
to a passing score.

Before consumer testing, `scripts/check-staged-publications.py` requires nonblank
publication coordinates and descriptive/license/SCM/developer metadata. Kastor's
JVM library publications must contain a main JAR with nonempty class entries,
Kotlin sources and HTML documentation; archives are checked for CRC corruption.
POM-only BOM and plugin-marker publications are exempt from JAR requirements.
The gate's seven CLI regression tests run in release CI. These structural checks
do not replace signature verification or execution of independent consumers.
