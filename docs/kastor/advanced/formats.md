## Formats

Use `readGraph`/`writeGraph` with format names:

Supported names (case-insensitive): `TURTLE`, `TTL`, `NTRIPLES`, `N-TRIPLES`, `NT`, `RDFXML`, `RDF/XML`, `JSONLD`, `JSON-LD`, `TRIG`, `NQUADS`, `N-QUADS`, `NQ`.

### Reading
```kotlin
repo.beginTransaction()
repo.readGraph(null, turtleString.byteInputStream(), "TURTLE")
repo.commit(); repo.end()
```

### Writing
```kotlin
val out = java.io.ByteArrayOutputStream()
repo.writeGraph(null, out, "JSONLD")
val jsonld = out.toString("UTF-8")
```

Notes:
- Jena and RDF4J do the actual parsing/serialization under the hood.
- For named graphs, pass a non-null `Iri` as the first parameter.




