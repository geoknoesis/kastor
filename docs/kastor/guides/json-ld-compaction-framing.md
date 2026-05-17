# JSON-LD Compaction and Framing

{% include version-banner.md %}

> **Documentation mode: Explanation** — how compaction/framing interact with the RDF graph. **Task:** serialization options → [How to Serialize RDF](how-to-serialize-rdf.md). **Concepts:** [RDF fundamentals](../concepts/rdf-fundamentals.md) (serialization formats).

## Problem

- Emit **compact** or **framed** JSON-LD for APIs or UI consumption **without silently dropping triples** that matter downstream.

## Important: Not always lossless

JSON-LD **compaction** and **framing** are presentation transforms:

- **Compaction** depends entirely on the **`@context`**. Predicates absent from the context remain as full IRIs but ordering and grouping still change; edge cases around lists, nesting, and `@graph` can surprise readers expecting isomorphism with the RDF graph.
- **Framing** **filters and reshapes** nodes to match the frame—properties omitted from the frame may disappear from the output document even though they existed in the RDF graph.
- **Round-trip** RDF → JSON-LD → RDF may therefore **lose** information unless you constrain options carefully.

If you need a canonical, faithful interchange representation, prefer **expanded JSON-LD**, **Turtle**, or **N-Triples** rather than compacted/framed JSON-LD alone.

## Prerequisites

- **`rdf-core`** and a provider that implements **`SerializationOptions`** fields **`jsonLdContext`**, **`jsonLdCompact`**, and **`jsonLdFrame`** (coverage varies—see **Provider support** below).

## Steps

### Step 1: Compact with a `@context`

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat
import com.geoknoesis.kastor.rdf.iri

val graph = Rdf.graph {
    val person = iri("http://example.org/person/alice")
    person[iri("http://xmlns.com/foaf/0.1/name")] = "Alice"
    person[iri("http://xmlns.com/foaf/0.1/age")] = 30
}

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
// Example shape: {"@context": {...}, "@id": "http://example.org/person/alice", "name": "Alice", "age": 30}
```

**Incomplete contexts** still serialize little-used predicates—but readers expecting only compact keys may overlook IRIs not mapped in **`@context`**:

```kotlin
val thinContextGraph = Rdf.graph {
    val person = iri("http://example.org/person/alice")
    person[iri("http://xmlns.com/foaf/0.1/name")] = "Alice"
    person[iri("http://example.org/custom/prop")] = "Value"
}

val thinContext = """
{
  "@context": {
    "foaf": "http://xmlns.com/foaf/0.1/",
    "name": "foaf:name"
  }
}
""".trimIndent()

val compactedThin = thinContextGraph.serialize(RdfFormat.JSON_LD) {
    jsonLdContext = thinContext
    jsonLdCompact = true
}

// Custom predicate typically appears under its full IRI key:
// "http://example.org/custom/prop": "Value"
```

### Step 2: Frame for presentation APIs

Framing picks subtrees that match the frame pattern—anything **not described by the frame** can be omitted even though triples exist in the RDF graph.

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
```

```kotlin
val richGraph = Rdf.graph {
    val person = iri("http://example.org/person/alice")
    person[iri("http://xmlns.com/foaf/0.1/name")] = "Alice"
    person[iri("http://xmlns.com/foaf/0.1/age")] = 30
    person[iri("http://xmlns.com/foaf/0.1/email")] = "alice@example.com"
}

val frameNameAgeOnly = """
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

val framedSubset = richGraph.serialize(RdfFormat.JSON_LD) {
    jsonLdFrame = frameNameAgeOnly
}

// Email triple exists in RDF but may be absent from framed JSON-LD output.
```

### Step 3: Prefer expanded JSON-LD (or another RDF syntax) for fidelity

```kotlin
// Expanded JSON-LD — default compaction flag off
val expanded = graph.serialize(RdfFormat.JSON_LD) {
    jsonLdCompact = false  // default
}

val roundTrip = Rdf.parse(expanded, RdfFormat.JSON_LD)
// Compare graphs with your test helpers (blank nodes may still differ)
```

When you must compact, enumerate **every predicate** you care about in **`@context`** (generate JSON programmatically from your vocabulary IRIs if the list is large—there is no single built-in **`buildJsonLdContext`** helper in Kastor).

## Validation

When fidelity matters, assert graph equality (or isomorphism-aware helpers from **`rdf-testkit`**) **before and after** JSON-LD transforms:

```kotlin
fun graphsEqualModuloBlankNodes(a: com.geoknoesis.kastor.rdf.RdfGraph, b: com.geoknoesis.kastor.rdf.RdfGraph): Boolean =
    a.getTriples().toSet() == b.getTriples().toSet() // replace with testkit isomorphism if you have blank nodes
```

Expect **`graphsEqualModuloBlankNodes(graph, Rdf.parse(compacted, RdfFormat.JSON_LD))`** only when the context truly covers every triple you need.

## Provider support

JSON-LD compaction and framing depend on the underlying implementation behind **`serialize`**:

- **Jena** and **RDF4J** integrations evolve independently—confirm behavior against your pinned versions.
- Treat **`jsonLdContext`**, **`jsonLdCompact`**, and **`jsonLdFrame`** as **best-effort hooks**: verify with integration tests before relying on them in production responses.

## Troubleshooting

- **Missing properties after framing:** extend the frame or stop using framing for that payload—framing is inherently selective.
- **Round-trip shrinkage:** compare **`getTriples()`** counts before/after parse; fall back to Turtle/N-Triples for storage.
- **Provider ignores options:** feature gaps surface as “compact JSON looks identical to expanded”—inspect **`SerializationOptions`** handling in your provider release notes.

## Related

- [How to Serialize RDF](how-to-serialize-rdf.md)
- [JSON-LD 1.1 specification](https://www.w3.org/TR/json-ld11/)
