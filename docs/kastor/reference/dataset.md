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


