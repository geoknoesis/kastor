# How to Use Datasets

{% include version-banner.md %}

## What you'll learn
- Build a dataset from multiple graphs or repositories
- Query default and named graphs
- Understand default graph union semantics

## Step 1: Prepare repositories

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

## Step 2: Build a dataset

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

## Step 3: Query the default graph

```kotlin
val defaultResults = dataset.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        ?person ${FOAF.name} ?name .
    }
"""))
```

## Step 4: Query a named graph

```kotlin
val namedResults = dataset.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        GRAPH ${peopleGraph} {
            ?person ${FOAF.name} ?name .
        }
    }
"""))
```

## Step 5: Inspect dataset contents

```kotlin
val names = dataset.listNamedGraphs()
println("Named graphs: $names")
println("Has people graph: ${dataset.hasNamedGraph(peopleGraph)}")
```

## Expected output

```
Named graphs: [http://example.org/graphs/people, http://example.org/graphs/orgs]
Has people graph: true
```

## Notes
- `Dataset` is **read‑only**. Use `RdfRepository` for edits.
- The default graph is the **union** of all graphs added via `defaultGraph(...)`.
- `RdfRepository` implements `Dataset`, so you can query repositories as datasets.


