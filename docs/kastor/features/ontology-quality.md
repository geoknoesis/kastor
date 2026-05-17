# Ontology Quality (`onto-quality`)

**Ontology quality** in Kastor is delivered through the **`:tools:onto-quality`** Gradle module: curated **SHACL 1.2** shape libraries plus a thin **`QualityChecker`** API on top of **`:rdf:shacl-validation`**. Reports are **`QualityReport`** instances with **`QualityFinding`** rows that include **category**, optional **pitfall** reference (OOPS **P** codes, Kastor **K** codes, modern **N** codes, SKOS rules, conventions), and **tier** (**structural**, **semantic**, or **reasoning** where the catalogue marks it).

This complements generic [SHACL Validation](shacl-validation.md): here the **shapes and vocabulary** (`oqsh:` / `skvsh:` / `dqcsh:`) are **productised** for ontology maintainers, not ad hoc data constraints.

## Modules

| Module | Role |
|--------|------|
| **`:tools:onto-quality`** | `QualityChecker`, `BundledCatalogs`, bundled Turtle under `src/main/resources/shapes/` |
| **`:tools:onto-quality-embed`** | `SemanticEnricher`, **configurable** BERT-style ONNX embeddings (bundled MiniLM or local ONNX + `tokenizer.json`), `oqsh:semanticallyCloseTo` materialization |
| **`:tools:onto-quality-cli`** | **`onto-qa`** — `check`, `enrich`, `pipeline` (optional **`--explain`**, **`--reasoner`**; see below) |
| **`:tools:onto-quality-llm-koog`** | **v0.3:** `DefaultQualityExplanationEnricher` / `qualityExplanationEnricher`, `LlmExplanationConfig` (OpenAI / Anthropic / Ollama; `modelId` / `modelPreset`) |

Published Maven coordinates follow `com.geoknoesis.kastor:onto-quality` and `onto-quality-embed` (see [Installation](../getting-started/installation.md) / BOM).

## Bundled catalogues

1. **OWL Ontology Quality** — structural / metadata OWL pitfalls (OOPS!-aligned where tagged); includes active **K01** (`owl:imports` cycle)
2. **SKOS Taxonomy Validation** — SKOS constraint shapes
3. **Data Quality Constraints** — DQ-SHACL-style constraints
4. **Embedding-based Ontology Quality** — consumes **`oqsh:semanticallyCloseTo`** (and optional drift scores) from **`SemanticEnricher`**
5. **Modern Ontology Engineering** — FAIR-style URI, licence, label and multilingual checks (`N…` pitfalls)
6. **RDF 1.2 Conformance** — RDF 1.2–specific hygiene
7. **OOPS! pitfall registry (documentation)** — **P01–P41** plus **Kastor K01–K07** text in deactivated `sh:NodeShape`s; merged automatically when you use **`QualityChecker.default()`** or **`BundledCatalogs.allWithOopsRegistry`**, so report rows can resolve full pitfall metadata without duplicating Turtle imports

**Preset for published SKOS vocabularies:** `BundledCatalogs.SKOS_VOCABULARY_QC` bundles SKOS + data-quality + modern-engineering + RDF12 (skips OWL-quality **and** the OOPS registry). For SKOS work you usually do not need **P/K** registry metadata; add **`OOPS_PITFALL_REGISTRY`** only if you want those labels in the report.

Shape sources live in the repository as Turtle:

- `tools/onto-quality/library/src/main/resources/shapes/*.ttl`

**CLI catalogue flag:** `owl-quality`, `skos-validation`, `data-quality`, `embedding-quality`, `modern-engineering`, `rdf12-quality`, `skos-vocabulary`, `skos-vocabulary-embed`, **`all`** (same as **`QualityChecker.default()`** — includes the OOPS registry).

## Tiers

- **Structural** — SHACL-only: class/property patterns, metadata, SKOS, DQ (no embeddings).
- **Semantic** — Requires a prior **enrichment** step that adds similarity (and optionally label–definition drift) triples. Shapes are tuned to **not** false-positive when enrichment is absent.
- **Reasoning (v0.4)** — Optional **RDFS** / **OWL Micro** (Jena) or **OWL 2 DL** (HermiT) **materialization** before SHACL, using `QualityChecker.check(graph, OntoQualityReasoningProfile)`. HermiT also performs a **consistency preflight**: globally **inconsistent** ontologies produce **Kastor K07** findings merged into the same report (metadata from the OOPS pitfall registry). Tier on findings is **`REASONING`** where shapes declare `oqsh:tier_Reasoning` or for **K07**.
- **LLM explanations (v0.3)** — Optional **Koog** layer: advisory text for existing findings (`ExplainedQualityReport`). Does not change SHACL outcomes.

Validation still runs entirely through the same **`ShaclValidator`** stack as the rest of Kastor; see [SHACL validation architecture](../design/shacl-validation-architecture.md).

## How to use

Task-oriented steps, Gradle coordinates, CLI examples, and links to **CALIBRATION.md**:

- **[How to Check Ontology Quality](../guides/how-to-ontology-quality.md)**
- **[Reasoning in Kastor](../design/reasoning-in-kastor.md)** — `--reasoner` / `OntoQualityReasoningProfile`

## Source and calibration

- [Module README](../../../tools/onto-quality/library/README.md) — roadmap, threshold tuning, CI flags, OOPS coverage summary
- [CALIBRATION.md](../../../tools/onto-quality/library/CALIBRATION.md) — OOPS! corpus (structural + semantic) and **HermiT K07** preflight
- [PITFALL_TRIAGE.md](../../../tools/onto-quality/library/docs/PITFALL_TRIAGE.md) — coverage stack and per-pitfall decisions
