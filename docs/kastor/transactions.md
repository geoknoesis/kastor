## Transactions

Use transactions to group writes and ensure consistency.

### Common pattern
```kotlin
repo.beginTransaction()
try {
  repo.update("INSERT DATA { <urn:s> <urn:p> 'o' }")
  repo.commit()
} catch (t: Throwable) {
  repo.rollback()
  throw t
} finally {
  repo.end()
}
```

### Provider behavior
- **Jena**: If you omit an explicit transaction, the repository opens short-lived read/write transactions around operations. For batching, wrap writes in an explicit transaction.
- **RDF4J**: If not inside `beginTransaction()`, operations run in auto-commit. Inside a transaction you control commit/rollback/end.
- **SPARQL (remote)**: Transactions are no-ops. Updates are sent as standalone HTTP requests.

