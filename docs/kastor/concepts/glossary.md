# Glossary

{% include version-banner.md %}

Central definitions for terms reused across **Getting Started**, **Concepts**, **How-To Guides**, and **Reference**. See also [**How documentation fits together**](../getting-started/documentation-guide.md).

---

## RDF and Web standards

| Term | Definition |
|------|------------|
| **RDF** | Resource Description Framework: graph data model made of **triples** (subject–predicate–object). See [RDF Fundamentals](rdf-fundamentals.md). |
| **Triple** | One RDF statement: subject term, predicate **IRI**, object term (IRI, **literal**, or **blank node**). |
| **Quad** | Triple plus optional **graph name** (IRI or blank node), used in datasets and N-Quads / TriG. |
| **IRI** | Internationalized Resource Identifier; global identifier for resources and properties (often an HTTP URL). |
| **Literal** | Typed or plain string value in a triple object (optionally with **language tag** or **datatype** IRI). |
| **Blank node** | Anonymous node scoped to a graph; no stable global name. |
| **Graph** | Set of triples. In Kastor, often an **`RdfGraph`** or **default graph** inside a repository. |
| **Named graph** | RDF graph identified by an IRI (or blank node) within a **dataset**. |
| **Default graph** | The unnamed graph in an RDF **dataset** — triples not assigned to a named graph. |
| **Dataset** | Default graph plus zero or more named graphs; SPARQL queries run in dataset scope. See [Datasets](datasets.md). |
| **Vocabulary** | A set of IRIs (classes, properties) used consistently for a domain (e.g. DCAT, FOAF). See [Vocabularies](vocabularies.md). |
| **RDFS** | RDF Schema: lightweight vocabulary for classes and subproperties; supports basic **entailment**. |
| **OWL** | Web Ontology Language: richer ontology axioms; subsets (e.g. OWL EL, OWL RL) and full **OWL DL** (e.g. **HermiT**). |
| **Entailment / inference** | Deriving additional triples from axioms and rules (or explicitly materializing them before validation). |
| **Materialization** | Computing inferred triples and merging them into the graph used for querying or validation (implementation-specific). |
| **SPARQL** | W3C query and update language for RDF **datasets**. See [SPARQL Fundamentals](sparql-fundamentals.md). |
| **JSON-LD** | JSON-based RDF serialization using **`@context`** to map keys to IRIs. Used by Kastor Gen inputs. |
| **SHACL** | Shapes Constraint Language: validates RDF data against **shapes** (constraints). See [feature overview](../features/shacl-validation.md). |
| **Shape** | SHACL description of allowed structure for focus nodes (classes, paths, cardinality, etc.). |
| **Focus node** | The RDF resource a SHACL constraint row applies to in a **validation report**. |
| **Validation report** | Structured result of SHACL validation (conforms / violations / warnings). |

### RDF 1.2

| Term | Definition |
|------|------------|
| **RDF 1.2** | Current RDF family of specs (syntax + concepts); adds **triple terms** and directional language strings among other features. See [RDF 1.2 in Kastor](rdf-1.2.md) and [conformance testing](rdf-1.2-conformance.md). |
| **Triple term** | RDF 1.2 term representing another triple as subject/object; distinct from legacy **reification** patterns. |

---

## Kastor runtime

| Term | Definition |
|------|------------|
| **Provider** | Implementation of **`RdfProvider`** — parsing, serialization, and backend-specific operations. Selected via classpath (**SPI**) or explicitly (e.g. `Rdf.memory()`, Jena/RDF4J setup). See [Providers overview](../providers/README.md). |
| **Repository** | **`RdfRepository`**: entry point holding a **default graph**, optional named graphs, **SPARQL**/`UPDATE`, and often **transactions**. |
| **DSL** | Kotlin embedded syntax for building graphs and queries (“person has name with …”). Described in [Compact DSL Guide](../api/compact-dsl-guide.md). |
| **Domain-first RDF** | Style where application types stay Kotlin‑centric and RDF is accessed via a controlled path (**side‑channel** / **`OntoMapper`**) rather than leaking engine types everywhere. See [Philosophy](../philosophy.md). |
| **Side-channel** | Pattern (especially with **Kastor Gen**) where generated “handles” expose RDF operations alongside domain interfaces. |
| **`Rdf.memory()`** | Convenience path to an in-memory repository via the default provider discovery (typically RDF 1.1-oriented in-memory **provider**). |
| **`kastor-rdf` CLI** | Command-line helpers shipped with **`rdf-cli`** (parse, diff, etc.). Mentioned in graph testing guides. |

---

## Validation and ontology quality (`onto-quality`)

| Term | Definition |
|------|------------|
| **Quality checker** | Library entry point (`QualityChecker`) running bundled **SHACL** catalogues over an ontology graph. |
| **Catalogue / catalog** | Packaged Turtle shapes (OWL quality, SKOS, DCAT-related checks, etc.) loaded as **`ShapeCatalog`**. |
| **Finding** | One constraint violation or advisory row in a **`QualityReport`**, with optional pitfall metadata. |
| **Pitfall code** | Identifier (e.g. OOPS **P** codes, Kastor **K** codes, modern **N** codes) documenting known ontology issues. |
| **Semantic tier** | Optional embedding‑based signals consumed by extra SHACL shapes (similarity, drift). Uses **`onto-quality-embed`**. |

---

## Kastor Gen (code generation)

| Term | Definition |
|------|------------|
| **KSP** | [Kotlin Symbol Processing](https://github.com/google/ksp): compile-time API used by **`kastor-gen:processor`**. |
| **`@Rdf` ontology / DSL** | Annotations marking generated ontology-backed Kotlin types and DSL entry points. |
| **`OntoMapper` / materialize** | Runtime wiring from graph nodes to generated interfaces (**materialization** at object level, distinct from reasoning **materialization**). See [Kastor Gen runtime](../../kastor-gen/reference/runtime.md). |

---

## Documentation vocabulary

| Term | Definition |
|------|------------|
| **Tutorial** | Learning-oriented walkthrough (fixed path, teaching goals). |
| **How-to guide** | Task-oriented steps for a problem you already identified. |
| **Concept / explanation** | Understanding-oriented prose (standards, tradeoffs, mental models). |
| **Reference** | Precise, exhaustive behaviour and API facts (signatures, defaults). |

Canonical site map, cognitive-need table, and **mode-mixing anti-patterns**: [**How documentation fits together**](../getting-started/documentation-guide.md).
