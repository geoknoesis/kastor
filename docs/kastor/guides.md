## Guides

### Transactions
- Call `beginTransaction(write = true)` before batched writes; follow with `commit()` or `rollback()`, then `end()`.
- Reads auto-open a read transaction in Jena; RDF4J uses a connection per operation unless inside an explicit transaction.

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

### Querying
- `querySelect` returns rows and variable->term bindings.
- `queryConstruct` returns an `RdfGraph` you can iterate.
- `queryAsk` returns a Boolean.

```kotlin
val rows = repo.querySelect("SELECT ?s WHERE { ?s ?p ?o }")
rows.rows.forEach { row -> println(row.vars["s"]) }

val g = repo.queryConstruct("CONSTRUCT { ?s ?p ?o } WHERE { ?s ?p ?o }")
println(g.size())
```

### Importing and exporting graphs
```kotlin
val turtle = """
@prefix ex: <urn:ex:> .
ex:s ex:p "o" .
""".trimIndent()
repo.beginTransaction()
repo.readGraph(null, turtle.byteInputStream(), "TURTLE")
repo.commit()
repo.end()

val out = java.io.ByteArrayOutputStream()
repo.writeGraph(null, out, "TURTLE")
println(out.toString("UTF-8"))
```

### Named graphs
```kotlin
val g = Rdf.iri("urn:g")
repo.beginTransaction()
repo.addTriple(g, RdfTriple(Rdf.iri("urn:s"), Rdf.iri("urn:p"), Rdf.literal("o")))
repo.commit(); repo.end()

val rows = repo.querySelect("SELECT ?s WHERE { GRAPH <urn:g> { ?s <urn:p> ?o } }")
```

