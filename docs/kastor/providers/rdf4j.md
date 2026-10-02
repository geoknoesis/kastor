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
- **Base direction**: RDF4J 5.3.2 has no base-direction API. Kastor stores a directional literal such as `"x"@ar--rtl` inside RDF4J under the language tag `ar--rtl` and restores the direction when reading, so SPARQL `LANG()` evaluated by RDF4J returns `ar--rtl`.
- **`parseGraph` accepts triple formats only**: TriG and N-Quads are rejected with `RdfFormatException`; use dataset parsing. Without a base IRI, relative IRIs are a parse error.
- **Interop functions** (`com.geoknoesis.kastor.rdf.rdf4j`): `rdfTermFromRdf4j(value)`, `rdf4jValueOf(term)`, `rdf4jResourceOf(resource)`, `rdfTriplesFromRdf4j(statement)` and `rdf4jStatementOf(triple)`. `rdfTriplesFromRdf4j` returns the complete RDF 1.2 form of a statement: the converted triple first, plus `_:r rdf:reifies <<( s p o )>>` for each RDF-star quoted-triple subject. The single-triple `rdfTripleFromRdf4j(statement)` is deprecated because it drops those `rdf:reifies` triples.
- **Reifier ids for RDF-star subjects** are deterministic and resolve to their quoted triple: `kastor-star-<base64url of the encoded triple>-<first 8 hex digits of its SHA-256>`. A blank node is treated as such a reifier only when its id decodes to a triple and the checksum matches, so reifier lookups are index lookups rather than store scans. A quoted triple too large for such an id (roughly 750 KB of encoded terms or more, e.g. a triple with a 1 MB literal) gets the bounded id `kastor-star-sha256-<64 hex digits>` instead, so reads never fail on large literals. Such an id does not carry its triple: each repository resolves it from its own index of the oversized triples it has written or read (there is no process-wide state). An id the index does not know is an ordinary blank node; the store is scanned for it, once, only when the index may be incomplete - after a SPARQL `UPDATE` that may have written triple values, or on a wrapped store that other code may change (once per transaction, so batch operations scan at most once; single operations outside a transaction scan each time they meet an unknown id). Don't build these ids yourself. A reifier stands for its quoted triple in **subject** position only. Used as an **object** (`:s :p _:r`, or the object of a triple term) it is an ordinary blank node: it is stored as that blank node and read back as the same blank node. (**Behaviour change:** it used to be stored as the quoted triple, so the triple read back had a triple term as object and `hasTriple` did not find the triple that was written.)
- **Explicit `rdf:reifies` triples are stored statements**: adding `_:r rdf:reifies <<( s p o )>>` through the graph API always stores that statement, also while annotations about the quoted triple already imply it. It therefore survives the removal of the last annotation, a restart of a persistent store, and other `Rdf4jRepository` instances over the same RDF4J repository; SPARQL sees it like any stored statement (the implied triple is only synthesized by graph reads).
- **Blank-node graph names are skolemized on load**: `parseDataset` replaces each distinct blank graph name of a TriG / N-Quads document (`_:g { ... }`, `[] { ... }`, an N-Quads graph label `_:g`) with an IRI `urn:kastor:skolem:<load>:<blank node id>` (one random `<load>` id per load), like the Jena provider, so the graph is reachable through `listGraphs()` / `getGraph()`. Separate loads never share a skolem graph. SPARQL `UPDATE` does the same when the request ends: a graph created under a blank-node name by `LOAD` of a quad document or by `INSERT { GRAPH ?g { ... } }` with `?g` bound to a blank node becomes `urn:kastor:skolem:<load>:<blank node id>` (**behaviour change:** it used to stay a blank-node context that `listGraphs()` did not list). A repository created by Kastor therefore never holds a blank-node graph. Blank-node contexts that other RDF4J code wrote to a wrapped store are not graphs of the Kastor dataset: `listGraphs()`, `GRAPH` in every query and update, and patterns outside `GRAPH` all ignore them (**behaviour change:** a query or update that read only inside `GRAPH` used to see them); only `serializeDataset` still writes their statements (with a blank graph label, which `parseDataset` skolemizes).
- **Dataset serialization writes the graph view**: `serializeDataset` writes every graph as the graph API returns it - a statement with an RDF-star subject in its reified form (`_:r p o` plus `_:r rdf:reifies <<( s p o )>>`), each `rdf:reifies` triple once per graph - so parsing the document into a fresh repository gives graphs isomorphic to the original ones. **Behaviour change:** the stored statements used to be exported as they are, which wrote the quoted triple as a subject *and* an explicit `rdf:reifies` statement; after parsing, the graph had two reifiers for one triple. Explicit statements only are written (also on an inference repository). Limitation of Rio: a blank node that occurs both inside a triple term and outside of it (for example the reifier of an annotation on an annotated triple) is not one node after a round trip, because Rio writes a triple term as an `urn:rdf4j:triple:` IRI and keeps the labels inside it as they are; this also holds for `serializeGraph`.
- **Graph handles** returned by `defaultGraph` / `getGraph(name)` / `editGraph(name)` are equal (`equals` / `hashCode`) when they denote the same graph of the same repository object, so they can be used as cache keys. On a repository that Kastor created and fully controls (the `memory`, `native`, `*-star` and `*-shacl` variants) they also implement `VersionedRdfGraph`: `modificationStamp` changes with every write through the repository (graph API, SPARQL `UPDATE`, dataset load, `clear`, `removeGraph`), with the commit of a transaction and with a rollback, and never repeats; it is one counter for the whole repository, so a write to any graph changes it. Read the stamp before the content it describes. Graphs of a wrapped repository (other code may write to the store) and of the `*-rdfs` variants (entailed statements) do not claim a stamp.
- **Strict graph reads** (default): a graph read that meets a statement Kastor cannot represent (for example a malformed language tag written by other RDF4J code) fails with `IllegalArgumentException`. To skip such statements with a logged warning, wrap a store with `Rdf4jRepository(repository, inference = false, lenientRead = true)` or pass the `lenientRead` option (`RdfConfig(providerId = "rdf4j", variantId = "native", options = mapOf("location" to "/data/rdf4j", "lenientRead" to "true"))`). In lenient mode `size()` counts only the statements reads return. SPARQL results are not affected.
- **Language-tag case on NativeStore**: Kastor compares tags ignoring case, but `NativeStore` matches a literal by its exact tag bytes once its small value-id cache misses (after a restart or an eviction). Kastor graph lookups and removals (`hasTriple`, `find`, `removeTriple`, `removeTriples`) fall back to a scan of the `(subject, predicate, *)` statements, so they still ignore tag case. SPARQL evaluated by RDF4J has no such fallback: a query literal such as `"x"@en-gb` may not match a stored `"x"@en-GB`. Memory stores ignore tag case.
- **Initial bindings**: `withSelectRows(query, bindings, timeout)` substitutes IRI and literal bindings into the query text with the same rules as the Jena provider (Jena's query substitution) and the SPARQL endpoint adapter, so all three return the same rows: a binding restricts the query before aggregation, `LIMIT` and `FILTER`, a projected bound variable is bound in every row, `SELECT *` does not return it, and `BOUND(?v)` is true. **Behaviour change:** queries that assign a bound variable (`BIND(... AS ?v)`, `(expr AS ?v)`, `VALUES ?v`) or use it inside a sub-select that does not project it now throw `IllegalArgumentException` (previously RDF4J's `setBinding` accepted them with different results). Blank nodes, triple terms and directional literals are still bound with RDF4J's native `setBinding`. RDF4J's SPARQL parser decodes `\uXXXX` / `\UXXXXXXXX` escapes over the whole query text before parsing, and the substitution does the same. A triple-term binding that contains a blank node or a directional language string (which RDF4J can neither spell in the query nor bind natively) is rejected with `IllegalArgumentException`; bind its components to separate variables instead. An internal assertion failure of RDF4J's evaluator surfaces as `RdfQueryException` for every SPARQL operation (`select`, `ask`, `construct`, `describe`, `update` and the scoped variants); other JVM errors (out of memory, stack overflow) propagate unchanged.
- **Query dataset**: outside `GRAPH`, a SPARQL query reads the repository's default graph only (the statements without a context), like `repo.defaultGraph` and like the Jena provider; inside `GRAPH` it reads the named graphs. **Behaviour change:** RDF4J's own default is to evaluate a query that declares no dataset against the union of all contexts, so `SELECT ?o { ?s ?p ?o }` used to return the triples of every named graph as well; query them with `GRAPH ?g { ... }` or `FROM <graph>`. Details: a query with its own `FROM` / `FROM NAMED` keeps the dataset it declares; the named graphs of a query that reads inside `GRAPH` are the IRI-named contexts (`listGraphs()`). Enumerating them is expensive on a memory store (it walks every IRI and blank node of the store): a repository created by Kastor enumerates them once and again only after a write (also inside a transaction, where the list is reused until the transaction writes), and not at all for a query that only reads inside `GRAPH`; a wrapped store, which other code may change, is enumerated for every query that reads inside `GRAPH`.
- **`DESCRIBE`** returns the concise bounded description of each selected resource, exactly as the Jena provider does: the statements whose subject is the resource and, recursively, those of the blank nodes that are objects of described statements (cycles end). The resources are the IRIs of the `DESCRIBE` clause (whatever the `WHERE` clause matches) and the IRIs and blank nodes the `WHERE` clause, with its solution modifiers, binds to the described variables; literals and triple terms are skipped. The description is read from the default graph of the query's dataset (the repository's default graph, or the merge of the `FROM` graphs) as the graph API returns it: RDF-star subjects as reifier blank nodes with one `rdf:reifies` triple, triple terms as objects that are not followed, entailed statements on an inference repository. **Behaviour change:** RDF4J's own `DESCRIBE` is symmetric and used to return the statements that point at the resource as well (`<x> :knows <s>` for `DESCRIBE <s>`); query them with `CONSTRUCT`. A wrapped remote repository returns the description its server computes.
- **`CONSTRUCT`** returns an `rdf:reifies` triple once when it is both stored and implied by a statement about the quoted triple. Only stores evaluated by an RDF4J Sail are affected: a wrapped remote repository (HTTP or SPARQL endpoint) keeps its server's dataset.
- **Update dataset**: SPARQL `UPDATE` follows the same contract, like the Jena provider. Outside `GRAPH`, the `WHERE` clause of `DELETE` / `INSERT` (and `DELETE WHERE`) matches the default graph only, and a `DELETE` / `INSERT` template or a `DELETE DATA` block without `GRAPH` changes the default graph only. **Behaviour change:** RDF4J's own default is to match such a `WHERE` clause in every context and to delete a template triple from every context, so `DELETE { ?s ?p ?o } WHERE { ?s ?p ?o }` or `DELETE WHERE { ?s ?p ?o }` used to empty every named graph and `DELETE DATA { <s> <p> <o> }` used to remove the triple from every named graph; address named graphs with `GRAPH <g> { ... }`, `WITH <g>` or `USING <g>`. Details: `WITH <g>` makes `g` the default graph of the templates and of `WHERE`, and `GRAPH` inside that `WHERE` still reads the other named graphs (whether an operation has a `USING` clause is read from the parsed request, per operation: the word in a literal, an IRI, a comment or another operation of the request does not count); `USING` / `USING NAMED` define the dataset of `WHERE` only (with only `USING NAMED` its default graph is empty, with only `USING` it has no named graphs) while templates without `GRAPH` keep writing the default graph, or the `WITH` graph; each operation of a multi-operation request has its own dataset and sees the graphs created by the operations before it. As for queries, the named graphs of a `WHERE` that reads inside `GRAPH` are the IRI-named contexts (never a blank-node context), and a wrapped remote repository keeps its server's behaviour. `INSERT DATA`, `LOAD`, `CLEAR`, `DROP`, `CREATE`, `COPY`, `MOVE` and `ADD` are unchanged.
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




