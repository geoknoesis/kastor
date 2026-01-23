# How to Parse RDF into a Graph

{% include version-banner.md %}

## What you'll learn
- Parse RDF from strings, files, or URLs into graphs
- Use format-based API that works with any provider
- Use type-safe `RdfFormat` for parsing

## Prerequisites
- A provider that supports parsing (automatically discovered)

## Step 1: Parse RDF from a string

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

## Step 2: Parse RDF from a file

```kotlin
val graph = Rdf.parseFromFile("data.ttl", format = RdfFormat.TURTLE)

val graph2 = Rdf.parseFromFile("data.jsonld", format = RdfFormat.JSON_LD)
```

## Step 3: Parse RDF from a URL

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

## Step 4: Add parsed graph to a repository

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

## Expected output

```
Parsed triples: 2
Alice Johnson is 30 years old
```

## Supported formats

The following formats are supported:

- **Turtle**: `RdfFormat.TURTLE`
- **JSON-LD**: `RdfFormat.JSON_LD`
- **RDF/XML**: `RdfFormat.RDF_XML`
- **N-Triples**: `RdfFormat.N_TRIPLES`

## Notes
- The API is **provider-agnostic** - it automatically discovers and uses available providers
- Use `RdfFormat` enum for **type-safe** format specification
- If no provider supports the requested format, a `RdfFormatException` is thrown
- The parsing automatically uses the first available provider that supports the format
- File paths are relative to the current working directory
- URL parsing includes timeout protection (30 seconds)


