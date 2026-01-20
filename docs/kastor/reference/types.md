## Types

### RdfTerm hierarchy
- `Iri(value: String)`
- `BlankNode(id: String)`
- `Literal(lexical: String, lang: String? = null, datatype: Iri? = null)`
- `TripleTerm(triple: RdfTriple)` (RDF-star)

### RdfTriple
`RdfTriple(subject: RdfTerm, predicate: Iri, object: RdfTerm)`

### RdfGraph
Read-only graph view.
`getTriples(): List<RdfTriple>`, `hasTriple(triple: RdfTriple): Boolean`, `size(): Int`

### GraphEditor
Mutable graph operations.
`addTriple(...)`, `addTriples(...)`, `removeTriple(...)`, `removeTriples(...)`, `clear()`

### MutableRdfGraph
Combination of `RdfGraph` + `GraphEditor`.

### Query results
- `ResultBinding(name: String, value: RdfTerm)`
- `RdfSelectRow(bindings: List<ResultBinding>)` with operator `get(name: String): RdfTerm?`
- `ResultSet(rows: List<RdfSelectRow>)`




