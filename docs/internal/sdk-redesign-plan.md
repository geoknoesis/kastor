# Kastor SDK Redesign Plan (Internal)

## Purpose
Define a concrete redesign plan to make Kastor a reference-quality, RDF-engine-agnostic Kotlin SDK with SHACL and JSON-LD as first-class contracts.

## Design Goals
- RDF-API agnostic public surface (no Jena/RDF4J leakage).
- Schema-driven, not string-driven.
- SHACL + JSON-LD as the semantic source of truth.
- Minimal, composable, and idiomatic Kotlin.
- Explicit semantics with implicit boilerplate.
- Easy to adopt, hard to misuse.

## Proposed Module Boundaries
- `rdf-core`: Semantic model (Iri, Term, GraphView), typed contracts, minimal graph/query API.
- `rdf-context`: JSON-LD context model and mapping (Context, Term, Mapping).
- `rdf-shacl`: SHACL domain model + validation API (Shape, Constraint, ValidationReport).
- `rdf-adapter-*`: Backend adapters (jena, rdf4j, sparql, in-memory).
- `kastor-gen-processor`: Codegen from SHACL + JSON-LD to Kotlin domain types.
- `kastor-gen-runtime`: Minimal runtime for materialization using contracts.

## Structural Changes (High Level)
1. Replace provider IDs and option maps with typed repository profiles and capabilities.
2. Introduce context-aware mapping types as first-class runtime contracts.
3. Make SHACL shapes and constraints typed and consistent across runtime and codegen.
4. Add a node-centric graph view alongside raw triple access.
5. Remove global registries from public API; prefer explicit factories and DI.

---

## Before / After Examples

### 1) Repository Creation (Engine-Agnostic)

**Before**
```kotlin
val repo = Rdf.memory()

val prod = Rdf.factory {
  providerId = "jena"
  variantId = "tdb2"
  location = "data"
  inference = true
}
```

**After**
```kotlin
val repo = Rdf.repositories.inMemory()

val prod = Rdf.repositories.persistent(
  location = Path.of("data"),
  profile = RepositoryProfile.Inference(Rdfs)
)
```

**Notes**
- `RepositoryProfile` is typed (e.g., `Inference`, `Transactions`, `NamedGraphs`).
- No `providerId` or `variantId` in the public surface.
- Backend selection happens in adapters by capabilities.

---

### 2) Graph Operations (Semantic Intent First)

**Before**
```kotlin
repo.add {
  val person = iri("http://example.org/person")
  person - FOAF.name - "Alice"
  person - FOAF.age - 30
}
```

**After**
```kotlin
val graph = repo.graph()
val person = graph.node(iri("http://example.org/person"))

person.set(FOAF.name, "Alice")
person.set(FOAF.age, 30)
```

**Notes**
- `GraphView` exposes `node(Iri)` with property accessors.
- Raw triple APIs remain available under an `advanced` or `raw` namespace.

---

### 3) SHACL Validation (Unified Contract)

**Before**
```kotlin
val validator = ShaclValidation.validator()
val report = validator.validate(dataGraph, shapesGraph)
if (!report.isValid) { ... }
```

**After**
```kotlin
val validator = Shacl.validator(profile = ValidationProfile.Strict)
val report = validator.validate(data = graph, shapes = shapes)

if (report is ValidationReport.Failed) {
  report.violations.forEach { v -> println(v.describe()) }
}
```

**Notes**
- `ValidationReport` is a sealed type (`Passed` | `Failed`).
- Shapes and paths are typed (`Iri`, `RdfPath`), not `String`.
- No duplicate validation registries in runtime.

---

### 4) JSON-LD Context as Runtime Contract

**Before**
```kotlin
@RdfClass(iri = "http://www.w3.org/ns/dcat#Catalog")
interface Catalog {
  @get:RdfProperty(iri = "http://purl.org/dc/terms/title")
  val title: String
}
```

**After**
```kotlin
object DcatContext : Context {
  val Catalog = term("dcat:Catalog")
  val title = term("dcterms:title", type = XSD.string)
}

@RdfClass(DcatContext.Catalog)
interface Catalog {
  @get:RdfProperty(DcatContext.title)
  val title: String
}
```

**Notes**
- Context is inspectable and versionable at runtime.
- IRIs are not repeated as strings throughout the codebase.

---

### 5) Materialization / Mapping

**Before**
```kotlin
val ref = RdfRef(node, graph)
val catalog = kastor.gen.materialize(ref, Catalog::class.java, validate = true)
```

**After**
```kotlin
val catalog = graph.node(node).asType<Catalog>(validate = true)
```

**Notes**
- `asType` is a semantic operator on `NodeView`.
- Validation is part of the mapping contract, not a side-channel registry.

---

### 6) Query API (Typed Bindings)

**Before**
```kotlin
val result = repo.query("SELECT ?value WHERE { ... }")
val title = result.firstAs<String>()
```

**After**
```kotlin
val query = Query.select {
  bind(title)
  where { node has DCTERMS.title value title }
}

val titles: List<String> = repo.query(query).map { it[title] }
```

**Notes**
- `Query` is a typed model with safe bindings.
- String SPARQL remains as an explicit opt-in.

---

## Detailed Design Proposals

### A) Repository Profiles and Capabilities
- `RepositoryProfile` (sealed) describes desired semantics.
- Adapters implement capability matching to select backend.
- `Rdf.repositories` provides a minimal creation surface.

### B) GraphView and NodeView
- `GraphView` exposes `node(Iri)` and high-level CRUD methods.
- `NodeView` provides `set`, `add`, `remove`, `values`.
- Raw triple API is isolated under `graph.raw`.

### C) SHACL Domain Model
- `ShapeId` (Iri), `Shape`, `PropertyShape`, `Constraint`.
- `Constraint` is a sealed hierarchy with typed fields.
- `ValidationReport` is a sealed result with rich data.

### D) JSON-LD Context Contract
- `Context` contains `Term` definitions (id, type, container).
- `Term` is used in annotations and mapping APIs.
- Codegen emits a context object per ontology.

### E) Error Surfaces
- `ValidationReport.Failed` includes `ShapeId` and `Term`.
- Mapping errors reference shape + term + node.
- No silent coercion in DSL by default.

---

## Migration Steps (Phased)
1. Introduce new contracts in parallel (`GraphView`, `Context`, `Shape`).
2. Add adapters that implement the new contracts.
3. Update codegen to emit typed `Context` and constraints.
4. Deprecate stringly APIs with clear replacements.
5. Remove public exposure of provider registries.

---

## Open Decisions
- Whether to ship a small typed SPARQL DSL in core or in a separate module.
- How to model complex SHACL paths (`RdfPath` and property paths).
- Whether to provide a default in-memory backend in `rdf-core` or in an adapter.





