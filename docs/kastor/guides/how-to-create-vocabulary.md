# How to Create a Custom Vocabulary

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** namespaces, **`Vocabulary`**, stable IRIs → [RDF fundamentals](../concepts/rdf-fundamentals.md), [**Glossary**](../concepts/glossary.md). **Reference:** `Vocabulary` → [Core API](../api/core-api.md).

## Problem

- Define a **custom vocabulary** as a Kotlin **`object`**, expose typed **`Iri`** constants for classes and properties, and use those constants in the graph DSL and SPARQL safely.

## Prerequisites

- **`rdf-core`** on the classpath ( **`Vocabulary`** lives alongside the RDF DSL).

## Steps

### Step 1: Create a vocabulary object

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

### Step 2: Use the vocabulary in the DSL

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

### Step 3: Use constants in SPARQL

```kotlin
val results = repo.select(SparqlSelectQuery("""
    SELECT ?name WHERE {
        ?person ${EX.name} ?name .
    }
"""))
```

## Validation

Run the **`SELECT`** and confirm bindings use the expected lexical forms (here, **`"Alice"`** when **`EX.name`** points at **`http://example.org/vocab/name`**).

## Troubleshooting

- **Extra allocations:** Keep **`by lazy`** on **`term(...)`** so IRIs are built once per property/class.
- **Unstable IRIs:** Changing **`namespace`** breaks stored data and linked data clients—treat it like a schema version boundary.

## Related

- [How to Parse RDF](how-to-parse-rdf.md)
- [How to Create SHACL Shapes](how-to-create-shacl-shapes.md)
- [Kastor Gen](../../kastor-gen/README.md) when generating vocabularies from ontology sources
