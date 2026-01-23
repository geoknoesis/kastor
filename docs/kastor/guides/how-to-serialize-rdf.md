# How to Serialize a Graph to RDF Formats

{% include version-banner.md %}

## What you'll learn
- Serialize a `RdfGraph` to Turtle, JSON-LD, or other formats
- Use format-based API that works with any provider
- Use type-safe `RdfFormat` for serialization

## Prerequisites
- A provider that supports serialization (automatically discovered)

## Step 1: Build a graph

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF

val graph = Rdf.graph {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice Johnson"
}
```

## Step 2: Serialize to Turtle

```kotlin
import com.geoknoesis.kastor.rdf.RdfFormat

val turtle = graph.serialize(RdfFormat.TURTLE)
println(turtle)
```

## Step 3: Serialize to JSON-LD

```kotlin
val jsonld = graph.serialize(RdfFormat.JSON_LD)
println(jsonld)
```

## Step 4: Serialize to other formats

```kotlin
val rdfXml = graph.serialize(RdfFormat.RDF_XML)
val nTriples = graph.serialize(RdfFormat.N_TRIPLES)
```

## Expected output

```
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

<http://example.org/alice> foaf:name "Alice Johnson" .
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
- The serialization automatically uses the first available provider that supports the format

