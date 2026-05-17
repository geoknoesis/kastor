# How to Work with Named Graphs

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** datasets, default vs named graphs → [Datasets](../concepts/datasets.md), [**Glossary**](../concepts/glossary.md). **Reference:** graph lifecycle and SPARQL → [Core API](../api/core-api.md).

## Problem

- Create a **named graph** in a repository, **add triples** scoped to that graph, and **query** it with SPARQL using a `GRAPH` block.

## Prerequisites

- **`rdf-core`** plus a provider that supports named graphs and SPARQL (for example **`rdf-jena`** or **`rdf-rdf4j`**). Align versions via the [Kastor BOM](../getting-started/installation.md) when possible.

## Steps

### Step 1: Create a named graph

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri

val repo = Rdf.memory()
val graphName = iri("http://example.org/graphs/metadata")

repo.createGraph(graphName)
```

### Step 2: Add data to the named graph

```kotlin
import com.geoknoesis.kastor.rdf.vocab.DCTERMS

repo.addToGraph(graphName) {
    val dataset = iri("http://example.org/dataset/1")
    dataset has DCTERMS.title with "Sample Dataset"
}
```

### Step 3: Query the named graph

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery
import com.geoknoesis.kastor.rdf.vocab.DCTERMS

val results = repo.select(SparqlSelectQuery("""
    SELECT ?title WHERE {
        GRAPH ${graphName} {
            <http://example.org/dataset/1> ${DCTERMS.title} ?title .
        }
    }
"""))

results.forEach { row ->
    println(row.getString("title"))
}
```

## Validation

You should see:

```
Sample Dataset
```

## Troubleshooting

- **Empty `GRAPH` results:** Confirm the SPARQL graph IRI matches the `iri(...)` used with `createGraph` / `addToGraph`.
- **Remote endpoints:** Named graph visibility depends on the server; see [Remote SPARQL endpoint](../tutorials/remote-endpoint.md).

## Related

- [How to Use Datasets](how-to-use-datasets.md)
- [SPARQL fundamentals](../concepts/sparql-fundamentals.md)
