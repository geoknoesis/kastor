# JSON-LD Compaction and Framing

{% include version-banner.md %}

## Overview

JSON-LD provides two important transformations:
- **Compaction**: Reduces verbosity by using terms from a context
- **Framing**: Restructures JSON-LD to match a specific frame pattern

This guide explains how Kastor handles JSON-LD compaction and framing, including important considerations about data preservation.

## ⚠️ Important: Lossless Behavior

**JSON-LD compaction and framing are NOT always lossless.**

When you compact or frame JSON-LD data:
- **Compaction** may lose some information if the context doesn't include all terms
- **Framing** restructures data according to the frame, which may filter or reorganize information
- **Round-trip conversion** (RDF → JSON-LD → compact → expand → RDF) may not preserve all triples

**Best Practice**: If you need to preserve all RDF data, use expanded JSON-LD or other RDF formats (Turtle, N-Triples) instead of compacted/framed JSON-LD.

## Compaction

Compaction uses a JSON-LD context to shorten IRIs and simplify the JSON structure.

### Basic Compaction

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.RdfFormat

val graph = Rdf.graph {
    val person = iri("http://example.org/person/alice")
    person[iri("http://xmlns.com/foaf/0.1/name")] = "Alice"
    person[iri("http://xmlns.com/foaf/0.1/age")] = 30
}

// Compact with a context
val context = """
{
  "@context": {
    "foaf": "http://xmlns.com/foaf/0.1/",
    "name": "foaf:name",
    "age": "foaf:age"
  }
}
""".trimIndent()

val compacted = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdContext = context
    jsonLdCompact = true
}

println(compacted)
// Output: {"@context": {...}, "@id": "http://example.org/person/alice", "name": "Alice", "age": 30}
```

### What Gets Preserved

Compaction preserves:
- ✅ All RDF triples (subject, predicate, object)
- ✅ Literal datatypes and language tags
- ✅ Blank node identifiers
- ✅ Graph structure

### What May Be Lost

Compaction may lose:
- ⚠️ Terms not in the context (will use full IRIs)
- ⚠️ Ordering of properties (JSON objects are unordered)
- ⚠️ Some structural information if context mappings are incomplete

### Example: Incomplete Context

```kotlin
val graph = Rdf.graph {
    val person = iri("http://example.org/person/alice")
    person[iri("http://xmlns.com/foaf/0.1/name")] = "Alice"
    person[iri("http://example.org/custom/prop")] = "Value"  // Not in context
}

val context = """
{
  "@context": {
    "foaf": "http://xmlns.com/foaf/0.1/",
    "name": "foaf:name"
  }
}
""".trimIndent()

val compacted = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdContext = context
    jsonLdCompact = true
}

// The custom property will use full IRI since it's not in the context
// Output includes: "http://example.org/custom/prop": "Value"
```

## Framing

Framing restructures JSON-LD to match a specific frame pattern, which can filter and reorganize data.

### Basic Framing

```kotlin
val frame = """
{
  "@context": {
    "foaf": "http://xmlns.com/foaf/0.1/",
    "name": "foaf:name",
    "age": "foaf:age"
  },
  "@type": "foaf:Person",
  "name": {},
  "age": {}
}
""".trimIndent()

val framed = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdFrame = frame
}

// Output matches the frame structure
```

### What Gets Preserved

Framing preserves:
- ✅ Data that matches the frame pattern
- ✅ Properties explicitly included in the frame
- ✅ Type information specified in the frame

### What May Be Lost

Framing may lose:
- ⚠️ Properties not included in the frame
- ⚠️ Data that doesn't match the frame pattern
- ⚠️ Blank nodes not referenced in the frame
- ⚠️ Triples in named graphs if frame doesn't specify them

### Example: Frame Filtering

```kotlin
val graph = Rdf.graph {
    val person = iri("http://example.org/person/alice")
    person[iri("http://xmlns.com/foaf/0.1/name")] = "Alice"
    person[iri("http://xmlns.com/foaf/0.1/age")] = 30
    person[iri("http://xmlns.com/foaf/0.1/email")] = "alice@example.com"
}

// Frame only includes name and age
val frame = """
{
  "@context": {
    "foaf": "http://xmlns.com/foaf/0.1/",
    "name": "foaf:name",
    "age": "foaf:age"
  },
  "name": {},
  "age": {}
}
""".trimIndent()

val framed = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdFrame = frame
}

// Email property is NOT included in the framed output
// This is intentional - framing filters data to match the frame
```

## Round-Trip Considerations

### Expanded JSON-LD (Lossless)

Expanded JSON-LD preserves all RDF data:

```kotlin
// Serialize to expanded JSON-LD (no compaction)
val expanded = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdCompact = false  // Default
}

// Parse back
val graph2 = Rdf.parse(expanded, RdfFormat.JSON_LD)

// All triples are preserved
assertEquals(graph.getTriples().toSet(), graph2.getTriples().toSet())
```

### Compacted JSON-LD (Usually Lossless)

Compacted JSON-LD with a complete context is usually lossless:

```kotlin
// Create complete context with all terms
val completeContext = """
{
  "@context": {
    "foaf": "http://xmlns.com/foaf/0.1/",
    "name": "foaf:name",
    "age": "foaf:age",
    "email": "foaf:email"
  }
}
""".trimIndent()

val compacted = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdContext = completeContext
    jsonLdCompact = true
}

// Parse back
val graph2 = Rdf.parse(compacted, RdfFormat.JSON_LD)

// Triples are preserved IF context is complete
// Note: Property order may differ, but data is preserved
```

### Framed JSON-LD (May Lose Data)

Framed JSON-LD may lose data that doesn't match the frame:

```kotlin
val framed = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdFrame = frame  // Frame may not include all properties
}

// Parse back
val graph2 = Rdf.parse(framed, RdfFormat.JSON_LD)

// Some triples may be missing if frame filtered them out
// graph2.getTriples().size <= graph.getTriples().size
```

## Best Practices

### 1. Use Expanded JSON-LD for Data Preservation

If you need to preserve all RDF data:

```kotlin
// Use expanded JSON-LD (default)
val jsonLd = graph.serialize(RdfFormat.JSON_LD)
// No compaction = all data preserved
```

### 2. Use Complete Contexts for Compaction

If using compaction, ensure your context includes all terms:

```kotlin
// Extract all predicates from graph
val predicates = graph.getTriples()
    .map { it.predicate.value }
    .distinct()

// Build complete context
val context = buildJsonLdContext(predicates)

val compacted = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdContext = context
    jsonLdCompact = true
}
```

### 3. Use Framing for Presentation, Not Storage

Framing is useful for:
- ✅ API responses (filtering to specific fields)
- ✅ UI display (showing only relevant properties)
- ✅ Data transformation (restructuring for specific use cases)

**Don't use framing for:**
- ❌ Data storage (may lose information)
- ❌ Round-trip conversion (data may not be preserved)
- ❌ Data exchange (use expanded or compacted JSON-LD)

### 4. Verify Round-Trip When Needed

If you need to ensure data preservation:

```kotlin
fun verifyRoundTrip(graph: RdfGraph): Boolean {
    val jsonLd = graph.serialize(RdfFormat.JSON_LD)
    val graph2 = Rdf.parse(jsonLd, RdfFormat.JSON_LD)
    
    val originalTriples = graph.getTriples().toSet()
    val roundTripTriples = graph2.getTriples().toSet()
    
    return originalTriples == roundTripTriples
}
```

## Provider Support

JSON-LD compaction and framing support depends on the underlying provider:

- **Jena**: Supports JSON-LD serialization, but compaction/framing options may not be fully implemented yet
- **RDF4J**: Supports JSON-LD serialization, but compaction/framing options may not be fully implemented yet

**Current Status**: The `SerializationOptions` API includes `jsonLdContext`, `jsonLdCompact`, and `jsonLdFrame` parameters, but provider implementations may not yet fully support all options. Check provider capabilities before relying on compaction/framing.

## Related Documentation

- [Serialization Guide](how-to-serialize-rdf.md) - General serialization guide
- [RDF Formats](../concepts/rdf-fundamentals.md#formats) - RDF format overview
- [JSON-LD Specification](https://www.w3.org/TR/json-ld11/) - Official JSON-LD specification

