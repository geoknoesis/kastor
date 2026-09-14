# onto-quality

<p align="center">
  <img src="../../../docs/assets/kastor-logo.png" alt="Kastor — beaver mascot with linked-data graph" width="128" height="128">
</p>

Ontology **quality checks** for Kastor: curated **SHACL 1.2** shape catalogues run through the
`:rdf:shacl-validation` engine. Results are surfaced as `QualityReport` / `QualityFinding` values with
**category** and **pitfall** metadata (where the catalogue defines it).

**Docs site:** [How to Check Ontology Quality](../../../docs/kastor/guides/how-to-ontology-quality.md) · [Feature overview](../../../docs/kastor/features/ontology-quality.md)

## Bundled catalogues

1. **OWL Ontology Quality** — `BundledCatalogs.OWL_QUALITY` — `/shapes/owl-quality-shacl.ttl`
2. **SKOS Taxonomy Validation** — `BundledCatalogs.SKOS_VALIDATION` — `/shapes/skos-validation-shacl.ttl`
3. **Data Quality Constraints** — `BundledCatalogs.DATA_QUALITY` — `/shapes/dq-constraints-shacl.ttl`
4. **Embedding-based Ontology Quality** — `BundledCatalogs.EMBEDDING_QUALITY` — `/shapes/embedding-quality-shacl.ttl` (semantic / embedding tier; run `SemanticEnricher` first for similarity triples)
5. **Modern Ontology Engineering** — `BundledCatalogs.MODERN_ENGINEERING` — `/shapes/modern-engineering-shacl.ttl` (pitfall codes `N` — beyond OOPS!)
6. **RDF 1.2 Conformance** — `BundledCatalogs.RDF12_QUALITY` — `/shapes/rdf12-quality-shacl.ttl`
7. **OOPS! pitfall registry (documentation)** — `BundledCatalogs.OOPS_PITFALL_REGISTRY` — `/shapes/oops-pitfall-registry-shacl.ttl` — one deactivated `sh:NodeShape` per OOPS **P01–P41** plus Kastor **K01–K07** (`skos:definition` only; **no** extra validation hits on data). **`QualityChecker.default()`** and **`OntoQualityReasoningProfile.HERMIT`** consumers get pitfall metadata (e.g. **K07**) without a separate catalog step; for custom builders, append **`BundledCatalogs.OOPS_PITFALL_REGISTRY`** or use **`BundledCatalogs.allWithOopsRegistry`** (same bundle as [BundledCatalogs.all] + registry).

**Published SKOS vocabulary preset (no OWL-quality):** `BundledCatalogs.SKOS_VOCABULARY_QC` lists
`SKOS_VALIDATION`, `DATA_QUALITY`, `MODERN_ENGINEERING`, `RDF12_QUALITY`.
With embeddings (after `SemanticEnricher`): `BundledCatalogs.SKOS_VOCABULARY_QC_WITH_EMBEDDING`.

The Turtle files under `src/main/resources/shapes/` are the **spec** of what is checked.

Sample graphs for manual runs live under `src/test/resources/test-ontologies/`
(`zoo-with-pitfalls.ttl` for the OWL integration test, plus `*-examples.ttl`).
Structural fixtures for the modern-engineering and RDF 1.2 catalogues live under
`src/test/resources/fixtures/`.

## Modern engineering pitfalls

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

## RDF 1.2 conformance (SHACL shapes)

A separate small catalogue (`rdf12-quality-shacl.ttl`) flags pitfalls
specific to RDF 1.2: legacy reification, RTL language tags without
direction, and the impossible-but-defensive check for triple terms
in subject position.

## Usage

`QualityChecker.default()` loads all active catalogues **and** the **OOPS pitfall registry** (metadata only). Use **`check(ontology)`** for asserted-graph validation, or **`check(ontology, OntoQualityReasoningProfile.*)`** when you want materialization before SHACL (**HermiT** surfaces **K07** when the ontology is inconsistent).

```kotlin
val checker = QualityChecker.default(ShaclValidation.validator())
val report = checker.check(ontology)
println(report.describeMarkdown())
```

## Metrics integration (optional)

`onto-quality` can be enhanced with metrics from
`:tools:onto-quality-metrics`. When a `MetricsProvider` is supplied,
findings are sorted by entity importance (centrality in the ontology),
and the Markdown report includes a "Top findings by importance"
section.

Usage:

```kotlin
import com.geoknoesis.kastor.ontoquality.metrics.integration.KastorMetricsProvider

val checker = QualityChecker.builder(validator)
    .withAllBundledCatalogs()
    .withMetricsProvider(KastorMetricsProvider())
    .build()

val report = checker.check(ontology)
println(report.describeMarkdown())  // Includes prioritized top findings
```

When `:tools:onto-quality-metrics` is not on the classpath, omit the
`withMetricsProvider` call. Findings will be returned in the order
produced by the SHACL engine.

If metrics computation fails, the checker logs the error and continues without rankings or the metrics “top findings” section. **`VirtualMachineError`** (and subclasses) are rethrown.

With a reasoning profile (**`check(ontology, profile)`**, CLI **`--with-metrics --reasoner …`**), SHACL validation runs on the materialised graph but the **`MetricsProvider`** always receives the **asserted** ontology: reasoner closures (reflexive/transitive `rdfs:subClassOf`, `rdfs:Resource` typing) would otherwise turn every class into a cycle participant and make depth, coupling and importance meaningless.

## Reports, Markdown, and references

### Markdown

Use **`QualityReport.describeMarkdown()`** with defaults, or **`describeMarkdown(MarkdownReportOptions(...))`** to:

- Set **`maxTopFindings`** — length of the “top findings” list when a metrics provider is configured.
- Set **`useAsciiSeverityMarkers = true`** — ASCII severity markers instead of Unicode symbols (CLI: **`--markdown-ascii`** on `check` / `pipeline`).

### Severity counts

**`QualityReport.violationsBySeverity()`** maps each **`ViolationSeverity`** to the number of violation rows on the underlying **`ValidationReport`**.

### Stable finding references

**`FindingRef`** identifies a finding from its **content** (severity, constraint type, message, shape or violation code, focus node, path, value, etc.). It does **not** depend on list position, which keeps IDs stable for sorting, JSON export, LLM prompts, and stored explanations.

**`QualityFinding`** resolves catalogue metadata using the shape IRI first, then **`violationCode`** when the catalogue indexes shapes that way.

Combined validation passes produce a single report whose **`violationsByType`** aggregates match the merged violation list.

## Capabilities by release track

- **Structural validation** — OWL / SKOS / data-quality SHACL bundles.
- **Semantic tier** — embeddings (`:tools:onto-quality-embed`, **`EMBEDDING_QUALITY`** catalogue).
- **LLM explanations** — optional **`onto-quality-llm-koog`** ([Koog](https://github.com/JetBrains/koog)); [design](../../../docs/kastor/design/onto-quality-v0.3-llm-explanations.md), [broader LLM notes](../../../docs/kastor/design/llm-assisted-ontology-modeling-review.md).
- **RDF reasoning before SHACL** — Jena **RDFS** / **OWL_MICRO** (Jena OWL Micro rule reasoner: fast, incomplete) / **OWL_RL** (Jena OWL rule reasoner) or **HermiT** ([reasoning overview](../../../docs/kastor/design/reasoning-in-kastor.md)); **K07** inconsistency rows with **HERMIT**. APIs: [`OntoQualityReasoning`](src/main/kotlin/com/geoknoesis/kastor/ontoquality/reasoning/OntoQualityReasoning.kt), [`QualityChecker.check`](src/main/kotlin/com/geoknoesis/kastor/ontoquality/QualityChecker.kt); CLI **`--reasoner none|rdfs|owl-micro|owl-rl|hermit`**.

## LLM explanations

Advisory natural-language explanations for existing SHACL findings ship in
`com.geoknoesis.kastor:onto-quality-llm-koog` (same version as `onto-quality`; Koog is the LLM runtime).

**Library:**

```kotlin
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.ontoquality.llm.ExplanationModelPreset
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.ontoquality.llm.LlmProvider
import com.geoknoesis.kastor.ontoquality.llm.qualityExplanationEnricher
import kotlinx.coroutines.runBlocking

val enricher = qualityExplanationEnricher(
    LlmExplanationConfig(
        provider = LlmProvider.OPENAI,
        modelPreset = ExplanationModelPreset.AUTO,
        // or: modelId = "gpt-4o"  // overrides modelPreset when set
    ),
)
val explained = runBlocking {
    enricher.enrich(report, ExplanationOptions())
}
check(explained.hasLlmExplanations) { "No explanations — check provider config and API keys" }
println(explained.describeMarkdown())
```

Set **`OPENAI_API_KEY`**, **`ANTHROPIC_API_KEY`**, or run **Ollama** locally for `LlmProvider.OLLAMA`.

**CLI (`onto-quality-cli`):** enable **`--explain`** on **`check`** or **`pipeline`** when **`KASTOR_ONTO_QUALITY_LLM=true`**. Typical flags: **`--llm-provider`**, **`--llm-model`**, **`--llm-model-preset`**, **`--ollama-base`**, **`--explain-max`**, **`--explain-batch`**, **`--explain-min-severity`**, **`--explain-dry-run`**, **`--markdown-ascii`**. Limits: **`--explain-max`** 1–500 (default 50), **`--explain-batch`** 1–100 (default 12). Reliability: **`--llm-timeout`** (seconds per request, 1–3600, default 60), **`--llm-retries`** (0–10, default 2; exponential backoff with jitter, or the provider's `Retry-After`) and **`--llm-max-duration`** (seconds for the whole explanation run, 1–86400, default 600). JSON output contains **`findings`**, **`llmExplanations`** and **`llmExplanationFailures`**; each finding reference uses **`FindingRef`** (order-independent).

Failures are isolated per batch: explanations from successful batches are kept, and batches that time out, error after retries, or return unparseable/incomplete JSON are recorded in **`ExplainedQualityReport.failures`** (library) and reported on stderr (CLI). Only transient failures are retried — timeouts, HTTP 408 / 429 / 5xx and connection errors; client errors such as HTTP 400 / 401 / 403 / 404 fail the batch immediately. After three consecutive identical non-retryable failures (for example a bad API key) the remaining batches are not sent, and once the run budget (`maxTotalDuration`) is spent the remaining batches are recorded as failures instead of being sent. By default the CLI still exits normally; pass **`--fail-on-explain-error`** to exit with status **3**. Finding text — and, for the JSON repair request, the model's previous reply — is sent to the model as JSON-encoded data inside tags with an instruction to treat it as data. LLM output and ontology-derived text (SHACL messages, focus and shape names) are Markdown-escaped in reports (no links, images or raw HTML), and control characters are rendered visibly (`\u001B`) in text output so terminal escape sequences cannot run. **`LlmExplanationConfig.toString()`** redacts the API key; library users can set **`requestTimeout`**, **`maxRetries`**, **`retryBackoff`**, **`maxTotalDuration`** and **`circuitBreakerThreshold`**.

**Automated tests:** `./gradlew :tools:onto-quality-llm-koog:test` uses OpenAI when **`OPENAI_API_KEY`** is set; otherwise those cases are skipped. Set **`KASTOR_SKIP_OPENAI_LLM_TESTS=1`** to skip them even when a key is present.

## RDF reasoning before validation

Optional **materialization** merges asserted triples with **Jena** RDFS or OWL RL rule inferences before SHACL, so validation can align with stores that apply the same entailment. **HermiT** runs a single OWL DL **`reason()`** pass: the expanded graph is validated, and a **globally inconsistent** ontology adds **Kastor K07** rows into the same **`QualityReport`** (pitfall copy from **`OOPS_PITFALL_REGISTRY`**, included in **`QualityChecker.default()`**). See [Reasoning in Kastor](../../../docs/kastor/design/reasoning-in-kastor.md) and [Reasoning ontology pitfalls](../../../docs/kastor/design/reasoning-ontology-pitfalls.md).

**Library:**

```kotlin
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile

val report = checker.check(ontology, OntoQualityReasoningProfile.RDFS)
```

**CLI:** `onto-qa check model.ttl --reasoner rdfs` (or `owl-micro`, `owl-rl`, `hermit`, default `none`). `owl-micro` runs Jena's OWL Micro rule reasoner (`ReasonerType.OWL_MICRO`: RDFS plus property axioms, equality and simple class expressions; faster, less complete); `owl-rl` runs Jena's OWL rule reasoner (`ReasonerType.OWL_RL`). Use **`--catalog all`** to match **`QualityChecker.default()`** (includes registry metadata for **K07**). With **`--with-metrics`**, metrics and importance ranking are computed on the asserted graph (see [Metrics integration](#metrics-integration-optional)).

## CLI: exit codes and input formats

| Exit status | Meaning |
|-------------|---------|
| **0** | Success; no findings at or above **`--severity`** (default `violation`). |
| **1** | At least one finding is at or above **`--severity`** (`info` fails on any finding). Used for nothing else. |
| **2** | The input ontology could not be parsed in the selected RDF syntax. |
| **3** | **`--fail-on-explain-error`** was set and LLM explanations failed or were incomplete (status 1 takes precedence). |
| **4** | Usage or configuration error: unknown command or option, bad or out-of-range value, missing or non-existent input file, inconsistent embedding options (e.g. `--model custom` without `--onnx`), `--include` with `--format json`/`turtle`. Nothing was run. |
| **5** | Runtime error: embedding model download or loading failed (network, checksum), similarity search budget exceeded, output could not be written, or any unexpected internal error. A one-line message is printed; run **`onto-qa --debug <command> …`** for the stack trace. |

Options are validated before any work starts (no model download, no LLM call): **`--format`**, **`--severity`**, **`--catalog`**, **`--reasoner`**, LLM provider/preset choices, **`--threshold`** in [-1, 1], **`--explain-max`** 1–500, **`--explain-batch`** 1–100, **`--max-tokens`** ≥ 1 (≤ 512 for the bundled MiniLM), the `--model` / `--onnx` / `--tokenizer` / `--embedding-dim` combination, **`--similarity-max-work`** ≥ 1, **`--similarity-timeout`** 1–86400, **`--llm-max-duration`** 1–86400.

**Relative IRIs:** the input file's URI is used as the base IRI, so `<> a owl:Ontology` in Turtle or `rdf:about="#Foo"` in RDF/XML resolve against the file (e.g. `file:///home/me/onto.ttl#Foo`) instead of failing to parse.

**Input formats:** `check`, `enrich`, `pipeline` and `metrics` pick the RDF syntax from the file extension — `.ttl` Turtle, `.owl` / `.rdf` / `.xml` RDF/XML, `.nt` N-Triples, `.jsonld` / `.json` JSON-LD; any other extension is read as Turtle. Override with **`--input-format turtle|rdfxml|ntriples|jsonld`** (e.g. a Turtle file named `.owl`).

## Semantic tier (embeddings)

The **embedding** quality tier detects pitfalls that pattern-matching alone cannot catch: synonymous classes or properties, miscellaneous catch-all classes, same-label–different-class pairs, and (when enrichment emits scores) label/definition drift.

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

`pipeline` keeps the enriched graph in memory; nothing is written to disk unless **`--keep-intermediate`** is given, in which case the enriched Turtle is written to a temporary `onto-qa-*.enriched.ttl` file whose path is printed on stderr and which is not deleted. The embedding model is always closed before the exit status is reported, including on failure.

On first run, the embedding model (~80–90 MB ONNX) is downloaded to `~/.kastor/onto-quality/models/` (override with `KASTOR_MODEL_CACHE` or `-Dkastor.onto-quality.model-cache=...`). Subsequent runs use the cache; a cached file whose SHA-256 no longer matches (truncated or tampered) is deleted and downloaded again once.

**Large vocabularies:** pairwise similarity is an exact search whose work budget (distance evaluations) and deadline scale with the number of labelled entities (at least 50,000,000 evaluations and 30 s). Override them with **`--similarity-max-work`** and **`--similarity-timeout`** (seconds). **`--similarity-mode approximate`** switches to random-projection LSH: much faster for tens of thousands of entities, but it may miss some similar pairs (about 95 % recall at cosine 0.85; every reported pair is verified), and the mode is recorded on the enrichment node as `oqsh:similaritySearchMode`. When a budget is exhausted the command stops with exit status **5** and a message naming these options.

### Custom ONNX + tokenizer (domain / medical)

Use **`--model custom`** with **`--onnx`**, **`--tokenizer`**, and **`--embedding-dim`** (hidden size of the last layer in the export). Optional **`--model-display-name`** and **`--tokenizer-note`** populate enrichment provenance. The ONNX output is selected by name — `last_hidden_state` / `token_embeddings` (rank 3, mean-pooled over the attention mask) or `sentence_embedding` (rank 2, used as-is) — and its full shape is validated; vectors are L2-normalized. Tokenization ignores fixed padding in `tokenizer.json`: inputs are truncated to **`--max-tokens`** (default 512; at most 512 for the bundled MiniLM) and padded per batch only to the longest input. Biomedical models (e.g. BioBERT / PubMedBERT exports) are typically used with a **higher** `--threshold` (see below).

Example:

```text
onto-qa enrich my-ontology.ttl --model custom --onnx biobert.onnx --tokenizer tokenizer.json --embedding-dim 768 --threshold 0.90
```

### Threshold tuning

The default threshold (0.85) was calibrated against the OOPS-style fixtures documented in [CALIBRATION.md](./CALIBRATION.md#v02--semantic-tier). Domain-specific ontologies may need adjustment:

- Biomedical: try **0.90** (more conservative — many domain terms are lexically close but semantically distinct).
- General/popular-domain: **0.85** (default).
- Cross-language alignment work: try **0.80** (more permissive).

### Embedding-heavy tests

Tests that load the bundled ONNX MiniLM model honor **`KASTOR_SKIP_EMBEDDING_TESTS=1`** (`@DisabledIfEnvironmentVariable`). Use that in constrained environments; run embedding-related checks locally or on a larger runner when you change shapes or the enricher.

## Calibration against OOPS!

Shape catalogues are compared to the **OOPS!** reference ontologies. Matrices, triage, and benchmarks live in [CALIBRATION.md](./CALIBRATION.md), **`docs/PITFALL_TRIAGE.md`**, and (for latency) **`KASTOR_OOPS_BENCHMARK=1`** with **`OopsBenchmarkTest`** as described there.

**OWL structural catalogue** (`owl-quality-shacl.ttl`) includes graph-level checks aligned with the OOPS corpus (codes such as **P03, P13, P20**, …), synthetic cases (**P01**, **P09**, **P23**), and Kastor **K01** (import cycles). Embedding-assisted pitfalls (**P02, P12, P21, P32**) live under **`embedding-quality-shacl.ttl`**. **`--reasoner hermit`** adds **K07** (global inconsistency) where HermiT is available. Finer DL-heavy pitfalls remain tracked in **`docs/PITFALL_TRIAGE.md`**. Pitfall text for **OOPS P01–P41** and **K01–K07** is in **`OOPS_PITFALL_REGISTRY`**, which **`QualityChecker.default()`** loads together with the active validation catalogues.
