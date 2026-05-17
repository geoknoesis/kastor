# How to Use Datasets

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** what a **dataset** is (default vs named graphs) → [Datasets](../concepts/datasets.md), [**Glossary**](../concepts/glossary.md). **Reference:** `Dataset`, `RdfRepository` query APIs → [Core API](../api/core-api.md).

## Problem

Combine multiple graph sources and run **SPARQL** over the **default graph** (union) and/or specific **named graphs** via `GRAPH { ... }`.

## Prerequisites

- **`rdf-core`** plus at least one provider (examples below use `Rdf.memory()` repositories).

## Steps

### Step 1: Prepare repositories

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
```

### Step 2: Build a dataset

```kotlin
val peopleGraph = iri("http://example.org/graphs/people")
val orgGraph = iri("http://example.org/graphs/orgs")

val dataset = Dataset {
    // Default graph is the union of these graphs
    defaultGraph(peopleRepo.defaultGraph)

    // Named graphs for explicit GRAPH queries
    namedGraph(peopleGraph, peopleRepo, null)
    namedGraph(orgGraph, orgRepo, null)
}
```

### Step 3: Query the default graph

```kotlin
val defaultResults = dataset.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        ?person ${FOAF.name} ?name .
    }
"""))
```

### Step 4: Query a named graph

```kotlin
val namedResults = dataset.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        GRAPH ${peopleGraph} {
            ?person ${FOAF.name} ?name .
        }
    }
"""))
```

### Step 5: Inspect dataset contents

```kotlin
val names = dataset.listNamedGraphs()
println("Named graphs: $names")
println("Has people graph: ${dataset.hasNamedGraph(peopleGraph)}")
```

## Validation

Example:

```
Named graphs: [http://example.org/graphs/people, http://example.org/graphs/orgs]
Has people graph: true
```

## Troubleshooting

- **Triple “missing” in default graph** — confirm it was merged via `defaultGraph(...)`; triples only in a named graph need a `GRAPH` clause unless your union semantics include them by design.
- **Writes ignored** — `Dataset` here is **read-oriented** for federation; mutate underlying **`RdfRepository`** instances, then rebuild or refresh views.

## Related tasks

- [Named graphs](how-to-named-graphs.md)
- [SPARQL bindings & Flow](how-to-sparql-bindings-and-flows.md)
- [Parse RDF](how-to-parse-rdf.md)

## Implementation notes

- `Dataset` composition is **read‑only** at this layer for the pattern above; use **`RdfRepository`** for transactional edits.
- The default graph supplied via `defaultGraph(...)` reflects the **union** you attach there (see [Datasets](../concepts/datasets.md)).
- **`RdfRepository`** implements **`Dataset`** where applicable — you can often query a single repo as a dataset without wrapping.
