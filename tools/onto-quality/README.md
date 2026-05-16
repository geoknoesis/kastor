# onto-quality

Ontology **quality checks** for Kastor: curated **SHACL 1.2** shape catalogues run through the
`:rdf:shacl-validation` engine. Results are surfaced as `QualityReport` / `QualityFinding` values with
**category** and **pitfall** metadata (where the catalogue defines it).

**Docs site:** [How to Check Ontology Quality](../../docs/kastor/guides/how-to-ontology-quality.md) · [Feature overview](../../docs/kastor/features/ontology-quality.md)

## Bundled catalogues

1. **OWL Ontology Quality** — `BundledCatalogs.OWL_QUALITY` — `/shapes/owl-quality-shacl.ttl`
2. **SKOS Taxonomy Validation** — `BundledCatalogs.SKOS_VALIDATION` — `/shapes/skos-validation-shacl.ttl`
3. **Data Quality Constraints** — `BundledCatalogs.DATA_QUALITY` — `/shapes/dq-constraints-shacl.ttl`
4. **Embedding-based Ontology Quality** — `BundledCatalogs.EMBEDDING_QUALITY` — `/shapes/embedding-quality-shacl.ttl` (v0.2 semantic tier; run `SemanticEnricher` first for similarity triples)
5. **Modern Ontology Engineering** — `BundledCatalogs.MODERN_ENGINEERING` — `/shapes/modern-engineering-shacl.ttl` (pitfall codes `N` — beyond OOPS!)
6. **RDF 1.2 Conformance** — `BundledCatalogs.RDF12_QUALITY` — `/shapes/rdf12-quality-shacl.ttl`

The Turtle files under `src/main/resources/shapes/` are the **spec** of what is checked.

Sample graphs for manual runs live under `src/test/resources/test-ontologies/`
(`zoo-with-pitfalls.ttl` for the OWL integration test, plus `*-examples.ttl`).
Structural fixtures for the modern-engineering and RDF 1.2 catalogues live under
`src/test/resources/fixtures/`.

## Modern engineering pitfalls (v0.1.x)

`onto-quality` ships with a "modern engineering" shape catalogue
covering quality dimensions that emerged after OOPS!'s 2014 catalogue
was finalized:

  - **FAIR compliance** — persistent identifiers, machine-readable
    licenses, contact points, versioned imports
  - **URI hygiene** — consistent namespace style
  - **LLM consumability** — readable local names, label-shaped labels,
    examples for grounding
  - **OWL anti-patterns** — metamodelling smell (class-as-instance)
  - **Vocabulary reuse** — flag unused imports
  - **Multilingual quality** — language-tag hygiene
  - **Maintainability** — named entities with stable IRIs

These shapes use pitfall codes prefixed with `N` (for "new") to
distinguish from OOPS!'s `P`-numbered catalogue. See
`src/main/resources/shapes/modern-engineering-shacl.ttl` for the full
list with rationale.

## RDF 1.2 conformance (v0.1.x)

A separate small catalogue (`rdf12-quality-shacl.ttl`) flags pitfalls
specific to RDF 1.2: legacy reification, RTL language tags without
direction, and the impossible-but-defensive check for triple terms
in subject position.

## Usage

```kotlin
val checker = QualityChecker.default(ShaclValidation.validator())
val report = checker.check(ontology)
println(report.describeMarkdown())
```

## Roadmap

- **v0.1:** structural validation (OWL / SKOS / DQ SHACL bundles).
- **v0.2:** embedding-assisted semantic tier (`:tools:onto-quality-embed`, `EMBEDDING_QUALITY` catalogue).
- **v0.3:** LLM-generated explanations.
- **v0.4:** reasoning integration.

## Semantic tier (v0.2)

`onto-quality` v0.2 adds an embedding-based quality tier. This detects pitfalls that pattern-matching cannot catch: synonymous classes or properties, miscellaneous catch-all classes, same-label–different-class pairs, and (when enrichment emits scores) label/definition drift.

The semantic tier is a two-step pipeline:

```text
# one-time per ontology version
onto-qa enrich my-ontology.ttl --output my-ontology.enriched.ttl

# repeatable, fast
onto-qa check my-ontology.enriched.ttl --catalog all
```

Or as a single command:

```text
onto-qa pipeline my-ontology.ttl
```

On first run, the embedding model (~80–90 MB ONNX) is downloaded to `~/.kastor/onto-quality/models/` (override with `KASTOR_MODEL_CACHE` or `-Dkastor.onto-quality.model-cache=...`). Subsequent runs use the cache.

### Custom ONNX + tokenizer (domain / medical)

Use **`--model custom`** with **`--onnx`**, **`--tokenizer`**, and **`--embedding-dim`** (hidden size of the last layer in the export). Optional **`--model-display-name`** and **`--tokenizer-note`** populate enrichment provenance. The ONNX I/O contract matches the bundled MiniLM runner (mean-pooled masked token vectors, then L2-normalized). Biomedical models (e.g. BioBERT / PubMedBERT exports) are typically used with a **higher** `--threshold` (see below).

Example:

```text
onto-qa enrich my-ontology.ttl --model custom --onnx biobert.onnx --tokenizer tokenizer.json --embedding-dim 768 --threshold 0.90
```

### Threshold tuning

The default threshold (0.85) was calibrated against the OOPS-style fixtures documented in [CALIBRATION.md](./CALIBRATION.md) §v0.2. Domain-specific ontologies may need adjustment:

- Biomedical: try **0.90** (more conservative — many domain terms are lexically close but semantically distinct).
- General/popular-domain: **0.85** (default).
- Cross-language alignment work: try **0.80** (more permissive).

### CI / embedding tests

Gradle tests that load ONNX MiniLM are gated with `@DisabledIfEnvironmentVariable(KASTOR_SKIP_EMBEDDING_TESTS = 1)`. Export that variable in memory-tight CI agents so only structural tests run; run embedding calibration locally or on larger runners when changing shapes or the enricher.

## Calibration against OOPS!

The `onto-quality` shape catalogues are calibrated against the reference test ontologies published with **OOPS!** (OntOlogy Pitfall Scanner!). See [CALIBRATION.md](./CALIBRATION.md) for pass/fail/skip matrices and **`docs/PITFALL_TRIAGE.md`** for a fixture-driven audit of remaining pitfalls. To print **latency** tables for OWL_QUALITY (and optionally EMBEDDING_QUALITY on a subset), set **`KASTOR_OOPS_BENCHMARK=1`** and run `OopsBenchmarkTest` as described under **Benchmark vs OOPS! corpus** in CALIBRATION.

### Extended structural coverage (v0.1.x)

Beyond the original twelve OOPS-aligned structural codes, `owl-quality-shacl.ttl` now exposes additional **graph-level** constraints that align with upstream regression files in **`oeg-upm/OOPS` `src/test/resources/data/input/`**: **P03, P13, P20, P24, P33, P36, P38, P39, P40, P41** (converted to Turtle under `oops-corpus/`). Pitfalls delegated to embeddings remain in **`embedding-quality-shacl.ttl` (v0.2)** for **P02, P12, P21, P32**. Pitfalls that require OWL entailment (**P05, P14–P16, P28, P29, P30, P31**, …) are tracked for **v0.3+** in the triage document. Genuine non-SHACL cases (**P01, P07, P10, P17, P18**, …) are explicitly marked **OUT_OF_SCOPE** there.

Summary as of this release (see [CALIBRATION.md](./CALIBRATION.md)): **25** structural calibration invocations (**15** original + **10** v0.1.x): **24** pass, **1** skipped (**P09** — no upstream fixture); semantic tier **4** of **4** when ONNX tests are enabled.
