# How to Parse RDF into a Graph

{% include version-banner.md %}

## What you'll learn
- Load RDF from a file or URL into a `RdfGraph`
- Add parsed triples to a repository

## Prerequisites
- Add the Jena provider dependency:

```kotlin
dependencies {
    implementation("com.geoknoesis.kastor:rdf-jena:0.1.0")
}
```

## Step 1: Parse RDF from a file

```kotlin
import com.geoknoesis.kastor.rdf.jena.JenaBridge

val graph = JenaBridge.fromFile("data.ttl", format = "TURTLE")
println("Parsed triples: ${graph.getTriples().size}")
```

## Step 2: Parse RDF from a URL

```kotlin
val remoteGraph = JenaBridge.fromUrl(
    "https://example.org/data.ttl",
    format = "TURTLE"
)
```

## Step 3: Add parsed triples to a repository

```kotlin
import com.geoknoesis.kastor.rdf.Rdf

val repo = Rdf.memory()
repo.addTriples(graph.getTriples())
```

## Expected output

```
Parsed triples: 42
```

## Notes
- Parsing is provided by the Jena bridge, so you must include `rdf-jena`.
- Supported formats: `"TURTLE"`, `"RDF/XML"`, `"N-TRIPLES"`, `"JSON-LD"`.

