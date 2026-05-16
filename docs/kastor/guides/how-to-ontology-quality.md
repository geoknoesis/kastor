# How to Check Ontology Quality

{% include version-banner.md %}

## What you'll learn

- Run **bundled SHACL catalogues** (OWL, SKOS, data quality, optional semantic tier) over an ontology graph
- Use **`QualityChecker`** and read **`QualityReport`** / **`QualityFinding`** with pitfall metadata
- Use the **`onto-qa`** CLI (`enrich`, `check`, `pipeline`) and the **embedding** preprocessor (`onto-quality-embed`)

## Prerequisites

Add the modules you need:

| Goal | Gradle dependency |
|------|-------------------|
| Quality API + bundled Turtle shapes | `implementation("com.geoknoesis.kastor:onto-quality:0.2.0")` |
| Embedding / `SemanticEnricher` | `implementation("com.geoknoesis.kastor:onto-quality-embed:0.2.0")` |
| Tests (optional) | Same artifacts on `testImplementation` |

You also need an RDF provider used elsewhere in your project (for example **`rdf-jena`**) so `Rdf.parse` / file IO works the same way as in [How to Validate with SHACL](how-to-validate-shacl.md).

When you use the [Kastor BOM](../getting-started/installation.md), align versions via the BOM instead of repeating `0.2.0`.

## Step 1: Parse the ontology

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat

val ontology = Rdf.parseFromFile("path/to/ontology.ttl", "TURTLE")
```

## Step 2: Run the quality checker

### Default (all bundled catalogues)

```kotlin
import com.geoknoesis.kastor.ontoquality.QualityChecker
import com.geoknoesis.kastor.rdf.shacl.ShaclValidation

val validator = ShaclValidation.validator()
val checker = QualityChecker.default(validator)
val report = checker.check(ontology)

println(report.describeText())
println("Conforms: ${report.conforms}")
```

### One catalogue or a custom subset

```kotlin
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs

val checker = QualityChecker.builder(validator)
    .addCatalog(BundledCatalogs.OWL_QUALITY)
    .build()
val report = checker.check(ontology)
```

### Published SKOS vocabulary stack (no OWL-quality catalogue)

```kotlin
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs

val checker = QualityChecker.builder(validator)
    .addCatalogs(BundledCatalogs.SKOS_VOCABULARY_QC)
    .build()
val report = checker.check(ontology)
```

Use `BundledCatalogs.SKOS_VOCABULARY_QC_WITH_EMBEDDING` after **`SemanticEnricher`** so **embedding-quality** shapes apply.

Catalogue ids match the CLI `--catalog` flag: `owl-quality`, `skos-validation`, `data-quality`, `embedding-quality`, `modern-engineering`, `rdf12-quality`, `skos-vocabulary` (SKOS + data + modern + RDF12, no OWL), `skos-vocabulary-embed` (same plus embedding shapes; use after enrich or `pipeline`), `all`.

## Step 3 (optional): Semantic tier — enrich then validate

Shapes in **`embedding-quality`** expect **`oqsh:semanticallyCloseTo`** triples (and optional **`oqsh:labelDefinitionDriftScore`**) produced by **`SemanticEnricher`**. Without enrichment, those shapes usually produce **no** findings (graceful degradation).

```kotlin
import com.geoknoesis.kastor.ontoquality.embed.SemanticEnricher
import com.geoknoesis.kastor.ontoquality.catalog.BundledCatalogs

val enriched = SemanticEnricher.default().enrich(ontology)

val checker = QualityChecker.builder(validator)
    .addCatalog(BundledCatalogs.EMBEDDING_QUALITY)
    .build()

val report = checker.check(enriched)
```

On first use, the default MiniLM ONNX model is downloaded under **`~/.kastor/onto-quality/models/`**. Override the cache root with **`KASTOR_MODEL_CACHE`** or **`-Dkastor.onto-quality.model-cache=...`**.

### Domain-specific embeddings (e.g. medical)

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

## `onto-qa` CLI

The **`:tools:onto-quality-cli`** module ships the **`onto-qa`** application (`com.geoknoesis.kastor.ontoquality.cli.MainKt`).

Run via Gradle from the repository root:

```bash
./gradlew :tools:onto-quality-cli:run --args="check path/to/ontology.ttl --catalog all"
./gradlew :tools:onto-quality-cli:run --args="check path/to/skos.ttl --catalog skos-vocabulary"
./gradlew :tools:onto-quality-cli:run --args="enrich path/to/ontology.ttl --output path/to/enriched.ttl"
./gradlew :tools:onto-quality-cli:run --args="pipeline path/to/ontology.ttl --catalog skos-vocabulary-embed --severity info"
```

See the [module README](../../../tools/onto-quality/README.md) for threshold tuning, exit codes, and **`KASTOR_SKIP_EMBEDDING_TESTS`** (CI).

## Calibration and pitfall metadata

Findings can carry **OOPS!**-style pitfall codes (and **SKOS** / **convention** labels) when the shape declares them. Calibration against the OOPS! reference corpus is documented in the repo:

- [CALIBRATION.md](../../../tools/onto-quality/CALIBRATION.md)

## Related

- [Ontology Quality feature overview](../features/ontology-quality.md) — modules, tiers, and architecture at a glance
- [SHACL Validation](../features/shacl-validation.md) — underlying validator and profiles
- [How to Validate with SHACL](how-to-validate-shacl.md) — generic shapes + `ShaclValidation.validator()`
- [SHACL validation architecture](../design/shacl-validation-architecture.md) — engine and provider notes
