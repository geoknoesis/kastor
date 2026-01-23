# How to Create a Custom Vocabulary

{% include version-banner.md %}

## What you'll learn
- Define a custom vocabulary as a Kotlin object
- Expose typed `Iri` constants for terms
- Use the vocabulary in DSL and SPARQL safely

## Step 1: Create a vocabulary object

```kotlin
import com.geoknoesis.kastor.rdf.Iri
import com.geoknoesis.kastor.rdf.vocab.Vocabulary

object EX : Vocabulary {
    override val namespace: String = "http://example.org/vocab/"
    override val prefix: String = "ex"

    // Classes
    val Person: Iri by lazy { term("Person") }
    val Organization: Iri by lazy { term("Organization") }

    // Properties
    val name: Iri by lazy { term("name") }
    val worksFor: Iri by lazy { term("worksFor") }
}
```

## Step 2: Use the vocabulary in the DSL

```kotlin
import com.geoknoesis.kastor.rdf.*

val repo = Rdf.memory()

repo.add {
    val alice = iri("http://example.org/alice")
    val acme = iri("http://example.org/org/acme")

    alice is EX.Person
    alice has EX.name with "Alice"
    alice has EX.worksFor with acme
    acme is EX.Organization
}
```

## Step 3: Use constants in SPARQL

```kotlin
val results = repo.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        ?person ${EX.name} ?name .
    }
"""))
```

## Notes
- Use `by lazy` so terms are created only when needed.
- Keep the namespace and prefix stable.
- Prefer vocabulary constants over string IRIs in application code.

