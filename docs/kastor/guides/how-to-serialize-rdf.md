# How to Serialize a Graph to RDF Formats

{% include version-banner.md %}

## What you'll learn
- Serialize a `RdfGraph` to Turtle or JSON-LD
- Control output format explicitly

## Prerequisites
- Add the Jena provider dependency:

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:rdf-jena:0.1.0")
}
```

## Step 1: Build a graph

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri

val graph = Rdf.graph {
    val alice = iri("http://example.org/alice")
    alice has "http://xmlns.com/foaf/0.1/name" with "Alice Johnson"
}
```

## Step 2: Serialize to Turtle

```kotlin
import com.geoknoesis.kastor.rdf.jena.JenaBridge

val turtle = JenaBridge.toString(graph, format = "TURTLE")
println(turtle)
```

## Step 3: Serialize to JSON-LD

```kotlin
val jsonld = JenaBridge.toString(graph, format = "JSON-LD")
println(jsonld)
```

## Expected output

```
@prefix foaf: <http://xmlns.com/foaf/0.1/> .

<http://example.org/alice> foaf:name "Alice Johnson" .
```

## Notes
- Serialization is provided by the Jena bridge, so you must include `rdf-jena`.
- Supported formats: `"TURTLE"`, `"RDF/XML"`, `"N-TRIPLES"`, `"JSON-LD"`.

