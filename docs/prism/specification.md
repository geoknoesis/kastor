# Prism Specification
## Version 1.1 — Working Draft

**Namespace:** `https://spec.prism.dev/ns#`  
**Prefix:** `prism:`  
**Vocabulary:** [prism/vocab/prism.ttl](../../prism/vocab/prism.ttl)  
**License:** [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)  
**Status:** Working Draft — 2026-05-25

---

## Abstract

**Prism** is an annotation vocabulary layered on top of OWL 2 DL that enables ontology authors to declare how domain classes, properties, and relationships should be materialised as code, APIs, persistence schemas, validation rules, AI agent tools, governance workflows, event schemas, digital twins, and vector search indexes — from a single source-of-truth domain model written in standard RDF/Turtle.

Prism 1.1 extends the Prism 1.0 foundation with four new conformance levels (Data Fabric, Governance, Event-Driven, and Vector-Native), bringing the total to eight cumulative levels. It also formalises SHACL's role as a co-input to Prism processors, not just a generated output — removing the redundant constraint annotation vocabulary in favour of full SHACL expressiveness.

Prism does not replace OWL 2, SHACL, or SPARQL. It extends them with generation metadata so that a conforming Prism processor can derive all downstream artefacts from the ontology, keeping every layer semantically consistent by construction.

---

## Table of Contents

1. [Introduction](#1-introduction)
2. [Design Principles](#2-design-principles)
3. [Conformance](#3-conformance)
4. [Terminology](#4-terminology)
5. [Core OWL Profile](#5-core-owl-profile)
6. [Entity Annotations](#6-entity-annotations)
7. [Property Annotations](#7-property-annotations)
8. [Relationship Annotations](#8-relationship-annotations)
9. [Persistence Configuration](#9-persistence-configuration)
10. [API Configuration](#10-api-configuration)
11. [Validation: SHACL as Co-Input and Co-Output](#11-validation-shacl-as-co-input-and-co-output)
12. [AI Agent Annotations](#12-ai-agent-annotations)
13. [Action Types](#13-action-types)
14. [Query Types](#14-query-types)
15. [Computed Properties](#15-computed-properties)
16. [Data Source Binding](#16-data-source-binding)
17. [Federation Configuration](#17-federation-configuration)
18. [Governance Configuration](#18-governance-configuration)
19. [Event Configuration](#19-event-configuration)
20. [Digital Twin Configuration](#20-digital-twin-configuration)
21. [Temporal Configuration](#21-temporal-configuration)
22. [Vector Configuration](#22-vector-configuration)
23. [Interoperability Mappings](#23-interoperability-mappings)
24. [Ontology Metadata and Versioning](#24-ontology-metadata-and-versioning)
25. [Schema Migrations](#25-schema-migrations)
26. [Serialization Formats](#26-serialization-formats)
27. [Conformance Levels](#27-conformance-levels)
28. [Complete Example: Document Domain](#28-complete-example-document-domain)
29. [JSON-LD Context](#29-json-ld-context)
30. [Appendix A: Annotation Quick Reference](#appendix-a-annotation-quick-reference)
31. [Appendix B: Persistence Backend Reference](#appendix-b-persistence-backend-reference)
32. [Appendix C: Comparison with Alternatives](#appendix-c-comparison-with-alternatives)
33. [Appendix D: Reference Implementations and Conformance Status](#appendix-d-reference-implementations-and-conformance-status)

---

## 1. Introduction

### 1.1 The Problem

Every significant software system maintains the same domain knowledge in multiple places: entity classes in code, table definitions in migrations, DTO schemas in API specs, constraint logic in validators, and system prompts for AI agents. These representations drift apart. A field added to the database is missing from the API schema; a constraint enforced in the API is absent from the database; an AI agent hallucinates field names that were renamed three months ago.

The root cause is the same in every case: **no single source of truth for the domain model**.

### 1.2 How Prism Solves It

Prism makes the domain ontology the canonical source of truth. You define your domain once in OWL 2 DL with Prism annotations, and a conforming Prism processor derives:

| Artefact | Derived from | Level |
|---|---|---|
| Language entity classes (Kotlin, TypeScript, Python, Java) | `owl:Class` + entity-level annotations | 1 |
| Enumeration types | `prism:Enumeration` + `owl:NamedIndividual` members | 1 |
| Repository/DAO interfaces | `prism:generateRepository true` | 2 |
| SHACL validation shapes | Co-input SHACL graph linked via `prism:validatedBy` | 2 |
| JPA entities / SQL DDL | `prism:PersistenceConfig` with `prism:JpaBackend` | 2 |
| Database migration scripts | `prism:Migration` instances | 2 |
| REST controller + OpenAPI spec | `prism:ApiConfig` | 3 |
| GraphQL schema + resolvers | `prism:generateGraphQL true` | 3 |
| gRPC service stubs | `prism:generateGrpc true` | 3 |
| AI agent tool definitions | `prism:agentAccessible`, `prism:semanticDescription` | 4 |
| Agent-grounded system prompts | Full ontology serialisation as JSON-LD context | 4 |
| Test fixtures | `prism:example` values on properties | 4 |
| Live source connectors and sync pipelines | `prism:DatasourceConfig` | 5 |
| Computed property resolvers | `prism:ComputedPropertyDef` | 5 |
| Cross-system federation adapters | `prism:FederationConfig` | 5 |
| Governance workflows and data quality dashboards | `prism:GovernanceConfig` | 6 |
| Data lineage reports and classification enforcement | `prism:dataClass`, `prism:derivedFrom` | 6 |
| Event schemas (Avro, Protobuf, CloudEvents) | `prism:EventConfig` | 7 |
| Digital twin state machines | `prism:TwinConfig` | 7 |
| Bitemporal history tables and AS-OF query APIs | `prism:TemporalConfig` | 7 |
| Embedding pipelines and vector store schemas | `prism:VectorConfig` | 8 |
| Semantic search API endpoints | `prism:VectorConfig` + `prism:vectorFields` | 8 |

Every derived artefact is semantically consistent with every other because they all come from the same graph.

### 1.3 Relationship to W3C Standards

Prism is built entirely on W3C standards. It introduces no new data model — only new annotation properties layered on top of OWL 2 DL and SHACL.

| Standard | Role in Prism |
|---|---|
| **OWL 2 DL** | Domain model language (classes, properties, restrictions) |
| **RDF 1.1** | Graph data model; Turtle as the primary serialisation |
| **SPARQL 1.1** | Query language for Prism processors and named Query types |
| **SHACL** | Constraint language — **co-input** (authored alongside the ontology) and co-output (generated by Level 2+ processors) |
| **JSON-LD 1.1** | Alternative serialisation; official Prism JSON-LD context provided |
| **Dublin Core Terms** | Ontology metadata (`dcterms:created`, `dcterms:creator`, etc.) |
| **SKOS** | Definitional clarity (`skos:definition`, `skos:example`) |
| **VANN** | Namespace declarations (`vann:preferredNamespacePrefix`) |

### 1.4 What Prism Is Not

- **Not a code generator.** Prism is the specification of what should be generated and how. Conforming Prism processors implement the generation logic.
- **Not a runtime framework.** Generated code has no dependency on Prism at runtime.
- **Not a replacement for OWL or SHACL.** Prism extends them; it does not replace them.
- **Not a constraint vocabulary.** String patterns, numeric bounds, and cross-property rules belong in SHACL shapes. Prism does not duplicate the SHACL constraint vocabulary.
- **Not proprietary.** Prism is a CC BY 4.0 open standard with a W3C-compatible vocabulary.

### 1.5 Domain Applicability

Prism is domain-agnostic. It makes no assumptions about the subject matter of the ontology to which it is applied. Any OWL 2 DL ontology — whether modelling documents, healthcare records, financial instruments, manufacturing processes, legal contracts, scientific data, or any other domain — can adopt Prism annotations without modification to the Prism vocabulary itself.

All examples in this specification use a generic **document management domain** (`ex:Document`, `ex:Person`, `ex:DocumentStatus`) for illustration. These entities are broadly familiar and carry no industry-specific assumptions. They are representative examples only; domain authors replace them with their own classes and properties.

All named individual sets defined in the Prism vocabulary (persistence backends, event formats, temporal strategies, data classes, federation protocols, quality certification tiers, etc.) are **open extension sets**. Domain profiles and processor implementations MAY extend any set by declaring additional `owl:NamedIndividual` instances of the relevant Prism class. See Section 2 (P9) and individual sections for extension guidance.

---

## 2. Design Principles

### P1: Standard-First
Every Prism construct maps to a W3C standard. Prism annotations are `owl:AnnotationProperty` instances — standard OWL. A document conforming to Prism 1.1 is also a valid OWL 2 DL ontology.

### P2: Additive
Prism annotations are purely additive. An OWL ontology without any Prism annotations is a valid OWL ontology. Adding Prism annotations does not alter its OWL semantics.

### P3: Convention Over Configuration
Every Prism annotation has a sensible default. For a class with no Prism annotations at all, a Level 2+ processor generates a primary entity with a repository; a Level 3+ processor additionally generates a REST endpoint and SHACL shape. Annotations are needed only to override defaults.

### P4: Single Source of Truth
The ontology is the domain model. All generated artefacts are derived, never edited directly. If you want to change the API, change the ontology.

### P5: Portability
A Prism-annotated ontology is portable. No vendor-specific format, no SDK dependency. Any conforming processor can consume it. Export your ontology as Turtle, move to a different processor, and regenerate.

### P6: Graph-Native
Prism treats the domain model as a knowledge graph, not a relational schema. Graph-native backends (RDF4J, Neo4j) are first-class persistence targets. Relational mapping is a projection, not the canonical form.

### P7: AI-Ready
Every Prism entity and property carries machine-readable semantic descriptions (`prism:semanticDescription`) designed for direct inclusion in LLM system prompts. AI agent tool definitions are derived from the same annotations as REST endpoints.

### P8: SHACL-First for Constraints
Prism does not re-implement the SHACL constraint vocabulary. String patterns, numeric bounds, cross-property rules, severity levels, and constraint messages belong in `sh:NodeShape` declarations authored alongside the ontology. Processors consume these shapes as co-input rather than generating redundant constraint metadata from Prism annotations. Link a class to its shapes via `prism:validatedBy`.

### P9: Extensibility-by-Design
Every set of named individuals in Prism (persistence backends, agent permissions, event kinds, temporal strategies, data classes, federation protocols, quality certifications, etc.) is an **open set**. Domain profiles and processor implementations MAY extend any set by declaring additional `owl:NamedIndividual` instances typed as the relevant Prism class (e.g., `prism:PersistenceBackend`, `prism:TemporalStrategy`, `prism:EventKind`). The base named individuals shipped with the Prism vocabulary are a starting point, not an exhaustive enumeration. Processors SHOULD treat unknown named individual values as opaque identifiers and report them as informational notices rather than errors, enabling forward compatibility as domain profiles add specialised values.

---

## 3. Conformance

A document is a **conformant Prism ontology** if:

1. It is a valid RDF 1.1 document in Turtle, JSON-LD, N-Triples, or RDF/XML serialisation.
2. It imports or references the Prism vocabulary (`https://spec.prism.dev/ns`).
3. All Prism annotation properties are used with values of the correct type as specified in this document.
4. It declares its target capabilities — either a profile via `prism:conformanceLevel` or an explicit set via `prism:requiresCapability` (see Section 3.1) — and satisfies the requirements of every capability it targets (see Section 27).

A software system is a **conformant Prism processor for a capability set C** if it:

1. Accepts a conformant Prism ontology as input.
2. Correctly derives all artefacts mandated by every capability in `C` and their transitive dependencies (see Section 3.1). For a capability that admits more than one target (e.g. persistence backend, API style, event format, vector store), the processor MUST support **at least one** such target and MUST document which.
3. Reads co-input SHACL shapes from `sh:NodeShape` declarations linked via `prism:validatedBy` and merges them with any generated shapes before validation (whenever it provides `prism:CoreModel` or `prism:Persistence`).
4. Applies all defaults specified in this document where annotations are absent.
5. Reports an error for any annotation value that violates its type constraint.
6. Treats requested capabilities it does not provide as a declared, fatal gap: it MUST report them rather than silently skipping the corresponding artefacts.

A processor MAY conform for any capability set — there is no requirement that a single processor support all capabilities or all targets. A processor "conforms to profile *Ln*" when its capability set includes every capability that *Ln* bundles (Section 3.1).

### 3.1 Capabilities and Profiles

Prism generation concerns are organised as **capabilities** — independently-supportable units such as `prism:Persistence`, `prism:RestApi`, `prism:AiTools`, and `prism:Vector` (full list in Section 27). Capabilities are *orthogonal*: an ontology may require `prism:CoreModel` + `prism:Vector` without requiring governance, events, or REST.

Capabilities may depend on one another via `prism:dependsOn` (e.g. `prism:Persistence` depends on `prism:CoreModel`; `prism:DigitalTwin` depends on `prism:Events`). Requiring or providing a capability transitively requires/provides its dependencies.

The eight **conformance levels** (`prism:Level1Core` … `prism:Level8VectorNative`) are retained as named **profiles** — cumulative, convenient bundles of capabilities declared in the vocabulary via `prism:bundlesCapability`. `prism:conformanceLevel prism:Ln` is exactly equivalent to declaring `prism:requiresCapability` for each capability that `Ln` bundles.

```turtle
# Profile-based declaration (cumulative bundle):
<…/my-ontology> prism:conformanceLevel prism:Level2Persistence .

# Equivalent à-la-carte declaration:
<…/my-ontology> prism:requiresCapability prism:CoreModel, prism:Persistence .

# Composition not expressible as a single level (Core + Vector, nothing else):
<…/my-ontology> prism:requiresCapability prism:CoreModel, prism:Vector .
```

An ontology MUST declare at least one of `prism:conformanceLevel` or `prism:requiresCapability`. It MAY declare both (the effective capability set is their union plus transitive dependencies). Section 5.1 specifies the cardinality rules.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT, and MAY in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

---

## 4. Terminology

**Domain Model** — The complete set of classes, properties, and relationships that describe a business domain.

**Entity** — An OWL class annotated with `prism:entityKind prism:Entity`. Has its own identity, lifecycle, and repository. The primary unit of API and persistence generation.

**Value Object** — An OWL class annotated with `prism:entityKind prism:ValueObject`. Has no independent identity; persisted as a component of its owning entity.

**Enumeration** — An OWL class annotated with `prism:entityKind prism:Enumeration`. Instances are OWL named individuals. Generates a language-level enum.

**Action** — An instance of `prism:Action`. A named, typed operation. Generates an API endpoint, a service method, and an AI agent tool.

**Query** — An instance of `prism:Query`. A named SPARQL SELECT. Generates a read-only API endpoint and an agent tool.

**Computed Property** — A property whose value is derived at read time via a SPARQL expression or function reference. Declared via `prism:computedBy`.

**Digital Twin** — A real-time RDF shadow graph mirroring a physical entity's state via streaming events. Declared via `prism:digitalTwin`.

**Bitemporal** — An entity versioning strategy tracking both when a fact was true in the real world (valid time) and when it was recorded (transaction time).

**Prism Processor** — A software system that reads a conformant Prism ontology and derives one or more artefacts from it.

**Co-input SHACL** — A `sh:NodeShape` graph authored alongside the ontology and consumed by the processor as primary constraint input (as opposed to shapes generated by the processor as output).

**Persistence Backend** — The target data store for generated persistence code. One of: JPA/relational, MongoDB, Neo4j, RDF4J triplestore, DynamoDB.

---

## 5. Core OWL Profile

Prism ontologies MUST use the OWL 2 DL profile. OWL Full constructs (e.g., classes as instances without explicit named individual declaration) are not supported by Prism processors.

### 5.1 Required Ontology Header

Every Prism ontology file MUST declare:

```turtle
@prefix prism:   <https://spec.prism.dev/ns#> .
@prefix owl:     <http://www.w3.org/2002/07/owl#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd:     <http://www.w3.org/2001/XMLSchema#> .
@prefix dcterms: <http://purl.org/dc/terms/> .

<https://example.com/ontologies/my-domain>
    a owl:Ontology ;
    rdfs:label "My Domain Ontology"@en ;
    dcterms:created "2026-05-25"^^xsd:date ;
    owl:versionIRI <https://example.com/ontologies/my-domain/1.0.0> ;
    owl:versionInfo "1.0.0" ;
    prism:conformanceLevel prism:Level2Persistence .
```

`prism:conformanceLevel` declares the conformance profile the ontology targets (see Sections 3.1 and 27). An ontology MUST declare its target capabilities by **at least one** of: a single `prism:conformanceLevel` value, or one or more `prism:requiresCapability` values (it MAY use both). At most one `prism:conformanceLevel` MAY be declared per ontology file. Processors MUST reject ontologies that declare more than one `prism:conformanceLevel`, and MUST reject ontologies that declare neither `prism:conformanceLevel` nor any `prism:requiresCapability`.

### 5.2 Entity Class Declaration

```turtle
ex:Document
    a owl:Class ;
    rdfs:label "Document"@en ;
    rdfs:comment "A versioned artefact created, reviewed, and published within the domain."@en ;
    prism:entityKind prism:Entity ;
    prism:pluralLabel "Documents"@en ;
    prism:generateCode true ;
    prism:generateRepository true ;
    prism:generateRestEndpoint true ;
    prism:validatedBy ex:DocumentShape .
```

`prism:validatedBy` links the class to its `sh:NodeShape`. Processors MUST load the shape graph and apply its constraints. If `prism:entityKind` is absent, processors MUST treat the class as `prism:Entity`.

### 5.3 Value Object Declaration

```turtle
ex:MonetaryAmount
    a owl:Class ;
    rdfs:label "Monetary Amount"@en ;
    prism:entityKind prism:ValueObject ;
    prism:generateCode true ;
    prism:generateRepository false ;
    prism:generateRestEndpoint false .
```

### 5.4 Enumeration Declaration

```turtle
ex:DocumentStatus
    a owl:Class ;
    rdfs:label "Document Status"@en ;
    prism:entityKind prism:Enumeration .

ex:DRAFT      a ex:DocumentStatus, owl:NamedIndividual ; rdfs:label "DRAFT"@en .
ex:ACTIVE     a ex:DocumentStatus, owl:NamedIndividual ; rdfs:label "ACTIVE"@en .
ex:DEPRECATED a ex:DocumentStatus, owl:NamedIndividual ; rdfs:label "DEPRECATED"@en .
ex:ARCHIVED   a ex:DocumentStatus, owl:NamedIndividual ; rdfs:label "ARCHIVED"@en .
```

### 5.5 Property Type Derivation

A processor derives each generated property's type from the OWL model and the co-input SHACL shape together, using the following precedence:

1. **Object vs. value.** A property declared `owl:ObjectProperty` (or constrained by `sh:class`, `sh:nodeKind sh:IRI`, or `sh:nodeKind sh:BlankNodeOrIRI`) generates a **reference** to another generated type. A property declared `owl:DatatypeProperty` (or constrained by `sh:datatype` or `sh:nodeKind sh:Literal`) generates a **value** type.
2. **Concrete type — OWL is authoritative.** When `rdfs:range` is present it determines the type: a datatype range maps to the corresponding language type; a `prism:Enumeration` class range maps to that class's generated enum type; any other class range maps to a reference to that class's generated type.
3. **SHACL as fallback and refinement.** When `rdfs:range` is absent, the processor derives the type from the co-input shape's `sh:datatype`, `sh:class`, or `sh:nodeKind`. When both are present, a SHACL type MAY only *refine* the OWL range (an equal type, or a recognised subtype/sub-datatype); it MUST NOT widen or replace it.
4. **Conflict is an error.** When `rdfs:range` and the SHACL type are both present and **incompatible** — neither equal nor a recognised refinement (e.g. `rdfs:range xsd:string` with `sh:datatype xsd:integer`, or a datatype range with `sh:nodeKind sh:IRI`) — the processor MUST report an error and MUST NOT silently choose one.

> A processor that reads only SHACL (no OWL range) operates under rule 3's fallback for every property. Such a processor cannot generate enumeration types from `prism:Enumeration` class ranges (it never sees them) and MUST document this as a `prism:CoreModel` capability limitation (see Appendix D).

---

## 6. Entity Annotations

### 6.1 Generation Control

| Annotation | Type | Default | Description |
|---|---|---|---|
| `prism:entityKind` | `prism:EntityKind` | `prism:Entity` | Semantic role of this class |
| `prism:generateCode` | `xsd:boolean` | `true` | Generate language entity class |
| `prism:generateRepository` | `xsd:boolean` | `true` (Entity), `false` (ValueObject) | Generate repository interface |
| `prism:generateRestEndpoint` | `xsd:boolean` | `true` (Entity), `false` (ValueObject) | Generate REST controller |
| `prism:generateGraphQL` | `xsd:boolean` | `false` | Generate GraphQL type + resolver |
| `prism:generateGrpc` | `xsd:boolean` | `false` | Generate gRPC service definition and stub |
| `prism:pluralLabel` | `xsd:string` | `rdfs:label + "s"` | Plural name for URL paths and collections |
| `prism:validatedBy` | `sh:NodeShape` | — | Link to co-input SHACL NodeShape |

**Generation flags are scoped by capability.** Each `prism:generate*` flag is a per-class opt-in/opt-out *within* a capability; it does not select capabilities. A flag takes effect only when its governing capability is in the ontology's effective capability set (Section 3.1):

| Flag | Governing capability |
|---|---|
| `prism:generateCode` | `prism:CoreModel` |
| `prism:generateRepository` | `prism:Persistence` |
| `prism:generateRestEndpoint` | `prism:RestApi` |
| `prism:generateGraphQL` | `prism:GraphQL` |
| `prism:generateGrpc` | `prism:Grpc` |

Rules:

1. Setting a flag to `true` **does not** add or raise a capability. An ontology that targets only `prism:CoreModel` but sets `prism:generateRestEndpoint true` does **not** thereby become a REST-API ontology; the flag is **inert** until `prism:RestApi` is targeted.
2. When a flag's governing capability **is** in the effective set, the flag (or its default) decides whether the artefact is generated for that class.
3. A processor SHOULD emit an informational notice when a `prism:generate*` flag is `true` but its governing capability is not in the effective set, since this usually indicates a missing capability declaration.

### 6.2 Versioning Annotations

```turtle
ex:Document
    a owl:Class ;
    prism:sinceVersion "1.0.0" .

ex:LegacyEntry
    a owl:Class ;
    owl:deprecated true ;
    prism:deprecatedSince "1.2.0" ;
    prism:replacedBy ex:Document .
```

### 6.3 Persistence Link

```turtle
ex:Document
    a owl:Class ;
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:JpaBackend ;
        prism:tableName "documents" ;
        prism:enableCache true ;
        prism:cacheTtlSeconds 300
    ] .
```

### 6.4 API Config Link

```turtle
ex:Document
    a owl:Class ;
    prism:apiConfig [
        a prism:ApiConfig ;
        prism:basePath "/api/v1/documents" ;
        prism:allowedMethod prism:GET, prism:POST, prism:PATCH ;
        prism:requiresAuthentication true ;
        prism:paginationDefault 25 ;
        prism:paginationMax 200 ;
        prism:apiTag "Documents"
    ] .
```

### 6.5 Level 5–8 Config Links

Higher conformance levels add further configuration links to entity classes:

| Annotation | Type | Level | Description |
|---|---|---|---|
| `prism:datasource` | `prism:DatasourceConfig` | 5 | Live data source binding |
| `prism:federated` | `prism:FederationConfig` | 5 | Federated property resolution |
| `prism:governance` | `prism:GovernanceConfig` | 6 | Stewardship, approval, retention |
| `prism:qualityCertification` | `prism:QualityCertification` | 6 | Target data quality tier |
| `prism:dataClass` | `prism:DataClass` | 6 | Data sensitivity classification |
| `prism:events` | `prism:EventConfig` | 7 | Event publishing configuration |
| `prism:digitalTwin` | `prism:TwinConfig` | 7 | Digital twin configuration |
| `prism:temporal` | `prism:TemporalConfig` | 7 | Bitemporal versioning |
| `prism:vector` | `prism:VectorConfig` | 8 | Embedding index configuration |

---

## 7. Property Annotations

Prism property annotations apply to both `owl:DatatypeProperty` and `owl:ObjectProperty` instances.

### 7.1 Constraint Delegation to SHACL

**Prism 1.1 does not define constraint annotations** (patterns, length bounds, numeric bounds). These belong in `sh:PropertyShape` declarations within a co-input SHACL graph. This avoids duplicating the SHACL constraint vocabulary and grants full SHACL expressiveness including `sh:message`, `sh:severity`, `sh:or`, SPARQL constraints, and custom validators.

Link a class to its shapes via `prism:validatedBy`:

```turtle
# Ontology file — generation control and indexing hints only
ex:Document
    a owl:Class ;
    prism:validatedBy ex:DocumentShape .          # link to co-input shapes

ex:documentId
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:string ;
    rdfs:label "Document ID"@en ;
    prism:required true ;
    prism:unique true ;
    prism:immutable true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:sortable true ;
    prism:example "DOC-20260001" ;
    prism:columnName "document_id" .

# SHACL shapes file (co-input — authored, not generated)
ex:DocumentShape
    a sh:NodeShape ;
    sh:targetClass ex:Document ;
    sh:property [
        sh:path ex:documentId ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "DOC-[0-9]+" ;
        sh:maxLength 20 ;
        sh:message "Document ID must match pattern DOC-<number>."@en ;
        sh:severity sh:Violation
    ] .
```

Processors MUST read co-input SHACL shapes and use them for validation generation. Processors MUST NOT require that all constraints be expressed as Prism annotations.

### 7.2 Cardinality and Identity Annotations

| Annotation | Type | Default | Description |
|---|---|---|---|
| `prism:required` | `xsd:boolean` | `false` | Property must be present on every instance. Generates `sh:minCount 1` in the SHACL shape AND a NOT NULL constraint in persistence and API layers |
| `prism:unique` | `xsd:boolean` | `false` | Value must be unique across all instances. Generates a UNIQUE index in relational backends |

These annotations are conveniences that contribute to *generated* SHACL shapes; a co-input shape constraining the same property always takes precedence. See §11.5 for the per-component merge and conflict-resolution rules.

### 7.3 Index and Search Annotations

| Annotation | Type | Default | Description |
|---|---|---|---|
| `prism:indexed` | `xsd:boolean` | `false` | Create a database index |
| `prism:searchable` | `xsd:boolean` | `false` | Include in full-text search |
| `prism:sortable` | `xsd:boolean` | `false` | Expose as API sort key |
| `prism:filterable` | `xsd:boolean` | `false` | Expose as API filter parameter |
| `prism:vectorIndexed` | `xsd:boolean` | `false` | Include as filterable metadata in the vector store index |

### 7.4 Persistence Annotations

| Annotation | Type | Description |
|---|---|---|
| `prism:columnName` | `xsd:string` | Override column name (default: snake_case of local name) |
| `prism:defaultValue` | `rdfs:Literal` or enumeration member IRI | Default value when not supplied |
| `prism:immutable` | `xsd:boolean` | Set at creation, never updated |

The value of `prism:defaultValue` is an `rdfs:Literal` for datatype properties, or — for a property whose range is a `prism:Enumeration` class — the IRI of one of that enumeration's named individuals (e.g. `prism:defaultValue ex:DRAFT`). The vocabulary therefore declares no `rdfs:range` on `prism:defaultValue`; processors interpret the value according to the property's range.

### 7.5 Safety and Documentation Annotations

| Annotation | Type | Description |
|---|---|---|
| `prism:sensitive` | `xsd:boolean` | PII — omit from logs, search, default serialisation |
| `prism:example` | `rdfs:Literal` | Example value for OpenAPI docs and test fixtures |

### 7.6 Lineage and Derivation Annotations

| Annotation | Type | Level | Description |
|---|---|---|---|
| `prism:computed` | `xsd:boolean` | 5 | Value is derived at read time, not stored |
| `prism:computedBy` | `prism:ComputedPropertyDef` | 5 | Links to the computation definition |
| `prism:derivedFrom` | IRI | 6 | Upstream property or class this is derived from |
| `prism:derivedUsing` | IRI | 6 | The `prism:ComputedPropertyDef` or `prism:Query` that produces the value |

### 7.7 Data Classification

```turtle
ex:email
    a owl:DatatypeProperty ;
    rdfs:domain ex:Person ;
    rdfs:range xsd:string ;
    rdfs:label "Email Address"@en ;
    prism:sensitive true ;
    prism:dataClass prism:PII .
```

`prism:dataClass` values: `prism:Public`, `prism:Internal`, `prism:Confidential`, `prism:StrictlyConfidential`, `prism:PII`, `prism:PHI`, `prism:PCI`.

**Relationship between `prism:sensitive` and `prism:dataClass`:** These are complementary, not redundant.
- `prism:sensitive` is an **operational** flag: processors use it to omit the property from logs, default serialisations, and search indexes. It is a binary directive.
- `prism:dataClass` is a **governance** classification: it declares the sensitivity tier for stewardship, retention, and access control generation.

When `prism:dataClass` is `prism:PII`, `prism:PHI`, or `prism:PCI`, processors SHOULD treat the property as implicitly `prism:sensitive true` even if the annotation is absent. Authors SHOULD declare both explicitly for clarity.

### 7.8 Full Property Example

```turtle
# Ontology file — Prism annotations (generation control, not constraints)
ex:Document
    a owl:Class ;
    prism:validatedBy ex:DocumentShape .          # ← annotation goes on the OWL class

ex:documentId
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:string ;
    rdfs:label "Document ID"@en ;
    prism:required true ;
    prism:unique true ;
    prism:immutable true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:sortable true ;
    prism:example "DOC-20260001" ;
    prism:columnName "document_id" .

# SHACL shapes file — constraints live here, not in the ontology
ex:DocumentShape
    a sh:NodeShape ;
    sh:targetClass ex:Document ;
    sh:property [
        sh:path ex:documentId ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "DOC-[0-9]+" ;
        sh:maxLength 20 ;
        sh:message "Document ID must match DOC-<number>."@en
    ] .
```

---

### 7.9 Quantity and Unit-of-Measure Annotations

Prism provides three annotations for numeric properties that represent physical or financial quantities. None of these annotations carry `rdfs:domain` in the OWL vocabulary — applicability constraints are expressed exclusively via SHACL meta-shapes (Section 21 of the vocabulary) to avoid inference side-effects in OWL reasoners.

| Annotation | Applies to | Value type | Purpose |
|---|---|---|---|
| `prism:unit` | `owl:DatatypeProperty` | IRI (QUDT unit) | Unit is invariant across all instances of this property |
| `prism:quantityKind` | `owl:DatatypeProperty` or `owl:Class` | IRI (QUDT QuantityKind) | Physical nature of the quantity; enables unit-set validation |
| `prism:unitProperty` | `owl:DatatypeProperty` | IRI (a sibling property) | Names the sibling property that carries the per-instance unit |

#### Pattern 1 — Fixed unit (unit is invariant per property)

Use `prism:unit` when every instance of a property always has the same unit. The unit annotation lives on the property declaration.

```turtle
@prefix qudt: <http://qudt.org/schema/qudt/> .
@prefix unit: <http://qudt.org/vocab/unit/> .
@prefix qk:   <http://qudt.org/vocab/quantitykind/> .

ex:fileSize
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:decimal ;
    rdfs:label "file size"@en ;
    prism:unit unit:Byte ;
    prism:quantityKind qk:DataQuantity .

ex:reviewDurationDays
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:integer ;
    rdfs:label "review duration"@en ;
    prism:unit unit:Day ;
    prism:quantityKind qk:Time .
```

Processors receiving `prism:unit` SHOULD:
- annotate generated OpenAPI schema fields with `x-unit` (the QUDT unit IRI)
- annotate generated fields with `x-quantity-kind` when `prism:quantityKind` is also present
- emit agent semantic metadata mapping the property to its QUDT kind

#### Pattern 2 — Variable unit (unit travels with the value)

When the unit may differ across instances (e.g. a monetary amount expressed in any currency), model a `prism:ValueObject` subclass that mirrors `qudt:QuantityValue` with a numeric value property and a unit property. Annotate the numeric property with `prism:unitProperty` pointing to its sibling.

```turtle
ex:MonetaryAmount
    a owl:Class ;
    prism:entityKind prism:ValueObject ;
    rdfs:label "Monetary Amount"@en ;
    rdfs:comment "A numeric amount paired with a currency unit."@en .

ex:amount
    a owl:DatatypeProperty ;
    rdfs:domain ex:MonetaryAmount ;
    rdfs:range xsd:decimal ;
    rdfs:label "amount"@en ;
    prism:unitProperty ex:currency ;
    prism:quantityKind qk:Currency .

ex:currency
    a owl:DatatypeProperty ;
    rdfs:domain ex:MonetaryAmount ;
    rdfs:range xsd:string ;
    rdfs:label "currency"@en ;
    prism:example "USD" .
```

For stronger typing, use a `qudt:Unit` object property instead of an xsd:string currency property:

```turtle
ex:unitRef
    a owl:ObjectProperty ;
    rdfs:domain ex:MonetaryAmount ;
    rdfs:range qudt:Unit ;
    rdfs:label "unit reference"@en .

ex:amount
    a owl:DatatypeProperty ;
    rdfs:domain ex:MonetaryAmount ;
    rdfs:range xsd:decimal ;
    prism:unitProperty ex:unitRef ;
    prism:quantityKind qk:Currency .
```

#### SHACL unit validation

When `prism:quantityKind` is present, processors can derive the set of applicable units from `qudt:applicableUnit` on the QUDT QuantityKind individual and emit SHACL `sh:in` constraints automatically:

```turtle
# Processor-generated SHACL (do not hand-author)
ex:FileSizeShape
    a sh:NodeShape ;
    sh:targetClass ex:Document ;
    sh:property [
        sh:path ex:fileSize ;
        sh:datatype xsd:decimal ;
        sh:minInclusive 0 ;
        sh:message "File size must be a non-negative decimal."@en
    ] .
```

#### QUDT import guidance

The Prism vocabulary does **not** `owl:imports` QUDT to remain self-contained. Ontologies using these annotations SHOULD import the relevant QUDT modules in their own ontology header:

```turtle
<https://example.com/ontologies/my-ontology>
    a owl:Ontology ;
    owl:imports <http://qudt.org/2.1/schema/qudt> ;
    owl:imports <http://qudt.org/2.1/vocab/unit> ;
    owl:imports <http://qudt.org/2.1/vocab/quantitykind> .
```

> **Extensibility:** Any IRI may be used as the value of `prism:unit` or `prism:quantityKind`. QUDT is the recommended vocabulary. Domain profiles that use non-QUDT unit systems SHOULD declare a mapping to QUDT via `owl:equivalentClass` or `skos:exactMatch` to enable interoperability.

---

## 8. Relationship Annotations

Relationship annotations apply to `owl:ObjectProperty` declarations.

### 8.1 Cardinality

```turtle
ex:hasSection
    a owl:ObjectProperty ;
    rdfs:domain ex:Document ;
    rdfs:range ex:Section ;
    rdfs:label "has section"@en ;
    prism:cardinality prism:OneToMany ;
    prism:cascade prism:CascadeAll ;
    prism:fetchStrategy prism:Lazy .

ex:belongsToCollection
    a owl:ObjectProperty ;
    rdfs:domain ex:Document ;
    rdfs:range ex:Collection ;
    rdfs:label "belongs to collection"@en ;
    prism:cardinality prism:ManyToOne ;
    prism:cascade prism:CascadeNone ;
    prism:fetchStrategy prism:Lazy ;
    prism:joinColumn "collection_id" ;
    prism:required true .
```

### 8.2 Cardinality Values

| Value | Meaning |
|---|---|
| `prism:OneToOne` | One instance of domain has exactly one range instance |
| `prism:OneToMany` | One instance of domain has many range instances |
| `prism:ManyToOne` | Many domain instances share one range instance |
| `prism:ManyToMany` | Many-to-many; requires `prism:joinTable` for relational backends |

### 8.3 Cascade Values

| Value | Meaning |
|---|---|
| `prism:CascadeAll` | Persist, merge, and remove cascade to related entity |
| `prism:CascadePersist` | Only persist cascades |
| `prism:CascadeMerge` | Only merge cascades |
| `prism:CascadeRemove` | Only remove cascades |
| `prism:CascadeNone` | No cascading; references only |

### 8.4 Edge Properties

Some relationships carry their own data. `prism:EdgeProperties` declares named datatype properties that exist on the edge (relationship) itself, not on either endpoint.

This maps to a join-entity in JPA, a relationship type in Neo4j, or a reified triple in RDF.

```turtle
ex:ContributorRole
    a prism:EdgeProperties ;
    prism:edgeProperty ex:contributionType ;
    prism:edgeProperty ex:contributedAt .

ex:hasContributor
    a owl:ObjectProperty ;
    rdfs:domain ex:Document ;
    rdfs:range ex:Person ;
    rdfs:label "has contributor"@en ;
    prism:cardinality prism:ManyToMany ;
    prism:joinTable "document_contributors" ;
    prism:edgeProperties ex:ContributorRole .

ex:contributionType
    a owl:DatatypeProperty ;
    rdfs:label "contribution type"@en ;
    rdfs:range xsd:string .         # e.g. "author", "reviewer", "editor"

ex:contributedAt
    a owl:DatatypeProperty ;
    rdfs:label "contributed at"@en ;
    rdfs:range xsd:dateTime .
```

---

## 9. Persistence Configuration

### 9.1 Persistence Config Block

A `prism:PersistenceConfig` is a blank node or named individual linked from a class via `prism:persistence`.

```turtle
ex:Document
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:JpaBackend ;
        prism:tableName "documents" ;
        prism:enableCache true ;
        prism:cacheTtlSeconds 600
    ] .
```

### 9.2 Supported Backends

| Named Individual | Backend |
|---|---|
| `prism:JpaBackend` | Relational via JPA (PostgreSQL, MySQL, H2) |
| `prism:MongoBackend` | MongoDB document store |
| `prism:Neo4jBackend` | Neo4j property graph |
| `prism:Rdf4jBackend` | RDF triplestore (RDF4J, Jena, Blazegraph) |
| `prism:DynamoBackend` | Amazon DynamoDB key-value / document |

> **Extensibility:** Custom backends may be added by declaring a new `owl:NamedIndividual` typed as `prism:PersistenceBackend`. Processors SHOULD report unknown backend values as informational and fall back to their default behaviour.

### 9.3 Multi-Backend Declaration

An entity MAY declare multiple persistence configs with different backends. Processors MUST generate code for each declared backend and SHOULD generate an interface abstraction over them.

```turtle
ex:ChangeRecord
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:Rdf4jBackend
    ] , [
        a prism:PersistenceConfig ;
        prism:backend prism:MongoBackend ;
        prism:tableName "change_records"
    ] .
```

### 9.4 Backend-Specific Naming Conventions

| Backend | Default name source | Override annotation |
|---|---|---|
| JPA | `snake_case(rdfs:label)` pluralised | `prism:tableName` |
| MongoDB | `camelCase(rdfs:label)` pluralised | `prism:tableName` |
| Neo4j | `rdfs:label` as node label | `prism:tableName` |
| RDF4J | Named graph IRI derived from `prism:pluralLabel` | `prism:tableName` |

---

## 10. API Configuration

### 10.1 REST Generation Defaults

When `prism:generateRestEndpoint true` and no `prism:ApiConfig` is present, processors MUST apply these defaults:

| Setting | Default Value |
|---|---|
| Base path | `/api/{prism:pluralLabel lowercase}` |
| Allowed methods | GET (list + get), POST (create), PUT (full update), PATCH (partial), DELETE |
| Authentication | Not required |
| Pagination default | 20 |
| Pagination max | 100 |

### 10.2 Full API Config Example

```turtle
ex:Document
    prism:apiConfig [
        a prism:ApiConfig ;
        prism:basePath "/api/v1/documents" ;
        prism:allowedMethod prism:GET, prism:POST, prism:PATCH ;
        prism:requiresAuthentication true ;
        prism:paginationDefault 25 ;
        prism:paginationMax 200 ;
        prism:apiTag "Documents"
    ] .
```

### 10.3 Generated Endpoints

For a `prism:ApiConfig` with `prism:basePath "/api/v1/documents"`, processors MUST generate:

| Method | Path | Operation |
|---|---|---|
| GET | `/api/v1/documents` | List with pagination, filter, sort |
| POST | `/api/v1/documents` | Create |
| GET | `/api/v1/documents/{id}` | Get by ID |
| PUT | `/api/v1/documents/{id}` | Full update |
| PATCH | `/api/v1/documents/{id}` | Partial update |
| DELETE | `/api/v1/documents/{id}` | Delete |

Methods NOT listed in `prism:allowedMethod` MUST NOT be generated.

### 10.4 OpenAPI Specification Generation

Conforming Level 3 processors MUST generate a valid OpenAPI 3.1 specification from Prism annotations:

- Entity `rdfs:comment` → operation `description`
- `prism:apiTag` → OpenAPI `tags`
- `prism:example` on properties → OpenAPI `example`
- SHACL `sh:pattern`, `sh:minLength`, `sh:maxLength` from co-input shapes → OpenAPI `pattern`, `minLength`, `maxLength`
- `prism:sensitive true` → mark property with `x-sensitive: true`; SHOULD be omitted from response schemas

### 10.5 GraphQL Generation

When `prism:generateGraphQL true`:

- Each `prism:Entity` becomes a GraphQL `type`
- Each `prism:ValueObject` becomes a GraphQL `input` and embedded `type`
- Each `prism:Enumeration` becomes a GraphQL `enum`
- `prism:required true` on a property generates a non-null (`!`) field
- Relationships generate nested type references and corresponding resolvers

### 10.6 gRPC Generation

When `prism:generateGrpc true`:

- Each `prism:Entity` becomes a `.proto` `message`
- Processors generate a service definition with CRUD RPCs
- `prism:Action` instances generate additional RPC methods
- `prism:required true` maps to a non-optional proto field

---

## 11. Validation: SHACL as Co-Input and Co-Output

### 11.1 SHACL as Co-Input

In Prism 1.1, SHACL shapes are **co-input** — they are authored by the ontology designer alongside the OWL ontology and consumed by the Prism processor, rather than being derived from Prism annotations. This is the primary validation mechanism.

Authors write `sh:NodeShape` declarations in a companion shapes file and link them to the corresponding OWL classes via `prism:validatedBy`:

```turtle
# Ontology
ex:Document
    a owl:Class ;
    prism:validatedBy ex:DocumentShape .

# Co-input SHACL (authored manually, read by processor)
ex:DocumentShape
    a sh:NodeShape ;
    sh:targetClass ex:Document ;
    sh:property [
        sh:path ex:documentId ;
        sh:minCount 1 ; sh:maxCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "DOC-[0-9]+" ;
        sh:message "Document ID must match pattern DOC-<number>."@en ;
        sh:severity sh:Violation
    ] ;
    sh:property [
        sh:path ex:title ;
        sh:minCount 1 ;
        sh:datatype xsd:string ;
        sh:maxLength 500
    ] ;
    sh:sparql [
        sh:message "Published documents must have a non-empty content field."@en ;
        sh:severity sh:Warning ;
        sh:select """
            SELECT $this WHERE {
                $this ex:status ex:ACTIVE .
                OPTIONAL { $this ex:content ?c }
                FILTER (!BOUND(?c) || ?c = "")
            }
        """
    ] .
```

### 11.2 SHACL as Co-Output

A Level 2+ processor MUST also **generate** a `sh:NodeShape` for every `prism:Entity` class, derived from the cardinality and identity Prism annotations:

- `prism:required true` → `sh:minCount 1`
- `prism:unique true` → `sh:maxCount 1` (where appropriate for the backend)
- Property datatype ranges from `rdfs:range` → `sh:datatype`
- Enumeration ranges → `sh:in` list of named individual IRIs

Generated shapes MUST NOT duplicate constraints already present in the co-input shapes. Processors MUST merge co-input and generated shapes before SHACL validation execution.

```turtle
# Generated shape (processor output — not authored manually)
ex:DocumentGeneratedShape
    a sh:NodeShape ;
    sh:targetClass ex:Document ;
    sh:property [
        sh:path ex:documentId ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
        sh:datatype xsd:string
    ] ;
    sh:property [
        sh:path ex:status ;
        sh:minCount 1 ;
        sh:in ( ex:DRAFT ex:ACTIVE ex:DEPRECATED ex:ARCHIVED )
    ] .
```

### 11.3 Severity

Co-input SHACL shapes use `sh:severity` directly (`sh:Violation`, `sh:Warning`, `sh:Info`). Generated shapes default to `sh:Violation`.

### 11.4 Runtime Validation Integration

Generated service and API layers MUST invoke SHACL validation (merging co-input and generated shapes) before persisting any entity. Violations MUST be returned as structured error responses, not runtime exceptions.

### 11.5 Constraint Precedence and Merge Semantics

Constraints may originate from two sources: **co-input** SHACL shapes (authored, §11.1) and **generated** shapes derived from Prism cardinality/identity annotations (§11.2). Because both can target the same `(sh:targetClass, sh:path)`, processors apply the following precedence when merging:

1. **Co-input is authoritative.** For any constraint *component* (e.g. `sh:minCount`, `sh:maxCount`, `sh:datatype`, `sh:in`) on a given `(target, path)`, a co-input value always wins over a generated one.
2. **Generated shapes fill gaps only.** A processor MUST NOT emit a generated constraint component for a `(target, path)` where the co-input shapes already declare that component. Concretely, `prism:required true` contributes `sh:minCount 1` *only if* no co-input shape already declares `sh:minCount` on that path; likewise `prism:unique true` → `sh:maxCount 1`, and range/enumeration-derived `sh:datatype` / `sh:in`.
3. **Contradictions resolve to co-input, with a warning.** When a Prism annotation would generate a component that contradicts a co-input value on the same `(target, path)` — e.g. `prism:required true` (implying `sh:minCount 1`) against a co-input `sh:minCount 0` — the co-input value is used and the processor MUST emit a warning identifying the inconsistency, so the author can remove the redundant or conflicting Prism annotation.
4. **Merge is per-component, not per-shape.** Merging combines constraint components across all shapes targeting the same node; it never discards a co-input `sh:PropertyShape` wholesale. SPARQL constraints (`sh:sparql`), logical constraints (`sh:or` / `sh:and` / `sh:not` / `sh:xone`), `sh:message`, and `sh:severity` from co-input shapes are always preserved.

This makes the cardinality/identity annotations (`prism:required`, `prism:unique`) pure conveniences: anything they express can equivalently — and authoritatively — be written as SHACL, consistent with Design Principle P8 (SHACL-First for Constraints).

---

## 12. AI Agent Annotations

### 12.1 Agent Accessibility

```turtle
ex:Document
    prism:agentAccessible true ;
    prism:agentPermission prism:ReadOnly ;
    prism:semanticDescription """A Document is the central entity in this domain. It has a lifecycle: DRAFT → ACTIVE → DEPRECATED → ARCHIVED. Each document has an author (a Person), optional reviewers, a title, content, and a category. When a user asks about content or published material, query Documents first, then traverse to the contributing Persons via the hasContributor relationship.""" ;
    prism:toolName "getDocument" .
```

### 12.2 Agent Permission Values

| Value | Meaning |
|---|---|
| `prism:ReadOnly` | Agent may query and read; mutations are blocked |
| `prism:ReadWrite` | Agent may query and invoke approved `prism:Action` types |
| `prism:AgentRestricted` | Entity is hidden from agents entirely |

Default: `prism:ReadOnly` for all `prism:Entity` classes.

> **Extensibility:** Additional permission values may be declared as `owl:NamedIndividual` instances typed as `prism:AgentPermission`.

### 12.3 Semantic Description Guidelines

`prism:semanticDescription` SHOULD:
- Explain the entity's role in the domain, not just its technical definition
- Reference related entities by name
- Describe when and why an agent would query this entity
- Note any non-obvious constraints or business rules the agent must respect

`prism:semanticDescription` MUST NOT duplicate `rdfs:comment`. They serve different audiences: `rdfs:comment` is for developers; `prism:semanticDescription` is for LLMs.

### 12.4 Agent Tool Generation

A Level 4 processor MUST generate agent tool definitions for every entity where `prism:agentAccessible true`. For `prism:ReadOnly` entities, tools are read-only SPARQL wrappers. For `prism:ReadWrite` entities, tools additionally expose approved `prism:Action` operations.

Generated tool definitions MUST include:
- Tool name from `prism:toolName` (or derived from entity label)
- Tool description from `prism:semanticDescription`
- Parameter schema derived from `prism:filterable` properties
- Response schema from entity properties with `prism:sensitive false`

### 12.5 System Prompt Generation

A Level 4 processor MAY generate an LLM system prompt preamble from the full ontology, including:
- All entity labels and `prism:semanticDescription` values
- All relationship descriptions
- All `prism:example` values as concrete illustrations
- The JSON-LD context for structured output

---

## 13. Action Types

Actions are named operations that transcend simple CRUD. Each action generates an API endpoint, a service method, and (if the entity is `prism:agentAccessible`) an agent tool.

### 13.1 Action Declaration

```turtle
ex:PublishDocumentAction
    a prism:Action ;
    rdfs:label "Publish Document"@en ;
    rdfs:comment "Transitions a document from DRAFT to ACTIVE and notifies subscribers."@en ;
    prism:actionKind prism:UpdateAction ;
    prism:targetClass ex:Document ;
    prism:inputShape ex:PublishDocumentInputShape ;
    prism:outputShape ex:PublishDocumentOutputShape ;
    prism:outputClass ex:Document ;
    prism:requiredRole "ROLE_PUBLISHER" ;
    prism:sideEffect "Sets status to ACTIVE, records publishedAt timestamp, and sends notification to subscribers." .

ex:PublishDocumentInputShape
    a sh:NodeShape ;
    sh:property [
        sh:path ex:documentId ;
        sh:minCount 1 ;
        sh:datatype xsd:string
    ] ;
    sh:property [
        sh:path ex:targetAudience ;
        sh:minCount 1 ;
        sh:datatype xsd:string
    ] .

ex:PublishDocumentOutputShape
    a sh:NodeShape ;
    sh:property [ sh:path ex:documentId ; sh:minCount 1 ] ;
    sh:property [ sh:path ex:status ; sh:minCount 1 ] ;
    sh:property [ sh:path ex:publishedAt ; sh:minCount 1 ] .
```

### 13.2 Generated API Endpoint

```
POST /api/v1/documents/actions/publish-document
Authorization: Bearer {token with ROLE_PUBLISHER}
Content-Type: application/json

Body: validated against ex:PublishDocumentInputShape
Response: 200 OK, ex:Document serialised as JSON-LD
         validated against ex:PublishDocumentOutputShape
```

### 13.3 Action Kind Values

| Value | Generated HTTP method | Typical use |
|---|---|---|
| `prism:CreateAction` | POST | Create a new entity with domain logic |
| `prism:UpdateAction` | PATCH | State transition, business-rule update |
| `prism:DeleteAction` | DELETE | Soft-delete, archive, cancel |
| `prism:CustomAction` | POST | Domain operation (approve, submit, release) |

> **Extensibility:** Additional action kinds may be declared as `owl:NamedIndividual` instances typed as `prism:ActionKind`.

---

## 14. Query Types

Queries are named SPARQL SELECT statements exposed as read-only API endpoints and agent tools.

### 14.1 Query Declaration

```turtle
ex:ActiveDocumentsByAuthorQuery
    a prism:Query ;
    rdfs:label "Active Documents by Author"@en ;
    rdfs:comment "Returns all active documents contributed to by a given person."@en ;
    prism:outputClass ex:Document ;
    prism:sparqlQuery """
        SELECT ?document ?documentId ?title WHERE {
            ?document a ex:Document ;
                      ex:documentId ?documentId ;
                      ex:title ?title ;
                      ex:status ex:ACTIVE .
            ?document ex:hasContributor ?person .
            ?person ex:personId ?personId .
            FILTER (?personId = ?authorId)
        }
        ORDER BY ?title
    """ ;
    prism:queryParam [
        a prism:QueryParameter ;
        prism:paramName "authorId" ;
        prism:paramType "xsd:string" ;
        prism:paramRequired true
    ] .
```

### 14.2 Generated Endpoint

```
GET /api/v1/queries/active-documents-by-author?authorId=PERSON-0042
Authorization: Bearer {token}
Response: 200 OK, array of ex:Document JSON-LD
```

---

## 15. Computed Properties

*Requires Level 5 — Data Fabric.*

Computed properties derive their values at read time from a SPARQL expression or an external function. They are never stored directly.

Computed properties interact with other Prism annotations as follows:
- A computed property MUST NOT be included in persistence DDL as a stored column.
- A computed property MUST NOT be included in input DTO schemas or input SHACL validation.
- A computed property SHOULD be included in output/read schemas and API response bodies.
- `prism:required true` on a computed property means the computed value MUST be present in every read response, not that callers must supply it.

### 15.1 Declaration

```turtle
ex:wordCount
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:integer ;
    rdfs:label "word count"@en ;
    prism:computed true ;
    prism:computedBy [
        a prism:ComputedPropertyDef ;
        prism:sparqlExpression """
            SELECT (COUNT(?word) AS ?wordCount) WHERE {
                $this ex:content ?text .
                BIND(STRLEN(REPLACE(?text, "[^ ]+", "")) AS ?wordCount)
            }
        """ ;
        prism:cacheComputed true ;
        prism:cacheTtl 300
    ] .
```

### 15.2 Function Reference

When computation logic cannot be expressed in SPARQL:

```turtle
ex:readingTimeMinutes
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:decimal ;
    rdfs:label "reading time (minutes)"@en ;
    prism:computed true ;
    prism:computedBy [
        a prism:ComputedPropertyDef ;
        prism:functionRef "com.example.content.ReadingTimeEstimator" ;
        prism:cacheComputed false
    ] .
```

### 15.3 `prism:ComputedPropertyDef` Properties

| Property | Type | Description |
|---|---|---|
| `prism:sparqlExpression` | `xsd:string` | SPARQL SELECT projecting the value. Use `$this` for the subject node. |
| `prism:functionRef` | `xsd:string` | Fully-qualified function identifier |
| `prism:cacheComputed` | `xsd:boolean` | Cache the result. Default: `false` |
| `prism:cacheTtl` | `xsd:integer` | Cache TTL in seconds |

---

## 16. Data Source Binding

*Requires Level 5 — Data Fabric.*

Data source binding connects an entity class to a live external data source. The processor generates a source connector and sync pipeline.

### 16.1 Declaration

```turtle
ex:Taxonomy
    a owl:Class ;
    prism:datasource [
        a prism:DatasourceConfig ;
        prism:sourceType prism:JdbcSource ;
        prism:connectionRef "taxonomy-db" ;
        prism:syncStrategy prism:IncrementalSync ;
        prism:syncCursorProperty ex:lastModified ;
        prism:sourceQuery "SELECT * FROM taxonomy_terms WHERE updated_at > :cursor"
    ] .
```

### 16.2 Source Types

| Named Individual | Description |
|---|---|
| `prism:JdbcSource` | Relational database via JDBC |
| `prism:RestApiSource` | REST API with pagination |
| `prism:KafkaSource` | Kafka topic (streaming) |
| `prism:S3Source` | S3-compatible object storage |
| `prism:GraphQLSource` | GraphQL endpoint |
| `prism:SparqlSource` | Remote SPARQL endpoint |

> **Extensibility:** Additional source types may be declared as `owl:NamedIndividual` instances typed as `prism:DatasourceType`.

### 16.3 Sync Strategies

| Named Individual | Description |
|---|---|
| `prism:FullSync` | Re-fetch all records on every cycle |
| `prism:IncrementalSync` | Fetch only records changed since the last cursor position |
| `prism:StreamingSync` | Maintain a continuous streaming connection |

> **Extensibility:** Additional sync strategies may be declared as `owl:NamedIndividual` instances typed as `prism:SyncStrategy`.

---

## 17. Federation Configuration

*Requires Level 5 — Data Fabric.*

Federation enables some properties of an entity to be resolved on-demand from an external system without storing the data locally.

### 17.1 Declaration

```turtle
ex:Person
    a owl:Class ;
    prism:federated [
        a prism:FederationConfig ;
        prism:federationProtocol prism:GraphQLFederation ;
        prism:federationEndpoint "https://identity-service.internal/graphql"^^xsd:anyURI ;
        prism:federationKeyProperty ex:personId ;
        prism:federatedProperty ex:externalScore ;
        prism:federatedProperty ex:verificationStatus
    ] .
```

### 17.2 Federation Protocols

| Named Individual | Description |
|---|---|
| `prism:GraphQLFederation` | Apollo Federation 2 subgraph |
| `prism:GrpcFederation` | gRPC remote call |
| `prism:SparqlFederation` | SPARQL 1.1 SERVICE federation |
| `prism:RestFederation` | REST API call keyed by local property |

> **Extensibility:** Additional federation protocols may be declared as `owl:NamedIndividual` instances typed as `prism:FederationProtocol`.

---

## 18. Governance Configuration

*Requires Level 6 — Governance.*

Governance configuration declares stewardship, approval workflows, data classification, quality certification, and retention policies for an entity.

### 18.1 Declaration

```turtle
ex:Document
    a owl:Class ;
    prism:governance [
        a prism:GovernanceConfig ;
        prism:stewardRole "ROLE_CONTENT_STEWARD" ;
        prism:approvalRequired true ;
        prism:approvalWorkflow prism:TwoPersonReview ;
        prism:changeNotification true ;
        prism:retentionDays 2557 ;
        prism:retentionPolicy "Records Act — 7-year retention" ;
        prism:completenessThreshold "0.95"^^xsd:decimal ;
        prism:freshnessMaxAge "P30D"^^xsd:duration
    ] ;
    prism:qualityCertification prism:Gold ;
    prism:dataClass prism:Internal .
```

### 18.2 Approval Workflows

| Named Individual | Description |
|---|---|
| `prism:NoApproval` | Changes publish immediately |
| `prism:SingleReview` | One reviewer must approve |
| `prism:TwoPersonReview` | Two independent reviewers must approve |
| `prism:ConsensusReview` | All designated stewards must approve |

> **Extensibility:** Additional approval workflows may be declared as `owl:NamedIndividual` instances typed as `prism:ApprovalWorkflow`.

### 18.3 Data Classification

| Named Individual | Description |
|---|---|
| `prism:Public` | Freely shareable |
| `prism:Internal` | Internal use only |
| `prism:Confidential` | Restricted to authorised roles |
| `prism:StrictlyConfidential` | Board/executive access only |
| `prism:PII` | Personally Identifiable Information |
| `prism:PHI` | Protected Health Information (HIPAA) |
| `prism:PCI` | Payment Card Industry (PCI DSS) |

> **Extensibility:** Domain-specific classifications may be added as `owl:NamedIndividual` instances typed as `prism:DataClass`.

### 18.4 Quality Certification Tiers

| Named Individual | Description |
|---|---|
| `prism:Bronze` | Minimum viable quality: present and parseable |
| `prism:Silver` | Required fields populated; freshness within max age |
| `prism:Gold` | High completeness threshold; SHACL validation passing |
| `prism:Platinum` | Full completeness; lineage tracked; change-reviewed |

> **Extensibility:** Additional certification tiers may be declared as `owl:NamedIndividual` instances typed as `prism:QualityCertification`.

---

## 19. Event Configuration

*Requires Level 7 — Event-Driven.*

Event configuration declares that lifecycle events for an entity are published to a message bus. Processors generate event schema files and publisher/consumer stubs.

### 19.1 Declaration

```turtle
ex:Document
    prism:events [
        a prism:EventConfig ;
        prism:eventFormat prism:CloudEventsFormat ;
        prism:eventTopic "documents.events" ;
        prism:eventSource "https://platform.example.com/documents"^^xsd:anyURI ;
        prism:publishEvent prism:CreatedEvent,
                          prism:UpdatedEvent,
                          prism:StateTransitionEvent
    ] .
```

### 19.2 Event Formats

| Named Individual | Description |
|---|---|
| `prism:CloudEventsFormat` | CNCF CloudEvents 1.0 envelope |
| `prism:AvroFormat` | Apache Avro schema |
| `prism:ProtobufFormat` | Protocol Buffers |
| `prism:JsonSchemaFormat` | JSON Schema |

> **Extensibility:** Additional event formats may be declared as `owl:NamedIndividual` instances typed as `prism:EventFormat`.

### 19.3 Event Kinds

| Named Individual | Description |
|---|---|
| `prism:CreatedEvent` | A new instance was created |
| `prism:UpdatedEvent` | An instance's properties changed |
| `prism:DeletedEvent` | An instance was deleted |
| `prism:StateTransitionEvent` | A status/state property changed value |
| `prism:CustomEvent` | A domain-specific event |

> **Extensibility:** Additional event kinds may be declared as `owl:NamedIndividual` instances typed as `prism:EventKind`.

### 19.4 Generated Artefacts

A Level 7 processor MUST generate for each declared event format:
- Schema file (`.avsc`, `.proto`, or JSON Schema) per event kind
- Publisher stub (typed method per event kind)
- Consumer stub (abstract handler method per event kind)
- CloudEvents envelope mapping

---

## 20. Digital Twin Configuration

*Requires Level 7 — Event-Driven.*

A digital twin is a real-time RDF shadow graph mirroring the state of a physical entity. The processor generates a streaming state receiver, shadow graph writer, and snapshot scheduler.

### 20.1 Declaration

```turtle
ex:Device
    a owl:Class ;
    prism:digitalTwin [
        a prism:TwinConfig ;
        prism:physicalIdProperty ex:deviceSerialNumber ;
        prism:stateEventTopic "iot.devices.telemetry" ;
        prism:snapshotInterval "PT1H"^^xsd:duration ;
        prism:twinShadowGraph "https://twin.example.com/device/{id}" ;
        prism:twinStateShape ex:DeviceStateShape
    ] .
```

### 20.2 `prism:TwinConfig` Properties

| Property | Type | Description |
|---|---|---|
| `prism:physicalIdProperty` | `owl:DatatypeProperty` | Property whose value is the physical-world identifier |
| `prism:stateEventTopic` | `xsd:string` | Kafka/MQTT/AMQP topic for state update events |
| `prism:snapshotInterval` | `xsd:duration` | ISO 8601 duration between full state snapshots |
| `prism:twinShadowGraph` | `xsd:string` | Named graph IRI pattern; `{id}` is the entity identifier |
| `prism:twinStateShape` | `sh:NodeShape` | SHACL shape validated at every snapshot |

---

## 21. Temporal Configuration

*Requires Level 7 — Event-Driven.*

Temporal configuration enables valid-time, transaction-time, or bitemporal versioning for an entity. Processors generate history tables, AS-OF query APIs, and SPARQL named-graph versioning.

### 21.1 Declaration

```turtle
ex:Policy
    a owl:Class ;
    prism:temporal [
        a prism:TemporalConfig ;
        prism:temporalStrategy prism:Bitemporal ;
        # Valid-time interval (when the policy was in force)
        prism:validFromProperty ex:effectiveFrom ;
        prism:validToProperty   ex:effectiveTo ;
        # Transaction-time interval (when the record was stored)
        # Required for prism:Bitemporal; optional for prism:ValidTime
        prism:txFromProperty    ex:recordedAt ;
        prism:txToProperty      ex:supersededAt ;
        prism:historyGraphSuffix "/history" ;
        prism:historyTableSuffix "_history"
    ] .
```

### 21.2 Temporal Strategies

| Named Individual | Description |
|---|---|
| `prism:ValidTime` | Application time: when the fact was true in the real world. Requires `prism:validFromProperty`. |
| `prism:TransactionTime` | System time: when the fact was recorded in the database. Requires `prism:txFromProperty`. |
| `prism:Bitemporal` | Both valid time and transaction time tracked independently. Requires all four interval properties. |

> **Extensibility:** Additional temporal strategies may be declared as `owl:NamedIndividual` instances typed as `prism:TemporalStrategy`.

### 21.3 `prism:TemporalConfig` Properties

| Property | Strategy | Description |
|---|---|---|
| `prism:validFromProperty` | ValidTime, Bitemporal | Property holding the start of the valid-time interval (`xsd:dateTime`) |
| `prism:validToProperty` | ValidTime, Bitemporal | Property holding the end of the valid-time interval; null = currently valid |
| `prism:txFromProperty` | TransactionTime, Bitemporal | Property holding when this version was stored (`xsd:dateTime`) |
| `prism:txToProperty` | TransactionTime, Bitemporal | Property holding when this version was superseded; null = current version |
| `prism:historyGraphSuffix` | all | Suffix appended to the named graph IRI for the RDF history graph (e.g. `/history`) |
| `prism:historyTableSuffix` | all | Suffix appended to the relational table name for the history table (default: `_history`) |

### 21.4 Generated Artefacts

A Level 7 processor MUST generate:
- History table (relational: `{tableName}{historyTableSuffix}`) or history named graph (RDF: `{graphIRI}{historyGraphSuffix}`) for each temporal entity
- AS-OF query API endpoint: `GET /api/v1/{entity}/{id}/as-of?validTime=...&txTime=...`
- SPARQL named-graph versioning queries

---

## 22. Vector Configuration

*Requires Level 8 — Vector-Native.*

Vector configuration declares that an entity should have a vector embedding index. Processors generate an embedding pipeline, vector store schema, and semantic search API endpoint.

### 22.1 Declaration

```turtle
ex:Article
    a owl:Class ;
    prism:vector [
        a prism:VectorConfig ;
        prism:vectorBackend prism:PgVectorBackend ;
        prism:vectorModel "text-embedding-3-small" ;
        prism:vectorDimensions 1536 ;
        prism:vectorFields ex:title, ex:content, ex:summary ;
        prism:vectorTextTemplate "{title}: {content}" ;
        prism:vectorChunkSize 512 ;
        prism:vectorOverlapTokens 64
    ] .
```

Properties annotated with `prism:vectorIndexed true` are stored as filterable metadata in the vector index alongside each embedding.

### 22.2 Vector Backends

| Named Individual | Description |
|---|---|
| `prism:PgVectorBackend` | pgvector extension for PostgreSQL |
| `prism:WeaviateBackend` | Weaviate |
| `prism:QdrantBackend` | Qdrant |
| `prism:ChromaBackend` | Chroma |
| `prism:PineconeBackend` | Pinecone |
| `prism:MilvusBackend` | Milvus |

> **Extensibility:** Additional vector backends may be declared as `owl:NamedIndividual` instances typed as `prism:VectorBackend`.

### 22.3 `prism:VectorConfig` Properties

| Property | Type | Description |
|---|---|---|
| `prism:vectorBackend` | `prism:VectorBackend` | Target vector store |
| `prism:vectorModel` | `xsd:string` | Embedding model identifier |
| `prism:vectorDimensions` | `xsd:integer` | Embedding dimension count |
| `prism:vectorFields` | `owl:DatatypeProperty` list | Datatype properties whose text values are embedded (range: `owl:DatatypeProperty`) |
| `prism:vectorTextTemplate` | `xsd:string` | Optional template; `{localName}` as placeholders |
| `prism:vectorChunkSize` | `xsd:integer` | Max tokens per chunk. Default: 512 |
| `prism:vectorOverlapTokens` | `xsd:integer` | Overlap tokens between chunks. Default: 64 |
| `prism:vectorMetadata` | `owl:DatatypeProperty` list | Datatype properties stored as filterable index metadata (range: `owl:DatatypeProperty`) |

### 22.4 Generated Artefacts

A Level 8 processor MUST generate:
- Vector store schema (collection/index definition per backend)
- Embedding pipeline (scheduled ingest + incremental update on entity change)
- Semantic search endpoint: `POST /api/v1/{entities}/search` accepting a natural language query string and returning ranked entity matches with similarity scores

---

## 23. Interoperability Mappings

Prism ontologies SHOULD declare alignment with external ontologies to enable semantic interoperability across systems.

### 23.1 Class Mapping

```turtle
ex:Document
    prism:mapsTo <http://schema.org/CreativeWork> ;
    prism:mappingType prism:NearMatch .

ex:Person
    prism:mapsTo <http://schema.org/Person> ;
    prism:mappingType prism:ExactMatch .
```

### 23.2 Property Mapping

```turtle
ex:documentId
    prism:mapsTo <http://purl.org/dc/terms/identifier> ;
    prism:mappingType prism:ExactMatch .

ex:title
    prism:mapsTo <http://purl.org/dc/terms/title> ;
    prism:mappingType prism:ExactMatch .
```

### 23.3 Mapping Type Values

| Value | Meaning |
|---|---|
| `prism:ExactMatch` | Identical meaning; same extension |
| `prism:NearMatch` | Closely related but not logically identical |
| `prism:BroaderTerm` | The external term is broader in scope |
| `prism:NarrowerTerm` | The external term is narrower in scope |

### 23.4 Supported External Standards

Prism-conformant ontologies SHOULD map to relevant external ontologies and vocabularies. The choice of target standards depends entirely on the domain being modelled. Representative examples:

| Domain | Standard | Namespace |
|---|---|---|
| General entities, creative works | schema.org | `http://schema.org/` |
| Bibliographic metadata | Dublin Core Terms | `http://purl.org/dc/terms/` |
| Persons and contacts | W3C vCard Ontology | `http://www.w3.org/2006/vcard/ns#` |
| Provenance and lineage | W3C PROV-O | `http://www.w3.org/ns/prov#` |
| Healthcare | HL7 FHIR | `http://hl7.org/fhir/` |
| Finance / legal entities | GLEIF LEI | `https://www.gleif.org/ontology/` |
| Trade & logistics | GS1 | `https://www.gs1.org/voc/` |
| Scientific data | W3C DCAT | `http://www.w3.org/ns/dcat#` |
| Physical quantities & units | QUDT | `http://qudt.org/schema/qudt/` |

Domain profiles SHOULD identify the most relevant external standards for their subject matter and declare alignments using `prism:mapsTo`.

### 23.5 `owl:equivalentClass` vs `prism:mapsTo`

Use `owl:equivalentClass` only when the mapping is logically exact and you are prepared for OWL reasoners to treat the classes as identical. Use `prism:mapsTo` with `prism:ExactMatch` for all other cases — it documents the alignment without asserting logical equivalence.

---

## 24. Ontology Metadata and Versioning

### 24.1 Required Metadata

Every Prism ontology MUST declare:

```turtle
<https://example.com/ontologies/document>
    a owl:Ontology ;
    rdfs:label "Document Management Ontology"@en ;
    dcterms:created "2026-05-25"^^xsd:date ;
    dcterms:modified "2026-05-25"^^xsd:date ;
    owl:versionIRI <https://example.com/ontologies/document/1.0.0> ;
    owl:versionInfo "1.0.0" ;
    prism:conformanceLevel prism:Level3Api .
```

### 24.2 Recommended Metadata

```turtle
<https://example.com/ontologies/document>
    dcterms:title "Document Management Ontology"@en ;
    dcterms:description "Models documents, contributors, and publishing workflows."@en ;
    dcterms:creator "Acme Corp" ;
    dcterms:license <https://creativecommons.org/licenses/by/4.0/> ;
    vann:preferredNamespacePrefix "ex" ;
    vann:preferredNamespaceUri "https://example.com/ontologies/document#" ;
    owl:imports <https://spec.prism.dev/ns> .
```

### 24.3 Semantic Versioning

Prism ontologies MUST use [Semantic Versioning 2.0](https://semver.org/):

- **MAJOR** — breaking changes: class removal, property removal, type change, cardinality restriction tightening
- **MINOR** — backwards-compatible additions: new classes, new optional properties, new relationships
- **PATCH** — non-semantic changes: label corrections, comment updates, example additions

A change is **breaking** if it would invalidate existing persisted data or require changes to generated code.

---

## 25. Schema Migrations

Prism provides a standard way to declare schema migrations as part of the ontology. Migrations are `prism:Migration` instances associated with the ontology.

### 25.1 Migration Declaration

```turtle
ex:Migration_1_0_to_1_1
    a prism:Migration ;
    rdfs:label "Rename legacyRef to documentId"@en ;
    prism:fromVersion "1.0.0" ;
    prism:toVersion "1.1.0" ;
    prism:isBreaking true ;
    prism:migrationScript """
        DELETE { ?s ex:legacyRef ?o }
        INSERT { ?s ex:documentId ?o }
        WHERE  { ?s ex:legacyRef ?o }
    """ .
```

### 25.2 Migration Ordering

Processors MUST execute migrations in ascending version order. Migrations MUST be idempotent — running a migration twice MUST produce the same result as running it once.

### 25.3 Breaking Migration Protocol

When `prism:isBreaking true`, processors MUST:

1. Warn the operator before executing
2. Create a snapshot of affected data
3. Execute the migration
4. Validate the migrated data against the new SHACL shapes
5. Report success or rollback to the snapshot on failure

---

## 26. Serialization Formats

### 26.1 Primary Format

**Turtle** (`.ttl`) is the primary and recommended serialisation format for Prism ontologies. It is human-readable, compact, and widely supported.

### 26.2 Supported Formats

| Format | Media Type | Status |
|---|---|---|
| Turtle | `text/turtle` | **Primary** (recommended) |
| JSON-LD 1.1 | `application/ld+json` | Supported; Prism JSON-LD context provided |
| N-Triples | `application/n-triples` | Supported (tooling interop) |
| RDF/XML | `application/rdf+xml` | Supported (legacy interop) |

### 26.3 Multi-File Ontologies

Large domain models SHOULD be split into multiple files using `owl:imports`. Each file MUST be a valid standalone RDF document. Processors MUST resolve `owl:imports` before generating artefacts.

```turtle
<https://example.com/ontologies/document>
    owl:imports <https://example.com/ontologies/document/core> ;
    owl:imports <https://example.com/ontologies/document/person> ;
    owl:imports <https://example.com/ontologies/document/governance> .
```

### 26.4 Co-Input SHACL Shapes Discovery

Processors need to locate co-input SHACL shapes. Recommended patterns, in order of preference:

1. **Same file** — shapes and ontology in the same Turtle document. Processors parse both together. This is the simplest approach for small domains.
2. **Separate shapes file via `owl:imports`** — declare the shapes file as an import of the main ontology:
   ```turtle
   <https://example.com/ontologies/document>
       owl:imports <https://example.com/ontologies/document/shapes> .
   ```
3. **Explicit processor input** — pass the shapes graph as a second input file to the processor command. Processors MUST accept a `--shapes` argument (or equivalent) in addition to the ontology path.

Processors MUST follow `owl:imports` transitively when resolving shapes. A `prism:validatedBy` link is still required on each class even when shapes are in an imported file — it is the primary lookup mechanism.

---

## 27. Conformance Levels

Prism organises generation into **capabilities** (Section 3.1) and bundles them into eight cumulative **conformance-level profiles**. A processor need not implement every capability; it conforms for whatever capability set it provides (Section 3). The level definitions below double as (a) the normative list of artefacts each capability mandates and (b) the cumulative profile bundles.

### 27.0 Capability catalogue

| Capability | Introduced by | Depends on | Admits multiple targets? | Mandated artefacts |
|---|---|---|---|---|
| `prism:CoreModel` | Level 1 | — | per output language | Entity/value classes, enumerations, type-safe accessors, application of co-input SHACL |
| `prism:Persistence` | Level 2 | CoreModel | **Yes** — ≥1 backend (JPA/Mongo/Neo4j/RDF4J/Dynamo) | Repositories, persistence mapping, generated+merged shapes, migrations, indexes |
| `prism:RestApi` | Level 3 | CoreModel | — | REST controllers + OpenAPI 3.1 |
| `prism:GraphQL` | Level 3 | CoreModel | — | GraphQL schema + resolvers (for `prism:generateGraphQL true`) |
| `prism:Grpc` | Level 3 | CoreModel | — | gRPC service defs + stubs (for `prism:generateGrpc true`) |
| `prism:AiTools` | Level 4 | CoreModel | — | Agent tool defs, semantic descriptions, system-prompt preamble, JSON-LD context |
| `prism:DataFabric` | Level 5 | Persistence | **Yes** — ≥1 source/protocol | Source connectors, computed-property resolvers, federation adapters |
| `prism:Governance` | Level 6 | Persistence | — | Governance workflows, classification enforcement, lineage, retention, quality |
| `prism:Events` | Level 7 | CoreModel | **Yes** — ≥1 format (Avro/Protobuf/JSON Schema/CloudEvents) | Event schemas + publisher/consumer stubs |
| `prism:DigitalTwin` | Level 7 | Events | — | Streaming state receivers, shadow-graph writers, snapshot scheduling |
| `prism:Temporal` | Level 7 | Persistence | — | Bitemporal history tables + AS-OF query APIs |
| `prism:Vector` | Level 8 | CoreModel | **Yes** — ≥1 backend (pgvector/Weaviate/Qdrant/Chroma/Pinecone/Milvus) | Vector store schema, embedding pipeline, semantic search endpoints |

The eight profiles bundle these capabilities cumulatively: **L1**=CoreModel; **L2**=+Persistence; **L3**=+RestApi, GraphQL, Grpc; **L4**=+AiTools; **L5**=+DataFabric; **L6**=+Governance; **L7**=+Events, DigitalTwin, Temporal; **L8**=+Vector. Declaring `prism:conformanceLevel prism:Ln` is equivalent to requiring every capability up to and including *Ln*. To target a subset that no single profile expresses (e.g. CoreModel + Vector only), use `prism:requiresCapability` instead (Section 3.1).

### Level 1: Core

Minimum viable conformance. Processor generates language entity classes only.

**Processor MUST generate:**
- Language-specific entity classes for all `prism:Entity` and `prism:ValueObject` classes
- Enumeration types for all `prism:Enumeration` classes
- Type-safe property accessors reflecting OWL datatype ranges
- Null-safety reflecting `prism:required`

**Declare:** `prism:conformanceLevel prism:Level1Core`

---

### Level 2: Persistence

**All Level 1 requirements, plus:**
- Repository/DAO interfaces for all `prism:Entity` with `prism:generateRepository true`
- Persistence mapping files (JPA annotations, MongoDB codecs, Cypher node definitions, SPARQL INSERT templates) per `prism:PersistenceConfig`
- Generated SHACL shapes derived from `prism:required`, `prism:unique`, and property datatype ranges
- Co-input SHACL shapes (from `prism:validatedBy`) merged and applied at validation
- Database migration scripts for `prism:Migration` instances
- Index creation for `prism:indexed true` and `prism:unique true` properties

**Declare:** `prism:conformanceLevel prism:Level2Persistence`

---

### Level 3: API

**All Level 2 requirements, plus:**
- REST controllers with all endpoints specified by `prism:ApiConfig`
- OpenAPI 3.1 specification including constraints from co-input SHACL shapes
- GraphQL schema and resolvers for `prism:generateGraphQL true` entities
- gRPC service definitions and stubs for `prism:generateGrpc true` entities
- Request/response DTO classes derived from entity properties
- SHACL-grounded request validation in service layer
- API endpoint stubs for all `prism:Action` and `prism:Query` instances

**Declare:** `prism:conformanceLevel prism:Level3Api`

---

### Level 4: AI-Native

**All Level 3 requirements, plus:**
- Agent tool definitions for all `prism:agentAccessible true` entities
- Agent tool input schemas from `prism:filterable` properties
- Agent tool output schemas from entity properties (excluding `prism:sensitive`)
- LLM system prompt preamble from `prism:semanticDescription` values
- JSON-LD context file for structured agent output
- Agent action tools from `prism:Action` instances where target entity is `prism:ReadWrite`

**Declare:** `prism:conformanceLevel prism:Level4AiNative`

---

### Level 5: Data Fabric

**All Level 4 requirements, plus:**
- Live source connectors and sync pipelines for all `prism:DatasourceConfig` declarations
- Computed property resolvers for all properties with `prism:computed true`
- Federation adapters for all `prism:FederationConfig` declarations

**Declare:** `prism:conformanceLevel prism:Level5DataFabric`

---

### Level 6: Governance

**All Level 5 requirements, plus:**
- Governance workflows (approval queues, steward notification) from `prism:GovernanceConfig`
- Data quality dashboard showing completeness and freshness metrics per `prism:qualityCertification` tier
- Data classification enforcement: properties classified `prism:PII`, `prism:PHI`, or `prism:PCI` MUST be masked in logs and non-privileged API responses
- Data lineage graph derived from `prism:derivedFrom` and `prism:derivedUsing` annotations
- Retention policy enforcement code and scheduled purge jobs from `prism:retentionDays`
- Geographic restriction enforcement from `prism:geographicRestriction`

**Declare:** `prism:conformanceLevel prism:Level6Governance`

---

### Level 7: Event-Driven

**All Level 6 requirements, plus:**
- Event schema files (Avro, Protobuf, JSON Schema, CloudEvents) for all `prism:EventConfig` declarations
- Publisher and consumer stubs per event kind
- Digital twin streaming state receivers and shadow graph writers for all `prism:TwinConfig` declarations
- Snapshot scheduler for `prism:snapshotInterval`
- SHACL validation at every twin snapshot against `prism:twinStateShape`
- Bitemporal history tables and AS-OF query APIs for all `prism:TemporalConfig` declarations

**Declare:** `prism:conformanceLevel prism:Level7EventDriven`

---

### Level 8: Vector-Native

**All Level 7 requirements, plus:**
- Vector store schema (collection/index definition) per `prism:VectorConfig` declaration
- Embedding pipeline with scheduled full ingest and incremental update on entity change
- Semantic search API endpoint for each vector-configured entity
- RAG grounding: agent tool descriptions include semantic search capability for vector-configured entities, enabling retrieval-augmented generation in agent workflows

**Declare:** `prism:conformanceLevel prism:Level8VectorNative`

---

## 28. Complete Example: Document Domain

The following is a self-contained Prism-conformant ontology for a generic document management domain, demonstrating all eight conformance levels. Replace the `ex:` namespace and class names with your own domain vocabulary.

```turtle
@prefix ex:      <https://example.com/ontologies/document#> .
@prefix prism:   <https://spec.prism.dev/ns#> .
@prefix owl:     <http://www.w3.org/2002/07/owl#> .
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd:     <http://www.w3.org/2001/XMLSchema#> .
@prefix sh:      <http://www.w3.org/ns/shacl#> .
@prefix dcterms: <http://purl.org/dc/terms/> .
@prefix vann:    <http://purl.org/vocab/vann/> .
@prefix schema:  <http://schema.org/> .

###########################################################################
# Ontology Header
###########################################################################

<https://example.com/ontologies/document>
    a owl:Ontology ;
    rdfs:label "Document Management Ontology"@en ;
    dcterms:title "Document Management Ontology"@en ;
    dcterms:description "Models documents, contributors, categories, and publishing workflows."@en ;
    dcterms:creator "Acme Corp" ;
    dcterms:created "2026-05-25"^^xsd:date ;
    owl:versionIRI <https://example.com/ontologies/document/1.0.0> ;
    owl:versionInfo "1.0.0" ;
    prism:conformanceLevel prism:Level8VectorNative ;
    vann:preferredNamespacePrefix "ex" ;
    vann:preferredNamespaceUri "https://example.com/ontologies/document#" ;
    owl:imports <https://spec.prism.dev/ns> .

###########################################################################
# Enumerations  (Level 1)
###########################################################################

ex:DocumentStatus
    a owl:Class ;
    rdfs:label "Document Status"@en ;
    prism:entityKind prism:Enumeration .

ex:DRAFT      a ex:DocumentStatus, owl:NamedIndividual ; rdfs:label "DRAFT"@en .
ex:ACTIVE     a ex:DocumentStatus, owl:NamedIndividual ; rdfs:label "ACTIVE"@en .
ex:DEPRECATED a ex:DocumentStatus, owl:NamedIndividual ; rdfs:label "DEPRECATED"@en .
ex:ARCHIVED   a ex:DocumentStatus, owl:NamedIndividual ; rdfs:label "ARCHIVED"@en .

ex:Category
    a owl:Class ;
    rdfs:label "Category"@en ;
    prism:entityKind prism:Enumeration .

ex:TECHNICAL a ex:Category, owl:NamedIndividual ; rdfs:label "TECHNICAL"@en .
ex:POLICY    a ex:Category, owl:NamedIndividual ; rdfs:label "POLICY"@en .
ex:REFERENCE a ex:Category, owl:NamedIndividual ; rdfs:label "REFERENCE"@en .
ex:GUIDE     a ex:Category, owl:NamedIndividual ; rdfs:label "GUIDE"@en .

###########################################################################
# Value Objects  (Level 1)
###########################################################################

ex:MonetaryAmount
    a owl:Class ;
    rdfs:label "Monetary Amount"@en ;
    rdfs:comment "A currency-denominated value."@en ;
    prism:entityKind prism:ValueObject ;
    prism:generateCode true .

ex:amount
    a owl:DatatypeProperty ;
    rdfs:domain ex:MonetaryAmount ;
    rdfs:range xsd:decimal ;
    rdfs:label "amount"@en ;
    prism:required true .

ex:currency
    a owl:DatatypeProperty ;
    rdfs:domain ex:MonetaryAmount ;
    rdfs:range xsd:string ;
    rdfs:label "currency"@en ;
    prism:required true ;
    prism:example "USD" .

###########################################################################
# Core Entity: Person  (Level 1–6)
# Persons play roles (author, reviewer, editor) via the
# ex:hasContributor relationship — "author" is a role, not a class.
###########################################################################

ex:Person
    a owl:Class ;
    rdfs:label "Person"@en ;
    rdfs:comment "An individual who may contribute to documents in various roles."@en ;
    prism:entityKind prism:Entity ;
    prism:pluralLabel "Persons"@en ;
    prism:generateCode true ;
    prism:generateRepository true ;
    prism:generateRestEndpoint true ;
    prism:validatedBy ex:PersonShape ;
    prism:agentAccessible true ;
    prism:agentPermission prism:ReadOnly ;
    prism:semanticDescription """A Person is an individual who contributes to documents. Persons play roles — author, reviewer, editor — expressed through the hasContributor relationship with a contributionType edge property. Query Persons to find who contributed to a document, then filter by contributionType to find authors vs reviewers.""" ;
    prism:mapsTo schema:Person ;
    prism:mappingType prism:ExactMatch ;
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:JpaBackend ;
        prism:tableName "persons" ;
        prism:enableCache true ;
        prism:cacheTtlSeconds 3600
    ] ;
    prism:apiConfig [
        a prism:ApiConfig ;
        prism:basePath "/api/v1/persons" ;
        prism:allowedMethod prism:GET, prism:POST, prism:PATCH ;
        prism:requiresAuthentication true ;
        prism:paginationDefault 50 ;
        prism:apiTag "Persons"
    ] ;
    prism:governance [
        a prism:GovernanceConfig ;
        prism:stewardRole "ROLE_DATA_STEWARD" ;
        prism:approvalRequired false ;
        prism:retentionDays 2557 ;
        prism:retentionPolicy "Data retention policy — 7 years"
    ] ;
    prism:dataClass prism:PII .

ex:personId
    a owl:DatatypeProperty ;
    rdfs:domain ex:Person ;
    rdfs:range xsd:string ;
    rdfs:label "person ID"@en ;
    prism:required true ;
    prism:unique true ;
    prism:immutable true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:example "PERSON-0042" .

ex:fullName
    a owl:DatatypeProperty ;
    rdfs:domain ex:Person ;
    rdfs:range xsd:string ;
    rdfs:label "full name"@en ;
    prism:required true ;
    prism:indexed true ;
    prism:searchable true ;
    prism:sortable true ;
    prism:filterable true ;
    prism:example "Jane Smith" .

ex:email
    a owl:DatatypeProperty ;
    rdfs:domain ex:Person ;
    rdfs:range xsd:string ;
    rdfs:label "email address"@en ;
    prism:required true ;
    prism:unique true ;
    prism:sensitive true ;
    prism:dataClass prism:PII ;
    prism:example "jane.smith@example.com" .

###########################################################################
# Core Entity: Document  (Level 1–8)
###########################################################################

ex:Document
    a owl:Class ;
    rdfs:label "Document"@en ;
    rdfs:comment "A versioned artefact that progresses through a publishing lifecycle."@en ;
    prism:entityKind prism:Entity ;
    prism:pluralLabel "Documents"@en ;
    prism:generateCode true ;
    prism:generateRepository true ;
    prism:generateRestEndpoint true ;
    prism:generateGraphQL true ;
    prism:validatedBy ex:DocumentShape ;
    prism:agentAccessible true ;
    prism:agentPermission prism:ReadWrite ;
    prism:semanticDescription """A Document is the central entity in this domain. It has a lifecycle: DRAFT → ACTIVE → DEPRECATED → ARCHIVED. Each document has one or more contributors (Persons) playing roles such as author, reviewer, or editor via the hasContributor relationship. When a user asks about published content, filter by status ACTIVE. Documents can be organised by Category. Retrieving a document's contributors requires traversing hasContributor to Person.""" ;
    prism:mapsTo schema:CreativeWork ;
    prism:mappingType prism:NearMatch ;
    # Level 2 — persistence
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:JpaBackend ;
        prism:tableName "documents" ;
        prism:enableCache true ;
        prism:cacheTtlSeconds 60
    ] ;
    # Level 3 — REST API
    prism:apiConfig [
        a prism:ApiConfig ;
        prism:basePath "/api/v1/documents" ;
        prism:allowedMethod prism:GET, prism:POST, prism:PATCH ;
        prism:requiresAuthentication true ;
        prism:paginationDefault 25 ;
        prism:paginationMax 200 ;
        prism:apiTag "Documents"
    ] ;
    # Level 6 — governance
    prism:governance [
        a prism:GovernanceConfig ;
        prism:stewardRole "ROLE_CONTENT_STEWARD" ;
        prism:approvalRequired true ;
        prism:approvalWorkflow prism:TwoPersonReview ;
        prism:changeNotification true ;
        prism:retentionDays 2557 ;
        prism:retentionPolicy "Records Act — 7-year retention" ;
        prism:completenessThreshold "0.95"^^xsd:decimal ;
        prism:freshnessMaxAge "P30D"^^xsd:duration
    ] ;
    prism:qualityCertification prism:Gold ;
    prism:dataClass prism:Internal ;
    # Level 7 — events
    prism:events [
        a prism:EventConfig ;
        prism:eventFormat prism:CloudEventsFormat ;
        prism:eventTopic "documents.events" ;
        prism:eventSource "https://platform.example.com/documents"^^xsd:anyURI ;
        prism:publishEvent prism:CreatedEvent, prism:StateTransitionEvent
    ] ;
    # Level 8 — vector search
    prism:vector [
        a prism:VectorConfig ;
        prism:vectorBackend prism:PgVectorBackend ;
        prism:vectorModel "text-embedding-3-small" ;
        prism:vectorDimensions 1536 ;
        prism:vectorFields ex:title, ex:content ;
        prism:vectorTextTemplate "{title}: {content}" ;
        prism:vectorChunkSize 512 ;
        prism:vectorOverlapTokens 64
    ] .

# Properties
ex:documentId
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:string ;
    rdfs:label "document ID"@en ;
    prism:required true ;
    prism:unique true ;
    prism:immutable true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:sortable true ;
    prism:vectorIndexed true ;
    prism:example "DOC-20260001" ;
    prism:columnName "document_id" .

ex:title
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:string ;
    rdfs:label "title"@en ;
    prism:required true ;
    prism:indexed true ;
    prism:searchable true ;
    prism:sortable true ;
    prism:filterable true ;
    prism:vectorIndexed true ;
    prism:example "Introduction to Prism" .

ex:content
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:string ;
    rdfs:label "content"@en ;
    prism:searchable true .

ex:status
    a owl:ObjectProperty ;
    rdfs:domain ex:Document ;
    rdfs:range ex:DocumentStatus ;
    rdfs:label "status"@en ;
    prism:required true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:sortable true ;
    prism:defaultValue ex:DRAFT .

ex:category
    a owl:ObjectProperty ;
    rdfs:domain ex:Document ;
    rdfs:range ex:Category ;
    rdfs:label "category"@en ;
    prism:indexed true ;
    prism:filterable true .

ex:publishedAt
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:dateTime ;
    rdfs:label "published at"@en ;
    prism:sortable true ;
    prism:filterable true ;
    prism:immutable true .

ex:version
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:string ;
    rdfs:label "version"@en ;
    prism:example "1.0.0" .

# Level 5 — computed property
ex:wordCount
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:integer ;
    rdfs:label "word count"@en ;
    prism:computed true ;
    prism:computedBy [
        a prism:ComputedPropertyDef ;
        prism:functionRef "com.example.content.WordCounter" ;
        prism:cacheComputed true ;
        prism:cacheTtl 600
    ] .

# Level 6 — data lineage
ex:derivedSummary
    a owl:DatatypeProperty ;
    rdfs:domain ex:Document ;
    rdfs:range xsd:string ;
    rdfs:label "derived summary"@en ;
    prism:computed true ;
    prism:derivedFrom ex:content ;
    prism:derivedUsing ex:SummarisationQuery ;
    prism:computedBy [
        a prism:ComputedPropertyDef ;
        prism:functionRef "com.example.content.SummaryGenerator" ;
        prism:cacheComputed true ;
        prism:cacheTtl 3600
    ] .

# Relationships
ex:hasContributor
    a owl:ObjectProperty ;
    rdfs:domain ex:Document ;
    rdfs:range ex:Person ;
    rdfs:label "has contributor"@en ;
    prism:cardinality prism:ManyToMany ;
    prism:cascade prism:CascadeNone ;
    prism:fetchStrategy prism:Lazy ;
    prism:joinTable "document_contributors" ;
    prism:edgeProperties ex:ContributorRole .

ex:ContributorRole
    a prism:EdgeProperties ;
    prism:edgeProperty ex:contributionType ;
    prism:edgeProperty ex:contributedAt .

ex:contributionType
    a owl:DatatypeProperty ;
    rdfs:label "contribution type"@en ;
    rdfs:range xsd:string .     # e.g. "author", "reviewer", "editor"

ex:contributedAt
    a owl:DatatypeProperty ;
    rdfs:label "contributed at"@en ;
    rdfs:range xsd:dateTime .

ex:licensedUnder
    a owl:ObjectProperty ;
    rdfs:domain ex:Document ;
    rdfs:range ex:MonetaryAmount ;
    rdfs:label "licensed under (fee)"@en ;
    prism:cascade prism:CascadeAll .

###########################################################################
# Co-input SHACL Shapes  (Level 2+, authored — not generated)
###########################################################################

ex:MonetaryAmountShape
    a sh:NodeShape ;
    sh:targetClass ex:MonetaryAmount ;
    sh:property [
        sh:path ex:amount ;
        sh:minCount 1 ;
        sh:datatype xsd:decimal ;
        sh:minInclusive "0.00"^^xsd:decimal ;
        sh:message "Amount must be a non-negative decimal."@en
    ] ;
    sh:property [
        sh:path ex:currency ;
        sh:minCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "[A-Z]{3}" ;
        sh:message "Currency must be a 3-letter ISO 4217 code."@en
    ] .

ex:DocumentShape
    a sh:NodeShape ;
    sh:targetClass ex:Document ;
    sh:property [
        sh:path ex:documentId ;
        sh:minCount 1 ; sh:maxCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "DOC-[0-9]+" ;
        sh:maxLength 20 ;
        sh:message "Document ID must match pattern DOC-<number>."@en
    ] ;
    sh:property [
        sh:path ex:title ;
        sh:minCount 1 ;
        sh:datatype xsd:string ;
        sh:maxLength 500
    ] ;
    sh:sparql [
        sh:message "Active documents must have non-empty content."@en ;
        sh:severity sh:Warning ;
        sh:select """
            SELECT $this WHERE {
                $this ex:status ex:ACTIVE .
                OPTIONAL { $this ex:content ?c }
                FILTER (!BOUND(?c) || ?c = "")
            }
        """
    ] .

ex:PersonShape
    a sh:NodeShape ;
    sh:targetClass ex:Person ;
    sh:property [
        sh:path ex:personId ;
        sh:minCount 1 ; sh:maxCount 1 ;
        sh:datatype xsd:string
    ] ;
    sh:property [
        sh:path ex:fullName ;
        sh:minCount 1 ;
        sh:datatype xsd:string ;
        sh:maxLength 500
    ] ;
    sh:property [
        sh:path ex:email ;
        sh:minCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "^[^@]+@[^@]+\\.[^@]+$" ;
        sh:message "Email must be a valid address."@en
    ] .

###########################################################################
# Actions  (Level 3+)
###########################################################################

ex:PublishDocumentAction
    a prism:Action ;
    rdfs:label "Publish Document"@en ;
    rdfs:comment "Transitions a document from DRAFT to ACTIVE and notifies subscribers."@en ;
    prism:actionKind prism:UpdateAction ;
    prism:targetClass ex:Document ;
    prism:inputShape ex:PublishDocumentInputShape ;
    prism:outputShape ex:PublishDocumentOutputShape ;
    prism:outputClass ex:Document ;
    prism:requiredRole "ROLE_PUBLISHER" ;
    prism:sideEffect "Sets status to ACTIVE, records publishedAt, publishes a StateTransitionEvent, and notifies subscribers." .

ex:PublishDocumentInputShape
    a sh:NodeShape ;
    sh:property [
        sh:path ex:documentId ;
        sh:minCount 1 ;
        sh:datatype xsd:string
    ] ;
    sh:property [
        sh:path ex:targetAudience ;
        sh:minCount 1 ;
        sh:datatype xsd:string
    ] .

ex:PublishDocumentOutputShape
    a sh:NodeShape ;
    sh:property [ sh:path ex:documentId  ; sh:minCount 1 ] ;
    sh:property [ sh:path ex:status      ; sh:minCount 1 ] ;
    sh:property [ sh:path ex:publishedAt ; sh:minCount 1 ] .

###########################################################################
# Named Queries  (Level 3+)
###########################################################################

ex:ActiveDocumentsByAuthorQuery
    a prism:Query ;
    rdfs:label "Active Documents by Author"@en ;
    rdfs:comment "Returns all active documents contributed to by a given person."@en ;
    prism:outputClass ex:Document ;
    prism:sparqlQuery """
        SELECT ?document ?documentId ?title WHERE {
            ?document a ex:Document ;
                      ex:documentId ?documentId ;
                      ex:title ?title ;
                      ex:status ex:ACTIVE .
            ?document ex:hasContributor ?person .
            ?person ex:personId ?personId .
            FILTER (?personId = ?authorId)
        }
        ORDER BY ?title
    """ ;
    prism:queryParam [
        a prism:QueryParameter ;
        prism:paramName "authorId" ;
        prism:paramType "xsd:string" ;
        prism:paramRequired true
    ] .

###########################################################################
# Migrations  (Level 2+)
###########################################################################

ex:Migration_1_0_to_1_1
    a prism:Migration ;
    rdfs:label "Rename legacyRef to documentId"@en ;
    prism:fromVersion "1.0.0" ;
    prism:toVersion "1.1.0" ;
    prism:isBreaking true ;
    prism:migrationScript """
        DELETE { ?s ex:legacyRef ?o }
        INSERT { ?s ex:documentId ?o }
        WHERE  { ?s ex:legacyRef ?o }
    """ .
```

---

## 29. JSON-LD Context

Prism provides an official JSON-LD 1.1 context at `https://spec.prism.dev/context/prism.jsonld`. The following is an excerpt covering the most commonly used terms:

```json
{
  "@context": {
    "prism":     "https://spec.prism.dev/ns#",
    "owl":        "http://www.w3.org/2002/07/owl#",
    "rdfs":       "http://www.w3.org/2000/01/rdf-schema#",
    "xsd":        "http://www.w3.org/2001/XMLSchema#",
    "dcterms":    "http://purl.org/dc/terms/",
    "sh":         "http://www.w3.org/ns/shacl#",

    "entityKind":           { "@id": "prism:entityKind",           "@type": "@id" },
    "generateCode":         { "@id": "prism:generateCode",         "@type": "xsd:boolean" },
    "generateRepository":   { "@id": "prism:generateRepository",   "@type": "xsd:boolean" },
    "generateRestEndpoint": { "@id": "prism:generateRestEndpoint", "@type": "xsd:boolean" },
    "generateGraphQL":      { "@id": "prism:generateGraphQL",      "@type": "xsd:boolean" },
    "generateGrpc":         { "@id": "prism:generateGrpc",         "@type": "xsd:boolean" },
    "pluralLabel":          { "@id": "prism:pluralLabel",          "@type": "xsd:string" },
    "persistence":          { "@id": "prism:persistence",          "@type": "@id" },
    "apiConfig":            { "@id": "prism:apiConfig",            "@type": "@id" },
    "conformanceLevel":     { "@id": "prism:conformanceLevel",     "@type": "@id" },
    "requiresCapability":   { "@id": "prism:requiresCapability",   "@type": "@id" },
    "validatedBy":          { "@id": "prism:validatedBy",          "@type": "@id" },

    "required":             { "@id": "prism:required",       "@type": "xsd:boolean" },
    "unique":               { "@id": "prism:unique",         "@type": "xsd:boolean" },
    "indexed":              { "@id": "prism:indexed",        "@type": "xsd:boolean" },
    "searchable":           { "@id": "prism:searchable",     "@type": "xsd:boolean" },
    "sortable":             { "@id": "prism:sortable",       "@type": "xsd:boolean" },
    "filterable":           { "@id": "prism:filterable",     "@type": "xsd:boolean" },
    "sensitive":            { "@id": "prism:sensitive",      "@type": "xsd:boolean" },
    "immutable":            { "@id": "prism:immutable",      "@type": "xsd:boolean" },
    "vectorIndexed":        { "@id": "prism:vectorIndexed",  "@type": "xsd:boolean" },
    "computed":             { "@id": "prism:computed",       "@type": "xsd:boolean" },
    "columnName":           { "@id": "prism:columnName",     "@type": "xsd:string" },
    "example":              { "@id": "prism:example" },
    "dataClass":            { "@id": "prism:dataClass",      "@type": "@id" },
    "derivedFrom":          { "@id": "prism:derivedFrom",    "@type": "@id" },

    "agentAccessible":      { "@id": "prism:agentAccessible",     "@type": "xsd:boolean" },
    "agentPermission":      { "@id": "prism:agentPermission",     "@type": "@id" },
    "semanticDescription":  { "@id": "prism:semanticDescription", "@type": "xsd:string" },
    "toolName":             { "@id": "prism:toolName",            "@type": "xsd:string" },

    "backend":              { "@id": "prism:backend",             "@type": "@id" },
    "tableName":            { "@id": "prism:tableName",           "@type": "xsd:string" },
    "enableCache":          { "@id": "prism:enableCache",         "@type": "xsd:boolean" },
    "cacheTtlSeconds":      { "@id": "prism:cacheTtlSeconds",     "@type": "xsd:integer" },

    "basePath":             { "@id": "prism:basePath",            "@type": "xsd:string" },
    "allowedMethod":        { "@id": "prism:allowedMethod",       "@type": "@id" },
    "requiresAuthentication": { "@id": "prism:requiresAuthentication", "@type": "xsd:boolean" },
    "paginationDefault":    { "@id": "prism:paginationDefault",   "@type": "xsd:integer" },
    "paginationMax":        { "@id": "prism:paginationMax",       "@type": "xsd:integer" },
    "apiTag":               { "@id": "prism:apiTag",              "@type": "xsd:string" },

    "governance":           { "@id": "prism:governance",          "@type": "@id" },
    "events":               { "@id": "prism:events",              "@type": "@id" },
    "digitalTwin":          { "@id": "prism:digitalTwin",         "@type": "@id" },
    "temporal":             { "@id": "prism:temporal",            "@type": "@id" },
    "vector":               { "@id": "prism:vector",              "@type": "@id" },
    "datasource":           { "@id": "prism:datasource",          "@type": "@id" },

    "cardinality":          { "@id": "prism:cardinality",          "@type": "@id" },
    "cascade":              { "@id": "prism:cascade",              "@type": "@id" },
    "fetchStrategy":        { "@id": "prism:fetchStrategy",        "@type": "@id" },
    "joinColumn":           { "@id": "prism:joinColumn",           "@type": "xsd:string" },
    "joinTable":            { "@id": "prism:joinTable",            "@type": "xsd:string" },
    "qualityCertification": { "@id": "prism:qualityCertification", "@type": "@id" },
    "mapsTo":               { "@id": "prism:mapsTo",               "@type": "@id" },
    "mappingType":          { "@id": "prism:mappingType",          "@type": "@id" },

    "CoreModel":            "prism:CoreModel",
    "Persistence":          "prism:Persistence",
    "RestApi":              "prism:RestApi",
    "GraphQL":              "prism:GraphQL",
    "Grpc":                 "prism:Grpc",
    "AiTools":              "prism:AiTools",
    "DataFabric":           "prism:DataFabric",
    "Governance":           "prism:Governance",
    "Events":               "prism:Events",
    "DigitalTwin":          "prism:DigitalTwin",
    "Temporal":             "prism:Temporal",
    "Vector":               "prism:Vector",

    "Entity":               "prism:Entity",
    "ValueObject":          "prism:ValueObject",
    "Enumeration":          "prism:Enumeration",
    "ReadOnly":             "prism:ReadOnly",
    "ReadWrite":            "prism:ReadWrite",
    "AgentRestricted":      "prism:AgentRestricted",
    "JpaBackend":           "prism:JpaBackend",
    "MongoBackend":         "prism:MongoBackend",
    "Neo4jBackend":         "prism:Neo4jBackend",
    "Rdf4jBackend":         "prism:Rdf4jBackend",
    "DynamoBackend":        "prism:DynamoBackend",
    "Eager":                "prism:Eager",
    "Lazy":                 "prism:Lazy",
    "OneToOne":             "prism:OneToOne",
    "OneToMany":            "prism:OneToMany",
    "ManyToOne":            "prism:ManyToOne",
    "ManyToMany":           "prism:ManyToMany",
    "CascadeAll":           "prism:CascadeAll",
    "CascadePersist":       "prism:CascadePersist",
    "CascadeMerge":         "prism:CascadeMerge",
    "CascadeRemove":        "prism:CascadeRemove",
    "CascadeNone":          "prism:CascadeNone",
    "Public":               "prism:Public",
    "Internal":             "prism:Internal",
    "Confidential":         "prism:Confidential",
    "StrictlyConfidential": "prism:StrictlyConfidential",
    "PII":                  "prism:PII",
    "PHI":                  "prism:PHI",
    "PCI":                  "prism:PCI",
    "Bronze":               "prism:Bronze",
    "Silver":               "prism:Silver",
    "Gold":                 "prism:Gold",
    "Platinum":             "prism:Platinum"
  }
}
```

---

## Appendix A: Annotation Quick Reference

### Entity-Level

| Annotation | Type | Default | Level | Section |
|---|---|---|---|---|
| `prism:entityKind` | `prism:EntityKind` | `prism:Entity` | 1 | §6 |
| `prism:generateCode` | boolean | `true` | 1 | §6.1 |
| `prism:generateRepository` | boolean | `true` (Entity) | 2 | §6.1 |
| `prism:generateRestEndpoint` | boolean | `true` (Entity) | 3 | §6.1 |
| `prism:generateGraphQL` | boolean | `false` | 3 | §6.1 |
| `prism:generateGrpc` | boolean | `false` | 3 | §6.1 |
| `prism:pluralLabel` | string | label + "s" | 1 | §6.1 |
| `prism:validatedBy` | `sh:NodeShape` | — | 2 | §6.1 |
| `prism:persistence` | `prism:PersistenceConfig` | — | 2 | §6.3 |
| `prism:apiConfig` | `prism:ApiConfig` | — | 3 | §6.4 |
| `prism:sinceVersion` | string | — | 1 | §6.2 |
| `prism:deprecatedSince` | string | — | 1 | §6.2 |
| `prism:replacedBy` | IRI | — | 1 | §6.2 |
| `prism:agentAccessible` | boolean | `true` (Entity) | 4 | §12 |
| `prism:agentPermission` | `prism:AgentPermission` | `prism:ReadOnly` | 4 | §12 |
| `prism:semanticDescription` | string | — | 4 | §12.3 |
| `prism:toolName` | string | camelCase(label) | 4 | §12.4 |
| `prism:mapsTo` | IRI | — | 1 | §23 |
| `prism:mappingType` | `prism:MappingType` | — | 1 | §23 |
| `prism:datasource` | `prism:DatasourceConfig` | — | 5 | §16 |
| `prism:federated` | `prism:FederationConfig` | — | 5 | §17 |
| `prism:governance` | `prism:GovernanceConfig` | — | 6 | §18 |
| `prism:qualityCertification` | `prism:QualityCertification` | — | 6 | §18.4 |
| `prism:dataClass` | `prism:DataClass` | — | 6 | §18.3 |
| `prism:events` | `prism:EventConfig` | — | 7 | §19 |
| `prism:digitalTwin` | `prism:TwinConfig` | — | 7 | §20 |
| `prism:temporal` | `prism:TemporalConfig` | — | 7 | §21 |
| `prism:vector` | `prism:VectorConfig` | — | 8 | §22 |

### Property-Level

| Annotation | Type | Default | Level | Section |
|---|---|---|---|---|
| `prism:required` | boolean | `false` | 2 | §7.2 |
| `prism:unique` | boolean | `false` | 2 | §7.2 |
| `prism:indexed` | boolean | `false` | 2 | §7.3 |
| `prism:searchable` | boolean | `false` | 3 | §7.3 |
| `prism:sortable` | boolean | `false` | 3 | §7.3 |
| `prism:filterable` | boolean | `false` | 3 | §7.3 |
| `prism:vectorIndexed` | boolean | `false` | 8 | §7.3 |
| `prism:columnName` | string | snake_case(localname) | 2 | §7.4 |
| `prism:defaultValue` | Literal or enum member IRI | — | 2 | §7.4 |
| `prism:immutable` | boolean | `false` | 2 | §7.4 |
| `prism:sensitive` | boolean | `false` | 3 | §7.5 |
| `prism:example` | Literal | — | 3 | §7.5 |
| `prism:computed` | boolean | `false` | 5 | §7.6 |
| `prism:computedBy` | `prism:ComputedPropertyDef` | — | 5 | §7.6 |
| `prism:derivedFrom` | IRI | — | 6 | §7.6 |
| `prism:derivedUsing` | IRI | — | 6 | §7.6 |
| `prism:dataClass` | `prism:DataClass` | — | 6 | §7.7 |
| `prism:unit` | IRI (QUDT unit) | — | 3 | §7.9 |
| `prism:quantityKind` | IRI (QUDT QuantityKind) | — | 3 | §7.9 |
| `prism:unitProperty` | IRI (sibling property) | — | 3 | §7.9 |

> **Note on `prism:derivedFrom` level:** `prism:derivedFrom` is a Level 6 (Governance) annotation. Processors at Level 5 or below SHOULD NOT attempt to build lineage graphs from it.

> **Note:** Constraint annotations (`sh:pattern`, `sh:minLength`, `sh:maxLength`, `sh:minInclusive`, `sh:maxInclusive`) are authored directly in co-input SHACL shapes linked via `prism:validatedBy`. They are not Prism annotations.

### Relationship-Level

| Annotation | Type | Default | Section |
|---|---|---|---|
| `prism:cardinality` | `prism:Cardinality` | `prism:ManyToOne` | §8 |
| `prism:cascade` | `prism:CascadeType` | `prism:CascadeNone` | §8 |
| `prism:fetchStrategy` | `prism:FetchStrategy` | `prism:Lazy` | §8 |
| `prism:joinColumn` | string | derived | §8 |
| `prism:joinTable` | string | — | §8 |
| `prism:edgeProperties` | `prism:EdgeProperties` | — | §8.4 |

---

## Appendix B: Persistence Backend Reference

| Backend | `prism:backend` value | Entity maps to | Property maps to | Relationship maps to |
|---|---|---|---|---|
| JPA / PostgreSQL | `prism:JpaBackend` | `@Entity` class + SQL table | `@Column` | `@OneToMany`, `@ManyToOne`, `@JoinColumn` |
| MongoDB | `prism:MongoBackend` | `@Document` class | BSON field | embedded document or `@DBRef` |
| Neo4j | `prism:Neo4jBackend` | `@Node` class | node property | `@Relationship` |
| RDF4J | `prism:Rdf4jBackend` | `owl:Class` in named graph | `owl:DatatypeProperty` triple | `owl:ObjectProperty` triple |
| DynamoDB | `prism:DynamoBackend` | DynamoDB table with PK/SK | item attribute | denormalised or GSI |

---

## Appendix C: Comparison with Alternatives

| Feature | **Prism 1.1** | **Palantir Foundry** | **TopQuadrant EDG** | JHipster JDL | OpenAPI 3.1 | Prisma Schema |
|---|---|---|---|---|---|---|
| **Standard** | W3C (OWL/RDF/SHACL) | Proprietary | SHACL + SKOS | Proprietary | OpenAPI consortium | Proprietary |
| **Portable** | Yes — Turtle is vendor-neutral | No — Foundry only | Partial — SHACL portable, platform tooling not | No | Partial | No |
| **Open source** | Yes (CC BY 4.0) | No | No | Yes (Apache 2.0) | Yes | Yes (Apache 2.0) |
| **Self-hosted** | Yes | Enterprise only | Enterprise only | Yes | N/A | Yes |
| **Price** | Free | $1M+/year | $200K+/year | Free | Free | Free |
| **Graph-native** | Yes — RDF is a graph model | Partial | Yes (SHACL/RDF) | No | No | No |
| **Level 1: Code generation** | Yes — multi-language | Yes (OSDK: TS/Python/Java) | No | Yes (JPA only) | No | Yes (Prisma) |
| **Level 2: Persistence mapping** | Yes — 5 backends | Foundry-internal only | Partial | JPA only | No | Prisma DBs only |
| **Level 2: SHACL validation** | Yes — co-input + generated | No | Yes (primary focus) | No | Partial (JSON Schema) | No |
| **Level 3: REST + OpenAPI** | Yes | Yes | Limited | Yes | Spec only | No |
| **Level 3: GraphQL** | Yes | No | No | Yes | No | No |
| **Level 3: gRPC** | Yes | No | No | No | No | No |
| **Level 4: AI agent generation** | Yes — tool defs + system prompts | Yes (AIP) | No | No | No | No |
| **Level 4: Semantic descriptions for LLMs** | Yes — `prism:semanticDescription` | Proprietary | No | No | `description` only | No |
| **Level 5: Live source connectors** | Yes — `prism:DatasourceConfig` | Yes (Phonograph) | No | No | No | No |
| **Level 5: Computed properties** | Yes — SPARQL or function ref | Yes (Foundry Functions) | No | No | No | No |
| **Level 5: Cross-system federation** | Yes — 4 protocols | Proprietary | Partial (SPARQL) | No | No | No |
| **Level 6: Data governance workflows** | Yes — approval, retention, stewardship | Partial | Yes (primary focus) | No | No | No |
| **Level 6: Data quality certification** | Yes — Bronze/Silver/Gold/Platinum tiers | Partial | Yes | No | No | No |
| **Level 6: Data lineage** | Yes — `prism:derivedFrom` | Yes | Yes | No | No | No |
| **Level 7: Event schema generation** | Yes — Avro/Protobuf/CloudEvents | No | No | No | No | No |
| **Level 7: Digital twins** | Yes — RDF shadow graph + state machine | No | No | No | No | No |
| **Level 7: Bitemporal versioning** | Yes — valid-time + transaction-time | Partial (Foundry timeline) | No | No | No | No |
| **Level 8: Vector embedding index** | Yes — 6 backends + pipeline | No | No | No | No | No |
| **Level 8: RAG-grounded agents** | Yes — semantic search in agent tools | No | No | No | No | No |
| **External ontology mapping** | Yes — `prism:mapsTo` | No | Yes (SKOS mapping) | No | No | No |
| **Schema migrations** | Yes — SPARQL UPDATE | Proprietary | Proprietary | Liquibase | No | Prisma Migrate |
| **Edge properties** | Yes — `prism:EdgeProperties` | Yes (Link Types) | Partial | No | No | No |

**Summary of decisive advantages over Palantir Foundry Ontology:**
1. Open standard (CC BY 4.0) vs. $1M+ proprietary platform
2. Multi-language, multi-processor, self-hostable vs. Foundry lock-in
3. Level 7–8 (event schemas, digital twins, bitemporal, vector-native) — capabilities Foundry does not provide
4. SHACL as first-class constraint language vs. Foundry's proprietary validation

**Summary of decisive advantages over TopQuadrant EDG:**
1. Full code and API generation (Levels 1–3) — EDG is governance tooling, not a code generator
2. AI-native (Level 4) agent generation — not available in EDG
3. Level 5–8 capabilities entirely absent from EDG
4. Open standard and self-hostable vs. expensive enterprise licensing

> **Note on the comparison table.** The "Prism 1.1" column above describes capabilities the *specification* defines, not capabilities any single processor currently implements. No known processor is conformant at Level 3 or above today. See Appendix D for the conformance status of the reference implementation.

---

## Appendix D: Reference Implementations and Conformance Status

This appendix records the conformance status of known Prism processors. It is informative, not normative. Conformance is self-declared by each implementation against the requirements of Section 3 and the level definitions of Section 27.

### D.1 `kastor-gen` (Kastor)

`kastor-gen` is a Kotlin code generator in the [Kastor](https://github.com/geoknoesis/kastor) project. It generates domain interfaces, RDF-backed wrapper classes, and immutable data-class snapshots from a domain model.

**Conformance status: not yet a conforming Prism processor (Section 3).** `kastor-gen` partially covers Level 1 (Core) through a *SHACL-first profile* and consumes co-input SHACL, but it does not yet read the OWL + `prism:` annotation layer that this specification is built on.

**Input contract.** `kastor-gen` is driven by a **SHACL shapes graph plus a JSON-LD context**, supplied as file paths via the `@Rdf(shacl = …, context = …)` annotation or the Gradle ontology-generation task. It does **not** consume an OWL ontology carrying `prism:` annotations, and it does not resolve `owl:imports`. The unit of generation is one Kotlin type per `sh:NodeShape` that declares a `sh:targetClass`; an OWL class with no corresponding shape produces no output.

**What it covers (Level 1, partial):**

| Level 1 requirement (§27) | Status in `kastor-gen` | Notes |
|---|---|---|
| Language entity classes for `prism:Entity` / `prism:ValueObject` | ⚠ Partial — different basis | One interface (+ wrapper / data-class) per `sh:NodeShape`, keyed on `sh:targetClass`, not on `prism:entityKind`. No Entity/ValueObject distinction. |
| Type-safe accessors reflecting datatype ranges | ✅ Yes — from SHACL | Types derived from `sh:datatype` (not OWL `rdfs:range`); object vs. literal decided by presence of `sh:class`. |
| Null-safety reflecting `prism:required` | ✅ Yes — from SHACL | Cardinality from `sh:minCount`/`sh:maxCount` (`maxCount > 1` ⇒ `List<T>`, `minCount 0` ⇒ `T?`). |
| Enumeration types for `prism:Enumeration` classes | ❌ No | No `owl:NamedIndividual`/`prism:Enumeration` handling; no Kotlin `enum class` is emitted. Enumeration-ranged properties fall through to `String` (`sh:in` is parsed but used only for runtime membership checks). |

**Co-input SHACL (Level 2, partial):** `kastor-gen` consumes SHACL as primary input, but locates it by configured file path rather than via `prism:validatedBy`, and embedded runtime validation enforces only `sh:minCount`/`sh:maxCount` — `sh:pattern`, length, numeric bounds, and `sh:in` are parsed but not enforced inline (full enforcement requires delegating to an external SHACL engine).

**Not implemented:** repositories/DAOs and persistence mapping (Level 2 co-output shapes, JPA/Mongo/Neo4j/RDF4J/Dynamo, migrations, indexes); REST/OpenAPI/GraphQL/gRPC (Level 3); AI agent tooling (Level 4); and all of Levels 5–8.

**Beyond the spec:** `kastor-gen` additionally generates an RDF write-back path (`toTriples` / `writeToGraph` over a CBD closure) and a type-safe instance DSL — capabilities not described by this specification.

### D.2 Path to Level 1 conformance

To claim Level 1 (Core) conformance, a processor in `kastor-gen`'s position would need to:

1. Read OWL classes (not only `sh:NodeShape`s) and honor `prism:entityKind` (defaulting absent values to `prism:Entity`).
2. Generate language-level enumerations from `prism:Enumeration` classes and their `owl:NamedIndividual` members, and type enumeration-ranged properties to those enums.
3. Resolve co-input shapes via `prism:validatedBy` (with transitive `owl:imports` resolution per §26.4), rather than a single configured path.
4. Reject ontologies that declare zero or more than one `prism:conformanceLevel` (§5.1).

### D.3 Reporting an implementation

Implementers MAY request inclusion in this appendix by opening an issue against the specification repository with a self-assessment against the Section 27 level requirements.

---

*Prism 1.1 Working Draft — 2026-05-25*  
*Prism Working Group*  
*https://prism.dev*
