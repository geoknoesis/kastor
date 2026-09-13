## Formats

Parse with `Rdf.parse` (or a provider's `parseGraph`) and serialize with `RdfGraph.serialize`, using format names:

Supported names (case-insensitive): `TURTLE`, `TTL`, `NTRIPLES`, `N-TRIPLES`, `NT`, `RDFXML`, `RDF/XML`, `JSONLD`, `JSON-LD`, `TRIG`, `NQUADS`, `N-QUADS`, `NQ`.

### Reading
```kotlin
val graph = Rdf.parse(turtleString, "TURTLE")
repo.addTriples(null, graph.getTriples())          // null = default graph
```

### Writing
```kotlin
val jsonld = repo.defaultGraph.serialize("JSONLD")
```

Notes:
- Jena and RDF4J do the actual parsing/serialization under the hood.
- For named graphs, pass a non-null `Iri` to `addTriples` and serialize `repo.getGraph(name)`.
- `parseGraph` accepts triple formats only; TriG and N-Quads are rejected with `RdfFormatException`. Parse them as a dataset instead (`Rdf.parseDataset(...)`).




