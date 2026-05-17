# SHACL validation architecture

| | |
|---|---|
| **Status** | Architectural intent (evolves with SHACL 1.2 W3C drafts). **Authority split:** (1) **Policies and layering** in this document are normative for behavior—if the implementation disagrees, treat the **code as wrong** until an issue/PR or an explicit doc amendment. (2) **Kotlin signatures and existing type members** in `rdf/shacl/validation` are normative for the API surface—if this document disagrees, treat the **doc as wrong** and fix the appendix/prose. Summary tables: [Appendix A](#appendix-a-contract-surfaces-and-source-of-truth). |
| **Audience** | Kastor contributors, integrators choosing validation backends |
| **Modules** | `rdf/shacl/validation`, future `shacl-validation-*` bridge artifacts, native engine submodules |

This document describes how Kastor supports **SHACL validation** in two complementary ways: a **native** Kotlin implementation on Kastor’s RDF and SPARQL stack, and **pluggable** adapters to existing SHACL engines (Jena, RDF4J, and others). User-facing how-to content remains in [SHACL validation](../features/shacl-validation.md), [How to validate SHACL](../guides/how-to-validate-shacl.md), and [Ontology Quality](../features/ontology-quality.md) / [How to check ontology quality](../guides/how-to-ontology-quality.md).

**Contents:** [§1 Problem](#1-problem-statement) · [§2 Goals](#2-design-goals) · [§3 DDD / Clean Architecture](#3-domain-driven-design--clean-architecture) · [§4 Non-goals](#4-non-goals-initial-phases) · [§5 Spec scope](#5-specification-scope) · [§6 Seam](#6-public-integration-seam) · [§7 Providers](#7-provider-resolution-required-hardening) · [§8 Modules](#8-module-and-artifact-layout) · [§9 Native engine](#9-native-engine-architecture-summary) · [§10 Bridges](#10-bridge-adapters-external-engines) · [§11 Capabilities](#11-capabilities-and-profiles) · [§12 Gen](#12-relationship-to-kastor-gen) · [§13 Testing](#13-testing-strategy) · [§13.1 Parity normalization](#131-parity-report-normalization) · [§14 Roadmap](#14-roadmap-engineering-phases) · [§15 References](#15-references) · [Appendix A](#appendix-a-contract-surfaces-and-source-of-truth) · [v2.0 refresh](#document-v20-refresh-after-p0a-non-normative)

---

## 1. Problem statement

Applications need to validate RDF data against SHACL shapes without locking themselves to a single engine:

- Some deployments already standardize on **Apache Jena SHACL**, **RDF4J**, or product-specific processors and want **drop-in** use from Kastor APIs.
- Other deployments want **no extra native stack** beyond Kastor, **predictable Kotlin semantics**, streaming, and tight alignment with Kastor’s **RDF 1.2** and **SPARQL** roadmap.

Kastor therefore treats SHACL as a **capability** accessed through a **stable facade**, with **multiple providers** behind it.

---

## 2. Design goals

1. **Single application API.** Callers depend on `com.geoknoesis.kastor.rdf.shacl` types (`ShaclValidator`, `ValidationReport`, `ValidationConfig`) and never on engine-specific types at the facade boundary.
2. **Optional bridge dependencies.** Core `shacl-validation` must not require Jena, RDF4J, or other SHACL implementations on the classpath.
3. **First-class native engine.** A Kastor-owned provider implements SHACL 1.2 (Core, then SPARQL Extensions, then Rules and related documents) using `RdfGraph` / dataset abstractions and Kastor’s SPARQL execution where constraints are graph-pattern-shaped.
4. **Explicit provider selection.** Registry resolution must not rely solely on opaque heuristics; callers can force a provider or express preference order (native vs bridge). **`EnginePreference.AUTO` is heuristic by design**; deployments that require reproducible engine choice **must** set `providerId` or a non-`AUTO` preference (see [§7](#7-provider-resolution-required-hardening)).
5. **Comparable results.** All providers map engine output into the same **`ValidationReport`** (and, when implemented, the same **RDF `sh:ValidationReport`** graph) so tools built on Kastor behave consistently.
6. **Conformance-driven evolution.** The native engine is judged against the **W3C SHACL 1.2 test suite** and pinned specification dates until Recommendation.
7. **JVM performance leadership (native engine).** The native implementation is optimized to be **among the fastest SHACL validators on the JVM** for typical enterprise workloads, with **reproducible micro- and macro-benchmarks** against Apache Jena SHACL and Eclipse RDF4J as baselines. “Fastest” is a **release criterion backed by CI**, not a slogan: regressions block merges when they exceed agreed thresholds on the benchmark corpus. **User-facing product copy** must not claim JVM speed leadership unless benchmark methodology and results are **published** alongside the claim (this doc is contributor-normative).
8. **Clean Architecture and DDD discipline.** SHACL validation is a **bounded context** with a **stable application boundary** (`ShaclValidator` and ports); domain rules live in pure Kotlin modules that do not depend on Jena, RDF4J, file I/O, or HTTP. See [§3.1](#31-domain-driven-design--bounded-context) and [§3.2](#32-clean-architecture--dependency-rule).

---

## 3. Domain-driven design & Clean Architecture

This section makes explicit how the earlier goals map to **DDD** and **Clean Architecture** so new code does not “leak” infrastructure into constraint semantics.

### 3.1 Domain-driven design & bounded context

- **Bounded context:** **SHACL Validation** — vocabulary, constraint semantics, path algebra, severity, and reporting are modeled here using **ubiquitous language** aligned with the W3C specs (`NodeShape`, `PropertyShape`, `FocusNode`, `ValidationReport`, `Severity`, `ConstraintComponent`, and so on). Types in user-facing APIs should read like the spec, not like a particular backend’s internals.
- **Core domain vs generic subdomain:** Constraint evaluation and path reasoning are **core** to Kastor’s RDF platform; **bridge adapters** are a **supporting subdomain** (integration), implemented as thin anti-corruption layers over Jena/RDF4J.
- **Domain services:** Cross-cutting pure operations (path normalization, constraint dispatch, plan selection) are **stateless domain services** over immutable compiled structures (**`CompiledShapeGraph`** — planned native compile artifact, not yet a public type in `rdf/shacl/validation` — and **constraint plans**), not methods on `RdfGraph` or SPARQL engine types.
- **Policies:** Provider selection (`EnginePreference`, `priority`, `providerId`) is an explicit **domain policy** expressed in configuration, not hidden inside static singletons.

### 3.2 Clean Architecture & dependency rule

Dependencies point **inward**: outer layers depend on inner abstractions; the domain never depends on frameworks.

| Layer (inner → outer) | Responsibility | Examples (conceptual packages / modules) |
|------------------------|----------------|------------------------------------------|
| **Entities / domain model** | SHACL semantics, compiled shape graph, path algebra, violation rules | `shacl-model`, `shacl-core` (pure Kotlin; **no** Jena/RDF4J/HTTP; graph access only via **narrow domain ports** if needed, not concrete store types) |
| **Use cases / application** | Orchestrate “validate dataset against shapes graph”; transaction boundaries; choose native vs bridge via ports | `ShaclValidator` implementations, validation orchestration |
| **Interface adapters** | Map `RdfGraph` to internal indexes; map engine reports to `ValidationReport`; SPARQL plan emission | Native execution adapters, bridge mappers |
| **Frameworks & drivers** | Kastor SPARQL engine, term storage, Jena/RDF4J SDKs | `rdf-core`, SPARQL executor, optional bridge JARs |

**Ports (outbound):** abstract interfaces the domain/use case layer needs (e.g. “execute this graph pattern with these bindings”, “enumerate focus nodes for target declaration X”). **Adapters (inbound):** `ShaclValidatorProvider` and `ShaclValidator` as the **primary port** for applications; bridges are **secondary adapters** to external engines.

**Invariant:** `shacl-validation` (facade + registry) depends only on **stable RDF abstractions** and domain-facing types; native and bridge modules depend **toward** the domain and **away** from embedding app code.

### 3.3 Mapping to the native pipeline (§9)

| Pipeline stage | Architectural role |
|----------------|---------------------|
| Parse / compile | **Domain factory:** shapes RDF → **`CompiledShapeGraph`** (planned internal representation; pure transformation where possible) |
| Plan | **Domain service:** constraint → execution plan (native vs SPARQL vs expression) |
| Execute | **Application + adapters:** run plans through **ports** (index scan, SPARQL executor) |
| Report | **Domain + application:** assemble `ValidationReport` / `sh:ValidationReport` from domain results |

---

## 4. Non-goals (initial phases)

- **In-process JavaScript/Python SHACL extensions** as required dependencies (optional sandboxes may appear later behind capability flags).
- **SHACL UI form rendering** inside `shacl-validation` (vocabulary consumption and label algorithms may live in higher layers or Kastor Gen).
- **Replacing** Kastor Gen’s materialization-time `ValidationContext` adapters; repository-level SHACL and Gen validation remain related but distinct entry points. This design focuses on **`ShaclValidator` / `rdf/shacl/validation`**.
- **Kotlin Multiplatform discovery:** `java.util.ServiceLoader` ties provider discovery to the **JVM**; a future multiplatform Kastor would need an alternate registry SPI. Out of scope until such a platform target exists.

---

## 5. Specification scope

The native engine targets the **SHACL 1.2** family as **W3C Working Drafts** (separate documents, independently versioned). Pin implementation and tests to **dated WD snapshots** until W3C Recommendation.

| Layer | Specification role |
|-------|---------------------|
| Core | Node and property shapes, parameters, targets, severity, logical operators, RDF 1.2 alignment |
| SPARQL Extensions | SPARQL-based constraints and components, pre-bound variables |
| Node Expressions | Executable value expressions (FPWD and later; e.g. Jan 2026 First Public Working Draft) — see [§15](#15-references) for vocabulary split (`shnex:` vs `sparql:`). |
| Rules | Rule sets and entailment-style materialization semantics |
| Profiling | Declared subsets for deployments with reduced surface area |

External engines are described by **`ValidatorCapabilities`** (and optional version metadata), not assumed to match every 1.2 feature on day one.

### 5.1 RDF 1.2 triple terms (native policy)

Kastor’s RDF stack targets **RDF 1.2**; SHACL 1.2 Core aligns with triple-term equality. Until W3C Recommendation, pin behavior to the **same dated RDF 1.2 / SHACL WD snapshot** as the rest of the engine.

- **Data graph:** Triple terms in **focus nodes, path objects, and compared values** are evaluated with **RDF 1.2 term equality** (including triple-term identity) per the pinned spec; no silent downgrade to string or lexical-only comparison.
- **Shapes graph:** Triple terms appearing as **parameter values** (e.g. `sh:hasValue`, `sh:in` members) are supported on the schedule below; unsupported combinations **fail at compile** with a clear error rather than mis-validating.
- **Coverage schedule:** **P1a** — triple terms in **data** only (validators must not crash; equality semantics per WD). **P1b** — triple terms as **`sh:hasValue` / `sh:in` / comparable Core parameters** in shapes where the WD defines behavior.

---

## 6. Public integration seam

### 6.1 `ShaclValidatorProvider`

Each engine (native or bridge) implements **`ShaclValidatorProvider`**:

- Stable **`getType(): String`** identifier (e.g. `kastor`, `jena`, `rdf4j`).
- **`createValidator(ValidationConfig): ShaclValidator`**
- **`getCapabilities(): ValidatorCapabilities`**
- **`getSupportedProfiles()` / `isSupported(ValidationProfile)`**

### 6.2 Discovery

Providers are registered via **`java.util.ServiceLoader`** using:

`META-INF/services/com.geoknoesis.kastor.rdf.shacl.ShaclValidatorProvider`

The in-repo default registration today lists the memory-based provider; as the native engine ships, its provider replaces or sits alongside that entry. Bridge modules add **additional** service files in their own JARs.

**JVM note:** ServiceLoader is the default discovery mechanism on JVM only; see non-goals ([§4](#4-non-goals-initial-phases)) for multiplatform.

### 6.3 `ValidatorRegistry` and `ShaclValidation`

`ValidatorRegistry` aggregates providers, resolves one for a given **`ValidationConfig`**, and constructs a **`ShaclValidator`**. `ShaclValidation` remains the **factory façade** for application code.

---

## 7. Provider resolution (required hardening)

Choosing among multiple providers must use a **deterministic policy**. **`AUTO` remains intentionally heuristic** (prefer **native** when it is selected by the rule below, else the highest-priority matching bridge). For **bit-reproducible** validation across machines and releases, callers **must** set `providerId` or a non-`AUTO` `EnginePreference`.

**`AUTO` — operational definition**

- **Profile satisfaction (superset check):** A provider **matches** the request when, for every capability **required** by the requested `ValidationProfile` (see [§11](#11-capabilities-and-profiles)), the corresponding bit (or structured field) on `getCapabilities()` is **true**. A provider **may** expose additional capabilities (superset).
- **Granularity vs [§5.1](#51-rdf-12-triple-terms-native-policy):** `ValidatorCapabilities` **must** expose distinct flags for at least **`supportsRdf12TripleTermsInData`** and **`supportsRdf12TripleTermsInShapeParameters`** so `AUTO` does not pick native at P1a (data-only triple terms) when the profile requires shape-parameter triple terms (P1b), nor pick a bridge that omits either bit while the profile demands it.
- **Ordering among matches:** Prefer **native** when it satisfies the profile and `EnginePreference` allows it; otherwise lowest `priority`, then lexicographic `getType()` as in [§7.1](#71-failure-semantics-normative).

**Observability:** The **Kastor default** `ValidatorRegistry` / factory path **must** log once per constructed validator (or per stable `ValidationConfig` fingerprint) at **INFO**: resolved **`getType()`**, **`EnginePreference`**, and **`ValidationProfile`**. Custom registries **should** do the same so operators can explain “why Jena / why RDF4J” without attaching a debugger.

| Mechanism | Description |
|-----------|-------------|
| **`providerId: String?`** on `ValidationConfig` | If set, **only** that provider may satisfy the request. |
| **`EnginePreference`** (new on `ValidationConfig`) | `NATIVE_FIRST`, `BRIDGE_FIRST`, `AUTO` — ordering when several providers match the profile. |
| **`priority: Int`** on `ShaclValidatorProvider` (new) | Lower value wins when preference and capabilities tie. |

### 7.1 Failure semantics (normative)

| Situation | Required behavior |
|-----------|-------------------|
| `providerId` set to `"jena"` (example) but no registered provider with that `getType()` | Throw **`ProviderNotFoundException`** (extends **`ShaclValidationException`**, [Appendix A](#appendix-a-contract-surfaces-and-source-of-truth)) with message naming the id; **do not** silently fall back. |
| `providerId` set but provider **does not** support the requested `ValidationProfile` | Throw **`UnsupportedProfileException`** (extends **`ShaclValidationException`**) listing provider id and profile. |
| `EnginePreference.NATIVE_FIRST` and native does **not** support the requested profile | **Fall through** to the next matching provider in preference order (bridges). If **no** provider satisfies the profile, fail with **`UnsupportedProfileException`** listing attempted providers. |
| `EnginePreference.BRIDGE_FIRST` and no bridge matches, native matches | Use native. |
| `EnginePreference` other than `AUTO` implies deterministic ordering | No random or classpath-order-only selection. |
| Two providers tie on `priority` and both match | **Tiebreaker:** lexicographically smaller `getType()` wins (stable, documented). **Implementation note:** renaming a provider’s `getType()` (e.g. `kastor` → `native`) changes tie order; record in registry release notes when it happens. |

### 7.2 Version negotiation (optional)

When **`EngineMetadata`** (or equivalent) exposes engine/vendor SHACL versions, the registry **may** warn or reject combinations that are known-incompatible with the requested profile; default remains **capability-bit** matching unless strict mode requests version pins.

---

## 8. Module and artifact layout

| Artifact | Purpose |
|----------|---------|
| **`shacl-validation` (existing)** | API: `ShaclValidator`, `ValidationConfig`, `ValidationReport`, `ShaclValidatorProvider`, registry, façade. Minimal or no engine-specific code. |
| **Native implementation** (split internally when size warrants) | `shacl-model`, `shacl-parse`, `shacl-core`, `shacl-sparql`, `shacl-expr`, `shacl-rules` as subprojects or packages; published under the same or coordinated version as Kastor. |
| **`shacl-validation-jena` (optional)** | Depends on Jena + Kastor Jena adapter; converts graphs, runs Jena SHACL, maps report to Kastor types. |
| **`shacl-validation-rdf4j` (optional)** | Same pattern for RDF4J. |

Applications add **`implementation(...)`** on the bridge they need; they do not pay the classpath cost of unused engines.

---

## 9. Native engine architecture (summary)

**`CompiledShapeGraph`** names the planned **normalized, immutable** result of compile (§9 pipeline step 1). It is **not** yet exposed as a public Kotlin type; native work introduces it inside the engine module set ([§8](#8-module-and-artifact-layout)).

High-level pipeline:

1. **Parse / compile:** RDF shapes graph → normalized **`CompiledShapeGraph`** (targets, property paths, parameters, nesting, **`sh:deactivated`** per [§9.1](#91-recursion-deactivation-and-logical-nesting)).
2. **Plan:** For each constraint, produce a **constraint plan** (native evaluator vs SPARQL fragment vs node expression).
3. **Execute:** Dispatch plans; SPARQL-shaped work runs through **Kastor SPARQL** with standard variable bindings per SHACL SPARQL Extensions.
4. **Report:** Emit **`ValidationReport`** and RDF **`sh:ValidationReport`** in Kastor’s term model.

Cross-cutting:

- **Path engine:** Shared between native evaluation and SPARQL generation (`sh:path`, inverse, alternatives, sequences, modifiers).
- **Dataset-aware validation:** See [§9.2](#92-dataset-named-graphs-and-shape-graph-imports) (not a one-line concern).

### 9.1 Recursion, deactivation, and logical nesting

These policies are **implementation-normative** for the native engine so behavior is debuggable and test-backed; SHACL 1.2 Core still carries sharp edges around recursion.

| Topic | Kastor policy |
|-------|----------------|
| **`sh:node` / shape cycles** | Detect **strongly connected** shape dependency components at compile time. For well-formed graphs, evaluate with a **visited set per validation root** and **bounded depth** via **`ValidationConfig.maxRecursionDepth`**, default **`64`** — high enough for **W3C SHACL test graphs and typical published shape libraries** (FHIR, schema.org, DCAT-style nesting rarely exceeds a handful of `sh:node` hops), low enough to **fail fast** on accidental cycles; adjust only with evidence from benchmarks or spec tests; document in KDoc. On exceed, emit **`sh:Violation`** (or `sh:Warning` per product policy) describing cycle participation rather than non-termination. **Ill-formed** graphs per spec: reject at compile with explicit error. |
| **Mutually recursive `sh:and` / `sh:or` / `sh:xone`** | Same SCC machinery as shape references; logical nodes share the visited/depth budget. |
| **`sh:deactivated true`** | **Policy (normative):** `sh:deactivated` applies **only** to the **node shape or property shape** that carries it. The compile stage **elides** that shape’s constraints from `CompiledShapeGraph`. **`sh:node` / `sh:property` nesting does not “inherit”** deactivation from a parent: each referenced shape is active unless **that** shape (or its own parameters) is deactivated. A deactivated **property shape** removes only that property’s constraints; sibling properties and the parent node shape’s other parameters remain unless they are separately deactivated. |

### 9.2 Dataset, named graphs, and shape-graph imports

**Defaults (document and test explicitly):**

- **Data graph:** The `RdfGraph` (or dataset view) passed to `validate` is the **default data graph** unless `ValidationConfig` names graph IRIs for data and shapes roles.
- **Shapes graph:** Distinct `RdfGraph` or named graph IRI when the API is dataset-shaped; never silently merge shapes into data without caller opt-in.
- **`sh:shapesGraph` / shapes discovery:** When the spec declares shapes graph references on data, the engine resolves them per SHACL 1.2 Core. **Misconfiguration is an engine fault, not a data `sh:Violation`:** throw a **`ShaclValidationException`** subtype **before** returning a `ValidationReport`, with IRIs and role (data vs shapes) in the message. Use **`ShapesGraphNotFoundException`** when the declared graph **IRI or named graph is absent** from the supplied dataset (nothing to validate against). Use **`ShapesGraphAccessException`** when triples were **expected to be available** but are not (e.g. `allowImportFetch == true` but fetch failed, file I/O error, or policy forbids loading that IRI). Callers distinguish “could not set up validation” from “data failed shapes.”
- **`owl:imports` on the shapes graph:** **Off by default**; when enabled via `ValidationConfig.resolveOwlImports`, resolve imports **before** compile, with **cycle detection** and **`maxImportDepth`**. **Network:** HTTP(S) or other **out-of-band fetch** for import IRIs **must** require explicit **`allowImportFetch: Boolean`** (default **`false`**). When `allowImportFetch` is false, only IRIs **already present** in the supplied dataset (or inlined closure the caller loaded) are followed—never silent network I/O during validation.
- **Offline / air-gapped:** Document which import IRIs were resolved from the dataset vs skipped due to missing triples or fetch disabled.

### 9.3 Performance engineering (JVM)

The native engine treats **throughput and predictable latency** as co-equal with conformance. The following strategies are intentional—not optional polish—and should be reflected in module boundaries (§3, §8) so hotspots stay in **adapters** and **compiled plans**, not in scattered stringly-typed logic.

**Benchmark contract (measurable thresholds)**

For workload tiers, phase separation (parse / convert / compile / validate), harness layout, and CI job roles, see the dedicated design note: [SHACL native engine: cross-implementation performance benchmarks](shacl-native-engine-benchmark.md).

- **CI hardware profile (default):** `ubuntu-latest`, single JVM, **JMH:** at least **3 warmup** and **5 measurement** iterations; use **5 forks** (JMH default) for **authoritative** regression comparison vs baselines. A **1-fork** quick job is allowed for smoke signals but **must** be labeled non-authoritative in CI and **must not** gate merges on thresholds alone.
- **Macro** comparisons (native vs Jena vs RDF4J): same runner image; **3 fixed random seeds** for stochastic graph generators; report **median** and **p95** wall time and **allocated bytes** (e.g. JFR or JMH `-prof gc`).
- **Regression gates (starting point for CI config):** a PR **fails** if native median regresses **>10%** or p95 **>25%** vs the **checked-in baseline** file for that scenario (see below), unless explicitly waived with a tracked issue.
- **Runner noise:** `ubuntu-latest` **CPU models vary** across GitHub-hosted runners. Gates are defined as **relative** (PR vs checked-in baseline, or native vs Jena/RDF4J **in the same job**). When a job recomputes baselines on the fly, compare **within that job**; absolute numbers drift across days—expected.
- **Baselines:** Store committed numbers or ratio bands under **`benchmarks/shacl/jmh/baselines/`** (or the module that hosts JMH) with a short **README** describing regeneration (`./gradlew jmh` or equivalent). Reviewers compare PR output to those files—**not** ad hoc judgement.

**Compile-time vs run-time**

- **Amortize compilation:** `CompiledShapeGraph` is **immutable and cacheable**. Two key strategies are supported; **name them in code and KDoc** so embedders are not tempted to invent digests.
  - **Default key — `SHAPES_STRUCTURAL_DIGEST_V1`:** deterministic **structural** digest of the shapes **RDF graph**: build **deterministic blank-node labels** from the **parsed graph**, not parser delivery order. **Blank-node identity:** one digest-local label per **distinct blank-node term** in the in-memory **`RdfGraph`** (implementation-stable blank node id / object identity). Isomorphic but **distinct** list cells in nested `sh:or` / `sh:and` remain **distinct** labels—desirable for cache keys. **Label ordering within the digest string:** assign each blank node a serial by **lexicographic order of canonicalized incident triple rows** (subject–predicate–object tuples with nested terms serialized consistently), then emit **sorted N-Triples** (Unicode codepoint order) over the full graph, concatenate with `ValidationProfile` and compile-relevant `ValidationConfig` flags (imports on/off, entailment hooks), then **SHA-256**. If an edge case remains ambiguous (exotic bnode-heavy shapes), **use `SHAPES_RDF_CANONICAL_DIGEST`** for that compile. This avoids digest drift when two Turtle parsers emit triples in different orders for the same file. Semantically isomorphic graphs that differ only in bnode **syntax** may still produce **distinct** digests and thus **recompile** — acceptable trade-off for speed vs full canonicalization.
  - **Optional strict key — `SHAPES_RDF_CANONICAL_DIGEST`:** **RDF graph canonicalization** (e.g. **RDFC-1.0**, with **URDNA2015** as legacy fallback where applicable) over the shapes graph treated as the default graph of a single-graph dataset, when semantic-level cache hits are required; expensive, **opt-in** via config.
  - **Opt-in tag fast path — `shapesGraphVersion: String?`:** when set, the engine **may** skip recomputing `SHAPES_STRUCTURAL_DIGEST_V1` **only** after verifying the tag matches the last run **or** after recomputing the digest and comparing to the cached entry. If the structural digest **does not** match the entry stored for that tag, the engine **must** throw **`StaleShapesGraphTagException`** (extends **`ShaclValidationException`**) and **must not** report conformance as if the new graph had been compiled. Callers bump the tag or clear the cache.
- **Specialize plans:** Map high-frequency Core constraints (`minCount`, `maxCount`, `datatype`, `in`, `class`, `nodeKind`, …) to **closed-form native evaluators** instead of generic SPARQL unless the data model forces generality.
- **De-duplicate work:** Share sub-plans across shapes; hoist common path walks; merge compatible filters on the same path.

**Data-side acceleration**

- Build **lightweight indexes** from `RdfGraph` for validation sessions: `rdf:type` / `sh:targetClass` sets, adjacency by predicate, optional subject→objects maps for hot predicates. Prefer **streaming single-pass** index build when graphs are already memory-resident.
- **Push filters into scans:** evaluate cheapest constraints first to **prune focus nodes** early (shape logical `sh:and` / `sh:or` ordering as an optimization problem with correctness preserved).
- **Batch pattern evaluation:** where SPARQL is required, prefer **one query with vectorized bindings** over per-focus-node queries when the algebra allows.

**Parallelism and concurrency**

- Exploit **embarrassingly parallel** partitions (independent node shapes, disjoint focus sets) with **structured concurrency** using **`kotlinx.coroutines`** (`supervisorScope`, bounded parallelism on `Dispatchers.Default` or a dedicated pool); avoid sharing mutable graph views without clear happens-before rules.
- Keep **parallelism policy** in `ValidationConfig` / capabilities so embedders can disable it for deterministic debugging.

**JVM and Kotlin specifics**

- Prefer **compact representations** for intermediate bindings (primitive arrays, `Long` bitsets for small universes) where profiling shows allocation pressure.
- Avoid boxing in inner loops; use **value-based** immutable rows for violations only when needed for reporting.
- Minimize **full materialization** of result sets: stream solutions into violation builders; cap optional “max violations per shape” for UI use cases.

**Correctness over shortcuts:** any optimization that could change semantics must be **guarded by tests** from the W3C suite or explicit negative cases. Spec edge cases stay on the **slow path** if needed.

### 9.4 Streaming and incremental validation (scope)

- **v1 native:** default remains **batch** `ValidationReport` unless `ValidationConfig.streamingMode` (or a dedicated API) is stabilized; streaming is **not** required for Core correctness in the first native release.
- **When streaming ships:** document **ordering** (e.g. stable by focus node IRI then constraint), **back-pressure** (`Flow` buffer bounds), and **`maxViolations` / per-shape caps**. Call out constraints that **require buffering** (e.g. `sh:qualifiedMinCount` / `sh:qualifiedMaxCount` per focus node).
- **Bounded buffering contract:** `ValidationConfig` gains **`maxPerFocusBuffer: Int`** (and **`streamingBufferPolicy`**, e.g. `BATCH_FALLBACK`, `SKIP_WITH_WARNING`): per-focus-node accumulators for qualified-value and similar constraints **must not** grow without bound. If **`maxPerFocusBuffer`** is exceeded, the engine **must** add a **`ValidationWarning`** (and the RDF report’s `sh:Warning` analogue when emitted) describing the cap, then apply the configured policy — **never** silent full-graph buffering. Correctness **must** still match batch mode for inputs within the buffer limits.

---

## 10. Bridge adapters (external engines)

Each bridge:

1. **Imports** `RdfGraph` (and optionally named graphs) into the backend model using existing Kastor **Jena/RDF4J** conversions.
2. **Invokes** the backend’s SHACL API with the same shapes and data roles as the native engine contract.
3. **Exports** results into **`ValidationViolation`** / **`ValidationReport`** under the fidelity rules below.

Bridges should expose **`EngineMetadata`** (engine name, vendor SHACL version claim, supported spec profile) for logging and support.

### 10.1 Bridge to Kastor report fidelity (normative)

For a bridge to be advertised as **`ValidatorCapabilities`**-conformant for a profile, every validation run **must** produce a `ValidationReport` such that:

| Field / aspect | Requirement |
|----------------|---------------|
| `isValid` | **MUST** reflect whether the backend reported non-conformance (modulo documented mapping if the backend only returns a boolean). |
| Each `ValidationViolation.resource` (focus node) | **MUST** be set when the backend identifies a focus node; if the backend only returns subject URIs, map them into `RdfResource`. |
| `ValidationViolation.severity` | **MUST** map from `sh:severity` when present; otherwise **default** to `ViolationSeverity.VIOLATION` (documented). |
| `ValidationViolation.message` | **MUST** be non-empty; use backend message or a deterministic fallback template (`"${constraint} at ${resource}"`). |
| `ValidationViolation.path` | **SHOULD** map `sh:resultPath` / equivalent when present. **MAY** be `null` if the backend does not expose paths, **documented** in bridge README. |
| `ShaclConstraint` / `ConstraintType` | **SHOULD** best-effort map from `sh:sourceConstraintComponent` (or backend analogue). **MAY** use a generic constraint row when unmappable, **never** drop the violation. |
| `shapeUri`, `violationCode`, `explanation`, `suggestedFix` | **MAY** be absent; absence **must not** drop the row. |

Optional CI may run **identical manifest tests** against native and a bridge and diff normalized reports to catch mapping regressions; the **native** engine remains the **release gate** for Kastor’s own conformance claims.

---

## 11. Capabilities and profiles

**`ValidatorCapabilities`** should evolve to list **spec modules** (Core, SPARQL, Rules, RDF 1.2 triple terms, streaming) with **fine-grained booleans** (or a small nested struct) so [§7](#7-provider-resolution-required-hardening) `AUTO` and bridges cannot lie by omission. Minimum set to align with [§5.1](#51-rdf-12-triple-terms-native-policy): **`supportsRdf12TripleTermsInData`**, **`supportsRdf12TripleTermsInShapeParameters`** (plus existing coarse flags until retired).

**v2 evolution (non-normative):** as more SHACL 1.2 facets gain independent capability switches, a **fixed struct of booleans** risks bit-soup. Consider migrating to a **`Set<URI>` or typed feature IRIs** (e.g. `https://geoknoesis.com/kastor/cap#rdf12-triple-terms-data`) for open-ended registration—trades compile-time exhaustiveness for extensibility. Decide after P1c based on real integrator churn.

**`ValidationProfile`:** Prefer a **`sealed` hierarchy** (e.g. `object Core : Profile`, `object SparqlExtensions : Profile`, `data class Custom(val iri: String) : Profile`) over a flat `enum` once vendor-specific or URI-identified profiles appear. **Migration order (P0a or P0a.1):** introduce the **sealed hierarchy first**, then implement **`enum class ValidationProfile`** (or the public enum) as a **thin façade** delegating to it—easier than retrofitting sealed types under an existing enum. Preserve binary-friendly enum names for integrators. If integrator coordination makes this risky, ship **`P0a.1`** (profile migration only) immediately after core **P0a** registry/exceptions/capabilities land ([§14](#14-roadmap-engineering-phases)).

**RDF 1.2 triple terms** in `ValidatorCapabilities` should track [§5.1](#51-rdf-12-triple-terms-native-policy) for native vs bridge truthfulness.

---

## 12. Relationship to Kastor Gen

[Kastor Gen](../../kastor-gen/README.md) may continue to use **`ValidationContext`** and engine-specific adapters for **materialization-time** validation. Repository-level **`ShaclValidator`** is the right abstraction for **whole-graph** or **dataset** validation and for tooling that already works in `RdfGraph` space.

**Two entry points are intentional, not debt:** Gen validates **during materialization** with editor/pipeline context; `ShaclValidator` validates **RDF-shaped artifacts** at repository or service boundaries. They serve different **use cases** in the same system.

**Convergence (roadmap-owned, not “long term”):**

| Phase | Gen / `ShaclValidator` integration |
|-------|--------------------------------------|
| **P7** | **Shared** graph conversion utilities (`rdf-jena` / `rdf-rdf4j`) and, where feasible, **optional** delegation from Gen to `ShaclValidator` behind the same `ValidationProfile` policy—without removing `ValidationContext` until Gen callers are migrated. |

Exit criterion for P7: duplicated conversion code **removed** or thin-wrapped; documented **when** to choose each entry point.

---

## 13. Testing strategy

**What counts as “conformance”**

- **W3C SHACL 1.2 test manifests** (Core first, then other layers) are the **primary** gate for labeling a release “SHACL 1.2 Core conformant” (etc.). The public suite is **not exhaustive** for deployment edge cases (dataset wiring, imports, bridge fidelity, recursion policies).
- A **supplementary internal test suite** (Kotlin JUnit) **must** cover: recursion and SCC handling, `owl:imports` toggles, **`sh:deactivated`** semantics ([§9.1](#91-recursion-deactivation-and-logical-nesting), including **non-inheritance** across `sh:node` / `sh:property`), named-graph defaults, bridge MUST/MAY mapping ([§10.1](#101-bridge-to-kastor-report-fidelity-normative)), and regression cases from production incidents.

| Layer | Responsibility |
|-------|----------------|
| **Unit tests** | Path algebra, constraint dispatch, message and severity mapping |
| **W3C SHACL test manifests** | Primary labeled-conformance gate for the **native** provider |
| **Internal supplementary suite** | Spec-adjacent and product edge cases not covered by manifests: recursion, imports, datasets ([§9](#9-native-engine-architecture-summary)), bridge fidelity ([§10.1](#101-bridge-to-kastor-report-fidelity-normative)), production regressions |
| **Bridge tests** | Round-trip a small fixed corpus for each bridge artifact |
| **Native–bridge parity** | **Optional but owned:** diff **normalized** reports (see [§13.1](#131-parity-report-normalization)) for a fixed manifest subset; default = **nightly** on `main` and **required on release branches** for published bridge modules; **not** a per-PR gate unless the PR touches that bridge (then **required**). Document the job owner in `benchmarks/shacl/jmh/` or bridge `README`. |
| **Performance** | JMH micro-benchmarks on hot paths; macro scenarios vs Jena/RDF4J on fixed hardware profile; allocation tracking; large `targetClass` and path-heavy shapes; optional parallel validation gates |

### 13.1 Parity report normalization

Textual diffs of `ValidationReport` or RDF **`sh:ValidationReport`** are insufficient: ordering, blank node IDs for paths/values, and equivalent blank focus nodes differ across engines. **Normalization contract (for parity jobs only):**

**In-memory `ValidationReport` track**

1. Map both sides to a common **canonical row model** (focus node IRI or canonical blank label, `sh:sourceConstraintComponent` IRI, `sh:resultPath` serialized with **RDFC-1.0** (or same canonicalization as structural digest) where the value is an RDF term, `sh:severity`, message template id + parameters **if** both engines expose stable template ids; otherwise omit `message` from the sort key below).
2. **Sort rows** lexicographically by `(focus, component, path, severity)` then **`message`** only when comparable; if messages are engine-specific prose, **drop `message` from the sort key** and compare messages only in a secondary diagnostic step.
3. Compare sorted sequences; first mismatch fails the job with a diff artifact attached to CI.

**RDF `sh:ValidationReport` track (P1c-era):** parity on serialized RDF is **stricter** (`sh:value` term types, literal facets, blank-node identity in results). Extend step 1 with explicit **RDFC-1.0 serialization** for each `sh:ValidationResult` resource and compare **canonical Turtle or N-Quads** lines, or normalize to the same row model after **RDF→row** projection. Specify in the parity job README when bridge jobs promote to RDF-level diffs.

Implement normalization **once** in test infrastructure under `rdf/shacl/validation` or `benchmarks/shacl/jmh/`; do not require each bridge to hand-roll.

---

## 14. Roadmap (engineering phases)

Phases below are **ordered**; each should add **exit criteria** in the issue tracker (e.g. “P1a done = X tests green + no P0a regressions”) when work starts.

| Phase | Deliverable |
|-------|-------------|
| **P0a** | Registry: `providerId`, `EnginePreference`, `priority`, failure semantics ([§7](#7-provider-resolution-required-hardening)); typed **`ShaclValidationException`** hierarchy ([Appendix A](#appendix-a-contract-surfaces-and-source-of-truth)); granular **`ValidatorCapabilities`** + `AUTO` satisfaction ([§7](#7-provider-resolution-required-hardening), [§11](#11-capabilities-and-profiles)); nested **`ValidationConfig`** value types (`CacheConfig`, `StreamingConfig`, `DatasetConfig`) ([Appendix A](#appendix-a-contract-surfaces-and-source-of-truth)). **Optional split:** if sealed **`ValidationProfile`** migration blocks integrators, land **`P0a.1`** immediately after for profile types only ([§11](#11-capabilities-and-profiles)). |
| **P0b** | **Benchmark harness** (JMH + macro corpus), baselines under `benchmarks/shacl/jmh/baselines/`, CI thresholds ([§9.3](#93-performance-engineering-jvm)); does **not** block P1a on functional work |
| P1a | Native: **compile** shapes → `CompiledShapeGraph`, **full path engine**, **non-SPARQL** Core constraints + unit/W3C slice coverage |
| P1b | Native: **full SHACL 1.2 Core** including logical operators, qualified value shapes, remaining Core components, and **triple-term shape parameters** per [§5.1](#51-rdf-12-triple-terms-native-policy) |
| P1c | Native: RDF **`sh:ValidationReport`** emission and **parity** checks vs in-memory `ValidationReport` |
| P2 | SPARQL Extensions via Kastor SPARQL |
| P3 | **Node Expressions public API** (for embedders such as Klotho Lens `ShaclNodeExpression`) — **before P4** because product embeddings depend on it; **not** because the Node Expressions TR is more stable than Rules (it is not); track FPWD drift via pinned WD + capability flags. |
| P4 | Rules + integration with reasoning / entailment configuration |
| P5 | Optional `shacl-validation-jena` / `rdf4j` modules shipping `ShaclValidatorProvider` |
| P6 | Streaming API (`Flow`), **incremental** validation where spec and correctness constraints allow ([§9.4](#94-streaming-and-incremental-validation-scope)) |
| P7 | Gen / `ShaclValidator` **shared adapters** and documented convergence ([§12](#12-relationship-to-kastor-gen)) |

---

## 15. References

- [SHACL 1.2 Core](https://www.w3.org/TR/shacl12-core/) (W3C Data Shapes WG)
- [SHACL 1.2 SPARQL Extensions](https://www.w3.org/TR/shacl12-sparql/)
- [SHACL 1.2 Node Expressions](https://www.w3.org/TR/shacl12-node-expr/) — **vocabulary split (FPWD-era):** node-expression functions may appear under the Node Expressions namespace (e.g. `shnex:` in drafts) while SPARQL-derived functions use `sparql:` (or the document’s normative prefixes). The native parser and SPARQL bridge **must** track the **published QName → IRI** mapping for the pinned WD date, not assume a single `sh:` namespace for all builtins. **Note:** W3C occasionally renames TR paths during the Recommendation track—re-verify this URL when bumping the pinned WD snapshot (at least annually).
- [SHACL 1.2 Rules](https://www.w3.org/TR/shacl12-rules/) (as published by the WG)
- Kastor feature overview: [SHACL validation](../features/shacl-validation.md)

---

## Appendix A. Contract surfaces and source of truth

**Authority split**

| Kind of statement | If doc vs code disagree |
|-------------------|-------------------------|
| **Policy** (this document: §7 failure modes, §9.1 recursion, §9.2 dataset errors, §10.1 fidelity, digest rules, …) | **Code is wrong** until an issue/PR aligns implementation, or the doc is explicitly revised after team agreement. |
| **Signatures** (method names, parameter lists, existing `data class` fields) | **Doc is wrong**; update this appendix and prose to match `rdf/shacl/validation`. |
| **Documented throwable behavior** of public API methods (which `ShaclValidationException` subclasses may be thrown and when) | **Policy**; implementation and **KDoc `@throws`** must match this document. Throwing `IllegalStateException` where this doc requires `ShapesGraphNotFoundException` is **wrong code**. |

Authoritative **Kotlin types** today live in the **`rdf/shacl/validation`** module (`com.geoknoesis.kastor.rdf.shacl`). This appendix lists the **application boundary** and **planned** additions.

| Surface | Source file (relative to `rdf/shacl/validation/src/main/kotlin/.../rdf/shacl/`) | Role |
|---------|---------------------------------------------------------------------|------|
| `ShaclValidatorProvider` | `ShaclValidatorProvider.kt` | Provider SPI: `getType()`, `createValidator`, `getCapabilities()`, profiles |
| `ShaclValidator` | `ShaclValidatorProvider.kt` | Primary port: `validate`, `validateResource`, `conforms`, … |
| `ValidationConfig` | `ValidationConfig.kt` | Timeouts, `ValidationProfile`, flags; **planned fields** (see table below) |
| `ValidationReport`, `ValidationViolation`, … | `ValidationResults.kt` | Portable report model |
| `ValidatorRegistry`, `ShaclValidation` | `ValidatorRegistry.kt`, façade package | Discovery + construction |

**Planned `ShaclValidationException` hierarchy (all extend common base)**

- **`ShaclValidationException`** — abstract or concrete base for **caller/registry/shapes-setup** failures (not a normal `ValidationReport` outcome). Existing **`ValidationException`** in `MemoryShaclValidator.kt` should be **retired or subclass** this hierarchy during P0a so callers have one stable catch at API boundaries.
- **`ProviderNotFoundException`**, **`UnsupportedProfileException`** — registry ([§7.1](#71-failure-semantics-normative)).
- **`ShapesGraphNotFoundException`** — declared shapes reference **missing** from the dataset (no triples to load). **`ShapesGraphAccessException`** — resolution **attempted** but failed (network, I/O, policy denial when `allowImportFetch` is true, etc.). See [§9.2](#92-dataset-named-graphs-and-shape-graph-imports).
- **`StaleShapesGraphTagException`** — compile cache tag mismatch ([§9.3](#93-performance-engineering-jvm)).

**Planned `ValidationConfig` shape (P0a)**

Prefer **nested value types** (e.g. `CacheConfig`, `StreamingConfig`, `DatasetConfig`) so defaults stay grouped and the public `ValidationConfig` does not accumulate a dozen top-level fields. The flat table below is the **logical** surface; map to nested types in the implementing PR.

**Planned `ValidationConfig` fields** (names stable for discussion; finalize in PR to `ValidationConfig.kt`)

| Field | Purpose |
|-------|---------|
| `providerId: String?` | Force provider ([§7](#7-provider-resolution-required-hardening)) |
| `enginePreference: EnginePreference` | `NATIVE_FIRST`, `BRIDGE_FIRST`, `AUTO` |
| `shapesGraphVersion: String?` | Opt-in compile-cache tag ([§9.3](#93-performance-engineering-jvm)) |
| `shapesDigestMode: enum` | `SHAPES_STRUCTURAL_DIGEST_V1` (default), `SHAPES_RDF_CANONICAL_DIGEST` |
| `maxRecursionDepth: Int` | Recursion guard ([§9.1](#91-recursion-deactivation-and-logical-nesting)); default **`64`** |
| `maxPerFocusBuffer: Int` | Streaming qualified-value buffer cap ([§9.4](#94-streaming-and-incremental-validation-scope)) |
| `streamingBufferPolicy: enum` | e.g. `BATCH_FALLBACK`, `SKIP_WITH_WARNING` when `maxPerFocusBuffer` exceeded ([§9.4](#94-streaming-and-incremental-validation-scope)) |
| `resolveOwlImports: Boolean` | Default **false** ([§9.2](#92-dataset-named-graphs-and-shape-graph-imports)) |
| `maxImportDepth: Int` | Cap for transitive `owl:imports` on shapes graph ([§9.2](#92-dataset-named-graphs-and-shape-graph-imports)) |
| `allowImportFetch: Boolean` | Default **false** — no network for imports unless true ([§9.2](#92-dataset-named-graphs-and-shape-graph-imports)) |
| Named graph IRIs / dataset roles | As in [§9.2](#92-dataset-named-graphs-and-shape-graph-imports) |

**Also planned (types):** `EnginePreference`, `EngineMetadata`; **`priority: Int`** on `ShaclValidatorProvider` ([§6](#6-public-integration-seam)–[§7](#7-provider-resolution-required-hardening), [§10](#10-bridge-adapters-external-engines)).

Integrators should treat **`META-INF/services`** registration and **`ValidationConfig`** defaults as part of the public contract once documented in the user guide.

---

## Document v2.0 refresh after P0a (non-normative)

**After P0a ships** (and optionally **P0a.1**), cut **v2.0** of this document so prose matches shipped types. Suggested checklist—**not** an additional design round:

1. **Status block:** Add **implementation-backed** wording and a **git tag or commit** (or release version) for the `rdf/shacl/validation` surface the doc describes.
2. **Appendix A:** Turn **planned** rows into **current** rows; add **file paths** (and optionally line ranges) for `ShaclValidationException` hierarchy, nested `ValidationConfig`, granular `ValidatorCapabilities`, default registry **INFO** logging.
3. **§13.1 RDF track:** When P1c lands, specify literal/datatype normalization, **BCP 47** language-tag comparison, and **`sh:value` / blank-node** handling for `sh:ValidationReport` parity (extend beyond the in-memory track).
4. **§11:** Revisit **capability URIs vs booleans** with real integrator data after P1c ([§11](#11-capabilities-and-profiles) v2 note).
5. **`ValidationProfile`:** Treat **`Custom(iri: String)`** (or equivalent) as the **v2 stability anchor** for the SHACL 1.2 family—profile churn should slow until a future major spec.

Further **prose-only** revisions have **diminishing returns** once v1.6 is frozen; prefer **feedback from P0a–P1c PRs** over speculative editing.

---

## Document history

| Revision | Summary |
|----------|---------|
| 1.7 | **v2.0 refresh checklist** (post-P0a); Contents link; meta only—no normative policy change |
| 1.6 | Re-review close-out: **INFO log MUST** (default registry) / SHOULD (custom); **`maxRecursionDepth` 64** rationale; **`ShapesGraphNotFoundException` vs `ShapesGraphAccessException`**; **§9.3** bnode identity + ambiguous-case fallback to RDF canonical digest; **§11** v2 capability URI note + **P0a.1** optional profile split; **§13.1** in-memory vs RDF parity tracks + sort-key note; §15 TR URL maintenance note |
| 1.5 | Re-review nits: **AUTO** superset + granular triple-term capability bits + **INFO** observability; **§9.3** bnode digest from graph order (not parse order), **runner noise** sentence; **`maxRecursionDepth` default 64**; **`allowImportFetch`** default false; **§13.1** parity normalization contract; **§11** sealed-first migration; Appendix **throws = policy** row, nested `ValidationConfig`, `streamingBufferPolicy: enum`, `allowImportFetch`; P0a bullets expanded; tiebreaker rename note |
| 1.4 | Pass: restored bidirectional **status** authority; **Contents** nav; **AUTO** tied to `ValidatorCapabilities` + profile; **`CompiledShapeGraph`** called out as planned; §9.1 vs §13 **deactivation** wording aligned; RDF canonical digest wording; Appendix **ValidationException** migration + `owl:imports` config rows |
| 1.3 | Re-review: split policy vs signature authority (status + Appendix A); `SHAPES_STRUCTURAL_DIGEST_V1` / RDF canonical opt-in / `StaleShapesGraphTagException`; §9.1 deactivation + `maxRecursionDepth`; §9.2 shapes-graph **engine exceptions**; §9.3 JMH forks + `kotlinx.coroutines`; §9.4 streaming buffer policy; §5.1 RDF 1.2 triple terms; §11 enum→sealed migration note; §13 parity row + anchor fix; P0a/P0b + P3 rationale; TR URL `shacl12-node-expr`; exception hierarchy |
| 1.2 | Review hardening: status vs code contract; §7 failure semantics + tiebreaker + AUTO caveat; §9.1–9.4 recursion/dataset/streaming; compile cache key; benchmark thresholds + baselines path; §10.1 bridge fidelity MUST/MAY; §11 sealed profiles; §12 P7 Gen convergence; §13 conformance + supplementary suite; P1 split; Appendix A |
| 1.1 | DDD bounded context + Clean Architecture layering; ports/adapters; native JVM performance goals and optimization strategies; testing/roadmap alignment |
| 1.0 | Initial architecture: native + pluggable providers, module split, registry policy, testing |
