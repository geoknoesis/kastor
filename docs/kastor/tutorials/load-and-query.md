## Load and Query

{% include version-banner.md %}

> **Documentation mode: Tutorial** — short path from empty repo to first **SELECT**. Terms: [**Glossary**](../concepts/glossary.md). Tasks without narration: [**How-to guides**](../guides/README.md).

### Goal

Create a **Jena**-backed in-memory repository, load Turtle bytes into the default graph, and run one **SPARQL** query.

### Prerequisites

- Dependencies: `rdf-core`, `rdf-jena` at **`0.2.0`** (see [Installation](../getting-started/installation.md)).

### Step 1: Start with an in-memory repository

```kotlin
val api = Rdf.repository {
  providerId = "jena"
  variantId = "memory"
}
val repo = api.repository
```

### Step 2: Load Turtle content

```kotlin
val turtle = """
@prefix ex: <urn:ex:> .
ex:s ex:p "o" .
""".trimIndent()
repo.beginTransaction()
repo.readGraph(null, turtle.byteInputStream(), "TURTLE")
repo.commit(); repo.end()
```

### Step 3: Run a SELECT query

```kotlin
val rows = repo.select(SparqlSelectQuery("SELECT ?s WHERE { ?s <urn:ex:p> ?o }"))
```

## Verify

You should obtain at least one binding for `?s` pointing at `urn:ex:s` (exact representation may vary by serializer).

## Next steps

- **How-to:** [Parse RDF](../guides/how-to-parse-rdf.md), [Use datasets](../guides/how-to-use-datasets.md)
- **Explanation:** [SPARQL Fundamentals](../concepts/sparql-fundamentals.md)
- **Reference:** [Repository / transactions](../api/repository-manager.md)
