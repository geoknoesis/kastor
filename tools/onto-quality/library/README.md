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

**Blank nodes** (for example `owl:Restriction` or `owl:intersectionOf` class expressions) have no name, and parser labels change on every parse, so they are identified by a **key** (`QualityFinding.blankNodeKeys`, shown as `_:k…` in the JSON `focusNode`, in LLM prompts and in messages):

- The key hashes the node's **own structure** (its triples and, through other blank nodes, everything nested in it) **and its owner chain** (the predicates leading to it from the IRIs that own it). The same restriction written under two different classes therefore gets two keys and two refs. Siblings are not part of a key: editing one member of an `owl:intersectionOf` list changes the key of that member and of the expressions that contain it, not the keys of the other members. An RDF list is one step whatever its length, and a member's position in the list is not part of its key.
- Keys are **unique within a graph**. Blank nodes that share a key (the same restriction written twice under one class) are numbered: `_:k…`, `_:k…-2`, `_:k…-3`. The numbers follow a canonical order that does not depend on parser labels or triple order: duplicates with different siblings are ordered by those siblings, and duplicates inside one expression by a canonical labelling of that expression. Duplicates that are fully interchangeable (same content, same context, hence the same findings) are numbered in the order in which the graph returns them under their owner; either order gives the same set of keys and refs.
- Only the blank nodes around the findings are read: the blank nodes connected to a finding's blank node, plus the expressions hanging off the same IRI by the same predicate (where a duplicate can be; at most 10,000 per IRI and predicate). The work does not grow with the number of blank nodes in the ontology. Duplicates of a root blank node that hangs off no IRI (`[] a owl:AllDisjointClasses`) are numbered among the findings' own blank nodes only.
- Keys never contain parser labels (also for blank nodes inside RDF 1.2 triple terms), and are computed on the **asserted** graph, so refs are the same with and without `--reasoner`. Only blank nodes introduced by a reasoner are keyed on the materialised graph (`_:i…`). Blank nodes of the shapes graph in a result path are keyed on the shapes graph (`_:s…`).
- When a SHACL message interpolates a blank node (`{$this}`, `{?other}`), the reference `_:label` in the message is replaced by the key (`QualityFinding.stableMessage`), in reports and in the ref. Only whole `_:label` references are replaced: a label that happens to occur inside an IRI or as a word (`b1` in `http://example.org/onto#b1`) is left alone.

Keys changed in this release (they used to hash everything within 8 links of the node, siblings included), so refs of findings on blank nodes differ from earlier releases; refs of findings on IRIs are unchanged.

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

**CLI (`onto-quality-cli`):** enable **`--explain`** on **`check`** or **`pipeline`** when **`KASTOR_ONTO_QUALITY_LLM=true`**. Typical flags: **`--llm-provider`**, **`--llm-model`**, **`--llm-model-preset`**, **`--ollama-base`**, **`--explain-max`**, **`--explain-batch`**, **`--explain-min-severity`**, **`--explain-dry-run`**, **`--markdown-ascii`**. Limits: **`--explain-max`** 1–500 (default 50), **`--explain-batch`** 1–100 (default 12). Reliability: **`--llm-timeout`** (seconds per request, 1–3600, default 60), **`--llm-retries`** (0–10, default 2; exponential backoff with jitter, or the provider's `Retry-After`) and **`--llm-max-duration`** (seconds for the whole explanation run, 1–86400, default 600) and **`--llm-max-output-tokens`** (tokens the model may generate per request, default 8192). JSON output contains **`findings`**, **`llmExplanations`** and **`llmExplanationFailures`**; each finding reference uses **`FindingRef`** (order-independent).

Failures are isolated per batch: explanations from successful batches are kept, and batches that time out, error after retries, or return unparseable/incomplete JSON are recorded in **`ExplainedQualityReport.failures`** (library) and reported on stderr (CLI). Only transient failures are retried — timeouts, HTTP 408 / 429 / 5xx and connection errors; client errors such as HTTP 400 / 401 / 403 / 404, OpenAI `insufficient_quota` (HTTP 429 with no credit left) and TLS / certificate errors fail the batch immediately. After three consecutive batches fail with the same error — non-retryable (for example a bad API key) or retryable with its retries exhausted (for example HTTP 503 throughout) — the remaining batches are not sent, and once the run budget (`maxTotalDuration`) is spent the remaining batches are recorded as failures instead of being sent. By default the CLI still exits normally; pass **`--fail-on-explain-error`** to exit with status **3**. Finding text — and, for the JSON repair request, the model's previous reply — is sent to the model as JSON-encoded data inside tags with an instruction to treat it as data. LLM output and ontology-derived text (SHACL messages, focus and shape names) are Markdown-escaped in reports (no links, images or raw HTML), and control characters are rendered visibly (`\u001B`) in text output so terminal escape sequences cannot run. **`LlmExplanationConfig.toString()`** redacts the API key and the credentials of `baseUrl`; library users can set **`requestTimeout`**, **`maxRetries`**, **`retryBackoff`**, **`maxTotalDuration`**, **`circuitBreakerThreshold`** and **`maxOutputTokens`**. When explanations are incomplete the CLI prints the number of findings left unexplained, per reason when there are several.

**Limits on untrusted text.** In a prompt, every finding field (message, shape, focus node, path element) is cut to **1,000 characters** (marked `…[truncated]`) and a path to 8 elements; a batch whose prompt would still exceed **120,000 characters** is not sent and is recorded as a failure (use a smaller `--explain-batch`). The previous reply echoed in a JSON repair request is cut to 20,000 characters. A failure reason (`ExplanationFailure.reason`) is one line of at most **500 characters**, with control and bidi characters made visible and the API key (whatever its length) and URL credentials (`http://user:password@host`) replaced by `***`: provider error bodies never reach a report unbounded. A request asks the provider for at most `maxOutputTokens` tokens (`--llm-max-output-tokens`, default 8192); a reply of more than **500,000 characters** fails its batch, and every text field of a reply is cut to **2,000 characters** (suggested actions to 10). The exception behind a failed request (HTTP 401, timeout, 5xx, …) is kept in **`ExplanationFailure.cause`**; it is not part of the value (`equals`, `toString`) and is never written to reports. `onto-qa --debug` prints it, sanitised and with keys and URL credentials redacted (at most three distinct causes, 120 lines per stack trace, 2,000 characters per line). `--explain` cannot be combined with `--format turtle` (usage error, status 4, before any LLM call): the Turtle output is a SHACL validation report, which has no place for explanations.

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
| **2** | The input ontology could not be parsed in the selected RDF syntax. A file that cannot be **read** is status 5. |
| **3** | **`--fail-on-explain-error`** was set and LLM explanations failed or were incomplete (status 1 takes precedence). |
| **4** | Usage or configuration error: unknown command or option, bad or out-of-range value, missing or non-existent input file, an input path that is a directory, a `--base-iri` that is not an absolute IRI, `--reasoner hermit` when HermiT is not on the classpath (with guidance), inconsistent embedding options (e.g. `--model custom` without `--onnx`), `--include` with `--format json`/`turtle`, `--explain` with `--format turtle`, an `--output` that is the input file without `--overwrite-input`. Nothing was run. |
| **5** | Runtime error: the input file could not be read (I/O error) or no RDF provider for its syntax is on the class path, embedding model download or loading failed (network, checksum), similarity search budget exceeded, output could not be written, or any unexpected internal error. A one-line message is printed; add **`--debug`** (before or after the command, e.g. `onto-qa check x.ttl --debug`) for the stack trace. |

The two command-line tools of this repository use different conventions (both tables are also printed by `--help`):

| Status | `onto-qa` | `kastor-rdf` |
|--------|-----------|--------------|
| **0** | Success; no findings at or above `--severity`. | Success; for `diff`, the inputs are isomorphic. |
| **1** | Findings at or above `--severity`. | Usage or input error: bad or extra arguments, unknown format, missing file, parse error. |
| **2** | The ontology could not be parsed. | `diff` found the inputs not isomorphic. |
| **3** | LLM explanations failed (with `--fail-on-explain-error`). | Runtime error: I/O, RDF provider, internal error, out of memory or stack overflow. |
| **4** | Usage or configuration error; nothing was run. | not used |
| **5** | Runtime error: I/O (the input cannot be read, the output cannot be written), model download or loading, similarity or LLM budget, internal error. | not used |

**Output:** stdout and stderr are **UTF-8** whatever the platform charset, so `onto-qa check --format json > report.json` is valid UTF-8 on Windows too (in a console, use a UTF-8 code page such as `chcp 65001` to display non-ASCII text). **`--output`** files (and the `enrich` result) are written **atomically** (temporary file in the target directory, then move): parent directories are created, an existing file is replaced, and a failed write leaves the previous file untouched. An `--output` that is the input ontology is refused (status 4) unless **`--overwrite-input`** is given.

Options are validated before any work starts (no model download, no LLM call): **`--format`**, **`--severity`**, **`--catalog`**, **`--reasoner`**, LLM provider/preset choices, **`--threshold`** in [-1, 1], **`--explain-max`** 1–500, **`--explain-batch`** 1–100, **`--max-tokens`** ≥ 1 (≤ 512 for the bundled MiniLM), the `--model` / `--onnx` / `--tokenizer` / `--embedding-dim` combination, **`--similarity-max-work`** ≥ 1, **`--similarity-timeout`** 1–86400, **`--llm-max-duration`** 1–86400.

**Relative IRIs and `--base-iri`:** relative references resolve against **`urn:onto-qa:input/<file name>`** by default, so `<> a owl:Ontology` becomes `urn:onto-qa:input/onto.ttl`, `<#Foo>` / `rdf:about="#Foo"` becomes `urn:onto-qa:input/onto.ttl#Foo` and `<Sub>` becomes `urn:onto-qa:input/Sub`. The base does not depend on the directory, so finding IRIs and `findingRef`s are identical on every machine and no absolute path (for example a user name) appears in reports or LLM prompts. Pass **`--base-iri <absolute IRI>`** (e.g. the ontology's published namespace) to resolve against another base; a non-absolute value is a usage error (status 4).

**Distribution:** `./gradlew :tools:onto-quality-cli:installDist` writes `bin/onto-qa`, `bin/onto-qa.bat` and `lib/`. Both start scripts put one launcher jar on the class path, whose manifest lists the jars of `lib/` in order, so Windows and Unix load the same class path in the same order. Output redirected to a file or a pipe is UTF-8; an interactive console is written in its own charset (on Windows, `chcp 65001` switches the console to UTF-8). An `--output` that cannot be written is refused (status 5) before anything is parsed, loaded or sent to an LLM.

**Warnings:** user-facing warnings — `--explain` without `KASTOR_ONTO_QUALITY_LLM=true` (a failure with status 3 under `--fail-on-explain-error`), or an embedding catalog on a graph without `oqsh:semanticallyCloseTo` triples — are printed on stderr. Library log messages at WARN and above (for example a corrupt model cache being re-downloaded) also go to stderr through the `slf4j-simple` binding bundled with the application (the distribution and `run`; neither the binding nor its `simplelogger.properties` is part of the published `onto-quality-cli` artifact, so embedding the CLI classes in another application does not impose an SLF4J binding or its configuration). Untrusted text in messages (exception messages, paths, LLM failure reasons, and every line of a `--debug` stack trace) has control and bidi characters rendered as `\uXXXX`.

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

**Large vocabularies:** pairwise similarity is an exact search whose work budget (distance evaluations) and deadline scale with the number of labelled entities (at least 50,000,000 evaluations and 30 s). Override them with **`--similarity-max-work`** and **`--similarity-timeout`** (seconds); at most **`--similarity-max-pairs`** (default 1,000,000) similar pairs are materialised. **`--similarity-mode approximate`** switches to random-projection LSH: much faster for tens of thousands of entities, but it may miss some similar pairs (about 95 % recall at cosine 0.85; every reported pair is verified), and the mode is recorded on the enrichment node as `oqsh:similaritySearchMode`. When a budget is exhausted the command stops with exit status **5** and advice for the limit that was hit: a longer timeout or larger work budget (or approximate mode), or — when too many pairs are similar — a higher `--threshold` or a larger `--similarity-max-pairs`.

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
