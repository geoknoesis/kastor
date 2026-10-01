# RDF4J Repository Management

The Kastor RDF API provides comprehensive RDF4J repository management capabilities, allowing you to create, configure, and manage multiple RDF4J repositories with advanced features like inference, validation, and federation.

## Overview

The RDF4J repository management system provides:

- **Centralized Repository Management**: Create and manage multiple repositories
- **Advanced Storage Backends**: Memory, Native, and specialized variants
- **Inference Capabilities**: RDFS reasoning support (RDF4J ships no OWL reasoner)
- **Validation Support**: SHACL constraint validation
- **Federation**: Cross-repository query capabilities
- **Statistics and Monitoring**: Performance tracking
- **Configuration Management**: Dynamic repository configuration updates

## Repository Variants

### Basic Repositories

#### `rdf4j:memory`
Basic in-memory RDF4J repository with default configuration.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory"
}
```

#### `rdf4j:native`
Persistent NativeStore with high-performance disk storage.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native"
    location = "/data/rdf4j"
}
```

### RDF-star Repositories

#### `rdf4j:memory:star`
In-memory repository with explicit RDF-star support.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-star"
}
```

#### `rdf4j:native:star`
Persistent repository with explicit RDF-star support.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native-star"
    location = "/data/rdf4j"
}
```

### Inference Repositories

#### `rdf4j:memory:rdfs`
In-memory repository with RDFS inference enabled.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-rdfs"
}
```

#### `rdf4j:native:rdfs`
Persistent repository with RDFS inference enabled.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native-rdfs"
    location = "/data/rdf4j"
}
```

### Validation Repositories

#### `rdf4j:memory:shacl`
In-memory repository wrapped with RDF4J's `ShaclSail`. Writes that violate
SHACL constraints fail at commit time with `ShaclSailValidationException`.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-shacl"
}
```

#### `rdf4j:native:shacl`
Persistent `ShaclSail` over `NativeStore`.

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "native-shacl"
    location = "/data/rdf4j"
}
```

> **Loading shapes:** SHACL shapes must be loaded into the reserved
> `RDF4J.SHACL_SHAPE_GRAPH` named graph (`http://rdf4j.org/schema/rdf4j#SHACLShapeGraph`)
> before validation kicks in. See the example in
> [Rdf4jVariantsTest](../../../rdf/providers/rdf4j/src/test/kotlin/com/geoknoesis/kastor/rdf/rdf4j/Rdf4jVariantsTest.kt).

## Managing Multiple Repositories

Kastor does not ship a dedicated RDF4J repository manager. Use explicit composition:

```kotlin
val repositories = mapOf(
    "users" to Rdf.repository { providerId = "rdf4j"; variantId = "memory" },
    "products" to Rdf.repository {
        providerId = "rdf4j"
        variantId = "native"
        location = "/data/products"
    }
)
```

Close all repositories when done:

```kotlin
repositories.values.forEach { it.close() }
```

## Advanced Features

### Inference Capabilities

```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDFS
import com.geoknoesis.kastor.rdf.vocab.SHACL

// Create repository with RDFS inference
val repo = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-rdfs"
}

val graphEditor = repo.editDefaultGraph()

// Add RDFS schema
val rdfsSubClassOf = RDFS.subClassOf
val personClass = iri("http://example.org/Person")
val animalClass = iri("http://example.org/Animal")

graphEditor.addTriple(triple(personClass, rdfsSubClassOf, animalClass))

// Query with inference
val results = repo.select(
    SparqlSelectQuery("SELECT ?s WHERE { ?s ${RDFS.subClassOf} ${animalClass} }")
)
// Returns Person class due to inference
```

The RDF4J `RdfReasonerProvider` (`rdf-rdf4j-reasoning`) supports only `ReasonerType.RDFS`; every other type is rejected. For OWL, use Jena (`OWL_RL`) or HermiT (`HERMIT` / `OWL_DL`).

### Parsing, literals and interop

- **Lexical forms are preserved exactly** (`"007"^^xsd:integer` stays `"007"`); only the exact forms `"true"` / `"false"` become boolean singletons.
- **Base direction**: RDF4J 5.3.1 has no base-direction API. Kastor stores a directional literal such as `"x"@ar--rtl` inside RDF4J under the language tag `ar--rtl` and restores the direction when reading, so SPARQL `LANG()` evaluated by RDF4J returns `ar--rtl`.
- **`parseGraph` accepts triple formats only**: TriG and N-Quads are rejected with `RdfFormatException`; use dataset parsing. Without a base IRI, relative IRIs are a parse error.
- **Interop functions** (`com.geoknoesis.kastor.rdf.rdf4j`): `rdfTermFromRdf4j(value)`, `rdf4jValueOf(term)`, `rdf4jResourceOf(resource)`, `rdfTriplesFromRdf4j(statement)` and `rdf4jStatementOf(triple)`. `rdfTriplesFromRdf4j` returns the complete RDF 1.2 form of a statement: the converted triple first, plus `_:r rdf:reifies <<( s p o )>>` for each RDF-star quoted-triple subject. The single-triple `rdfTripleFromRdf4j(statement)` is deprecated because it drops those `rdf:reifies` triples.
- **Reifier ids for RDF-star subjects** are deterministic and encode their quoted triple: `kastor-star-<base64url of the encoded triple>-<first 8 hex digits of its SHA-256>`. A blank node is treated as such a reifier only when its id decodes to a triple and the checksum matches, so reifier lookups are index lookups rather than store scans. Don't build these ids yourself.
- **Strict graph reads** (default): a graph read that meets a statement Kastor cannot represent (for example a malformed language tag written by other RDF4J code) fails with `IllegalArgumentException`. To skip such statements with a logged warning, wrap a store with `Rdf4jRepository(repository, inference = false, lenientRead = true)` or pass the `lenientRead` option (`RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data/rdf4j", "lenientRead" to "true"))`). In lenient mode `size()` counts only the statements reads return. SPARQL results are not affected.
- **Language-tag case on NativeStore**: Kastor compares tags ignoring case, but `NativeStore` matches a literal by its exact tag bytes once its small value-id cache misses (after a restart or an eviction). Kastor graph lookups and removals (`hasTriple`, `find`, `removeTriple`, `removeTriples`) fall back to a scan of the `(subject, predicate, *)` statements, so they still ignore tag case. SPARQL evaluated by RDF4J has no such fallback: a query literal such as `"x"@en-gb` may not match a stored `"x"@en-GB`. Memory stores ignore tag case.
- **Initial bindings**: `withSelectRows(query, bindings, timeout)` substitutes IRI and literal bindings into the query text with the same rules as the Jena provider (Jena's query substitution) and the SPARQL endpoint adapter, so all three return the same rows: a binding restricts the query before aggregation, `LIMIT` and `FILTER`, a projected bound variable is bound in every row, `SELECT *` does not return it, and `BOUND(?v)` is true. **Behaviour change:** queries that assign a bound variable (`BIND(... AS ?v)`, `(expr AS ?v)`, `VALUES ?v`) or use it inside a sub-select that does not project it now throw `IllegalArgumentException` (previously RDF4J's `setBinding` accepted them with different results). Blank nodes, triple terms and directional literals are still bound with RDF4J's native `setBinding`. RDF4J's SPARQL parser decodes `\uXXXX` / `\UXXXXXXXX` escapes over the whole query text before parsing, and the substitution does the same.
- **Query dataset**: outside `GRAPH`, a SPARQL query reads the repository's default graph only (the statements without a context), like `repo.defaultGraph` and like the Jena provider; inside `GRAPH` it reads the named graphs. **Behaviour change:** RDF4J's own default is to evaluate a query that declares no dataset against the union of all contexts, so `SELECT ?o { ?s ?p ?o }` used to return the triples of every named graph as well; query them with `GRAPH ?g { ... }` or `FROM <graph>`. Details: a query with its own `FROM` / `FROM NAMED` keeps the dataset it declares; a query that only reads inside `GRAPH` runs as before (blank-node contexts included); a query that reads both inside and outside `GRAPH` gets the IRI-named contexts (`listGraphs()`) as named graphs, enumerated once per query, so blank-node contexts are not visible to it; `DESCRIBE` describes from the default graph. Only stores evaluated by an RDF4J Sail are affected: a wrapped remote repository (HTTP or SPARQL endpoint) keeps its server's dataset. **SPARQL `UPDATE` is unchanged**: RDF4J still matches the `WHERE` clause of an update against all contexts (Jena matches the default graph only).
- **Transactions**: batch writes run in one transaction; per-triple writes outside a transaction each cost one, so batch them or use `repo.transaction { }`.

### Validation Capabilities

```kotlin
// Create repository with SHACL validation
val repo = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory-shacl"
}

// Add SHACL shapes
repo.createGraph(iri("http://example.org/shapes"))
val shapesEditor = repo.editGraph(iri("http://example.org/shapes"))
val shaclTargetClass = SHACL.targetClass
val shaclProperty = SHACL.property
val shaclPath = SHACL.path
val shaclMinCount = SHACL.minCount

val personShape = iri("http://example.org/PersonShape")
val personClass = iri("http://example.org/Person")
val nameProperty = iri("http://example.org/nameProperty")
val namePath = FOAF.name

shapesEditor.addTriple(triple(personShape, shaclTargetClass, personClass))
shapesEditor.addTriple(triple(personShape, shaclProperty, nameProperty))
shapesEditor.addTriple(triple(nameProperty, shaclPath, namePath))
shapesEditor.addTriple(triple(nameProperty, shaclMinCount, int(1)))
```

### Federation and Cross‑Repository Patterns

Kastor does not provide RDF4J‑specific federation utilities. If you need federation,
use a SPARQL endpoint that supports it or compose results in your application.

## Migration from Basic Implementation

Use explicit provider/variant selection:

```kotlin
val api = Rdf.repository {
    providerId = "rdf4j"
    variantId = "memory"
}

val manager = Rdf4jRepositoryManagerFactory.create()
manager.createRepository("data", RdfConfig(providerId = "rdf4j", variantId = "memory-rdfs"))
```

## Examples

See the `Rdf4jRepositoryManagerExample.kt` file for comprehensive examples demonstrating all features.




