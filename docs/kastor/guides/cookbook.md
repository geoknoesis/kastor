# Cookbook

{% include version-banner.md %}

> **Documentation mode: Reference** — short, copy-paste recipes. **Learning path:** [Guides hub](guides.md), [How documentation fits together](../getting-started/documentation-guide.md).

## Problem

- Perform common RDF tasks quickly: **discover providers**, **open a repository**, **insert triples**, **load files**, **export JSON-LD**.

## Prerequisites

- **`rdf-core`** plus any provider artifacts you reference (**`rdf-jena`**, **`rdf-rdf4j`**, **`rdf-sparql`**, …) at **`0.2.0`** or via the BOM.
- **`sparql-lang`** when you use Kotlin **`select {}`** SPARQL builders ([architecture](../concepts/architecture.md#dependency-profiles-gradle)).
- **`shacl-dsl`** (`rdf-shacl-dsl`) when you use the **`shacl {}`** shapes DSL or **`Rdf.shacl`** (pulls **`sparql-lang`** transitively).

## Recipes

### Discover registered providers and variants

```kotlin
import com.geoknoesis.kastor.rdf.RdfProviderRegistry

RdfProviderRegistry.discoverProviders().forEach { provider ->
    provider.variants().forEach { variant ->
        println("${provider.id}:${variant.id} — ${variant.description}")
    }
}
```

### Open a SPARQL repository

Remote endpoints require a **`location`** option (threaded from **`Rdf.repository { location = "…" }`** or set on **`RdfConfig`**):

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfConfig
import com.geoknoesis.kastor.rdf.RdfProviderRegistry

val repo = RdfProviderRegistry.create(
    RdfConfig(
        providerId = "sparql",
        variantId = "sparql",
        options = mapOf("location" to "https://dbpedia.org/sparql"),
    ),
)
```

### Insert a triple with SPARQL UPDATE

```kotlin
import com.geoknoesis.kastor.rdf.UpdateQuery

repo.update(UpdateQuery("INSERT DATA { <urn:s> <urn:p> \"o\" . }"))
```

### Insert triples with the Kotlin DSL

```kotlin
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice"
    alice has FOAF.age with 30
}
```

### Load Turtle from a file into the default graph

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.RdfFormat

val graph = Rdf.parseFromFile("./data.ttl", RdfFormat.TURTLE)
repo.editDefaultGraph().addTriples(graph.getTriples())
```

### Export JSON-LD

```kotlin
import com.geoknoesis.kastor.rdf.RdfFormat

val jsonLd = repo.defaultGraph.serialize(RdfFormat.JSON_LD)
```

### SPARQL: English labels

```sparql
SELECT ?s ?label WHERE {
  ?s rdfs:label ?label
  FILTER (langMatches(lang(?label), "EN"))
}
```

### SPARQL: Named graph only

```sparql
CONSTRUCT { ?s ?p ?o }
WHERE {
  GRAPH <urn:g> { ?s ?p ?o }
}
```

## Related

- [How to Parse RDF](how-to-parse-rdf.md)
- [How to Serialize RDF](how-to-serialize-rdf.md)
- [How to Use Datasets](how-to-use-datasets.md)
