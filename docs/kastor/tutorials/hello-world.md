## Hello, RDF

{% include version-banner.md %}

> **Documentation mode: Tutorial** — no RDF background assumed. For vocabulary definitions (**triple**, **IRI**), keep the [**Glossary**](../concepts/glossary.md) open. For task-only workflows later, use [**How-to guides**](../guides/README.md).

### Goal

Create an in-memory graph, run one **SPARQL** `SELECT`, and print a human-readable line.

### Prerequisites

- JDK **17**, Gradle + Kotlin (see [Installation](../getting-started/installation.md))
- Dependencies: `rdf-core` plus a provider such as `rdf-jena` or `rdf-rdf4j` at **`0.2.0`** (see [Getting Started](../getting-started/getting-started.md))

## What you'll build

You will model one person and one organization, then query the person's name and employer.

### Step 1: Create an in-memory repository

```kotlin
import com.geoknoesis.kastor.rdf.Rdf

val repo = Rdf.memory()
```

### Step 2: Add a few triples (subject–predicate–object)

```kotlin
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF

repo.add {
    val alice = iri("http://example.org/alice")
    val org = iri("http://example.org/org/acme")

    alice has FOAF.name with "Alice Johnson"
    alice has FOAF.age with 30
    alice has FOAF.worksFor with org

    org has FOAF.name with "Acme Corp"
}
```

### Step 3: Query the graph

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery

val results = repo.select(SparqlSelectQuery("""
    SELECT ?name ?orgName WHERE {
        <http://example.org/alice> ${FOAF.name} ?name .
        <http://example.org/alice> ${FOAF.worksFor} ?org .
        ?org ${FOAF.name} ?orgName .
    }
"""))

results.forEach { row ->
    val name = row.getString("name")
    val orgName = row.getString("orgName")
    println("$name works for $orgName")
}
```

### Step 4: Close the repository

```kotlin
repo.close()
```

## Verify

You should see:

```
Alice Johnson works for Acme Corp
```

## Next steps

- **Explanation:** [RDF Fundamentals](../concepts/rdf-fundamentals.md), [SPARQL Fundamentals](../concepts/sparql-fundamentals.md)
- **How-to:** [Parse RDF](../guides/how-to-parse-rdf.md), [Validate with SHACL](../guides/how-to-validate-shacl.md)
- **Reference:** [Core API](../api/core-api.md)

You’ve created your first RDF data and queried it successfully.





