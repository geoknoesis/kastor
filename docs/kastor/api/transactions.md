## Transactions

Use transactions to group writes and ensure consistency.

### Common pattern
```kotlin
repo.transaction {
  update(UpdateQuery("INSERT DATA { <urn:s> <urn:p> 'o' }"))
  addTriple(iri("urn:s"), iri("urn:p2"), string("o2"))
}   // committed when the block returns; rolled back if it throws

repo.readTransaction {
  val rows = select(SparqlSelectQuery("SELECT * WHERE { ?s ?p ?o }"))
}
```

### Provider behavior
- **Jena / RDF4J**: Outside `transaction { }`, each write runs in its own short transaction. Batch calls (`addTriples`, `removeTriples`) use one transaction, but many per-triple writes cost one transaction each, so batch them or wrap them in `transaction { }`.
- **SPARQL (remote)**: Not supported. `transaction { }` and `readTransaction { }` throw `UnsupportedOperationException`, and each update is a standalone HTTP request.




