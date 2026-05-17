# Design: HermiT OWL reasoning in Kastor

This note describes **HermiT** (OWL 2 DL) integration in Kastor alongside the [reasoning provider model](reasoning-in-kastor.md) and [onto-quality v0.4](reasoning-in-kastor.md#part-2--onto-quality-v04-reasoning-before-shacl).

**Status:** Implemented in `:rdf:reasoning-hermit` (`HermitRdfReasonerProvider`, `HermitRdfReasoner`). **onto-quality** pins this provider for `OntoQualityReasoningProfile.HERMIT`, merges materialized triples before SHACL, and surfaces **globally inconsistent** ontologies as **Kastor K07** findings when registry metadata is loaded (see [reasoning ontology pitfalls](reasoning-ontology-pitfalls.md) §5).

---

## 1. Goals

1. Expose **HermiT-backed** `RdfReasoner.reason(...)` / inferred-triple materialization for graphs that benefit from **OWL 2 DL** entailments (beyond Jena RDFS / OWL Micro).
2. Fit the existing **`RdfReasonerProvider` + ServiceLoader** pattern so callers can opt in without forking SHACL or core RDF APIs.
3. Keep **core** and **default** Kastor installs free of HermiT; ship integration in a **dedicated Gradle module** (fat optional artifact).
4. Allow **onto-quality** (or any tool) to run SHACL on a **materialized** graph after HermiT closure, with **explicit** profile selection (same determinism principle as Jena-only v0.4).

## 2. Non-goals

- Replacing Jena for **RDFS** quick paths or making HermiT the default everywhere.
- Full **incremental** reasoning over persistent **RdfRepository** graphs (first version may be batch materialization only).
- Guaranteeing **complete RDF 1.2** round-trip through OWL API (see §7).
- SPARQL **entailment regime** in the query engine (out of scope unless a separate design extends SPARQL).

---

## 3. Architecture

### 3.1 New module

Suggested name: **`:rdf:reasoning-hermit`** (or `:rdf:reasoning-owlapi-hermit` if multiple OWL API backends share code).

| Responsibility | Notes |
|----------------|--------|
| **Dependency boundary** | Pull in HermiT + a single **pinned OWL API** version; isolate from Jena/RDF4J optional trees where possible. |
| **`HermitReasonerProvider`** | Implements `RdfReasonerProvider`; `getType()` e.g. `"hermit"`. |
| **`HermitRdfReasoner`** | Implements `RdfReasoner`; orchestrates load → classify / materialize → `ReasoningResult`. |
| **Graph bridge** | `RdfGraph` ↔ OWL API `OWLOntology` (see §4). |
| **META-INF/services** | Register `com.geoknoesis.kastor.rdf.reasoning.RdfReasonerProvider`. |

Consumers add **one** implementation dependency; `ReasonerRegistry` discovers the provider like Jena/RDF4J/Memory today.

### 3.2 Reasoner type mapping

Extend usage of [ReasonerType](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerConfig.kt):

- Prefer **`ReasonerType.HERMIT`** for HermiT-specific configuration and documentation.
- Optionally treat **`ReasonerType.OWL_DL`** as “default DL engine” with `ReasonerConfig.parameters["engine"] = "hermit"` if Pellet or others are added later.

`HermitReasonerProvider.isSupported` should return **`HERMIT`** (and **`OWL_DL`** only if explicitly documented as an alias).

### 3.3 Configuration (`ReasonerConfig`)

Reuse [ReasonerConfig](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerConfig.kt):

- **`timeout`** — map to HermiT / classification limits where the API allows.
- **`parameters`** — optional: disable classification, realisation only, number of workers, diagnostics.
- **`includeAxioms` / classification** — map to HermiT’s classification API vs “materialize triples only”.

Preset factory (optional):

```kotlin
// Illustrative only
ReasonerConfig.hermitDl(timeout = Duration.ofMinutes(2))
```

---

## 4. `RdfGraph` ↔ OWL API bridge

HermiT operates on **OWL ontologies**, not arbitrary RDF graphs. The bridge is the highest-risk area.

### 4.1 Recommended strategy (v1)

1. Serialize **`RdfGraph`** to **RDF/XML** or **Turtle** via existing Kastor/Jena (or Rio) writers.
2. Load with **OWL API** `OWLOntologyManager.loadOntologyFromOntologyDocument` (stream or temp file).
3. Run HermiT (`ReasonerFactory.createReasoner(...)`).
4. For **materialization**, use OWL API patterns to export **inferred axioms** or use a documented **RDF export** of the inferred ontology, then parse back to **`RdfGraph`**.

### 4.2 Alternatives (later)

- **Stream-based parsers** to avoid large temp files for huge graphs.
- **Partitioning**: reason over TBox + selected ABox imports only.

### 4.3 Identity of nodes

- **IRIs** — stable.
- **Blank nodes** — OWL API RDF parsers may **skolemize** or rename anonymouse nodes; merging inferred triples back must preserve **or explicitly document** that finding refs may shift relative to the original `RdfGraph`. Prefer documenting **“HermiT path is IRI-safe; blank-node-heavy graphs are best-effort”** for v1.

---

## 5. Materialization semantics

SHACL and onto-quality need a **single merged RDF graph**: asserted ∪ inferred.

1. **Baseline:** `out = original ∪ inferredTriples` as today in onto-quality Jena path.
2. **Dedup:** treat triple equality as RDF term equality; optionally normalize literals.
3. **Size guard:** optional `ReasonerConfig.materializationThreshold` / cap on inferred triple count with a clear **abort or warn** policy for CI.

**Consistency:** HermiT can detect **unsatisfiable classes / inconsistent ontology**. Map to `ConsistencyResult` in [ReasoningResult](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasoningResults.kt); do not silently drop errors.

---

## 6. Dependencies and coexistence with RDF4J / Jena

- Kastor today pins **RDF4J 5.3.x** for RDF4J modules; **HermiT does not replace RDF4J**.
- Resolve **one** OWL API version on the classpath for HermiT + any Rio OWL helpers; use Gradle **constraints** or BOM if multiple libraries pull OWL API.
- **Jena** and **HermiT** can coexist; they do not share a native embedding. Avoid duplicating **different OWL API majors** across modules.

Document the **supported matrix** (OWL API x HermiT version) in the module README.

---

## 7. onto-quality integration

**Principle:** Same as Jena v0.4 — **explicit engine**, no accidental registry ambiguity.

Options:

| Option | Behavior |
|--------|----------|
| **A (recommended)** | Add `OntoQualityReasoningProfile.HERMIT_DL` that constructs `ReasonerConfig(reasonerType = HERMIT, ...)` and calls a **small factory** inside `:tools:onto-quality` that selects **`HermitReasonerProvider()`** directly (mirror `JenaReasonerProvider()` pinning). |
| **B** | Use `RdfReasoning.reasoner(config)` only when `config.reasonerType == HERMIT` and document that **only the HermiT module** must be on the classpath. |

CLI (illustrative): `--reasoner hermit` gated on the optional CLI fat-jar or explicit dependency.

---

## 8. Testing

- **Unit:** tiny ontologies with known entailments (e.g. subclass instance typing, simple property chains).
- **Negative:** inconsistent ontology → `ConsistencyResult.isConsistent == false`.
- **Regression:** dependency convergence task or lockfile check — no duplicate OWL API.
- **Optional:** snapshot count of inferred triples for a fixed fixture (fragile across HermiT minor releases; use ranges or structural asserts).

---

## 9. Rollout phases

| Phase | Deliverable |
|-------|-------------|
| **P0** | `:rdf:reasoning-hermit`, `HermitReasonerProvider`, `reason(graph)` returning `ReasoningResult` with inferred triples + consistency. |
| **P1** | onto-quality profile + CLI flag; design doc update in [reasoning-in-kastor.md](reasoning-in-kastor.md). |
| **P2** | Performance tuning, streaming load, stricter blank-node policy. |

---

## 10. References

- [Reasoning in Kastor](reasoning-in-kastor.md) — provider model and onto-quality v0.4 context.
- [RdfReasonerProvider](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/RdfReasonerProvider.kt) / [ReasonerRegistry](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerRegistry.kt).
- HermiT / OWL API upstream documentation (versions pinned in Gradle when implementing).
