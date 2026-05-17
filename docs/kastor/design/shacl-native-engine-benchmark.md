# SHACL native engine: cross-implementation performance benchmarks

| | |
|---|---|
| **Status** | Design proposal (contributor-normative once adopted). Implementation may trail this document; treat gaps as backlog. |
| **Audience** | Kastor contributors maintaining `rdf/shacl/validation`, bridge modules, and CI |
| **Depends on** | [SHACL validation architecture](shacl-validation-architecture.md) — especially native performance engineering and the benchmark thresholds described there |
| **Modules** | `rdf/shacl/validation`, future `shacl-validation-jena` / `shacl-validation-rdf4j` (or equivalent bridge artifacts), optional `benchmarks/shacl` aggregate; optional Python env + scripts for [PySHACL](https://github.com/RDFLib/pySHACL); optional TopBraid-capable runner (see [Section 2.2](#22-non-jvm-and-vendor-baselines-pyshacl-topbraid)) |

This document specifies **how** to compare the **native** Kastor SHACL engine against **other** serious SHACL implementations, including:

- **In-process on the JVM** via **`ShaclValidator`** (native Kastor, Apache Jena SHACL, Eclipse RDF4J SHACL, and any future `ShaclValidatorProvider`).
- **Outside that API** but on **comparable workloads**: **[PySHACL](https://github.com/RDFLib/pySHACL)** (Python / RDFLib) and **TopBraid SHACL** (TopQuadrant’s SHACL stack as used in [TopBraid](https://www.topquadrant.com/products/topbraid-shacl/) products — JVM, enterprise deployments).

It complements the architecture doc: that document states **intent and gating rules**; this one describes **workloads, harness shape, fairness constraints, and operational CI practice**.

User-facing product claims about speed must still follow the policy in the architecture document (published methodology and results alongside any leadership claim).

---

## 1. Objectives

1. **Regression detection:** Catch native-engine slowdowns on a fixed corpus before they reach `main`, using relative thresholds consistent with the architecture doc.
2. **Competitive baselines:** Measure native against **other widely used validators** on **meaningful** enterprise-style graphs: JVM peers (Jena, RDF4J), **PySHACL** (Python ecosystem), and optionally **TopBraid SHACL** where a licensed or evaluation environment is available — not only toy fixtures.
3. **Actionable profiles:** Separate results by **shape profile** (Core-only vs SPARQL constraints vs large property-shape fan-out) so optimizations and regressions can be tied to features.
4. **Honest apples-to-apples:** Attribute **import**, **RDF parsing**, **RdfGraph ↔ backend model conversion**, **compile/plan**, and **execute** costs so comparisons are interpretable.

Non-goals for this benchmark program:

- Replacing W3C conformance tests (performance suites **must not** gate spec correctness).
- Proving optimality on every possible RDF graph (focus on **repeatable**, **documented** scenarios).
- Benchmarking remote SPARQL endpoints or on-disk TDB/NativeStore unless explicitly added as a later tier (initial design is **in-memory** validation latency and throughput).

---

## 2. Implementations under test

### 2.1 JVM engines via `ShaclValidator`

| Label | Typical provider id | Role in benchmark |
|-------|---------------------|-------------------|
| **Native** | `kastor` (or documented default for the native provider) | System under test |
| **Jena** | `jena` | Primary open-source JVM baseline |
| **RDF4J** | `rdf4j` | Secondary open-source JVM baseline |

**Rules**

- JVM baselines that ship as Kastor providers participate through **`ShaclValidator`** with an explicit **`ValidationConfig`** (`providerId` or non-`AUTO` preference) so resolution heuristics do not taint results.
- If a bridge module is not yet published, the harness may use a **temporary** adapter in the benchmark module only, with a tracked issue to replace it with the real provider SPI. Results must label such runs **provisional**.
- Record **`EngineMetadata`** / capability flags per run for reproducibility (SHACL profile, RDF 1.2 triple-term support, SPARQL extension support).

### 2.2 Non-JVM and vendor baselines (PySHACL, TopBraid)

These engines **do not** implement `ShaclValidator`. They are still first-class **comparison targets** using the **same workload descriptors** (Section 3) and a **thin harness** that:

1. Invokes the engine in its **native** embedding (Python process, TopBraid library or supported CLI/HTTP entrypoint).
2. Emits the same **CSV/JSON timing row schema** as the JVM harness so results tables can be merged.
3. Records **version pins** (`pyshacl` / `rdflib` / Python; TopBraid or product build identifier).

| Label | Integration style | CI default | Notes |
|-------|-------------------|------------|--------|
| **PySHACL** | **Subprocess** (recommended): Gradle/JMH spawns `python -m ...` after writing **shared** Turtle/N-Triples files; or **long-lived** Python sidecar with a tiny JSON-RPC/HTTP protocol to avoid per-iteration interpreter startup | **Optional** job (`ubuntu-latest` + pinned Python): good for regression **ratios** vs native on the same runner | Startup cost is real: report **cold** (new process) vs **warm** (reuse interpreter) if subprocess is used. Pin **`requirements.txt`** or **`uv.lock`**. |
| **TopBraid SHACL** | **In-process Java** only where the project has a supported API on the classpath, or **external** tool invocation documented by TopQuadrant | **Manual / self-hosted** unless the project obtains a redistributable automation path | **Licensing:** TopBraid components are **commercial**; public CI cannot assume availability. Publish methodology and numbers only from **allowed** environments. When automated, record exact **product edition** and **build**. |

**Semantic parity**

- SHACL 1.0 vs 1.1 vs 1.2 and **RDF 1.2** features differ across engines. Tier A (Section 3) must **filter** to tests all participants actually support; PySHACL and TopBraid columns may be **empty** for 1.2-only rows until support catches up — that is an **honest** result, not a harness bug.

**Fairness vs Kastor**

- Do not compare **native embedded** Kastor to **CLI round-trip** TopBraid if the goal is algorithmic speed: either split phases (Section 4) or label the comparison **“tooling path comparison”** separately from **“validator core”**.

---

## 3. Workload tiers

Benchmarks use **versioned** workload descriptors (small JSON or TOML files checked into the repo) listing: data paths, shapes paths, dataset layout (default graph vs named graphs), `ValidationProfile`, and expected **conformance outcome** (valid vs invalid — invalid fixtures are allowed when the scenario is about **speed of reporting**, not about zero violations).

### Tier A — Conformance-adjacent smoke

- **Source:** Curated subset of W3C SHACL 1.2 manifest tests (or Kastor’s pinned manifest subset), filtered to scenarios that all **participating** engines support under the same profile; see [Section 12](#12-maximum-fairness-and-w3c-test-suite-scope) for intersecting on capability vs chasing every listed vendor implementation. For PySHACL and TopBraid, this often means starting from a **SHACL 1.1–aligned** or **Core-only** slice unless their advertised capability includes the same 1.2 features Kastor tests.
- **Purpose:** Quick guard that performance work does not skip expensive but spec-relevant constructs.
- **Risk:** Manifest graphs are often small; use Tier B/C for scalability signal.

### Tier B — Synthetic stress

- **Generators:** Kotlin (or Java) generators produce deterministic graphs from a **seed**, parameterized by:
  - number of focus nodes / instances
  - property-shape count per node shape
  - path depth (`sh:path` sequences, `sh:inversePath`, alternatives)
  - use of `sh:sparql`, `sh:target`, `sh:targetClass`, `sh:targetSubjectsOf`, `sh:targetObjectsOf`
  - qualified-value constraint shapes (`sh:qualifiedValueShape` / min/max)
- **Purpose:** Control independent variables; expose algorithmic hot spots (path evaluation, join batching, SPARQL constraint planning).
- **Requirement:** At least **three fixed seeds** per scenario family; report **median** and **p95** across seeds (architecture doc).

### Tier C — Product-realistic corpora

- **Examples:** Ontology-quality shapes over bundled OWL/SKOS pitfall fixtures (**OOPS!-style corpus** pattern, as used in `OopsBenchmarkTest`), mid-size **DCAT**/catalog graphs, any large open ontology the project already uses for integration testing, and **[ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark)** (railway-domain RINF-derived subsets at three scales, with shape subsets — see Section 12.4).
- **Purpose:** Reflect allocations and shape libraries seen in tooling built on Kastor.
- **Note:** Semantic-tier tests (embeddings) are **out of scope** for validator comparison unless both engines share the same augmented graph.

---

## 4. Phases measured

For each workload, split timing where practical:

| Phase | Definition | Notes |
|-------|------------|--------|
| **Load / parse** | Bytes → `RdfGraph` (or backend model for bridges) | Often dominated by I/O in CLI apps; for micro-benchmarks use **pre-parsed** graphs in memory |
| **Convert** | `RdfGraph` → Jena `Model` / RDF4J `Model` | Critical for fairness; native may skip this entirely |
| **Interop / process** | JVM writes files + spawns PySHACL; JVM ↔ TopBraid bridge | **PySHACL:** subprocess creation, RDF parse inside Python, report serialization — split out when possible |
| **Compile / plan** | Shapes → internal compiled representation | Native: emphasize **amortization** via digest cache keys (see architecture doc); backends may differ |
| **Validate** | Full validation run producing `ValidationReport` | Primary cross-engine comparison when conversion is split out |
| **E2E** | Sum of what a typical embedder pays | Single number for “out of the box” comparisons |

Reporting should include at least **Validate** and **E2E**; **Convert** must appear for bridge engines whenever conversion is not negligible. For PySHACL subprocess mode, report **Interop / process** separately or publish **warm** timings with interpreter reuse so numbers are not dominated by OS process spawn.

---

## 5. Metrics and statistics

**Primary**

- Wall time per **E2E** validation (or per **Validate** phase if split).
- Throughput: **validations per second** for repeated runs on the **same** in-memory graphs (amortized compile vs cold compile reported separately).

**Secondary**

- **Allocated bytes** per validation (`-prof gc` / JFR) — highlights allocation regressions in path and SPARQL execution (JVM only).
- **Python:** optional `tracemalloc` peak or **wall-only** if allocation tooling is not standardized in CI.
- Optional: **peak heap** after a run (less stable in CI; treat as supplementary).

**Summaries**

- Report **median** and **p95** over measurements; architecture doc defines JMH iteration/fork discipline for authoritative runs.
- For synthetic workloads, aggregate across the **mandatory multiple seeds**; never report a single seed as definitive.

---

## 6. Harness technology

**Preferred: JMH**

- Dedicated benchmark sources (e.g. `src/jmh/java` or a small `benchmarks/shacl` composite module) invoked via Gradle.
- Use blackhole consumption for `ValidationReport` where necessary to prevent dead-code elimination without changing semantics.
- Pin **JVM flags** in the benchmark README (heap size, GC algorithm) and keep CI flags **identical** across JVM engines in the same job.
- **PySHACL:** pin Python and deps; prefer a **single** runner image that installs both JDK and Python so **native vs PySHACL** ratios are meaningful on identical metal.

**Optional: gated JUnit (smoke)**

- Follow the pattern in `OopsBenchmarkTest`: environment variable gate, warmup + measured loops, print-only or JSON artifact — suitable for **manual** or **quick CI smoke**, not sole regression enforcement.

### 6.1 Where benchmarks should live (repository layout)

| Piece | Recommended location | Rationale |
|-------|---------------------|-----------|
| **JMH / Gradle JVM harness** | **`benchmarks/shacl/jmh/`** (Gradle **`:benchmarks:shacl`** in `settings.gradle.kts`); run `./gradlew :benchmarks:shacl:jmh` | Keeps **long-running perf tests** and **JMH** out of **`rdf/shacl/validation`**.
| **Baselines & capability matrix** | **`benchmarks/shacl/jmh/baselines/`**, **`benchmarks/shacl/jmh/capability/`** (or similar next to the JMH module) | Version-controlled thresholds and per-test engine support; easy for CI to diff. |
| **Workload RDF (W3C subsets, synthetic seeds)** | **`benchmarks/shacl/jmh/src/jmh/resources/`** or **`benchmarks/shacl/jmh/workloads/`** | Large binary corpora: **do not** commit without license review; use download scripts or Git LFS as for `rdf/shacl/validation/test-data`. |
| **ERA-SHACL Docker + shell glue** | **`engines/kastor/`** inside an **[ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark) fork or PR** (Section 12.4) | Upstream expects each engine as a **sibling** of `engines/jena`; the Kastor monorepo only needs to **publish the CLI JAR** (next row). |
| **ERA CLI fat JAR / installDist** | **`benchmarks/shacl/era-cli`** (Gradle `:benchmarks:shacl-era-cli`) — `ShaclEraCli`, `./gradlew :benchmarks:shacl-era-cli:installDist`, optional [Dockerfile.sample](../../../benchmarks/shacl/era-cli/Dockerfile.sample) | Single artifact for **`engines/kastor`** in ERA; avoids bloating `:rdf:shacl-validation`.
| **optional PySHACL scripts** | **`benchmarks/shacl/jmh/scripts/pyshacl/`** + pinned `requirements.txt` | Keeps Python beside the JVM harness that spawns it. |

**Avoid** putting JMH sources only under **`rdf/shacl/validation/src/jmh`** unless the team explicitly wants perf code in the same module as production tests; the default is a **top-level `benchmarks/`** tree so CI can skip or isolate it cleanly.

---

## 7. Fairness checklist

- **Same logical RDF:** Identical triples for data and shapes roles; if not byte-identical, document **canonicalization** step and apply equally where possible.
- **Same validation profile:** Do not compare native **Core + SPARQL** against Jena on **Core-only** unless the scenario explicitly tests graceful skipping.
- **Same machine class:** Compare within a single CI job or self-hosted runner pool; treat absolute times across days as **non-comparable**.
- **Warmup:** Separate warmup from measurement; discard first iterations per fork per JMH rules.
- **Parallelism:** Default to **`ValidationConfig` defaults**; if native enables parallel validation, either enable equivalent backend parallelism where supported or document **serial-only** mode for a “single-threaded validator core” slice. **PySHACL** / **TopBraid:** use each tool’s default concurrency unless documenting an explicit **single-thread** mode for comparability.
- **Cross-language:** Compare **ratios on the same machine in one job**, not absolute seconds published from different workflow containers on different days.

---

## 8. Correctness guardrails

Performance tests never replace conformance tests. Additionally:

- Optional **parity job:** Same workload, same normalized report shape (where fidelity rules allow), compare **violation counts** and stable keys (focus node, severity, path). Intended to catch bridge mapping drift, not full graph equality of `sh:ValidationReport` RDF. For **PySHACL** / **TopBraid**, map to the same keys where their APIs expose them; otherwise record **conforms** / violation count only.
- Any workload that triggers **known** engine divergence must be **excluded** from cross-engine timing tables or marked **native-only**.

---

## 9. Baselines and regression policy

- Store baselines under a dedicated path (architecture doc suggests `benchmarks/shacl/jmh/baselines/` or co-located with the JMH module) with a **README** describing regeneration.
- **Gating:** Follow project policy: native median regression **>10%** or p95 **>25%** vs checked-in baseline fails the build **unless waived** with a tracked issue (see architecture doc). **PySHACL** and **TopBraid** baselines are **informational** unless the project explicitly promotes them to merge gates with a maintained runner.
- Prefer **ratio-based** gates when comparing native to a baseline engine in the **same CI job** (reduces noise from runner CPU differences).

---

## 10. CI integration

| Job | Frequency | Role |
|-----|-----------|------|
| **Smoke** | PR (optional / fast) | 1 fork, short iteration count; detects catastrophic regressions only |
| **Authoritative** | `main` nightly or release branch | Full JMH forks; updates baselines via reviewed PRs only |
| **Manual** | On demand | Contributor runs with `KASTOR_*` env flags or Gradle properties |

Publish **machine profile**, **`java -version`**, and for Python jobs **`python --version`** plus locked dependency versions alongside result tables in CI artifacts. For public claims, also publish the **workload descriptor** set and the **exact Git SHA**. TopBraid runs should publish **product identification** only as permitted by license.

---

## 11. Deliverables checklist

- [x] JMH Gradle entrypoint — `:benchmarks:shacl` (`./gradlew :benchmarks:shacl:jmh`); JVM flags documented in module `README.md`
- [x] Tier A/B/C workload layout — `benchmarks/shacl/jmh/workloads/` (Tier **A** JSON examples + B/C README stubs)
- [x] Baseline directory + README — `benchmarks/shacl/jmh/baselines/`
- [x] CI compiles benchmark modules on each PR (`ci.yml`); optional full JMH workflow [`.github/workflows/shacl-jmh.yml`](../../../.github/workflows/shacl-jmh.yml) (`workflow_dispatch` / weekly artifact)
- [x] **PySHACL** — `benchmarks/shacl/jmh/scripts/pyshacl/` (`requirements.txt`, `validate_era_style.py`, README)
- [x] **TopBraid** — self-hosted notes: `benchmarks/shacl/docs/topbraid-self-hosted.md`
- [x] **Capability matrix** starter — `benchmarks/shacl/jmh/capability/matrix.v1.json` (extend as engines are automated)
- [x] **ERA-compatible CLI** — `:benchmarks:shacl-era-cli` + `Dockerfile.sample`; upstream **`engines/kastor`** + `run_benchmark.sh` patch still optional (Section 12.4)
- [x] Architecture / feature cross-links — [SHACL validation feature](../features/shacl-validation.md) → benchmark design

---

## 12. Maximum fairness and W3C test-suite scope

### 12.1 How fair can benchmarking get?

Perfect cross-implementation fairness is **impossible** (different heaps, runtimes, APIs, and internal optimisations), but you can get **operationally fair** comparisons by controlling everything **outside** the validator core and being explicit about what is left **inside** the black box.

**Control outside the engine (must match):**

| Factor | Why it matters |
|--------|----------------|
| **Identical RDF inputs** | Same data graph and shapes graph triples (prefer one canonical serialization written once, then loaded by each runner). |
| **Identical dataset layout** | Default graph vs named graph IRIs for data/shapes; same `sh:shapesGraph` behaviour where tests use it. |
| **No accidental reasoning** | RDFS/OWL reasoning off unless the **specific** test case or documented scenario requires entailment. Label runs **“no reasoning”** vs **“with reasoning”** explicitly. |
| **Deterministic threading** | Either **single-threaded** for all engines or document each engine’s default parallelism and match where an API allows (see Section 7). |
| **Same stop condition** | **Full report** (all violations or full conform) vs **fail-fast** after first violation must be **the same**; otherwise faster engines may be “fast” only because they exit early. |
| **Warm hardware / one OS process layout** | Same machine, same CI job, minimal competing load; for JVM use identical `-Xmx`/GC where comparisons are JVM-vs-JVM. |
| **Phase separation** | Publish parse, convert, validate, and E2E separately so a “slower” engine is not penalised for Python startup or Jena model conversion alone. |

**Inside the engine (cannot fully normalise — disclose):**

- Internal join order, lazy vs eager evaluation, shape compile caches, native vs interpreted SPARQL.
- Different **supported SHACL/RDF versions** (e.g. 1.1 vs 1.2 Core vs triple terms).

**Document a “comparison mode”** in results (e.g. `CORE_STRICT_SINGLE_THREAD_FULL_REPORT`) so readers know what was held constant.

### 12.2 Can we compare every implementation “from the SHACL test suite”?

**What the W3C publishes.** The [SHACL test suite](https://github.com/w3c/data-shapes) is a **corpus of manifests and RDF files** (`sht:Validate` entries, data and shapes resources). It is **not** a registry of implementations. Vendors and open-source projects **independently** run those tests and may publish [implementation reports](https://www.w3.org/2017/shacl/submissions.html) (SHACL 1.0 era) or their own matrices for 1.2; membership in an IR does **not** guarantee automation, license terms, or API access for benchmarking.

**What you can do in Kastor.**

1. **Use the same manifest-discovered cases as workloads** the conformance harness already understands (see `Shacl12NativeConformanceTest` and pinned `manifest.ttl` / `test-data` layout): each row becomes a **workload descriptor** (data path, shapes path, graph roles, expected outcome).
2. **Run every engine you can legally and technically automate** against that descriptor set.
3. **Intersect on capability:** For each test ID, record `supported | skipped | divergent` per engine (reason: unsupported SPARQL constraint, RDF 1.2 triple term, approval flag, known bug). **Timing tables only include rows in the intersection** for that pair-wise or N-way comparison, or show **per-engine coverage %** beside timings so readers see selection bias.
4. **Do not equate “passes conformance” with “included in perf table”:** A test can be **correctness-pass** but **excluded from perf** if it is too small, flaky on CI, or dominated by I/O; keep two tracks — **conformance** (full manifest) vs **benchmark** (curated superset of larger/slower cases from the same repo).

**PySHACL, TopBraid, Jena, RDF4J, etc.**

- Any of them can be added as a **column** as long as the harness can invoke them and the **capability matrix** marks unsupported tests as N/A rather than timing zero.
- **Commercial** or **non-redistributable** engines belong in **manual/self-hosted** jobs; the **methodology** is still the same (shared files, same manifest slice, published skip reasons).

### 12.3 Recommended artefact: capability matrix

Maintain a machine-readable table (e.g. CSV or JSON checked into `benchmarks/shacl/`) keyed by **stable test id** (manifest entry URI or Kastor normalised id):

- Columns: engines under test.
- Values: `run` · `skip_unsupported` · `skip_known_bug` · … with optional free-text reason.

The benchmark harness **generates** skip entries from conformance probes where feasible so manual maintenance does not drift.

### 12.4 ERA-SHACL-Benchmark: integrating the Kastor native engine

The published **[ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark)** repo runs each engine as a **Docker image** and parses **exactly two** lines from container stdout (`run_benchmark.sh` uses `grep "Load time:"` and `grep "Validation time:"`). Any Kastor integration must preserve that contract so results are comparable with Jena, RDF4J, pySHACL, etc.

#### 12.4.1 Behavioural contract (must match)

**CLI arguments** (three positionals, same as `engines/jena`):

1. Path to **data** Turtle  
2. Path to **shapes** Turtle  
3. Path to **output validation report** Turtle  

Paths are whatever `docker run …` passes; volumes mount host `./data` → `/data`, `./shapes` → `/shapes`, `./results/<engine>/reports` → `/reports` (see upstream `run_benchmark.sh`). The reference **Jena** CLI uses filesystem paths that resolve inside the container (`engines/jena` uses `RDFDataMgr.loadGraph(DATA)` — mirror that with `Rdf.parseFromFile` or equivalent).

**Stdout** (parsed by benchmark scripts):

```text
Load time: <seconds>
Validation time: <seconds>
```

- Numeric **seconds** only (no unit suffix). Use the same precision style as Jena (`TimeUnit.NANOSECONDS.toMillis(n)/1000.0`).

**Optional stdout** (ignored by `grep` but useful for logs): lines such as `Data graph size:` — copied from [JenaValidator.java](https://github.com/oeg-upm/ERA-SHACL-Benchmark/blob/main/engines/jena/src/main/java/oeg/shacl/validator/JenaValidator.java).

**Artifact:** Write an **RDF/Turtle** validation report to argument 3. ERA’s analysis scripts expect a report file; use Kastor’s **`ValidationReport.toShaclValidationReportRdf`** and **`Rdf.serializeGraph`** (or equivalent) so output is valid SHACL report vocabulary.

#### 12.4.2 Timing split aligned with the reference Jena engine

ERA’s Jena reference measures:

| Phase | What is timed |
|--------|----------------|
| **Load time** | Loading the **data** graph from disk into a Jena `Graph` only |
| *(untimed in stdout)* | Loading the **shapes** graph, `Shapes.parse`, setup |
| **Validation time** | `ShaclValidator.get().validate(shapes, dataGraph)` only |

So **shapes I/O and compile/plan** sit **outside** the printed `Validation time` in Jena. For **fair comparison** on ERA tables, the Kastor CLI should mirror that split:

1. Start timer A → parse **data** TTL into `RdfGraph` → stop → print `Load time:`  
2. Parse **shapes** Turtle, run shape merge/digest/`ShapesCompiler.compile` (and any equivalent of `Shapes.parse`) **without** including timer B yet  
3. Start timer B → run **constraint execution only** (the hot loop after `ValidationContext` is built) → stop → print `Validation time:`  

**Important:** `NativeShaclValidator`’s public `validate(graph, shapes)` currently **fuses** compile and execution. For ERA parity you should either:

- **Preferred:** Add a small **benchmark-only entry** (in the ERA `engines/kastor` module or Kastor’s repo) that calls the same internal steps as `runValidation` but **inserts** the timer boundary **after** compile / **before** the node-shape loop, or  
- **Documented fallback:** Print `Validation time` for the **whole** `validate()` call and label published rows **“Kastor (fused compile+validate)”** — not directly comparable to Jena’s second column.

#### 12.4.3 Docker image layout (inside ERA repo)

Follow the pattern of `engines/jena/`:

| File | Purpose |
|------|---------|
| `engines/kastor/Dockerfile` | JDK image + copy fat JAR or build context |
| `engines/kastor/build.sh` | `docker build -t kastor-validation-experiment:latest .` |
| `engines/kastor/entrypoint.sh` | Optional wrapper: `java -jar kastor-era-cli.jar "$@"` |

**Image tag:** `kastor-validation-experiment:latest` — `run_benchmark.sh` invokes `$engine-validation-experiment:latest` where `$engine` is the directory name (e.g. `jena`, `kastor`).

`engines/build_images.sh` loops `*/` under `engines/` and runs `build.sh`; adding `engines/kastor` is enough for local builds.

#### 12.4.4 Registering Kastor in `run_benchmark.sh`

Edit the `for engine in …` list in [run_benchmark.sh](https://github.com/oeg-upm/ERA-SHACL-Benchmark/blob/main/run_benchmark.sh) to include **`kastor`** (same loop as `jena`, `rdf4j`, …). Until an upstream PR is merged, keep this change in a **fork** or local patch.

#### 12.4.5 Kastor runtime configuration

- Use **`ShaclValidation.validator(ValidationConfig.default())`** (or an explicit config) with **`providerId`** / preference set to the **native** Kastor engine so the ERA image does not accidentally use a different provider.  
- For **cold** shape compile per run, disable or bypass **compile cache** for ERA’s repeated iterations if you need each Docker run to reflect full compile cost (match team policy; Jena does not cache `Shapes` across process invocations).  
- **GeoSPARQL:** Jena’s reference enables `GeoSPARQLConfig.setupMemoryIndex()`. If ERA data uses GeoSPARQL and Kastor requires analogous setup, document it in the Kastor engine README; if unsupported, mark those rows **N/A** in the capability matrix (Section 12.3).

#### 12.4.6 Build options for the CLI artifact

- **Fat JAR / shadow JAR:** Use **`benchmarks/shacl-era-cli`** (`installDist` or fat JAR) that depends on `rdf-core`, `rdf:jena`, and `rdf:shacl-validation`, and copy the artifact into `engines/kastor/` for Docker.  
- **Multi-stage Dockerfile:** Clone Kastor, run `./gradlew …` to produce the same JAR inside the image (slower CI, single source of truth).  
- Pin **JDK major version** to match Kastor’s supported runtime.

#### 12.4.7 Operating the full benchmark

1. Clone [ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark).  
2. Run `./get_data.sh` (downloads/prepares `data/` and `shapes/` — check **license** for RINF-derived assets).  
3. `./engines/build_images.sh` (builds `kastor-validation-experiment` among others).  
4. `./run_benchmark.sh` — results under `results/kastor/*.csv`, logs from Docker.  
5. Optional: `analysis/` scripts as in upstream README.

#### 12.4.8 Contributing upstream

Open a PR against `oeg-upm/ERA-SHACL-Benchmark` adding `engines/kastor/` and a one-line `run_benchmark.sh` change. Include a short **README** in `engines/kastor` documenting JDK version, Kastor version/git tag, and any **capability / timing** caveats from Sections 12.4.1–12.4.2.

#### 12.4.9 Caveats (remain true)

- ERA workloads stress **SHACL Core + SHACL-SPARQL** in a domain graph; they **do not** replace Kastor **SHACL 1.2** conformance coverage.  
- **Data provenance:** confirm redistribution terms before vendoring ERA `data/` in the Kastor monorepo.  
- When merging ERA numbers with **in-process JMH** results from this document, declare a **comparison mode** (Section 12.1).

**Corpus-only mode:** You can still use ERA-derived Turtle under `test-data/` inside Kastor’s own JMH harness without Docker; that does **not** require the Docker contract but loses direct comparability to published ERA tables unless methodology is aligned. Checked-in JSON workload descriptors: **`benchmarks/shacl/jmh/workloads/`** (paths relative to repository root).

---

## 13. References

- [SHACL validation architecture](shacl-validation-architecture.md) — provider model, bridge fidelity, performance engineering, CI threshold intent
- [SHACL validation feature](../features/shacl-validation.md) — user-facing overview
- [PySHACL](https://github.com/RDFLib/pySHACL) — Python SHACL validator (RDFLib)
- [TopQuadrant / TopBraid SHACL](https://www.topquadrant.com/products/topbraid-shacl/) — commercial SHACL tooling and APIs (benchmark only where licensing allows)
- [W3C data-shapes test repository](https://github.com/w3c/data-shapes) — manifests and fixtures (source for Tier A / capability matrix)
- [ERA-SHACL-Benchmark](https://github.com/oeg-upm/ERA-SHACL-Benchmark) — real-world corpus + Docker harness (optional Tier C); companion paper via [Semantic Web Journal](https://www.semantic-web-journal.net/content/era-shacl-benchmark-real-world-benchmark-assess-performance-and-quality-memory-shacl-engines)
- W3C SHACL test suite (pinned versions as used by `Shacl12NativeConformanceTest`)
