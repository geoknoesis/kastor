# Ontology Quality (`onto-quality`)

**Ontology quality** in Kastor is delivered through the **`:tools:onto-quality`** Gradle module: curated **SHACL 1.2** shape libraries plus a thin **`QualityChecker`** API on top of **`:rdf:shacl-validation`**. Reports are **`QualityReport`** instances with **`QualityFinding`** rows that include **category**, optional **pitfall** reference (OOPS **P** codes, Kastor **K** codes, modern **N** codes, SKOS rules, conventions), and **tier** (**structural**, **semantic**, or **reasoning** where the catalogue marks it).

This complements generic [SHACL Validation](shacl-validation.md): here the **shapes and vocabulary** (`oqsh:` / `skvsh:` / `dqcsh:`) are **productised** for ontology maintainers, not ad hoc data constraints.

## Modules

| Module | Role |
|--------|------|
| **`:tools:onto-quality`** | `QualityChecker`, `BundledCatalogs`, bundled Turtle under `src/main/resources/shapes/` |
| **`:tools:onto-quality-embed`** | `SemanticEnricher`, **configurable** BERT-style ONNX embeddings (bundled MiniLM or local ONNX + `tokenizer.json`), `oqsh:semanticallyCloseTo` materialization; exact similarity search with limits scaled to the vocabulary size, or opt-in approximate LSH (`SimilaritySearchMode.ApproximateLsh`, CLI `--similarity-mode approximate`) |
| **`:tools:onto-quality-cli`** | **`onto-qa`** — `check`, `enrich`, `pipeline`, `metrics` (optional **`--explain`**, **`--reasoner`**; see below). Distinct exit codes: 0 ok, 1 findings at/above `--severity`, 2 parse error, 3 explanation failure with `--fail-on-explain-error`, 4 usage/configuration error, 5 runtime error; `onto-qa --debug` prints stack traces. Input is parsed with the file URI as base IRI, so relative IRIs resolve. See [How to Check Ontology Quality](../guides/how-to-ontology-quality.md#validation). |
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
- **Reasoning (v0.4)** — Optional **RDFS** / **OWL RL** (`OntoQualityReasoningProfile.OWL_RL`, CLI `--reasoner owl-rl`: Jena's OWL rule reasoner, `ReasonerType.OWL_RL`; not complete OWL 2 RL, and not Jena's OWL Micro rule set — the old `OWL_MICRO` / `owl-micro` names are deprecated aliases) or **OWL 2 DL** (HermiT) **materialization** before SHACL, using `QualityChecker.check(graph, OntoQualityReasoningProfile)`. HermiT also performs a **consistency preflight**: globally **inconsistent** ontologies produce **Kastor K07** findings merged into the same report (metadata from the OOPS pitfall registry). Tier on findings is **`REASONING`** where shapes declare `oqsh:tier_Reasoning` or for **K07**.
- **LLM explanations (v0.3)** — Optional **Koog** layer: advisory text for existing findings (`ExplainedQualityReport`). Does not change SHACL outcomes. Each request has a timeout. Only timeouts, HTTP 408/429/5xx and connection errors are retried, with jittered exponential backoff. The whole run is bounded (`maxTotalDuration`, CLI `--llm-max-duration`, default 600 s), and a circuit breaker stops after repeated identical non-retryable failures. Failed or skipped batches are recorded in `ExplainedQualityReport.failures` while successful batches are kept. Finding text, and the previous reply in the JSON repair request, is framed as untrusted JSON data; LLM output and ontology-derived text are Markdown-escaped in reports (no links, images or HTML).
- **Metrics (OQuaRE)** — `onto-qa --with-metrics` computes structural metrics on the **asserted** graph, even when `--reasoner` is set. `onto-qa metrics` prints the same report on its own (`--format text|markdown|json|turtle`; `--include owl|skos|graph` only with text or markdown). Formula and scoring tables are in the [metrics module README](../../../tools/onto-quality/metrics/README.md#formulas).
  **Changed in this release** (values are not comparable with earlier releases):
  - **NOCOnto** is the mean number of direct subclasses per class *that has subclasses*.
  - **CBOOnto** counts, per class, its direct superclasses plus the classes associated with it through property ranges (`rdfs:range` of a property whose domain is the class) or restriction fillers (`owl:someValuesFrom` / `owl:allValuesFrom` / `owl:onClass`), divided by **all** classes. NOCOnto and CBOOnto were previously the same number.
  - Hierarchy paths are measured from **owl:Thing**: roots have depth 1, and an isolated class forms one path of length 1. **DITOnto** and **LCOMOnto** are therefore one higher than before.
  - **TMOnto** scores use the bands 0 → 5, (0, 2] → 4, (2, 4] → 3, (4, 8] → 2, > 8 → 1. This is a documented deviation from the published OQuaRE band (≤ 2 → 5), which could never detect tangling because TMOnto is ≥ 2 whenever a class has several parents.
  - SKOS **definitionCoverage** counts only `skos:definition` (no longer `rdfs:comment`).
  - VoID **distinctObjectCount** keys literals by lexical form, datatype and language tag, so `"1"`, `"1"^^xsd:integer` and `"1"@en` are distinct objects.

Validation still runs entirely through the same **`ShaclValidator`** stack as the rest of Kastor; see [SHACL validation architecture](../design/shacl-validation-architecture.md).

## How to use

Task-oriented steps, Gradle coordinates, CLI examples, and links to **CALIBRATION.md**:

- **[How to Check Ontology Quality](../guides/how-to-ontology-quality.md)**
- **[Reasoning in Kastor](../design/reasoning-in-kastor.md)** — `--reasoner` / `OntoQualityReasoningProfile`

## Source and calibration

- [Module README](../../../tools/onto-quality/library/README.md) — roadmap, threshold tuning, CI flags, OOPS coverage summary
- [CALIBRATION.md](../../../tools/onto-quality/library/CALIBRATION.md) — OOPS! corpus (structural + semantic) and **HermiT K07** preflight
- [PITFALL_TRIAGE.md](../../../tools/onto-quality/library/docs/PITFALL_TRIAGE.md) — coverage stack and per-pitfall decisions
