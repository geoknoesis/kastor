# Pitfalls of the SHACL model for quality control

This note lists **recurring traps** when using **SHACL** as the enforcement layer for **ontology or RDF data quality**. It is about limitations of the **SHACL-based quality-control approach itself** (modeling, deployment, and interpretation), not an inventory of individual ontology pitfalls such as OOPS! codes.

For how Kastor packages SHACL for ontology maintainers, see [Ontology Quality](../features/ontology-quality.md). For engine architecture and validation APIs, see [SHACL validation architecture](shacl-validation-architecture.md).

## Machine-readable definitions (SHACL)

The twelve pitfalls are encoded **the same way as the OWL and SKOS shape libraries** in `onto-quality`: an ontology header with `sh:declare`, a **tagging vocabulary** on `sh:Shape`, a **SKOS-backed category scheme**, and one **`sh:NodeShape` per pitfall** with `rdfs:label`, `rdfs:comment`, `sh:message`, and `sh:severity`.

| Parallel | OWL catalogue | SKOS catalogue | This library |
|----------|---------------|----------------|--------------|
| Code on shape | `oqsh:pitfall` (`P04`, `"convention"`, …) | `skvsh:rule` (`S9`, `"convention"`, …) | **`qcdsh:designPitfall`** (`QC-01` … `QC-12`) |
| Category | `oqsh:category` → `oqsh:QualityShapeCategoryScheme` | `skvsh:category` | **`qcdsh:category` → `qcdsh:MethodologyCategoryScheme`** |

**Important:** each shape has **`sh:deactivated true`**. They **document** methodological traps; they are **not** constraints on domain ontologies. Load them for registries, portals, or custom tooling — never activate them in a production QC bundle.

**Turtle (canonical, on the module classpath):** [../../../tools/onto-quality/library/src/main/resources/shapes/shacl-qc-design-shacl.ttl](../../../tools/onto-quality/library/src/main/resources/shapes/shacl-qc-design-shacl.ttl) — namespace `http://example.org/shacl-qc-design-shacl#` (`qcdsh:`).

**API:** [`BundledCatalogs.SHACL_QC_DESIGN`](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/catalog/BundledCatalogs.kt) references the same resource path (`/shapes/shacl-qc-design-shacl.ttl`). It is **not** part of [`BundledCatalogs.all`](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/catalog/BundledCatalogs.kt).

The earlier standalone SKOS-individual registry under `qcsh:` in `docs/` has been **retired** in favour of this catalogue-style encoding.

---

## 1. Asserted graph vs entailment

SHACL Core largely validates the **asserted RDF graph** (and any **explicit** shapes of that graph). Many OWL axioms only “show up” as useful patterns **after** OWL reasoning or materialization (subclass closure, same-as collapse, property chains, restrictions).

**Pitfall:** A shape expresses a logical condition that is **true under OWL-DL entailment** but **false on raw triples**, or the opposite: violations appear only after reasoning while shapes are run on the serialized file.

**Mitigation:** Declare and stick to a **validation profile**: no reasoning, RDF-S-only, OWL-RL fragment, full DL, etc. Pre-compute closures where needed, or encode checks against the triple patterns you actually persist.

---

## 2. Imports, named graphs, and “one graph” assumptions

Quality rules are easy to write as if **one Turtle file** were the whole world. In practice, `owl:imports`, SPARQL dataset **named graphs**, and **split modules** change which triples exist at validation time.

**Pitfall:** Findings **disappear or appear** depending on whether imports are merged, whether vocabulary IRIs are bundled, or whether shapes target the default graph only.

**Mitigation:** Document the **exact graph assembly** (imports resolved? external vocab inlined?). Prefer explicit **validation graphs** in CI rather than “whatever the desktop tool loaded.”

---

## 3. False positives from style-as-logic

Naming conventions (camelCase, prefixes, ban lists), URI hygiene, and metadata “best practices” are **policy**, not logic. They are natural to express in SHACL but **culture-dependent**.

**Pitfall:** Teams treat **convention shapes** as objective defects, or publishers reject releases for rules that do not match their naming standard or multilingual policy.

**Mitigation:** Separate **tier** or **severity** (structural vs editorial), make conservative checks **opt-in**, and version **catalogues** independently from the ontology.

---

## 4. Internationalization and literals

Labels, definitions, and SKOS documentation often use **multiple language tags** or plain literals. Constraints such as “exactly one `skos:prefLabel`” or `sh:languageIn` easily **contradict legitimate localization** or legacy data.

**Pitfall:** Shapes written for one language silently **fail** international vocabularies, or shapes that allow “any language” **miss** missing-language defects.

**Mitigation:** Align rules with a **published language policy** (required languages, `und`, fallbacks). Prefer **per-locale** cardinality where SKOS expects multiplicity.

---

## 5. Blank nodes and unstable focus nodes

Many ontologies use blank nodes for restrictions, anonymous individuals, and reification. SHACL still targets **nodes in the graph**, but bnode skolemization and **round-trip serialization** can change surface structure.

**Pitfall:** Diagnostics reference **unstable** blank node IDs; the **same** ontology file validates differently after trivial rewrite (JSON-LD frame, RDF/XML reorder).

**Mitigation:** Prefer reporting via **stable IRIs** where possible; where not, report **property paths and surrounding triples**, not only blank node labels. Treat **syntax-level** instability as a known limitation in CI.

---

## 6. SPARQL-based constraints: power vs operability

SHACL-SPARQL (`sh:sparql`) can encode almost arbitrary checks. That flexibility is a **double edge**.

**Pitfalls:**

- **Engine variance** — different validators differ on **extensions**, **function libraries**, and edge cases.
- **Performance cliffs** — joins over large property paths can dominate runtime.
- **Opacity** — failures are harder for authors to map back to **edit locations** than simple `sh:property` constraints.

**Mitigation:** Prefer Core constructs where they suffice; keep SPARQL **small and tested**; pin **engine and SHACL version** in CI.

---

## 7. Path expressions and mental models

`sh:path` sequences, alternatives, and inverses are powerful but **easy to misread**. A path that looks “obvious” in prose may **not** traverse the direction or loop structure authors imagine (especially with inverse paths and multi-hop constraints).

**Pitfall:** Subtle **under-** or **over-constrained** paths yield silent false negatives or noisy false positives.

**Mitigation:** Prototype paths on **minimal graphs**; add **regression fixtures** per shape; review path semantics against the SHACL specification for your dialect (for example SHACL 1.2 path semantics in Kastor).

---

## 8. Closed shapes and completeness illusion

`sh:closed` (with ignored properties) declares a **closed world** for a focus node. Quality teams sometimes reach for closure to “ban surprises.”

**Pitfall:** Closed shapes **fight extensibility** (anyone adding a lawful annotation property trips validation) and can **duplicate** OWL’s open-world intent in ways that confuse authors.

**Mitigation:** Reserve closure for **record-like** nodes (SKOS Concept status flags, configuration blocks), not general ontology classes.

---

## 9. Severity, duplication, and alert fatigue

Multiple shapes can fire on **one underlying mistake** (missing label, wrong typing, broken hierarchy). Without aggregation, reports feel like **many unrelated errors**.

**Pitfall:** Maintainers dismiss the report as noise; triage cost exceeds fix cost.

**Mitigation:** Normalize **codes** (for example pitfall identifiers), **group by focus node**, cap counts, and document **primary vs secondary** findings in the catalogue.

---

## 10. Calibration gaps and incomplete ground truth

Reference corpora (for example OOPS! fixtures) are **incomplete or uneven**; some pitfalls lack machine-checkable consensus examples. Shape libraries inherit **whatever the reference suite exercises**.

**Pitfall:** Over-claiming “we detect pitfall X” when the **only** evidence is a narrow fixture, or **skipping** pitfalls that matter because no reference graph exists.

**Mitigation:** Publish a **triage matrix**: detected, intentionally out of scope, blocked on corpus; refresh when new reference data appears (see `tools/onto-quality/library` calibration notes).

---

## 11. Preprocessing-dependent checks (semantic tier)

Embedding similarity, lexical clustering, and LLM-assisted annotations introduce **derived triples** that shapes depend on.

**Pitfalls:**

- **Pipeline fragility** — validation outcomes depend on **model version**, thresholds, and tokenization.
- **Absent enrichment** — distinguishing “passed because OK” from “passed because **rule did not apply**” requires explicit **gating** (for example only evaluating similarity shapes when `oqsh:semanticallyCloseTo` is present).

**Mitigation:** Keep **structural** and **semantic** tiers separate; version **models and thresholds** alongside shape catalogues; document **preconditions** per shape group.

---

## 12. Shape maintenance as a product

Ontologies evolve; SKOS and OWL **best practices** shift; licenses and FAIR expectations change. A static `.ttl` shape file **rots**.

**Pitfall:** Old shapes **block useful evolution** (new property annotations) or **miss** new failure modes (for example RDF 1.2 hygiene).

**Mitigation:** Treat catalogues as **versioned products** with changelogs, migration notes, and automated tests on **representative corpora**.

---

## 13. Summary

| Code | Area | Core risk |
|------|------|-----------|
| QC-01 | Reasoning profile | Results flip with asserted vs entailed triples |
| QC-02 | Graph scope | Imports and named graphs change the “world” |
| QC-03 | Conventions vs logic | Subjective rules masquerade as defects |
| QC-04 | Literals / locales | Label constraints misfire across languages |
| QC-05 | bnodes / syntax | Unstable identities and reorder sensitivity |
| QC-06 | SPARQL constraints | Portability, performance, debuggability |
| QC-07 | Paths | Mis-specified traversal → silent wrong results |
| QC-08 | Closed world | Over-restriction of extensible vocabularies |
| QC-09 | Reporting | Duplication and fatigue undermine trust |
| QC-10 | Ground truth | Reference gaps limit defensible claims |
| QC-11 | Derived data | Embedding and ML steps become part of the spec |
| QC-12 | Lifecycle | Unmaintained shapes become technical debt |

Using SHACL for quality control remains **practical and standard**, especially for **structural** and **SKOS-shaped** data—the point is to adopt it with **explicit assumptions**, **tiered severity**, and **continuous calibration** so the validator reinforces engineering discipline instead of fighting the ontology’s actual intent.
