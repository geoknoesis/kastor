## Cookbook

### Discover Providers and Variants
```kotlin
val providers = RdfProviderRegistry.discoverProviders()
providers.forEach { provider ->
    provider.variants().forEach { variant ->
        println("${provider.id}:${variant.id} — ${variant.description}")
    }
}
```

### Create Repository with Required Options
```kotlin
// SPARQL endpoints require a location option
val repo = RdfProviderRegistry.create(
    RdfConfig(
        providerId = "sparql",
        variantId = "sparql",
        options = mapOf("location" to "http://dbpedia.org/sparql")
    )
)
```

### Insert a triple
```kotlin
repo.update(UpdateQuery("INSERT DATA { <urn:s> <urn:p> 'o' }"))
```

### Create triples using the DSL
```kotlin
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    val alice = iri("http://example.org/alice")
    alice has FOAF.name with "Alice"
    alice has FOAF.age with 30
}
```

### Load Turtle from file
```kotlin
val graph = Rdf.parseFromFile("./data.ttl", RdfFormat.TURTLE)
repo.editDefaultGraph().addTriples(graph.getTriples())
```

### Export JSON-LD
```kotlin
val jsonLd = repo.defaultGraph.serialize(RdfFormat.JSON_LD)
```

### Labels in English
```sparql
SELECT ?s ?label WHERE {
  ?s rdfs:label ?label
  FILTER (langMatches(lang(?label), 'EN'))
}
```

### Named graph only
```sparql
CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <urn:g> { ?s ?p ?o } }
```




