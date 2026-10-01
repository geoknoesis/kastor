# Dataset Reference

{% include version-banner.md %}

This page documents the `Dataset` API and its SPARQL semantics.

## What a Dataset is

A dataset is a **query scope**:
- **Default graph**: the union of one or more graphs.
- **Named graphs**: addressable via `GRAPH <name>` patterns.

Datasets are **read‑only views** used for query execution. For mutation, use
`RdfRepository` and its graph editors.

## `Dataset` interface

```kotlin
interface Dataset : SparqlQueryable {
    val defaultGraphs: List<RdfGraph>
    val namedGraphs: Map<Iri, RdfGraph>

    override val defaultGraph: RdfGraph

    fun getNamedGraph(name: Iri): RdfGraph?
    fun hasNamedGraph(name: Iri): Boolean
    fun listNamedGraphs(): List<Iri>

    override fun graph(name: Iri): RdfGraph = getNamedGraph(name) ?: defaultGraph
}
```

### Semantics
- `defaultGraphs`: the graphs contributing to the **default graph union**.
- `namedGraphs`: graph map accessed via `GRAPH <name>`.
- `defaultGraph`: the union view used when queries omit `GRAPH`.
- `graph(name)`: returns named graph if present, otherwise the default graph.

### Query semantics
- A query sees the dataset's graphs only: patterns outside `GRAPH` read the default graph union, `GRAPH` patterns read the dataset's named graphs. The contract is the same on every provider (Jena, RDF4J).
- `Dataset { defaultGraph(repo) }` has **no named graphs**: `GRAPH` patterns match nothing, even though the repository has named graphs. The query runs in place (the store is not copied); `GRAPH` inside `SERVICE <endpoint> { ... }` is left to the remote endpoint.
- Queries must not declare `FROM` / `FROM NAMED` (`IllegalArgumentException`): the dataset defines the graphs.
- `DESCRIBE` returns only triples that are in a graph of the dataset. Which of the dataset's graphs an engine describes from is engine-defined (Jena also reads the dataset's named graphs, RDF4J only its default graph).
- Codepoint escapes (`\uXXXX`, `\UXXXXXXXX`) are decoded before the query is analysed and sent, so an escaped keyword (`\u0047RAPH`) is treated as the keyword. A query whose escapes spell a quote, a backslash or a line break, or follow another backslash, cannot be analysed: it is rejected with `IllegalArgumentException` when the dataset is a repository's default graph, and run against a temporary copy of the dataset's graphs otherwise. Use the string escapes `\"`, `\\`, `\n` instead.
- `CONSTRUCT WHERE { GRAPH ... }` (a non-standard short form) is rejected for a dataset without named graphs.

## `DatasetBuilder`

Create datasets using the builder DSL:

```kotlin
val dataset = Dataset {
    defaultGraph(repo.defaultGraph)
    namedGraph(iri("http://example.org/graph"), repo, null)
}
```

### Builder functions
- `defaultGraph(graph: RdfGraph)`
- `defaultGraph(repository: RdfRepository)`
- `namedGraph(name: Iri, graph: RdfGraph)`
- `namedGraph(name: Iri, repository: RdfRepository, sourceGraphName: Iri? = null)`
- `defaultGraphs(vararg graphs: RdfGraph)`
- `namedGraphs(vararg pairs: Pair<Iri, RdfGraph>)`

### Validation
- At least one default graph is required.
- Named graph names must be unique.

## `Dataset { ... }` factory

```kotlin
val dataset = Dataset {
    defaultGraph(repo.defaultGraph)
    namedGraph(iri("http://example.org/graph"), repo.defaultGraph)
}
```

## Related
- [Core API](../api/core-api.md)
- [Repository Reference](repository.md)
- [How to Use Datasets](../guides/how-to-use-datasets.md)


