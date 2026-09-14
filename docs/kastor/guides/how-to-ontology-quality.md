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

// Jena RDFS or OWL_RL (Jena OWL rule reasoner, ReasonerType.OWL_RL): materialize then validate (no consistency row unless the engine reports one)
val reportRdfs = checker.check(ontology, OntoQualityReasoningProfile.RDFS)

// HermiT (OWL 2 DL): same pipeline; globally inconsistent ontologies add ERROR-level rows tagged Kastor **K07** (with default checker / registry)
val reportHermit = checker.check(ontology, OntoQualityReasoningProfile.HERMIT)
```

CLI examples:

```bash
onto-qa check ontology.ttl --catalog all --reasoner none
onto-qa check ontology.ttl --catalog all --reasoner rdfs
onto-qa check ontology.ttl --catalog all --reasoner owl-rl
onto-qa check ontology.ttl --catalog all --reasoner hermit
```

`--reasoner owl-micro` is still accepted as a deprecated alias of `owl-rl` and prints a warning: it has always run Jena's OWL rule reasoner, not OWL Micro. The Kotlin enum constant `OntoQualityReasoningProfile.OWL_MICRO` is likewise deprecated in favour of `OWL_RL`.

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

On first use, the default MiniLM ONNX model is downloaded under **`~/.kastor/onto-quality/models/`**. Override the cache root with **`KASTOR_MODEL_CACHE`** or **`-Dkastor.onto-quality.model-cache=...`**, or pass it directly with **`OnnxEmbeddingModel.fromMiniLm(cacheRoot, maxTokens)`**. Input text is truncated to `maxTokens` and batches are padded dynamically. Similarity search that would exceed its budget fails with **`SimilaritySearchBudgetExceededException`** instead of running unbounded.

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

Bundled **`--model all-MiniLM-L6-v2`** must not be combined with `--onnx` / `--tokenizer` / `--embedding-dim`.

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

Library callers can tune `LlmExplanationConfig.requestTimeout` (default 60 s), `maxRetries` (default 2) and `retryBackoff`. Batches that time out, fail after retries or return unusable JSON are listed in **`explained.failures`**; explanations from the other batches are kept.

**CLI:** `export KASTOR_ONTO_QUALITY_LLM=true` then e.g. `onto-qa check ontology.ttl --explain --llm-provider openai`. Use `--llm-model` for a raw provider id or `--llm-model-preset` when omitting `--llm-model`. Use `--explain-dry-run` to preview counts without an API call. Tune calls with `--llm-timeout` (seconds, default 60), `--llm-retries` (0–10, default 2), `--explain-max` (1–500) and `--explain-batch` (1–100). Explanation failures are reported on stderr; add `--fail-on-explain-error` to exit with status 3. JSON output (kotlinx.serialization) includes **`findings`**, **`llmExplanations`** and **`llmExplanationFailures`**.

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

The input syntax is picked from the file extension (`.ttl` Turtle; `.owl`/`.rdf`/`.xml` RDF/XML; `.nt` N-Triples; `.jsonld`/`.json` JSON-LD; anything else is read as Turtle). Override it with `--input-format turtle|rdfxml|ntriples|jsonld`. The file is parsed with its own URI as base IRI, so relative IRIs such as `<> a owl:Ontology` in Turtle or `rdf:about="#Foo"` in RDF/XML resolve against the file (for example `file:///home/me/onto.ttl#Foo`) instead of failing to parse. Options are validated before any model download or ontology load. `pipeline` keeps the enriched graph in memory and writes it to a temporary file only with `--keep-intermediate`.

Errors print a one-line message; add the root option **`--debug`** before the subcommand (`onto-qa --debug check …`) to also print stack traces.

**Report output:** text taken from the ontology (SHACL messages, focus node and shape names) and from the LLM is Markdown-escaped in `--format markdown`, so it cannot inject links, images or raw HTML. In `--format text`, control characters other than newline and tab are shown as visible `\uXXXX` escapes, so terminal escape sequences embedded in the ontology cannot execute. JSON output is serialized with kotlinx.serialization and escapes every string.

See the [module README](../../../tools/onto-quality/library/README.md#cli-exit-codes-and-input-formats) for the complete option validation rules, threshold tuning, and **`KASTOR_SKIP_EMBEDDING_TESTS`** (CI).

## Validation

- Kotlin: `report.conforms` / `report.describeText()` reflect SHACL outcomes for the selected catalogues.
- CLI exit codes (every failure has its own status):

| Exit status | Meaning |
|-------------|---------|
| **0** | Success; no findings at or above `--severity` (default `violation`). |
| **1** | At least one finding is at or above `--severity`; with `--severity info` any finding fails. Used for nothing else. |
| **2** | The input ontology could not be parsed in the selected RDF syntax. |
| **3** | `--fail-on-explain-error` was set and LLM explanations failed or were incomplete (status 1 takes precedence). |
| **4** | Usage or configuration error; nothing was run. Examples: unknown option or bad value, missing or non-existent input file, inconsistent embedding options (`--model custom` without `--onnx`), `metrics --include` with `--format json` or `turtle`. |
| **5** | Runtime error: embedding model download or loading, similarity search budget exceeded, I/O (for example the output cannot be written), or an unexpected internal error. Run `onto-qa --debug <command> …` for the stack trace. |

  Scripts that treated status 1 as "findings or bad usage" should also check 4 and 5. See the [module README](../../../tools/onto-quality/library/README.md#cli-exit-codes-and-input-formats).

## Troubleshooting

- **No embedding findings** — run **`SemanticEnricher`** first when using **`EMBEDDING_QUALITY`** / `skos-vocabulary-embed`; without similarity triples, those shapes usually emit nothing (by design).
- **Missing K07 / pitfall text** — ensure **`OOPS_PITFALL_REGISTRY`** is on the checker when using custom catalogue lists; **`QualityChecker.default()`** and **`onto-qa --catalog all`** include it.
- **HermiT / classpath** — CLI **`--reasoner hermit`** needs **`:rdf:reasoning-hermit`** transitively on the classpath for the packaged CLI.

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
