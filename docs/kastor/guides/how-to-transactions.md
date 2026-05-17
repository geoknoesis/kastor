# How to Use Transactions

{% include version-banner.md %}

> **Documentation mode: How-to guide.** **Explanation:** when providers offer atomic writes vs best-effort batches → [Architecture](../concepts/architecture.md). **Reference:** `transaction`, `readTransaction` → [Core API](../api/core-api.md).

## Problem

- Run **writes** through `transaction { }` so they succeed or fail together when the provider supports it, and use **`readTransaction`** for read-only scopes where available.

## Prerequisites

- **`rdf-core`** and a repository implementation that advertises transaction support if you need real atomicity (check **`getCapabilities()`**). In-memory and embedded stores typically cooperate; remote SPARQL often does not.

## Steps

### Step 1: Write inside a transaction

```kotlin
import com.geoknoesis.kastor.rdf.Rdf
import com.geoknoesis.kastor.rdf.iri
import com.geoknoesis.kastor.rdf.vocab.FOAF

val repo = Rdf.memory()

repo.transaction {
    add {
        val alice = iri("http://example.org/alice")
        alice has FOAF.name with "Alice Johnson"
    }
}
```

### Step 2: Read inside a read-only transaction

```kotlin
import com.geoknoesis.kastor.rdf.SparqlSelectQuery

repo.readTransaction {
    val results = select(SparqlSelectQuery("""
        SELECT ?name WHERE {
            <http://example.org/alice> ${FOAF.name} ?name .
        }
    """))

    results.forEach { row ->
        println(row.getString("name"))
    }
}
```

## Validation

You should see:

```
Alice Johnson
```

## Troubleshooting

- **SPARQL / HTTP repositories:** Many providers implement `transaction` as a simple sequential block without rollback or isolation. Do not rely on ACID semantics unless the capability flags say otherwise.
- **Errors mid-block:** Behavior on failure is provider-specific; consult the backing implementation or run critical workflows against a store with explicit transaction support.

## Related

- [How to Use Datasets](how-to-use-datasets.md)
- [Error handling](error-handling.md)
