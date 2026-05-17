# Ontology Quality (`onto-quality`)

**Ontology quality** in Kastor is delivered through the **`:tools:onto-quality`** Gradle module: curated **SHACL 1.2** shape libraries plus a thin **`QualityChecker`** API on top of **`rdf/shacl-validation`**. Reports are **`QualityReport`** instances with **`QualityFinding`** rows that include **category**, optional **pitfall** reference (for example OOPS! codes), and **tier** (structural vs semantic).

This complements generic [SHACL Validation](shacl-validation.md): here the **shapes and vocabulary** (`oqsh:`) are **productised** for ontology maintainers, not ad hoc data constraints.

## Modules

| Module | Role |
|--------|------|
| **`:tools:onto-quality`** | `QualityChecker`, `BundledCatalogs`, bundled Turtle under `src/main/resources/shapes/` |
| **`:tools:onto-quality-embed`** | `SemanticEnricher`, **configurable** BERT-style ONNX embeddings (bundled MiniLM or local ONNX + `tokenizer.json`), `oqsh:semanticallyCloseTo` materialization |
| **`:tools:onto-quality-cli`** | **`onto-qa`** — `check`, `enrich`, `pipeline` (optional **`--explain`** LLM tier when `KASTOR_ONTO_QUALITY_LLM=true`) |
| **`:tools:onto-quality-llm-koog`** | **v0.3:** `DefaultQualityExplanationEnricher` / `qualityExplanationEnricher`, `LlmExplanationConfig` (OpenAI / Anthropic / Ollama; `modelId` / `modelPreset`) |

Published Maven coordinates follow `com.geoknoesis.kastor:onto-quality` and `onto-quality-embed` (see [Installation](../getting-started/installation.md) / BOM).

## Bundled catalogues

1. **OWL Ontology Quality** — structural / metadata OWL pitfalls (OOPS!-aligned where tagged)
2. **SKOS Taxonomy Validation** — SKOS constraint shapes
3. **Data Quality Constraints** — DQ-SHACL-style constraints
4. **Embedding-based Ontology Quality** — consumes **`oqsh:semanticallyCloseTo`** (and optional drift scores) from **`SemanticEnricher`**
5. **Modern Ontology Engineering** — FAIR-style URI, licence, label and multilingual checks (`N…` pitfalls)
6. **RDF 1.2 Conformance** — RDF 1.2–specific hygiene

**Preset for published SKOS vocabularies:** `BundledCatalogs.SKOS_VOCABULARY_QC` bundles SKOS + data-quality + modern-engineering + RDF12 (skips OWL-quality). CLI: `--catalog skos-vocabulary` (or `skos-vocabulary-embed` after `pipeline` / enrich for embedding shapes).

Shape sources live in the repository as Turtle:

- `tools/onto-quality/src/main/resources/shapes/*.ttl`

## Tiers

- **Structural** — SHACL-only: class/property patterns, metadata, SKOS, DQ (no embeddings).
- **Semantic** — Requires a prior **enrichment** step that adds similarity (and optionally label–definition drift) triples. Shapes are tuned to **not** false-positive when enrichment is absent.
- **LLM explanations (v0.3)** — Optional **Koog** layer: advisory text for existing findings (`ExplainedQualityReport`). Does not change SHACL outcomes.

Validation still runs entirely through the same **`ShaclValidator`** stack as the rest of Kastor; see [SHACL validation architecture](../design/shacl-validation-architecture.md).

## How to use

Task-oriented steps, Gradle coordinates, CLI examples, and links to **CALIBRATION.md**:

- **[How to Check Ontology Quality](../guides/how-to-ontology-quality.md)**

## Source and calibration

- [Module README](../../../tools/onto-quality/README.md) — roadmap, threshold tuning, CI flags
- [CALIBRATION.md](../../../tools/onto-quality/CALIBRATION.md) — OOPS! corpus results (structural + semantic)
