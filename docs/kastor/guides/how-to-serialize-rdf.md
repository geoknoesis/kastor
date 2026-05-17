# How to Serialize a Graph to RDF Formats

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** [RDF Fundamentals](../concepts/rdf-fundamentals.md). **Reference:** `serialize`, `RdfFormat`, `SerializationOptions` → [Core API](../api/core-api.md).

## Problem

Turn an in-memory **`RdfGraph`** into Turtle, JSON-LD, RDF/XML, or N-Triples **strings** for logging, HTTP responses, or files.

## Prerequisites

- **`rdf-core`** plus a **provider** that supports serialization for the target format (Jena/RDF4J modules typically do).

## Steps

### Step 1: Build a graph

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF

val graph = Rdf.graph {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice Johnson"
}
```

### Step 2: Serialize to Turtle

```kotlin
import com.geoknoesis.kastor.rdf.RdfFormat

val turtle = graph.serialize(RdfFormat.TURTLE)
println(turtle)
```

### Step 3: Serialize to JSON-LD

```kotlin
val jsonld = graph.serialize(RdfFormat.JSON_LD)
println(jsonld)
```

### Step 4: Serialize to other formats

```kotlin
val rdfXml = graph.serialize(RdfFormat.RDF_XML)
val nTriples = graph.serialize(RdfFormat.N_TRIPLES)
```

### Step 5: Serialize with options

You can customize serialization behavior using `SerializationOptions`:

```kotlin
import com.geoknoesis.kastor.rdf.SerializationOptions

// Using explicit options
val options = SerializationOptions(
    prettyPrint = true,
    baseUri = "http://example.org/",
    prefixMappings = mapOf("ex" to "http://example.org/"),
    lineWidth = 120
)
val turtle = graph.serialize(RdfFormat.TURTLE, options)

// Using builder lambda (fluent API)
val turtle2 = graph.serialize(RdfFormat.TURTLE) {
    prettyPrint = true
    baseUri = "http://example.org/"
    prefix("ex", "http://example.org/")
    prefix("foaf", "http://xmlns.com/foaf/0.1/")
    lineWidth = 120
}

// Using predefined options
val compact = graph.serialize(RdfFormat.TURTLE, SerializationOptions.COMPACT)
val pretty = graph.serialize(RdfFormat.TURTLE, SerializationOptions.PRETTY)
```

## Reference: SerializationOptions (quick lookup)

Use **`SerializationOptions`** for pretty-printing, base URI, prefixes, and JSON-LD compaction. Authoritative defaults and signatures: **Reference** [Core API](../api/core-api.md) / `SerializationOptions` in source.

- **prettyPrint**, **lineWidth** — layout
- **baseUri**, **prefixMappings** — IRIs and QName prefixes
- **jsonLdContext**, **jsonLdCompact**, **jsonLdFrame** — JSON-LD only

**⚠️ Important**: JSON-LD compaction and framing may not preserve all RDF data. See [JSON-LD Compaction and Framing Guide](json-ld-compaction-framing.md).

## Validation

Example Turtle output:

```
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

<http://example.org/alice> foaf:name "Alice Johnson" .
```

## Troubleshooting

- **`RdfFormatException`** — no serializer for that format on the classpath; add `rdf-jena` / `rdf-rdf4j` or register a provider.
- **Unexpected JSON-LD** — check compaction/framing flags and read the JSON-LD guide above.

## Supported formats (quick lookup)

- **Turtle**: `RdfFormat.TURTLE`
- **JSON-LD**: `RdfFormat.JSON_LD`
- **RDF/XML**: `RdfFormat.RDF_XML`
- **N-Triples**: `RdfFormat.N_TRIPLES`

## Related tasks

- [Parse RDF](how-to-parse-rdf.md)
- [Test RDF graphs](how-to-test-rdf-graphs.md)
- [Named graphs](how-to-named-graphs.md)
