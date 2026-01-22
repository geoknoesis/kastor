# How to Use Transactions

{% include version-banner.md %}

## What you'll learn
- Execute atomic write operations
- Use read-only transactions when supported

## Step 1: Write inside a transaction

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri

val repo = Rdf.memory()

repo.transaction {
    add {
        val alice = iri("http://example.org/alice")
        alice has "http://xmlns.com/foaf/0.1/name" with "Alice Johnson"
    }
}
```

## Step 2: Read inside a read-only transaction

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery

repo.readTransaction {
    val results = select(SparqlSelectQuery("""
        SELECT ?name WHERE {
            <http://example.org/alice> <http://xmlns.com/foaf/0.1/name> ?name .
        }
    """))

    results.forEach { row ->
        println(row.getString("name"))
    }
}
```

## Expected output

```
Alice Johnson
```

## Notes
- Use `transaction` for atomic writes.
- Use `readTransaction` for read-only operations when supported by the provider.

