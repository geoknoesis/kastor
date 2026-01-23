# Jena Provider Interoperability

Kastor documentation avoids provider-specific bridge APIs. Use the provider-agnostic
core API (`Rdf`, `RdfFormat`, `RdfRepository`) and vocabulary constants for portable
code. This page shows Jena integration using only the core surface.

## Create a Jena Repository

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.FOAF
import com.geoknoesis.kastor.rdf.vocab.RDF

val repo = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}

repo.add {
    val alice = iri("http://example.org/alice")
    alice - RDF.type - FOAF.Person
    alice - FOAF.name - "Alice"
}
```

## Parse RDF (Provider-Agnostic)

```kotlin
val turtle = """
    @prefix foaf: <http://xmlns.com/foaf/0.1/> .
    @prefix ex: <http://example.org/> .

    ex:alice a foaf:Person ;
        foaf:name "Alice" .
""".trimIndent()

val graph = Rdf.parse(turtle, RdfFormat.TURTLE)
repo.editDefaultGraph().addTriples(graph.getTriples())
```

## Serialize RDF (Typed Format)

```kotlin
val turtleOut = repo.defaultGraph.serialize(RdfFormat.TURTLE)
val jsonLdOut = repo.defaultGraph.serialize(RdfFormat.JSON_LD)
```

## Query with Vocabulary Constants

```kotlin
val results = repo.select(
    SparqlSelectQuery("SELECT ?name WHERE { ?s ${FOAF.name} ?name }")
)
results.forEach { binding ->
    println(binding.getString("name"))
}
```

## Notes

- If you need direct Jena APIs, keep them in application code and convert data using
  standard RDF serialization formats. The documentation intentionally focuses on
  provider-agnostic APIs to preserve portability.


