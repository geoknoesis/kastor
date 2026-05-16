# `onto-quality` v0.1 — Calibration against the OOPS! reference suite

This document records how Kastor's `onto-quality` v0.1 OWL shape bundle performs against
the OOPS! (OntOlogy Pitfall Scanner!) reference test ontologies.
OOPS! is the established baseline for OWL ontology quality checking;
its test ontologies are the closest available thing to a community-agreed ground truth.

Source corpus: [https://github.com/oeg-upm/OOPS](https://github.com/oeg-upm/OOPS) (Apache 2.0)

Calibration date: **2026-05-15** (last verified: same date; `./gradlew :tools:onto-quality:cleanTest :tools:onto-quality:test --tests "*OopsCalibrationTest*"` with **`KASTOR_SKIP_EMBEDDING_TESTS` unset** — BUILD SUCCESSFUL)

Kastor / `onto-quality` version (Gradle `rootProject.version`): **0.2.0** — the primary OWL catalogue ships in `owl-quality-shacl.ttl`; this document covers the **v0.1** structural baseline **plus** the **v0.1.x** extensions and the **v0.2** semantic harness. Authoritative triage of every upstream fixture is recorded in [`docs/PITFALL_TRIAGE.md`](./docs/PITFALL_TRIAGE.md).

## How the OOPS! test corpus is laid out

Inspection of the OOPS! repository shows:

- **`run/test-pitfall.sh`** is the authoritative CLI driver: it validates a pitfall id against **`src/test/resources/data/input/<pitfall>.owl`** (for example `P04`, `P22M1`).
- **`src/test/resources/data/input/`** contains **38** RDF/XML inputs (`P02.owl` … `P41.owl`, including hyphenated variants such as `P10-A.owl`).
- There is **no** `P09.owl`, and **`P09` is absent from the `valid_pitfalls` array** in `test-pitfall.sh`, so the open-source reference suite does not ship a dedicated “P09” regression file in that tree.

For this module, selected inputs were converted to Turtle and committed under `src/test/resources/oops-corpus/` (see [ATTRIBUTION.md](./src/test/resources/oops-corpus/ATTRIBUTION.md)).

## Summary

| Calibration slice | Invocations (latest run) | Passing | Failing | Skipped / gated |
|-------------------|-------------------------:|--------:|--------:|-----------------|
| Structural — original v0.1 rows (`calibrationCases()` in `OopsCalibrationTest`) | 15 | **14** | 0 | **1** (`P09` — skipped at assumption: no `P09.ttl` on classpath) |
| Structural — **v0.1.x** extension (`extended OOPS pitfall fixtures`) | 10 | **10** | 0 | 0 |
| Semantic tier (nested `SemanticTierAfterEnrichment`; ONNX + `EMBEDDING_QUALITY`) | 4 | **4** | 0 | **4** not executed when `KASTOR_SKIP_EMBEDDING_TESTS=1` |

**Combined outcome (latest run with `KASTOR_SKIP_EMBEDDING_TESTS` unset):** **28** passing pitfall assertions (**24** structural + **4** semantic), **0** failures, **1** skip (**P09** — no fixture). With semantic tests gated off, **24** structural assertions still pass; the **4** semantic rows are not executed.

### Embedding pitfalls matrix

`EMBEDDING_QUALITY` after `SemanticEnricher.default()`; asserted by **`OopsCalibrationTest.SemanticTierAfterEnrichment`** on `oops-corpus/*.ttl` (MiniLM **all-MiniLM-L6-v2**, default cosine threshold **0.85** for `oqsh:semanticallyCloseTo`).

| Pitfall | OOPS! name | Corpus TTL | Shape(s) | Status |
|---------|------------|------------|----------|--------|
| P02 | Synonyms as classes | `oops-corpus/P02.ttl` | `SynonymousClassCandidatesShape` | ✓ Pass |
| P12 | Synonyms as properties (variant) | `oops-corpus/P12.ttl` | `SynonymousPropertyCandidatesShape` | ✓ Pass |
| P21 | Miscellaneous classes | `oops-corpus/P21.ttl` | `JunkDrawerClassShape` | ✓ Pass |
| P32 | Same label, different class | `oops-corpus/P32.ttl` | `SameLabelDifferentClassShape` | ✓ Pass |

`./gradlew :tools:onto-quality:test --tests "*OopsCalibrationTest*"` schedules **29** JUnit test invocations when embeddings are enabled: **15** original structural + **10** v0.1.x + **4** semantic. With **`KASTOR_SKIP_EMBEDDING_TESTS=1`**, the nested semantic class is disabled (**25** invocations: structural only).

**Status legend (all tables below)**

- ✓ Pass — the expected OOPS pitfall code is reported among findings for that reference ontology.
- ✗ Fail — the reference ontology exists but the pitfall code is not detected.
- ⊘ Skip — no OOPS reference file is available for that pitfall in the corpus we import.

## Per-pitfall results — structural (`OWL_QUALITY`)

Statuses refer to detection of the corresponding **`PitfallReference.Oops`** code in `owl-quality-shacl.ttl` when validating the OOPS reference TTL listed in `oops-corpus/`. **Semantic / embedding pitfalls (P02, P12, P21, P32)** use `embedding-quality-shacl.ttl` only after enrichment — see **Summary → Embedding pitfalls matrix** and [§ v0.2 — Semantic Tier](#v02--semantic-tier).

| Pitfall | OOPS! name | Our shape(s) | Status | Notes |
|---------|------------|--------------|--------|-------|
| P04 | Creating unconnected ontology elements | `OrphanClassShape` | ✓ Pass | |
| P06 | Including cycles in a class hierarchy | `NoSubClassCycleShape`, `NoSubPropertyCycleShape` | ✓ Pass | |
| P08 | Missing annotations (sub-types P08-A / P08-C / P08-L in OOPS!) | `ClassHasLabelShape`, `ObjectPropertyHasLabelShape`, `DatatypePropertyHasLabelShape`, `ClassHasDefinitionShape` | ✓ Pass | Extra findings (other shapes) may appear; not asserted against. |
| P09 | Missing basic information (catalogue alignment) | `OntologyMetadataShape` | ⊘ Skip | **No** `P09.owl` under OOPS `data/input/` and `P09` not in `valid_pitfalls` for the CLI script in the revision examined. Calibration test skips when the resource is absent. |
| P11 | Missing domain or range in properties | `ObjectPropertyHasDomainShape`, `ObjectPropertyHasRangeShape`, `DatatypePropertyHasDomainShape`, `DatatypePropertyHasRangeShape` | ✓ Pass | |
| P19 | Defining multiple domains or ranges in properties | `SingleDomainShape`, `SingleRangeShape` | ✓ Pass | |
| P22 | Using different naming conventions in the ontology | `ClassNameUpperCamelShape`, `PropertyNameLowerCamelShape` | ✓ Pass | OOPS uses **four** inputs (`P22M1`…`P22M4`); all four pass. |
| P25 | Defining a relationship as inverse to itself | `NoSelfInverseShape` | ✓ Pass | |
| P26 | Defining inverse relationships for a symmetric one | `NoInverseForSymmetricShape` | ✓ Pass | |
| P27 | Defining wrong equivalent properties | `WrongEquivalentPropertiesShape` | ✓ Pass | The OOPS `P27.owl` fixture exercises **equivalent properties with mismatched domain/range**. `EquivalenceVsSubclassShape` is a separate **convention** check (redundant `rdfs:subClassOf` alongside `owl:equivalentClass`) referenced in its comment only—it is **not** tagged as OOPS P27. |
| P34 | Untyped class | `UntypedClassShape` | ✓ Pass | |
| P35 | Untyped property | `UntypedPropertyShape` | ✓ Pass | |

## v0.1.x — Extended structural coverage

This release extends the structural tier to OOPS pitfalls whose fixtures were historically absent from the original “twelve-shape” scope. See **`docs/PITFALL_TRIAGE.md`** for rationales, audit trail, and catalogued pitfalls lacking fixtures.

### New pitfalls covered (OOPS `src/test/resources/data/input/` → `oops-corpus/*.ttl`)

| Pitfall | OOPS! name | Shape | Status |
|---------|------------|-------|--------|
| P03 | Relationship “is/has” naming | `NoMetaPredicateNameShape` | ✓ Pass |
| P13 | Missing inverse (M1 heuristic) | `CandidateMissingInverseShape` | ✓ Pass |
| P20 | Misused documentation annotations | `MisusedDocumentationAnnotationsShape` | ✓ Pass (covers empty / duplicate literals; swapped label↔comment text remains backlog) |
| P24 | Recursive class / domain patterns | `RecursiveClassExpressionShape`, `RecursivePropertyDomainRestrictionShape` | ✓ Pass |
| P33 | Singleton `owl:propertyChainAxiom` list | `SingletonPropertyChainShape` | ✓ Pass |
| P36 | Ontology URI ends with `.owl` / similar | `OntologyIriEndsWithSerializationSuffixShape` | ✓ Pass |
| P38 | Missing `owl:Ontology` declaration | `OntologyDeclarationShape` | ✓ Pass (`P38.ttl`) — implementation ignores the bundled shape-library ontology IRI when testing for a user declaration |
| P39 | Anonymous ontology head | `AnonymousOntologyHeadShape` | ✓ Pass |
| P40 | Namespace host mismatch (imports-aware) | `NamespaceMismatchShape` | ✓ Pass |
| P41 | No license on ontology | `OntologyLicenseShape` | ✓ Pass |

### Coverage after this extension (unique pitfall numbers)

- **Structural (implemented + calibrated here):** **22** codes (12 legacy + 10 extension) across **32** OOPS pitfall numbers that have upstream fixtures.
- **Semantic (v0.2, embedding pitfalls):** **P02, P12, P21, P32** — `embedding-quality-shacl.ttl`; **4** / **4** ✓ in calibration (see **Summary → Embedding pitfalls matrix**).
- **REASONING (v0.3+ backlog via triage):** P05, P28, P29, P31, P30 (mixed), etc.
- **OUT_OF_SCOPE:** P07, P10, P17-style judgement calls, etc.

## Where the old “deferred pitfalls” table went

The long table of “not in v0.1” pitfalls is **obsolete** after the v0.1.x extension. **`docs/PITFALL_TRIAGE.md`** is now the canonical, fixture-level record (including audit overrides and items without OOPS inputs).

## Failing tests — v0.2 backlog

**None** at the time of this calibration run: all executed structural and semantic (embedding) cases **passed**; only **`P09`** was **skipped** for lack of an upstream fixture. Semantic tier: **4** / **4** pitfall codes (**P02, P12, P21, P32**) when ONNX tests are enabled.

If a future OOPS revision or shape change causes a regression, capture it here (pitfall id, corpus path, actual vs expected findings) instead of “papering over” the report.

## Benchmark vs OOPS! corpus (latency harness)

The upstream OOPS! CLI (`run/test-pitfall.sh` → `mvn exec:java` in the OOPS repo) is not run from this Gradle module. **OopsCalibrationTest** asserts pitfall codes on each fixture; **OopsBenchmarkTest** only measures wall-clock for `QualityChecker.check` over the bundled `oops-corpus/*.ttl` files.

Enable benchmarks (prints tables to test stdout):

```bash
export KASTOR_OOPS_BENCHMARK=1
./gradlew :tools:onto-quality:test --tests "*OopsBenchmarkTest*"
```

PowerShell:

```powershell
$env:KASTOR_OOPS_BENCHMARK='1'
./gradlew :tools:onto-quality:test --tests '*OopsBenchmarkTest*'
```

- **Structural:** all available Turtle fixtures, `BundledCatalogs.OWL_QUALITY`, configurable warmup and iterations via `-Dkastor.oops.benchmark.warmup=…` and `-Dkastor.oops.benchmark.iterations=…` (defaults documented in `OopsBenchmarkTest`).
- **Semantic:** P02, P12, P21, P32 — one ONNX enrichment pass timed separately, then repeated `EMBEDDING_QUALITY` checks (`-Dkastor.oops.benchmark.embeddingWarmup` / `embeddingIterations`). Skipped when `KASTOR_SKIP_EMBEDDING_TESTS=1`.

## Reproducing this calibration

```bash
cd /path/to/kastor
./gradlew :tools:onto-quality:test --tests "*OopsCalibrationTest*"
```

Or a single pitfall display name:

```bash
./gradlew :tools:onto-quality:test --tests "*OopsCalibrationTest.*P04*"
```

Full module test suite:

```bash
./gradlew :tools:onto-quality:test
```

## v0.1.x — Modern engineering coverage

These shapes have no OOPS! equivalent — they cover pitfall categories
that emerged after OOPS! was finalized in 2014. There is no external
reference corpus to calibrate against, so calibration is against
internal fixtures only.

| Pitfall | Name | Category | Status |
|---------|------|----------|--------|
| N03 | Non-persistent identifier | FAIR_COMPLIANCE | ✓ |
| N04 | License not machine-readable | FAIR_COMPLIANCE | ✓ |
| N06 | No contact point | FAIR_COMPLIANCE | ✓ |
| N07 | Unversioned imports | FAIR_COMPLIANCE | ✓ |
| N09 | Inconsistent hash/slash namespaces | URI_HYGIENE | ✓ |
| N16 | Cryptic local names | LLM_CONSUMABILITY | ✓ |
| N17 | Sentence-shaped labels | LLM_CONSUMABILITY | ✓ |
| N20 | Classes without examples | LLM_CONSUMABILITY | ✓ |
| N23 | Class-as-instance metamodelling | OWL_ANTI_PATTERNS | ✓ |
| N26 | Unused imports | VOCABULARY_REUSE | ✓ |
| N32 | Mixed tagged/untagged labels | MULTILINGUAL | ✓ |
| N34 | Named blank-node entities | MAINTAINABILITY | ✓ |

## v0.1.x — RDF 1.2 conformance

| Pitfall | Name | Status | Notes |
|---------|------|--------|-------|
| N28 | Legacy reification | ✓ | |
| N29 | RTL labels without direction | ✓ | |
| N30 | Triple term in subject | ⊘ | Untestable via Turtle fixture; engines reject at parse |

## Coverage summary after v0.1.x

From the **baseline OOPS structural slice** (twelve original calibrated codes in `owl-quality-shacl.ttl`), plus **12** modern engineering (`N`) pitfalls and **3** RDF 1.2 pitfalls, you have **27 STRUCTURAL pitfalls** in that narrow accounting — before counting the further **v0.1.x extended OOPS rows** in `owl-quality-shacl.ttl` documented in the table above (“v0.1.x — Extended structural coverage”). The **N** shapes address categories OOPS! does not cover at all.

## v0.2 — Semantic Tier

The semantic tier adds embedding-driven materialization of `oqsh:semanticallyCloseTo` (via `:tools:onto-quality-embed` / `SemanticEnricher`) and a dedicated SHACL bundle `embedding-quality-shacl.ttl` that consumes those triples together with pattern-based label checks.

Model used: **all-MiniLM-L6-v2** (sentence-transformers ONNX export)  
Default cosine threshold for `semanticallyCloseTo`: **0.85**

CI / resource-constrained hosts: set **`KASTOR_SKIP_EMBEDDING_TESTS=1`** so Gradle skips the ONNX-backed calibration nest (`OopsCalibrationTest.SemanticTierAfterEnrichment`). Structural OOPS calibration remains enabled.

Pass/fail status for each semantic code is in the **Summary** section (**Embedding pitfalls matrix** table above). Notes:

- **P02:** synthetic `oops-corpus/P02.ttl` (“Car” / “Automobile”); upstream OOPS `P02.owl` ties classes with `owl:equivalentClass` but dissimilar labels—unsuitable for embedding synonym detection (see `ATTRIBUTION.md`).
- **P12:** OOPS `P12.owl` Turtle conversion — duplicate labels on distinct object/datatype properties in two namespaces.
- **P21:** OOPS `P21.owl` conversion; labels match junk-drawer regex patterns.
- **P32:** OOPS `P32.owl` conversion; `SameLabelDifferentClassShape` is severity **Warning** in SHACL.

Deterministic reproduction (requires ~300 MB heap for the model; **do not** set `KASTOR_SKIP_EMBEDDING_TESTS`):

```bash
./gradlew :tools:onto-quality:test --tests "*OopsCalibrationTest*SemanticTierAfterEnrichment*"
```

### Threshold sensitivity (P02 synthetic pair)

On `oops-corpus/P02.ttl` (exactly one unordered class pair), **pair counts** above each cosine threshold are:

| Threshold | Pairs ≥ threshold |
|-----------|-------------------|
| 0.70 | 1 |
| 0.75 | 1 |
| 0.80 | 1 |
| 0.85 | 1 |
| 0.90 | 0 |
| 0.95 | 0 |

Empirical cosine for “Car” vs “Automobile” with this export lies **between 0.85 and 0.90**, so tightening to **0.90** drops detection for this pair; loosening to **0.80** still yields one pair (no extra noise on this two-class fixture).

The same sweep guides **P12** synonym-property detection, which relies on the same embedding space (e.g. near-duplicate labels such as `relationship T` on distinct IRIs remain ≥ 0.85 with this model).
