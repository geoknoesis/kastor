# Datasets and Named Graphs

{% include version-banner.md %}

Kastor models SPARQL datasets explicitly. A dataset is **not** a single graph—it is:
- a **default graph** (which may be the union of multiple graphs), and
- **named graphs** that are addressable by IRI in `GRAPH <name> { ... }` patterns.

## What a dataset is

In SPARQL 1.1, a dataset is the query scope:
- **Default graph**: what you query when no `GRAPH` clause is used.
- **Named graphs**: graphs you query with `GRAPH <name>`.

The dataset is **a view** over graphs. It does not imply storage or mutation rules.

## Dataset vs Repository

Kastor distinguishes:
- **`Dataset`**: read‑only query scope (SPARQL semantics).
- **`RdfRepository`**: mutable storage that **implements** `Dataset`.

Use a repository when you need to write. Use a dataset when you want to query
across multiple graphs or repositories with explicit SPARQL semantics.

## Default graph union

When a dataset has multiple default graphs, the default graph is the **union** of
those graphs (SPARQL `FROM` semantics). This is explicit and predictable:
no hidden inference, no implicit union unless you add graphs.

## Named graphs

Named graphs are accessed via `GRAPH <name> { ... }` in SPARQL. In Kastor,
named graphs are stored in a map of `Iri` to `RdfGraph`.

## Dataset in Kastor

Kastor exposes:
- `Dataset` interface for read‑only access.
- `DatasetBuilder` + `Dataset { ... }` factory.
- `RdfRepository` which is both a dataset **and** a mutable store.

## Example: build a dataset from two repositories

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF

val peopleRepo = Rdf.memory()
val orgRepo = Rdf.memory()

peopleRepo.add {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice"
}

orgRepo.add {
    val acme = iri("http://example.org/org/acme")
    acme has FOAF.name with "ACME Corp"
}

val peopleGraph = iri("http://example.org/graphs/people")
val orgGraph = iri("http://example.org/graphs/orgs")

val dataset = Dataset {
    defaultGraph(peopleRepo.defaultGraph)
    namedGraph(peopleGraph, peopleRepo, null)
    namedGraph(orgGraph, orgRepo, null)
}
```

## Querying a dataset

```kotlin
val results = dataset.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        GRAPH ${peopleGraph} {
            ?person ${FOAF.name} ?name .
        }
    }
"""))
```

## Related guides
- [How to Work with Named Graphs](../guides/how-to-named-graphs.md)
- [How to Use Datasets](../guides/how-to-use-datasets.md)


