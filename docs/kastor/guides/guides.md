## Guides

### Transactions
- Use `transaction { ... }` for atomic operations.
- Use `readTransaction { ... }` for read-only work when the provider supports it.

```kotlin
repo.transaction {
  update(UpdateQuery("INSERT DATA { <urn:s> <urn:p> 'o' }"))
}
```

### Querying
- `select` returns a `QueryResult` you can iterate.
- `construct` returns a `Sequence<RdfTriple>` for streaming.
- `ask` returns a Boolean.

```kotlin
val rows = repo.select(SparqlSelectQuery("SELECT ?s WHERE { ?s ?p ?o }"))
rows.forEach { row -> println(row.get("s")) }

val triples = repo.construct(SparqlConstructQuery("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }"))
println(triples.count())
```

### Named graphs
```kotlin
val g = iri("urn:g")
repo.transaction {
  editGraph(g).addTriple(RdfTriple(iri("urn:s"), iri("urn:p"), literal("o")))
}

val rows = repo.select(SparqlSelectQuery("SELECT ?s WHERE { GRAPH <urn:g> { ?s <urn:p> ?o } }"))
```




