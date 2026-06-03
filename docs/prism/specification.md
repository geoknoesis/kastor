# Prism Specification
## Version 1.0 — Working Draft

**Namespace:** `https://spec.prism.dev/1.0#`  
**Prefix:** `prism:`  
**Vocabulary:** [prism/vocab/prism.ttl](../../prism/vocab/prism.ttl)  
**License:** [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)  
**Status:** Working Draft — 2026-05-25

---

## Abstract

**Prism** is an annotation vocabulary layered on top of OWL 2 DL that enables ontology authors to declare how domain classes, properties, and relationships should be materialised as code, APIs, persistence schemas, validation rules, and AI agent tools — from a single source-of-truth domain model written in standard RDF/Turtle.

Prism does not replace OWL 2, SHACL, or SPARQL. It extends them with generation metadata so that a conforming Prism processor can derive all downstream artefacts from the ontology alone, keeping every layer semantically consistent by construction.

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
11. [Validation: SHACL Integration](#11-validation-shacl-integration)
12. [AI Agent Annotations](#12-ai-agent-annotations)
13. [Action Types](#13-action-types)
14. [Query Types](#14-query-types)
15. [Interoperability Mappings](#15-interoperability-mappings)
16. [Ontology Metadata and Versioning](#16-ontology-metadata-and-versioning)
17. [Schema Migrations](#17-schema-migrations)
18. [Serialization Formats](#18-serialization-formats)
19. [Conformance Levels](#19-conformance-levels)
20. [Complete Example: Trade Domain](#20-complete-example-trade-domain)
21. [JSON-LD Context](#21-json-ld-context)
22. [Appendix A: Annotation Quick Reference](#appendix-a-annotation-quick-reference)
23. [Appendix B: Persistence Backend Reference](#appendix-b-persistence-backend-reference)
24. [Appendix C: Comparison with Alternatives](#appendix-c-comparison-with-alternatives)

---

## 1. Introduction

### 1.1 The Problem

Every significant software system maintains the same domain knowledge in multiple places: entity classes in code, table definitions in migrations, DTO schemas in API specs, constraint logic in validators, and system prompts for AI agents. These representations drift apart. A field added to the database is missing from the API schema; a constraint enforced in the API is absent from the database; an AI agent hallucinates field names that were renamed three months ago.

The root cause is the same in every case: **no single source of truth for the domain model**.

### 1.2 How Prism Solves It

Prism makes the domain ontology the canonical source of truth. You define your domain once in OWL 2 DL with Prism annotations, and a conforming Prism processor derives:

| Artefact | Derived from |
|---|---|
| Language entity classes (Kotlin, TypeScript, Python, Java) | `owl:Class` + entity-level annotations |
| Repository/DAO interfaces | `prism:generateRepository true` |
| REST controller + OpenAPI spec | `prism:ApiConfig` |
| GraphQL schema + resolvers | `prism:generateGraphQL true` |
| JPA entities / SQL DDL | `prism:PersistenceConfig` with `prism:JpaBackend` |
| SHACL validation shapes | Property annotations (`prism:required`, `prism:pattern`, etc.) |
| AI agent tool definitions | `prism:agentAccessible`, `prism:semanticDescription` |
| Agent-grounded system prompts | Full ontology serialisation as JSON-LD context |
| Test fixtures | `prism:example` values on properties |
| Schema migration scripts | `prism:Migration` instances |

Every derived artefact is semantically consistent with every other because they all come from the same graph.

### 1.3 Relationship to W3C Standards

Prism is built entirely on W3C standards. It introduces no new data model — only new annotation properties layered on top of OWL 2 DL and SHACL.

| Standard | Role in Prism |
|---|---|
| **OWL 2 DL** | Domain model language (classes, properties, restrictions) |
| **RDF 1.1** | Graph data model; Turtle as the primary serialisation |
| **SPARQL 1.1** | Query language for Prism processors and named Query types |
| **SHACL** | Constraint language; Prism property annotations generate SHACL shapes |
| **JSON-LD 1.1** | Alternative serialisation; official Prism JSON-LD context provided |
| **Dublin Core Terms** | Ontology metadata (`dcterms:created`, `dcterms:creator`, etc.) |
| **SKOS** | Definitional clarity (`skos:definition`, `skos:example`) |
| **VANN** | Namespace declarations (`vann:preferredNamespacePrefix`) |

### 1.4 What Prism Is Not

- **Not a code generator.** Prism is the specification of what should be generated and how. Conforming Prism processors implement the generation logic.
- **Not a runtime framework.** Generated code has no dependency on Prism at runtime.
- **Not a replacement for OWL or SHACL.** Prism extends them; it does not replace them.
- **Not proprietary.** Prism is a CC BY 4.0 open standard with a W3C-compatible vocabulary.

---

## 2. Design Principles

### P1: Standard-First
Every Prism construct maps to a W3C standard. Prism annotations are `owl:AnnotationProperty` instances — standard OWL. A document conforming to Prism 1.0 is also a valid OWL 2 DL ontology.

### P2: Additive
Prism annotations are purely additive. An OWL ontology without any Prism annotations is a valid OWL ontology. Adding Prism annotations does not alter its OWL semantics.

### P3: Convention Over Configuration
Every Prism annotation has a sensible default. For a class with no Prism annotations at all, a processor applying Prism conventions generates a primary entity with a repository, REST endpoint, and SHACL shape. Annotations are needed only to override defaults.

### P4: Single Source of Truth
The ontology is the domain model. All generated artefacts are derived, never edited directly. If you want to change the API, change the ontology.

### P5: Portability
An Prism-annotated ontology is portable. No vendor-specific format, no SDK dependency. Any conforming processor can consume it. Export your ontology as Turtle, move to a different processor, and regenerate.

### P6: Graph-Native
Prism treats the domain model as a knowledge graph, not a relational schema. Graph-native backends (RDF4J, Neo4j) are first-class persistence targets. Relational mapping is a projection, not the canonical form.

### P7: AI-Ready
Every Prism entity and property carries machine-readable semantic descriptions (`prism:semanticDescription`) designed for direct inclusion in LLM system prompts. AI agent tool definitions are derived from the same annotations as REST endpoints.

---

## 3. Conformance

A document is a **conformant Prism ontology** if:

1. It is a valid RDF 1.1 document in Turtle, JSON-LD, N-Triples, or RDF/XML serialisation.
2. It imports or references the Prism vocabulary (`https://spec.prism.dev/1.0`).
3. All Prism annotation properties are used with values of the correct type as specified in this document.
4. It satisfies the conformance level it declares (see Section 19).

A software system is a **conformant Prism processor** if it:

1. Accepts a conformant Prism ontology as input.
2. Correctly derives all artefacts mandated by the ontology's declared conformance level.
3. Applies all defaults specified in this document where annotations are absent.
4. Reports an error for any annotation value that violates its type constraint.

The key words MUST, MUST NOT, SHOULD, SHOULD NOT, and MAY in this document are to be interpreted as described in [RFC 2119](https://www.rfc-editor.org/rfc/rfc2119).

---

## 4. Terminology

**Domain Model** — The complete set of classes, properties, and relationships that describe a business domain.

**Entity** — An OWL class annotated with `prism:entityKind prism:Entity`. Has its own identity, lifecycle, and repository. The primary unit of API and persistence generation.

**Value Object** — An OWL class annotated with `prism:entityKind prism:ValueObject`. Has no independent identity; persisted as a component of its owning entity.

**Enumeration** — An OWL class annotated with `prism:entityKind prism:Enumeration`. Instances are OWL named individuals. Generates a language-level enum.

**Action** — An instance of `prism:Action`. A named, typed operation. Generates an API endpoint, a service method, and an AI agent tool.

**Query** — An instance of `prism:Query`. A named SPARQL SELECT. Generates a read-only API endpoint and an agent tool.

**Prism Processor** — A software system that reads a conformant Prism ontology and derives one or more artefacts from it.

**Persistence Backend** — The target data store for generated persistence code. One of: JPA/relational, MongoDB, Neo4j, RDF4J triplestore, DynamoDB.

---

## 5. Core OWL Profile

Prism ontologies MUST use the OWL 2 DL profile. OWL Full constructs (e.g., classes as instances without explicit named individual declaration) are not supported by Prism processors.

### 5.1 Required Ontology Header

Every Prism ontology file MUST declare:

```turtle
@prefix prism:     <https://spec.prism.dev/1.0#> .
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

`prism:conformanceLevel` declares the highest conformance level the ontology targets (see Section 19).

### 5.2 Entity Class Declaration

```turtle
ex:Shipment
    a owl:Class ;
    rdfs:label "Shipment"@en ;
    rdfs:comment "A consignment of goods transported under a trade transaction."@en ;
    prism:entityKind prism:Entity ;
    prism:pluralLabel "Shipments"@en ;
    prism:generateCode true ;
    prism:generateRepository true ;
    prism:generateRestEndpoint true .
```

If `prism:entityKind` is absent, processors MUST treat the class as `prism:Entity`.

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
ex:IncotermsCode
    a owl:Class ;
    rdfs:label "Incoterms Code"@en ;
    prism:entityKind prism:Enumeration .

ex:EXW a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "EXW"@en .
ex:FOB a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "FOB"@en .
ex:CIF a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "CIF"@en .
ex:DDP a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "DDP"@en .
```

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
| `prism:pluralLabel` | `xsd:string` | `rdfs:label + "s"` | Plural name for URL paths and collections |

### 6.2 Versioning Annotations

```turtle
ex:Container
    a owl:Class ;
    prism:sinceVersion "1.0.0" .

ex:LegacyWaybill
    a owl:Class ;
    owl:deprecated true ;
    prism:deprecatedSince "1.2.0" ;
    prism:replacedBy ex:BillOfLading .
```

### 6.3 Persistence Link

```turtle
ex:Shipment
    a owl:Class ;
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:JpaBackend ;
        prism:tableName "shipments" ;
        prism:enableCache true ;
        prism:cacheTtlSeconds 300
    ] .
```

### 6.4 API Config Link

```turtle
ex:Shipment
    a owl:Class ;
    prism:apiConfig [
        a prism:ApiConfig ;
        prism:basePath "/api/v1/shipments" ;
        prism:allowedMethod prism:GET, prism:POST, prism:PATCH ;
        prism:requiresAuthentication true ;
        prism:paginationDefault 25 ;
        prism:paginationMax 200 ;
        prism:apiTag "Shipments"
    ] .
```

---

## 7. Property Annotations

Prism property annotations apply to both `owl:DatatypeProperty` and `owl:ObjectProperty` instances.

### 7.1 Constraint Annotations

These annotations generate corresponding SHACL property shapes on the owning entity's `sh:NodeShape` (see Section 11).

| Annotation | Type | SHACL Equivalent | Description |
|---|---|---|---|
| `prism:required` | `xsd:boolean` | `sh:minCount 1` | Property must be present |
| `prism:unique` | `xsd:boolean` | `sh:uniqueLang` or DB unique index | Value must be unique across instances |
| `prism:pattern` | `xsd:string` | `sh:pattern` | Regex the value must match |
| `prism:minValue` | `rdfs:Literal` | `sh:minInclusive` | Minimum numeric/date value |
| `prism:maxValue` | `rdfs:Literal` | `sh:maxInclusive` | Maximum numeric/date value |
| `prism:minLength` | `xsd:integer` | `sh:minLength` | Minimum string length |
| `prism:maxLength` | `xsd:integer` | `sh:maxLength` | Maximum string length |

### 7.2 Index and Search Annotations

| Annotation | Type | Default | Description |
|---|---|---|---|
| `prism:indexed` | `xsd:boolean` | `false` | Create a database index |
| `prism:searchable` | `xsd:boolean` | `false` | Include in full-text search |
| `prism:sortable` | `xsd:boolean` | `false` | Expose as API sort key |
| `prism:filterable` | `xsd:boolean` | `false` | Expose as API filter parameter |

### 7.3 Persistence Annotations

| Annotation | Type | Description |
|---|---|---|
| `prism:columnName` | `xsd:string` | Override column name (default: snake_case of local name) |
| `prism:defaultValue` | `rdfs:Literal` | Default value when not supplied |
| `prism:immutable` | `xsd:boolean` | Set at creation, never updated |

### 7.4 Safety and Documentation Annotations

| Annotation | Type | Description |
|---|---|---|
| `prism:sensitive` | `xsd:boolean` | PII — omit from logs, search, default serialisation |
| `prism:example` | `rdfs:Literal` | Example value for OpenAPI docs and test fixtures |

### 7.5 Full Property Example

```turtle
ex:shipmentId
    a owl:DatatypeProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range xsd:string ;
    rdfs:label "Shipment ID"@en ;
    prism:required true ;
    prism:unique true ;
    prism:immutable true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:sortable true ;
    prism:pattern "[A-Z]{2}[0-9]{8}" ;
    prism:maxLength 12 ;
    prism:example "SH20260001" ;
    prism:columnName "shipment_id" .

ex:declaredValue
    a owl:DatatypeProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range xsd:decimal ;
    rdfs:label "Declared Value"@en ;
    prism:required true ;
    prism:minValue "0.01"^^xsd:decimal ;
    prism:maxValue "999999999.99"^^xsd:decimal ;
    prism:filterable true ;
    prism:sortable true .

ex:buyerEmail
    a owl:DatatypeProperty ;
    rdfs:domain ex:Party ;
    rdfs:range xsd:string ;
    rdfs:label "Buyer Email"@en ;
    prism:sensitive true ;
    prism:pattern "^[^@]+@[^@]+\\.[^@]+$" ;
    prism:maxLength 254 .
```

---

## 8. Relationship Annotations

Relationship annotations apply to `owl:ObjectProperty` declarations.

### 8.1 Cardinality

```turtle
ex:hasContainer
    a owl:ObjectProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range ex:Container ;
    rdfs:label "has container"@en ;
    prism:cardinality prism:OneToMany ;
    prism:cascade prism:CascadeAll ;
    prism:fetchStrategy prism:Lazy .

ex:belongsToTransaction
    a owl:ObjectProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range ex:TradeTransaction ;
    rdfs:label "belongs to transaction"@en ;
    prism:cardinality prism:ManyToOne ;
    prism:cascade prism:CascadeNone ;
    prism:fetchStrategy prism:Lazy ;
    prism:joinColumn "transaction_id" ;
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

---

## 9. Persistence Configuration

### 9.1 Persistence Config Block

A `prism:PersistenceConfig` is a blank node or named individual linked from a class via `prism:persistence`.

```turtle
ex:Shipment
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:JpaBackend ;
        prism:tableName "shipments" ;
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

### 9.3 Multi-Backend Declaration

An entity MAY declare multiple persistence configs with different backends. Processors MUST generate code for each declared backend and SHOULD generate an interface abstraction over them.

```turtle
ex:AuditEvent
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:Rdf4jBackend
    ] , [
        a prism:PersistenceConfig ;
        prism:backend prism:MongoBackend ;
        prism:tableName "audit_events"
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
ex:Shipment
    prism:apiConfig [
        a prism:ApiConfig ;
        prism:basePath "/api/v1/shipments" ;
        prism:allowedMethod prism:GET, prism:POST, prism:PATCH ;
        prism:requiresAuthentication true ;
        prism:paginationDefault 25 ;
        prism:paginationMax 200 ;
        prism:apiTag "Shipments"
    ] .
```

### 10.3 Generated Endpoints

For a `prism:ApiConfig` with `prism:basePath "/api/v1/shipments"`, processors MUST generate:

| Method | Path | Operation |
|---|---|---|
| GET | `/api/v1/shipments` | List with pagination, filter, sort |
| POST | `/api/v1/shipments` | Create |
| GET | `/api/v1/shipments/{id}` | Get by ID |
| PUT | `/api/v1/shipments/{id}` | Full update |
| PATCH | `/api/v1/shipments/{id}` | Partial update |
| DELETE | `/api/v1/shipments/{id}` | Delete |

Methods NOT listed in `prism:allowedMethod` MUST NOT be generated.

### 10.4 OpenAPI Specification Generation

Conforming Level 3 processors MUST generate a valid OpenAPI 3.1 specification from Prism annotations:

- Entity `rdfs:comment` → operation `description`
- `prism:apiTag` → OpenAPI `tags`
- `prism:example` on properties → OpenAPI `example`
- `prism:pattern`, `prism:minLength`, `prism:maxLength` → OpenAPI `pattern`, `minLength`, `maxLength`
- `prism:sensitive true` → mark property with `x-sensitive: true`; SHOULD be omitted from response schemas

### 10.5 GraphQL Generation

When `prism:generateGraphQL true`:

- Each `prism:Entity` becomes a GraphQL `type`
- Each `prism:ValueObject` becomes a GraphQL `input` and embedded `type`
- Each `prism:Enumeration` becomes a GraphQL `enum`
- `prism:required true` on a property generates a non-null (`!`) field
- Relationships generate nested type references and corresponding resolvers

---

## 11. Validation: SHACL Integration

### 11.1 Automatic Shape Generation

A conforming Level 2+ processor MUST generate a `sh:NodeShape` for every `prism:Entity` class. Property annotations are the source of SHACL constraint generation.

```turtle
# Prism source
ex:shipmentId
    a owl:DatatypeProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range xsd:string ;
    prism:required true ;
    prism:unique true ;
    prism:pattern "[A-Z]{2}[0-9]{8}" ;
    prism:maxLength 12 .

# Generated SHACL (processor output — not authored manually)
ex:ShipmentShape
    a sh:NodeShape ;
    sh:targetClass ex:Shipment ;
    sh:property [
        sh:path ex:shipmentId ;
        sh:minCount 1 ;
        sh:maxCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "[A-Z]{2}[0-9]{8}" ;
        sh:maxLength 12
    ] .
```

### 11.2 Authoring Custom SHACL

Authors MAY write additional SHACL shapes beyond what Prism generates. Custom shapes MUST NOT conflict with generated shapes. Processors MUST merge custom and generated shapes before validation.

```turtle
# Cross-property custom constraint (not expressible via single-property Prism annotations)
ex:ShipmentValueConsistencyShape
    a sh:NodeShape ;
    sh:targetClass ex:Shipment ;
    sh:sparql [
        sh:message "Declared customs value must not exceed invoice total." ;
        sh:severity sh:Warning ;
        sh:select """
            SELECT $this WHERE {
                $this ex:customsDeclaredValue ?cv ;
                      ex:invoiceTotal ?it .
                FILTER (?cv > ?it)
            }
        """
    ] .
```

### 11.3 Severity Mapping

Prism property annotations generate `sh:Violation` severity by default. Authors MAY override severity on custom SHACL shapes using `sh:Warning` or `sh:Info`.

### 11.4 Runtime Validation Integration

Generated service and API layers MUST invoke SHACL validation before persisting any entity. Violations MUST be returned as structured error responses, not runtime exceptions.

---

## 12. AI Agent Annotations

### 12.1 Agent Accessibility

```turtle
ex:Shipment
    prism:agentAccessible true ;
    prism:agentPermission prism:ReadOnly ;
    prism:semanticDescription """A Shipment represents a consignment of goods moving from an exporter to an importer. It is the central node of the trade graph. All documents (invoices, bills of lading, customs declarations), all shipment events, and all trade parties connect to a Shipment via named relationships. When reasoning about a trade operation, always anchor your analysis to the Shipment entity first.""" ;
    prism:toolName "getShipment" .
```

### 12.2 Agent Permission Values

| Value | Meaning |
|---|---|
| `prism:ReadOnly` | Agent may query and read; mutations are blocked |
| `prism:ReadWrite` | Agent may query and invoke approved `prism:Action` types |
| `prism:Restricted` | Entity is hidden from agents entirely |

Default: `prism:ReadOnly` for all `prism:Entity` classes.

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
ex:BookShipmentAction
    a prism:Action ;
    rdfs:label "Book Shipment"@en ;
    rdfs:comment "Creates a new shipment booking and sends instructions to the carrier."@en ;
    prism:actionKind prism:CreateAction ;
    prism:targetClass ex:Shipment ;
    prism:inputShape ex:BookShipmentInputShape ;
    prism:outputClass ex:Shipment ;
    prism:requiredRole "ROLE_FREIGHT_FORWARDER" ;
    prism:sideEffect "Triggers a booking confirmation email to the exporter and carrier." .

ex:BookShipmentInputShape
    a sh:NodeShape ;
    sh:property [
        sh:path ex:originLocode ;
        sh:minCount 1 ;
        sh:datatype xsd:string
    ] ;
    sh:property [
        sh:path ex:destinationLocode ;
        sh:minCount 1 ;
        sh:datatype xsd:string
    ] ;
    sh:property [
        sh:path ex:incoterms ;
        sh:minCount 1
    ] .
```

### 13.2 Generated API Endpoint

For the above action, a processor generates:

```
POST /api/v1/shipments/actions/book-shipment
Authorization: Bearer {token with ROLE_FREIGHT_FORWARDER}
Content-Type: application/json

Body: validated against ex:BookShipmentInputShape
Response: 201 Created, ex:Shipment serialised as JSON-LD
```

### 13.3 Action Kind Values

| Value | Generated HTTP method | Typical use |
|---|---|---|
| `prism:CreateAction` | POST | Create a new entity with domain logic |
| `prism:UpdateAction` | PATCH | State transition, business-rule update |
| `prism:DeleteAction` | DELETE | Soft-delete, archive, cancel |
| `prism:CustomAction` | POST | Domain operation (approve, submit, release) |

---

## 14. Query Types

Queries are named SPARQL SELECT statements exposed as read-only API endpoints and agent tools.

### 14.1 Query Declaration

```turtle
ex:ShipmentsAtRiskQuery
    a prism:Query ;
    rdfs:label "Shipments at Risk"@en ;
    rdfs:comment "Returns shipments with open compliance findings of BLOCKED severity."@en ;
    prism:outputClass ex:Shipment ;
    prism:sparqlQuery """
        SELECT ?shipment ?shipmentId ?findingCode WHERE {
            ?shipment a ex:Shipment ;
                      ex:shipmentId ?shipmentId .
            ?finding ex:affectsShipment ?shipment ;
                     ex:severity ex:BLOCKED ;
                     ex:findingCode ?findingCode ;
                     ex:resolved false .
        }
        ORDER BY ?shipmentId
    """ ;
    prism:queryParam [
        a prism:QueryParameter ;
        prism:paramName "severity" ;
        prism:paramType "xsd:string" ;
        prism:paramRequired false
    ] .
```

### 14.2 Generated Endpoint

```
GET /api/v1/queries/shipments-at-risk?severity=BLOCKED
Authorization: Bearer {token}
Response: 200 OK, array of ex:Shipment JSON-LD
```

---

## 15. Interoperability Mappings

Prism ontologies SHOULD declare alignment with external ontologies to enable semantic interoperability across systems.

### 15.1 Class Mapping

```turtle
ex:Shipment
    prism:mapsTo <https://www.gs1.org/voc/Shipment> ;
    prism:mappingType prism:ExactMatch .

ex:Party
    prism:mapsTo <http://schema.org/Organization> ;
    prism:mappingType prism:NearMatch .
```

### 15.2 Property Mapping

```turtle
ex:shipmentId
    prism:mapsTo <https://www.gs1.org/voc/globalShipmentIdentificationNumber> ;
    prism:mappingType prism:NearMatch .
```

### 15.3 Supported External Standards

Prism-conformant ontologies SHOULD map to relevant industry standards:

| Domain | Standard | Namespace |
|---|---|---|
| Trade & logistics | GS1 | `https://www.gs1.org/voc/` |
| Organisations | schema.org | `http://schema.org/` |
| Locations | UN/LOCODE | `https://schema.tradeweave.ai/location#` |
| Healthcare | HL7 FHIR | `http://hl7.org/fhir/` |
| Finance | GLEIF LEI | `https://www.gleif.org/ontology/` |
| Customs & tariffs | WCO HS | `https://schema.tradeweave.ai/tariff#` |

### 15.4 `owl:equivalentClass` vs `prism:mapsTo`

Use `owl:equivalentClass` only when the mapping is logically exact and you are prepared for OWL reasoners to treat the classes as identical. Use `prism:mapsTo` with `prism:ExactMatch` for all other cases — it documents the alignment without asserting logical equivalence.

---

## 16. Ontology Metadata and Versioning

### 16.1 Required Metadata

Every Prism ontology MUST declare:

```turtle
<https://example.com/ontologies/trade>
    a owl:Ontology ;
    rdfs:label "Trade Domain Ontology"@en ;
    dcterms:created "2026-05-25"^^xsd:date ;
    dcterms:modified "2026-05-25"^^xsd:date ;
    owl:versionIRI <https://example.com/ontologies/trade/1.0.0> ;
    owl:versionInfo "1.0.0" ;
    prism:conformanceLevel prism:Level3Api .
```

### 16.2 Recommended Metadata

```turtle
<https://example.com/ontologies/trade>
    dcterms:title "Trade Domain Ontology"@en ;
    dcterms:description "..."@en ;
    dcterms:creator "Acme Corp" ;
    dcterms:license <https://creativecommons.org/licenses/by/4.0/> ;
    vann:preferredNamespacePrefix "trade" ;
    vann:preferredNamespaceUri "https://example.com/ontologies/trade#" ;
    owl:imports <https://spec.prism.dev/1.0> .
```

### 16.3 Semantic Versioning

Prism ontologies MUST use [Semantic Versioning 2.0](https://semver.org/):

- **MAJOR** — breaking changes: class removal, property removal, type change, cardinality restriction tightening
- **MINOR** — backwards-compatible additions: new classes, new optional properties, new relationships
- **PATCH** — non-semantic changes: label corrections, comment updates, example additions

A change is **breaking** if it would invalidate existing persisted data or require changes to generated code.

---

## 17. Schema Migrations

Prism provides a standard way to declare schema migrations as part of the ontology. Migrations are `prism:Migration` instances associated with the ontology.

### 17.1 Migration Declaration

```turtle
ex:Migration_1_0_to_1_1
    a prism:Migration ;
    rdfs:label "Rename consigneeId to importerId"@en ;
    prism:fromVersion "1.0.0" ;
    prism:toVersion "1.1.0" ;
    prism:isBreaking true ;
    prism:migrationScript """
        DELETE { ?s ex:consigneeId ?o }
        INSERT { ?s ex:importerId ?o }
        WHERE  { ?s ex:consigneeId ?o }
    """ .
```

### 17.2 Migration Ordering

Processors MUST execute migrations in ascending version order. Migrations MUST be idempotent — running a migration twice MUST produce the same result as running it once.

### 17.3 Breaking Migration Protocol

When `prism:isBreaking true`, processors MUST:

1. Warn the operator before executing
2. Create a snapshot of affected data
3. Execute the migration
4. Validate the migrated data against the new SHACL shapes
5. Report success or rollback to the snapshot on failure

---

## 18. Serialization Formats

### 18.1 Primary Format

**Turtle** (`.ttl`) is the primary and recommended serialisation format for Prism ontologies. It is human-readable, compact, and widely supported.

### 18.2 Supported Formats

| Format | Media Type | Status |
|---|---|---|
| Turtle | `text/turtle` | **Primary** (recommended) |
| JSON-LD 1.1 | `application/ld+json` | Supported; Prism JSON-LD context provided |
| N-Triples | `application/n-triples` | Supported (tooling interop) |
| RDF/XML | `application/rdf+xml` | Supported (legacy interop) |

### 18.3 Multi-File Ontologies

Large domain models SHOULD be split into multiple files using `owl:imports`. Each file MUST be a valid standalone RDF document. Processors MUST resolve `owl:imports` before generating artefacts.

```turtle
<https://example.com/ontologies/trade>
    owl:imports <https://example.com/ontologies/trade/shipment> ;
    owl:imports <https://example.com/ontologies/trade/party> ;
    owl:imports <https://example.com/ontologies/trade/document> .
```

---

## 19. Conformance Levels

Prism defines four cumulative conformance levels. Each level extends the previous.

### Level 1: Core

Minimum viable conformance. Processor generates language entity classes only.

**Processor MUST generate:**
- Language-specific entity classes for all `prism:Entity` and `prism:ValueObject` classes
- Enumeration types for all `prism:Enumeration` classes
- Type-safe property accessors reflecting OWL datatype ranges
- Null-safety reflecting `prism:required`

**Declare:** `prism:conformanceLevel prism:Level1Core`

### Level 2: Persistence

**All Level 1 requirements, plus:**
- Repository/DAO interfaces for all `prism:Entity` with `prism:generateRepository true`
- Persistence mapping files (JPA annotations, MongoDB codecs, Cypher node definitions, SPARQL INSERT templates) per `prism:PersistenceConfig`
- SHACL shapes derived from property constraint annotations
- Database migration scripts for `prism:Migration` instances
- Index creation for `prism:indexed true` and `prism:unique true` properties

**Declare:** `prism:conformanceLevel prism:Level2Persistence`

### Level 3: API

**All Level 2 requirements, plus:**
- REST controllers with all endpoints specified by `prism:ApiConfig`
- OpenAPI 3.1 specification
- GraphQL schema and resolvers for `prism:generateGraphQL true` entities
- Request/response DTO classes derived from entity properties
- SHACL-grounded request validation in service layer
- API endpoint stubs for all `prism:Action` and `prism:Query` instances

**Declare:** `prism:conformanceLevel prism:Level3Api`

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

## 20. Complete Example: Trade Domain

The following is a self-contained Prism-conformant ontology for a trade domain, demonstrating all Level 4 features.

```turtle
@prefix ex:      <https://example.com/ontologies/trade#> .
@prefix prism:     <https://spec.prism.dev/1.0#> .
@prefix owl:     <http://www.w3.org/2002/07/owl#> .
@prefix rdf:     <http://www.w3.org/1999/02/22-rdf-syntax-ns#> .
@prefix rdfs:    <http://www.w3.org/2000/01/rdf-schema#> .
@prefix xsd:     <http://www.w3.org/2001/XMLSchema#> .
@prefix sh:      <http://www.w3.org/ns/shacl#> .
@prefix skos:    <http://www.w3.org/2004/02/skos/core#> .
@prefix dcterms: <http://purl.org/dc/terms/> .
@prefix vann:    <http://purl.org/vocab/vann/> .
@prefix schema:  <http://schema.org/> .

###########################################################################
# Ontology Header
###########################################################################

<https://example.com/ontologies/trade>
    a owl:Ontology ;
    rdfs:label "Trade Domain Ontology"@en ;
    dcterms:title "Trade Domain Ontology"@en ;
    dcterms:description "Models international trade shipments, parties, documents, and compliance events."@en ;
    dcterms:creator "Acme Logistics" ;
    dcterms:created "2026-05-25"^^xsd:date ;
    owl:versionIRI <https://example.com/ontologies/trade/1.0.0> ;
    owl:versionInfo "1.0.0" ;
    prism:conformanceLevel prism:Level4AiNative ;
    vann:preferredNamespacePrefix "ex" ;
    vann:preferredNamespaceUri "https://example.com/ontologies/trade#" ;
    owl:imports <https://spec.prism.dev/1.0> .

###########################################################################
# Enumerations
###########################################################################

ex:ShipmentStatus
    a owl:Class ;
    rdfs:label "Shipment Status"@en ;
    prism:entityKind prism:Enumeration .

ex:BOOKED     a ex:ShipmentStatus, owl:NamedIndividual ; rdfs:label "BOOKED"@en .
ex:IN_TRANSIT a ex:ShipmentStatus, owl:NamedIndividual ; rdfs:label "IN_TRANSIT"@en .
ex:CUSTOMS    a ex:ShipmentStatus, owl:NamedIndividual ; rdfs:label "CUSTOMS"@en .
ex:DELIVERED  a ex:ShipmentStatus, owl:NamedIndividual ; rdfs:label "DELIVERED"@en .
ex:CANCELLED  a ex:ShipmentStatus, owl:NamedIndividual ; rdfs:label "CANCELLED"@en .

ex:IncotermsCode
    a owl:Class ;
    rdfs:label "Incoterms Code"@en ;
    prism:entityKind prism:Enumeration .

ex:EXW a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "EXW"@en .
ex:FOB a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "FOB"@en .
ex:CIF a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "CIF"@en .
ex:DDP a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "DDP"@en .
ex:DAP a ex:IncotermsCode, owl:NamedIndividual ; rdfs:label "DAP"@en .

###########################################################################
# Value Objects
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
    prism:required true ;
    prism:minValue "0.00"^^xsd:decimal .

ex:currency
    a owl:DatatypeProperty ;
    rdfs:domain ex:MonetaryAmount ;
    rdfs:range xsd:string ;
    rdfs:label "currency"@en ;
    prism:required true ;
    prism:pattern "[A-Z]{3}" ;
    prism:example "USD" .

###########################################################################
# Core Entity: Party
###########################################################################

ex:Party
    a owl:Class ;
    rdfs:label "Party"@en ;
    rdfs:comment "A legal entity participating in a trade transaction."@en ;
    prism:entityKind prism:Entity ;
    prism:pluralLabel "Parties"@en ;
    prism:generateCode true ;
    prism:generateRepository true ;
    prism:generateRestEndpoint true ;
    prism:agentAccessible true ;
    prism:agentPermission prism:ReadOnly ;
    prism:semanticDescription """A Party is a legal entity — a company, government body, or individual — participating in a trade operation. Parties play roles: Exporter (seller/shipper), Importer (buyer/consignee), Carrier (transport provider), Customs Broker, Freight Forwarder, or Notify Party. A single legal entity may play multiple roles across different shipments. When a user asks about who sent or received a shipment, look up the Party with the EXPORTER or IMPORTER role respectively.""" ;
    prism:mapsTo schema:Organization ;
    prism:mappingType prism:NearMatch ;
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:JpaBackend ;
        prism:tableName "parties" ;
        prism:enableCache true ;
        prism:cacheTtlSeconds 3600
    ] ;
    prism:apiConfig [
        a prism:ApiConfig ;
        prism:basePath "/api/v1/parties" ;
        prism:allowedMethod prism:GET, prism:POST, prism:PATCH ;
        prism:requiresAuthentication true ;
        prism:paginationDefault 50 ;
        prism:apiTag "Parties"
    ] .

ex:partyId
    a owl:DatatypeProperty ;
    rdfs:domain ex:Party ;
    rdfs:range xsd:string ;
    rdfs:label "party ID"@en ;
    prism:required true ;
    prism:unique true ;
    prism:immutable true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:example "PARTY-CN-0042" .

ex:legalName
    a owl:DatatypeProperty ;
    rdfs:domain ex:Party ;
    rdfs:range xsd:string ;
    rdfs:label "legal name"@en ;
    prism:required true ;
    prism:indexed true ;
    prism:searchable true ;
    prism:sortable true ;
    prism:filterable true ;
    prism:maxLength 500 ;
    prism:example "Shenzhen Electronics Manufacturing Ltd." .

ex:countryCode
    a owl:DatatypeProperty ;
    rdfs:domain ex:Party ;
    rdfs:range xsd:string ;
    rdfs:label "country code"@en ;
    prism:required true ;
    prism:pattern "[A-Z]{2}" ;
    prism:indexed true ;
    prism:filterable true ;
    prism:example "CN" .

###########################################################################
# Core Entity: Shipment
###########################################################################

ex:Shipment
    a owl:Class ;
    rdfs:label "Shipment"@en ;
    rdfs:comment "A consignment of goods transported from an exporter to an importer under a trade transaction."@en ;
    prism:entityKind prism:Entity ;
    prism:pluralLabel "Shipments"@en ;
    prism:generateCode true ;
    prism:generateRepository true ;
    prism:generateRestEndpoint true ;
    prism:generateGraphQL true ;
    prism:agentAccessible true ;
    prism:agentPermission prism:ReadWrite ;
    prism:semanticDescription """A Shipment is the central node of the trade knowledge graph. Every document (commercial invoice, bill of lading, packing list, customs declaration), every event (booking, loading, customs clearance, delivery), and every party (exporter, importer, carrier) connects to a Shipment. When answering questions about a trade operation, anchor your analysis to the Shipment first, then traverse its relationships. A shipment has a status lifecycle: BOOKED → IN_TRANSIT → CUSTOMS → DELIVERED. If a Shipment has compliance findings with severity BLOCKED, it cannot progress past the CUSTOMS stage.""" ;
    prism:persistence [
        a prism:PersistenceConfig ;
        prism:backend prism:JpaBackend ;
        prism:tableName "shipments" ;
        prism:enableCache true ;
        prism:cacheTtlSeconds 60
    ] ;
    prism:apiConfig [
        a prism:ApiConfig ;
        prism:basePath "/api/v1/shipments" ;
        prism:allowedMethod prism:GET, prism:POST, prism:PATCH ;
        prism:requiresAuthentication true ;
        prism:paginationDefault 25 ;
        prism:paginationMax 200 ;
        prism:apiTag "Shipments"
    ] .

ex:shipmentId
    a owl:DatatypeProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range xsd:string ;
    rdfs:label "shipment ID"@en ;
    prism:required true ;
    prism:unique true ;
    prism:immutable true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:sortable true ;
    prism:pattern "SH[0-9]{8}" ;
    prism:maxLength 10 ;
    prism:example "SH20260001" .

ex:status
    a owl:ObjectProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range ex:ShipmentStatus ;
    rdfs:label "status"@en ;
    prism:required true ;
    prism:indexed true ;
    prism:filterable true ;
    prism:sortable true ;
    prism:defaultValue ex:BOOKED .

ex:incoterms
    a owl:ObjectProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range ex:IncotermsCode ;
    rdfs:label "Incoterms"@en ;
    prism:required true .

ex:invoiceTotal
    a owl:ObjectProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range ex:MonetaryAmount ;
    rdfs:label "invoice total"@en ;
    prism:required true ;
    prism:cascade prism:CascadeAll .

ex:originLocode
    a owl:DatatypeProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range xsd:string ;
    rdfs:label "origin UN/LOCODE"@en ;
    prism:required true ;
    prism:pattern "[A-Z]{2}[A-Z0-9]{3}" ;
    prism:filterable true ;
    prism:example "CNSHA" .

ex:destinationLocode
    a owl:DatatypeProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range xsd:string ;
    rdfs:label "destination UN/LOCODE"@en ;
    prism:required true ;
    prism:pattern "[A-Z]{2}[A-Z0-9]{3}" ;
    prism:filterable true ;
    prism:example "USLAX" .

ex:bookedAt
    a owl:DatatypeProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range xsd:dateTime ;
    rdfs:label "booked at"@en ;
    prism:required true ;
    prism:immutable true ;
    prism:sortable true ;
    prism:filterable true .

# Relationships
ex:hasExporter
    a owl:ObjectProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range ex:Party ;
    rdfs:label "has exporter"@en ;
    prism:cardinality prism:ManyToOne ;
    prism:cascade prism:CascadeNone ;
    prism:fetchStrategy prism:Lazy ;
    prism:required true ;
    prism:joinColumn "exporter_id" .

ex:hasImporter
    a owl:ObjectProperty ;
    rdfs:domain ex:Shipment ;
    rdfs:range ex:Party ;
    rdfs:label "has importer"@en ;
    prism:cardinality prism:ManyToOne ;
    prism:cascade prism:CascadeNone ;
    prism:fetchStrategy prism:Lazy ;
    prism:required true ;
    prism:joinColumn "importer_id" .

###########################################################################
# Actions
###########################################################################

ex:SubmitCustomsDeclarationAction
    a prism:Action ;
    rdfs:label "Submit Customs Declaration"@en ;
    rdfs:comment "Files an import customs declaration for a shipment at its destination country."@en ;
    prism:actionKind prism:CustomAction ;
    prism:targetClass ex:Shipment ;
    prism:inputShape ex:CustomsDeclarationInputShape ;
    prism:outputClass ex:Shipment ;
    prism:requiredRole "ROLE_CUSTOMS_BROKER" ;
    prism:sideEffect "Creates a CustomsDeclaration document, transitions shipment status to CUSTOMS, and notifies the importer by email." .

ex:CustomsDeclarationInputShape
    a sh:NodeShape ;
    sh:property [
        sh:path ex:shipmentId ;
        sh:minCount 1 ;
        sh:datatype xsd:string
    ] ;
    sh:property [
        sh:path ex:hsCode ;
        sh:minCount 1 ;
        sh:datatype xsd:string ;
        sh:pattern "[0-9]{6,10}"
    ] ;
    sh:property [
        sh:path ex:declaredValue ;
        sh:minCount 1 ;
        sh:datatype xsd:decimal
    ] .

###########################################################################
# Named Queries
###########################################################################

ex:BlockedShipmentsQuery
    a prism:Query ;
    rdfs:label "Blocked Shipments"@en ;
    rdfs:comment "Returns all shipments currently blocked by a compliance finding."@en ;
    prism:outputClass ex:Shipment ;
    prism:sparqlQuery """
        SELECT ?shipment ?shipmentId ?origin ?destination WHERE {
            ?shipment a ex:Shipment ;
                      ex:shipmentId ?shipmentId ;
                      ex:originLocode ?origin ;
                      ex:destinationLocode ?destination .
            ?finding  ex:affectsShipment ?shipment ;
                      ex:severity ex:BLOCKED .
        }
        ORDER BY ?shipmentId
    """ .

###########################################################################
# Migrations
###########################################################################

ex:Migration_1_0_to_1_1
    a prism:Migration ;
    rdfs:label "Rename originPort to originLocode"@en ;
    prism:fromVersion "1.0.0" ;
    prism:toVersion "1.1.0" ;
    prism:isBreaking true ;
    prism:migrationScript """
        DELETE { ?s ex:originPort ?o }
        INSERT { ?s ex:originLocode ?o }
        WHERE  { ?s ex:originPort ?o }
    """ .
```

---

## 21. JSON-LD Context

Prism provides an official JSON-LD 1.1 context at `https://spec.prism.dev/context/1.0.jsonld`. The following is an excerpt:

```json
{
  "@context": {
    "prism":     "https://spec.prism.dev/1.0#",
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
    "pluralLabel":          { "@id": "prism:pluralLabel",          "@type": "xsd:string" },
    "persistence":          { "@id": "prism:persistence",          "@type": "@id" },
    "apiConfig":            { "@id": "prism:apiConfig",            "@type": "@id" },
    "conformanceLevel":     { "@id": "prism:conformanceLevel",     "@type": "@id" },

    "required":             { "@id": "prism:required",     "@type": "xsd:boolean" },
    "unique":               { "@id": "prism:unique",       "@type": "xsd:boolean" },
    "indexed":              { "@id": "prism:indexed",      "@type": "xsd:boolean" },
    "searchable":           { "@id": "prism:searchable",   "@type": "xsd:boolean" },
    "sortable":             { "@id": "prism:sortable",     "@type": "xsd:boolean" },
    "filterable":           { "@id": "prism:filterable",   "@type": "xsd:boolean" },
    "sensitive":            { "@id": "prism:sensitive",    "@type": "xsd:boolean" },
    "immutable":            { "@id": "prism:immutable",    "@type": "xsd:boolean" },
    "columnName":           { "@id": "prism:columnName",   "@type": "xsd:string" },
    "pattern":              { "@id": "prism:pattern",      "@type": "xsd:string" },
    "minLength":            { "@id": "prism:minLength",    "@type": "xsd:integer" },
    "maxLength":            { "@id": "prism:maxLength",    "@type": "xsd:integer" },
    "example":              { "@id": "prism:example" },

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

    "Entity":       "prism:Entity",
    "ValueObject":  "prism:ValueObject",
    "Enumeration":  "prism:Enumeration",
    "ReadOnly":     "prism:ReadOnly",
    "ReadWrite":    "prism:ReadWrite",
    "Restricted":   "prism:Restricted",
    "JpaBackend":   "prism:JpaBackend",
    "MongoBackend": "prism:MongoBackend",
    "Neo4jBackend": "prism:Neo4jBackend",
    "Rdf4jBackend": "prism:Rdf4jBackend",
    "Eager":        "prism:Eager",
    "Lazy":         "prism:Lazy",
    "OneToOne":     "prism:OneToOne",
    "OneToMany":    "prism:OneToMany",
    "ManyToOne":    "prism:ManyToOne",
    "ManyToMany":   "prism:ManyToMany",
    "CascadeAll":   "prism:CascadeAll",
    "CascadeNone":  "prism:CascadeNone"
  }
}
```

---

## Appendix A: Annotation Quick Reference

### Entity-Level

| Annotation | Type | Default | Section |
|---|---|---|---|
| `prism:entityKind` | `prism:EntityKind` | `prism:Entity` | §6 |
| `prism:generateCode` | boolean | `true` | §6.1 |
| `prism:generateRepository` | boolean | `true` (Entity) | §6.1 |
| `prism:generateRestEndpoint` | boolean | `true` (Entity) | §6.1 |
| `prism:generateGraphQL` | boolean | `false` | §6.1 |
| `prism:pluralLabel` | string | label + "s" | §6.1 |
| `prism:persistence` | `prism:PersistenceConfig` | — | §6.3 |
| `prism:apiConfig` | `prism:ApiConfig` | — | §6.4 |
| `prism:sinceVersion` | string | — | §6.2 |
| `prism:deprecatedSince` | string | — | §6.2 |
| `prism:replacedBy` | IRI | — | §6.2 |
| `prism:agentAccessible` | boolean | `true` (Entity) | §12 |
| `prism:agentPermission` | `prism:AgentPermission` | `prism:ReadOnly` | §12 |
| `prism:semanticDescription` | string | — | §12.3 |
| `prism:toolName` | string | camelCase(label) | §12.4 |
| `prism:mapsTo` | IRI | — | §15 |
| `prism:mappingType` | `prism:MappingType` | — | §15 |

### Property-Level

| Annotation | Type | Default | Section |
|---|---|---|---|
| `prism:required` | boolean | `false` | §7.1 |
| `prism:unique` | boolean | `false` | §7.1 |
| `prism:pattern` | string | — | §7.1 |
| `prism:minValue` | Literal | — | §7.1 |
| `prism:maxValue` | Literal | — | §7.1 |
| `prism:minLength` | integer | — | §7.1 |
| `prism:maxLength` | integer | — | §7.1 |
| `prism:indexed` | boolean | `false` | §7.2 |
| `prism:searchable` | boolean | `false` | §7.2 |
| `prism:sortable` | boolean | `false` | §7.2 |
| `prism:filterable` | boolean | `false` | §7.2 |
| `prism:columnName` | string | snake_case(localname) | §7.3 |
| `prism:defaultValue` | Literal | — | §7.3 |
| `prism:immutable` | boolean | `false` | §7.3 |
| `prism:sensitive` | boolean | `false` | §7.4 |
| `prism:example` | Literal | — | §7.4 |

### Relationship-Level

| Annotation | Type | Default | Section |
|---|---|---|---|
| `prism:cardinality` | `prism:Cardinality` | `prism:ManyToOne` | §8 |
| `prism:cascade` | `prism:CascadeType` | `prism:CascadeNone` | §8 |
| `prism:fetchStrategy` | `prism:FetchStrategy` | `prism:Lazy` | §8 |
| `prism:joinColumn` | string | derived | §8 |
| `prism:joinTable` | string | — | §8 |

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

| Feature | Prism 1.0 | Palantir Foundry Ontology | JHipster JDL | OpenAPI 3.1 | Prisma Schema |
|---|---|---|---|---|---|
| **Standard** | W3C (OWL/RDF/SHACL) | Proprietary | Proprietary | OpenAPI consortium | Proprietary |
| **Portable** | Yes — Turtle is vendor-neutral | No — Foundry only | No | Partial | No |
| **Graph-native** | Yes — RDF is a graph model | Partial | No | No | No |
| **AI agent generation** | Yes (Level 4) | Yes (AIP) | No | No | No |
| **SHACL validation** | Yes — generated from annotations | No | No | Partial (JSON Schema) | No |
| **External ontology mapping** | Yes — `prism:mapsTo` | No | No | No | No |
| **Semantic descriptions for LLMs** | Yes — `prism:semanticDescription` | Proprietary | No | `description` field only | No |
| **Multi-backend persistence** | Yes | Foundry-internal | JPA only | No | Prisma-supported DBs |
| **Schema migrations** | Yes — SPARQL UPDATE | Proprietary | Liquibase | No | Prisma Migrate |
| **Open source** | Yes (CC BY 4.0) | No | Yes (Apache 2.0) | Yes | Yes (Apache 2.0) |
| **Self-hosted** | Yes | Enterprise only | Yes | N/A | Yes |
| **Price** | Free | $1M+ enterprise | Free | Free | Free |

---

*Prism 1.0 Working Draft — 2026-05-25*  
*Prism Working Group*  
*https://prism.dev*
