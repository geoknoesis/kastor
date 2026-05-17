# Design note: pitfalls when using reasoning with ontologies

This document records **recurring traps** for teams that **add RDF/OWL reasoning** to ontology development, CI, or tools: mismatched entailment expectations, deployment graph shape, and operational limits. It complements [Pitfalls of the SHACL model for quality control](shacl-quality-control-pitfalls.md) (validation layer) and [Reasoning in Kastor and onto-quality v0.4](reasoning-in-kastor.md) (API and materialization flow).

Most sections below are **engineering and semantics** issues (pipelines, entailment regimes, SPARQL). Where they touch **ontology authoring mistakes** that [OOPS!](https://oops.linkeddata.es/catalogue.jsp) (OntOlogy Pitfall Scanner) classifies, that overlap is called out per section and in the summary table. OOPS! does **not** replace a reasoner consistency check, an OWL profile report, or CI graph-assembly policy.

---

## 1. Wrong entailment regime for the vocabulary you use

**OOPS!:** Not covered. OOPS! evaluates the ontology document, not which entailment regime your CI or triple store applies.

**Pitfall:** The same Turtle file behaves differently under **RDFS rules**, **OWL RL / OWL Micro**, and **OWL 2 DL** (tableau reasoners). Authors assume “OWL means one closure,” but only fragments line up between engines.

**Symptoms:** Subclass typing appears in Protégé + HermiT but **not** in a pipeline that runs **RDFS-only**; property chains or restrictions fire only under DL; two tools disagree on “missing” `rdf:type` arcs.

**Mitigation:** Publish a **named profile** for CI and editors (e.g. “RDFS expand then SHACL”, “HermiT DL materialize then SHACL”). Align [OntoQualityReasoningProfile](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/reasoning/OntoQualityReasoning.kt) (or equivalent) with that contract. See [HermiT reasoning integration](hermit-reasoning-integration.md) for DL-shaped graphs vs lightweight Jena paths.

---

## 2. Asserted graph vs materialized graph

**OOPS!:** Not covered. This is a validation / deployment graph contract, not a pitfall type in the [OOPS! catalogue](https://oops.linkeddata.es/catalogue.jsp).

**Pitfall:** Shapes, queries, or custom rules are written against **entailed** facts, but the runtime graph is **only asserted** (or the reverse: rules expect raw triples after a colleague materialized locally).

**Symptoms:** Intermittent CI failures; “passes in GraphDB with reasoning, fails in file-only validation.”

**Mitigation:** Make the **merge step** explicit: `asserted ∪ inferred` before consumers that need closure. Document **whether** imports and inferred triples are included. Cross-reference §1 of [shacl-quality-control-pitfalls.md](shacl-quality-control-pitfalls.md).

---

## 3. OWL DL vs OWL Full: silent profile drift

**OOPS! (partial):** [P34](https://oops.linkeddata.es/catalogue.jsp) (*Untyped class*), [P35](https://oops.linkeddata.es/catalogue.jsp) (*Untyped property*), and [P38](https://oops.linkeddata.es/catalogue.jsp) (*No OWL ontology declaration*) flag authoring patterns that often coincide with **non-DL or fragile RDF-to-OWL** situations. They do **not** prove OWL 2 DL vs Full; use an OWL profile checker for that.

**Pitfall:** RDF syntax allows constructions that push an ontology toward **OWL Full** or **non-DL** shapes. A DL reasoner may **ignore**, **approximate**, or **refuse** consequences you expected from “the file.”

**Symptoms:** Surprising consistency results, dropped axioms after round-trip, or different classes of errors between OWL API versions.

**Mitigation:** Prefer **declared** `owl:Class`, `owl:ObjectProperty`, `owl:DatatypeProperty`, and known DL-safe patterns in Turtle. Run an OWL profile checker where releases are DL-gated. Treat “valid RDF” as weaker than “in the profile HermiT is built for.”

---

## 4. Open world vs closed world (validation mindset)

**OOPS! (related):** [P16](https://oops.linkeddata.es/catalogue.jsp) (*Using a primitive class in place of a defined one*) is explicitly tied to the **open world assumption** and typical **classification / “nothing inferred under primitive”** surprises when authors expected automatic subsumption of individuals.

**Pitfall:** Reasoning proves **what follows**, not **what must be present**. A class can be non-empty in every model yet have **no asserted individuals**; absence of a triple is usually **not** a logical refutation.

**Symptoms:** “The reasoner didn’t infer X, so the data is wrong” when X is not entailed under open-world semantics; false confidence from compulsory cardinalities read like SQL `NOT NULL`.

**Mitigation:** Use **SHACL** (or application rules) for closed-world checks; use **OWL** for consistency and allowed inference. Teach reviewers the split.

---

## 5. Inconsistent ontologies: trivial theory

**OOPS! (related, not equivalent):** OOPS! does not emit a single “inconsistent ontology” code; several **Critical** modelling pitfalls often produce **unsatisfiable classes** or inconsistency once a reasoner runs, e.g. [P06](https://oops.linkeddata.es/catalogue.jsp) (*cycles in a class hierarchy*), [P24](https://oops.linkeddata.es/catalogue.jsp) (*recursive definitions*), [P27](https://oops.linkeddata.es/catalogue.jsp) / [P28](https://oops.linkeddata.es/catalogue.jsp) / [P29](https://oops.linkeddata.es/catalogue.jsp) / [P31](https://oops.linkeddata.es/catalogue.jsp) (*wrong property/class equivalence or characteristics*). [P10](https://oops.linkeddata.es/catalogue.jsp) (*Missing disjointness*) is about **omitted** axioms (different issue); lack of disjointness does not by itself mean inconsistency.

**Pitfall:** Once an ontology is **inconsistent**, **everything** is entailed in classical logic. Tools differ on whether they materialize anything, fail fast, or return partial results.

**Symptoms:** Explosion of “inferred” junk in naive materializers; silent empty inference; timeouts masking the root cause (a single disjointness clash).

**Mitigation:** Treat **consistency** as a first-class gate (HermiT’s `isConsistent`, or equivalent). **Do not** feed SHACL conformance reports from unrestricted materialization of inconsistent graphs. Surface **unsatisfiable classes** and **clash explanations** early.

**Kastor / onto-quality:** When using [`QualityChecker.check(graph, HERMIT)`](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/QualityChecker.kt), an inconsistent ontology adds a structured **`QualityFinding`** tagged **Kastor K07** (see registry shape `oqsh:KastorDoc_K07`). **`QualityChecker.default()`** merges **`OOPS_PITFALL_REGISTRY`** so this metadata is present without extra setup; custom builders should add **`BundledCatalogs.OOPS_PITFALL_REGISTRY`** (or **`allWithOopsRegistry`**). **K07** is **not** an OOPS! catalogue code — it operationalizes the consistency gate above inside the same SHACL report stream.

---

## 6. Imports and partial modules

**OOPS! (tangential):** [P37](https://oops.linkeddata.es/catalogue.jsp) (*Ontology not available on the Web*), [P38](https://oops.linkeddata.es/catalogue.jsp) (*No OWL ontology declaration* — includes `imports`), and [P40](https://oops.linkeddata.es/catalogue.jsp) (*Namespace hijacking*) concern **resolvable vocabularies and metadata**, not your CI rule of “merge imports before reasoning.” Pipeline **import closure** is still a separate contract.

**Pitfall:** Reasoning is run on **one module** while the logical ontology is the **imports closure** across files, named graphs, or SPARQL endpoints.

**Symptoms:** Locally consistent modules that become inconsistent when merged; missing entailments because a vocabulary IRI was never loaded.

**Mitigation:** Fix the **reasoning input graph** in CI: either fully expand imports into one graph or document “reasoning over this artifact only.” Match what [owl:imports](https://www.w3.org/TR/owl2-syntax/#Imports) resolution does in your toolchain.

---

## 7. Blank nodes, skolemization, and round-trip identity

**OOPS!:** No dedicated pitfall. [P34](https://oops.linkeddata.es/catalogue.jsp) / [P35](https://oops.linkeddata.es/catalogue.jsp) are only loosely related (typing of terms).

**Pitfall:** OWL restrictions and reification often use **blank nodes**. Serializers, OWL API, and RDF/XML round-trips may **skolemize** or reorder structure. Inferred triples can refer to **IRIs that differ** from the blank node–based view in the editor.

**Symptoms:** Diff noise; “lost” inferences when comparing Kastor graphs to Protégé; tests that compare triple sets across formats flake.

**Mitigation:** Prefer **IRI-named** classes and individuals in published ontologies where possible. For automation, assert on **stable entailed facts** (`ex:i rdf:type ex:C`) not on the internal structure of restrictions. See §4.3 of [hermit-reasoning-integration.md](hermit-reasoning-integration.md).

---

## 8. Literals, datatypes, and (in)equality

**OOPS! (weak overlap):** [P23](https://oops.linkeddata.es/catalogue.jsp) (*Duplicating a datatype already provided by the implementation language*) touches **datatype modelling**, not lexical normalization or unsupported XSD facets. Lexical/datatype quirks remain **outside** the OOPS! catalogue.

**Pitfall:** OWL axioms involving datatypes (e.g. facets, disjoint value spaces) interact subtly with **lexical forms** and **unsupported datatypes** (treated as `rdfs:Literal`).

**Symptoms:** Unexpected disjoint data ranges; reasoning results that change when `"1"^^xsd:int` is normalized to `"1"^^xsd:integer`.

**Mitigation:** Normalize literals at the boundary you control; declare datatypes explicitly; test with round-trip through your RDF stack.

---

## 9. Cardinality and “existential” readings

**OOPS! (direct):** [P14](https://oops.linkeddata.es/catalogue.jsp) (*Misusing `owl:allValuesFrom`*), [P15](https://oops.linkeddata.es/catalogue.jsp) (*Using “some not” in place of “not some”*), and again [P16](https://oops.linkeddata.es/catalogue.jsp) (*primitive vs defined class*) are the catalogue entries closest to **restriction misuse** and **what a classifier may or may not materialize** for individuals.

**Pitfall:** `owl:minQualifiedCardinality` and similar axioms require **some** anonymous witness in interpretations, not necessarily a **named** individual you can query as a URI.

**Symptoms:** “The reasoner didn’t assert a specific partner individual” even though the axiom is satisfiable.

**Mitigation:** If you need **named** instances in the data graph, assert them or use closed-world checks; do not expect DL materialization to invent stable IRIs for existentials.

---

## 10. Performance and materialization explosion

**OOPS!:** Not covered.

**Pitfall:** Full classification + materialization on large ABoxes is **expensive**. Blind “expand everything before SHACL” can blow memory or wall-clock time.

**Symptoms:** CI timeouts; GC thrashing; multi-gigabyte merged graphs for modest TBoxes.

**Mitigation:** Scope reasoning (TBox-only checks, module extraction, limits). Use **thresholds** (e.g. [ReasonerConfig.materializationThreshold](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerConfig.kt)) and fail with a clear message. Prefer **query-time** entailment for exploration; **batch materialization** only where downstream tools require triples.

---

## 11. Provider selection and nondeterminism

**OOPS!:** Not covered (runtime Java `ServiceLoader` / library choice).

**Pitfall:** When several [RdfReasonerProvider](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/RdfReasonerProvider.kt) implementations register support for the **same** [ReasonerType](../../../rdf/reasoning/facade/src/main/kotlin/com/geoknoesis/kastor/rdf/reasoning/ReasonerConfig.kt), **which one runs** may not be stable across JVMs or class paths.

**Symptoms:** “Works on my machine” across Jena vs RDF4J backends; HermiT accidentally shadowed by another provider.

**Mitigation:** For products and CI, **construct the concrete provider** (as onto-quality does for Jena and HermiT) instead of relying on global `ServiceLoader` ordering alone. See Part 2 of [reasoning-in-kastor.md](reasoning-in-kastor.md).

---

## 12. SPARQL entailment is a separate contract

**OOPS!:** Not covered.

**Pitfall:** A triple store’s **SPARQL OWL entailment** mode is **not** automatically the same graph as “run HermiT in OWL API and dump triples.” Regimes, incomplete approximations, and query optimization differ.

**Symptoms:** SPARQL `ENTAILMENT` answers disagree with offline materialization.

**Mitigation:** Treat **SPARQL** and **batch reasoner export** as two integration points; align test fixtures to the regime you actually deploy.

---

## Summary table

| Pitfall | Typical mistake | Stabilizing move | OOPS! (see [catalogue](https://oops.linkeddata.es/catalogue.jsp)) |
|--------|------------------|------------------|---------------------------------------------------------------------|
| Regime mismatch | Assuming one “OWL closure” everywhere | Name and enforce RDFS / micro / DL per pipeline | — |
| Asserted vs infer | Validating wrong graph | Explicit merge policy and docs | — |
| Profile drift | OWL Full–shaped RDF in DL tooling | DL lint + named IRIs | Partial: P34, P35, P38 |
| Open world | Using OWL like SQL `NOT NULL` | SHACL for closed-world checks | Related: P16 |
| Inconsistency | Materializing after a clash | Consistency gate before expansion | Related OOPS patterns: P06, P24, P27–P29, P31; **Kastor K07** = HermiT inconsistency preflight in onto-quality |
| Imports | Reasoning on a slice | Fixed import closure in CI | Tangential: P37, P38, P40 |
| Blank nodes | Bit-exact triple diffs across tools | Assert on stable IRIs and entailed atoms | — |
| Literals | Silent datatype widening | Normalize and declare types | Weak: P23 |
| Cardinality | Expecting named fillers for ∃ | Assert witnesses or use SHACL | P14, P15, P16 |
| Scale | Full materialization by default | Thresholds, modules, query-time | — |
| Providers | Registry picks “some” reasoner | Pin concrete provider in apps | — |
| SPARQL | Equating entailment modes | Contract per endpoint | — |

---

## References

- [OOPS! pitfall catalogue](https://oops.linkeddata.es/catalogue.jsp) (P01–P41)
- [OOPS pitfall registry (machine-readable)](../../../tools/onto-quality/library/src/main/resources/shapes/oops-pitfall-registry-shacl.ttl) — deactivated SHACL documentation for all P01–P41 plus Kastor **K01–K07**; API: [`BundledCatalogs.OOPS_PITFALL_REGISTRY`](../../../tools/onto-quality/library/src/main/kotlin/com/geoknoesis/kastor/ontoquality/catalog/BundledCatalogs.kt)
- [Reasoning in Kastor and onto-quality v0.4](reasoning-in-kastor.md)
- [HermiT reasoning integration](hermit-reasoning-integration.md)
- [Pitfalls of the SHACL model for quality control](shacl-quality-control-pitfalls.md)
- [SHACL validation architecture](shacl-validation-architecture.md)
- End-user overview: [Reasoning](../features/reasoning.md), [Ontology quality](../features/ontology-quality.md)
