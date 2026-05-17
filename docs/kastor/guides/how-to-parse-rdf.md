# How to Parse RDF into a Graph

{% include version-banner.md %}

> **Documentation mode: How-to guide** — task-focused steps. **Explanation:** [RDF Fundamentals](../concepts/rdf-fundamentals.md). **Reference:** parsing overloads and formats → [API](../api/api-reference.md), `RdfFormat`.

## Problem

Load RDF from **strings**, **files**, or **URLs** into a Kastor **graph** (and optionally merge into a **repository** for querying).

## Prerequisites

- A JVM dependency that includes **`rdf-core`** and at least one **provider** that supports the format (e.g. `rdf-jena` / `rdf-rdf4j`). Providers are usually discovered automatically on JVM.

## Steps

### Step 1: Parse RDF from a string

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat

val turtleData = """
    @prefix foaf: <http://xmlns.com/foaf/0.1/> .
    <http://example.org/alice> foaf:name "Alice Johnson" ;
                                foaf:age 30 .
"""

val graph = Rdf.parse(turtleData, format = RdfFormat.TURTLE)
println("Parsed triples: ${graph.getTriples().size}")
```

### Step 2: Parse RDF from a file

```kotlin
val graph = Rdf.parseFromFile("data.ttl", format = RdfFormat.TURTLE)

val graph2 = Rdf.parseFromFile("data.jsonld", format = RdfFormat.JSON_LD)
```

### Step 3: Parse RDF from a URL

```kotlin
val remoteGraph = Rdf.parseFromUrl(
    "https://example.org/data.ttl",
    format = RdfFormat.TURTLE
)

val remoteGraph2 = Rdf.parseFromUrl(
    "https://example.org/data.jsonld",
    format = RdfFormat.JSON_LD
)
```

If you want to avoid blocking the current thread, use the async variant:

```kotlin
val future = Rdf.parseFromUrlAsync(
    "https://example.org/data.ttl",
    format = RdfFormat.TURTLE
)

val remoteGraphAsync = future.get() // or attach callbacks
```

### Step 4: Add parsed graph to a repository

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.vocab.FOAF

val repo = Rdf.memory()
repo.addTriples(graph.getTriples())

// Now you can query the repository
val results = repo.select(SparqlSelectQuery("""
    SELECT ?name ?age WHERE {
        ?person ${FOAF.name} ?name .
        ?person ${FOAF.age} ?age .
    }
"""))

results.forEach { binding ->
    println("${binding.getString("name")} is ${binding.getInt("age")} years old")
}
```

## Validation

You should see non-empty triple counts after parse; the Step 4 snippet should print bindings similar to:

```
Parsed triples: 2
Alice Johnson is 30 years old
```

## Troubleshooting

- **`RdfFormatException`** — no provider registered for that format; add a backend module or register a provider explicitly ([Android/KMP](../guides/android-kmp.md) often requires explicit registration).
- **Empty graph** — wrong **syntax** vs declared `RdfFormat`, or empty input.
- **URL timeouts** — remote fetch uses timeout protection (~30s); check network or mirror the file locally.

## Supported formats (quick lookup)

The following formats are supported:

- **Turtle**: `RdfFormat.TURTLE`
- **JSON-LD**: `RdfFormat.JSON_LD`
- **RDF/XML**: `RdfFormat.RDF_XML`
- **N-Triples**: `RdfFormat.N_TRIPLES`

## Related tasks

- [Serialize RDF](how-to-serialize-rdf.md)
- [Use datasets / named graphs](how-to-use-datasets.md)
- [Test RDF graphs](how-to-test-rdf-graphs.md)