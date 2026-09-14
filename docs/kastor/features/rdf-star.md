# RDF-star (legacy) and RDF 1.2 triple terms in Kastor

> **Kastor 0.2.0 update.** Kastor now follows the W3C RDF 1.2 model: triple
> terms (`<<( s p o )>>`) are object-position-only and metadata is attached
> through `rdf:reifies`. The RDF-star idioms below still parse on the
> Jena/RDF4J side because both libraries' parsers accept the legacy syntax,
> but new code should use the RDF 1.2 reifier pattern documented in
> [RDF 1.2 in Kastor](../concepts/rdf-1.2.md). The migration guide is
> [here](../guides/migrating-to-rdf-1.2.md).

Kastor lets you make statements about statements with RDF 1.2 triple terms and reifiers (the successor of RDF-star). This supports metadata modeling, provenance tracking and confidence scores.

## 🎯 Overview

Support in Kastor includes:

- **Triple terms**: `TripleTerm` values (`quoted(triple)`), allowed only in object position
- **Reifiers**: `reifies(...)` in the graph and repository DSLs emits `_:r rdf:reifies <<( s p o )>>`
- **SPARQL 1.2 functions**: `TRIPLE`, `isTRIPLE`, `SUBJECT`, `PREDICATE`, `OBJECT`
- **Query DSL**: `quotedTriple(...)` reified-triple patterns and `ReifierPatternAst` for bound reifiers (see [SPARQL fundamentals](../concepts/sparql-fundamentals.md))
- **Provider capabilities**: `supportsTripleTerms` and `rdfVersion` on `ProviderCapabilities`

All bundled stores hold triple terms (`supportsTripleTerms = true`): `jena`, `rdf4j` and the graph-only `memory` provider. Querying them with SPARQL needs `jena` or `rdf4j`, because the `memory` provider has no SPARQL engine.

## 🚀 Key Concepts

### Reified Triples

In RDF 1.2 Turtle, `<< … >>` is shorthand for a reifier:

```turtle
PREFIX : <http://example.org/>

:alice :knows :bob .
<< :alice :knows :bob >> :certainty 0.9 ;
                         :source :wikipedia .
```

This means:
- A regular, asserted triple: `:alice :knows :bob`
- A reifier (a blank node) with `rdf:reifies <<( :alice :knows :bob )>>`, carrying the certainty and source

The reifier does **not** assert the triple; the first line does.

### Triple Terms

A triple term `<<( s p o )>>` is an RDF term that denotes a triple. RDF 1.2 allows it only in the **object** position, which is why metadata hangs off a reifier rather than off the triple term itself. In Kotlin, `TripleTerm(RdfTriple(s, p, o))` (or `quoted(triple)`) creates one.

## 🎨 DSL Integration

### Annotating a Triple

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD

val ex = "http://example.org/"
val alice = iri("${ex}alice")
val bob = iri("${ex}bob")
val knows = iri("${ex}knows")

val repo = Rdf.repository {
    providerId = "jena"
    variantId = "memory"
}

repo.add {
    // Regular triple
    alice has knows with bob

    // Reifier with metadata: _:r rdf:reifies <<( alice knows bob )>>
    reifies(alice, knows, bob) { r ->
        r - iri("${ex}certainty") - 0.9
        r - iri("${ex}source") - iri("${ex}wikipedia")
        r - iri("${ex}date") - Literal("2024-01-15", XSD.date)
    }
}
```

`reifies(...)` returns the reifier, so you can keep adding triples to it later. Pass `reifier = iri("...")` to use an IRI instead of a fresh blank node:

```kotlin
repo.add {
    val claim = reifies(alice, knows, bob, reifier = iri("${ex}claim1"))
    claim - iri("${ex}author") - iri("${ex}system")
}
```

The same `reifies` function is available inside `Rdf.graph { }`.

### Statements About Annotations

A reifier is an ordinary resource, so metadata about an annotation is just more triples about that resource. You can also reify a triple whose subject is the reifier:

```kotlin
repo.add {
    val claim = reifies(alice, knows, bob, reifier = iri("${ex}claim1"))
    claim - iri("${ex}certainty") - 0.9

    reifies(claim, iri("${ex}certainty"), 0.9.toLiteral()) { r ->
        r - iri("${ex}verifiedBy") - iri("${ex}system")
    }
}
```

## 🔍 SPARQL 1.2 Functions

The query strings below assume `PREFIX : <http://example.org/>` and `PREFIX rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>`.

### TRIPLE Function

Creates a triple term from subject, predicate, and object:

```kotlin
val query = """
    SELECT ?tripleTerm WHERE {
        BIND(TRIPLE(:alice, :knows, :bob) AS ?tripleTerm)
    }
"""
```

### isTRIPLE Function

Checks if a term is a triple term:

```kotlin
val query = """
    SELECT ?term ?isTriple WHERE {
        ?reifier rdf:reifies ?term .
        BIND(isTRIPLE(?term) AS ?isTriple)
    }
"""
```

### Component Functions

Extract components from triple terms:

```kotlin
val query = """
    SELECT ?subject ?predicate ?object ?certainty WHERE {
        ?reifier rdf:reifies ?tripleTerm ;
                 :certainty ?certainty .
        BIND(SUBJECT(?tripleTerm) AS ?subject)
        BIND(PREDICATE(?tripleTerm) AS ?predicate)
        BIND(OBJECT(?tripleTerm) AS ?object)
    }
"""
```

## 📊 Provider Capabilities

### Checking Triple-Term Support

```kotlin
val capabilities = repo.getCapabilities()

if (capabilities.supportsTripleTerms) {
    println("Repository supports RDF 1.2 triple terms (RDF ${capabilities.rdfVersion})")
} else {
    println("RDF 1.1 store: triple terms are not supported")
}
```

### Capability Discovery

```kotlin
// Find registered providers whose default variant supports triple terms
val tripleTermProviders = RdfProviderRegistry.getAllProviders().filter {
    it.getCapabilities(it.defaultVariantId()).supportsTripleTerms
}
println("Providers supporting triple terms: ${tripleTermProviders.map { it.id }}")
```

### Service Description Integration

The bundled providers do not generate service descriptions themselves; build one from capabilities with `SparqlServiceDescriptionGenerator` (module `rdf-sparql-lang`):

```kotlin
import com.geoknoesis.kastor.rdf.sparql.KastorSparqlVocabulary
import com.geoknoesis.kastor.rdf.sparql.SparqlServiceDescriptionGenerator

val description = SparqlServiceDescriptionGenerator(
    "http://example.org/sparql",
    repo.getCapabilities()
).generateServiceDescription()

// The flag is a Kastor term (https://kastor.geoknoesis.com/ns/sparql#supportsRdfStar)
val advertisesRdfStar = description.getTriples().any { triple ->
    triple.predicate == KastorSparqlVocabulary.supportsRdfStar && triple.obj == boolean(true)
}
println("Service description advertises RDF-star support: $advertisesRdfStar")
```

## 🎯 Query Patterns

### Basic Queries

```kotlin
// Find all statements with certainty information
val query = """
    SELECT ?subject ?predicate ?object ?certainty WHERE {
        << ?subject ?predicate ?object >> :certainty ?certainty
    }
"""
```

### Filtering by Metadata

```kotlin
// Find high-confidence statements
val query = """
    SELECT ?subject ?predicate ?object ?certainty WHERE {
        << ?subject ?predicate ?object >> :certainty ?certainty .
        FILTER(?certainty > 0.8)
    }
"""
```

### Aggregating Metadata

```kotlin
// Average certainty by predicate
val query = """
    SELECT ?predicate (AVG(?certainty) AS ?avgCertainty) WHERE {
        << ?subject ?predicate ?object >> :certainty ?certainty
    }
    GROUP BY ?predicate
"""
```

### Several Metadata Properties

```kotlin
// Bind the reifier once to read several annotations of the same statement
val query = """
    SELECT ?subject ?predicate ?object ?certainty ?source ?date WHERE {
        ?reifier rdf:reifies <<( ?subject ?predicate ?object )>> ;
                 :certainty ?certainty ;
                 :source ?source ;
                 :date ?date .
    }
"""
```

### Conditional Queries

```kotlin
// Find statements that are either certain or from Wikipedia
val query = """
    SELECT ?subject ?predicate ?object ?reason WHERE {
        << ?subject ?predicate ?object >> ?metadata ?value .
        FILTER(
            (?metadata = :certainty && ?value > 0.9) ||
            (?metadata = :source && ?value = :wikipedia)
        )
        BIND(
            IF(?metadata = :certainty, "high certainty", "wikipedia source")
            AS ?reason
        )
    }
"""
```

## 🔧 Advanced Use Cases

Provenance, confidence, temporal and contextual metadata all use the same pattern: one reifier per annotated statement.

```kotlin
repo.add {
    alice has knows with bob

    reifies(alice, knows, bob) { r ->
        // Provenance
        r - iri("${ex}source") - iri("${ex}wikipedia")
        r - iri("${ex}extractedBy") - iri("${ex}system")
        // Confidence
        r - iri("${ex}certainty") - 0.9
        // Temporal validity
        r - iri("${ex}validFrom") - Literal("2024-01-01", XSD.date)
        r - iri("${ex}validUntil") - Literal("2024-12-31", XSD.date)
        // Context
        r - iri("${ex}context") - iri("${ex}professional")
    }
}
```

## 🎨 Best Practices

1. **Choose a provider with SPARQL.** All bundled stores hold triple terms, but querying them needs `jena` or `rdf4j`; the `memory` provider is graph-only.
2. **Assert explicitly.** A reifier does not assert its triple. Add the triple separately when it should be part of the data.
3. **Use consistent metadata properties.** Attach all annotations of one statement to the same reifier, and prefer well-known vocabularies (for example PROV-O or Dublin Core terms).
4. **Keep triple terms in object position.** RDF 1.2 forbids triple terms as subjects; use a reifier instead. See the [migration guide](../guides/migrating-to-rdf-1.2.md).
5. **Bound queries.** Filter and `LIMIT` metadata queries as you would any other query.

## 📖 Complete Example

```kotlin
import com.geoknoesis.kastor.rdf.*
import com.geoknoesis.kastor.rdf.vocab.XSD

fun rdfStarExample() {
    val ex = "http://example.org/"
    val alice = iri("${ex}alice")
    val bob = iri("${ex}bob")
    val charlie = iri("${ex}charlie")
    val knows = iri("${ex}knows")
    val certainty = iri("${ex}certainty")
    val source = iri("${ex}source")

    val repo = Rdf.repository {
        providerId = "jena"
        variantId = "memory"
    }
    if (!repo.getCapabilities().supportsTripleTerms) {
        println("Repository does not support RDF 1.2 triple terms")
        return
    }

    repo.add {
        alice has knows with bob
        alice has knows with charlie
        bob has knows with alice

        reifies(alice, knows, bob) { r ->
            r - certainty - 0.9
            r - source - iri("${ex}wikipedia")
        }
        reifies(alice, knows, charlie) { r ->
            r - certainty - 0.7
            r - source - iri("${ex}linkedin")
        }
        reifies(bob, knows, alice) { r ->
            r - certainty - 0.8
            r - source - iri("${ex}wikipedia")
        }
    }

    val query = """
        PREFIX : <http://example.org/>
        SELECT ?subject ?object ?certainty ?source WHERE {
            ?reifier <http://www.w3.org/1999/02/22-rdf-syntax-ns#reifies> <<( ?subject :knows ?object )>> ;
                     :certainty ?certainty ;
                     :source ?source .
            FILTER(?certainty > 0.7)
        }
        ORDER BY DESC(?certainty)
    """

    repo.select(SparqlSelectQuery(query)).forEach { binding ->
        val subject = binding.get("subject") as? Iri
        val obj = binding.get("object") as? Iri
        val score = binding.getDouble("certainty")
        val from = binding.get("source") as? Iri
        println("$subject knows $obj (certainty: $score, source: $from)")
    }

    val aggregateQuery = """
        PREFIX : <http://example.org/>
        SELECT ?source (AVG(?certainty) AS ?avgCertainty) (COUNT(?reifier) AS ?statementCount) WHERE {
            ?reifier :certainty ?certainty ;
                     :source ?source .
        }
        GROUP BY ?source
    """

    repo.select(SparqlSelectQuery(aggregateQuery)).forEach { binding ->
        println("Source ${binding.get("source")}: avg certainty ${binding.getDouble("avgCertainty")}, " +
            "${binding.getInt("statementCount")} statements")
    }
}
```

## 🔗 Related Documentation

- [SPARQL 1.2 Support](sparql-1.2.md)
- [Provider Capabilities](provider-capabilities.md)
- [Service Description](service-description.md)
- [Enhanced Providers](enhanced-providers.md)
- [Query DSL tutorial](../guides/kastor-query-dsl-tutorial.md)

## 📞 Support

For questions about RDF-star support in Kastor:

- **Email**: stephanef@geoknoesis.com
- **Issues**: GitHub Issues
- **Documentation**: [Kastor Docs](https://docs.kastor.org)

---

*Kastor RDF-star support is developed by [GeoKnoesis LLC](https://geoknoesis.com) and maintained by Stephane Fellah.*
