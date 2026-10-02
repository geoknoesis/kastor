# How to Check Ontology Quality

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** tiers, catalogues, pitfalls → [Ontology quality feature](../features/ontology-quality.md), [Reasoning in Kastor](../design/reasoning-in-kastor.md), [**Glossary**](../concepts/glossary.md). **Reference:** [tools/onto-quality README](../../../tools/onto-quality/library/README.md).

## Problem

- Run **bundled SHACL catalogues** (OWL, SKOS, data quality, RDF 1.2, modern engineering, optional semantic tier) and interpret **`QualityReport`** / **`QualityFinding`** (**category**, **tier**, **pitfall** codes: OOPS **P**, Kastor **K**, modern **N**, …).
- Use **`QualityChecker.default()`** (includes **OOPS pitfall registry** metadata) or a **custom catalogue list**.
- Optionally: **`SemanticEnricher`** + embedding shapes, **LLM** explanations (`onto-quality-llm-koog`), **reasoning** before SHACL (**RDFS** / **OWL RL** / **HermiT**; **K07** when globally inconsistent).
- Operate from Kotlin and/or **`onto-qa`** CLI.

## Prerequisites

Add the modules you need:

| Goal | Gradle dependency |
|------|-------------------|
| Quality API + bundled Turtle shapes | `implementation("com.geoknoesis.kastor:onto-quality:0.3.0-SNAPSHOT")` |
| Embedding / `SemanticEnricher` | `implementation("com.geoknoesis.kastor:onto-quality-embed:0.3.0-SNAPSHOT")` |
| LLM explanations (Koog) | `implementation("com.geoknoesis.kastor:onto-quality-llm-koog:0.3.0-SNAPSHOT")` |

You also need an RDF provider used elsewhere in your project (for example **`rdf-jena`**) so `Rdf.parse` / file IO works the same way as in [How to Validate with SHACL](how-to-validate-shacl.md).

When you use the [Kastor BOM](../getting-started/installation.md), align versions via the BOM instead of repeating the version.

## Steps

### Step 1: Parse the ontology

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat

val ontology = Rdf.parseFromFile("path/to/ontology.ttl", "TURTLE")
```

### Step 2: Run the quality checker

#### Default (all bundled catalogues)

```kotlin
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

val validator = ShaclValidation.validator()
val checker = QualityChecker.default(validator)
val report = checker.check(ontology)

println(report.describeText())
println("Conforms: ${report.conforms}")
```

**What `default()` loads:** the six **active** bundles ([`BundledCatalogs.all`](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/catalog/BundledCatalogs.kt)) — OWL quality, SKOS validation, data quality, embedding quality, modern engineering, RDF 1.2 — **plus** [`OOPS_PITFALL_REGISTRY`](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/catalog/BundledCatalogs.kt) (deactivated shapes only). That registry carries human-readable definitions for **OOPS! P01–P41** and **Kastor K01–K07** so findings can resolve **pitfall** labels (for example **K07** after a HermiT inconsistency check) without merging a second catalog by hand. It does **not** add extra validation constraints on your data.

#### One catalogue or a custom subset

```kotlin
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs

val checker = QualityChecker.builder(validator)
    .addCatalog(BundledCatalogs.OWL_QUALITY)
    .build()
val report = checker.check(ontology)
```

If you use **`check(ontology, OntoQualityReasoningProfile.HERMIT)`** on a **custom** checker that omits the registry, add **`BundledCatalogs.OOPS_PITFALL_REGISTRY`** (or **`addCatalogs(BundledCatalogs.allWithOopsRegistry)`** for the full default set) so **K07** and other **K**-code metadata resolve on merged report rows.

#### Published SKOS vocabulary stack (no OWL-quality catalogue)

```kotlin
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs

val checker = QualityChecker.builder(validator)
    .addCatalogs(BundledCatalogs.SKOS_VOCABULARY_QC)
    .build()
val report = checker.check(ontology)
```

Use `BundledCatalogs.SKOS_VOCABULARY_QC_WITH_EMBEDDING` after **`SemanticEnricher`** so **embedding-quality** shapes apply.

Catalogue ids match the CLI `--catalog` flag: `owl-quality`, `skos-validation`, `data-quality`, `embedding-quality`, `modern-engineering`, `rdf12-quality`, `skos-vocabulary` (SKOS + data + modern + RDF12, no OWL), `skos-vocabulary-embed` (same plus embedding shapes; use after enrich or `pipeline`), `all`.

#### RDF reasoning before SHACL

```kotlin
import com.geoknoesis.kastor.ontoquality.reasoning.OntoQualityReasoningProfile

// Jena RDFS, OWL_MICRO (Jena OWL Micro, ReasonerType.OWL_MICRO) or OWL_RL (Jena OWL rule reasoner, ReasonerType.OWL_RL): materialize then validate (no consistency row unless the engine reports one)
val reportRdfs = checker.check(ontology, OntoQualityReasoningProfile.RDFS)

// HermiT (OWL 2 DL): same pipeline; globally inconsistent ontologies add ERROR-level rows tagged Kastor **K07** (with default checker / registry)
val reportHermit = checker.check(ontology, OntoQualityReasoningProfile.HERMIT)
```

CLI examples:

```bash
onto-qa check ontology.ttl --catalog all --reasoner none
onto-qa check ontology.ttl --catalog all --reasoner rdfs
onto-qa check ontology.ttl --catalog all --reasoner owl-micro
onto-qa check ontology.ttl --catalog all --reasoner owl-rl
onto-qa check ontology.ttl --catalog all --reasoner hermit
```

`--reasoner owl-micro` (`OntoQualityReasoningProfile.OWL_MICRO`) runs Jena's OWL Micro rule reasoner (`ReasonerType.OWL_MICRO`): faster than `owl-rl`, but less complete. `--reasoner owl-rl` (`OWL_RL`) runs Jena's full OWL rule reasoner (`ReasonerType.OWL_RL`). The two profiles are distinct. For example, `owl-rl` derives `owl:sameAs` between two values of an `owl:FunctionalProperty`, while `owl-micro` has no equality reasoning and does not.

A reasoner only changes what the **entailment-aware** detectors see (SKOS integrity conditions such as S9, S25, S27, S46, and shapes of your own catalogues). **Structural** detectors (cycles, orphans, missing declarations, annotations, naming: the whole `owl-quality`, `modern-engineering`, `rdf12-quality` and `embedding-quality` catalogues) always evaluate the ontology as written, so `--reasoner rdfs` does not report a `rdfs:subClassOf` cycle on every class or hide undeclared classes. Each shape states its graph with `oqsh:evaluatedOn`; see [Which graph a detector evaluates](../../../tools/onto-quality/library/README.md#which-graph-a-detector-evaluates).

**CLI `--catalog all`** uses **`QualityChecker.default()`**, so the OOPS registry is present and **K07** metadata applies when **HermiT** reports inconsistency. Requires **`:rdf:reasoning-hermit`** (and its transitive deps) on the classpath for the CLI artifact.

Design and limitations: [Reasoning in Kastor](../design/reasoning-in-kastor.md), operational traps: [Reasoning ontology pitfalls](../design/reasoning-ontology-pitfalls.md).

### Step 3 (optional): Semantic tier — enrich then validate

Shapes in **`embedding-quality`** expect **`oqsh:semanticallyCloseTo`** triples (and optional **`oqsh:labelDefinitionDriftScore`**) produced by **`SemanticEnricher`**. Without enrichment, those shapes usually produce **no** findings (graceful degradation).

```kotlin
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs

val enriched = SemanticEnricher.default().use { it.enrich(ontology) }

val checker = QualityChecker.builder(validator)
    .addCatalog(BundledCatalogs.EMBEDDING_QUALITY)
    .build()

val report = checker.check(enriched)
```

On first use, the default MiniLM ONNX model is downloaded under **`~/.kastor/onto-quality/models/`**. A cached file whose SHA-256 no longer matches (truncated or tampered) is deleted and downloaded again once. Override the cache root with **`KASTOR_MODEL_CACHE`** or **`-Dkastor.onto-quality.model-cache=...`**, or pass it directly with **`OnnxEmbeddingModel.fromMiniLm(cacheRoot, maxTokens)`**. Input text is truncated to `maxTokens` and batches are padded dynamically. Similarity search that would exceed its budget fails with **`SimilaritySearchBudgetExceededException`** instead of running unbounded.

**Similarity limits and large vocabularies.** By default the pairwise search is exact, and its limits scale with the number of labelled entities (`SimilarityLimitsPolicy.scaled()`). The work budget covers the worst case, n(n−1)/2 distance evaluations plus the index build, and never drops below 50,000,000 evaluations or a 30 s deadline. Override one or both with `SimilarityLimitsPolicy.scaled(maxDistanceEvaluations, timeout)`, or pin them with `SimilarityLimitsPolicy.fixed(SimilaritySearchLimits(...))`. For tens of thousands of entities, opt in to approximate search:

```kotlin
import com.geoknoesis.kastor.ontoquality.embed.SimilarityLimitsPolicy
import com.geoknoesis.kastor.ontoquality.embed.SimilaritySearchMode

val enricher = SemanticEnricher(model, 0.85, SimilarityLimitsPolicy.scaled(), SimilaritySearchMode.ApproximateLsh())
```

`ApproximateLsh` (defaults: 20 tables, 10 bits per table, seed 42) uses random-projection locality-sensitive hashing. Only entities that share a bucket are compared, and every reported pair is verified against the threshold. Results are therefore a subset of the exact results: no pair is invented, but some may be missed (about 95 % chance of comparing a pair at cosine 0.85 with the defaults, higher for closer pairs). The mode used is recorded in the enrichment provenance (`oqsh:similaritySearchMode`).

#### Domain-specific embeddings (e.g. medical)

The default model is general English. For specialised text (UMLS-style labels, biomedical jargon), supply a **BERT-style** ONNX export and a HuggingFace **`tokenizer.json`** that matches it. The graph must expose `input_ids` / `attention_mask` / `token_type_ids` (optional) and a **rank-3** float output (batch × sequence × hidden), with hidden size equal to **`--embedding-dim`**.

**API:**

```kotlin
import com.geoknoesis.kastor.ontoquality.embed.OnnxEmbeddingModel
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import java.nio.file.Path

OnnxEmbeddingModel.fromLocalFiles(
    onnxPath = Path.of("path/to/model.onnx"),
    tokenizerPath = Path.of("path/to/tokenizer.json"),
    name = "my-biobert-export",
    dimension = 768,
    maxTokens = 512,
    tokenizerDescription = "dmis-lab/biobert-base-cased-v1.2",
).use { model ->
    val enriched = SemanticEnricher(model = model, threshold = 0.90).enrich(ontology)
    // run QualityChecker on enriched
}
```

**CLI (`onto-qa enrich` / `onto-qa pipeline`):**

```bash
onto-qa enrich ontology.ttl --model custom \
  --onnx path/to/model.onnx --tokenizer path/to/tokenizer.json \
  --embedding-dim 768 --threshold 0.90 \
  --model-display-name biobert-export \
  --tokenizer-note "dmis-lab/biobert-base-cased-v1.2"
```

Bundled **`--model all-MiniLM-L6-v2`** must not be combined with `--onnx` / `--tokenizer` / `--embedding-dim`; inconsistent embedding options exit with status 4 before any model is loaded.

Similarity options on `enrich` and `pipeline`:

| Option | Meaning |
|--------|---------|
| `--similarity-max-work N` | Maximum distance evaluations (≥ 1). Default: scaled to the number of labelled entities, at least 50,000,000. |
| `--similarity-timeout S` | Similarity search deadline in seconds (1–86400). Default: scaled, at least 30. |
| `--similarity-max-pairs N` | Maximum similar pairs the search may return before failing (≥ 1). Default: 1,000,000. |
| `--similarity-mode exact\|approximate` | `exact` (default) finds every pair. `approximate` is the opt-in LSH mode: much faster on large vocabularies, may miss some pairs, every reported pair is verified, and the mode is recorded in provenance. |

When a similarity budget is exhausted, the command exits with status 5. The message says which limit was reached, and the hint matches it:

- **Work** (distance evaluations): raise `--similarity-max-work`, or use `--similarity-mode approximate`.
- **Time**: raise `--similarity-timeout`, or use `--similarity-mode approximate`.
- **Pairs**: too many pairs are similar. Raise `--threshold` or `--similarity-max-pairs`.

### Step 4 (optional): LLM explanations (Koog)

Add **`onto-quality-llm-koog`** and configure a supported provider (for example **`OPENAI_API_KEY`**). Explanations are **advisory** only and do not change SHACL conformance.

```kotlin
import com.geoknoesis.kastor.ontoquality.explanation.ExplanationOptions
import com.geoknoesis.kastor.ontoquality.llm.LlmExplanationConfig
import com.geoknoesis.kastor.ontoquality.llm.LlmProvider
import com.geoknoesis.kastor.ontoquality.llm.qualityExplanationEnricher
import kotlinx.coroutines.runBlocking

val explained = runBlocking {
    qualityExplanationEnricher(LlmExplanationConfig(provider = LlmProvider.OPENAI))
        .enrich(report, ExplanationOptions())
}
println(explained.describeMarkdown())
```

Library callers can tune `LlmExplanationConfig.requestTimeout` (default 60 s per request, including the JSON repair request), `maxRetries` (default 2), `retryBackoff` (default 1 s), `maxTotalDuration` (default 10 minutes for the whole run) and `circuitBreakerThreshold` (default 3). Only transient failures are retried: timeouts, HTTP 408 / 429 / 5xx and connection errors. Other errors fail the batch immediately: for example HTTP 400 / 401 / 403 / 404, OpenAI `insufficient_quota` (an HTTP 429 when the account has no credit left) and TLS / certificate errors. Each retry waits `retryBackoff` × 2^attempt with jitter (a random delay between half and all of it), or the provider's `Retry-After` when the error carries one. Once `maxTotalDuration` is spent, the remaining batches are recorded as failures instead of being sent. After `circuitBreakerThreshold` consecutive batches fail with the same error, the remaining batches are skipped. The error can be non-retryable (a bad API key, say) or retryable with its retries exhausted (a provider returning HTTP 503 throughout). Batches that time out, fail after retries, are skipped, or return unusable JSON are listed in **`explained.failures`**; explanations from the other batches are kept.

Finding text comes from the ontology and is treated as untrusted. It is sent as JSON-encoded data inside explicit data tags, with `<` and `>` escaped, plus an instruction to ignore any instructions it contains. The JSON repair request frames the model's previous reply the same way, because that reply may echo injected ontology text.

**CLI:** `export KASTOR_ONTO_QUALITY_LLM=true` then e.g. `onto-qa check ontology.ttl --explain --llm-provider openai`. Use `--llm-model` for a raw provider id or `--llm-model-preset` when omitting `--llm-model`. Use `--explain --explain-dry-run` to preview counts without an API call (a dry run needs neither `KASTOR_ONTO_QUALITY_LLM` nor an API key; `--explain-dry-run` without `--explain` is a usage error, status 4). Tune calls with `--llm-timeout` (seconds per request, 1–3600, default 60), `--llm-retries` (0–10, default 2; transient failures only), `--llm-max-duration` (seconds for all requests, retries and waits, 1–86400, default 600), `--llm-max-output-tokens` (tokens the model may generate per request, default 8192; raise it with a large `--explain-batch`), `--explain-max` (1–500) and `--explain-batch` (1–100). Explanation failures are reported on stderr with the number of findings left unexplained (per reason when there are several, for example a batch that was too large to send and a request that failed); add `--fail-on-explain-error` to exit with status 3. Prompts, replies and failure reasons are bounded: finding fields are cut to 1,000 characters, a prompt over 120,000 characters is not sent (use a smaller `--explain-batch`), a reply over 500,000 characters is not used, every text field of a reply is cut to 2,000 characters (suggested actions to 10), and a failure reason is one sanitised line of at most 500 characters. An `--output` that cannot be written (a directory, a read-only file, a parent that is not a writable directory) is refused with status 5 before anything is parsed, loaded or sent to an LLM. The prerequisites — `KASTOR_ONTO_QUALITY_LLM=true` and, for `openai` / `anthropic`, `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` — are checked before the ontology is parsed or an embedding model is loaded. If one is missing, no explanations are produced and a warning is printed on stderr; with `--fail-on-explain-error` the command stops immediately with status 3. `--debug` prints the stack trace of an explanation failure on stderr. JSON output (kotlinx.serialization) includes **`findings`**, **`llmExplanations`** and **`llmExplanationFailures`**.

## CLI (`onto-qa`)

The **`:tools:onto-quality-cli`** module ships the **`onto-qa`** application (`com.geoknoesis.kastor.ontoquality.cli.MainKt`).

Run via Gradle from the repository root:

```bash
./gradlew :tools:onto-quality-cli:run --args="check path/to/ontology.ttl --catalog all"
./gradlew :tools:onto-quality-cli:run --args="check path/to/ontology.ttl --catalog all --reasoner hermit"
./gradlew :tools:onto-quality-cli:run --args="check path/to/skos.ttl --catalog skos-vocabulary"
./gradlew :tools:onto-quality-cli:run --args="enrich path/to/ontology.ttl --output path/to/enriched.ttl"
./gradlew :tools:onto-quality-cli:run --args="pipeline path/to/ontology.ttl --catalog skos-vocabulary-embed --severity info"
```

The input syntax is picked from the file extension (`.ttl` Turtle; `.owl`/`.rdf`/`.xml` RDF/XML; `.nt` N-Triples; `.jsonld`/`.json` JSON-LD; anything else is read as Turtle). Override it with `--input-format turtle|rdfxml|ntriples|jsonld`.

Relative IRIs resolve against the default base IRI **`urn:onto-qa:input/<file name>`**, whatever directory the file is in. In `onto.ttl`, `<> a owl:Ontology` becomes `urn:onto-qa:input/onto.ttl`, `<#Foo>` (or `rdf:about="#Foo"` in RDF/XML) becomes `urn:onto-qa:input/onto.ttl#Foo`, and `<Sub>` becomes `urn:onto-qa:input/Sub`. As a result, finding IRIs and `findingRef` values are the same on every machine, and no absolute path (such as a user name) appears in reports or LLM prompts. To resolve against another base, such as the ontology's published namespace, pass **`--base-iri <absolute IRI>`**. A value that is not an absolute IRI is a usage error (status 4).

Options are validated before any model download or ontology load. `pipeline` keeps the enriched graph in memory and writes it to a temporary file only with `--keep-intermediate`.

Errors print a one-line message. To also print stack traces, add **`--debug`** before or after the subcommand (`onto-qa --debug check …` or `onto-qa check x.ttl --debug`). With `--explain`, `--debug` also prints the exception behind a failed LLM request (HTTP 401, timeout, 5xx; at most three distinct ones). Every line of a stack trace is sanitised like any other message; provider API keys (whatever their length) and the credentials of URLs (`http://user:password@host`, for example in `--ollama-base`) are replaced by `***`; a trace is cut to 120 lines and a line to 2,000 characters.

**Output.** stdout and stderr are UTF-8 whatever the platform charset, so redirecting a report to a file works on Windows as well. `--output` files, and the result of `enrich`, are written atomically (temporary file, then move): parent directories are created and an existing file is replaced. An `--output` that is the input ontology is refused (status 4) unless you pass **`--overwrite-input`**. `--explain` cannot be combined with `--format turtle` (status 4, before any LLM call), because a SHACL validation report has no place for explanations: use `text`, `markdown` or `json`.

**Blank nodes.** Findings on blank nodes (restrictions, class expressions) show the node as a key such as `_:k3f9a…` instead of a parser label. The key covers the node and its context, is unique within the ontology, and is the same on every parse and with any `--reasoner`. See [Stable finding references](../../../tools/onto-quality/library/README.md#stable-finding-references).

**Warnings** go to stderr. Two cases produce one: `--explain` without `KASTOR_ONTO_QUALITY_LLM=true`, and an embedding catalogue on a graph with no `oqsh:semanticallyCloseTo` triples. The CLI also ships the `slf4j-simple` binding at WARN level, writing to stderr, so library warnings are visible too (for example, a corrupt model cache being downloaded again). Untrusted text in these messages, such as exception messages, paths and LLM failure reasons, is sanitised (see **Report output** below).

**Report output:** text taken from the ontology (SHACL messages, focus node and shape names) and from the LLM is Markdown-escaped in `--format markdown`, so it cannot inject links, images or raw HTML. `@` is escaped too, so e-mail addresses do not become autolinks. In `--format text`, control characters other than newline and tab are shown as visible `\uXXXX` escapes, so terminal escape sequences embedded in the ontology cannot execute. Unicode bidirectional controls are shown as `\uXXXX` in both formats, so they cannot visually reorder the output: embeddings and overrides U+202A–U+202E, isolates U+2066–U+2069, and the marks LRM U+200E, RLM U+200F and ALM U+061C. Messages the CLI prints on stderr are sanitised the same way. JSON output is serialized with kotlinx.serialization and escapes every string. To apply the same rules to untrusted text printed outside a report, use the public helper **`OutputSanitizer`**: `OutputSanitizer.terminal(text)` for terminal output, `OutputSanitizer.markdownInline(text)` for inline Markdown.

See the [module README](../../../tools/onto-quality/library/README.md#cli-exit-codes-and-input-formats) for the complete option validation rules, threshold tuning, and **`KASTOR_SKIP_EMBEDDING_TESTS`** (CI).

## Validation

- Kotlin: `report.conforms` / `report.describeText()` reflect SHACL outcomes for the selected catalogues.
- CLI exit codes (every failure has its own status):

| Exit status | Meaning |
|-------------|---------|
| **0** | Success; no findings at or above `--severity` (default `violation`). |
| **1** | At least one finding is at or above `--severity`; with `--severity info` any finding fails. Used for nothing else. |
| **2** | The input ontology could not be parsed in the selected RDF syntax. A file that cannot be read is status 5. |
| **3** | `--fail-on-explain-error` was set and LLM explanations failed or were incomplete (status 1 takes precedence). |
| **4** | Usage or configuration error; nothing was run. Examples: unknown option or bad value, missing or non-existent input file, an input path that is a directory, a `--base-iri` that is not an absolute IRI, `--reasoner hermit` when HermiT is not on the classpath (the message says how to fix it), inconsistent embedding options (`--model custom` without `--onnx`), `metrics --include` with `--format json` or `turtle`, `--explain` with `--format turtle`, an `--output` that is the input file without `--overwrite-input`. |
| **5** | Runtime error: I/O (the input cannot be read, the output cannot be written), no RDF provider for the input syntax, embedding model download or loading, similarity search budget exceeded, or an unexpected internal error. Add `--debug` (before or after the command) for the stack trace. |

  `kastor-rdf` (see [How to test RDF graphs](how-to-test-rdf-graphs.md#kastor-rdf-cli)) uses a different, older convention. Side by side (both are printed by `--help` of either tool):

| Status | `onto-qa` | `kastor-rdf` |
|--------|-----------|--------------|
| **0** | Success; no findings at or above `--severity`. | Success; for `diff`, the inputs are isomorphic. |
| **1** | Findings at or above `--severity`. | Usage or input error: bad or extra arguments, unknown format, missing file, parse error. |
| **2** | The ontology could not be parsed. | `diff` found the inputs not isomorphic. |
| **3** | LLM explanations failed (with `--fail-on-explain-error`). | Runtime error: I/O, RDF provider, internal error, out of memory or stack overflow. |
| **4** | Usage or configuration error; nothing was run. | not used |
| **5** | Runtime error: I/O (the input cannot be read, the output cannot be written), model download or loading, similarity or LLM budget, internal error. | not used |

  Scripts that treated status 1 as "findings or bad usage" should also check 4 and 5. See the [module README](../../../tools/onto-quality/library/README.md#cli-exit-codes-and-input-formats).
- The SHACL benchmark CLI (`benchmarks/shacl/era-cli`) uses the same convention for the statuses it has: 0, 2, 4 and 5. It prints errors as one sanitised stderr line. See its [README](../../../benchmarks/shacl/era-cli/README.md#exit-status).

## Troubleshooting

- **No embedding findings** — run **`SemanticEnricher`** first when using **`EMBEDDING_QUALITY`** / `skos-vocabulary-embed`; without similarity triples, those shapes usually emit nothing (by design).
- **Missing K07 / pitfall text** — ensure **`OOPS_PITFALL_REGISTRY`** is on the checker when using custom catalogue lists; **`QualityChecker.default()`** and **`onto-qa --catalog all`** include it.
- **HermiT / classpath** — CLI **`--reasoner hermit`** needs **`:rdf:reasoning-hermit`** transitively on the classpath for the packaged CLI. Without it, the command exits with status 4 before loading anything, and suggests `owl-rl`, `owl-micro` or `rdfs`.

## Calibration and pitfall metadata

Findings can carry pitfall references according to the shape catalogue:

| Style | Example | Typical source |
|-------|---------|----------------|
| OOPS! | `P04`, `P20`, … | [`owl-quality-shacl.ttl`](../../../tools/onto-quality/library/src/main/resources/shapes/owl-quality-shacl.ttl), [`embedding-quality-shacl.ttl`](../../../tools/onto-quality/library/src/main/resources/shapes/embedding-quality-shacl.ttl) |
| Kastor extension | `K01` (import cycle), `K07` (HermiT inconsistency), `K02`–`K06` (documented in registry) | Active shapes + [`oops-pitfall-registry-shacl.ttl`](../../../tools/onto-quality/library/src/main/resources/shapes/oops-pitfall-registry-shacl.ttl) |
| Modern engineering | `N03`, `N16`, … | [`modern-engineering-shacl.ttl`](../../../tools/onto-quality/library/src/main/resources/shapes/modern-engineering-shacl.ttl) |
| SKOS catalogue | rule codes on SKOS shapes | [`skos-validation-shacl.ttl`](../../../tools/onto-quality/library/src/main/resources/shapes/skos-validation-shacl.ttl) |

Calibration against the OOPS! reference corpus, the semantic tier, and **K07** (HermiT) is recorded here:

- [CALIBRATION.md](../../../tools/onto-quality/library/CALIBRATION.md) — pass/skip matrices and HermiT preflight
- [PITFALL_TRIAGE.md](../../../tools/onto-quality/library/docs/PITFALL_TRIAGE.md) — per-pitfall decisions (structural vs semantic vs reasoning backlog)

## Related tasks

- [Ontology Quality feature overview](../features/ontology-quality.md) — modules, tiers, and architecture at a glance
- [SHACL Validation](../features/shacl-validation.md) — underlying validator and profiles
- [How to Validate with SHACL](how-to-validate-shacl.md) — generic shapes + `ShaclValidation.validator()`
- [SHACL validation architecture](../design/shacl-validation-architecture.md) — engine and provider notes
