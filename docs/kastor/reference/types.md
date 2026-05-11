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

### MutableRdfGraph
Mutable RDF graph operations. Provides both read and write operations.
`addTriple(...)`, `addTriples(...)`, `removeTriple(...)`, `removeTriples(...)`, `clear()`
Extends `RdfGraph` to provide both read and write capabilities.

### Query results
- `ResultBinding(name: String, value: RdfTerm)`
- `RdfSelectRow(bindings: List<ResultBinding>)` with operator `get(name: String): RdfTerm?`
- `ResultSet(rows: List<RdfSelectRow>)`




