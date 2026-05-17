# OOPS! pitfall coverage — triage and decisions

This document records, for every OOPS! pitfall that has a regression fixture in [`oeg-upm/OOPS`](https://github.com/oeg-upm/OOPS) under `src/test/resources/data/input/`, whether `onto-quality` covers it in the bundled **OWL** SHACL catalogue, defers it to a later tier, or considers it out of scope.

**Upstream fixtures path (authoritative):** `src/test/resources/data/input/` (wired from `run/test-pitfall.sh` via `src/test/resources/data/input/<id>.owl`).

Surveyed corpus git revision: **`11572ea6b31cba29dd3f8a443e24311652f059de`** (cloned at **`D:/work/oops-source`**, mirrored from GitHub [`oeg-upm/OOPS`](https://github.com/oeg-upm/OOPS); this matches the authoritative remote history).

Surveyed on: **2026-05-15** (ISO 8601 date).

---

## Coverage stack (how the pieces fit)

| Layer | Role |
|-------|------|
| **`BundledCatalogs.OOPS_PITFALL_REGISTRY`** | Machine-readable **OOPS! P01–P41** text plus **Kastor K01–K07** (`sh:deactivated true`; documentation + pitfall metadata only). |
| **`OWL_QUALITY`** | Active SHACL for **structural** OOPS-aligned checks, **N**-style conventions, **K01** (`owl:imports` cycle), and synthetic/heuristic **P01** / **P23**. |
| **`EMBEDDING_QUALITY`** | **Semantic** tier: **P02, P12, P21, P32** after `SemanticEnricher` materializes similarity triples. |
| **HermiT profile** (`QualityChecker.check(..., HERMIT)`) | **Reasoning** preflight: single DL `reason()` pass, materialized graph → SHACL, plus **K07** rows when the ontology is **inconsistent**. Pitfall metadata for **K07** is included in **`QualityChecker.default()`** / **`BundledCatalogs.allWithOopsRegistry`** (deactivated registry shapes). |

---

## Fixtures discovered

The following **`*.owl`** files exist under **`src/test/resources/data/input/`** (38 RDF/XML inputs). Pitfall identifiers follow OOPS **`valid_pitfalls`** in `run/test-pitfall.sh`.

| Fixture | Pitfall grouping | Notes |
|--------|------------------|-------|
| `P02.owl` | P02 | |
| `P03.owl` | P03 | |
| `P04.owl` | P04 | v0.1 structural |
| `P05.owl` | P05 | |
| `P06.owl` | P06 | v0.1 structural |
| `P07.owl` | P07 | |
| `P08.owl` | P08 | v0.1 structural |
| `P10.owl`, `P10-A.owl`, `P10-B.owl`, `P10-C.owl` | P10 | Disjointness family |
| `P11.owl` | P11 | v0.1 structural |
| `P12.owl` | P12 | |
| `P13.owl` | P13 | |
| `P19.owl` | P19 | v0.1 structural |
| `P20.owl` | P20 | |
| `P21.owl` | P21 | |
| `P22M1.owl` … `P22M4.owl` | P22 | Four naming-regression variants (v0.1 structural) |
| `P24.owl` | P24 | |
| `P25.owl` | P25 | v0.1 structural |
| `P26.owl` | P26 | v0.1 structural |
| `P27.owl` | P27 | v0.1 structural |
| `P28.owl` | P28 | |
| `P29.owl` | P29 | |
| `P30.owl` | P30 | |
| `P31.owl` | P31 | |
| `P32.owl` | P32 | |
| `P33.owl` | P33 | Property-chain singleton |
| `P34.owl` | P34 | v0.1 structural |
| `P35.owl` | P35 | v0.1 structural |
| `P36.owl` | P36 | Ontology URI ends with `.owl` |
| `P38.owl` | P38 | No `owl:Ontology` in document graph |
| `P39.owl` | P39 | Anonymous `owl:Ontology` head |
| `P40.owl` | P40 | FOAF namespace “hijacking” |
| `P41.owl` | P41 | Missing licence on ontology |

**Absent from upstream inputs:** **`P09.owl`** — not listed in upstream `valid_pitfalls`; calibration still references a synthetic/skipped corpus row if present.

---

## Summary

|Pitfalls (unique numbers with fixtures) | Count |
|----------------------------------------|------:|
| STRUCTURAL (original v0.1 bundle) | 12 codes — P04, P06, P08, P09, P11, P19, P22, P25, P26, P27, P34, P35 |
| STRUCTURAL (this task, v0.1.x) | **10** codes — **P03, P13, P20, P24, P33, P36, P38, P39, P40, P41** |
| STRUCTURAL (synthetic / heuristic, OOPS text only or no upstream `*.owl`) | **P01** (class∩property proxy), **P23** (XSD-token class-name heuristic), **K01** (import cycle — Kastor) |
| SEMANTIC (v0.2 `embedding-quality-shacl.ttl`) | 4 — **P02, P12, P21, P32** |
| REASONING — **implemented (v0.4)** | **K07** — ontology **inconsistent** under HermiT (`check(..., HERMIT)`); surfaced as SHACL-shaped findings, not a separate report type |
| REASONING — **backlog** (no dedicated automated substitute yet) | **P05, P14, P15, P16, P28, P29, P30, P31** (and similar DL-judgement cases) |
| OUT_OF_SCOPE for pure SHACL / open-world | **P07, P10, P17, P18** (judgement, policy, or absence-of-axiom heuristics we deliberately avoid) |
| **Total distinct OOPS pitfall numbers with ≥1 fixture** | **32** |
| **Total fixture files** | **38** |

**Coverage overview:** **`owl-quality-shacl.ttl`** tags **24** distinct **P** numbers (original 12 + extension 10 + **P01** + **P23**); **`embedding-quality-shacl.ttl`** adds **P02, P12, P21, P32** → **28** unique **P** codes with active automation when semantic inputs exist. **`P09`** keeps a shape though the upstream OOPS tree has no `P09.owl`. **K01** and **K07** are **Kastor** extensions (not OOPS ids); **K02–K06** are documented in the registry for pipeline/reasoning methodology only.

---

## Per-pitfall decisions (REMAINING set)

The **REMAINING** set is every pitfall that appears in `data/input/` but was **not** among the original twelve v0.1 codes:  
**P02, P03, P05, P07, P10, P12, P13, P20, P21, P24, P28, P29, P30, P31, P32, P33, P36, P38, P39, P40, P41**.

### P02 — Creating synonyms as classes

- **OOPS! fixture:** `src/test/resources/data/input/P02.owl`
- **OOPS! importance:** Important (catalogue)
- **Decision:** **SEMANTIC**
- **Shape added (if STRUCTURAL):** — (use `SynonymousClassCandidatesShape` in `embedding-quality-shacl.ttl`; calibration uses a **synthetic** `P02.ttl` for embedding-friendly labels — see `ATTRIBUTION.md`).
- **Rationale:** Lexical synonymy without declared `owl:equivalentClass` requires similarity (or manual review), not SHACL alone.
- **What the fixture contains:** Two classes interlinked with `owl:equivalentClass` but dissimilar human-readable labels (the upstream file is a poor fit for cosine-only synonym detection).
- **Calibration test result (if shape added):** **PASS** (semantic nested test, when embeddings enabled).
- **Notes:** v0.2 catalogue reference: `oqsh:SynonymousClassCandidatesShape`, pitfall `P02`.

### P03 — Relationship “is” instead of using `rdf:type` / `rdfs:subClassOf`

- **OOPS! fixture:** `src/test/resources/data/input/P03.owl`
- **Decision:** **STRUCTURAL** (overrides prior “semantic” guess — local names are explicit in RDF).
- **Shape added:** `oqsh:NoMetaPredicateNameShape`
- **Rationale:** OOPS encodes object properties `#is`, `#is-a`, `#isA`, `#is_a`; these are pure lexical / graph declarations.
- **Fixture contents:** Four `owl:ObjectProperty` resources with meta-style local names.
- **Calibration:** **PASS** (`oops-corpus/P03.ttl`).

### P05 — Wrong inverse relationships

- **Fixture:** `P05.owl`
- **Decision:** **REASONING**
- **Rationale:** Detecting **wrong** inverses needs pairwise satisfiability checks against the axiom closure, not naked triple patterns.
- **Fixture contents:** Deliberately inconsistent inverse pairs (per OOPS documentation).
- **Calibration:** N/A (deferred).

### P07 — Merging different concepts

- **Fixture:** `P07.owl`
- **Decision:** **OUT_OF_SCOPE** (for SHACL)
- **Rationale:** Requires judgement over near-duplicate natural-language labels (“bed and breakfast” vs permuted tokens) without a reliable structural discriminant.
- **Fixture contents:** Lexically related class labels in one namespace.
- **Notes:** Could be revisited with embeddings (not currently tagged in `embedding-quality-shacl.ttl`).

### P10 — Missing disjointness

- **Fixtures:** `P10.owl`, `P10-A.owl`, `P10-B.owl`, `P10-C.owl`
- **Decision:** **OUT_OF_SCOPE** (open-world + policy)
- **Rationale:** Absence of `owl:disjointWith` is entailed-compatible; global heuristics create massive false positives on domain ontologies that intentionally stay non-disjoint.
- **Calibration:** N/A.

### P12 — Equivalent properties not explicitly declared (variant)

- **Fixture:** `P12.owl`
- **Decision:** **SEMANTIC**
- **Shape:** `SynonymousPropertyCandidatesShape` (`embedding-quality-shacl.ttl`), pitfall `P12`.
- **Rationale:** Near-duplicate property labels / implicit equivalence is embedding territory.
- **Calibration:** **PASS** (semantic tier).

### P13 — Missing inverse (and related inverse hygiene)

- **Fixture:** `P13.owl`
- **Decision:** **STRUCTURAL** (semi-automated **suggestion** only).
- **Shape:** `oqsh:CandidateMissingInverseShape`
- **Rationale:** Upstream file names relationships with domain/range but no `owl:inverseOf`; this is a conservative graph pattern (filtered to non-functional / non-symmetric props per shape comment).
- **Fixture contents:** `relationshipNoInverse` plus two “suggestion” properties with explicit domain/range.
- **Calibration:** **PASS** (`P13.ttl`).

### P20 — Misusing ontology annotations

- **Fixture:** `P20.owl`
- **Decision:** **STRUCTURAL** (partial automation).
- **Shape:** `oqsh:MisusedDocumentationAnnotationsShape`
- **Rationale:** The OOPS file encodes **empty** `rdfs:label` / `rdfs:comment` literals, plus cases where label text equals comment text — all visible in RDF without NLP.
- **Fixture contents:** Parallel patterns for classes, object properties, and datatype properties (patterns A–D in OOPS comments).
- **Calibration:** **PASS** (`P20.ttl`).
- **Notes:** “Label/comment **content swap**” (pattern A — text moved to the wrong annotation slot) is **not** structurally unique; it remains **backlog** for embeddings or human review.

### P21 — Miscellaneous class

- **Fixture:** `P21.owl`
- **Decision:** **SEMANTIC**
- **Shape:** `MiscellaneousClassShape` / related rules in `embedding-quality-shacl.ttl` (pitfall `P21`).
- **Calibration:** **PASS** (semantic tier).

### P24 — Recursive definitions

- **Fixture:** `P24.owl`
- **Decision:** **STRUCTURAL**
- **Shapes:** `oqsh:RecursiveClassExpressionShape`, `oqsh:RecursivePropertyDomainRestrictionShape`
- **Rationale:** OOPS uses explicit RDF: `owl:unionOf` lists containing the same class as the subject, and `owl:Restriction` domains whose `owl:onProperty` points back to the property.
- **Calibration:** **PASS** (`P24.ttl`).

### P28 — Wrong symmetric property

- **Fixture:** `P28.owl`
- **Decision:** **REASONING**
- **Rationale:** Needs satisfiability / model-theoretic check over symmetry constraints plus domain usage.
- **Calibration:** N/A.

### P29 — Wrong transitive property

- **Fixture:** `P29.owl`
- **Decision:** **REASONING**
- **Rationale:** Same class of problem as P28 with transitivity semantics.
- **Calibration:** N/A.

### P30 — Missing equivalent classes

- **Fixture:** `P30.owl`
- **Decision:** **SEMANTIC / REASONING hybrid (deferred)**
- **Rationale:** Discovering **missing** `owl:equivalentClass` statements between distinct IRIs generally needs reasoning or similarity; there is **no** dedicated `P30` shape in `embedding-quality-shacl.ttl` yet.
- **Calibration:** N/A (documented backlog).

### P31 — Wrong equivalent classes

- **Fixture:** `P31.owl`
- **Decision:** **REASONING**
- **Rationale:** Needs consistency checking over equivalence lattice.
- **Calibration:** N/A.

### P32 — Same label, different class

- **Fixture:** `P32.owl`
- **Decision:** **SEMANTIC**
- **Shape:** `SameLabelDifferentClassShape` (see `embedding-quality-shacl.ttl`, pitfall `P32`).
- **Calibration:** **PASS** (semantic tier).

### P33 — Property chain with a single property

- **Fixture:** `P33.owl`
- **Decision:** **STRUCTURAL** (overrides older “reasoning-only” guidance — the RDF list has a single `rdf:first` member and `rdf:rest rdf:nil`).
- **Shape:** `oqsh:SingletonPropertyChainShape`
- **Calibration:** **PASS** (`P33.ttl`).

### P36 — Ontology URI contains a file extension

- **Fixture:** `P36.owl`
- **Decision:** **STRUCTURAL**
- **Shape:** `oqsh:OntologyIriEndsWithSerializationSuffixShape`
- **Rationale:** The regression ontology IRI ends with `.owl`; this is a deterministic lexical check on the ontology IRI string.
- **Calibration:** **PASS** (`P36.ttl`).

### P38 — No OWL ontology declaration

- **Fixture:** `P38.owl`
- **Decision:** **STRUCTURAL**
- **Shape:** `oqsh:OntologyDeclarationShape`
- **Rationale:** Graph declares `owl:Class` individuals but no user `owl:Ontology` resource.
- **Calibration:** **PASS** (`P38.ttl`).
- **Implementation note:** Validation merges the user graph with the bundled SHACL library graph (which itself declares `<http://example.org/owl-quality-shacl> a owl:Ontology`). The shape therefore counts only **non-library** ontology heads via `FILTER (?userOnt NOT IN (<http://example.org/owl-quality-shacl>))`.

### P39 — Ambiguous namespace

- **Fixture:** `P39.owl`
- **Decision:** **STRUCTURAL** (narrow interpretation)
- **Shape:** `oqsh:AnonymousOntologyHeadShape`
- **Rationale:** The published fixture uses a **blank-node** `owl:Ontology` while term IRIs are named under `http://oops.linkeddata.es/data/testP39#` — a structural mismatch between anonymous ontology head and dereferenceable namespace.
- **Calibration:** **PASS** (`P39.ttl`).
- **Notes:** Broader “same namespace, two prefixes” issues are not visible as triples; those remain **OUT_OF_SCOPE** unless prefix maps are carried alongside the graph.

### P40 — Namespace hijacking

- **Fixture:** `P40.owl`
- **Decision:** **STRUCTURAL** (heuristic)
- **Shape:** `oqsh:NamespaceMismatchShape`
- **Rationale:** Detects HTTP(S) classes/properties whose **host** differs from the host of a named `owl:Ontology`, unless `owl:imports` licenses the foreign namespace. Tuned to the FOAF-style hijack in the OOPS file and conservatively limited to `http(s)` IRIs to avoid `file:` / `jar:` test noise.
- **Calibration:** **PASS** (`P40.ttl`).

### P41 — No license declared

- **Fixture:** `P41.owl`
- **Decision:** **STRUCTURAL**
- **Shape:** `oqsh:OntologyLicenseShape` (pitfall code updated from `convention` to **`P41`**).
- **Rationale:** The fixture’s ontology omits `dct:license`; the existing min-cardinality property shape fires unchanged except for metadata labelling.
- **Calibration:** **PASS** (`P41.ttl`).

---

## Pitfalls in the OOPS catalogue but **not** in `src/test/resources/data/input/`

Examples that OOPS documents in the literature / web UI but **do not** ship as `*.owl` inputs in the tree above (non-exhaustive): **P14, P15, P16, P17, P18, …** — any implementation without an upstream `.owl` remains **uncalibrated** against OOPS’s own regression suite. (Synthetic **`P01.ttl` / `P09.ttl` / `P23.ttl`** complement the corpus for shapes in `OWL_QUALITY`; **`K01.ttl`** exercises **K01**; **`K07.ttl`** (individual typed as `owl:Nothing`) exercises **K07** when `check(..., HERMIT)` and HermiT are available.

---

## Decision audit trail

| Pitfall | Prior guidance (task brief) | Final decision | Why it changed |
|--------|-----------------------------|----------------|----------------|
| P03 | Often tagged “semantic / heuristic” | **STRUCTURAL** | Local names in the OOPS RDF are explicit tokens (`is`, `is-a`, …). |
| P13 | Prior table said “REASONING” | **STRUCTURAL** (Info) | Fixture is a **missing inverse** graph pattern, not an inconsistency proof obligation. |
| P20 | “Semantic” | **STRUCTURAL** (partial) | Fixture’s empty / duplicated literals are pure RDF checks; swapped label↔comment text is still backlog. |
| P24 | “REASONING” | **STRUCTURAL** | OOPS exposes a finite RDF recursion pattern (`owl:unionOf` membership + cyclic restrictions). |
| P33 | “REASONING / chain pattern” | **STRUCTURAL** | Singleton `rdf:list` under `owl:propertyChainAxiom` is enumerable without OWL entailment. |
| P36 | “SEMANTIC / URI heuristic” | **STRUCTURAL** | Extension-suffix literals on ontology IRIs are pure lexical checks aligned with upstream `P36.owl`. |
| P38/P39/P40/P41 | Mixed “semantic/policy” tags | **STRUCTURAL** after inspecting fixtures | RDF patterns are enumerable with SPARQL; P40 uses a deliberate host mismatch heuristic (documented severity = Warning where appropriate). |

---

## Full OOPS! catalogue in machine-readable form (2026-05 extension)

[`BundledCatalogs.OOPS_PITFALL_REGISTRY`](../src/main/kotlin/com/geoknoesis/kastor/ontoquality/catalog/BundledCatalogs.kt) loads [`oops-pitfall-registry-shacl.ttl`](../src/main/resources/shapes/oops-pitfall-registry-shacl.ttl): **one deactivated `sh:NodeShape` per OOPS! P01–P41** (`rdfs:label`, `skos:definition`, `oqsh:pitfall`) plus **Kastor extensions K01–K07** (pipeline / reasoning operational pitfalls, including **K07** OWL DL inconsistency — documented in [`reasoning-ontology-pitfalls.md`](../../../../docs/kastor/design/reasoning-ontology-pitfalls.md)).

- **Behaviour:** `sh:deactivated true` ⇒ **no** validation constraints on domain data; meant for registries, portals, and LLM context.
- **Stack:** [`BundledCatalogs.allWithOopsRegistry`](../src/main/kotlin/com/geoknoesis/kastor/ontoquality/catalog/BundledCatalogs.kt) = active catalogues + registry (merged shape graph still yields the same violations as `all` alone).

### New active structural checks (same `OWL_QUALITY` bundle)

| Code | Shape | Notes |
|------|--------|------|
| **P01** | `PolysemousClassAndPropertyShape` | Structural proxy: same IRI `owl:Class` ∩ (`owl:ObjectProperty` ∪ `owl:DatatypeProperty`). |
| **P23** | `DatatypeMirrorClassNameShape` | Heuristic: class local name matches common XSD tokens. |
| **K01** | `OwlImportsCycleShape` | **Kastor** (not OOPS): cyclic `owl:imports` via SPARQL property paths. |
| **K07** | *(no SHACL shape)* — synthetic `ValidationViolation` from HermiT preflight | **Kastor**: ontology **inconsistent** in OWL 2 DL; requires `check(ontology, HERMIT)` and **K07** metadata from the registry (`allWithOopsRegistry` or equivalent). |

Synthetic / derived fixtures: [`P01.ttl`](../src/test/resources/oops-corpus/P01.ttl), [`P09.ttl`](../src/test/resources/oops-corpus/P09.ttl), [`P23.ttl`](../src/test/resources/oops-corpus/P23.ttl), [`K01.ttl`](../src/test/resources/oops-corpus/K01.ttl), [`K07.ttl`](../src/test/resources/oops-corpus/K07.ttl).

### Remaining gaps (active automation)

Per-pitfall **DL explanation** for OOPS codes (**P05, P14, P15, P16, P28, P29, P30, P31**, …) still has **no** SHACL substitute; the **registry documents** them for traceability. **Global inconsistency** is now gated via **K07** + HermiT; fine-grained “why P28 is wrong” style reports remain backlog. Policy/judgement pitfalls (**P07, P10, P17, P18**, **P37** live-Web checks) stay **out of scope** for structural SHACL.

---

## Maintenance checklist

When bumping OOPS corpus revisions:

1. Diff `run/test-pitfall.sh → valid_pitfalls` and `src/test/resources/data/input/*.owl`.
2. Re-run `./gradlew :tools:onto-quality:test --tests "*OopsCalibrationTest*"`.
3. Update **`CALIBRATION.md`**, **`ATTRIBUTION.md`**, and this file’s revision hash table.
