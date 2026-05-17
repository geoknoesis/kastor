# Design: RDF reasoning in Kastor and onto-quality v0.4

This document describes how **RDF reasoning** is exposed in Kastor and how **onto-quality v0.4** uses it to run SHACL validation over a **materialized** (asserted + entailed) graph.

---

## Part 1 — Reasoning in the Kastor RDF stack

### Goals

- Provide a **single facade** for “run a reasoner on an [RdfGraph](../../../rdf/core/src/main/kotlin/com/geoknoesis/kastor/rdf/RdfTerms.kt)” without locking callers to one engine.
- Allow **multiple backends** (in-memory reference rules, Jena, RDF4J, …) via **provider registration**.
- Keep **core** free of heavyweight reasoner dependencies; live implementations live in modules such as `:rdf:reasoning`, `:rdf:jena`, `:rdf:rdf4j`.

### Main types (module `:rdf:reasoning`)

| API | Role |
|-----|------|
| [RdfReasoning](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/RdfReasoning.kt) | Factory: `reasoner(config)`, `reasoner(type)`, provider discovery helpers. |
| [RdfReasoner](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/RdfReasonerProvider.kt) | `reason(graph)`, `getInferredTriples`, `classify`, `isConsistent`, `validateOntology`. |
| [ReasonerConfig](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerConfig.kt) | `ReasonerType`, enabled rules, timeouts, streaming/batch hints, custom rules. |
| [ReasonerRegistry](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerRegistry.kt) | Registers [RdfReasonerProvider](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/RdfReasonerProvider.kt) instances; **ServiceLoader** discovery from `META-INF/services/...RdfReasonerProvider`. |
| [ReasoningResult](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasoningResults.kt) | Original graph, **inferred triples**, optional classification, consistency, timings. |

### Providers shipped in this repo

- **Memory** ([MemoryReasonerProvider](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/providers/MemoryReasonerProvider.kt)): lightweight **RDFS** subset (e.g. transitivity on `rdfs:subClassOf`, domain/range). Useful for tests and fast smoke checks; **not** a full RDFS or OWL implementation.
- **Jena** ([JenaReasonerProvider](../../../rdf/providers/jena-reasoning/src/main/kotlin/com/geoknoesis/kastor/rdf/jena/reasoning/JenaReasonerProvider.kt)): **RDFS**, **OWL “Micro”** (exposed as [ReasonerType.OWL_EL](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerConfig.kt) in config), **OWL_RL** placeholder, **CUSTOM** rules. This is the **reference implementation** for realistic entailment when Jena is on the classpath.
- **RDF4J** ([Rdf4jReasonerProvider](../../../rdf/providers/rdf4j-reasoning/src/main/kotlin/com/geoknoesis/kastor/rdf/rdf4j/reasoning/Rdf4jReasonerProvider.kt)): optional alternative backend.

### Choosing a backend

At runtime, `ReasonerRegistry.createReasoner(config)` picks **one** registered provider that declares support for the requested [ReasonerType](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerConfig.kt). When **several** providers support the same type, selection is **not** guaranteed to be stable; callers that need **deterministic semantics** should **instantiate a concrete provider** (as onto-quality does for v0.4; see Part 2).

### Relation to SHACL validation

SHACL validation in Kastor operates on an [RdfGraph](../../../rdf/core/src/main/kotlin/com/geoknoesis/kastor/rdf/RdfTerms.kt). The SHACL engine does **not** automatically perform OWL/RDFS materialization; if rules depend on entailed triples (e.g. `rdfs:subClassOf` implying `rdf:type` for individuals), those triples must appear in the **data graph passed to validation** (or shapes must be written to match asserted data only).

---

## Part 2 — onto-quality v0.4: reasoning before SHACL

### Problem

[Largely structural SHACL catalogs](../../../tools/onto-quality/library/README.md) often assume **explicit** `rdf:type` arcs. RDFS (and light OWL) can **entail** additional type and property assertions. Without materialization, **conformance can disagree** with an editor or triple store that applies the same entailment regime.

### Approach

1. **Optional expansion step**: merge **asserted** triples with **inferred** triples from a configured profile.
2. Run [QualityChecker.check(graph, profile)](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/QualityChecker.kt) so expansion and **reasoning preflight** (consistency) share one materialization pass where supported.
3. **Do not** change shape semantics or pitfall catalogues; v0.4 is **operational only**.

### API (module `:tools:onto-quality`)

| API | Meaning |
|-----|---------|
| [OntoQualityReasoningProfile](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/reasoning/OntoQualityReasoning.kt) | `NONE`, `RDFS`, `OWL_MICRO`, `HERMIT` (OWL 2 DL via HermiT when module present). |
| [OntoQualityReasoning.expand](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/reasoning/OntoQualityReasoning.kt) | `expand(graph, profile)` → materialized [RdfGraph](../../../rdf/core/src/main/kotlin/com/geoknoesis/kastor/rdf/RdfTerms.kt). |
| [QualityChecker.check(graph, profile)](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/QualityChecker.kt) | Expand (single `reason()` per profile), run SHACL, merge **OWL inconsistency** rows (HermiT) as violations when reported. |
| [OntoQualityReasoning.expand(graph, ReasonerConfig)](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/reasoning/OntoQualityReasoning.kt) | Advanced: custom timeouts/rules when you depend on `:rdf:reasoning`. |

### Why Jena is fixed for this step

onto-quality already validates with the **Jena-backed** SHACL stack. v0.4 uses [JenaReasonerProvider](../../../rdf/providers/jena-reasoning/src/main/kotlin/com/geoknoesis/kastor/rdf/jena/reasoning/JenaReasonerProvider.kt) **directly** so that:

- **RDFS** materialization includes the usual subclass-based instance typing (not only transitivity on class axioms).
- Results are **aligned** with common Jena+RDFS expectations.
- Behavior does not depend on which provider `ReasonerRegistry` happens to return first.

### CLI (`onto-qa`)

On **`check`** and **`pipeline`**:

- `--reasoner none` (default) — no expansion.
- `--reasoner rdfs` — RDFS materialization before SHACL.
- `--reasoner owl-micro` — Jena OWL Micro (`ReasonerType.OWL_EL` binding).
- `--reasoner hermit` — HermiT-backed OWL 2 DL materialization (requires `:rdf:reasoning-hermit`); inconsistent ontologies fail quality checks via merged **K07** rows when pitfall metadata is present (included in **`QualityChecker.default()`** / CLI `--catalog all`).

When a non-`none` profile is selected, the CLI logs the active reasoning profile name.

### Limitations and non-goals

- **Not** full OWL 2 DL in the Jena-only paths; use `hermit` (or a custom [ReasonerConfig](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerConfig.kt)) for DL-shaped materialization.
- **Not** streaming/graph-store-specific incremental reasoning; expansion builds an in-memory merged graph via [JenaBridge.createEmptyModel](../../../rdf/providers/jena/src/main/kotlin/com/geoknoesis/kastor/rdf/jena/JenaBridge.kt).
- **Not** changing [SHACL validation architecture](shacl-validation-architecture.md) itself.

### References

- End-user feature overview: [Ontology quality — reasoning tier](../features/ontology-quality.md)
- v0.3 (LLM explanations): [onto-quality-v0.3-llm-explanations.md](onto-quality-v0.3-llm-explanations.md) (orthogonal to v0.4)
- Broader reasoning feature page: [Reasoning](../features/reasoning.md)
- HermiT (OWL 2 DL) module and bridge: [HermiT reasoning integration](hermit-reasoning-integration.md)
- Operational and modeling traps when wiring reasoning: [Reasoning ontology pitfalls](reasoning-ontology-pitfalls.md)
