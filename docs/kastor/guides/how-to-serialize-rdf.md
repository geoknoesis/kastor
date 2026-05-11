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

## Step 5: Serialize with options

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

## Serialization Options

The `SerializationOptions` class provides control over serialization:

- **prettyPrint**: Enable/disable pretty-printed output with indentation (default: `true`)
- **baseUri**: Base URI for resolving relative IRIs (default: `null`)
- **prefixMappings**: Custom prefix mappings (e.g., `"ex" -> "http://example.org/"`)
- **useAbbreviatedSyntax**: Use abbreviated syntax when possible (default: `true`)
- **lineWidth**: Maximum line width for pretty printing, 0 = no limit (default: `80`)
- **jsonLdContext**: JSON-LD context for compaction (JSON-LD format only)
- **jsonLdCompact**: Enable JSON-LD compaction (JSON-LD format only, default: `false`)
- **jsonLdFrame**: JSON-LD frame for framing (JSON-LD format only)

**⚠️ Important**: JSON-LD compaction and framing may not preserve all RDF data. See [JSON-LD Compaction and Framing Guide](json-ld-compaction-framing.md) for details.

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
- Options are optional - default options are used if not specified

