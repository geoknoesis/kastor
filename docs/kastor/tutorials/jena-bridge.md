# Jena-backed repository (portable API)

{% include version-banner.md %}

> **Documentation mode: Tutorial** — patterns using **`providerId = "jena"`** while staying on **`Rdf` / `RdfRepository`** types. For provider comparison, see **Explanation:** [Providers](../providers/jena.md).

### Goal

Use the **Jena** engine behind Kastor’s portable surface: create a repo, add triples, parse/serialize, run **SPARQL**.

### Prerequisites

- `rdf-core` + **`rdf-jena`** on the classpath (`0.2.0` with other Kastor artifacts).

### Step 1: Create a Jena repository

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

### Step 2: Parse RDF (provider-agnostic API)

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

### Step 3: Serialize RDF

```kotlin
val turtleOut = repo.defaultGraph.serialize(RdfFormat.TURTLE)
val jsonLdOut = repo.defaultGraph.serialize(RdfFormat.JSON_LD)
```

### Step 4: Query with vocabulary constants

```kotlin
val results = repo.select(
    SparqlSelectQuery("SELECT ?name WHERE { ?s ${FOAF.name} ?name }")
)
results.forEach { binding ->
    println(binding.getString("name"))
}
```

## Verify

Printed output includes **`Alice`** from the SPARQL step.

## Next steps

- **How-to:** [Parse RDF](../guides/how-to-parse-rdf.md), [Serialize RDF](../guides/how-to-serialize-rdf.md)
- **Reference:** [Jena provider](../providers/jena.md)

## Notes

If you need **direct Jena `Model` APIs**, keep them in application code and round-trip through standard RDF formats or project-specific bridges. These docs emphasize **`RdfRepository`** so code stays easier to move to RDF4J or other providers.
