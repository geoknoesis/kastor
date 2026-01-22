## Hello, RDF

{% include version-banner.md %}

This tutorial assumes no prior RDF knowledge. You will create a graph, query it, and print results.

## What you'll build

You will model one person and one organization, then query the person's name and employer.

## Step 1: Create an in-memory repository

```kotlin
import com.geoknoesis.kastor.rdf.Rdf

val repo = Rdf.memory()
```

## Step 2: Add a few triples (subject–predicate–object)

```kotlin
import com.geoknoesis.kastor.rdf.iri

repo.add {
    val alice = iri("http://example.org/alice")
    val org = iri("http://example.org/org/acme")

    alice has "http://xmlns.com/foaf/0.1/name" with "Alice Johnson"
    alice has "http://xmlns.com/foaf/0.1/age" with 30
    alice has "http://xmlns.com/foaf/0.1/worksFor" with org

    org has "http://xmlns.com/foaf/0.1/name" with "Acme Corp"
}
```

## Step 3: Query the graph

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery

val results = repo.select(SparqlSelectQuery("""
    SELECT ?name ?orgName WHERE {
        <http://example.org/alice> <http://xmlns.com/foaf/0.1/name> ?name .
        <http://example.org/alice> <http://xmlns.com/foaf/0.1/worksFor> ?org .
        ?org <http://xmlns.com/foaf/0.1/name> ?orgName .
    }
"""))

results.forEach { row ->
    val name = row.getString("name")
    val orgName = row.getString("orgName")
    println("$name works for $orgName")
}
```

## Step 4: Close the repository

```kotlin
repo.close()
```

## Expected output

```
Alice Johnson works for Acme Corp
```

You’ve created your first RDF data and queried it successfully.




